package com.geostep.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 低端机 / 老版本系统的降级开关。
 *
 * 目标：在 Android 8/9（API 26–28）这类低版本、以及低内存设备上保持流畅，
 * 具体做法是把「一次性动画时长」和「地图瓦片缓存」按设备能力收一收，
 * 而不是砍功能——功能必须完整可用，只是少花点绘制与内存。
 */
internal object Perf {

    /**
     * 判定是否按低端处理：
     * - Android 8/9（API ≤ 28）：Compose 渲染在老设备上更容易掉帧；
     * - 系统标记为低内存设备（`ActivityManager.isLowRamDevice`）。
     */
    fun isLowEnd(context: Context): Boolean {
        if (Build.VERSION.SDK_INT <= 28) return true
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return manager?.isLowRamDevice == true
    }

    /** 开屏动画总时长缩放：低端机 0.55，常规 1.0。 */
    fun splashScale(lowEnd: Boolean): Float = if (lowEnd) 0.55f else 1f

    /** 地图瓦片磁盘缓存上限（字节）：低端机 48MB，常规 160MB。 */
    fun tileCacheBytes(lowEnd: Boolean): Long =
        if (lowEnd) 48L * 1024 * 1024 else 160L * 1024 * 1024

    /** 地图瓦片下载线程数：低端机 1，常规 2。 */
    fun tileThreads(lowEnd: Boolean): Int = if (lowEnd) 1 else 2

    /**
     * 低功耗模式下的 tick 间隔：至少 2 秒。
     *
     * 模拟循环每 tick 要算位置、写 provider、发状态，1 秒一次在低端机上会明显抢 CPU；
     * 路线本身是匀速插值，拉长间隔只影响刷新观感，不影响最终轨迹。
     */
    fun tickIntervalMs(baseMs: Long, lowPower: Boolean): Long = if (lowPower) maxOf(baseMs, 2_000L) else baseMs

    /** 低功耗时地图不做 DPI 缩放、瓦片线程压到 1，省掉重采样与并发下载的开销。 */
    fun tileScaleToDpi(lowEnd: Boolean, lowPower: Boolean): Boolean = !(lowEnd || lowPower)

    fun tileThreadsFor(lowEnd: Boolean, lowPower: Boolean): Short = if (lowPower) 1 else tileThreads(lowEnd).toShort()
}
