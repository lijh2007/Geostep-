package com.geostep.module

import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat
import java.util.Locale

class CompatibilityRegressionTest {
    private val valid = """{"e":1,"h":1,"lat":39.9,"lng":116.4,"spd":1.2,"sat":12,"ts":1700000000000,"et":1000,"boot":4,"session":10,"alt":50,"brg":90}"""

    @Test fun `persisted state expires and cannot survive a reboot`() {
        val state = AppScopeConfig.parse(valid)!!
        // 注入侧窗口是 5 分钟（见 AppScopeConfig.INJECT_FRESH_MS）：Root 模式下 App 可能被系统
        // 冻结、镜像停止续期，15 秒窗口会让已注入进程立刻失效，所以放宽；但绝不能跨重启存活。
        assertThat(state.isFresh(1000, 4)).isTrue()
        assertThat(state.isFresh(299_999, 4)).isTrue()
        assertThat(state.isFresh(301_000, 4)).isFalse()
        assertThat(state.isFresh(2000, 5)).isFalse()
        assertThat(state.isFresh(999, 4)).isFalse()
    }
    @Test fun `old incomplete malformed and nonfinite mirrors fail closed`() {
        for (input in listOf("""{"e":1}""", valid + " garbage", valid.replace("39.9", "999"),
            valid.replace("1.2", "1e999"), valid.replace("\"et\":1000", "\"et\":1000,\"et\":1001"),
            valid.replace("\"e\":1", "\"e\":2"))) {
            assertThat(AppScopeConfig.parse(input)).isNull()
        }
    }
    @Test fun `nmea date uses supplied UTC date and formatting ignores device locale`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val gga = FakeEvidence.nmeaGga(39.9087, 116.3975, 50.0, 1793318400000L, 17)
            assertThat(gga).contains("3954.5220,N,11623.8500,E,1,17,0.9,50.0")
            assertThat(FakeEvidence.nmeaRmc(39.9087, 116.3975, 1f, 90f, 1793318400000L)).contains(",301026,,")
        } finally { Locale.setDefault(old) }
    }
    @Test fun `no satellites has no valid gga fix`() {
        assertThat(FakeEvidence.nmeaGga(0.0, 0.0, 0.0, 0, 0)).contains(",E,0,00,")
    }
    class Slice(val items: List<Any>)
    @Test fun `return adapter preserves actual list wrapper and rejects wrong types`() {
        val entries = listOf<Any>("fixture")
        assertThat((ResultAdapter.list(Slice::class.java, null, entries) as Slice).items).isEqualTo(entries)
        val original = Any()
        assertThat(ResultAdapter.checked(Slice::class.java, 1, original)).isSameInstanceAs(original)
        assertThat(ResultAdapter.checked(Int::class.javaPrimitiveType!!, 1, 0)).isEqualTo(1)
    }
    @Test fun `finite subscriptions stop at request max updates`() {
        val budget = DeliveryBudget(RequestPolicy(maxUpdates = 1), 100)
        assertThat(budget.ready(100, null)).isTrue()
        budget.delivered(100)
        assertThat(budget.ready(101, 100f)).isFalse()
    }
    @Test fun `subscriptions respect expiry interval and displacement`() {
        val budget = DeliveryBudget(RequestPolicy(durationMs = 2000, intervalMs = 500, minDistanceM = 5f), 100)
        budget.delivered(100)
        assertThat(budget.ready(599, 5f)).isFalse()
        assertThat(budget.ready(600, 4f)).isFalse()
        assertThat(budget.ready(600, 5f)).isTrue()
        assertThat(budget.ready(2100, 5f)).isFalse()
    }
    @Test fun `paused step counter holds accumulated value`() {
        val steps = StepAccumulator()
        // 固定 60 步/分：每运动 1 秒 +1 步，暂停期间不计步。
        assertThat(steps.counter(100f, 1f, 1, 0, 60)).isEqualTo(100f)
        assertThat(steps.counter(100f, 0f, 1, 3000, 60)).isEqualTo(103f)
        assertThat(steps.counter(100f, 0f, 1, 9000, 60)).isEqualTo(103f)
        assertThat(steps.counter(100f, 1f, 1, 10000, 60)).isEqualTo(103f)
        assertThat(steps.counter(100f, 1f, 1, 11000, 60)).isEqualTo(104f)
        assertThat(steps.counter(108f, 1f, 2, 20000, 60)).isEqualTo(108f)
    }
    @Test fun `step counter follows configured cadence`() {
        val steps = StepAccumulator()
        assertThat(steps.counter(0f, 1.2f, 1, 0, 120)).isEqualTo(0f)
        assertThat(steps.counter(0f, 1.2f, 1, 1000, 120)).isEqualTo(2f)   // 1s @120 = 2 步
        assertThat(steps.counter(0f, 0f, 1, 6000, 120)).isEqualTo(12f)    // 运动 5s = 10 步
        assertThat(steps.counter(0f, 0f, 1, 9000, 120)).isEqualTo(12f)    // 静止不再累计
    }
    @Test fun `step counter auto cadence scales with speed`() {
        val steps = StepAccumulator()
        assertThat(steps.counter(0f, 1f, 1, 0)).isEqualTo(0f)
        assertThat(steps.counter(0f, 1f, 1, 60_000, 0)).isEqualTo(92f)    // 自动：约 92 步/分
    }
    @Test fun `stationary detector emits no zero-valued fake step`() {
        val steps = StepAccumulator()
        assertThat(steps.detector(0f, 1, 0, 60)).isNull()
        assertThat(steps.detector(1f, 1, 500, 60)).isEqualTo(1f)
        assertThat(steps.detector(1f, 1, 900, 60)).isNull()
        assertThat(steps.detector(1f, 1, 1500, 60)).isEqualTo(1f)
    }
    @Test fun `detector spacing follows configured cadence`() {
        val steps = StepAccumulator()
        assertThat(steps.detector(1f, 1, 0, 180)).isEqualTo(1f)           // 180 步/分 → 333ms 一步
        assertThat(steps.detector(1f, 1, 300, 180)).isNull()
        assertThat(steps.detector(1f, 1, 333, 180)).isEqualTo(1f)
    }
    @Test fun `cadence mirror field is optional but validated when present`() {
        assertThat(AppScopeConfig.parse(valid)!!.cadenceSpm).isEqualTo(0)   // 旧模块不写 cad
        val withCadence = valid.dropLast(1) + ""","cad":120}"""
        assertThat(AppScopeConfig.parse(withCadence)!!.cadenceSpm).isEqualTo(120)
        for (bad in listOf(
            valid.dropLast(1) + ""","cad":301}""",
            valid.dropLast(1) + ""","cad":120.5}""",
            valid.dropLast(1) + ""","cad":-1}""",
        )) assertThat(AppScopeConfig.parse(bad)).isNull()
    }
}
