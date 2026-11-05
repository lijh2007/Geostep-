package com.geostep.module

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** A single stable proxy per listener, shared by all overloads and sensor types. */
internal object SensorHooks {
    private class Entry(val original: SensorEventListener) : SensorEventListener2 {
        val sensors = mutableSetOf<Sensor>()
        private val steps = StepAccumulator()
        private val motion = SensorMotion()
        override fun onSensorChanged(event: SensorEvent) {
            val state = AppScopeConfig.snapshot(AppScopeHooks.appContext())
            StepPump.remember(event)
            val type = event.sensor?.type
            if (!AppScopeHooks.isActive(state) || event.values.isEmpty() || type == null) {
                original.onSensorChanged(event)
                return
            }
            if (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR) {
                if (!SensorSynth.supports(type)) { original.onSensorChanged(event); return }
                // 加速度/陀螺/旋转向量/磁力：改值后转发，再恢复（框架可能复用数组）
                val backup = event.values.copyOf()
                runCatching { SensorSynth.apply(event, state, event.timestamp / 1_000_000L, motion.turnRate(state)) }
                try {
                    original.onSensorChanged(event)
                } finally {
                    backup.copyInto(event.values)
                }
                return
            }
            val old = event.values[0]
            val now = SystemClock.elapsedRealtime()
            lastRealStepAt = now
            if (type == Sensor.TYPE_STEP_COUNTER) lastRealCounter = old
            val value = if (type == Sensor.TYPE_STEP_COUNTER) steps.counter(
                old, state.speed, state.session, now,
                state.cadenceSpm, state.cadenceVariabilityPercent, state.cadencePeriodSec,
            ) else steps.detector(
                state.speed, state.session, now,
                state.cadenceSpm, state.cadenceVariabilityPercent, state.cadencePeriodSec,
            ) ?: return
            try { event.values[0] = value; original.onSensorChanged(event) }
            finally { event.values[0] = old } // framework may reuse this array
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = original.onAccuracyChanged(sensor, accuracy)
        override fun onFlushCompleted(sensor: Sensor?) { (original as? SensorEventListener2)?.onFlushCompleted(sensor) }

        // ---- 真实传感器静止时按步频自驱动事件（否则运动 App 永远看不到步数） ----
        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var pumping = false
        @Volatile private var lastRealStepAt = 0L
        private var stepSensor: Sensor? = null
        private var lastReported = 0f

        /** 真实计步器的最后一个累计值，泵从它继续累加（否则运动 App 会看到“步数归零”）。 */
        private var lastRealCounter = 0f
        private var pumpSeeded = false
        private var pumpDelivered = 0

        fun startPump(sensor: Sensor) {
            if (sensor.type != Sensor.TYPE_STEP_COUNTER && sensor.type != Sensor.TYPE_STEP_DETECTOR) return
            stepSensor = sensor
            if (pumping) return
            pumping = true
            // 立即给一次机会：轮询式 App（注册后马上读一次再注销）也能拿到步数。
            handler.post(pumpRunnable)
        }

        fun stopPump() {
            pumping = false
            handler.removeCallbacks(pumpRunnable)
        }

        private val pumpRunnable = Runnable { pumpTick() }

        private fun schedule(delayMs: Long) {
            if (pumping) handler.postDelayed(pumpRunnable, delayMs.coerceAtLeast(MIN_PUMP_DELAY_MS))
        }

        private fun pumpTick() {
            if (!pumping) return
            val sensor = stepSensor ?: run { stopPump(); return }
            val context = AppScopeHooks.appContext()
            if (context == null) { schedule(IDLE_RETRY_MS); return }
            val state = AppScopeConfig.snapshot(context)
            if (!AppScopeHooks.isActive(state) || state.speed <= 0f) { schedule(IDLE_BACKOFF_MS); return }
            val now = SystemClock.elapsedRealtime()
            // 真实传感器还在出事件时让位，避免同一秒出双份步数
            if (lastRealStepAt != 0L && now - lastRealStepAt < REAL_SENSOR_QUIET_MS) { schedule(IDLE_RETRY_MS); return }
            val spm = StepCadence.spmAt(
                state.speed, state.cadenceSpm, now, state.cadenceVariabilityPercent, state.cadencePeriodSec,
            )
            if (spm <= 0.5) { schedule(IDLE_RETRY_MS); return }
            val interval = StepCadence.intervalMs(spm)
            val value = if (sensor.type == Sensor.TYPE_STEP_COUNTER) {
                if (!pumpSeeded) {
                    pumpSeeded = true
                    lastReported = steps.counter(
                        lastRealCounter, state.speed, state.session, now,
                        state.cadenceSpm, state.cadenceVariabilityPercent, state.cadencePeriodSec,
                    )
                }
                val next = steps.counter(
                    lastReported, state.speed, state.session, now,
                    state.cadenceSpm, state.cadenceVariabilityPercent, state.cadencePeriodSec,
                )
                if (next <= lastReported) { schedule(interval); return }
                next
            } else {
                steps.detector(
                    state.speed, state.session, now,
                    state.cadenceSpm, state.cadenceVariabilityPercent, state.cadencePeriodSec,
                ) ?: run { schedule(interval); return }
            }
            val synthetic = StepPump.event(sensor, value)
            if (synthetic == null) {
                schedule(interval)
                return
            }
            lastReported = value
            pumpDelivered += 1
            if (pumpDelivered == 1 || pumpDelivered % PUMP_LOG_EVERY == 0) {
                ModuleLog.i(
                    "step pump: delivered=$pumpDelivered type=${sensor.type} " +
                        "spm=${spm.roundToInt()} value=$value pkg=${AppScopeHooks.selfPackage ?: "?"}",
                )
            }
            runCatching { original.onSensorChanged(synthetic) }
                .onFailure { StepPump.reportDeliveryFailure(it); stopPump() }
            schedule(interval)
        }
    }
    private val entries = IdentityHashMap<SensorEventListener, Entry>()
    private val depth = ThreadLocal.withInitial { 0 }

