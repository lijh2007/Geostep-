package com.geostep.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.util.Locale
import com.geostep.app.BuildConfig
import com.geostep.app.diagnostics.DiagnosticReport
import com.geostep.app.geo.AmapKeyStore
import com.geostep.core.engine.SimLogger
import com.geostep.app.sim.SimController.SimUiState
import com.geostep.core.data.prefs.AppSettings
import com.geostep.core.engine.XposedEngine.ModuleStatus
import com.geostep.core.engine.XposedEngine
import com.geostep.core.model.CheckResult
import com.geostep.core.model.EngineState

/**
 * 诊断报告的**文本拼装**部分。
 *
 * 从 `HomeViewModel` 里搬出来的原因：这段代码只是「把状态读成中文字段」，没有业务副作用，
 * 混在 800 行的 ViewModel 里既难读也难测。ViewModel 只负责收集状态并调用这里。
 */
internal object DiagnosticsReportBuilder {

    fun build(
        context: Context,
        ui: SimUiState,
        settings: AppSettings,
        engineId: String,
        status: ModuleStatus?,
        rootAccess: CheckResult?,
        injectionChecks: List<CheckResult>,
        checks: List<CheckResult>,
        logs: List<SimLogger.LogLine>,
        secrets: List<String?>,
    ): String {
        val power = context.getSystemService(PowerManager::class.java)
        val details = mutableListOf(
            "应用版本" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "应用包名" to context.packageName,
            "应用用户空间" to (Process.myUid() / 100_000).toString(),
            "Root 执行入口" to if (settings.rootSuPath.isBlank()) "默认 su" else "自定义路径（不导出路径内容）",
            "Root 调用方式" to settings.rootSuMode.label,
            "模拟位置候选声明" to mockLocationDeclaration(context),
            "设备" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "系统" to "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            "系统构建" to Build.DISPLAY,
            "CPU ABI" to Build.SUPPORTED_ABIS.joinToString(),
            "运行引擎" to engineId,
            "引擎状态" to DiagnosticReport.stateLabel(ui.engineState),
            "模拟模式" to ui.mode.name,
            "暂停移动" to ui.paused.toString(),
            "标准电池优化豁免" to power.isIgnoringBatteryOptimizations(context.packageName).toString(),
            "系统省电模式" to power.isPowerSaveMode.toString(),
            "设备空闲模式" to power.isDeviceIdleMode.toString(),
            "厂商省电策略" to "需在应用省电设置中确认；标准电池豁免不代表厂商已设为不受限制",
            "已安装模块版本" to installedModuleVersion(context),
        )

        (ui.engineState as? EngineState.Failed)?.detail?.let { details += "失败详情" to it }
        rootAccess?.let { details += "Root 授权检测" to (it.message ?: if (it.ok) "通过" else "未通过") }
        if (injectionChecks.isNotEmpty()) {
            details += "注入回读校验" to injectionChecks.joinToString("；") {
                "${it.name}=${if (it.ok) "通过" else "未通过"}（${it.message ?: ""}）"
            }
        }
        status?.let { report ->
            details += "系统回执步频" to if (report.cadenceSpm < 0) {
                "未上报（模块 < ${XposedEngine.MIN_CADENCE_MODULE_VERSION}）"
            } else {
                "${report.cadenceSpm} 步/分"
            }
            details += "系统回执速度" to if (report.speedMps.isFinite()) {
                String.format(Locale.US, "%.2f m/s", report.speedMps)
            } else {
                "未上报"
            }
        }

        val now = System.currentTimeMillis()
        if (status != null) {
            details += listOf(
                "系统后端" to status.backend,
                "系统载荷版本" to status.moduleVersion,
                "系统进程" to status.processId.toString(),
                "回执仍新鲜" to status.isFresh(now).toString(),
                "回执年龄 ms" to (now - status.updatedAt).toString(),
                "系统模拟启用" to status.enabled.toString(),
                "关键钩子就绪" to status.hooksInstalled.toString(),
                "钩子详情" to status.hookDetail,
                "心跳剩余 ms（回执时）" to status.leaseRemainingMs.toString(),
                "心跳过期次数" to status.leaseExpirations.toString(),
                "模拟标记" to status.reportMock.toString(),
                "定位计数" to "last=${status.lastCalls}, current=${status.currentCalls}, " +
                    "subscriptions=${status.listenerCalls}, pushes=${status.pushes}",
                "回调计数" to "rewritten=${status.rewrittenCallbacks}, unmatched=${status.unmatchedCallbacks}",
            )
        } else {
            details += "系统回执" to "当前进程尚未读取到回执"
        }

        return DiagnosticReport.render(details, checks, logs, secrets.filterNotNull())
    }

    private fun mockLocationDeclaration(context: Context): String = runCatching {
        val requested = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty()
        if ("android.permission.ACCESS_MOCK_LOCATION" in requested) {
            "已声明（系统选择列表仍由 ROM 决定）"
        } else {
            "缺失，当前安装包不会被标准选择器列出"
        }
    }.getOrElse { "读取失败：${it.javaClass.simpleName}" }

    private fun installedModuleVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo("com.geostep.module", 0).versionName
            ?.takeIf { it.isNotBlank() } ?: "安装包未提供版本号"
    }.getOrDefault("无法查询（可能未安装或当前用户空间不可见）；请结合系统加载回执判断")
}
