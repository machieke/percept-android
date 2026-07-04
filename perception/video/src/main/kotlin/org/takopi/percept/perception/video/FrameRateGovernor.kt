package org.takopi.percept.perception.video

enum class ThermalLevel {
    NOMINAL,
    SEVERE,
    CRITICAL,
}

/**
 * §3.7 thermal policy: full analysis fps while nominal, half fps at SEVERE,
 * video path suspended at CRITICAL (audio stays up — it is the cheap
 * modality). Also enforces the base analysis frame budget by dropping frames
 * that arrive faster than the target interval.
 */
class FrameRateGovernor(
    targetFps: Int = 10,
) {
    init {
        require(targetFps > 0) { "targetFps must be positive" }
    }

    private val nominalIntervalNanos = 1_000_000_000L / targetFps
    private var lastAcceptedTNanos = Long.MIN_VALUE
    private var lastThermalLevel = ThermalLevel.NOMINAL
    private var throttleTransitions = 0L

    /** Number of NOMINAL→(SEVERE|CRITICAL) transitions, for session-stop counters. */
    val thermalThrottleEvents: Long
        get() = throttleTransitions

    fun shouldProcess(tNanos: Long, thermal: ThermalLevel): Boolean {
        if (thermal != ThermalLevel.NOMINAL && lastThermalLevel == ThermalLevel.NOMINAL) {
            throttleTransitions += 1
        }
        lastThermalLevel = thermal

        val interval = when (thermal) {
            ThermalLevel.NOMINAL -> nominalIntervalNanos
            ThermalLevel.SEVERE -> nominalIntervalNanos * 2
            ThermalLevel.CRITICAL -> return false
        }
        if (lastAcceptedTNanos != Long.MIN_VALUE && tNanos - lastAcceptedTNanos < interval) {
            return false
        }
        lastAcceptedTNanos = tNanos
        return true
    }
}
