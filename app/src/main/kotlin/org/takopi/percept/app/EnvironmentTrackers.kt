package org.takopi.percept.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.SystemClock
import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.SessionTimeBase
import org.takopi.percept.core.trace.TraceSink

/**
 * Ambient light is log-distributed (moonlight 0.1 lx, office 400 lx, sun
 * 100k lx), so the gate fires on ratio change, not absolute deltas.
 * Pure and host-tested.
 */
class LightGate(
    private val minRatio: Double = 2.0,
    private val minIntervalNanos: Long = 120_000_000_000L,
) {
    private var lastLuxMilli: Long = -1
    private var lastTNanos: Long = 0

    fun shouldEmit(tNanos: Long, luxMilli: Long): Boolean {
        if (lastLuxMilli < 0 || tNanos - lastTNanos >= minIntervalNanos) {
            record(tNanos, luxMilli)
            return true
        }
        // +1000 milli-lux smoothing keeps darkness transitions sane.
        val a = (luxMilli + 1_000).toDouble()
        val b = (lastLuxMilli + 1_000).toDouble()
        if (maxOf(a, b) / minOf(a, b) >= minRatio) {
            record(tNanos, luxMilli)
            return true
        }
        return false
    }

    private fun record(tNanos: Long, luxMilli: Long) {
        lastTNanos = tNanos
        lastLuxMilli = luxMilli
    }
}

/** Charging flips, 5% battery bands, and a slow heartbeat. Pure. */
class PowerGate(
    private val pctBand: Long = 5,
    private val minIntervalNanos: Long = 600_000_000_000L,
) {
    private var lastCharging: Boolean? = null
    private var lastBand: Long = -1
    private var lastTNanos: Long = 0

    fun shouldEmit(tNanos: Long, charging: Boolean, batteryPct: Long): Boolean {
        val band = batteryPct / pctBand
        if (charging != lastCharging || band != lastBand ||
            tNanos - lastTNanos >= minIntervalNanos
        ) {
            lastCharging = charging
            lastBand = band
            lastTNanos = tNanos
            return true
        }
        return false
    }
}

/** Light + proximity sensors → gated environment events. No permissions. */
class EnvironmentTracker(
    context: Context,
    private val sink: TraceSink,
    private val timeBase: SessionTimeBase,
    private val lightGate: LightGate = LightGate(),
) : SensorEventListener {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var proximityThreshold = 0f
    private var lastProximityState: String? = null
    private var registered = false

    fun start(): Boolean {
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximityThreshold = (proximity?.maximumRange ?: 0f) / 2f
        var any = false
        for (sensor in listOfNotNull(light, proximity)) {
            any = sensorManager.registerListener(
                this, sensor, SensorManager.SENSOR_DELAY_NORMAL,
            ) || any
        }
        registered = any
        return any
    }

    fun stop() {
        if (registered) {
            sensorManager.unregisterListener(this)
            registered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.timestamp < timeBase.monotonicAnchorNanos) return
        val tNanos = timeBase.elapsedNanos(event.timestamp)
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val luxMilli = (event.values[0].toDouble() * 1000.0).toLong().coerceAtLeast(0)
                if (lightGate.shouldEmit(tNanos, luxMilli)) {
                    sink.trySubmit(PerceptionEvent.AmbientLight(tNanos, luxMilli))
                }
            }
            Sensor.TYPE_PROXIMITY -> {
                val state = if (event.values[0] < proximityThreshold) "near" else "far"
                if (state != lastProximityState) {
                    lastProximityState = state
                    sink.trySubmit(PerceptionEvent.Proximity(tNanos, state))
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/** Default-network identity via ConnectivityManager; SSID needs the location
 *  grant (already optional-requested) and the location-info callback flag. */
class NetworkTracker(
    context: Context,
    private val sink: TraceSink,
    private val timeBase: SessionTimeBase,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var lastKey: String? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback(
        FLAG_INCLUDE_LOCATION_INFO,
    ) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val ssid = (caps.transportInfo as? WifiInfo)?.ssid
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            emit(transport, ssid, metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }

        override fun onLost(network: Network) {
            emit(transport = "none", ssid = null, metered = false)
        }
    }

    fun start(): Boolean = try {
        connectivityManager.registerDefaultNetworkCallback(callback)
        registered = true
        true
    } catch (_: RuntimeException) {
        false
    }

    fun stop() {
        if (registered) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            registered = false
        }
    }

    private fun emit(transport: String, ssid: String?, metered: Boolean) {
        val key = "$transport|$ssid|$metered"
        if (key == lastKey) return
        lastKey = key
        val monotonic = SystemClock.elapsedRealtimeNanos()
        if (monotonic < timeBase.monotonicAnchorNanos) return
        sink.trySubmit(
            PerceptionEvent.NetworkContext(
                tNanos = timeBase.elapsedNanos(monotonic),
                transport = transport,
                ssid = ssid,
                metered = metered,
            ),
        )
    }
}

/** Battery/charging via the sticky battery broadcast. */
class PowerTracker(
    private val context: Context,
    private val sink: TraceSink,
    private val timeBase: SessionTimeBase,
    private val gate: PowerGate = PowerGate(),
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val pct = (level.toLong() * 100L / scale).coerceIn(0, 100)
            val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val monotonic = SystemClock.elapsedRealtimeNanos()
            if (monotonic < timeBase.monotonicAnchorNanos) return
            val tNanos = timeBase.elapsedNanos(monotonic)
            if (gate.shouldEmit(tNanos, charging, pct)) {
                sink.trySubmit(PerceptionEvent.PowerState(tNanos, charging, pct))
            }
        }
    }

    fun start(): Boolean {
        // Sticky broadcast: registering delivers the current state at once.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registered = true
        return true
    }

    fun stop() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }
}
