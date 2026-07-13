package org.takopi.percept.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.takopi.percept.core.index.EventPointerDatabase
import org.takopi.percept.core.trace.PerceptionRunCounters
import org.takopi.percept.core.trace.SessionTimeBase
import org.takopi.percept.core.trace.TraceSink
import org.takopi.percept.perception.audio.AsrEngine
import org.takopi.percept.perception.audio.AsrWindowSegment
import org.takopi.percept.perception.audio.AudioPerceptionEngine
import org.takopi.percept.perception.audio.AudioTagScore
import org.takopi.percept.perception.audio.AudioTagger
import org.takopi.percept.perception.audio.EnergyVad
import org.takopi.percept.perception.audio.meanAbsoluteLevelPerMille
import org.takopi.percept.perception.video.FrameObservation
import org.takopi.percept.perception.video.SceneChangeGate
import org.takopi.percept.perception.video.LuminanceHistogram
import org.takopi.percept.perception.video.PixelBox
import org.takopi.percept.perception.video.VideoDetection
import org.takopi.percept.perception.video.VideoPerceptionEngine
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Full-stack host harness: real perception engines with deterministic fake
 * models, through the ingestion funnel into real FileDA + Room, exported as a
 * bundle. scripts/run_live_engine_harness.py verifies the result against the
 * Python reference implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveEngineBundleHarnessTest {
    private lateinit var database: EventPointerDatabase
    private lateinit var controller: SessionController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EventPointerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        controller = SessionController(
            appContext = context,
            databaseFactory = { database },
            monotonicNanos = { 0L },
            wallClockMillis = { Instant.parse("2026-07-04T12:00:00Z").toEpochMilli() },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun engineProducedBundleExportsForReferenceVerification() = runBlocking {
        val rig = SyntheticEngineRig()
        controller.startSessionAndWait(rig)
        controller.stopSessionAndWait()

        // session-start + 2 scenes + 2 tracks + 1 audio tag + 1 asr + session-stop
        assertEquals(8, controller.state.value.eventsIngested)
        val kinds = controller.state.value.recentEvents.groupingBy { it.valueKind }.eachCount()
        assertEquals(2, kinds["scene-change"])
        assertEquals(2, kinds["track-segment"])
        assertEquals(1, kinds["audio-tag-segment"])
        assertEquals(1, kinds["asr-segment"])

        val export = controller.exportLastSessionBundle()
        assertNotNull(export)
        assertEquals(8, export!!.pointerCount)
        assertTrue(Files.exists(export.zipPath))

        // Stage the artifacts where the python harness expects them.
        val outputDir = Paths.get("build", "live-engine-harness")
        Files.createDirectories(outputDir)
        Files.copy(
            export.zipPath,
            outputDir.resolve("bundle.zip"),
            StandardCopyOption.REPLACE_EXISTING,
        )
        Files.write(
            outputDir.resolve("expected-events.txt"),
            export.pointerCount.toString().toByteArray(StandardCharsets.UTF_8),
        )
        Files.write(
            outputDir.resolve("session-id.txt"),
            controller.state.value.lastSessionId!!.toByteArray(StandardCharsets.UTF_8),
        )
        Unit
    }
}

private class SyntheticEngineRig : PerceptionRig {
    override val detectorRunId = "synthetic-detector-v0@robolectric"
    override val sceneGateRunId = "synthetic-scene-gate-v0@robolectric"
    override val audioTaggerRunId = "synthetic-yamnet-v0@robolectric"
    override val asrRunId = "synthetic-asr-v0@robolectric"

    private var counters: PerceptionRunCounters? = null

    override fun start(sink: TraceSink, timeBase: SessionTimeBase) {
        val videoCounters = runVideo(sink)
        val audioCounters = runAudio(sink)
        counters = PerceptionRunCounters(
            tEndNanos = 30_000_000_000L,
            framesProcessed = videoCounters.framesProcessed,
            droppedFrames = videoCounters.droppedFrames,
            audioRingBufferOverruns = audioCounters.ringBufferOverruns,
            thermalThrottleEvents = 0,
        )
    }

    override fun stop(): PerceptionRunCounters = checkNotNull(counters) { "rig not started" }

    /** 300 frames at 10 fps: person throughout, cup joins at 15 s (scene change).
     *  Subject-triggered capture is disabled here (it has its own gate unit
     *  tests) so this stays a minimal, deterministic reference-bundle fixture —
     *  otherwise the always-present person would add a periodic keyframe every
     *  few seconds. */
    private fun runVideo(sink: TraceSink) =
        VideoPerceptionEngine(sink, gate = SceneChangeGate(subjectLabels = emptySet())).let { engine ->
        val histogram = LuminanceHistogram(IntArray(8) { 100 })
        for (frameIndex in 0 until 300) {
            val tNanos = frameIndex * 100_000_000L
            val person = VideoDetection(
                label = "person",
                labelSpace = "coco-80",
                scorePerMille = 800,
                box = PixelBox(100 + frameIndex / 4, 80, 240 + frameIndex / 4, 420),
            )
            val detections = if (frameIndex < 150) {
                listOf(person)
            } else {
                listOf(
                    person,
                    VideoDetection(
                        label = "cup",
                        labelSpace = "coco-80",
                        scorePerMille = 620,
                        box = PixelBox(400, 300, 460, 360),
                    ),
                )
            }
            engine.onFrame(
                FrameObservation(
                    tNanos = tNanos,
                    detections = detections,
                    histogram = histogram,
                    keyframeJpegProvider = { "synthetic-keyframe-$frameIndex".toByteArray() },
                ),
            )
        }
        engine.finish()
    }

    /** 20 s of PCM: 5 s speech, 10 s music, 5 s silence. */
    private fun runAudio(sink: TraceSink): org.takopi.percept.perception.audio.AudioRunCounters {
        val engine = AudioPerceptionEngine(
            sink = sink,
            asr = ScriptedAsr(),
            tagger = LevelBandTagger(),
            vad = EnergyVad(500),
        )
        // Interleave appends with draining as the real capture pipeline does;
        // batch-appending everything would engage the ASR lag-skip.
        engine.append(ShortArray(5 * 16_000) { 25_000 })
        engine.processAvailable()
        engine.append(ShortArray(10 * 16_000) { 2_000 })
        engine.processAvailable()
        engine.append(ShortArray(5 * 16_000) { 0 })
        engine.processAvailable()
        return engine.finish()
    }

    private class ScriptedAsr : AsrEngine {
        private var calls = 0

        override fun transcribe(samples: ShortArray, sampleRate: Int): List<AsrWindowSegment> =
            if (calls++ == 0) {
                listOf(
                    AsrWindowSegment(
                        text = "synthetic harness transcript",
                        startOffsetNanos = 500_000_000L,
                        endOffsetNanos = 4_500_000_000L,
                        avgLogProbMicro = -140_000,
                    ),
                )
            } else {
                emptyList()
            }
    }

    private class LevelBandTagger : AudioTagger {
        override fun classifyTop(samples: ShortArray, sampleRate: Int): AudioTagScore? {
            val level = meanAbsoluteLevelPerMille(samples)
            return when {
                level >= 500 -> AudioTagScore(label = "Speech", scorePerMille = 800)
                level >= 50 -> AudioTagScore(label = "Music", scorePerMille = 700)
                else -> null
            }
        }
    }
}
