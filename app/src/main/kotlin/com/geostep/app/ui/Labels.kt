package com.geostep.app.ui

import com.geostep.core.model.EngineState
import com.geostep.core.model.LatLng
import com.geostep.core.model.SimError

/**
 * 界面文案与数值格式化。
 *
 * 这些是纯函数，放在 ViewModel 之外：既方便复用，也能用单测钉住格式
 * （坐标固定 6 位小数、米/公里切换阈值、失败原因对应的可操作提示）。
 */
internal fun coordinates(pos: LatLng): String = "%.6f, %.6f".format(java.util.Locale.US, pos.lat, pos.lng)

internal fun distanceLabel(meters: Double): String =
    if (meters >= 1000) "%.2f km".format(meters / 1000) else "%.0f m".format(meters)

internal fun errorLabel(failed: EngineState.Failed): String = when (failed.reason) {
    SimError.MOCK_APPOP_DENIED -> "请在开发者选项中将 Geostep 设为模拟位置应用"
    SimError.PERMISSION_DENIED -> "定位权限未开启，点击查看设置"
    SimError.MODULE_UNREACHABLE -> "LSPosed 模块未生效：启用模块、勾选系统框架与目标应用后重启"
    SimError.ACK_TIMEOUT -> "引擎响应超时，请查看诊断后重试"
    else -> failed.detail ?: "模拟启动失败，请查看诊断后重试"
}
