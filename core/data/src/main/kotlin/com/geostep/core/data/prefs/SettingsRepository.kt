package com.geostep.core.data.prefs

import android.content.Context
import com.geostep.core.model.RootSuPath
import com.geostep.core.model.RootSuMode
import com.geostep.core.model.SimConfig
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置项（docs/08 §8 的 DataStore 键）。默认值与规格一致：
 * interval 1000ms / accuracy 3.0 / route speed 1.4 / history 100。
 */
private val ROOT_PACKAGE = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")

private fun parsePackages(raw: String?): Set<String> =
    raw.orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() && ROOT_PACKAGE.matches(it) }
        .toSet()

data class AppSettings(
    val engineId: String = "provider",
    val simIntervalMs: Long = 1000L,
    val gpsAccuracyM: Double = 3.0,
    val jitterEnabled: Boolean = false,
    val jitterMeters: Double = 0.0,
    val routeSpeedMps: Double = 1.4,
    val stepCadenceSpm: Int = 0,
    /** 步频浮动幅度（百分比）：0 = 节拍器式恒定，默认 8% ≈ 真人。 */
    val stepCadenceVariabilityPercent: Int = SimConfig.DEFAULT_STEP_CADENCE_VARIABILITY,
    /** 步频浮动周期（秒）。 */
    val stepCadencePeriodSec: Int = SimConfig.DEFAULT_STEP_CADENCE_PERIOD_SEC,

    /** Root 直连模式下用过的目标应用包名；开始模拟时会自动对它们重新注入载荷。 */
    val rootInjectedPackages: Set<String> = emptySet(),
    val historyLimit: Int = 100,
    val screenKeepOn: Boolean = false,
    val lowPowerMode: Boolean = false,
    val theme: String = "system",
    val language: String = "system",
    val networkProviderToo: Boolean = false,
    val altitudeM: Double = 0.0,
    val satellites: Int = 12,
    val sineSpeedCurve: Boolean = true,
    val mapProvider: String = "amap",
    val amapConsent: Boolean = false,
    val mapSatellite: Boolean = false,
    val compatibilityMode: Boolean = false,
    val rootSuPath: String = "",
    val rootSuMode: RootSuMode = RootSuMode.AUTO,
) {
    companion object {
        val ENGINE_IDS = listOf("provider", "xposed", "root")
    }
}