    /** 自驱动事件的最小调度延迟。 */
    private const val MIN_PUMP_DELAY_MS = 80L
    private const val IDLE_RETRY_MS = 500L

    /**
     * 待机退避：目标应用在后台、或模拟正处于静止时，没必要每 500ms 醒一次。
     * 步频为 0 时按 3 秒轮询，能显著减少待机唤醒（低版本安卓上尤其是）。
     */
    private const val IDLE_BACKOFF_MS = 3_000L
    /** 真实计步器最近一次出事件的时间窗：窗内让位，避免同一段路出双份步数。 */
    private const val REAL_SENSOR_QUIET_MS = 1500L
    private const val PUMP_LOG_EVERY = 20

    private val logged = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 每类事件只在进程生命周期内记录一次，避免日志刷屏，同时能确认 hook 是否真的进了目标进程。 */
    private fun logOnce(key: String, message: () -> String) {
        if (logged.add(key)) runCatching { ModuleLog.i(message()) }
    }

    fun install(api: XposedInterface) {
        hookListenerApis(api)
        hookSensorQueries(api)
    }

    /**
     * 覆盖监听器注册的全部入口：registerListener / unregisterListener / flush，
     * 以及触发式传感器 requestTriggerSensor / cancelTriggerSensor（部分健康 App 走这条路）。
     */
    private fun hookListenerApis(api: XposedInterface) {
        for (method in SensorManager::class.java.declaredMethods) {
            if (method.name !in setOf("registerListener", "unregisterListener", "flush", "requestTriggerSensor", "cancelTriggerSensor")) continue
            if (method.parameterTypes.firstOrNull() != SensorEventListener::class.java) continue
            runCatching { api.hook(method).intercept(XposedInterface.Hooker { chain ->
                val nesting = depth.get() ?: 0
                if (nesting > 0) return@Hooker chain.proceed()
                val listener = chain.args.firstOrNull() as? SensorEventListener ?: return@Hooker chain.proceed()
                val registering = method.name == "registerListener" || method.name == "requestTriggerSensor"
                val entry = synchronized(entries) { entries[listener] }
                    ?: if (registering) Entry(listener) else return@Hooker chain.proceed()
                val sensor = chain.args.filterIsInstance<Sensor>().firstOrNull()
                val args = chain.args.toTypedArray().apply { this[0] = entry }
                depth.set(nesting + 1)
                try {
                    val result = chain.proceed(args)
                    synchronized(entries) {
                        if (registering && result == true && sensor != null) {
                            entry.sensors.add(sensor); entries[listener] = entry
                            entry.startPump(sensor)
                            if (sensor.type == Sensor.TYPE_STEP_COUNTER || sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                                logOnce("reg:${method.name}:${sensor.type}") {
                                    "listener registered via ${method.name} type=${sensor.type} pkg=${AppScopeHooks.selfPackage ?: "?"}"
                                }
                            }
                        } else if (!registering) {
                            if (sensor == null) entry.sensors.clear() else entry.sensors.remove(sensor)
                            if (entry.sensors.isEmpty()) { entry.stopPump(); entries.remove(listener) }
                        }
                    }
                    result
                } finally { depth.set(nesting) }
            }) }.onFailure { ModuleLog.w("sensor ${method.name} install failed", it) }
        }
    }

