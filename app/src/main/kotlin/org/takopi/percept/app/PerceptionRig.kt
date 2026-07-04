package org.takopi.percept.app

import org.takopi.percept.core.trace.PerceptionRunCounters
import org.takopi.percept.core.trace.SessionTimeBase
import org.takopi.percept.core.trace.TraceSink

/**
 * The device-facing half of a session: capture hardware plus model adapters.
 * [SessionController] owns everything schema-facing; a rig only pushes
 * [org.takopi.percept.core.trace.PerceptionEvent]s into the sink. Tests use a
 * fake rig, the app uses [CameraMicrophoneRig].
 */
interface PerceptionRig {
    val detectorRunId: String
    val sceneGateRunId: String
    val audioTaggerRunId: String
    val asrRunId: String

    fun start(sink: TraceSink, timeBase: SessionTimeBase)

    fun stop(): PerceptionRunCounters
}
