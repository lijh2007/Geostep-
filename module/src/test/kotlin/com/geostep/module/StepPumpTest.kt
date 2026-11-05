package com.geostep.module

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 自驱动步数事件泵只做两件可验证的事：拿不到事件对象时安静返回 `null`（绝不抛给调用方），
 * 以及在没真正发出事件前不虚增计数。真实的事件构造依赖 ART 的隐藏接口，只能在真机验证。
 */
class StepPumpTest {
    @Test
    fun `synthetic event creation returns null instead of throwing`() {
        // 单测里 android.jar 的 SensorEvent 构造器是 stub，反射实例化必然失败；
        // 这里要求它退化成 null，让上层停泵，而不是把异常抛进目标 App 的回调。
        assertThat(StepPump.event(null, 1f)).isNull()
        val before = StepPump.syntheticSteps
        assertThat(StepPump.event(null, 2f)).isNull()
        assertThat(StepPump.syntheticSteps).isEqualTo(before)
    }
}
