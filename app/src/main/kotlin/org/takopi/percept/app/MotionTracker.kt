package org.takopi.percept.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.SessionTimeBase
import org.takopi.percept.core.trace.TraceSink
import kotlin.math.sqrt

/**
 * Run-length motion classification over windowed linear-acceleration RMS.
 * Pure and host-tested; units are integer cm/s² (floats never reach
 * canonical payloads). Deliberately NOT an inertial-navigation attempt.
 */
class MotionStateClassifier(
    private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    private val movingThresholdCmS2: Long = DEFAULT_MOVING_THRESHOLD_CM_S2,
) {
    init {
        require(windowNanos > 0) { "windowNanos must be positive" }
        require(movingThresholdCmS2 > 0) { "threshold must be positive" }
    }

    data class Segment(
        val state: String,
        val tStartNanos: Long,
        val tEndNanos: Long,
        val rmsAccelCmS2: Long,
        val peakAccelCmS2: Long,
    )

    private var windowStartNanos = -1L
    private var windowSquareSum = 0.0
    private var windowSamples = 0
    private var windowPeak = 0L

    private var runState: String? = null
    private var runStartNanos = 0L
    private var runEndNanos = 0L
    private var runSquareSum = 0.0
    private var runSamples = 0
    private var runPeak = 0L

    /** Returns a closed segment when the motion state changes. */
    fun onSample(tNanos: Long, magnitudeCmS2: Long): Segment? {
        require(tNanos >= 0) { "tNanos must be non-negative" }
        if (windowStartNanos < 0) {
            windowStartNanos = tNanos
        }
        windowSquareSum += magnitudeCmS2.toDouble() * magnitudeCmS2
        windowSamples += 1
        if (magnitudeCmS2 > windowPeak) windowPeak = magnitudeCmS2
        if (tNanos - windowStartNanos < windowNanos) {
            return null
        }
        return closeWindow(tNanos)
    }

    /** Flushes the open run (and any partial window) at session stop. */
    fun finish(): Segment? {
        var flushed: Segment? = null
        if (windowSamples > 0 && windowStartNanos >= 0) {
            flushed = closeWindow(windowStartNanos + windowNanos)
        }
        val open = runState?.let {
            Segment(it, runStartNanos, runEndNanos, runRms(), runPeak)
        }
        runState = null
        return flushed ?: open
    }

    private fun closeWindow(windowEndNanos: Long): Segment? {
        val rms = sqrt(windowSquareSum / windowSamples).toLong()
        val state = if (rms >= movingThresholdCmS2) STATE_MOVING else STATE_STILL
        var closed: Segment? = null
        if (runState != null && runState != state) {
            closed = Segment(runState!!, runStartNanos, runEndNanos, runRms(), runPeak)
            runState = null
        }
        if (runState == null) {
            runState = state
            runStartNanos = windowStartNanos
            runSquareSum = 0.0
            runSamples = 0
            runPeak = 0
        }
        runEndNanos = windowEndNanos
        runSquareSum += windowSquareSum
        runSamples += windowSamples
        if (windowPeak > runPeak) runPeak = windowPeak

        windowStartNanos = windowEndNanos
        windowSquareSum = 0.0
        windowSamples = 0
        windowPeak = 0
        return closed
    }

    private fun runRms(): Long =
        if (runSamples == 0) 0 else sqrt(runSquareSum / runSamples).toLong()

    companion object {
        const val STATE_STILL = "still"
        const val STATE_MOVING = "moving"
        const val DEFAULT_WINDOW_NANOS: Long = 2_000_000_000L

        /** 0.4 m/s² RMS of linear (gravity-removed) acceleration. */
        const val DEFAULT_MOVING_THRESHOLD_CM_S2: Long = 40
    }
}

/** Feeds the linear-acceleration sensor into the classifier and the sink. */
class MotionTracker(
    context: Context,
    private val sink: TraceSink,
    private val timeBase: SessionTimeBase,
    private val classifier: MotionStateClassifier = MotionStateClassifier(),
) : SensorEventListener {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var registered = false

    fun start(): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
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
        classifier.finish()?.let(::submit)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.timestamp < timeBase.monotonicAnchorNanos) return
        val tNanos = timeBase.elapsedNanos(event.timestamp)
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitudeCmS2 = (sqrt(x * x + y * y + z * z) * 100.0).toLong()
        classifier.onSample(tNanos, magnitudeCmS2)?.let(::submit)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun submit(segment: MotionStateClassifier.Segment) {
        sink.trySubmit(
            PerceptionEvent.MotionSegment(
                state = segment.state,
                tStartNanos = segment.tStartNanos,
                tEndNanos = segment.tEndNanos,
                rmsAccelCmS2 = segment.rmsAccelCmS2,
                peakAccelCmS2 = maxOf(segment.peakAccelCmS2, segment.rmsAccelCmS2),
            ),
        )
    }
}
