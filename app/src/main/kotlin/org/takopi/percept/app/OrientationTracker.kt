package org.takopi.percept.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.SessionTimeBase
import org.takopi.percept.core.trace.TraceSink
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Pure math: where does the back camera point, given the device-to-world
 * rotation matrix (row-major 3x3, world = X east / Y north / Z up)? The back
 * camera looks along the device -Z axis, so its world direction is the
 * negated third column of the matrix.
 */
object CameraPoseMath {
    data class Pose(val elevationCentiDeg: Long, val azimuthCentiDeg: Long)

    fun fromRotationMatrix(r: FloatArray): Pose {
        require(r.size >= 9) { "need a 3x3 rotation matrix" }
        val east = -r[2].toDouble()
        val north = -r[5].toDouble()
        val up = -r[8].toDouble()
        val elevation = Math.toDegrees(asin(up.coerceIn(-1.0, 1.0)))
        val azimuth = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
        return Pose(
            elevationCentiDeg = (elevation * 100.0).toLong().coerceIn(-9_000, 9_000),
            azimuthCentiDeg = (azimuth * 100.0).toLong().mod(36_000L).coerceAtMost(35_999),
        )
    }
}

/**
 * Emits a pose when the camera direction meaningfully changes (wrap-aware
 * azimuth) or on a stationary heartbeat — low-rate like every modality.
 */
class CameraPoseGate(
    private val minAngleCentiDeg: Long = 1_500,
    private val minIntervalNanos: Long = 60_000_000_000L,
) {
    private var lastElevation: Long? = null
    private var lastAzimuth: Long = 0
    private var lastTNanos: Long = 0

    fun shouldEmit(tNanos: Long, elevationCentiDeg: Long, azimuthCentiDeg: Long): Boolean {
        val previous = lastElevation
        if (previous == null ||
            tNanos - lastTNanos >= minIntervalNanos ||
            abs(elevationCentiDeg - previous) >= minAngleCentiDeg ||
            azimuthDelta(azimuthCentiDeg, lastAzimuth) >= minAngleCentiDeg
        ) {
            lastElevation = elevationCentiDeg
            lastAzimuth = azimuthCentiDeg
            lastTNanos = tNanos
            return true
        }
        return false
    }

    companion object {
        fun azimuthDelta(a: Long, b: Long): Long {
            val diff = abs(a - b) % 36_000
            return minOf(diff, 36_000 - diff)
        }
    }
}

/** Rotation-vector sensor → gated camera-pose events. No permission needed. */
class OrientationTracker(
    context: Context,
    private val sink: TraceSink,
    private val timeBase: SessionTimeBase,
    private val gate: CameraPoseGate = CameraPoseGate(),
) : SensorEventListener {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationMatrix = FloatArray(9)
    private var registered = false

    @Volatile
    private var accuracyLabel: String? = null

    fun start(): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return false
        registered = sensorManager.registerListener(
            this, sensor, SensorManager.SENSOR_DELAY_NORMAL,
        )
        return registered
    }

    fun stop() {
        if (registered) {
            sensorManager.unregisterListener(this)
            registered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.timestamp < timeBase.monotonicAnchorNanos) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val pose = CameraPoseMath.fromRotationMatrix(rotationMatrix)
        val tNanos = timeBase.elapsedNanos(event.timestamp)
        if (!gate.shouldEmit(tNanos, pose.elevationCentiDeg, pose.azimuthCentiDeg)) return
        sink.trySubmit(
            PerceptionEvent.CameraPose(
                tNanos = tNanos,
                elevationCentiDeg = pose.elevationCentiDeg,
                azimuthCentiDeg = pose.azimuthCentiDeg,
                accuracy = accuracyLabel,
            ),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        accuracyLabel = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
            else -> "unreliable"
        }
    }
}
