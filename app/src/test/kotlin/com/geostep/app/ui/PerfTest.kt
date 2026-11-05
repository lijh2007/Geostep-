package com.geostep.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 低端机 / 低功耗降级参数的边界：这些值直接决定老设备上的内存、线程与刷新频率，
 * 改动时应当先看这里的断言，别让「优化」变成「更吃资源」。
 */
class PerfTest {

    @Test
    fun `low end devices get shorter splash and smaller caches`() {
        assertThat(Perf.splashScale(lowEnd = true)).isEqualTo(0.55f)
        assertThat(Perf.splashScale(lowEnd = false)).isEqualTo(1f)
        assertThat(Perf.tileCacheBytes(lowEnd = true)).isEqualTo(48L * 1024 * 1024)
        assertThat(Perf.tileCacheBytes(lowEnd = false)).isEqualTo(160L * 1024 * 1024)
        assertThat(Perf.tileThreads(lowEnd = true)).isEqualTo(1)
        assertThat(Perf.tileThreads(lowEnd = false)).isEqualTo(2)
    }

    @Test
    fun `low end budget never exceeds the regular budget`() {
        assertThat(Perf.tileCacheBytes(lowEnd = true)).isLessThan(Perf.tileCacheBytes(lowEnd = false))
        assertThat(Perf.tileThreads(lowEnd = true)).isAtMost(Perf.tileThreads(lowEnd = false))
        assertThat(Perf.splashScale(lowEnd = true)).isAtMost(Perf.splashScale(lowEnd = false))
    }

    @Test
    fun `tick interval keeps the configured value unless low power is on`() {
        assertThat(Perf.tickIntervalMs(1_000L, lowPower = false)).isEqualTo(1_000L)
        assertThat(Perf.tickIntervalMs(200L, lowPower = false)).isEqualTo(200L)
    }

    @Test
    fun `low power raises the tick interval to two seconds but never shortens it`() {
        assertThat(Perf.tickIntervalMs(1_000L, lowPower = true)).isEqualTo(2_000L)
        assertThat(Perf.tickIntervalMs(200L, lowPower = true)).isEqualTo(2_000L)
        assertThat(Perf.tickIntervalMs(2_000L, lowPower = true)).isEqualTo(2_000L)
    }

    @Test
    fun `tile dpi scaling is off on low end devices or in low power mode`() {
        assertThat(Perf.tileScaleToDpi(lowEnd = false, lowPower = false)).isTrue()
        assertThat(Perf.tileScaleToDpi(lowEnd = true, lowPower = false)).isFalse()
        assertThat(Perf.tileScaleToDpi(lowEnd = false, lowPower = true)).isFalse()
    }

    @Test
    fun `tile threads drop to one in low power mode`() {
        assertThat(Perf.tileThreadsFor(lowEnd = false, lowPower = true)).isEqualTo(1)
        assertThat(Perf.tileThreadsFor(lowEnd = false, lowPower = false)).isEqualTo(2)
        assertThat(Perf.tileThreadsFor(lowEnd = true, lowPower = false)).isEqualTo(1)
    }
}