    /** 只记录、不改行为：目标 App 到底向系统要了哪些传感器，这是“读不到步数”的决定性证据。 */
    private fun hookSensorQueries(api: XposedInterface) {
        for (name in setOf("getDefaultSensor", "getSensorList")) {
            val method = SensorManager::class.java.declaredMethods.firstOrNull { it.name == name } ?: continue
            runCatching { api.hook(method).intercept(XposedInterface.Hooker { chain ->
                val asked = (chain.args.firstOrNull() as? Int)?.takeIf { it == Sensor.TYPE_STEP_COUNTER || it == Sensor.TYPE_STEP_DETECTOR }
                if (asked != null) {
                    logOnce("query:$name:$asked") {
                        "app queried $name type=$asked pkg=${AppScopeHooks.selfPackage ?: "?"}"
                    }
                }
                chain.proceed()
            }) }.onFailure { ModuleLog.w("sensor $name hook failed", it) }
        }
    }
}

/** Counter progression is monotonic within a session; pausing holds the value. */
internal class StepAccumulator {
    private var session = Long.MIN_VALUE
    private var lastAt = 0L
    private var lastSpeed = 0f
    private var total = 0.0
    private var lastDetectorAt = Long.MIN_VALUE
    private var detectorSession = Long.MIN_VALUE
    fun counter(
        real: Float, speed: Float, id: Long, now: Long,
        cadenceSpm: Int = StepCadence.AUTO,
        variabilityPercent: Int = 0,
        periodSec: Int = StepCadence.DEFAULT_PERIOD_SEC,
    ): Float {
        if (id != session) { session = id; total = real.toDouble(); lastAt = now; lastSpeed = speed }
        val dt = (now - lastAt).coerceAtLeast(0)
        // 用上一帧速度：暂停立即停表，恢复立即续算；步频按当前时刻取瞬时值（含自然浮动）。
        val spm = if (lastSpeed > 0f) {
            StepCadence.spmAt(lastSpeed, cadenceSpm, now, variabilityPercent, periodSec)
        } else 0.0
        if (spm > 0.0) total += dt / 1000.0 * spm / 60.0
        lastAt = now; lastSpeed = speed
        return kotlin.math.floor(total).toFloat()
    }

    fun detector(
        speed: Float, id: Long, now: Long,
        cadenceSpm: Int = StepCadence.AUTO,
        variabilityPercent: Int = 0,
        periodSec: Int = StepCadence.DEFAULT_PERIOD_SEC,
    ): Float? {
        if (id != detectorSession) { detectorSession = id; lastDetectorAt = Long.MIN_VALUE }
        val spm = StepCadence.spmAt(speed, cadenceSpm, now, variabilityPercent, periodSec)
        if (spm <= 0.5) return null
        if (lastDetectorAt != Long.MIN_VALUE && now - lastDetectorAt < StepCadence.intervalMs(spm)) return null
        lastDetectorAt = now
        return 1f
    }
}
