package com.geostep.app.ui

import com.geostep.core.model.SimConfig
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ParameterLimitsTest {

    @Test
    fun `interval clamps to the same range the slider uses`() {
        assertThat(ParameterLimits.interval(50)).isEqualTo(200L)
        assertThat(ParameterLimits.interval(1_000)).isEqualTo(1_000L)
        assertThat(ParameterLimits.interval(9_000)).isEqualTo(2_000L)
    }

    @Test
    fun `accuracy and altitude clamp to their physical ranges`() {
        assertThat(ParameterLimits.accuracy(0.1)).isEqualTo(1.0)
        assertThat(ParameterLimits.accuracy(3.0)).isEqualTo(3.0)
        assertThat(ParameterLimits.accuracy(99.0)).isEqualTo(50.0)

        assertThat(ParameterLimits.altitude(-900.0)).isEqualTo(-500.0)
        assertThat(ParameterLimits.altitude(0.0)).isEqualTo(0.0)
        assertThat(ParameterLimits.altitude(9_500.0)).isEqualTo(9_000.0)
    }

    @Test
    fun `jitter stays inside the sim config ceiling`() {
        assertThat(ParameterLimits.jitter(-1.0)).isEqualTo(0.0)
        assertThat(ParameterLimits.jitter(SimConfig.MAX_JITTER_METERS + 10)).isEqualTo(SimConfig.MAX_JITTER_METERS)
    }

    @Test
    fun `route speed and satellites keep the documented bounds`() {
        assertThat(ParameterLimits.routeSpeed(0.01)).isEqualTo(0.1)
        assertThat(ParameterLimits.routeSpeed(1.4)).isEqualTo(1.4)
        assertThat(ParameterLimits.routeSpeed(40.0)).isEqualTo(30.0)

        assertThat(ParameterLimits.satellites(-3)).isEqualTo(0)
        assertThat(ParameterLimits.satellites(12)).isEqualTo(12)
        assertThat(ParameterLimits.satellites(65)).isEqualTo(64)
    }

    @Test
    fun `cadence never exceeds what the module accepts`() {
        assertThat(ParameterLimits.stepCadence(-5)).isEqualTo(SimConfig.STEP_CADENCE_AUTO)
        assertThat(ParameterLimits.stepCadence(120)).isEqualTo(120)
        assertThat(ParameterLimits.stepCadence(999)).isEqualTo(SimConfig.MAX_STEP_CADENCE_SPM)

        assertThat(ParameterLimits.cadenceVariability(-1)).isEqualTo(0)
        assertThat(ParameterLimits.cadenceVariability(99)).isEqualTo(SimConfig.MAX_STEP_CADENCE_VARIABILITY)

        assertThat(ParameterLimits.cadencePeriod(1)).isEqualTo(SimConfig.MIN_STEP_CADENCE_PERIOD_SEC)
        assertThat(ParameterLimits.cadencePeriod(600)).isEqualTo(SimConfig.MAX_STEP_CADENCE_PERIOD_SEC)
    }

    @Test
    fun `history limit stays positive and bounded`() {
        assertThat(ParameterLimits.history(0)).isEqualTo(10)
        assertThat(ParameterLimits.history(100)).isEqualTo(100)
        assertThat(ParameterLimits.history(99_999)).isEqualTo(2_000)
    }
}