class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val ENGINE = stringPreferencesKey("engine")
        val INTERVAL = longPreferencesKey("sim_interval_ms")
        val ACCURACY = doublePreferencesKey("gps_accuracy_m")
        val JITTER_ON = booleanPreferencesKey("jitter_enabled")
        val JITTER_M = doublePreferencesKey("jitter_m")
        val ROUTE_SPEED = doublePreferencesKey("route_speed_mps")
        val CADENCE = intPreferencesKey("step_cadence_spm")
        val CADENCE_VARIABILITY = intPreferencesKey("step_cadence_variability_pct")
        val CADENCE_PERIOD = intPreferencesKey("step_cadence_period_sec")
        val ROOT_PACKAGES = stringPreferencesKey("root_injected_packages")
        val HISTORY_LIMIT = intPreferencesKey("history_limit")
        val KEEP_ON = booleanPreferencesKey("screen_keep_on")
        val LOW_POWER = booleanPreferencesKey("low_power_mode")
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val NETWORK_TOO = booleanPreferencesKey("network_provider_too")
        val ALTITUDE = doublePreferencesKey("altitude_m")
        val SATELLITES = intPreferencesKey("satellites")
        val SINE_CURVE = booleanPreferencesKey("sine_speed_curve")
        val MAP_PROVIDER = stringPreferencesKey("map_provider")
        val AMAP_CONSENT = booleanPreferencesKey("amap_consent")
        val MAP_SATELLITE = booleanPreferencesKey("map_satellite")
        val COMPATIBILITY = booleanPreferencesKey("compatibility_format")
        val ROOT_SU_PATH = stringPreferencesKey("root_su_path")
        val ROOT_SU_MODE = stringPreferencesKey("root_su_mode")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            engineId = p[Keys.ENGINE] ?: "provider",
            simIntervalMs = p[Keys.INTERVAL] ?: 1000L,
            gpsAccuracyM = p[Keys.ACCURACY] ?: 3.0,
            jitterEnabled = p[Keys.JITTER_ON] ?: false,
            jitterMeters = p[Keys.JITTER_M] ?: 0.0,
            routeSpeedMps = p[Keys.ROUTE_SPEED] ?: 1.4,
            stepCadenceSpm = p[Keys.CADENCE] ?: 0,
            stepCadenceVariabilityPercent = p[Keys.CADENCE_VARIABILITY]
                ?: SimConfig.DEFAULT_STEP_CADENCE_VARIABILITY,
            stepCadencePeriodSec = p[Keys.CADENCE_PERIOD] ?: SimConfig.DEFAULT_STEP_CADENCE_PERIOD_SEC,
            rootInjectedPackages = parsePackages(p[Keys.ROOT_PACKAGES]),
            historyLimit = p[Keys.HISTORY_LIMIT] ?: 100,
            screenKeepOn = p[Keys.KEEP_ON] ?: false,
            lowPowerMode = p[Keys.LOW_POWER] ?: false,
            theme = p[Keys.THEME] ?: "system",
            language = p[Keys.LANGUAGE] ?: "system",
            networkProviderToo = p[Keys.NETWORK_TOO] ?: false,
            altitudeM = p[Keys.ALTITUDE] ?: 0.0,
            satellites = p[Keys.SATELLITES] ?: 12,
            sineSpeedCurve = p[Keys.SINE_CURVE] ?: true,
            mapProvider = p[Keys.MAP_PROVIDER] ?: "amap",
            amapConsent = p[Keys.AMAP_CONSENT] ?: false,
            mapSatellite = p[Keys.MAP_SATELLITE] ?: false,
            compatibilityMode = p[Keys.COMPATIBILITY] ?: false,
            rootSuPath = p[Keys.ROOT_SU_PATH] ?: "",
            rootSuMode = RootSuMode.parse(p[Keys.ROOT_SU_MODE]),
        )
    }

    suspend fun setEngine(id: String) {
        require(id in AppSettings.ENGINE_IDS) { "unknown engine: $id" }
        store.edit { it[Keys.ENGINE] = id }
    }

    suspend fun setInterval(ms: Long) {
        require(ms in 200..2000) { "interval out of range: $ms" }
        store.edit { it[Keys.INTERVAL] = ms }
    }

    suspend fun setAccuracy(m: Double) {
        require(m in 1.0..50.0) { "accuracy out of range: $m" }
        store.edit { it[Keys.ACCURACY] = m }
    }

    suspend fun setJitter(enabled: Boolean, meters: Double) {
        require(meters in 0.0..SimConfigMaxJitter) { "jitter out of range: $meters" }
        store.edit {
            it[Keys.JITTER_ON] = enabled
            it[Keys.JITTER_M] = meters
        }
    }

    suspend fun setRouteSpeed(mps: Double) {
        require(mps in 0.1..30.0) { "speed out of range: $mps" }
        store.edit { it[Keys.ROUTE_SPEED] = mps }
    }

    suspend fun setLowPowerMode(enabled: Boolean) {
        store.edit { it[Keys.LOW_POWER] = enabled }
    }

    suspend fun setHistoryLimit(limit: Int) {
        require(limit in 10..1000) { "history limit out of range: $limit" }
        store.edit { it[Keys.HISTORY_LIMIT] = limit }
    }

    suspend fun setScreenKeepOn(on: Boolean) = store.edit { it[Keys.KEEP_ON] = on }

    suspend fun setTheme(theme: String) {
        require(theme in listOf("system", "light", "dark")) { "未知主题" }
        store.edit { it[Keys.THEME] = theme }
    }

    suspend fun setNetworkProviderToo(too: Boolean) =
        store.edit { it[Keys.NETWORK_TOO] = too }

    suspend fun setAltitude(m: Double) {
        require(m in -500.0..9000.0) { "altitude out of range: $m" }
        store.edit { it[Keys.ALTITUDE] = m }
    }

    suspend fun setSatellites(n: Int) {
        require(n in 0..64) { "satellites out of range: $n" }
        store.edit { it[Keys.SATELLITES] = n }
    }

    /** 步频（步/分）：0 = 按速度自动推算，1..300 = 固定步频。 */
    suspend fun setStepCadence(spm: Int) {
        require(spm in SimConfig.STEP_CADENCE_AUTO..SimConfig.MAX_STEP_CADENCE_SPM) {
            "step cadence out of range: $spm"
        }
        store.edit { it[Keys.CADENCE] = spm }
    }

    /** 步频浮动幅度（百分比）：0..30，0 = 完全恒定。 */
    suspend fun setRootInjectedPackages(packages: Set<String>) {
        store.edit { it[Keys.ROOT_PACKAGES] = packages.sorted().joinToString(",") }
    }

    suspend fun rememberRootInjectedPackage(packageName: String) {
        val trimmed = packageName.trim()
        if (!ROOT_PACKAGE.matches(trimmed)) return
        val current = parsePackages(store.data.first()[Keys.ROOT_PACKAGES])
        if (trimmed in current) return
        store.edit { it[Keys.ROOT_PACKAGES] = (current + trimmed).sorted().joinToString(",") }
    }

    suspend fun setStepCadenceVariability(percent: Int) {
        require(percent in 0..SimConfig.MAX_STEP_CADENCE_VARIABILITY) {
            "step cadence variability out of range: $percent"
        }
        store.edit { it[Keys.CADENCE_VARIABILITY] = percent }
    }

    /** 步频浮动周期（秒）：10..120。 */
    suspend fun setStepCadencePeriod(seconds: Int) {
        require(seconds in SimConfig.MIN_STEP_CADENCE_PERIOD_SEC..SimConfig.MAX_STEP_CADENCE_PERIOD_SEC) {
            "step cadence period out of range: $seconds"
        }
        store.edit { it[Keys.CADENCE_PERIOD] = seconds }
    }

    suspend fun setSineCurve(on: Boolean) = store.edit { it[Keys.SINE_CURVE] = on }

    suspend fun setMapProvider(id: String) {
        require(id in listOf("osm", "amap")) { "未知地图服务" }
        store.edit { it[Keys.MAP_PROVIDER] = id }
    }
    suspend fun setAmapConsent(agreed: Boolean) = store.edit {
        it[Keys.AMAP_CONSENT] = agreed
        it[Keys.MAP_PROVIDER] = if (agreed) "amap" else "osm"
    }
    suspend fun setMapSatellite(on: Boolean) = store.edit { it[Keys.MAP_SATELLITE] = on }
    suspend fun setCompatibilityMode(on: Boolean) = store.edit { it[Keys.COMPATIBILITY] = on }

    suspend fun setRootSuPath(path: String, mode: RootSuMode? = null) {
        val normalized = RootSuPath.normalize(path)
        store.edit {
            it[Keys.ROOT_SU_PATH] = normalized
            if (mode != null) it[Keys.ROOT_SU_MODE] = mode.name
        }
    }

    fun exportJson(settings: AppSettings): String = SettingsJson.encode(settings)

    /** Validate the entire document before one atomic edit. Engine selection is never imported. */
    suspend fun importJson(json: String) {
        val value = SettingsJson.decode(json)
        store.edit {
            it[Keys.INTERVAL] = value.simIntervalMs
            it[Keys.ACCURACY] = value.gpsAccuracyM
            it[Keys.JITTER_ON] = value.jitterEnabled
            it[Keys.JITTER_M] = value.jitterMeters
            it[Keys.ROUTE_SPEED] = value.routeSpeedMps
            it[Keys.CADENCE] = value.stepCadenceSpm
            it[Keys.CADENCE_VARIABILITY] = value.stepCadenceVariabilityPercent
            it[Keys.CADENCE_PERIOD] = value.stepCadencePeriodSec
            it[Keys.HISTORY_LIMIT] = value.historyLimit
            it[Keys.KEEP_ON] = value.screenKeepOn
            it[Keys.LOW_POWER] = value.lowPowerMode
            it[Keys.THEME] = value.theme
            it[Keys.LANGUAGE] = value.language
            it[Keys.NETWORK_TOO] = value.networkProviderToo
            it[Keys.ALTITUDE] = value.altitudeM
            it[Keys.SATELLITES] = value.satellites
            it[Keys.SINE_CURVE] = value.sineSpeedCurve
        }
    }

    companion object {
        const val SimConfigMaxJitter = 50.0
    }
}
