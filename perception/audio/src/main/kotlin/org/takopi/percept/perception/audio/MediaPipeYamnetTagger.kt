package org.takopi.percept.perception.audio

import android.content.Context
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import kotlin.math.roundToInt

/**
 * §3.4 audio tagger: YAMNet via MediaPipe Tasks AudioClassifier over the
 * engine's 0.975 s frames.
 */
class MediaPipeYamnetTagger private constructor(
    private val classifier: AudioClassifier,
    val extractionRunId: String,
) : AudioTagger, AutoCloseable {

    override fun classifyTop(samples: ShortArray, sampleRate: Int): AudioTagScore? {
        val floats = FloatArray(samples.size) { index -> samples[index] / 32768f }
        val audioData = AudioData.create(
            AudioData.AudioDataFormat.builder()
                .setNumOfChannels(1)
                .setSampleRate(sampleRate.toFloat())
                .build(),
            samples.size,
        )
        audioData.load(floats)
        val result = classifier.classify(audioData)
        val top = result.classificationResults()
            .flatMap { classificationResult -> classificationResult.classifications() }
            .flatMap { classifications -> classifications.categories() }
            .maxByOrNull { category -> category.score() }
            ?: return null
        return AudioTagScore(
            label = top.categoryName(),
            labelSpace = LABEL_SPACE,
            scorePerMille = (top.score() * 1000f).roundToInt().coerceIn(0, 1000),
        )
    }

    override fun close() {
        classifier.close()
    }

    companion object {
        const val LABEL_SPACE: String = "audioset"
        const val MODEL_ASSET_PATH: String = "models/yamnet.tflite"

        fun create(context: Context, scoreThreshold: Float = 0.1f): MediaPipeYamnetTagger {
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET_PATH).build())
                .setRunningMode(RunningMode.AUDIO_CLIPS)
                .setScoreThreshold(scoreThreshold)
                .setMaxResults(1)
                .build()
            return MediaPipeYamnetTagger(
                classifier = AudioClassifier.createFromOptions(context, options),
                extractionRunId = "yamnet@mediapipe-0.10.14-cpu",
            )
        }
    }
}
