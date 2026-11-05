package com.geostep.core.model

/**
 * 一次模拟的全部参数（不可变快照）。
 * 速度统一 m/s（UI 显示 km/h 时换算）；bearing 单位度；accuracy 单位米。
 */
data class SimConfig(
    val position: LatLng,
    val altitude: Double = 0.0,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    val accuracyM: Float = 5f,
    val speedAccuracyMps: Float = 0.5f,
    val bearingAccuracyDeg: Float = 1.0f,
    val verticalAccuracyM: Float = 1.0f,
    val satellites: Int = 12,
    val jitterEnabled: Boolean = false,
    val jitterMeters: Double = 0.0,
    /** 是否同时向 network provider 报告（仅 ProviderEngine 支持）。 */
    val networkProviderToo: Boolean = false,
    /** LSPosed output format only; official test providers always retain Android's mock flag. */
    val reportMock: Boolean = true,
    /**
     * 步频（步/分）：0 = 按速度自动推算（步行 92 左右、跑步 160+），
     * 40..[MAX_STEP_CADENCE_SPM] = 固定步频。静止时始终不产生步数。
     */
    val stepCadenceSpm: Int = STEP_CADENCE_AUTO,
    /**
     * 步频浮动幅度（百分比）：0 = 恒定节拍，1..[MAX_STEP_CADENCE_VARIABILITY] = 在平均值附近
     * 做周期性的自然起伏（真实步频不会一分不差）。
     */
    val stepCadenceVariabilityPercent: Int = DEFAULT_STEP_CADENCE_VARIABILITY,
    /** 浮动周期（秒）：一个起伏回合的时长。 */
    val stepCadencePeriodSec: Int = DEFAULT_STEP_CADENCE_PERIOD_SEC,
) {
    init {
        require(accuracyM > 0f) { "accuracy must be > 0" }
        require(satellites in 0..64) { "satellites out of range: $satellites" }
        require(jitterMeters >= 0.0) { "jitterMeters must be >= 0" }
        require(speedMps >= 0f) { "speed must be >= 0" }
        require(stepCadenceSpm in STEP_CADENCE_AUTO..MAX_STEP_CADENCE_SPM) {
            "stepCadenceSpm out of range: $stepCadenceSpm"
        }
        require(stepCadenceVariabilityPercent in 0..MAX_STEP_CADENCE_VARIABILITY) {
            "stepCadenceVariabilityPercent out of range: $stepCadenceVariabilityPercent"
        }
        require(stepCadencePeriodSec in MIN_STEP_CADENCE_PERIOD_SEC..MAX_STEP_CADENCE_PERIOD_SEC) {
            "stepCadencePeriodSec out of range: $stepCadencePeriodSec"
        }
    }

    fun withPosition(position: LatLng, bearingDeg: Float? = null): SimConfig = copy(
        position = position,
        bearingDeg = bearingDeg ?: this.bearingDeg,
    )

    companion object {
        /** 抖动半径上限，防止用户输入把坐标抖出合理范围。 */
        const val MAX_JITTER_METERS = 50.0

        /** 步频「自动」档。 */
        const val STEP_CADENCE_AUTO = 0

        /** 固定步频上限（300 步/分 = 5 步/秒，已高于人类极限，留出测试余量）。 */
        const val MAX_STEP_CADENCE_SPM = 300

        /** 步频浮动幅度上限（百分比）。 */
        const val MAX_STEP_CADENCE_VARIABILITY = StepCadence.MAX_VARIABILITY_PERCENT

        /** 默认浮动幅度：8%，看起来像真人而不是节拍器。 */
        const val DEFAULT_STEP_CADENCE_VARIABILITY = StepCadence.DEFAULT_VARIABILITY_PERCENT

        /** 浮动周期边界与默认值（秒）。 */
        const val MIN_STEP_CADENCE_PERIOD_SEC = StepCadence.MIN_PERIOD_SEC
        const val MAX_STEP_CADENCE_PERIOD_SEC = StepCadence.MAX_PERIOD_SEC
        const val DEFAULT_STEP_CADENCE_PERIOD_SEC = StepCadence.DEFAULT_PERIOD_SEC
    }
}
