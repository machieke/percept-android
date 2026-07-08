package org.takopi.percept.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.takopi.percept.core.index.DispatchState
import org.takopi.percept.core.index.EventPointerDatabase
import org.takopi.percept.core.index.RoomEventIndex
import org.takopi.percept.core.trace.PerceptionEvent
import org.takopi.percept.core.trace.PerceptionRunCounters
import org.takopi.percept.core.trace.SessionTimeBase
import org.takopi.percept.core.trace.TraceSink
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.io.path.exists

private class FakeRig : PerceptionRig {
    override val detectorRunId = "fake-detector-v0@robolectric"
    override val sceneGateRunId = "fake-scene-gate-v0@robolectric"
    override val audioTaggerRunId = "fake-yamnet-v0@robolectric"
    override val asrRunId = "fake-asr-v0@robolectric"

    var sink: TraceSink? = null
    var timeBase: SessionTimeBase? = null
    var stopped = false

    override fun start(sink: TraceSink, timeBase: SessionTimeBase) {
        this.sink = sink
        this.timeBase = timeBase
    }

    override fun stop(): PerceptionRunCounters {
        stopped = true
        return PerceptionRunCounters(
            tEndNanos = 10_000_000_000L,
            framesProcessed = 120,
            droppedFrames = 3,
            audioRingBufferOverruns = 0,
            thermalThrottleEvents = 0,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionControllerTest {
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
            monotonicNanos = { 1_000_000L },
            wallClockMillis = { Instant.parse("2026-07-04T12:00:00Z").toEpochMilli() },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun fullSessionRoundTripThroughRealStores() = runBlocking {
        val rig = FakeRig()
        controller.startSessionAndWait(rig)
        assertTrue(controller.state.value.running)
        val sessionId = controller.state.value.sessionId
        assertEquals("sess-20260704-120000", sessionId)

        val sink = assertNotNull(rig.sink).let { rig.sink!! }
        assertTrue(
            sink.trySubmit(
                PerceptionEvent.SceneChange(
                    sceneIndex = 0,
                    tNanos = 1_000_000_000L,
                    gateMetricPerMille = 1000,
                    keyframeJpeg = byteArrayOf(9, 9, 9),
                ),
            ),
        )
        assertTrue(
            sink.trySubmit(
                PerceptionEvent.TrackSegment(
                    trackId = 1,
                    label = "person",
                    labelSpace = "coco-80",
                    scorePerMille = 800,
                    tStartNanos = 1_000_000_000L,
                    tEndNanos = 4_000_000_000L,
                    frameCount = 30,
                    boxFirst = listOf(0, 0, 100, 200),
                    boxLast = listOf(10, 5, 110, 205),
                ),
            ),
        )

        controller.stopSessionAndWait()
        assertTrue(rig.stopped)
        val state = controller.state.value
        assertFalse(state.running)
        assertEquals(sessionId, state.lastSessionId)
        // session-start + scene + track + session-stop
        assertEquals(4, state.eventsIngested)
        assertEquals(4, state.recentEvents.size)
        assertEquals("session-stop", state.recentEvents.first().valueKind)

        // Pointer rows landed in Room with dispatchState PENDING.
        val index = RoomEventIndex(database)
        val pending = index.eventsByDispatchState(DispatchState.PENDING)
        assertEquals(4, pending.size)
        val channelRows = index.eventsByChannelPrefix("/perception/$sessionId/video")
        assertEquals(2, channelRows.size)

        // v1a export produces a verifiable zip and flips rows to BUNDLED.
        val export = controller.exportLastSessionBundle()
        assertNotNull(export)
        assertTrue(export!!.zipPath.exists())
        assertEquals(4, export.pointerCount)
        ZipFile(export.zipPath.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(names.any { it.endsWith("pointers.jsonl") })
            assertTrue(names.any { it.contains("objects/") })
        }
        assertEquals(0, index.eventsByDispatchState(DispatchState.PENDING).size)
        assertEquals(4, index.eventsByDispatchState(DispatchState.BUNDLED).size)
        assertEquals(export.zipPath.toString(), controller.state.value.lastExportPath)
    }

    @Test
    fun stopWithoutStartIsANoOp() = runBlocking {
        controller.stopSessionAndWait()
        assertFalse(controller.state.value.running)
        assertEquals(0, controller.state.value.eventsIngested)
    }

    @Test
    fun ackedUploadEvictsArtifactsFromLeanBuffer() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        PerceptSettings(context).endpointUrl = "http://memory.invalid:8124"
        val uploader = object : org.takopi.percept.dispatch.BundleUploader {
            var uploads = 0

            override fun upload(
                bundleZip: java.nio.file.Path,
                destination: org.takopi.percept.dispatch.BundleUploadDestination,
            ): org.takopi.percept.dispatch.BundleUploadResult {
                uploads += 1
                return org.takopi.percept.dispatch.BundleUploadResult(true, 200, "ok")
            }
        }
        val controller = SessionController(
            appContext = context,
            databaseFactory = { database },
            monotonicNanos = { 1_000_000L },
            wallClockMillis = { Instant.parse("2026-07-06T09:00:00Z").toEpochMilli() },
            uploader = uploader,
            autoDispatchIntervalMillis = Long.MAX_VALUE / 2,
            retentionCapBytes = 0,
        )

        val rig = FakeRig()
        controller.startSessionAndWait(rig)
        val chunkBytes = ByteArray(50_000) { 7 }
        rig.sink!!.trySubmit(
            PerceptionEvent.AudioChunk(
                chunkIndex = 0,
                tStartNanos = 0,
                tEndNanos = 5_000_000_000L,
                sampleRate = 16_000,
                sampleCount = 80_000,
                contentType = "audio/ogg; codecs=opus",
                codecId = "test",
                encoded = chunkBytes,
            ),
        )
        controller.stopSessionAndWait()

        val chunkCid = org.takopi.percept.core.canonical.cidForBytes(chunkBytes)
        val chunkObject = controller.daRoot
            .resolve("objects")
            .resolve(chunkCid.substringAfterLast(':'))
        assertTrue(java.nio.file.Files.exists(chunkObject))

        val result = controller.exportAndUpload()

        assertEquals(1, uploader.uploads)
        assertEquals(3, result!!.ackedEvents)
        // Lean buffer: the acked audio artifact is physically gone.
        assertFalse(java.nio.file.Files.exists(chunkObject))
        val index = RoomEventIndex(database)
        assertEquals(0, index.eventsByDispatchState(DispatchState.PENDING).size)
        assertEquals(3, index.eventsByDispatchState(DispatchState.ACKED).size)
    }

    @Test
    fun liveStreamDeliversAndAcksEventsWithoutBundles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = java.net.ServerSocket(0)
        val acceptor = kotlin.concurrent.thread {
            try {
                while (true) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        var contentLength = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.lowercase().startsWith("content-length:")) {
                                contentLength = line.substringAfter(':').trim().toInt()
                            }
                        }
                        val body = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = input.read(body, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        received.add(String(body))
                        val response = """{"ok":true}"""
                        socket.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\n" +
                                "Connection: close\r\n\r\n$response").toByteArray(),
                        )
                    }
                }
            } catch (_: Exception) {
                // closed on shutdown
            }
        }
        PerceptSettings(context).endpointUrl = "http://127.0.0.1:${server.localPort}"
        val controller = SessionController(
            appContext = context,
            databaseFactory = { database },
            monotonicNanos = { 1_000_000L },
            wallClockMillis = { Instant.parse("2026-07-06T10:00:00Z").toEpochMilli() },
            autoDispatchIntervalMillis = Long.MAX_VALUE / 2,
        )

        val rig = FakeRig()
        controller.startSessionAndWait(rig)
        rig.sink!!.trySubmit(
            PerceptionEvent.SceneChange(
                sceneIndex = 0,
                tNanos = 1_000_000_000L,
                gateMetricPerMille = 1000,
                keyframeJpeg = byteArrayOf(1, 2, 3),
            ),
        )
        controller.stopSessionAndWait()
        // The streamer drains in the background (stop no longer blocks on the
        // network), so wait for delivery before asserting.
        val deadline = System.currentTimeMillis() + 5_000
        while (received.size < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        server.close()
        acceptor.join(2_000)

        // session-start + scene-change + session-stop each streamed live.
        assertEquals(3, received.size)
        assertTrue(received.any { it.contains("scene-change") })
        // Stream success ACKs directly: nothing left for the bundle path.
        val index = RoomEventIndex(database)
        assertEquals(0, index.eventsByDispatchState(DispatchState.PENDING).size)
        assertEquals(3, index.eventsByDispatchState(DispatchState.ACKED).size)
    }

    @Test
    fun rigStopFailureStillIngestsSessionStop() = runBlocking {
        val rig = object : PerceptionRig {
            override val detectorRunId = "fake-detector-v0@robolectric"
            override val sceneGateRunId = "fake-scene-gate-v0@robolectric"
            override val audioTaggerRunId = "fake-yamnet-v0@robolectric"
            override val asrRunId = "fake-asr-v0@robolectric"

            override fun start(sink: TraceSink, timeBase: SessionTimeBase) {}

            override fun stop(): PerceptionRunCounters =
                throw IllegalStateException("poisoned inference graph")
        }
        controller.startSessionAndWait(rig)
        controller.stopSessionAndWait()

        val state = controller.state.value
        assertFalse(state.running)
        assertTrue(state.lastError!!.contains("poisoned inference graph"))
        // session-start + session-stop both landed despite the rig failure.
        assertEquals(2, state.eventsIngested)
        assertEquals("session-stop", state.recentEvents.first().valueKind)
    }
}
