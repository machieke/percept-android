package org.takopi.percept.core.trace

import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cMap

data class SessionStopCounters(
    val framesProcessed: Long,
    val eventsEmitted: Long,
    val droppedFrames: Long,
    val audioRingBufferOverruns: Long,
    val thermalThrottleEvents: Long,
) {
    init {
        require(framesProcessed >= 0) { "framesProcessed must be non-negative" }
        require(eventsEmitted >= 0) { "eventsEmitted must be non-negative" }
        require(droppedFrames >= 0) { "droppedFrames must be non-negative" }
        require(audioRingBufferOverruns >= 0) { "audioRingBufferOverruns must be non-negative" }
        require(thermalThrottleEvents >= 0) { "thermalThrottleEvents must be non-negative" }
    }
}

fun sessionStopPayload(
    sessionId: String,
    tStartNanos: Long,
    tEndNanos: Long,
    observedAt: String,
    counters: SessionStopCounters,
) = run {
    require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    require(tStartNanos >= 0) { "tStartNanos must be non-negative" }
    require(tEndNanos >= tStartNanos) { "tEndNanos must be >= tStartNanos" }
    cMap(
        "kind" to CString("raw-payload"),
        "schema" to CString("perception-session-stop-v0.1"),
        "sessionId" to CString(sessionId),
        "tStartNanos" to CLong(tStartNanos),
        "tEndNanos" to CLong(tEndNanos),
        "observedAt" to CString(observedAt),
        "counters" to cMap(
            "framesProcessed" to CLong(counters.framesProcessed),
            "eventsEmitted" to CLong(counters.eventsEmitted),
            "droppedFrames" to CLong(counters.droppedFrames),
            "audioRingBufferOverruns" to CLong(counters.audioRingBufferOverruns),
            "thermalThrottleEvents" to CLong(counters.thermalThrottleEvents),
        ),
    )
}
