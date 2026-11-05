package com.geostep.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geostep.core.engine.EngineRouter
import com.geostep.core.model.LatLng
import com.geostep.core.model.SimConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 仅 debug 构建存在的自动化入口，供开发与 CI 在没有 UI 自动化权限的机器上验证注入链路：
 *
 * ```
 * # 向目标应用进程注入传感器钩子（Root 直连模式）
 * adb shell am broadcast -a com.geostep.app.DEBUG_INJECT \
 *     -n com.geostep.app/.DebugAutomationReceiver --es package com.lptiyu.tanke
 *
 * # 直接让引擎开始 / 停止模拟
 * adb shell am broadcast -a com.geostep.app.DEBUG_START \
 *     -n com.geostep.app/.DebugAutomationReceiver \
 *     --es engine provider --ef lat 39.9042 --ef lng 116.4074 --ef speed 1.4 --ei cadence 0
 * adb shell am broadcast -a com.geostep.app.DEBUG_STOP -n com.geostep.app/.DebugAutomationReceiver
 *
 * # 打印当前模式的自检结果
 * adb shell am broadcast -a com.geostep.app.DEBUG_STATUS -n com.geostep.app/.DebugAutomationReceiver
 * ```
 *
 * release 构建不包含本文件，避免给正式用户留后门。
 */
class DebugAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val router = runCatching { GlobalContext.get().get<EngineRouter>() }.getOrElse {
            Log.w(TAG, "$action router unavailable: ${it.message}")
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        when (action) {
            ACTION_INJECT -> scope.launch { inject(router, intent) }
            ACTION_START -> scope.launch { start(router, intent) }
            ACTION_EXEC -> {
                val command = intent.getStringExtra("cmd").orEmpty()
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                        val output = process.inputStream.bufferedReader().readText()
                        process.waitFor()
                        output.trim()
                    }.onSuccess { Log.i(TAG, "DEBUG_EXEC out=${it.take(1200)}") }
                        .onFailure { Log.i(TAG, "DEBUG_EXEC failed error=${it.message}") }
                }
            }

            ACTION_STOP -> scope.launch {
                val result = router.stop()
                Log.i(
                    TAG,
                    if (result.isSuccess) "DEBUG_STOP ok" else "DEBUG_STOP failed ${result.exceptionOrNull()?.message}",
                )
            }
            ACTION_STATUS -> scope.launch { status(router) }
        }
    }

    private suspend fun inject(router: EngineRouter, intent: Intent) {
        val target = intent.getStringExtra("package")?.trim().orEmpty()
        if (target.isEmpty()) {
            Log.w(TAG, "DEBUG_INJECT missing package extra")
            return
        }
        val result = router.injectRootSensors(target)
        Log.i(
            TAG,
            if (result.isSuccess) "DEBUG_INJECT ok target=$target"
            else "DEBUG_INJECT failed target=$target error=${result.exceptionOrNull()?.message}",
        )
    }

    private suspend fun start(router: EngineRouter, intent: Intent) {
        val engine = intent.getStringExtra("engine")?.trim().takeUnless { it.isNullOrEmpty() } ?: "provider"
        val switched = router.switch(engine)
        if (switched.isFailure) {
            Log.w(TAG, "DEBUG_START switch failed engine=$engine ${switched.exceptionOrNull()?.message}")
            return
        }
        val lat = intent.floatExtra("lat", DEFAULT_LAT.toFloat()).toDouble()
        val lng = intent.floatExtra("lng", DEFAULT_LNG.toFloat()).toDouble()
        val speed = intent.floatExtra("speed", 1.4f)
        val cadence = intent.getIntExtra("cadence", 0)
        val cfg = SimConfig(
            position = LatLng(lat, lng),
            speedMps = speed,
            stepCadenceSpm = cadence,
        )
        val started = router.start(cfg)
        if (started.isFailure) {
            Log.w(TAG, "DEBUG_START failed engine=$engine error=${started.exceptionOrNull()?.message}")
            return
        }
        Log.i(TAG, "DEBUG_START ok engine=$engine lat=$lat lng=$lng speed=$speed cadence=$cadence")
        val checks = router.verifyApplied(cfg)
        checks.forEach { Log.i(TAG, "DEBUG_START check ${it.name} ok=${it.ok} ${it.message.orEmpty()}") }
    }

    private suspend fun status(router: EngineRouter) {
        Log.i(TAG, "DEBUG_STATUS engine=${router.currentEngineId}")
        router.selfCheck().forEach { Log.i(TAG, "DEBUG_STATUS check ${it.name} ok=${it.ok} ${it.message.orEmpty()}") }
    }

    private fun Intent.floatExtra(key: String, fallback: Float): Float =
        if (hasExtra(key)) getFloatExtra(key, fallback) else fallback

    private companion object {
        const val TAG = "Geostep"
        const val ACTION_INJECT = "com.geostep.app.DEBUG_INJECT"
        const val ACTION_START = "com.geostep.app.DEBUG_START"
        const val ACTION_STOP = "com.geostep.app.DEBUG_STOP"
        const val ACTION_EXEC = "com.geostep.app.DEBUG_EXEC"
        const val ACTION_STATUS = "com.geostep.app.DEBUG_STATUS"
        val ACTIONS = setOf(ACTION_INJECT, ACTION_START, ACTION_STOP, ACTION_STATUS, ACTION_EXEC)
        const val DEFAULT_LAT = 39.9042
        const val DEFAULT_LNG = 116.4074
    }
}
