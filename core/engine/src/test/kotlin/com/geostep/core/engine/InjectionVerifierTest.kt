package com.geostep.core.engine

import com.google.common.truth.Truth.assertThat
import com.geostep.core.model.LatLng
import com.geostep.core.model.SimConfig
import com.geostep.core.model.StepCadence
import org.junit.jupiter.api.Test

class InjectionVerifierTest {

    private val position = LatLng(39.9075, 116.3972)

    private fun config(speed: Float = 1.4f, cadence: Int = StepCadence.AUTO) = SimConfig(
        position = position,
        speedMps = speed,
        stepCadenceSpm = cadence,
    )

    private fun status(
        lat: Double = position.lat,
        lng: Double = position.lng,
        speed: Double = 1.4,
        cadence: Int = StepCadence.effective(1.4f, StepCadence.AUTO),
        version: String = "0.3.17",
    ) = XposedEngine.ModuleStatus(
        enabled = true,
        revision = 1,
        lat = lat,
        lng = lng,
        hooksInstalled = true,
        uptimeMs = 1_000,
        updatedAt = 10_000,
        moduleVersion = version,
        speedMps = speed,
        cadenceSpm = cadence,
    )

    @Test
    fun `location within tolerance passes`() {
        val check = InjectionVerifier.location(position, position.lat + 0.00001, position.lng)
        assertThat(check.ok).isTrue()
        assertThat(check.name).isEqualTo(InjectionVerifier.NAME_LOCATION)
    }

    @Test
    fun `location far away fails`() {
        val check = InjectionVerifier.location(position, position.lat + 0.01, position.lng)
        assertThat(check.ok).isFalse()
        assertThat(check.message).contains("相差")
    }

    @Test
    fun `speed mismatch fails and unreported speed asks for upgrade`() {
        assertThat(InjectionVerifier.speed(1.4f, status(speed = 1.4)).ok).isTrue()
        assertThat(InjectionVerifier.speed(1.4f, status(speed = 3.0)).ok).isFalse()
        assertThat(InjectionVerifier.speed(1.4f, status(speed = Double.NaN)).message).contains("0.3.17")
    }

    @Test
    fun `fixed cadence must be reported back verbatim`() {
        val ok = InjectionVerifier.cadence(120, 1.4f, status(cadence = 120))
        assertThat(ok.ok).isTrue()
        assertThat(ok.message).contains("固定步频 120 步/分")
        val bad = InjectionVerifier.cadence(120, 1.4f, status(cadence = 60))
        assertThat(bad.ok).isFalse()
        assertThat(bad.message).contains("预期 120 步/分")
    }

    @Test
    fun `cadence tolerance absorbs rounding and reports the sway`() {
        // 回执的是平均步频，取整误差 ±1 步/分不应判失败
        assertThat(InjectionVerifier.cadence(120, 1.4f, status(cadence = 121)).ok).isTrue()
        assertThat(InjectionVerifier.cadence(120, 1.4f, status(cadence = 119)).ok).isTrue()
        val sway = InjectionVerifier.cadence(120, 1.4f, status(cadence = 120), variabilityPercent = 8)
        assertThat(sway.ok).isTrue()
        assertThat(sway.message).contains("浮动 ±8%")
    }

    @Test
    fun `auto cadence expects the value derived from speed`() {
        val expected = StepCadence.effective(1.0f, StepCadence.AUTO)
        assertThat(expected).isEqualTo(92)
        val ok = InjectionVerifier.cadence(StepCadence.AUTO, 1.0f, status(speed = 1.0, cadence = expected))
        assertThat(ok.ok).isTrue()
        assertThat(ok.message).contains("自动步频 92 步/分")
    }

    @Test
    fun `unreported cadence is a hard failure`() {
        val check = InjectionVerifier.cadence(120, 1.4f, status(cadence = InjectionVerifier.UNREPORTED_CADENCE))
        assertThat(check.ok).isFalse()
        assertThat(check.message).contains("0.3.17")
    }

    @Test
    fun `stationary cadence expectation is zero`() {
        val ok = InjectionVerifier.cadence(StepCadence.AUTO, 0f, status(speed = 0.0, cadence = 0))
        assertThat(ok.ok).isTrue()
        assertThat(ok.message).contains("静止")
    }

    @Test
    fun `missing status yields location and cadence failures with the stale reason`() {
        val checks = InjectionVerifier.verify(config(), null, "状态回执已过期")
        assertThat(checks).hasSize(2)
        assertThat(checks.map { it.name })
            .containsExactly(InjectionVerifier.NAME_LOCATION, InjectionVerifier.NAME_CADENCE)
        assertThat(checks.all { !it.ok }).isTrue()
        assertThat(checks.first().message).isEqualTo("状态回执已过期")
    }

    @Test
    fun `verify reports three checks when the status is fresh`() {
        val checks = InjectionVerifier.verify(config(speed = 1.4f, cadence = 120), status(cadence = 120))
        assertThat(checks).hasSize(3)
        assertThat(checks.all { it.ok }).isTrue()
    }

    @Test
    fun `distance helper is accurate for short offsets`() {
        val meters = InjectionVerifier.distanceMeters(position.lat, position.lng, position.lat + 0.00009, position.lng)
        assertThat(meters).isWithin(1.5).of(10.0)
    }
}
