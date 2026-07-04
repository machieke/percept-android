package org.takopi.percept.dispatch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.takopi.percept.core.canonical.CID_PREFIX
import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cList
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.canonical.digestFromCid
import org.takopi.percept.core.canonical.sha256Hex
import org.takopi.percept.core.da.FileDA
import org.takopi.percept.core.index.DispatchState
import org.takopi.percept.core.index.EventPointerDatabase
import org.takopi.percept.core.index.RoomEventIndex
import org.takopi.percept.core.trace.EventIngestor
import org.takopi.percept.core.trace.SessionStopCounters
import org.takopi.percept.core.trace.sessionStopPayload
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundleExporterRobolectricTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: EventPointerDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EventPointerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exportsPendingSessionBundleAndMarksRowsBundled() {
        val sessionId = "m6-session"
        val daRoot = temporaryFolder.newFolder("da").toPath()
        val outputRoot = temporaryFolder.newFolder("exports").toPath()
        val da = FileDA(daRoot)
        val index = RoomEventIndex(database)
        val ingestor = EventIngestor(da, index)
        val keyframeCid = da.putBytes("synthetic-jpeg-keyframe".toByteArray(StandardCharsets.UTF_8), codec = "raw")

        val root = ingestor.ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-session-v0.1"),
                "device" to CString("moto-g84-5g"),
                "sessionId" to CString(sessionId),
                "monotonicAnchorNanos" to CLong(123456789000),
                "observedAt" to CString("2026-07-04T12:00:00Z"),
            ),
            observedAt = "2026-07-04T12:00:00Z",
            actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
            channelPath = listOf("perception", sessionId, "session"),
            valueKind = "session-start",
            preview = "session $sessionId start",
            provenance = androidProvenance(),
        )

        ingestor.ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-track-segment-v0.1"),
                "sessionId" to CString(sessionId),
                "trackId" to CLong(7),
                "label" to CString("person"),
                "labelSpace" to CString("coco-80"),
                "scorePerMille" to CLong(874),
                "tStartNanos" to CLong(1_500_000_000),
                "tEndNanos" to CLong(41_500_000_000),
                "frameCount" to CLong(412),
                "boxFirst" to cList(CLong(120), CLong(88), CLong(340), CLong(460)),
                "boxLast" to cList(CLong(180), CLong(92), CLong(400), CLong(470)),
                "observedAt" to CString("2026-07-04T12:00:41Z"),
            ),
            observedAt = "2026-07-04T12:00:41Z",
            actorPath = listOf("device", "moto-g84-5g", "camera", "0"),
            channelPath = listOf("perception", sessionId, "video"),
            valueKind = "track-segment",
            parentEventIds = listOf(root.eventId),
            rootEventId = root.eventId,
            provenance = androidProvenance("synthetic-detector-v0"),
        )

        ingestor.ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-scene-v0.1"),
                "sessionId" to CString(sessionId),
                "sceneIndex" to CLong(1),
                "tNanos" to CLong(2_000_000_000),
                "gateMetricPerMille" to CLong(612),
                "observedAt" to CString("2026-07-04T12:00:02Z"),
            ),
            observedAt = "2026-07-04T12:00:02Z",
            actorPath = listOf("device", "moto-g84-5g", "camera", "0"),
            channelPath = listOf("perception", sessionId, "video"),
            valueKind = "scene-change",
            parentEventIds = listOf(root.eventId),
            rootEventId = root.eventId,
            outputArtifactIds = listOf(keyframeCid),
            provenance = androidProvenance("scene-gate-v0"),
        )

        ingestor.ingestEvent(
            rawPayload = sessionStopPayload(
                sessionId = sessionId,
                tStartNanos = 0,
                tEndNanos = 300_000_000_000,
                observedAt = "2026-07-04T12:05:00Z",
                counters = SessionStopCounters(
                    framesProcessed = 2400,
                    eventsEmitted = 4,
                    droppedFrames = 7,
                    audioRingBufferOverruns = 0,
                    thermalThrottleEvents = 1,
                ),
            ),
            observedAt = "2026-07-04T12:05:00Z",
            actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
            channelPath = listOf("perception", sessionId, "session"),
            valueKind = "session-stop",
            preview = "session $sessionId stop",
            parentEventIds = listOf(root.eventId),
            rootEventId = root.eventId,
            provenance = androidProvenance(),
        )

        val rowsBeforeExport = index.allEvents()
        val result = BundleExporter(index).exportPending(
            sessionId = sessionId,
            sequence = 1,
            sourceDaRoot = daRoot,
            outputRoot = outputRoot,
        )

        assertEquals("bundle-m6-session-0001", result.bundleId)
        assertEquals(4, result.pointerCount)
        assertEquals(9, result.objectCount)
        assertEquals(9, result.manifestCount)
        assertTrue(result.bundleDir.exists())
        assertTrue(result.zipPath.exists())
        assertEquals(0, index.eventsByDispatchState(DispatchState.PENDING).size)
        assertEquals(4, index.eventsByDispatchState(DispatchState.BUNDLED).size)

        val pointerLines = result.bundleDir.resolve("pointers.jsonl")
            .readText(StandardCharsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() }
        assertEquals(rowsBeforeExport.map { it.pointerJson }, pointerLines)

        val expectedCids = rowsBeforeExport.flatMap { row ->
            listOf(row.eventCid, row.payloadCid) +
                row.outputArtifactIds.lines().filter { it.startsWith(CID_PREFIX) }
        }.distinct()
        expectedCids.forEach { cid ->
            val digest = digestFromCid(cid)
            val objectPath = result.bundleDir.resolve("objects").resolve(digest)
            val manifestPath = result.bundleDir.resolve("manifests").resolve("$digest.json")
            assertTrue(objectPath.exists())
            assertTrue(manifestPath.exists())
            assertEquals(digest, sha256Hex(objectPath.readBytes()))
        }

        ZipFile(result.zipPath.toFile()).use { zip ->
            val zipEntries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("pointers.jsonl" in zipEntries)
            expectedCids.forEach { cid ->
                val digest = digestFromCid(cid)
                assertTrue("objects/$digest" in zipEntries)
                assertTrue("manifests/$digest.json" in zipEntries)
            }
        }
    }

    private fun androidProvenance(extractionRunId: String? = null) = if (extractionRunId == null) {
        cMap(
            "source" to CString("android-percept"),
            "observedBy" to CString("percept-app"),
            "ingestionPipeline" to CString("event-trace-v0"),
        )
    } else {
        cMap(
            "source" to CString("android-percept"),
            "observedBy" to CString("percept-app"),
            "ingestionPipeline" to CString("event-trace-v0"),
            "extractionRunId" to CString(extractionRunId),
        )
    }
}
