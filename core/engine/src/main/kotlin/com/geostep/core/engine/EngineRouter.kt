package com.geostep.core.engine

import com.geostep.core.model.CheckResult
import com.geostep.core.model.EngineCapability
import com.geostep.core.model.EngineState
import com.geostep.core.model.InjectionEngine
import com.geostep.core.model.SimConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 引擎路由器：UI 可切换引擎（provider / xposed），会话与 UI 始终面对同一个对象。
 *
 * 规则：
 * - 仅在 Idle 态允许切换（运行中切换必须先停止——避免两个引擎同时注入）；
 * - 自持 StateFlow 镜像当前引擎状态（切换不破坏 UI 收集器）；
 * - capability 在构造期缓存（引擎的 capability 是纯数据，无 IO）。
 */
class EngineRouter(
    engines: List<InjectionEngine>,
    private val scope: CoroutineScope,
    defaultEngineId: String,
) : InjectionEngine {

    private val caps: Map<String, EngineCapability>
    private val byId: Map<String, InjectionEngine>

    init {
        // capability() 是纯数据返回（两个引擎实现均无 IO），构造期一次性读取
        val c = mutableMapOf<String, EngineCapability>()
        val m = mutableMapOf<String, InjectionEngine>()
        runBlocking {
            for (e in engines) {
                val cap = e.capability()
                c[cap.engineId] = cap
                m[cap.engineId] = e
            }
        }
        caps = c
        byId = m
    }

    @Volatile
    private var currentId: String = byId[defaultEngineId]?.let { idOf(it) } ?: idOf(engines.first())

    @Volatile
    private var current: InjectionEngine = byId[currentId] ?: engines.first()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    private var mirrorJob: Job? = null

    init {
        mirrorState()
    }

    val currentEngineId: String get() = currentId

    val availableEngineIds: Set<String> get() = caps.keys

    fun capabilityOf(engineId: String): EngineCapability? = caps[engineId]

    /** 切换引擎。仅 Idle 允许；否则返回失败。 */
    fun switch(engineId: String): Result<Unit> = runCatching {
        val next = byId[engineId] ?: throw IllegalArgumentException("unknown engine: $engineId")
        if (_state.value != EngineState.Idle || current.state().value != EngineState.Idle) {
            throw IllegalStateException("cannot switch engine while not idle (stop first)")
        }
        current = next
        currentId = engineId
        mirrorState()
    }

    override suspend fun capability(): EngineCapability = caps.getValue(currentId)

    override suspend fun start(cfg: SimConfig): Result<Unit> = current.start(cfg)

    override suspend fun update(cfg: SimConfig): Result<Unit> = current.update(cfg)

    override suspend fun stop(): Result<Unit> {
        var failure: Throwable? = null
        // A previous process may have selected a different engine. Close every
        // reachable backend before claiming that simulation has stopped.
        for (engine in byId.values) {
            if (engine !== current && engine is XposedEngine && !engine.pingModule()) continue
            if (engine !== current && engine is RootEngine && !engine.pingModule()) continue
            // The default provider can be selected after process recreation while
            // the module is absent; only reachable modules need a cleanup command.
            if (engine === current && engine is XposedEngine && !engine.pingModule()) {
                failure = IllegalStateException("LSPosed module unreachable; stop not confirmed")
                continue
            }
            val result = engine.stop()
            if (result.isFailure && failure == null) failure = result.exceptionOrNull()
        }
        if (failure == null) _state.value = EngineState.Idle
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    override suspend fun selfCheck(): List<CheckResult> = current.selfCheck()

    suspend fun selfCheck(engineId: String): List<CheckResult> =
        byId[engineId]?.selfCheck() ?: listOf(CheckResult("引擎", false, "引擎不存在"))

    /**
     * 回读校验当前引擎：坐标与步频是否真的注入生效。
     * 三种模式各有判据（provider 读回 test location，xposed/root 读模块状态回执）。
     */
    /** Root 模式专用：把载荷注入目标 App 进程，让该应用的定位与传感器读模拟值。 */
    suspend fun injectRootSensors(packageName: String): Result<Unit> {
        val root = byId.values.filterIsInstance<RootEngine>().firstOrNull()
            ?: return Result.failure(IllegalStateException("Root 引擎不可用"))
        return root.injectClient(packageName)
    }

    /** 同步「需要保持注入」的目标包：目标 App 新起的进程会被自动补注入。 */
    fun setRootTargets(packages: Set<String>) {
        byId.values.filterIsInstance<RootEngine>().forEach { it.setTargets(packages) }
    }

    fun rootTargets(): Set<String> =
        byId.values.filterIsInstance<RootEngine>().firstOrNull()?.targetPackages().orEmpty()

    suspend fun verifyApplied(cfg: SimConfig): List<CheckResult> = when (val engine = current) {
        is RootEngine -> engine.verifyApplied(cfg)
        is ProviderEngine -> engine.verifyApplied(cfg)
        is XposedEngine -> engine.verifyApplied(cfg)
        else -> listOf(CheckResult("注入校验", false, "当前引擎不支持回读校验"))
    }

    fun moduleSnapshot(): XposedEngine.ModuleStatus? =
        (byId[currentId] as? RootEngine)?.statusSnapshot()
            ?: (byId["xposed"] as? XposedEngine)?.statusSnapshot()

    override fun state(): StateFlow<EngineState> = _state

    private fun mirrorState() {
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            current.state().collect { _state.value = it }
        }
    }

    private fun idOf(engine: InjectionEngine): String = runBlocking { engine.capability().engineId }
}
