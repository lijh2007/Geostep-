package com.geostep.module

import android.hardware.Sensor
import android.hardware.SensorEvent
import kotlin.math.*

/** A flat virtual device. All motion/orientation outputs derive from one coordinate convention. */
internal object SensorSynth {
    private const val GRAVITY = 9.80665f
    fun supports(type: Int) = type in setOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY, Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)

    fun apply(event: SensorEvent, state: ScopeState, nowMs: Long, turnRate: Float = 0f): Boolean {
        return apply(event.sensor?.type ?: return false, event.values, state, nowMs, turnRate)
    }

    fun apply(type: Int, values: FloatArray, state: ScopeState, nowMs: Long, turnRate: Float = 0f): Boolean {
        if (!supports(type) || values.size < 3) return false
        // 步态相位跟随步频（含自然浮动）：加速度峰值与 STEP_COUNTER/DETECTOR 的步事件同相。
        val meanSpm = StepCadence.effective(state.speed, state.cadenceSpm)
        val spm = StepCadence.instantSpm(
            meanSpm, nowMs, state.cadenceVariabilityPercent, state.cadencePeriodSec,
        )
        val gaitHz = if (spm > 0) spm / 60.0 else 1.6
        val phase = 2 * PI * gaitHz * nowMs / 1000.0
        // 真实步行：垂直峰值约 1.0~1.5 m/s²、侧向 0.1~0.2 m/s²、前后 0.2 左右。
        // 振幅必须到这个量级，靠加速度计做峰值检测/带通判步的运动 App 才数得到步；
        // 旧版只有 0.04/0.35 m/s²，第三方软件基本无感。
        val strength = min(state.speed / 1.4f, 1.5f).coerceAtLeast(0f)
        val vertical = 1.15 * strength * (sin(phase) + 0.32 * sin(2 * phase))
        val lateral = 0.18 * strength * cos(phase)
        val forward = 0.22 * strength * sin(phase + 0.4)
        val linear = floatArrayOf(lateral.toFloat(), forward.toFloat(), vertical.toFloat())
        val heading = Math.toRadians(state.bearing.toDouble())
        when (type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                linear.copyInto(values); values[2] += GRAVITY
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> linear.copyInto(values)
            Sensor.TYPE_GRAVITY -> { values[0] = 0f; values[1] = 0f; values[2] = GRAVITY }
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                values[0] = 0f; values[1] = 0f; values[2] = -turnRate
            }
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                values[0] = (-22 * sin(heading)).toFloat()
                values[1] = (22 * cos(heading)).toFloat()
                values[2] = -38f
            }
            else -> {
                // Android azimuth is clockwise from north; quaternion rotation is about Z.
                var z = sin(-heading / 2).toFloat()
                val w = cos(-heading / 2).toFloat()
                if (values.size == 3 && w < 0) z = -z
                values[0] = 0f; values[1] = 0f; values[2] = z
                if (values.size > 3) values[3] = w
                if (values.size > 4) values[4] = 0.05f
            }
        }
        if (type in setOf(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
                Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)) {
            for (index in 3 until min(values.size, 6)) values[index] = 0f
        }
        return true
    }
}

/** Changes in heading, not arbitrary oscillation, determine angular velocity. */
internal class SensorMotion {
    private var last: ScopeState? = null
    private var rate = 0f
    fun turnRate(state: ScopeState): Float {
        val previous = last
        if (previous == null || state.session != previous.session || state.elapsedMs <= previous.elapsedMs) {
            if (previous == null || state.session != previous.session || state.elapsedMs < previous.elapsedMs) rate = 0f
        } else {
            val delta = ((state.bearing - previous.bearing + 540f) % 360f) - 180f
            rate = (Math.toRadians(delta.toDouble()) * 1000 / (state.elapsedMs - previous.elapsedMs)).toFloat()
        }
        last = state
        return rate
    }
}
