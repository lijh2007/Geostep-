package com.geostep.app.ui

import com.geostep.core.model.SimConfig

/**
 * 界面参数的可接受范围。
 *
 * 以前这些数字散落在 `HomeViewModel` 的十几个 setter 里（`coerceIn(200, 2000)` 之类），
 * 既容易和滑杆配置对不上，也没有任何测试兜着。集中到一处后：滑杆、setter、单测都引用同一份边界。
 */
internal object ParameterLimits {

    val intervalMs = 200L..2_000L
    val accuracyM = 1.0..50.0
    val jitterMeters = 0.0..SimConfig.MAX_JITTER_METERS
    val routeSpeedMps = 0.1..30.0
    val altitudeM = -500.0..9_000.0
    val satellites = 0..64
    val stepCadenceSpm = SimConfig.STEP_CADENCE_AUTO..SimConfig.MAX_STEP_CADENCE_SPM
    val stepCadenceVariability = 0..SimConfig.MAX_STEP_CADENCE_VARIABILITY
    val stepCadencePeriodSec = SimConfig.MIN_STEP_CADENCE_PERIOD_SEC..SimConfig.MAX_STEP_CADENCE_PERIOD_SEC
    val historyLimit = 10..2_000

    fun interval(ms: Long): Long = ms.coerceIn(intervalMs)

    fun accuracy(meters: Double): Double = meters.coerceIn(accuracyM)

    fun jitter(meters: Double): Double = meters.coerceIn(jitterMeters)

    fun routeSpeed(mps: Double): Double = mps.coerceIn(routeSpeedMps)

    fun altitude(meters: Double): Double = meters.coerceIn(altitudeM)

    fun satellites(count: Int): Int = count.coerceIn(satellites)

    fun stepCadence(spm: Int): Int = spm.coerceIn(stepCadenceSpm)

    fun cadenceVariability(percent: Int): Int = percent.coerceIn(stepCadenceVariability)

    fun cadencePeriod(seconds: Int): Int = seconds.coerceIn(stepCadencePeriodSec)

    fun history(count: Int): Int = count.coerceIn(historyLimit)
}
