package org.takopi.percept.app

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.takopi.percept.core.trace.PerceptionRunCounters
import org.takopi.percept.core.trace.SessionTimeBase
import org.takopi.percept.core.trace.TraceSink
import org.takopi.percept.perception.audio.AudioCapturePipeline
import org.takopi.percept.perception.audio.AudioChunkEncoders
import org.takopi.percept.perception.audio.AudioChunkRecorder
import org.takopi.percept.perception.audio.AudioPerceptionEngine
import org.takopi.percept.perception.audio.CancellableAsrEngine
import org.takopi.percept.perception.audio.FallbackAsrEngine
import org.takopi.percept.perception.audio.RemoteAsrEngine
import org.takopi.percept.perception.audio.SherpaZipformerAsrEngine
import org.takopi.percept.perception.audio.TfLiteYamnetTagger
import org.takopi.percept.perception.audio.NativeWhisper
import org.takopi.percept.perception.video.FrameRateGovernor
import org.takopi.percept.perception.video.MediaPipeFrameDetector
import org.takopi.percept.perception.video.PerceptFrameAnalyzer
import org.takopi.percept.perception.video.ThermalLevel
import org.takopi.percept.perception.video.VideoPerceptionEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The real capture rig: CameraX ImageAnalysis (KEEP_ONLY_LATEST, 640×480) and
 * one AudioRecord, model adapters from §3.3/§3.4, thermal policy from §3.7.
 */
