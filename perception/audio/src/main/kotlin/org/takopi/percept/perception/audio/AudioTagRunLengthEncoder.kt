package org.takopi.percept.perception.audio

data class AudioTagFrame(
    val label: String,
    val labelSpace: String = "audioset",
    val scorePerMille: Int,
    val tStartNanos: Long,
    val tEndNanos: Long,
    val asrActive: Boolean = false,
) {
    init {
        require(scorePerMille in 0..1000) { "scorePerMille must be in 0..1000" }
        require(tEndNanos >= tStartNanos) { "tEndNanos must be >= tStartNanos" }
    }
}

data class AudioTagSegment(
    val label: String,
    val labelSpace: String,
    val scorePerMille: Int,
    val tStartNanos: Long,
    val tEndNanos: Long,
    val frameCount: Int,
)

class AudioTagRunLengthEncoder(
    private val thresholdPerMille: Int = 300,
    private val suppressSpeechWhenAsrActive: Boolean = true,
) {
    init {
        require(thresholdPerMille in 0..1000) { "thresholdPerMille must be in 0..1000" }
    }

    private var current: MutableAudioTagRun? = null

    fun process(frame: AudioTagFrame): AudioTagSegment? {
        val active = frame.scorePerMille >= thresholdPerMille &&
            !(suppressSpeechWhenAsrActive && frame.asrActive && frame.isSpeechLabel())
        if (!active) {
            return closeOpen()
        }

        val run = current
        if (run == null) {
            current = MutableAudioTagRun.start(frame)
            return null
        }

        if (run.label == frame.label && run.labelSpace == frame.labelSpace) {
            run.add(frame)
            return null
        }

        val closed = run.toSegment()
        current = MutableAudioTagRun.start(frame)
        return closed
    }

    fun closeOpen(): AudioTagSegment? {
        val closed = current?.toSegment()
        current = null
        return closed
    }

    private data class MutableAudioTagRun(
        val label: String,
        val labelSpace: String,
        var scoreSum: Long,
        var tStartNanos: Long,
        var tEndNanos: Long,
        var frameCount: Int,
    ) {
        fun add(frame: AudioTagFrame) {
            scoreSum += frame.scorePerMille
            tEndNanos = frame.tEndNanos
            frameCount += 1
        }

        fun toSegment(): AudioTagSegment = AudioTagSegment(
            label = label,
            labelSpace = labelSpace,
            scorePerMille = (scoreSum / frameCount).toInt(),
            tStartNanos = tStartNanos,
            tEndNanos = tEndNanos,
            frameCount = frameCount,
        )

        companion object {
            fun start(frame: AudioTagFrame): MutableAudioTagRun =
                MutableAudioTagRun(
                    label = frame.label,
                    labelSpace = frame.labelSpace,
                    scoreSum = frame.scorePerMille.toLong(),
                    tStartNanos = frame.tStartNanos,
                    tEndNanos = frame.tEndNanos,
                    frameCount = 1,
                )
        }
    }
}

private fun AudioTagFrame.isSpeechLabel(): Boolean =
    label.trim().equals("Speech", ignoreCase = true)
