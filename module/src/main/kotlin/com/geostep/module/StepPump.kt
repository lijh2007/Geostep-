package com.geostep.module

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import java.lang.reflect.Constructor
import java.lang.reflect.Field

/**
 * 真实计步传感器是「事件驱动」的：手机不动就不上报事件，第三方运动 App 因此看不到任何步数。
 *
 * 这里负责在没有真实事件时**自己造事件**喂给目标 App：
 * - 事件对象用 `SensorEvent` 的构造器反射创建（AOSP 里 `SensorEvent(int valueSize)` 是 public，
 *   但在 android.jar 里被标记为非 SDK 接口，因此逐个候选构造器尝试，失败就整体停用并记一条日志）；
 * - 只创建一次构造器引用，之后复用，避免每个 tick 都反射查找；
 * - 任何失败都不会影响真实事件的转发。
 */
internal object StepPump {
    private var ctorSize: Constructor<SensorEvent>? = null
    private var ctorEmpty: Constructor<SensorEvent>? = null
    private var valuesField: Field? = null
    private var resolved = false
    private var unavailableLogged = false

    @Volatile
    var syntheticSteps: Long = 0L
        private set

    /** 创建一条只带 `values[0]` 的计步事件；返回 `null` 表示当前进程不允许构造，调用方应停用泵。 */
    /**
     * 真机实测：部分 ROM 在目标进程内禁止反射构造 SensorEvent（hidden API 策略），
     * 这时退化为「复用 App 自己收到过的真实事件对象」——只改 values/timestamp/sensor。
     */
    fun remember(template: SensorEvent) {
        if (this.template == null) this.template = template
    }

    private var template: SensorEvent? = null

    fun event(sensor: Sensor?, value: Float): SensorEvent? {
        if (sensor == null) return null
        val reused = template
        if (reused != null && ctorSize == null && ctorEmpty == null) {
            return runCatching {
                reused.sensor = sensor
                reused.timestamp = System.nanoTime()
                reused.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                reused.values[0] = value
                syntheticSteps += 1
                reused
            }.getOrNull()
        }
        resolve()
        val event = newInstance() ?: return null
        return runCatching {
            event.sensor = sensor
            event.timestamp = System.nanoTime()
            event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            val values = event.values
            if (values.isNotEmpty()) {
                values[0] = value
            } else {
                val replacement = FloatArray(1).also { it[0] = value }
                valuesField?.set(event, replacement)
            }
            syntheticSteps += 1
            event
        }.getOrNull()
    }

    private fun newInstance(): SensorEvent? {
        ctorSize?.let { ctor -> runCatching { ctor.newInstance(1) }.getOrNull()?.let { return it } }
        ctorEmpty?.let { ctor -> runCatching { ctor.newInstance() }.getOrNull()?.let { return it } }
        return null
    }

    private fun resolve() {
        if (resolved) return
        resolved = true
        val type = SensorEvent::class.java
        ctorSize = runCatching {
            type.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
        }.getOrNull()
        ctorEmpty = runCatching {
            type.getDeclaredConstructor().apply { isAccessible = true }
        }.getOrNull()
        valuesField = runCatching { type.getField("values").apply { isAccessible = true } }.getOrNull()
        if (ctorSize == null && ctorEmpty == null) {
            ModuleLog.w("step pump: no SensorEvent constructor reachable; synthetic step events disabled")
        }
    }

    fun reportDeliveryFailure(error: Throwable) {
        if (unavailableLogged) return
        unavailableLogged = true
        ModuleLog.w("step pump: synthetic event rejected, pump stopped", error)
    }
}
