package org.takopi.percept.perception.video

import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.TraceSink

/**
 * One analyzed frame: monotonic session-relative timestamp, model-space
 * detections, and a luminance histogram for the scene gate. The keyframe JPEG
 * is provided lazily so encoding only happens when the gate actually fires.
 */
class FrameObservation(
    val tNanos: Long,
    val detections: List<VideoDetection>,
    val histogram: LuminanceHistogram,
    val keyframeJpegProvider: () -> ByteArray,
) {
    init {
        require(tNanos >= 0) { "tNanos must be non-negative" }
    }
}

data class VideoRunCounters(
    val framesProcessed: Long,
    val droppedFrames: Long,
    val lastFrameTNanos: Long,
)

/**
 * §3.3 pipeline glue: detections → IoU tracker + scene gate → trace sink.
 * Callers feed frames from any single thread; the sink funnel serializes
 * ingestion downstream.
 */
class VideoPerceptionEngine(
    private val sink: TraceSink,
    private val tracker: IouTrackAggregator = IouTrackAggregator(),
    private val gate: SceneChangeGate = SceneChangeGate(),
) {
    private var framesProcessed = 0L
    private var droppedFrames = 0L
    private var lastFrameTNanos = 0L
    private var finished = false

    fun onFrame(frame: FrameObservation) {
        check(!finished) { "engine already finished" }
        val frameIndex = framesProcessed
        framesProcessed += 1
        lastFrameTNanos = maxOf(lastFrameTNanos, frame.tNanos)

        gate.process(SceneGateFrame(frame.tNanos, frame.histogram, frame.detections))?.let { scene ->
            sink.trySubmit(
                PerceptionEvent.SceneChange(
                    sceneIndex = scene.sceneIndex,
                    tNanos = scene.tNanos,
                    gateMetricPerMille = scene.metricPerMille,
                    keyframeJpeg = frame.keyframeJpegProvider(),
                ),
            )
        }

        tracker.process(VideoFrameDetections(frameIndex, frame.tNanos, frame.detections))
            .forEach(::submit)
    }

    fun onFrameDropped() {
        droppedFrames += 1
    }

    /** Flushes open tracks and returns the counters for session-stop. */
    fun finish(): VideoRunCounters {
        check(!finished) { "engine already finished" }
        finished = true
        tracker.flush().forEach(::submit)
        return VideoRunCounters(framesProcessed, droppedFrames, lastFrameTNanos)
    }

    private fun submit(segment: TrackSegment) {
        sink.trySubmit(
            PerceptionEvent.TrackSegment(
                trackId = segment.trackId.toLong(),
                label = segment.label,
                labelSpace = segment.labelSpace,
                scorePerMille = segment.scorePerMille,
                tStartNanos = segment.tStartNanos,
                tEndNanos = segment.tEndNanos,
                frameCount = segment.frameCount.toLong(),
                boxFirst = segment.boxFirst.toList(),
                boxLast = segment.boxLast.toList(),
            ),
        )
    }
}

private fun PixelBox.toList(): List<Int> = listOf(x1, y1, x2, y2)
