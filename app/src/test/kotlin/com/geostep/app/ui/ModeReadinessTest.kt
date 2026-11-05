package com.geostep.app.ui

import com.geostep.core.model.CheckResult
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ModeReadinessTest {

    private fun check(name: String, ok: Boolean, message: String? = null) =
        CheckResult(name = name, ok = ok, message = message)

    @Test
    fun `每个模式的成败都由自己的自检项决定`() {
        assertThat(requiredCheckNames("provider")).containsExactly("定位权限", "模拟位置 AppOps")
        assertThat(requiredCheckNames("xposed"))
            .containsExactly("定位权限", "LSPosed 模块安装", "模块版本", "系统控制通道", "系统定位钩子")
        assertThat(requiredCheckNames("root")).containsExactly("定位权限", "Root 权限", "Root 独立注入")
        assertThat(requiredCheckNames("unknown")).isEqualTo(requiredCheckNames("root"))
    }

    @Test
    fun `别的模式的失败项不会拦住当前模式`() {
        val checks = listOf(
            check("定位权限", false, "缺少定位权限"),
            check("模拟位置 AppOps", true),
            check("Root 权限", false, "su 不可用"),
            check("LSPosed 模块安装", false, "未安装模块"),
        )
        assertThat(blockingChecksFor("provider", checks).map { it.name }).containsExactly("定位权限")
        assertThat(blockingChecksFor("root", checks).map { it.name }).containsExactly("定位权限", "Root 权限")
        assertThat(blockingChecksFor("xposed", checks).map { it.name })
            .containsExactly("定位权限", "LSPosed 模块安装")
    }

    @Test
    fun `全部通过时没有阻断项也没有原因`() {
        val checks = listOf(
            check("定位权限", true),
            check("Root 权限", true),
            check("Root 独立注入", true),
            check("系统控制通道", false, "LSPosed 通道未建立"),
        )
        assertThat(blockingChecksFor("root", checks)).isEmpty()
        assertThat(blockedReasonOf("root", checks)).isNull()
    }

    @Test
    fun `失败原因带上模式名与可操作说明`() {
        val checks = listOf(
            check("定位权限", true),
            check("Root 权限", false, "没有可用的 su 入口"),
            check("Root 独立注入", true),
        )
        val reason = blockedReasonOf("root", checks)
        assertThat(reason).isEqualTo("Root 直连模式尚不可用：没有可用的 su 入口")
    }

    @Test
    fun `没有说明时回退到自检项名字`() {
        val reason = blockedReasonOf("provider", listOf(check("模拟位置 AppOps", false)))
        assertThat(reason).isEqualTo("标准模式尚不可用：模拟位置 AppOps")
    }

    @Test
    fun `模式名覆盖三种取值`() {
        assertThat(modeLabelOf("provider")).isEqualTo("标准模式")
        assertThat(modeLabelOf("root")).isEqualTo("Root 直连模式")
        assertThat(modeLabelOf("xposed")).isEqualTo("LSPosed 模式")
        assertThat(modeLabelOf("")).isEqualTo("LSPosed 模式")
    }

    @Test
    fun `包名校验接受常见形式并拒绝空格与单段名`() {
        assertThat(isPackageName("com.mi.health")).isTrue()
        assertThat(isPackageName("com.lptiyu.tanke")).isTrue()
        assertThat(isPackageName("  com.geostep.probe  ")).isTrue()
        assertThat(isPackageName("com")).isFalse()
        assertThat(isPackageName("com..health")).isFalse()
        assertThat(isPackageName("com.mi health")).isFalse()
        assertThat(isPackageName("")).isFalse()
    }

    @Test
    fun `自动注入只补没注入过的合法包名`() {
        val remembered = setOf("com.mi.health", "com.lptiyu.tanke", "bad", "com.geostep.probe")
        assertThat(packagesToInject(remembered, listOf("com.mi.health")))
            .containsExactly("com.geostep.probe", "com.lptiyu.tanke")
            .inOrder()
        assertThat(packagesToInject(emptySet(), emptyList())).isEmpty()
        assertThat(packagesToInject(remembered, remembered)).isEmpty()
    }
}
