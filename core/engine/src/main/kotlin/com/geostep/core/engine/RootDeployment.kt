package com.geostep.core.engine

import android.content.Context
import android.os.Build
import com.geostep.core.model.CheckResult
import com.geostep.core.model.ControlPlane
import com.geostep.core.model.SimConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Deploys only bundled, hash-checked artifacts. Does not install an LSPosed/Magisk module. */
class RootDeployment(private val context: Context, private val suPath: suspend () -> String = { "" },
                     private val suMode: suspend () -> com.geostep.core.model.RootSuMode = { com.geostep.core.model.RootSuMode.AUTO }) : RootEnvironment {
    private val manifest by lazy {
        Properties().apply { context.assets.open("root/manifest.properties").use { load(it) } }
    }
    override val version: String get() = manifest.getProperty("version") ?: error("Root payload version missing")

    override suspend fun readiness(): List<CheckResult> = withContext(Dispatchers.IO) {
        val artifacts = runCatching { version.isNotBlank() && ARTIFACTS.all { manifest.getProperty(it)?.matches(HASH) == true } }.getOrDefault(false)
        val shell = runCatching { SuCommandRunner(suPath(), suMode()) }
        val authorization = runCatching { shell.getOrThrow().requireRoot() }
        val hasRoot = authorization.isSuccess
        val runtimeCheck = runCatching {
            check(hasRoot) { "获得 Root 权限后才能检查系统环境" }
            val pid = shell.getOrThrow().run("pidof system_server").trim().toInt()
            requireIndependentRuntime(shell.getOrThrow(), pid)
            true
        }
        val frameworkFree = runtimeCheck.getOrDefault(false)
        listOf(
            RootAccess.result(authorization.exceptionOrNull()),
            CheckResult("Root 独立注入", artifacts && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a",
                if (!artifacts) "安装包缺少 Root 注入组件" else if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a")
                    "当前 Root 直连仅支持 ARM64 设备" else "本地组件已就绪，尚未因此判定系统钩子已加载"),
            CheckResult("Root 独立注入", frameworkFree,
                if (frameworkFree) "系统未驻留 LSPosed 注入框架" else runtimeCheck.exceptionOrNull()?.message ?: "系统环境检查失败"),
        )
    }

    override suspend fun load(): RootReceipt = withContext(Dispatchers.IO) {
        require(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") { "Root loader requires ARM64" }
        val shell = SuCommandRunner(suPath(), suMode())
        shell.requireRoot()
        val pid = shell.run("pidof system_server").trim().toIntOrNull() ?: error("system_server PID unavailable")
        requireIndependentRuntime(shell, pid)
        val startTicks = shell.run("cat /proc/$pid/stat").substringAfterLast(") ").split(' ')[19]
        readReceipt(shell)?.takeIf { it.optInt("pid") == pid && it.optString("startTicks") == startTicks }?.let {
            error("当前系统进程已有 Root 加载记录，但控制通道未就绪；请手动重启后重试")
        }

        val release = stage(shell)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        // The loader has its own per-call deadlines and restores the traced thread before exiting.
        // The outer deadline is longer than all of those deadlines combined.
        shell.run("$release/loader $pid $release/libmockgeo_root.so $release/payload.apk $nonce system", 90_000)
        var receipt: JSONObject? = null
        repeat(40) {
            if (receipt == null) {
                readReceipt(shell)?.takeIf { it.optString("nonce") == nonce }?.let { receipt = it }
                if (receipt == null) delay(200)
            }
        }
        val result = checkNotNull(receipt) { "Root 载荷启动后没有系统回执" }
        check(result.optBoolean("ready") && result.optString("mode") == "system" && result.optString("backend") == "root" &&
            result.optInt("pid") == pid && result.optInt("uid") == 1000 && result.optString("startTicks") == startTicks &&
            result.optString("version") == version && shell.run("pidof system_server").trim() == pid.toString()) {
            "Root 载荷未就绪：${result.optString("error", result.optString("stage", "identity mismatch"))}"
        }
        RootReceipt(pid, nonce, version)
    }

    /**
     * 把载荷注入目标 App 进程：Root 模式默认只接管 system_server，App 进程内的传感器必须单独注入。
     * 失败一律返回 Result.failure，由界面提示可操作原因。
     */
    suspend fun injectClient(packageName: String): Result<Int> = withContext(Dispatchers.IO) { runCatching {
        require(packageName.matches(PACKAGE)) { "包名格式不对：$packageName" }
        val shell = shell()
        val pids = processesOf(shell, packageName)
        check(pids.isNotEmpty()) {
            "$packageName 没有在运行（或只有 32 位进程）。请先打开该应用再注入。"
        }
        requireIndependentRuntime(shell, pids.first())
        var done = 0
        for (pid in pids) {
            if (injectProcess(shell, pid, packageName)) done++
        }
        check(done > 0) { "注入后没在 $packageName 的任何进程里找到钩子库" }
        done
    } }

    /**
     * 目标包当前所有 64 位进程。高德会把定位放在 `:locationservice` 子进程里，
     * 只注入主进程的话它依旧用真实定位。
     */
    internal suspend fun processesOf(shell: SuCommandRunner, packageName: String): List<Int> {
        // 不能用 pidof：它只按主进程名匹配，高德的 :locationservice 等子进程会被漏掉。
        // 也不能遍历 /proc（几百个进程会让 su 调用超时），先用一次 ps 过滤出候选，再逐个确认 64 位。
        val d = "$"
        val script = "for p in " + d + "(ps -A -o PID=,NAME= | awk -v q=" + packageName +
            " '" + d + "2==q || index(" + d + "2, q \":\")==1 {print " + d + "1}'); do " +
            "if [ \"" + d + "(basename " + d + "(readlink /proc/" + d + "p/exe 2>/dev/null))\" = \"app_process64\" ]; " +
            "then echo " + d + "p; fi; done"
        return shell.run(script).lineSequence()
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 1 }
            .toList()
    }

    /** 用常驻 su 会话查询目标包的 64 位进程。 */
    internal suspend fun processesOf(packageName: String): List<Int> = withContext(Dispatchers.IO) {
        runCatching { processesOf(shell(), packageName) }.getOrDefault(emptyList())
    }

    internal suspend fun injectProcess(pid: Int, packageName: String): Boolean =
        withContext(Dispatchers.IO) { injectProcess(shell(), pid, packageName) }

    /** 注入单个进程；ptrace 偶发超时，重试两次再判失败。 */
    internal suspend fun injectProcess(shell: SuCommandRunner, pid: Int, packageName: String): Boolean {
        repeat(3) { attempt ->
            if (injectProcessOnce(shell, pid, packageName)) return true
            if (attempt < 2) delay(250)
        }
        return false
    }

    private suspend fun injectProcessOnce(shell: SuCommandRunner, pid: Int, packageName: String): Boolean = runCatching {
        requireIndependentRuntime(shell, pid)
        val release = stage(shell)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val copy = clientCopy(shell, pid, packageName, release, nonce)
        try {
            shell.run(
                "$release/loader $pid ${copy.library} ${copy.payload} $nonce client:$packageName",
                60_000,
            )
            mapped(shell, pid, ".geostep_$nonce")
        } finally {
            shell.run("rm -f ${copy.library} ${copy.payload}")
        }
    }.getOrDefault(false)

    /**
     * 目标应用可能有多个进程（`:push`、`:remote` 等）。只注入主进程，且必须是 64 位
     * （内核不允许把 arm64 的 .so 映射进 32 位进程）。
     */
    private suspend fun mainProcess(shell: SuCommandRunner, packageName: String): Int {
        val d = "$"
        val script = "for p in " + d + "(pidof " + packageName + "); do " +
            "c=" + d + "(tr '\\0' '\\n' < /proc/" + d + "p/cmdline | head -n1); " +
            "if [ \"" + d + "c\" = \"" + packageName + "\" ] && [ \"" + d + "(basename " +
            d + "(readlink /proc/" + d + "p/exe))\" = \"app_process64\" ]; " +
            "then echo " + d + "p; break; fi; done"
        val pid = shell.run(script).trim().toIntOrNull()
        check(pid != null && pid > 1) {
            "$packageName 的主进程没有在运行（或不是 64 位进程）。请先打开该应用再注入。"
        }
        return pid
    }

    private data class ClientCopy(val library: String, val payload: String)

    private suspend fun clientCopy(
        shell: SuCommandRunner,
        pid: Int,
        packageName: String,
        release: String,
        nonce: String,
    ): ClientCopy {
        val apk = shell.run("pm path $packageName").lineSequence()
            .firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim().orEmpty()
        check(apk.endsWith("/base.apk")) { "读不到 $packageName 的安装路径：$apk" }
        val libDir = apk.removeSuffix("/base.apk") + "/lib/arm64"
        // 纯 Kotlin 应用没有原生库目录；它就在应用自己的 lib 路径下，root 可以补建。
        if (shell.run("test -d $libDir && echo ok || true").trim() != "ok") {
            val uid = shell.run(
                "stat -c %u /proc/$pid 2>/dev/null || " +
                    "sed -n 's/^Uid:[[:space:]]*\\([0-9]*\\).*/\\1/p' /proc/$pid/status",
            ).trim().toIntOrNull()
            check(uid != null && uid > 0) { "读不到 $packageName 的运行用户，无法为它准备注入目录" }
            shell.run(
                "mkdir -p $libDir && chown $uid:$uid $libDir && chmod 0711 $libDir && " +
                    "chcon u:object_r:apk_data_file:s0 $libDir || true",
            )
            check(shell.run("test -d $libDir && echo ok || true").trim() == "ok") {
                "无法为 $packageName 创建原生库目录：$libDir（它可能是 32 位应用，Root 注入只支持 64 位目标）"
            }
        }
        val library = "$libDir/.geostep_$nonce.so"
        val payload = "$libDir/.geostep_$nonce.apk"
        shell.run("cp $release/libmockgeo_root.so $library")
        shell.run("cp $release/payload.apk $payload")
        shell.run("chmod 644 $library $payload")
        shell.run("chcon u:object_r:apk_data_file:s0 $library $payload || true")
        return ClientCopy(library, payload)
    }

    /**
     * 是否尝试把载荷注入 system_server。
     *
     * 该路径依赖 LSPlant 对目标 ART 的内联钩子，在部分 ROM（实测 HyperOS / Android 17）上会在
     * `lsplant::SetClassStatus` 里崩溃并带走整个 system_server，因此默认关闭。
     * 关闭时 Root 模式走「注入式」：App 自己写状态镜像，并按需把钩子注入目标 App 进程。
     */
    val systemPayloadEnabled: Boolean = false

    /** 复用同一个 su 会话，避免每次发布状态都重新走一遍授权探测。 */
    private var sessionShell: SuCommandRunner? = null

    private suspend fun shell(): SuCommandRunner = sessionShell ?: openShell().also { sessionShell = it }

    /**
     * 镜像里的 boot 必须与模块读到的 Settings.Global.BOOT_COUNT 完全一致，
     * 否则模块会把它当成「上次开机写入的过期状态」直接丢弃。
     * su 会话的首条命令偶尔读不到值，所以这里失败时回退到应用自己读同一个设置。
     */
    suspend fun bootCount(): Int = withContext(Dispatchers.IO) {
        val fromShell = runCatching {
            shell().run("settings get global boot_count").trim().toIntOrNull()
        }.getOrNull()
        if (fromShell != null && fromShell >= 0) return@withContext fromShell
        val fallback = runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
        if (fallback >= 0) return@withContext fallback
        runCatching {
            shell().run("cmd settings get global boot_count").trim().toIntOrNull()
        }.getOrNull()?.takeIf { it >= 0 } ?: -1
    }

    /** 把当前模拟参数写进 Settings.Global，供已注入的目标进程读取。 */
    suspend fun publishState(cfg: SimConfig, session: Long, bootCount: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { RootStatePublisher.publish(shell(), cfg, session, bootCount).getOrThrow() }
        }

    suspend fun clearState(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { RootStatePublisher.clear(shell()).getOrThrow() }
    }

    /**
     * 让系统把本应用当作"模拟位置应用"：有些 App 的进程注入不进去（ptrace 超时），
     * 这时用系统级模拟位置兜底，任何进程都能读到假位置。
     */
    suspend fun allowMockLocation(packageName: String): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        val shell = shell()
        shell.run("appops set $packageName android:mock_location allow || true")
        shell.run("settings put secure mock_location $packageName || true")
        require(shell.run("settings get secure mock_location").trim() == packageName) {
            "系统没有记录模拟位置应用"
        }
    } }

    /** 读回当前的系统状态镜像，用于验证「App 写的状态」确实落到了系统设置里。 */
    suspend fun readState(): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = shell().run("settings get global ${ControlPlane.GLOBAL_STATE_KEY}").trim()
            if (raw.isEmpty() || raw == "null") null else JSONObject(raw)
        }.getOrNull()
    }

    /** 目标进程里是否仍然映射着我们的钩子库（注入是否还有效）。 */
    suspend fun injected(packageName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { mapped(shell(), mainProcess(shell(), packageName), ".geostep_") }.getOrDefault(false)
    }

    private fun mapped(shell: SuCommandRunner, pid: Int, marker: String = "mockgeo_root"): Boolean =
        shell.run("awk 'BEGIN{f=0} /$marker/ {f=1} END{print f}' /proc/$pid/maps").trim() == "1"

    /**
     * 取一个可用的 su。不同提权工具把 su 放在不同位置，且部分路径只有 shell/root 域能执行
     * （应用去 exec 会拿到 EACCES=13）。这里按顺序试：用户自定义路径 → PATH 里的 su →
     * 常见绝对路径。只有「启动不了」才换下一个；拿到 uid=0 就停，避免反复弹授权框。
     */
    private suspend fun openShell(): SuCommandRunner {
        val preferred = runCatching { suPath() }.getOrDefault("")
        val candidates = buildList {
            if (preferred.isNotBlank()) add(preferred)
            add("su")
            addAll(SU_FALLBACKS)
        }.distinct()
        val failures = mutableListOf<String>()
        for (candidate in candidates) {
            val runner = SuCommandRunner(candidate, suMode())
            val result = runCatching { runner.requireRoot() }
            if (result.isSuccess) return runner
            val error = result.exceptionOrNull()
            failures += "${candidate.ifBlank { "su" }} → ${error?.message}"
            // 授权被拒/超时这类问题换路径也没用，直接报给用户。
            if (error !is IllegalStateException || !(error.message ?: "").contains("无法启动所选 su")) throw error!! 
        }
        error("没有可用的 su 入口。已尝试：${failures.joinToString("；")}")
    }

    private fun readReceipt(shell: SuCommandRunner): JSONObject? = runCatching {
        val text = shell.run("if [ -f $STATE/receipt.json ]; then cat $STATE/receipt.json; fi")
        if (text.isBlank()) null else JSONObject(text)
    }.getOrNull()

    private fun requireIndependentRuntime(shell: SuCommandRunner, pid: Int) {
        val query = "BEGIN {found=0} /lsposed|\\/lspd\\// {found=1} END {print found}"
        check(shell.run("awk ${quote(query)} /proc/$pid/maps").trim() == "0") { RootEngine.CONFLICT_REASON }
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    /** 把 payload.apk / libmockgeo_root.so / loader 部署到 /data/misc/geostep/root，返回 release 目录。 */
    private suspend fun stage(shell: SuCommandRunner): String {
            val digest = manifest.getProperty("payload.apk").also { require(it?.matches(HASH) == true) }
            val staging = File(context.noBackupFilesDir, "root-stage/$digest").apply { mkdirs() }
            for (name in ARTIFACTS) {
                val file = File(staging, name)
                context.assets.open("root/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                check(sha256(file) == manifest.getProperty(name)) { "Root artifact hash mismatch: $name" }
            }
            val release = "$DIRECTORY/releases/$digest"
            val commands = mutableListOf(
                "set -e",
                "mkdir -p /data/misc/geostep $DIRECTORY $DIRECTORY/releases $release $STATE",
                "chown system:system /data/misc/geostep",
                "chmod 750 /data/misc/geostep",
                "chown root:system $DIRECTORY $DIRECTORY/releases $release",
                "chmod 750 $DIRECTORY $DIRECTORY/releases $release",
                "chown system:system $STATE",
                "chmod 750 $STATE",
                "if [ ! -e /data/misc/geostep/exempt.txt ]; then touch /data/misc/geostep/exempt.txt; chown system:system /data/misc/geostep/exempt.txt; chmod 640 /data/misc/geostep/exempt.txt; fi",
            )
            for (name in ARTIFACTS) {
                commands += "cp ${quote(File(staging, name).absolutePath)} ${quote("$release/$name") }"
                commands += "chown root:system ${quote("$release/$name") }"
                commands += "chmod ${if (name == "payload.apk") "440" else "550"} ${quote("$release/$name") }"
                commands += "echo ${quote(manifest.getProperty(name) + "  $release/$name")} | sha256sum -c"
            }
            commands += "if [ \"\$(getenforce)\" != Disabled ]; then chcon u:object_r:system_data_file:s0 /data/misc/geostep /data/misc/geostep/exempt.txt $DIRECTORY $DIRECTORY/releases $release $STATE; chcon u:object_r:system_file:s0 $release/payload.apk $release/libmockgeo_root.so $release/loader; fi"
            shell.run(commands.joinToString("\n"), 15_000)
        return release
    }

    companion object {
        private const val DIRECTORY = "/data/misc/geostep/root"
        private const val STATE = "$DIRECTORY/state"
        private val ARTIFACTS = listOf("payload.apk", "libmockgeo_root.so", "loader")
        private val HASH = Regex("[0-9a-f]{64}")
        private val PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        private val SU_FALLBACKS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su", "/data/adb/apd/su", "/data/adb/magisk/su",
        )
        internal fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
