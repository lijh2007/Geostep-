package com.geostep.core.model

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * 步频（步/分）语义与换算的唯一实现。
 *
 * 三处必须使用同一套换算，否则模块注入的步频与 App 侧校验对不上：
 * 1. 模块 [com.geostep.module.StepCadence]（计步器 / 计步事件 / 步态相位）；
 * 2. App 侧注入校验（读取模块回执的“实际生效步频”后与本类的期望值比对）；
 * 3. 证据合成（FakeEvidence 的步数推算）。
 *
 * 约定：
 * - [AUTO]（0）表示按速度自动推算 —— 静止时不出步数，步行约 90 步/分，跑步 160 步/分左右；
 * - 固定步频只在运动时生效（速度 <= 0 一律 0 步），避免原地刷步数；
 * - 上限 [MAX_SPM] 防止超出真实人体范围，也让 UI 与协议校验有共同边界；
 * - 真实步频不会恒定：[instantSpm] 用两条不可公度正弦做确定性的“呼吸式”浮动，
 *   均值为 [effective]，模块与 App 各自算出的曲线完全一致（无随机数、无状态）。
 */
object StepCadence {
    /** 自动档：按速度推算步频。 */
    const val AUTO = 0

    /** 步频上限（步/分）。 */
    const val MAX_SPM = 300

    /** 自动推算的下限（步/分）：极慢的移动也按这个步频注入，避免除零与“走不动”。 */
    const val MIN_AUTO_SPM = 40

    /** 浮动幅度上限（百分比）：30% 已经接近快慢交替走。 */
    const val MAX_VARIABILITY_PERCENT = 30

    /** 默认浮动幅度（百分比）：让注入的节奏看起来像真人，而不是节拍器。 */
    const val DEFAULT_VARIABILITY_PERCENT = 8

    /** 浮动周期的下限 / 上限 / 默认值（秒）：一个起伏周期大致几十秒，接近真实配速漂移。 */
    const val MIN_PERIOD_SEC = 10
    const val MAX_PERIOD_SEC = 120
    const val DEFAULT_PERIOD_SEC = 45

    /** 瞬时步频的物理下限：再低就不是“走”了（浮动到此处即被夹住）。 */
    private const val MIN_INSTANT_SPM = 10.0

    /**
     * 实际生效的平均步频。
     *
     * @param speedMps 当前移动速度（米/秒）
     * @param configuredSpm 用户设定（[AUTO] 或 1..[MAX_SPM]）
     */
    fun effective(speedMps: Float, configuredSpm: Int): Int {
        if (!speedMps.isFinite() || speedMps <= 0f) return 0
        if (configuredSpm > 0) return configuredSpm.coerceAtMost(MAX_SPM)
        // 步长随速度增长：走 1 m/s 约 0.65 m/步 → 92 步/分；跑 3 m/s 约 1.11 m/步 → 162 步/分。
        val stride = 0.42 + 0.23 * speedMps
        return (speedMps * 60.0 / stride).roundToInt().coerceIn(MIN_AUTO_SPM, MAX_SPM)
    }

    /**
     * 某一时刻的瞬时步频（含浮动）。纯函数：同一 `nowMs` 必然得到同一结果，
     * 因此模块注入与 App 校验可以各算一次而不会漂移。
     *
     * @param meanSpm 平均步频（[effective] 的结果）
     * @param nowMs 单调时钟毫秒（模块用 elapsedRealtime，证据合成用会话相对时间）
     * @param variabilityPercent 浮动幅度百分比（0 = 恒定，上限 [MAX_VARIABILITY_PERCENT]）
     * @param periodSec 一个起伏周期（秒）
     */
    fun instantSpm(
        meanSpm: Int,
        nowMs: Long,
        variabilityPercent: Int = DEFAULT_VARIABILITY_PERCENT,
        periodSec: Int = DEFAULT_PERIOD_SEC,
    ): Double {
        if (meanSpm <= 0) return 0.0
        val percent = variabilityPercent.coerceIn(0, MAX_VARIABILITY_PERCENT)
        if (percent == 0) return meanSpm.toDouble()
        val period = periodSec.coerceIn(MIN_PERIOD_SEC, MAX_PERIOD_SEC) * 1000.0
        val t = nowMs.toDouble()
        // 0.62 的主周期 + 0.38 的快速分量（周期比 0.41 不可公度）：均值仍为 0，不会系统性偏移。
        val wave = 0.62 * sin(2 * PI * t / period) + 0.38 * sin(2 * PI * t / (period * 0.41) + 1.7)
        val amplitude = meanSpm * percent / 100.0
        return (meanSpm + amplitude * wave).coerceIn(MIN_INSTANT_SPM, MAX_SPM.toDouble())
    }

    /** 便捷组合：按速度与设定算平均步频，再叠加浮动。 */
    fun spmAt(
        speedMps: Float,
        configuredSpm: Int,
        nowMs: Long,
        variabilityPercent: Int = DEFAULT_VARIABILITY_PERCENT,
        periodSec: Int = DEFAULT_PERIOD_SEC,
    ): Double = instantSpm(effective(speedMps, configuredSpm), nowMs, variabilityPercent, periodSec)

    /** 计步事件间隔（毫秒，整数版）：60 步/分 → 1000ms；180 步/分 → 333ms。 */
    fun intervalMs(spm: Int): Long = (60_000L / spm.coerceIn(1, MAX_SPM)).coerceAtLeast(100L)

    /** 计步事件间隔（毫秒，含浮动的瞬时步频）：下限 80ms 防止异常值把事件打爆。 */
    fun intervalMs(spm: Double): Long =
        (60_000.0 / spm.coerceIn(1.0, MAX_SPM.toDouble())).roundToLong().coerceAtLeast(80L)

    /** 步态基频（Hz）：用于加速度计等波形的相位，使峰值与步事件同相。 */
    fun gaitHz(spm: Int): Double = if (spm <= 0) 0.0 else spm / 60.0

    /** 配置值是否在允许范围内。 */
    fun isValid(configuredSpm: Int): Boolean = configuredSpm in AUTO..MAX_SPM

    /** 浮动幅度是否在允许范围内。 */
    fun isVariabilityValid(percent: Int): Boolean = percent in 0..MAX_VARIABILITY_PERCENT

    /** 浮动周期是否在允许范围内。 */
    fun isPeriodValid(periodSec: Int): Boolean = periodSec in MIN_PERIOD_SEC..MAX_PERIOD_SEC
}