class CameraMicrophoneRig(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onError: ((String) -> Unit)? = null,
) : PerceptionRig {
    private val detector = MediaPipeFrameDetector.createWithFallback(context)
    private val tagger = TfLiteYamnetTagger.create(context)
    private val asrPair = createAsrEngine()

    override val detectorRunId: String = detector.extractionRunId
    override val sceneGateRunId: String = SCENE_GATE_RUN_ID
    override val audioTaggerRunId: String = tagger.extractionRunId
    override val asrRunId: String = asrPair.second

    private val governor = FrameRateGovernor(targetFps = 10)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var videoEngine: VideoPerceptionEngine? = null
    private var audioPipeline: AudioCapturePipeline? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var analyzer: PerceptFrameAnalyzer? = null
    private var locationTracker: LocationTracker? = null
    private var motionTracker: MotionTracker? = null
    private var orientationTracker: OrientationTracker? = null
    private var timeBase: SessionTimeBase? = null

    override fun start(sink: TraceSink, timeBase: SessionTimeBase) {
        this.timeBase = timeBase
        val engine = VideoPerceptionEngine(sink)
        videoEngine = engine
        val analyzer = PerceptFrameAnalyzer(
            engine = engine,
            detector = detector,
            timeBase = timeBase,
            governor = governor,
            thermalLevelProvider = ::currentThermalLevel,
            onAnalysisError = { e ->
                onError?.invoke("video analysis failing: ${e.message}")
            },
        )
        this.analyzer = analyzer
        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor = executor
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(640, 480))
            .build()
            .also { it.setAnalyzer(executor, analyzer) }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                analysis,
            )
        }, ContextCompat.getMainExecutor(context))

        val audioStartTNanos = timeBase.elapsedNanos(SystemClock.elapsedRealtimeNanos())
        val audioEngine = AudioPerceptionEngine(
            sink = sink,
            asr = asrPair.first,
            tagger = tagger,
            startTNanos = audioStartTNanos,
        )
        // Full-session compressed audio → DA artifacts: bundles carry the
        // complete episodic record for server-side processing.
        val chunkRecorder = AudioChunkRecorder(
            sink = sink,
            encoder = AudioChunkEncoders.createBest(context),
            startTNanos = audioStartTNanos,
        )
        audioPipeline = AudioCapturePipeline(
            engine = audioEngine,
            chunkRecorder = chunkRecorder,
        ).also(AudioCapturePipeline::start)

        locationTracker = LocationTracker(context, sink, timeBase).also { tracker ->
            if (!tracker.start()) {
                onError?.invoke("location unavailable (permission or providers); no location-fix events")
            }
        }
        motionTracker = MotionTracker(context, sink, timeBase).also { it.start() }
        orientationTracker = OrientationTracker(context, sink, timeBase).also { it.start() }
    }

    override fun stop(): PerceptionRunCounters {
        locationTracker?.stop()
        motionTracker?.stop()
        orientationTracker?.stop()
        // Called from a background coroutine; CameraX requires unbinding on
        // the main thread.
        cameraProvider?.let { provider ->
            val unbound = CountDownLatch(1)
            ContextCompat.getMainExecutor(context).execute {
                provider.unbindAll()
                unbound.countDown()
            }
            unbound.await(5, TimeUnit.SECONDS)
        }
        // Drain in-flight analysis before finishing the engine, or the last
        // frame races finish() and gets miscounted.
        analysisExecutor?.let { executor ->
            executor.shutdown()
            executor.awaitTermination(3, TimeUnit.SECONDS)
        }
        val video = checkNotNull(videoEngine) { "rig not started" }.finish()
        // A whisper window runs ~30 s on this device; abort the in-flight
        // transcription so the audio processing thread can be joined promptly.
        (asrPair.first as? CancellableAsrEngine)?.cancelInFlight()
        val audio = checkNotNull(audioPipeline) { "rig not started" }.stop()
        // A poisoned inference graph can throw from close(); the session's
        // data is already safe, so never let teardown fail the stop.
        runCatching(detector::close)
        runCatching(tagger::close)
        val base = checkNotNull(timeBase)
        return PerceptionRunCounters(
            tEndNanos = base.elapsedNanos(SystemClock.elapsedRealtimeNanos()),
            framesProcessed = video.framesProcessed,
            droppedFrames = video.droppedFrames,
            audioRingBufferOverruns = audio.ringBufferOverruns,
            thermalThrottleEvents = governor.thermalThrottleEvents,
            // Device diagnostics for the M4/M5 tuning loop: was ASR gated out
            // by VAD, too slow, or fine? Did the HAL clock base fall back?
            extraCounters = mapOf(
                "asrWindowsProcessed" to audio.asrWindowsProcessed,
                "asrWindowsTranscribed" to audio.asrWindowsTranscribed,
                "asrWindowsSkipped" to audio.asrWindowsSkipped,
                "asrTranscribeMillis" to audio.asrTranscribeMillis,
                "clockBaseFallbackFrames" to (analyzer?.clockBaseFallbacks ?: 0),
                "analysisFailures" to (analyzer?.analysisFailureCount ?: 0),
            ),
        )
    }

    private fun currentThermalLevel(): ThermalLevel =
        when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
            -> ThermalLevel.CRITICAL

            PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            else -> ThermalLevel.NOMINAL
        }

    /**
     * Remote Parakeet (server/asr) when an endpoint is configured, wrapped so
     * connectivity loss falls back per-window to the on-device engine;
     * otherwise on-device Zipformer with whisper as last resort.
     */
    private fun createAsrEngine(): Pair<org.takopi.percept.perception.audio.AsrEngine, String> {
        val local = createLocalAsrEngine()
        val remoteUrl = PerceptSettings(context).asrEndpointUrl
        if (remoteUrl.isBlank()) return local
        val remote = RemoteAsrEngine(remoteUrl)
        val engine = FallbackAsrEngine(
            primary = remote,
            fallback = local.first,
            onPrimaryFailure = { t ->
                onError?.invoke("remote ASR failed (${t.message}); window used ${local.second}")
            },
        )
        return engine to "$REMOTE_ASR_RUN_ID+fallback-${local.second}"
    }

    private fun createLocalAsrEngine(): Pair<org.takopi.percept.perception.audio.AsrEngine, String> =
        try {
            val sherpa = SherpaZipformerAsrEngine.create(context)
            sherpa to sherpa.extractionRunId
        } catch (t: Throwable) {
            onError?.invoke("zipformer ASR unavailable (${t.message}); falling back to whisper")
            NativeWhisper.createEngineOrNoop(whisperModelPathOrNull())
        }

    /** whisper.cpp reads the model from a real file, so stage it out of assets once. */
    private fun whisperModelPathOrNull(): String? {
        if (!NativeWhisper.isAvailable()) return null
        val target = File(context.filesDir, NativeWhisper.MODEL_ASSET_PATH)
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            context.assets.open(NativeWhisper.MODEL_ASSET_PATH).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return target.absolutePath
    }

    companion object {
        const val SCENE_GATE_RUN_ID: String = "scene-gate-detset-luma-v1"
        const val REMOTE_ASR_RUN_ID: String = "parakeet-tdt-0.6b-v3-int8@sherpa-onnx-1.13.3"
    }
}
