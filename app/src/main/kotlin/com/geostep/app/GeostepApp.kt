package com.geostep.app

import android.app.Application
import com.geostep.app.di.appModule
import com.geostep.core.engine.SimulationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import com.geostep.core.data.prefs.SettingsRepository
import com.geostep.core.engine.EngineRouter
import com.geostep.app.sim.SimController
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.osmdroid.config.Configuration as OsmConfig

class GeostepApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session: SimulationSession by inject()
    val ready = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        // osmdroid 全局配置：只初始化一次（docs/08 §15.7）
        val lowEnd = com.geostep.app.ui.Perf.isLowEnd(this)
        OsmConfig.getInstance().apply {
            userAgentValue = "Geostep/${BuildConfig.VERSION_NAME} ($packageName; Android personal location testing app)"
            tileDownloadThreads = com.geostep.app.ui.Perf.tileThreads(lowEnd).toShort()
            tileDownloadMaxQueueSize = 40
            cacheMapTileCount = if (lowEnd) 24 else 48
            cacheMapTileOvershoot = if (lowEnd) 8 else 16
            tileFileSystemCacheMaxBytes = com.geostep.app.ui.Perf.tileCacheBytes(lowEnd)
            osmdroidBasePath = getDir("osmdroid", MODE_PRIVATE)
            osmdroidTileCache = getDir("osmdroid_tiles", MODE_PRIVATE)
        }
        startKoin {
            androidContext(this@GeostepApp)
            modules(appModule)
        }
        // 启动自愈（docs/08 §4 规则 5 + docs/06 §3.3）：
        // 进程重启 → 一律回 Idle 并确保引擎关闭。
        // 对 LSPosed 引擎：若模块侧残留 enabled=true（上次进程被杀），这里发 STOP 收敛；
        // 对 provider 引擎：test provider 随进程死亡已被系统移除，stop 幂等无害。
        appScope.launch {
            try {
                session.resetToIdle()
                val settings = org.koin.core.context.GlobalContext.get().get<SettingsRepository>()
                val router = org.koin.core.context.GlobalContext.get().get<EngineRouter>()
                val controller = org.koin.core.context.GlobalContext.get().get<SimController>()
                val initial = settings.settings.first()
                controller.updateSettings(initial)
                router.switch(initial.engineId).onFailure {
                    android.util.Log.w("GeostepApp", "engine selection pending cleanup", it)
                }
                ready.complete(Unit)
                settings.settings.collect { controller.updateSettings(it) }
            } catch (error: Exception) {
                android.util.Log.w("GeostepApp", "initialization failed", error)
                ready.complete(Unit) // Session retains any cleanup error for the next explicit stop.
            }
        }
    }
}
