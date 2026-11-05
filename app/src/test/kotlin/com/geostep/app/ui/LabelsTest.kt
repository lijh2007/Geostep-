package com.geostep.app.ui

import com.geostep.core.model.EngineState
import com.geostep.core.model.LatLng
import com.geostep.core.model.SimError
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LabelsTest {

    @Test
    fun `坐标固定六位小数并使用点号`() {
        assertThat(coordinates(LatLng(39.904202123, 116.40739987))).isEqualTo("39.904202, 116.407400")
        assertThat(coordinates(LatLng(-0.0000004, 0.0))).isEqualTo("-0.000000, 0.000000")
    }

    @Test
    fun `距离在千米以下用米 以上用公里`() {
        assertThat(distanceLabel(0.0)).isEqualTo("0 m")
        assertThat(distanceLabel(999.4)).isEqualTo("999 m")
        assertThat(distanceLabel(1000.0)).isEqualTo("1.00 km")
        assertThat(distanceLabel(1234.56)).isEqualTo("1.23 km")
        assertThat(distanceLabel(15_000.0)).isEqualTo("15.00 km")
    }

    @Test
    fun `模拟位置被拒时引导去开发者选项`() {
        val label = errorLabel(EngineState.Failed(SimError.MOCK_APPOP_DENIED, retryable = true))
        assertThat(label).contains("开发者选项")
        assertThat(label).contains("模拟位置应用")
    }

    @Test
    fun `权限失败提示点击设置`() {
        val label = errorLabel(EngineState.Failed(SimError.PERMISSION_DENIED, retryable = true))
        assertThat(label).contains("定位权限未开启")
    }

    @Test
    fun `模块不可达提示启用模块与作用域`() {
        val label = errorLabel(EngineState.Failed(SimError.MODULE_UNREACHABLE, retryable = true))
        assertThat(label).contains("LSPosed")
        assertThat(label).contains("系统框架")
    }

    @Test
    fun `超时提示查看诊断`() {
        val label = errorLabel(EngineState.Failed(SimError.ACK_TIMEOUT, retryable = true))
        assertThat(label).contains("超时")
    }

    @Test
    fun `其他失败优先展示引擎给出的细节`() {
        val withDetail = errorLabel(EngineState.Failed(SimError.ENGINE_CRASH, retryable = false, detail = "绑定服务失败"))
        assertThat(withDetail).isEqualTo("绑定服务失败")
        val withoutDetail = errorLabel(EngineState.Failed(SimError.ENGINE_CRASH, retryable = false))
        assertThat(withoutDetail).contains("请查看诊断")
    }
}
