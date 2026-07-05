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

    // Runs on the plain TFLite interpreter: MediaPipe's audio task cannot
    // share a process with tasks-vision (single framework registry per JNI lib).
    val YAMNET = ModelEntry(
        runId = "yamnet@tflite-2.14.0-cpu2",
        assetPath = "models/yamnet.tflite",
        sha256 = "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf",
        sourceUrl = "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite",
    )

    // Primary ASR backend; sha256 pins the release tarball containing the
    // int8 encoder/joiner, fp32 decoder, and tokens extracted into assets.
    val ZIPFORMER_EN_20M = ModelEntry(
        runId = "zipformer-en-20m-int8@sherpa-onnx-1.13.3-cpu2",
        assetPath = "models/zipformer20m",
        sha256 = "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9",
        sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
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
        ZIPFORMER_EN_20M,
        WHISPER_TINY_Q8,
    ).associateBy(ModelEntry::runId)
}
