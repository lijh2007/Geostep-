package com.geostep.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.hardware.*
import android.location.*
import android.os.*
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/** Public APIs only. Records matches and lifecycle, never real coordinates or raw NMEA. */
@SuppressLint("MissingPermission")
class GnssSensorProbeActivity : Activity(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val events = JSONArray()
    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private lateinit var view: TextView
    private var beginning = 0L
    private var closed = false
    private var removed = false
    private var expectedSatellites = 12
    private var expectedBearing = 0f
    private val sensorCounts = mutableMapOf<Int, Int>()
    private var delayMs = 0L
    private var lastStepAt = 0L
    private var stepEvents = 0
    private var lastCounter = -1f
    private val callback = object : GnssStatus.Callback() {
        override fun onStarted() = record("gnss-started")
        override fun onStopped() = record("gnss-stopped")
        override fun onFirstFix(ttffMillis: Int) = record("gnss-first-fix")
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var matches = status.satelliteCount == expectedSatellites
            for (i in 0 until status.satelliteCount) {
                matches = matches && status.getSvid(i) == i % 32 + 1 &&
                    status.getConstellationType(i) == (if (i < 32) GnssStatus.CONSTELLATION_GPS else GnssStatus.CONSTELLATION_BEIDOU) &&
                    abs(status.getCn0DbHz(i) - (30f + i % 10)) < 0.001 && status.usedInFix(i) &&
                    status.hasCarrierFrequencyHz(i)
            }
            record("gnss-status", JSONObject().put("matchesExpected", matches).put("count", status.satelliteCount))
        }
    }
    private val nmea = OnNmeaMessageListener { sentence, timestamp ->
        val body = sentence.substringAfter('$').substringBefore('*')
        val checksum = body.fold(0) { a, c -> a xor c.code }
        val checksumValid = sentence.substringAfter('*', "").trim().toIntOrNull(16) == checksum
        val fields = body.split(',')
        val kind = fields.firstOrNull()?.takeLast(3)
        if (kind == "GGA" || kind == "RMC") {
            val index = if (kind == "GGA") 2 else 3
            val matches = runCatching {
                val lat = coordinate(fields[index], fields[index + 1])
                val lng = coordinate(fields[index + 2], fields[index + 3])
                val distance = FloatArray(1)
                Location.distanceBetween(39.9087, 116.3975, lat, lng, distance)
                distance[0] < 25f
            }.getOrDefault(false)
            val consistent = if (kind == "GGA") fields.getOrNull(7)?.toIntOrNull() == expectedSatellites
                else fields.getOrNull(9) == SimpleDateFormat("ddMMyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timestamp))
            record("nmea-$kind", JSONObject().put("matchesExpected", matches).put("checksumValid", checksumValid).put("consistent", consistent))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply { textSize = 14f; setPadding(24, 28, 24, 24) }
        setContentView(view)
        beginning = SystemClock.elapsedRealtime()
        expectedSatellites = intent.getIntExtra("satellites", 12)
        expectedBearing = intent.getFloatExtra("bearing", 0f)
        delayMs = intent.getLongExtra("delayMs", 0L)
        lm = getSystemService(LocationManager::class.java)
        sm = getSystemService(SensorManager::class.java)
        record("begin", JSONObject().put("uid", Process.myUid()))
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val status = GnssStatus.Builder().addSatellite(GnssStatus.CONSTELLATION_GPS, 1, 30f, 15f, 0f, true, true, true, true, 1_575_420_000f, true, 28f).build()
                record("public-builder", JSONObject().put("passed", status.satelliteCount == 1 && status.usedInFix(0)))
            }.onFailure { record("public-builder", JSONObject().put("error", it.javaClass.simpleName)) }
        }
        runCatching { lm.registerGnssStatusCallback(callback, handler) }
            .onSuccess { record("gnss-register", JSONObject().put("accepted", it)) }
            .onFailure { record("gnss-register", JSONObject().put("error", it.javaClass.simpleName)) }
        runCatching { lm.addNmeaListener(nmea, handler) }
            .onSuccess { record("nmea-register", JSONObject().put("accepted", it)) }
            .onFailure { record("nmea-register", JSONObject().put("error", it.javaClass.simpleName)) }
        // 监听器可以延后注册，留出外部注入原生库的时间窗口。
        handler.postDelayed({ registerSensors() }, delayMs)
        handler.postDelayed({ release(); record("unregistered"); handler.postDelayed({ removed = true }, 1000) }, delayMs + 16_000)
        handler.postDelayed({ record("complete"); closed = true }, delayMs + 19_000)
    }

    private fun registerSensors() {
        for (type in listOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY,
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR,
        )) {
            val sensor = sm.getDefaultSensor(type) ?: continue
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        record("sensors-registered")
        runCatching {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            record(
                "last-known",
                JSONObject()
                    .put("lat", last?.latitude ?: 0.0).put("present", last != null)
                    .put("lng", last?.longitude ?: 0.0)
                    .put("mock", last?.isFromMockProvider ?: false),
            )
        }.onFailure { record("last-known", JSONObject().put("error", it.javaClass.simpleName)) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(LocationManager.GPS_PROVIDER, null, mainExecutor) { location ->
                    record(
                        "current-location",
                        JSONObject()
                            .put("lat", location?.latitude ?: 0.0).put("present", location != null)
                            .put("lng", location?.longitude ?: 0.0),
                    )
                }
            }
        }.onFailure { record("current-location", JSONObject().put("error", it.javaClass.simpleName)) }
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        record(
                            "location-callback",
                            JSONObject()
                                .put("lat", location.latitude)
                                .put("lng", location.longitude)
                                .put("speed", location.speed.toDouble())
                                .put("mock", location.isFromMockProvider),
                        )
                    }
                },
                Looper.getMainLooper(),
            )
            record("location-register")
        }.onFailure { record("location-register", JSONObject().put("error", it.javaClass.simpleName)) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val type = event.sensor.type
        val count = sensorCounts.getOrDefault(type, 0)
        sensorCounts[type] = count + 1
        if (type == Sensor.TYPE_STEP_DETECTOR) {
            val now = SystemClock.elapsedRealtime()
            val interval = if (lastStepAt == 0L) 0L else now - lastStepAt
            lastStepAt = now
            stepEvents += 1
            if (stepEvents <= 12) {
                record("step-detector", JSONObject().put("value", event.values[0]).put("intervalMs", interval))
            }
            return
        }
        if (type == Sensor.TYPE_STEP_COUNTER) {
            val total = event.values[0]
            if (count < 6) record("step-counter", JSONObject().put("value", total).put("delta", total - lastCounter))
            lastCounter = total
            return
        }
        if (count >= 3) return
        val v = event.values
        val matches = when (type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY -> abs(v[0]) < 0.0001 && abs(v[1]) < 0.0001 && abs(v[2] - 9.80665) < 0.0001
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GYROSCOPE -> v.take(3).all { abs(it) < 0.0001 }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val angle = Math.toRadians(expectedBearing.toDouble())
                abs(v[0] + 22 * sin(angle)) < 0.001 && abs(v[1] - 22 * cos(angle)) < 0.001 && abs(v[2] + 38) < 0.001
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val matrix = FloatArray(9); val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix, v)
                SensorManager.getOrientation(matrix, orientation)
                abs(sin(orientation[0].toDouble()) - sin(Math.toRadians(expectedBearing.toDouble()))) < 0.0001 &&
                    abs(cos(orientation[0].toDouble()) - cos(Math.toRadians(expectedBearing.toDouble()))) < 0.0001
            }
            else -> false
        }
        record("sensor", JSONObject().put("type", type).put("matchesSynthetic", matches))
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun coordinate(dmm: String, hemisphere: String): Double {
        val value = dmm.toDouble(); val degrees = floor(value / 100)
        return (degrees + (value - degrees * 100) / 60) * if (hemisphere == "S" || hemisphere == "W") -1 else 1
    }
    private fun record(api: String, value: JSONObject = JSONObject()) {
        if (closed) return
        value.put("api", api).put("elapsedMs", SystemClock.elapsedRealtime() - beginning).put("afterRemoval", removed)
        events.put(value)
        view.append("$api $value\n")
        File(filesDir, "gnss-report.json").writeText(JSONObject().put("runId", intent.getStringExtra("runId")).put("events", events).toString(2))
    }
    private fun release() {
        runCatching { lm.unregisterGnssStatusCallback(callback) }
        runCatching { lm.removeNmeaListener(nmea) }
        runCatching { sm.unregisterListener(this) }
    }
    override fun onDestroy() { closed = true; handler.removeCallbacksAndMessages(null); release(); super.onDestroy() }
}
