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
    /** Relative sharpness of the frame (see [Sharpness]); 0 when unknown. */
    val sharpness: Int = 0,
) {
    init {
        require(tNanos >= 0) { "tNanos must be non-negative" }
        require(sharpness >= 0) { "sharpness must be non-negative" }
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
 *
 * Scene changes fire while the camera is moving (that is what changes the
 * detection set), so the gate frame is systematically the blurriest choice
 * of keyframe — and blurry keyframes cap VLM caption quality downstream.
 * With [keyframeSelectionFrames] > 0 the scene event is held briefly and the
 * sharpest frame in the window supplies its keyframe.
 */
class VideoPerceptionEngine(
    private val sink: TraceSink,
    private val tracker: IouTrackAggregator = IouTrackAggregator(),
    private val gate: SceneChangeGate = SceneChangeGate(),
    private val keyframeSelectionFrames: Int = DEFAULT_KEYFRAME_SELECTION_FRAMES,
    /** Tracks shorter than this are detector flicker, not presences: a
     *  dashcam session produced 1369 track segments in 12 min, mostly
     *  sub-second churn. 0 keeps everything. */
    private val minTrackDurationNanos: Long = 0,
) {
    init {
        require(keyframeSelectionFrames >= 0) { "keyframeSelectionFrames must be >= 0" }
        require(minTrackDurationNanos >= 0) { "minTrackDurationNanos must be >= 0" }
    }

    private var framesProcessed = 0L
    private var droppedFrames = 0L
    private var lastFrameTNanos = 0L
    private var finished = false

    private var pendingScene: SceneChange? = null
    private var pendingFramesSeen = 0
    private var bestSharpness = -1
    private var bestKeyframeProvider: (() -> ByteArray)? = null

    fun onFrame(frame: FrameObservation) {
        // Camera teardown races a final in-flight frame past finish(); the
        // session is over, so it is a silent drop rather than an error.
        if (finished) return
        val frameIndex = framesProcessed
        framesProcessed += 1
        lastFrameTNanos = maxOf(lastFrameTNanos, frame.tNanos)

        if (pendingScene != null) {
            pendingFramesSeen += 1
            if (frame.sharpness > bestSharpness) {
                bestSharpness = frame.sharpness
                bestKeyframeProvider = frame.keyframeJpegProvider
            }
            if (pendingFramesSeen >= keyframeSelectionFrames) {
                submitPendingScene()
            }
        }

        gate.process(SceneGateFrame(frame.tNanos, frame.histogram, frame.detections))?.let { scene ->
            submitPendingScene()
            if (keyframeSelectionFrames == 0) {
                submitScene(scene, frame.keyframeJpegProvider())
            } else {
                pendingScene = scene
                pendingFramesSeen = 0
                bestSharpness = frame.sharpness
                bestKeyframeProvider = frame.keyframeJpegProvider
            }
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
        submitPendingScene()
        finished = true
        tracker.flush().forEach(::submit)
        return VideoRunCounters(framesProcessed, droppedFrames, lastFrameTNanos)
    }

    private fun submitPendingScene() {
        val scene = pendingScene ?: return
        val provider = bestKeyframeProvider
        pendingScene = null
        bestKeyframeProvider = null
        bestSharpness = -1
        pendingFramesSeen = 0
        submitScene(scene, provider?.invoke())
    }

    private fun submitScene(scene: SceneChange, keyframeJpeg: ByteArray?) {
        sink.trySubmit(
            PerceptionEvent.SceneChange(
                sceneIndex = scene.sceneIndex,
                tNanos = scene.tNanos,
                gateMetricPerMille = scene.metricPerMille,
                keyframeJpeg = keyframeJpeg,
            ),
        )
    }

    companion object {
        /** ~1 s of candidates at the 8-10 fps analysis rate. */
        const val DEFAULT_KEYFRAME_SELECTION_FRAMES: Int = 8
    }

    private fun submit(segment: TrackSegment) {
        if (segment.tEndNanos - segment.tStartNanos < minTrackDurationNanos) return
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
