package org.takopi.percept.core.trace

/**
 * §5 model provenance registry: every bundled model's sha256 is pinned here
 * and in the app's downloadModels task, so an extractionRunId always maps to
 * exact model bytes even across model swaps.
 */
object ExtractionRuns {
    data class ModelEntry(
        val runId: String,
        val assetPath: String,
        val sha256: String,
        val sourceUrl: String,
    )

    val EFFICIENTDET_LITE0_INT8 = ModelEntry(
        runId = "efficientdet-lite0-int8-320@mediapipe-0.10.14-gpu",
        assetPath = "models/efficientdet_lite0_int8.tflite",
        sha256 = "0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb",
        sourceUrl = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite",
    )

    val YAMNET = ModelEntry(
        runId = "yamnet@mediapipe-0.10.14-cpu",
        assetPath = "models/yamnet.tflite",
        sha256 = "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf",
        sourceUrl = "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite",
    )

    val WHISPER_TINY_Q8 = ModelEntry(
        runId = "whisper-tiny-q8_0@whispercpp-cpu4",
        assetPath = "models/ggml-tiny-q8_0.bin",
        sha256 = "c2085835d3f50733e2ff6e4b41ae8a2b8d8110461e18821b09a15c40c42d1cca",
        sourceUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin",
    )

    val REGISTRY: Map<String, ModelEntry> = listOf(
        EFFICIENTDET_LITE0_INT8,
        YAMNET,
        WHISPER_TINY_Q8,
    ).associateBy(ModelEntry::runId)
}
