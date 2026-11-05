package com.geostep.core.engine

import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import com.geostep.core.model.CheckResult
import com.geostep.core.model.EngineCapability
import com.geostep.core.model.EngineState
import com.geostep.core.model.InjectionEngine
import com.geostep.core.model.SimConfig
import com.geostep.core.model.SimError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 路线 A 引擎：官方 test provider（docs/02 §A、docs/08 §5）。
 *
 * 要点：
 * - AppOps 自检在 start 前做（SecurityException 不会告诉用户去哪开）；
 * - Location 必须完整（provider + accuracy + time + elapsedRealtimeNanos），
 *   不依赖 @SystemApi 的 makeComplete()（普通 App 不可见）；
 * - 不改 isMock（保持系统语义；test provider 的 Location 系统会自动标记 mock）；
 * - stop 幂等：disable + remove 各自 runCatching。
 */
class ProviderEngine(
    private val context: Context,
    private val clock: Clock,
) : InjectionEngine {

    private val lm = context.getSystemService(LocationManager::class.java)

    private val registered = linkedSetOf<String>()

    /** 本次会话开始的 elapsedRealtimeNanos，用来过滤注入前的真实缓存定位。 */
    @Volatile
    private var startedAtNanos = 0L

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)

    override fun state(): StateFlow<EngineState> = _state

    override suspend fun capability(): EngineCapability = EngineCapability(
        engineId = ENGINE_ID,
        displayName = "官方 Test Provider",
        requiresMockAppOp = true,
        requiresExternalModule = false,
        supportsNetworkProvider = true,
        supportsStreamingUpdate = true,
    )

    override suspend fun selfCheck(): List<CheckResult> {
        val results = mutableListOf<CheckResult>()
        val appOpOk = isMockAppOpAllowed()
        results += CheckResult(
            name = "定位权限",
            ok = hasLocationPermission(),
            message = if (hasLocationPermission()) null else "缺少定位权限：请在弹窗中允许“位置信息”",
            actionKey = "APP_DETAILS",
        )
        results += CheckResult(
            name = "模拟位置 AppOps",
            ok = appOpOk,
            message = if (appOpOk) null else "开发者选项中未选择本应用为模拟位置应用",
            actionKey = "DEV_OPTIONS",
        )
        val mockApp = Settings.Secure.getString(
            context.contentResolver,
            "mock_location",
        )
        val selected = mockApp == context.packageName
        results += CheckResult(
            name = "系统模拟位置应用",
            ok = selected,
            message = if (selected) {
                null
            } else {
                "开发者选项里的“选择模拟位置信息应用”当前是" +
                    (mockApp?.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: "空的") +
                    "，不是 Geostep；若个别系统页面仍显示真实位置，把它改选成 Geostep 后再试"
            },
            actionKey = "DEV_OPTIONS",
        )
        results += CheckResult(
            name = "LocationManager 可用",
            ok = lm != null,
            message = if (lm == null) "系统 Location 服务不可用" else null,
        )
        return results
    }

    override suspend fun start(cfg: SimConfig): Result<Unit> {
        val r = runCatching {
            checkNotNull(lm) { "LocationManager unavailable" }
            startedAtNanos = clock.elapsedRealtimeNanos()
            ensureAppOpAllowed()
            check(hasLocationPermission()) { "缺少定位权限：请先在应用内允许位置信息" }
            syncProviders(cfg)
            push(cfg)
        }
        _state.value = if (r.isSuccess) {
            EngineState.Running(clock.elapsedRealtime(), cfg)
        } else {
            EngineState.Failed(r.exceptionOrNull().toSimError(), retryable = true, detail = r.exceptionOrNull()?.message)
        }
        return r
    }

    override suspend fun update(cfg: SimConfig): Result<Unit> = runCatching {
        check(registered.isNotEmpty()) { "provider not started" }
        syncProviders(cfg)
        push(cfg)
    }.onFailure { error ->
        _state.value = EngineState.Failed(error.toSimError(), true, error.message)
    }

    override suspend fun stop(): Result<Unit> {
        val l = lm ?: return Result.success(Unit)
        if (registered.isEmpty() && !isMockAppOpAllowed()) {
            _state.value = EngineState.Idle
            return Result.success(Unit)
        }
        var failure: Throwable? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            // Removal is still attempted when disabling fails. Only an absent test
            // provider is benign; permission/service failures must reach the UI.
            runCatching { l.setTestProviderEnabled(p, false) }
            val removed = runCatching { l.removeTestProvider(p) }
            val error = removed.exceptionOrNull()
            if (error == null || error is IllegalArgumentException) registered.remove(p)
            else if (failure == null) failure = error
        }
        return if (failure == null) {
            _state.value = EngineState.Idle
            Result.success(Unit)
        } else {
            _state.value = EngineState.Failed(failure.toSimError(), true, failure.message)
            Result.failure(failure)
        }
    }

    /**
     * 回读校验（标准模式）：读回刚写入的 test provider 位置，确认坐标/速度真的落到系统。
     *
     * 标准模式没有传感器钩子，所以步频如实标记为“未生效”，并提示改用 LSPosed / Root 模式；
     * 这里不做“假成功”，避免用户以为步频已经在注入。
     */
    private suspend fun currentLocation(manager: LocationManager, cfg: SimConfig): Location? {
        var listener: LocationListener? = null
        try {
            return withTimeoutOrNull(3_000L) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val callback = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            // provider 在注册监听时会补发缓存定位，注入前的真实定位必须丢掉；
                            // 同时只接受被系统标记为 mock 的定位，否则会把真实 GNSS fix 当成自己的回读。
                            if (location.elapsedRealtimeNanos < startedAtNanos) return
                            @Suppress("DEPRECATION")
                            if (!location.isFromMockProvider) return
                            if (cont.isActive) cont.resume(location)
                        }

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) = Unit

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    }
                    listener = callback
                    val issued = runCatching {
                        manager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            0L,
                            0f,
                            callback,
                            context.mainLooper,
                        )
                    }
                    if (issued.isFailure) {
                        if (cont.isActive) cont.resume(null)
                    } else if (registered.isNotEmpty()) {
                        runCatching { push(cfg) }
                    }
                }
            }
        } finally {
            listener?.let { callback -> runCatching { manager.removeUpdates(callback) } }
        }
    }

    /**
     * 系统把 test provider 的位置写进 last known 之后就能直接读回，
     * 比监听回调更宽松，用来兜住部分 ROM 上「回调被后台限制」的情况。
     */
    private fun lastKnown(manager: LocationManager, cfg: SimConfig): Location? {
        for (p in providerList(cfg)) {
            val location = runCatching { manager.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (InjectionVerifier.distanceMeters(
                    cfg.position.lat, cfg.position.lng, location.latitude, location.longitude,
                ) <= InjectionVerifier.LOCATION_TOLERANCE_M
            ) {
                return location
            }
        }
        return null
    }

    suspend fun verifyApplied(cfg: SimConfig): List<CheckResult> {
        val manager = lm ?: return listOf(CheckResult(InjectionVerifier.NAME_LOCATION, false, "LocationManager 不可用"))
        val location = currentLocation(manager, cfg) ?: lastKnown(manager, cfg)
        val coordinate = if (location == null) {
            CheckResult(
                InjectionVerifier.NAME_LOCATION,
                false,
                "还没读回模拟坐标：请在开发者选项里把「选择模拟位置信息应用」设为 Geostep，并确认 AppOps 的模拟位置权限已允许",
            )
        } else {
            InjectionVerifier.location(cfg.position, location.latitude, location.longitude)
        }
        val speed = if (location == null) {
            CheckResult(InjectionVerifier.NAME_SPEED, false, "未能读回 test provider 的位置")
        } else {
            InjectionVerifier.speed(cfg.speedMps, location.speed.toDouble())
        }
        return listOf(
            coordinate,
            speed,
            CheckResult(
                InjectionVerifier.NAME_CADENCE, false,
                "标准模式只写系统模拟位置，不注入传感器：步频需切换到 LSPosed 或 Root 模式",
            ),
        )
    }

    private fun syncProviders(cfg: SimConfig) {
        val l = checkNotNull(lm) { "LocationManager unavailable" }
        val wanted = providerList(cfg).toSet()
        for (p in registered.toList() - wanted) {
            l.setTestProviderEnabled(p, false)
            l.removeTestProvider(p)
            registered.remove(p)
        }
        for (p in wanted - registered) {
            @Suppress("DEPRECATION")
            runCatching {
                l.addTestProvider(p, false, p == LocationManager.GPS_PROVIDER, false,
                    false, true, true, true, 1, 1)
            }.onFailure { error ->
                // 有些 ROM 会把上一次会话残留的 test provider 留着，先清掉再注册一次。
                if (error is IllegalArgumentException) {
                    runCatching { l.removeTestProvider(p) }
                    @Suppress("DEPRECATION")
                    l.addTestProvider(p, false, p == LocationManager.GPS_PROVIDER, false,
                        false, true, true, true, 1, 1)
                } else {
                    throw error
                }
            }
            // Track immediately so partially successful setup can be rolled back.
            registered.add(p)
            l.setTestProviderEnabled(p, true)
        }
    }

    // ---- 内部 ----

    private fun providerList(cfg: SimConfig): List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (cfg.networkProviderToo) add(LocationManager.NETWORK_PROVIDER)
    }

    private fun push(cfg: SimConfig) {
        val nowNanos = clock.elapsedRealtimeNanos()
        val nowMs = clock.currentTimeMillis()
        for (p in providerList(cfg)) {
            val loc = Location(p).apply {
                latitude = cfg.position.lat
                longitude = cfg.position.lng
                altitude = cfg.altitude
                accuracy = cfg.accuracyM
                speed = cfg.speedMps
                bearing = cfg.bearingDeg
                verticalAccuracyMeters = cfg.verticalAccuracyM
                speedAccuracyMetersPerSecond = cfg.speedAccuracyMps
                bearingAccuracyDegrees = cfg.bearingAccuracyDeg
                time = nowMs
                elapsedRealtimeNanos = nowNanos
                setExtras(Bundle().apply { putInt("satellites", cfg.satellites) })
            }
            // 完整性由上面字段保证：provider(构造器) + accuracy + time + elapsedRealtimeNanos
            lm.setTestProviderLocation(p, loc)
        }
    }

    private fun ensureAppOpAllowed() {
        if (!isMockAppOpAllowed()) {
            throw SecurityException("mock_location AppOps not allowed (enable in developer options)")
        }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED


    private fun isMockAppOpAllowed(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val pkg = context.packageName ?: return false
        val uid = context.applicationInfo?.uid ?: return false
        return if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, pkg) ==
                AppOpsManager.MODE_ALLOWED
        } else {
            // 字符串版 checkOpNoThrow 自 API 19 起公开（int 常量 OP_MOCK_LOCATION 是 @hide）
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, pkg) ==
                AppOpsManager.MODE_ALLOWED
        }
    }

    private fun Throwable?.toSimError(): SimError = when (this) {
        null -> SimError.ENGINE_CRASH
        is SecurityException -> SimError.MOCK_APPOP_DENIED
        is IllegalArgumentException -> SimError.INVALID_CONFIG
        else -> SimError.ENGINE_CRASH
    }

    companion object {
        const val ENGINE_ID = "provider"
    }
}
