package com.geostep.module

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** 步频换算的纯逻辑测试（不依赖 Android 运行时）。 */
class StepCadenceTest {

    @Test fun `stationary never produces steps even with a fixed cadence`() {
        assertThat(StepCadence.effective(0f, 120)).isEqualTo(0)
        assertThat(StepCadence.effective(-1f, 120)).isEqualTo(0)
        assertThat(StepCadence.effective(Float.NaN, 120)).isEqualTo(0)
    }

    @Test fun `auto cadence follows speed`() {
        // 1 m/s → stride 0.65m → 92.3 步/分
        assertThat(StepCadence.effective(1.0f, StepCadence.AUTO)).isEqualTo(92)
        // 3 m/s（跑步）→ stride 1.11m → 162 步/分
        assertThat(StepCadence.effective(3.0f, StepCadence.AUTO)).isEqualTo(162)
        // 极慢速被下限夹住，极快速按公式增长（30 m/s → 246 步/分）
        assertThat(StepCadence.effective(0.1f, StepCadence.AUTO)).isEqualTo(StepCadence.MIN_AUTO_SPM)
        assertThat(StepCadence.effective(30f, StepCadence.AUTO)).isEqualTo(246)
    }

    @Test fun `fixed cadence wins over speed and is capped`() {
        assertThat(StepCadence.effective(1.0f, 120)).isEqualTo(120)
        assertThat(StepCadence.effective(5.0f, 75)).isEqualTo(75)
        assertThat(StepCadence.effective(5.0f, 500)).isEqualTo(StepCadence.MAX_SPM)
    }

    @Test fun `interval and gait frequency derive from cadence`() {
        assertThat(StepCadence.intervalMs(60)).isEqualTo(1000L)
        assertThat(StepCadence.intervalMs(120)).isEqualTo(500L)
        assertThat(StepCadence.intervalMs(180)).isEqualTo(333L)
        assertThat(StepCadence.intervalMs(0)).isEqualTo(60_000L)   // 0 不会被用于发射，防御性取 1 步/分
        assertThat(StepCadence.gaitHz(120)).isEqualTo(2.0)
        assertThat(StepCadence.gaitHz(0)).isEqualTo(0.0)
    }

    @Test fun `zero variability keeps the cadence exactly constant`() {
        assertThat(StepCadence.instantSpm(120, 12_345L, 0, 45)).isEqualTo(120.0)
        assertThat(StepCadence.spmAt(1.0f, 120, 99_000L, 0, 45)).isEqualTo(120.0)
        assertThat(StepCadence.spmAt(0f, 120, 99_000L, 8, 45)).isEqualTo(0.0)
    }

    @Test fun `variability is deterministic for the same instant`() {
        val first = StepCadence.instantSpm(120, 7_000L, 8, 45)
        val second = StepCadence.instantSpm(120, 7_000L, 8, 45)
        assertThat(first).isEqualTo(second)
        // 不同时刻必然不同（否则“浮动”名不副实）
        assertThat(StepCadence.instantSpm(120, 7_000L, 8, 45))
            .isNotEqualTo(StepCadence.instantSpm(120, 23_000L, 8, 45))
    }

    @Test fun `variability stays inside its band and averages near the mean`() {
        val mean = 120
        val samples = (0 until 900).map { i -> StepCadence.instantSpm(mean, i * 100L, 15, 45) }
        val band = mean * 0.15
        assertThat(samples.min()).isAtLeast(mean - band)
        assertThat(samples.max()).isAtMost(mean + band)
        // 一个完整周期内的平均应接近设定值（起伏是对称的）
        assertThat(samples.average()).isWithin(2.0).of(mean.toDouble())
    }

    @Test fun `variability is clamped by physical bounds`() {
        // 极端浮动不允许把步频推到 0 或超过上限
        assertThat(StepCadence.instantSpm(12, 0L, StepCadence.MAX_VARIABILITY_PERCENT, 45)).isAtLeast(10.0)
        assertThat(StepCadence.instantSpm(StepCadence.MAX_SPM, 0L, StepCadence.MAX_VARIABILITY_PERCENT, 45))
            .isAtMost(StepCadence.MAX_SPM.toDouble())
    }
}
