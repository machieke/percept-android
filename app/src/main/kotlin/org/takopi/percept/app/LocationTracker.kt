package org.takopi.percept.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.SessionTimeBase
import org.takopi.percept.core.trace.TraceSink
import kotlin.math.cos

/**
 * Decides which raw fixes become location-fix events: the first fix, then
 * movement beyond [minDistanceMeters] or [minIntervalNanos] elapsed —
 * location is a low-rate modality like everything else in the trace.
 * Pure logic, host-tested; floats are fine here (only canonical payloads
 * must be integer).
 */
class LocationFixGate(
    private val minDistanceMeters: Double = 10.0,
    private val minIntervalNanos: Long = 60_000_000_000L,
) {
    private var lastLatE7: Long? = null
    private var lastLonE7: Long? = null
    private var lastTNanos: Long = 0

    fun shouldEmit(tNanos: Long, latE7: Long, lonE7: Long): Boolean {
        val lat = lastLatE7
        val lon = lastLonE7
        if (lat == null || lon == null) {
            record(tNanos, latE7, lonE7)
            return true
        }
        if (tNanos - lastTNanos >= minIntervalNanos ||
            distanceMeters(lat, lon, latE7, lonE7) >= minDistanceMeters
        ) {
            record(tNanos, latE7, lonE7)
            return true
        }
        return false
    }

    private fun record(tNanos: Long, latE7: Long, lonE7: Long) {
        lastTNanos = tNanos
        lastLatE7 = latE7
        lastLonE7 = lonE7
    }

    companion object {
        private const val METERS_PER_E7_DEGREE = 111_320.0 / 1e7

        /** Equirectangular approximation — plenty for a 10 m gate. */
        fun distanceMeters(latE7A: Long, lonE7A: Long, latE7B: Long, lonE7B: Long): Double {
            val dLat = (latE7B - latE7A) * METERS_PER_E7_DEGREE
            val meanLatRadians = Math.toRadians(((latE7A + latE7B) / 2) / 1e7)
            val dLon = (lonE7B - lonE7A) * METERS_PER_E7_DEGREE * cos(meanLatRadians)
            return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
        }
    }
}

/**
 * Feeds gated GPS/network fixes into the trace sink. Inert without location
 * permission — the rest of the session is unaffected.
 */
class LocationTracker(
    private val context: Context,
    private val sink: TraceSink,
    private val timeBase: SessionTimeBase,
    private val gate: LocationFixGate = LocationFixGate(),
) : LocationListener {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var started = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (!hasPermission()) return false
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter(locationManager.allProviders::contains)
        if (providers.isEmpty()) return false
        try {
            for (provider in providers) {
                locationManager.requestLocationUpdates(
                    provider,
                    REQUEST_INTERVAL_MILLIS,
                    REQUEST_DISTANCE_METERS,
                    this,
                    Looper.getMainLooper(),
                )
                locationManager.getLastKnownLocation(provider)?.let(::onLocationChanged)
            }
        } catch (_: SecurityException) {
            return false
        }
        started = true
        return true
    }

    fun stop() {
        if (started) {
            locationManager.removeUpdates(this)
            started = false
        }
    }

    override fun onLocationChanged(location: Location) {
        val monotonic = location.elapsedRealtimeNanos
        if (monotonic < timeBase.monotonicAnchorNanos) {
            // A stale last-known fix from before the session started: the
            // session timeline cannot represent it.
            return
        }
        val tNanos = timeBase.elapsedNanos(monotonic)
        val latE7 = (location.latitude * 1e7).toLong()
        val lonE7 = (location.longitude * 1e7).toLong()
        if (!gate.shouldEmit(tNanos, latE7, lonE7)) return
        sink.trySubmit(
            PerceptionEvent.LocationFix(
                tNanos = tNanos,
                latE7 = latE7,
                lonE7 = lonE7,
                accuracyCm = if (location.hasAccuracy()) (location.accuracy * 100f).toLong() else 0,
                altitudeCm = if (location.hasAltitude()) (location.altitude * 100.0).toLong() else null,
                provider = location.provider ?: "unknown",
                speedCmPerS = if (location.hasSpeed()) {
                    (location.speed * 100f).toLong().coerceAtLeast(0)
                } else {
                    null
                },
                bearingCentiDeg = if (location.hasBearing()) {
                    (location.bearing * 100f).toLong().mod(36_000L)
                } else {
                    null
                },
            ),
        )
    }

    @Deprecated("Deprecated in API 29, still invoked on some devices")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    companion object {
        const val REQUEST_INTERVAL_MILLIS: Long = 5_000
        const val REQUEST_DISTANCE_METERS: Float = 5f
    }
}
