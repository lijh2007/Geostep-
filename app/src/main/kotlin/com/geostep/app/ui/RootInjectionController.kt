package com.geostep.app.ui

import com.geostep.core.data.prefs.SettingsRepository
import com.geostep.core.engine.EngineRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Root 模式的「应用注入」状态机。
 *
 * 之前这些逻辑长在 `HomeViewModel` 里，和模拟状态、回读校验挤在同一个类，改一处要读三个地方。
 * 搬出来之后：注入成功与否只在这里判断，`HomeViewModel` 只负责把 UI 调用转发过来。
 */
data class RootInjectState(
    val busy: Boolean = false,
    val ok: Boolean = false,
    val message: String = "",
)

internal class RootInjectionController(
    private val router: EngineRouter,
    private val settingsRepo: SettingsRepository,
) {

    private val _state = MutableStateFlow(RootInjectState())
    val state: StateFlow<RootInjectState> = _state.asStateFlow()

    /** 本次会话已注入过的包：只影响自动补注入的去重，持久化由 SettingsRepository 负责。 */
    private val _injected = MutableStateFlow<Set<String>>(emptySet())
    val injectedPackages: StateFlow<Set<String>> = _injected.asStateFlow()

    /** 手动注入：UI 里填包名点「注入」走这里。 */
    suspend fun inject(raw: String) {
        val target = raw.trim()
        if (target.isEmpty()) {
            _state.value = RootInjectState(ok = false, message = "请先填写目标应用的包名")
            return
        }
        if (_state.value.busy) return
        _state.value = RootInjectState(busy = true, message = "正在注入 $target …")
        val result = withContext(Dispatchers.IO) { router.injectRootSensors(target) }
        if (result.isFailure) {
            _state.value = RootInjectState(
                ok = false,
                message = "注入失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
            )
            return
        }
        _injected.value = _injected.value + target
        settingsRepo.rememberRootInjectedPackage(target)
        // 记住的目标包交给引擎自动维护：高德的 :locationservice 之类的新进程会被持续补注入。
        router.setRootTargets(_injected.value)
        _state.value = RootInjectState(
            ok = true,
            message = "已注入 $target：该应用的定位与传感器会跟随模拟值（新起的子进程会自动补注入）",
        )
    }

    /** 开始模拟时给记住过的应用补一次注入：应用重启后载荷会丢，这一步让「开箱即用」。 */
    suspend fun reInject(packages: Set<String>) {
        if (packages.isEmpty() || _state.value.busy) return
        val failed = mutableListOf<String>()
        val targets = packagesToInject(packages, _injected.value)
        for (target in targets) {
            val result = withContext(Dispatchers.IO) { router.injectRootSensors(target) }
            if (result.isFailure) failed += target else _injected.value = _injected.value + target
        }
        _state.value = if (failed.isEmpty()) {
            RootInjectState(ok = true, message = "已自动注入 ${packages.size} 个应用")
        } else {
            RootInjectState(ok = false, message = "部分应用没能注入：${failed.joinToString("、")}")
        }
    }

    suspend fun forget(packageName: String) {
        val current = settingsRepo.settings.first().rootInjectedPackages
        settingsRepo.setRootInjectedPackages(current - packageName)
        _injected.value = _injected.value - packageName
        router.setRootTargets(_injected.value)
        _state.value = RootInjectState(message = "已移除 $packageName")
    }

    /** 模拟开始时的准备：先把目标登记给引擎（新进程会被扫描），再补注入。 */
    suspend fun startSession(remembered: Set<String>) {
        router.setRootTargets(remembered + _injected.value)
        if (remembered.isNotEmpty()) reInject(remembered)
    }
}
