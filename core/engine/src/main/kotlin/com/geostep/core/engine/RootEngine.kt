package com.geostep.core.engine

import android.content.Context
import com.geostep.core.model.CheckResult
import com.geostep.core.model.ControlPlane
import com.geostep.core.model.EngineCapability
import com.geostep.core.model.EngineState
import com.geostep.core.model.InjectionEngine
import com.geostep.core.model.SimConfig
import com.geostep.core.model.StepCadence
import com.geostep.core.model.SimError
import com.geostep.core.model.RootSuMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Independent Root loader plus a backend-specific, nonce-checked system channel. */
class RootEngine(
    private val environment: RootEnvironment,
    private val channel: SystemHookControl,
    private val legacy: SystemHookControl,
    private val scope: CoroutineScope,
    private val fallback: InjectionEngine? = null,
    private val selfPackage: String = "",
) : InjectionEngine {
    constructor(context: Context, clock: Clock, scope: CoroutineScope, log: SimLogger,
                suPath: suspend () -> String = { "" }, suMode: suspend () -> RootSuMode = { RootSuMode.AUTO },
                fallback: InjectionEngine? = null) : this(
        RootDeployment(context, suPath, suMode),
        XposedEngine(context, clock, scope, log, ControlPlane.ROOT_PROVIDER, "root"),
        XposedEngine(context, clock, scope, log),
        scope,
        fallback,
        context.packageName,
    )

    private val current = MutableStateFlow<EngineState>(EngineState.Idle)
    private val operations = Mutex()
    @Volatile private var ownsSession = false
    init { scope.launch { channel.state().collect { if (ownsSession) current.value = it } } }
    override fun state(): StateFlow<EngineState> = current
    override suspend fun capability() = EngineCapability(ENGINE_ID, "Root 直连（系统定位）", false, false, true, true,
        startupTimeoutMs = 90_000)

    override suspend fun selfCheck(): List<CheckResult> {
        if (legacy.pingModule()) return listOf(CheckResult(CHECK_NAME, false, CONFLICT_REASON))
        if (channel.pingModule()) {
            val status = channel.statusSnapshot()
            val valid = status != null && status.processId > 1 && status.moduleVersion == environment.version &&
                RootReceipt(status.processId, status.instanceNonce, environment.version).matches(status)
            return listOf(CheckResult(CHECK_NAME, valid,
                if (valid) "Root 系统定位钩子已加载" else "Root 载荷版本或回执不匹配，请手动重启后重试"))
        }
        return environment.readiness() + CheckResult("Root 首次加载", true, "组件通过检查后，开始模拟时加载系统钩子")
    }

    /**
     * Root 直连只接管 system_server 的定位。目标 App 进程内的传感器需要单独注入载荷，
     * 注入后 App 侧走与 LSPosed 相同的 AppScopeHooks 路径（位置 + 步频都会生效）。
     */
    suspend fun injectClient(packageName: String): Result<Unit> {
        val deployment = environment as? RootDeployment
            ?: return Result.failure(IllegalStateException("当前 Root 组件不支持应用注入"))
        val result = deployment.injectClient(packageName)
        if (result.isSuccess) {
            injectedPackages += packageName
            // 注入成功即把该包纳入自动维护列表：目标 App 之后新起的进程会被自动补注入。
            targets = targets + packageName
            injectedProcesses.clear()
        }
        return result.map { }
    }

    /** 运行期间需要保持注入的目标包（App 侧同步过来）。 */
    @Volatile private var targets: Set<String> = emptySet()

    private val injectedProcesses = java.util.concurrent.ConcurrentHashMap<Int, String>()

    fun setTargets(packages: Set<String>) {
        val cleaned = packages.filter { it.isNotBlank() }.toSet()
        if (cleaned == targets) return
        targets = cleaned
        injectedProcesses.clear()
    }

    fun targetPackages(): Set<String> = targets

    /**
     * 目标 App 会不断新建进程（高德把定位放在 `:locationservice` 子进程里），
     * 所以运行期间要持续扫描并补注入，否则会一直拿到真实定位。
     */
    private suspend fun ensureProcesses(deployment: RootDeployment) {
        if (targets.isEmpty()) return
        for (pkg in targets) {
            for (pid in deployment.processesOf(pkg)) {
                if (injectedProcesses[pid] == pkg) continue
                if (deployment.injectProcess(pid, pkg)) {
                    injectedProcesses[pid] = pkg
                    injectedPackages += pkg
                }
            }
        }
        val alive = targets.flatMap { deployment.processesOf(it) }.toSet()
        injectedProcesses.keys.retainAll(alive)
    }

    private val injectedPackages = java.util.concurrent.CopyOnWriteArraySet<String>()

    /** 系统载荷不可用时的降级：由 App 写状态镜像，注入到目标进程的钩子照常工作。 */
    private var clientMode = false
    private var session = 0L
    private var bootCount = -1
    private var lastPublishAt = 0L
    @Volatile private var lastConfig: SimConfig? = null
    private var publishJob: kotlinx.coroutines.Job? = null

    override suspend fun start(cfg: SimConfig): Result<Unit> = operations.withLock {
        current.value = EngineState.Starting(System.nanoTime())
        val result = runCatching {
            check(!legacy.pingModule()) { CONFLICT_REASON }
            // 真实部署才具备「应用注入 / 状态镜像」能力；单元测试与其它实现走纯系统载荷路径。
            val deployment = environment as? RootDeployment
            session = System.currentTimeMillis()
            bootCount = deployment?.bootCount() ?: -1
            val system = if (deployment != null && !deployment.systemPayloadEnabled) {
                Result.failure<Unit>(IllegalStateException("系统载荷注入已关闭（部分 ROM 会崩溃），使用应用注入模式"))
            } else runCatching {
                val receipt = if (channel.pingModule()) {
                    val status = checkNotNull(channel.statusSnapshot())
                    check(status.moduleVersion == environment.version) { "已加载的 Root 载荷需手动重启后更新" }
                    RootReceipt(status.processId, status.instanceNonce, environment.version)
                } else environment.load()
                var confirmed = false
                repeat(25) {
                    if (!confirmed) {
                        confirmed = channel.pingModule() && receipt.matches(channel.statusSnapshot())
                        if (!confirmed) delay(200)
                    }
                }
                check(confirmed) { "Root 载荷未收到实际 system_server 的匹配回执" }
                // SET 可能已经到达服务端：即使回执丢失也要保留清理责任。
                ownsSession = true
                channel.start(cfg).getOrThrow()
            }
            if (system.isSuccess) {
                clientMode = false
                current.value = channel.state().value
            } else {
                // 部分 ROM 会拦截对 system_server 的 remote call（loader 报 unexpected signal）。
                // 此时改为「注入式 Root 模式」：状态由 App 自己写，目标进程内的钩子照常生效。
                val target = deployment
                    ?: throw (system.exceptionOrNull() ?: IllegalStateException("Root 载荷不可用"))
                target.publishState(cfg, session, bootCount).getOrThrow()
                clientMode = true
                lastPublishAt = System.currentTimeMillis()
                lastConfig = cfg
                // 有些 App（高德就把定位放在 :locationservice 子进程）的进程 ptrace 注入会超时，
                // 这时靠系统级模拟位置兜底：任何进程都能拿到位置，只是它们看不到"无痕"。
                if (selfPackage.isNotEmpty()) runCatching { target.allowMockLocation(selfPackage) }
                runCatching { fallback?.start(cfg) }
                startPublishLoop(deployment)
                current.value = EngineState.Running(System.currentTimeMillis(), cfg)
            }
            ownsSession = true
        }
        if (result.isFailure) {
            // Once a SET may have reached the service, retain responsibility for
            // cleanup even when its receipt is lost.
            current.value = EngineState.Failed(SimError.MODULE_UNREACHABLE, retryable = true,
                detail = result.exceptionOrNull()?.message)
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        }
        result
    }

    override suspend fun update(cfg: SimConfig): Result<Unit> {
        if (!ownsSession) return Result.failure(IllegalStateException("Root engine not started"))
        if (!clientMode) return channel.update(cfg)
        val deployment = environment as? RootDeployment
            ?: return Result.failure(IllegalStateException("当前 Root 组件不支持应用注入"))
        lastConfig = cfg
        val now = System.currentTimeMillis()
        runCatching { fallback?.update(cfg) }
        // 镜像有 15s 新鲜度窗口，这里 3s 刷一次即可，避免每秒 spawn 一次 su。
        if (now - lastPublishAt < 3_000L) return Result.success(Unit)
        return deployment.publishState(cfg, session, bootCount).onSuccess { lastPublishAt = now }
    }

    override suspend fun stop(): Result<Unit> = operations.withLock {
        val deployment = environment as? RootDeployment
        val result = when {
            clientMode && deployment != null -> deployment.clearState()
            channel.pingModule() -> channel.stop()
            ownsSession -> Result.failure(IllegalStateException("Root 系统通道不可达，尚未确认停止"))
            else -> Result.success(Unit)
        }
        if (result.isSuccess) {
            runCatching { fallback?.stop() }
            publishJob?.cancel()
            publishJob = null
            ownsSession = false
            clientMode = false
            current.value = EngineState.Idle
        }
        else current.value = EngineState.Failed(SimError.MODULE_UNREACHABLE, true, result.exceptionOrNull()?.message)
        result
    }
    /**
     * 注入式 Root 模式的状态镜像需要持续保鲜（模块只认 15 秒内的镜像），
     * 但 UI 驱动之外（例如自动化脚本）不一定有 tick，所以这里自带一个 3 秒的发布循环。
     */
    private fun startPublishLoop(deployment: RootDeployment) {
        publishJob?.cancel()
        publishJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(3_000)
                val cfg = lastConfig ?: continue
                if (!clientMode || !isActive) break
                deployment.publishState(cfg, session, bootCount).onSuccess { lastPublishAt = System.currentTimeMillis() }
                // 每 6 秒补注入一次新出现的进程（高德的定位进程常常是后起的）。
                if (ticks++ % 2 == 1) runCatching { ensureProcesses(deployment) }
            }
        }
    }

    suspend fun pingModule(): Boolean = channel.pingModule()
    fun statusSnapshot() = channel.statusSnapshot()

    /**
     * 回读校验：系统载荷路径委托给 backend 专属状态通道；注入式路径则检查系统状态镜像
     * 是否与期望一致，以及目标进程里的钩子库是否还在（进程重启后需要重新注入）。
     */
    suspend fun verifyApplied(cfg: SimConfig): List<CheckResult> {
        if (!clientMode) return channel.verifyApplied(cfg)
        val deployment = environment as? RootDeployment
            ?: return listOf(CheckResult("Root 状态镜像", false, "当前 Root 组件不支持应用注入"))
        val state = deployment.readState()
        val lat = state?.optDouble("lat", Double.NaN) ?: Double.NaN
        val lng = state?.optDouble("lng", Double.NaN) ?: Double.NaN
        val spd = state?.optDouble("spd", Double.NaN) ?: Double.NaN
        val cad = state?.optInt("cad", -1) ?: -1
        val enabled = state?.optInt("e", 0) == 1
        val distance = if (lat.isFinite() && lng.isFinite()) {
            InjectionVerifier.distanceMeters(cfg.position.lat, cfg.position.lng, lat, lng)
        } else Double.MAX_VALUE
        val expectedCadence = StepCadence.effective(cfg.speedMps, cfg.stepCadenceSpm)
        return listOf(
            CheckResult(
                "Root 状态镜像",
                enabled && distance <= InjectionVerifier.LOCATION_TOLERANCE_M &&
                    kotlin.math.abs(spd - cfg.speedMps) <= InjectionVerifier.SPEED_TOLERANCE_MPS &&
                    cad == expectedCadence,
                if (!enabled) "系统状态镜像未生效：请确认已给本应用授权 Root"
                else "镜像 lat=$lat lng=$lng spd=$spd cad=$cad（期望 $expectedCadence）",
            ),
            CheckResult(
                "Root 应用注入",
                injectedPackages.isNotEmpty(),
                if (injectedPackages.isEmpty()) "还没有注入任何应用：在「模拟参数 → Root 传感器注入」里填入目标包名"
                else "已注入：${injectedPackages.joinToString()}（应用进程重启后需要重新注入）",
            ),
        )
    }

    companion object {
        const val ENGINE_ID = "root"
        const val CHECK_NAME = "Root 独立注入"
        const val CONFLICT_REASON = "系统进程仍驻留 LSPosed。免框架 Root 直连需先停用 LSPosed 注入并手动重启，或继续选择 LSPosed 模式"
    }
}
