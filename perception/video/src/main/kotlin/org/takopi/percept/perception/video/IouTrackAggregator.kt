package org.takopi.percept.perception.video

class IouTrackAggregator(
    private val missedFrameLimit: Int = 15,
    private val heartbeatNanos: Long = 30_000_000_000L,
    private val iouThresholdPerMille: Int = 300,
) {
    init {
        require(missedFrameLimit > 0) { "missedFrameLimit must be positive" }
        require(heartbeatNanos > 0) { "heartbeatNanos must be positive" }
        require(iouThresholdPerMille in 0..1000) { "iouThresholdPerMille must be in 0..1000" }
    }

    private val tracks = linkedMapOf<Int, MutableTrack>()
    private var nextTrackId = 1

    fun process(frame: VideoFrameDetections): List<TrackSegment> {
        val emitted = mutableListOf<TrackSegment>()
        val unmatchedTrackIds = tracks.keys.toMutableSet()
        val unmatchedDetectionIndexes = frame.detections.indices.toMutableSet()

        while (true) {
            val match = bestMatch(unmatchedTrackIds, unmatchedDetectionIndexes, frame.detections) ?: break
            val track = tracks.getValue(match.trackId)
            val detection = frame.detections[match.detectionIndex]
            track.update(detection, frame.tNanos)
            unmatchedTrackIds -= match.trackId
            unmatchedDetectionIndexes -= match.detectionIndex
            track.heartbeatIfDue(heartbeatNanos)?.let(emitted::add)
        }

        for (detectionIndex in unmatchedDetectionIndexes) {
            val detection = frame.detections[detectionIndex]
            val id = nextTrackId++
            tracks[id] = MutableTrack.start(id, detection, frame.tNanos)
        }

        val tracksToRemove = mutableListOf<Int>()
        for (trackId in unmatchedTrackIds) {
            val track = tracks.getValue(trackId)
            track.missedFrames += 1
            if (track.missedFrames >= missedFrameLimit) {
                emitted += track.toSegment(TrackCloseReason.MISSED_LIMIT)
                tracksToRemove += trackId
            }
        }
        tracksToRemove.forEach(tracks::remove)

        return emitted
    }

    fun flush(): List<TrackSegment> {
        val emitted = tracks.values.map { it.toSegment(TrackCloseReason.FLUSH) }
        tracks.clear()
        return emitted
    }

    fun activeTrackIds(): Set<Int> = tracks.keys.toSet()

    private fun bestMatch(
        trackIds: Set<Int>,
        detectionIndexes: Set<Int>,
        detections: List<VideoDetection>,
    ): Match? {
        var best: Match? = null
        for (trackId in trackIds) {
            val track = tracks.getValue(trackId)
            for (detectionIndex in detectionIndexes) {
                val detection = detections[detectionIndex]
                if (track.label != detection.label || track.labelSpace != detection.labelSpace) continue
                val iou = track.boxLast.iouPerMille(detection.box)
                if (iou < iouThresholdPerMille) continue
                if (best == null || iou > best.iouPerMille) {
                    best = Match(trackId, detectionIndex, iou)
                }
            }
        }
        return best
    }

    private data class Match(
        val trackId: Int,
        val detectionIndex: Int,
        val iouPerMille: Int,
    )

    private data class MutableTrack(
        val trackId: Int,
        val label: String,
        val labelSpace: String,
        var scorePerMille: Int,
        var tStartNanos: Long,
        var tEndNanos: Long,
        var frameCount: Int,
        var boxFirst: PixelBox,
        var boxLast: PixelBox,
        var lastScorePerMille: Int,
        var missedFrames: Int,
    ) {
        fun update(detection: VideoDetection, tNanos: Long) {
            scorePerMille = maxOf(scorePerMille, detection.scorePerMille)
            lastScorePerMille = detection.scorePerMille
            tEndNanos = tNanos
            frameCount += 1
            boxLast = detection.box
            missedFrames = 0
        }

        fun heartbeatIfDue(heartbeatNanos: Long): TrackSegment? {
            if (tEndNanos - tStartNanos < heartbeatNanos) return null
            val segment = toSegment(TrackCloseReason.HEARTBEAT)
            tStartNanos = tEndNanos
            frameCount = 1
            boxFirst = boxLast
            scorePerMille = lastScorePerMille
            return segment
        }

        fun toSegment(reason: TrackCloseReason): TrackSegment = TrackSegment(
            trackId = trackId,
            label = label,
            labelSpace = labelSpace,
            scorePerMille = scorePerMille,
            tStartNanos = tStartNanos,
            tEndNanos = tEndNanos,
            frameCount = frameCount,
            boxFirst = boxFirst,
            boxLast = boxLast,
            closeReason = reason,
        )

        companion object {
            fun start(trackId: Int, detection: VideoDetection, tNanos: Long): MutableTrack =
                MutableTrack(
                    trackId = trackId,
                    label = detection.label,
                    labelSpace = detection.labelSpace,
                    scorePerMille = detection.scorePerMille,
                    tStartNanos = tNanos,
                    tEndNanos = tNanos,
                    frameCount = 1,
                    boxFirst = detection.box,
                    boxLast = detection.box,
                    lastScorePerMille = detection.scorePerMille,
                    missedFrames = 0,
                )
        }
    }
}
