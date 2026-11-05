package com.geostep.module

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.geostep.core.model.ControlPlane

internal data class ScopeState(
    val enabled: Boolean, val hideMock: Boolean, val lat: Double, val lng: Double,
    val speed: Float, val satellites: Int, val atMs: Long,
    val elapsedMs: Long = -1, val bootCount: Int = -1, val session: Long = 0,
    val altitude: Double = 0.0, val bearing: Float = 0f,
    val exempt: Set<String> = emptySet(),
    /** 固定步频（步/分）；0 = 按速度自动。字段可选，兼容旧模块写出的镜像。 */
    val cadenceSpm: Int = StepCadence.AUTO,
    /** 步频浮动幅度（百分比）；旧镜像缺省用默认浮动，避免升级后节奏突然变机械。 */
    val cadenceVariabilityPercent: Int = StepCadence.DEFAULT_VARIABILITY_PERCENT,
    /** 步频浮动周期（秒）。 */
    val cadencePeriodSec: Int = StepCadence.DEFAULT_PERIOD_SEC,
) {
    /** 当前包是否应被注入（模拟开启且不在豁免名单）。 */
    fun activeFor(packageName: String?): Boolean =
        enabled && (packageName.isNullOrBlank() || packageName !in exempt)
    fun isFresh(nowElapsed: Long, currentBoot: Int): Boolean =
        bootCount >= 0 && currentBoot == bootCount && elapsedMs >= 0 &&
            nowElapsed - elapsedMs in 0 until AppScopeConfig.INJECT_FRESH_MS

    companion object { val DISABLED = ScopeState(false, false, 0.0, 0.0, 0f, 0, 0L) }
}

/** A persisted mirror is a lease, never an authority to keep injecting indefinitely. */
internal object AppScopeConfig {
    private const val CACHE_MS = 200L

    /**
     * 注入有效期：比 App 侧的状态回读窗口（15 秒）宽松得多。
     * Root 模式下 App 退到后台会被系统冻结，镜像无法每 3 秒续期；
     * 若仍按 15 秒判定，用户切到高德/运动 App 时注入就会失效。
     */
    internal const val INJECT_FRESH_MS = 300_000L
    @Volatile private var cached: ScopeState? = null
    @Volatile private var cachedAt = -1L
    @Volatile private var boot = -1

    fun snapshot(context: Context?): ScopeState {
        val now = SystemClock.elapsedRealtime()
        val hit = cached
        if (hit != null && now - cachedAt in 0 until CACHE_MS) {
            return if (hit.isFresh(now, boot)) hit else ScopeState.DISABLED
        }
        val fresh = runCatching {
            val resolver = context?.contentResolver ?: return@runCatching null
            boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
            parse(Settings.Global.getString(resolver, ControlPlane.GLOBAL_STATE_KEY))
        }.getOrNull()
        cached = fresh
        cachedAt = now
        return fresh?.takeIf { it.isFresh(now, boot) } ?: ScopeState.DISABLED
    }

    /** Strict numeric-only wire format; incomplete/old/malformed mirrors fail closed. */
    fun parse(json: String?): ScopeState? = runCatching {
        val text = json?.trim() ?: return null
        if (!text.startsWith('{') || !text.endsWith('}')) return null
        val fields = mutableMapOf<String, Double>()
        var exempt: Set<String> = emptySet()
        val body = text.substring(1, text.length - 1)
        var numericPart = body
        val exemptions = X_FIELD.findAll(body).toList()
        if (exemptions.size > 1) return null
        exemptions.singleOrNull()?.let { match ->
            exempt = match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (exempt.size > 256 || exempt.any { !Exemptions.validPackage(it) }) return null
            val before = body.substring(0, match.range.first).trimEnd()
            val after = body.substring(match.range.last + 1).trimStart()
            if (before.isNotEmpty() && !before.endsWith(',')) return null
            if (after.isNotEmpty() && !after.startsWith(',')) return null
            numericPart = if (before.isEmpty()) after.removePrefix(",")
                else if (after.isEmpty()) before.removeSuffix(",") else before + after.removePrefix(",")
        }
        for (part in numericPart.split(',')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return null
            val match = FIELD.matchEntire(trimmed) ?: return null
            val value = match.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            if (fields.put(match.groupValues[1], value) != null) return null
        }
        // 步频相关键是后加的：缺省时按默认浮动处理，多余/未知键仍然 fail-closed。
        if (fields.keys - OPTIONAL_KEYS != KEYS) return null
        fun n(key: String) = fields.getValue(key)
        fun integer(key: String) = n(key) % 1.0 == 0.0
        if (n("e") !in listOf(0.0, 1.0) || n("h") !in listOf(0.0, 1.0) ||
            n("lat") !in -90.0..90.0 || n("lng") !in -180.0..180.0 ||
            n("spd") !in 0.0..Float.MAX_VALUE.toDouble() || n("sat") !in 0.0..64.0 ||
            n("brg") !in 0.0..360.0 || n("boot") !in 0.0..Int.MAX_VALUE.toDouble() ||
            listOf("ts", "et", "session").any { n(it) < 0 || n(it) > 9_007_199_254_740_991.0 } ||
            listOf("sat", "boot", "et", "ts", "session").any { !integer(it) }) return null
        if (fields.containsKey(CADENCE) &&
            (n(CADENCE) !in 0.0..StepCadence.MAX_SPM.toDouble() || !integer(CADENCE))) return null
        if (fields.containsKey(VARIABILITY) && (n(VARIABILITY) !in 0.0..
                StepCadence.MAX_VARIABILITY_PERCENT.toDouble() || !integer(VARIABILITY))) return null
        if (fields.containsKey(PERIOD) && (n(PERIOD) !in StepCadence.MIN_PERIOD_SEC.toDouble()..
                StepCadence.MAX_PERIOD_SEC.toDouble() || !integer(PERIOD))) return null
        ScopeState(n("e") == 1.0, n("h") == 1.0, n("lat"), n("lng"), n("spd").toFloat(),
            n("sat").toInt(), n("ts").toLong(), n("et").toLong(), n("boot").toInt(),
            n("session").toLong(), n("alt"), n("brg").toFloat(), exempt,
            fields[CADENCE]?.toInt() ?: StepCadence.AUTO,
            fields[VARIABILITY]?.toInt() ?: StepCadence.DEFAULT_VARIABILITY_PERCENT,
            fields[PERIOD]?.toInt() ?: StepCadence.DEFAULT_PERIOD_SEC)
    }.getOrNull()

    private val X_FIELD = Regex("\"x\"\\s*:\\s*\"([^\"]*)\"")
    private val FIELD = Regex("\"([a-z]+)\"\\s*:\\s*([-+]?(?:[0-9]+(?:\\.[0-9]+)?)(?:[eE][-+]?[0-9]+)?)")
    private val KEYS = setOf("e", "h", "lat", "lng", "spd", "sat", "ts", "et", "boot", "session", "alt", "brg")
    /** 步频键是后加的：旧模块 APK 不写它，读端必须同时接受多种键集合。 */
    internal const val CADENCE = "cad"
    internal const val VARIABILITY = "cav"
    internal const val PERIOD = "cap"
    private val OPTIONAL_KEYS = setOf(CADENCE, VARIABILITY, PERIOD)
    fun invalidate() { cached = null; cachedAt = -1 }
}
