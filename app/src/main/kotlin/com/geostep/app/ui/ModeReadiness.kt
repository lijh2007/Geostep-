package com.geostep.app.ui

import com.geostep.core.model.CheckResult

/**
 * 工作模式的展示名与“能否切换”的判定。
 *
 * 这里刻意做成不依赖 ViewModel 的纯函数：模式选择的门槛只由自检项名字决定，
 * 与网络、Root 会话、地图等都无关，因此可以单独写单测钉住行为。
 */
internal fun modeLabelOf(engineId: String): String = when (engineId) {
    "provider" -> "标准模式"
    "root" -> "Root 直连模式"
    else -> "LSPosed 模式"
}

/** 每种模式真正阻断启动的自检项：其他项失败只是提示，不应该拦住切换。 */
internal fun requiredCheckNames(engineId: String): Set<String> = when (engineId) {
    "provider" -> setOf("定位权限", "模拟位置 AppOps")
    "xposed" -> setOf("定位权限", "LSPosed 模块安装", "模块版本", "系统控制通道", "系统定位钩子")
    else -> setOf("定位权限", "Root 权限", "Root 独立注入")
}

internal fun blockingChecksFor(engineId: String, checks: List<CheckResult>): List<CheckResult> {
    val names = requiredCheckNames(engineId)
    return checks.filter { !it.ok && it.name in names }
}

/** 切换模式时的失败提示：优先用自检给出的可操作说明。 */
internal fun blockedReasonOf(engineId: String, checks: List<CheckResult>): String? {
    val blocked = blockingChecksFor(engineId, checks)
    if (blocked.isEmpty()) return null
    return modeLabelOf(engineId) + "尚不可用：" + blocked.joinToString("；") { it.message ?: it.name }
}

/** 目标应用包名合法性（与 RootDeployment 里的校验保持一致，用于 UI 层提前拦截）。 */
internal fun isPackageName(value: String): Boolean =
    Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+").matches(value.trim())

/** 开始模拟时要自动补注入的包名：已记住但本次会话还没注入成功的。 */
internal fun packagesToInject(remembered: Set<String>, injected: Collection<String>): List<String> =
    remembered.filter { it.isNotBlank() && isPackageName(it) && it !in injected }.sorted()
