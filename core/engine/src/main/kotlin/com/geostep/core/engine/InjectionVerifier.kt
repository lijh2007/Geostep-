package com.geostep.core.engine

import com.geostep.core.model.CheckResult
import com.geostep.core.model.LatLng
import com.geostep.core.model.SimConfig
import com.geostep.core.model.StepCadence
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 注入校验：拿模块回执里“实际生效”的坐标/速度/步频，跟 App 刚刚下发的配置比对。
 *
 * 为什么需要它：控制面只保证 SET 命令被接收（enabled/revision 一致），
 * 不保证模块真的把坐标写进了 Location、把步频写进了计步器。
 * 这两件事出问题时用户看到的是“显示在模拟，但目标 App 拿到的还是真实位置/没有步数”，
 * 所以每次开始模拟后都要回读一次，把结论直接摆到界面上。
 *
 * 纯函数，无 Android 依赖，可在 JVM 上单测（见 InjectionVerifierTest）。
 */
internal object InjectionVerifier {

    /** 坐标容忍半径（米）：模块侧不抖动、不加噪时，回读坐标应当几乎等于下发坐标。 */
    const val LOCATION_TOLERANCE_M = 2.5

    /** 速度比对容忍度（m/s）。 */
    const val SPEED_TOLERANCE_MPS = 0.05

    /** 未上报（旧模块）时的哨兵值，与 [XposedEngine.ModuleStatus.cadenceSpm] 一致。 */
    const val UNREPORTED_CADENCE = -1

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun location(
        expected: LatLng,
        status: XposedEngine.ModuleStatus?,
        staleness: String? = null,
    ): CheckResult {
        status ?: return CheckResult(NAME_LOCATION, false, staleness ?: "未读到模块状态回执")
        return location(expected, status.lat, status.lng)
    }

    /** 坐标比对（App 自己从 LocationManager 读回的场景也用这条）。 */
    fun location(expected: LatLng, reportedLat: Double, reportedLng: Double): CheckResult {
        val reported = LatLng(reportedLat, reportedLng)
        if (!reported.lat.isFinite() || !reported.lng.isFinite()) {
            return CheckResult(NAME_LOCATION, false, "回读的坐标不是有效数值")
        }
        val delta = distanceMeters(expected.lat, expected.lng, reported.lat, reported.lng)
        return if (delta <= LOCATION_TOLERANCE_M) {
            CheckResult(NAME_LOCATION, true, "回读坐标与下发值偏差 %.1f 米".format(java.util.Locale.US, delta))
        } else {
            CheckResult(
                NAME_LOCATION, false,
                "回读坐标与下发值相差 %.0f 米（下发 %.5f,%.5f / 实际 %.5f,%.5f）".format(
                    java.util.Locale.US, delta, expected.lat, expected.lng, reported.lat, reported.lng,
                ),
            )
        }
    }

    fun speed(expectedMps: Float, status: XposedEngine.ModuleStatus?): CheckResult {
        status ?: return CheckResult(NAME_SPEED, false, "未读到模块状态回执")
        if (!status.speedMps.isFinite()) {
            return CheckResult(NAME_SPEED, false, "模块版本低于 0.3.17，未上报速度（请升级模块后重试）")
        }
        return speed(expectedMps, status.speedMps)
    }

    /** 速度比对（标准模式从 LocationManager 读回的场景也用这条）。 */
    fun speed(expectedMps: Float, reportedMps: Double): CheckResult {
        if (!reportedMps.isFinite()) return CheckResult(NAME_SPEED, false, "回读的速度不是有效数值")
        val delta = abs(reportedMps - expectedMps)
        return if (delta <= SPEED_TOLERANCE_MPS) {
            CheckResult(NAME_SPEED, true, "速度 %.2f m/s 已生效".format(java.util.Locale.US, reportedMps))
        } else {
            CheckResult(
                NAME_SPEED, false,
                "回读速度 %.2f m/s 与下发 %.2f m/s 不一致".format(java.util.Locale.US, reportedMps, expectedMps),
            )
        }
    }

    fun cadence(
        configuredSpm: Int,
        speedMps: Float,
        status: XposedEngine.ModuleStatus?,
        variabilityPercent: Int = 0,
    ): CheckResult {
        status ?: return CheckResult(NAME_CADENCE, false, "未读到模块状态回执")
        val expected = StepCadence.effective(speedMps, configuredSpm)
        val reported = status.cadenceSpm
        if (reported == UNREPORTED_CADENCE) {
            return CheckResult(NAME_CADENCE, false, "模块版本低于 0.3.17，未上报步频（请升级模块后重试）")
        }
        // 浮动只改变瞬时节奏，回执的是平均值；允许 ±1 步/分的取整误差。
        if (reported in (expected - 1)..(expected + 1)) {
            val sway = if (variabilityPercent > 0) "，浮动 ±$variabilityPercent%" else ""
            val detail = when {
                expected == 0 -> "静止不产生步数（符合预期）"
                configuredSpm == StepCadence.AUTO -> "自动步频 $expected 步/分$sway 已生效"
                else -> "固定步频 $expected 步/分$sway 已生效"
            }
            return CheckResult(NAME_CADENCE, true, detail)
        }
        return CheckResult(
            NAME_CADENCE, false,
            "模块回执步频 $reported 步/分，预期 $expected 步/分（配置 $configuredSpm，速度 ${"%.2f".format(java.util.Locale.US, speedMps)} m/s）",
        )
    }

    /** 三条一起校验；[staleReason] 用于状态回执不可用时的说明。 */
    fun verify(cfg: SimConfig, status: XposedEngine.ModuleStatus?, staleReason: String? = null): List<CheckResult> {
        if (status == null) {
            val reason = staleReason ?: "未读到模块状态回执（模块未加载或未勾选作用域）"
            return listOf(CheckResult(NAME_LOCATION, false, reason), CheckResult(NAME_CADENCE, false, reason))
        }
        return listOf(
            location(cfg.position, status),
            speed(cfg.speedMps, status),
            cadence(cfg.stepCadenceSpm, cfg.speedMps, status, cfg.stepCadenceVariabilityPercent),
        )
    }

    const val NAME_LOCATION = "模拟坐标回写"
    const val NAME_SPEED = "模拟速度回写"
    const val NAME_CADENCE = "模拟步频回写"
}
