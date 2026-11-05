package com.geostep.module

/**
 * 模块侧步频换算入口。
 *
 * 真正的实现已上移到 [com.geostep.core.model.StepCadence]：
 * App 侧要用同一套换算去校验“模块报回来的实际步频”，模块与 App 必须是同一份公式，
 * 否则注入校验会误报。这里保留同名 API，模块内部调用点（SensorHooks / SensorSynth /
 * StatusWriter / FakeEvidence）与既有单测无需改动。
 */
internal object StepCadence {
    const val MAX_SPM = com.geostep.core.model.StepCadence.MAX_SPM
    const val MIN_AUTO_SPM = com.geostep.core.model.StepCadence.MIN_AUTO_SPM
    const val AUTO = com.geostep.core.model.StepCadence.AUTO
    const val MAX_VARIABILITY_PERCENT = com.geostep.core.model.StepCadence.MAX_VARIABILITY_PERCENT
    const val DEFAULT_VARIABILITY_PERCENT =
        com.geostep.core.model.StepCadence.DEFAULT_VARIABILITY_PERCENT
    const val MIN_PERIOD_SEC = com.geostep.core.model.StepCadence.MIN_PERIOD_SEC
    const val MAX_PERIOD_SEC = com.geostep.core.model.StepCadence.MAX_PERIOD_SEC
    const val DEFAULT_PERIOD_SEC = com.geostep.core.model.StepCadence.DEFAULT_PERIOD_SEC

    fun effective(speedMps: Float, configuredSpm: Int): Int =
        com.geostep.core.model.StepCadence.effective(speedMps, configuredSpm)

    /** 某一时刻的瞬时步频（含浮动）。 */
    fun instantSpm(meanSpm: Int, nowMs: Long, variabilityPercent: Int, periodSec: Int): Double =
        com.geostep.core.model.StepCadence.instantSpm(meanSpm, nowMs, variabilityPercent, periodSec)

    /** 平均步频 + 浮动一步到位。 */
    fun spmAt(
        speedMps: Float,
        configuredSpm: Int,
        nowMs: Long,
        variabilityPercent: Int = DEFAULT_VARIABILITY_PERCENT,
        periodSec: Int = DEFAULT_PERIOD_SEC,
    ): Double = com.geostep.core.model.StepCadence.spmAt(
        speedMps, configuredSpm, nowMs, variabilityPercent, periodSec,
    )

    /** 相邻两次步事件的间隔（毫秒，整数步频）。 */
    fun intervalMs(spm: Int): Long = com.geostep.core.model.StepCadence.intervalMs(spm)

    /** 相邻两次步事件的间隔（毫秒，含浮动的瞬时步频）。 */
    fun intervalMs(spm: Double): Long = com.geostep.core.model.StepCadence.intervalMs(spm)

    /** 步态基频（Hz）；供加速度计/陀螺仪相位与步事件同相。 */
    fun gaitHz(spm: Int): Double = com.geostep.core.model.StepCadence.gaitHz(spm)
}
