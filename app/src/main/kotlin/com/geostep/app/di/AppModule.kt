package com.geostep.app.di

import android.content.Context
import androidx.room.Room
import com.geostep.core.data.db.AppDatabase
import com.geostep.core.data.prefs.SettingsRepository
import com.geostep.core.data.repo.LocationRepository
import com.geostep.core.engine.Clock
import com.geostep.core.engine.EngineRouter
import com.geostep.core.engine.ProviderEngine
import com.geostep.core.engine.RingSimLogger
import com.geostep.core.engine.RootEngine
import com.geostep.core.engine.SimLogger
import com.geostep.core.engine.SimulationSession
import com.geostep.core.engine.SystemClockImpl
import com.geostep.core.engine.XposedEngine
import com.geostep.core.model.InjectionEngine
import com.geostep.app.sim.SimController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {

    single<CoroutineScope>(named("app")) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    single<Clock> { SystemClockImpl() }

    single<SimLogger> { RingSimLogger() }

    single<AppDatabase> {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "geostep.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
    }

    single { LocationRepository(get()) }

    single { SettingsRepository(androidContext()) }

    single<ProviderEngine> { ProviderEngine(androidContext(), get()) }

    single<XposedEngine> { XposedEngine(androidContext(), get(), get(named("app")), get()) }

    single<RootEngine> {
        val settings = get<SettingsRepository>()
        RootEngine(androidContext(), get(), get(named("app")), get(), suPath = { settings.settings.first().rootSuPath },
            suMode = { settings.settings.first().rootSuMode }, fallback = get<ProviderEngine>())
    }

    single<EngineRouter> {
        val engines: List<InjectionEngine> = listOf(get<ProviderEngine>(), get<XposedEngine>(), get<RootEngine>())
        EngineRouter(engines, get(named("app")), defaultEngineId = "provider")
    }

    single<SimulationSession> {
        SimulationSession(
            engine = get<EngineRouter>(),
            clock = get(),
            log = get(),
            scope = get(named("app")),
        )
    }

    single { SimController(get(), get(), get(), get(named("app"))) }

    single { com.geostep.app.geo.NominatimClient() }

    single { com.geostep.app.geo.OsrmClient() }

    single { com.geostep.app.ui.HomeViewModel(get(), get(), get(), get(), get(), get(), get()) }
}

/** 供测试/预览用：不依赖 Koin 的手动装配。 */
object ManualDi {
    fun build(context: Context): SimController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock: Clock = SystemClockImpl()
        val log: SimLogger = RingSimLogger()
        val provider = ProviderEngine(context, clock)
        val xposed = XposedEngine(context, clock, scope, log)
        val settings = SettingsRepository(context)
        val root = RootEngine(context, clock, scope, log, suPath = { settings.settings.first().rootSuPath },
            suMode = { settings.settings.first().rootSuMode })
        val router = EngineRouter(listOf(provider, xposed, root), scope, defaultEngineId = "provider")
        val session = SimulationSession(router, clock, log, scope)
        return SimController(session, clock, log, scope)
    }
}
