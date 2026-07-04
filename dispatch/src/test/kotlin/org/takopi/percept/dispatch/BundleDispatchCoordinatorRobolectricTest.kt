package org.takopi.percept.dispatch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.da.FileDA
import org.takopi.percept.core.index.DispatchState
import org.takopi.percept.core.index.EventPointerDatabase
import org.takopi.percept.core.index.RoomEventIndex
import org.takopi.percept.core.trace.EventIngestor
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.exists

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundleDispatchCoordinatorRobolectricTest {
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
    fun successfulUploadMarksBundledEventsAcked() {
        val daRoot = temporaryFolder.newFolder("da").toPath()
        val outputRoot = temporaryFolder.newFolder("exports").toPath()
        val index = RoomEventIndex(database)
        seedTwoEventSession(daRoot, index)
        val uploader = RecordingUploader(BundleUploadResult(ok = true, statusCode = 200, responseBody = "ok"))

        val result = BundleDispatchCoordinator(index = index, uploader = uploader).exportAndUploadPending(
            request = BundleDispatchRequest(
                sessionId = "dispatch-session",
                sequence = 1,
                sourceDaRoot = daRoot,
                outputRoot = outputRoot,
            ),
            destination = BundleUploadDestination(URL("https://example.invalid/bundles/test")),
        )

        assertEquals(2, result.export.pointerCount)
        assertEquals(2, result.ackedEvents)
        assertEquals(0, index.eventsByDispatchState(DispatchState.PENDING).size)
        assertEquals(0, index.eventsByDispatchState(DispatchState.BUNDLED).size)
        assertEquals(2, index.eventsByDispatchState(DispatchState.ACKED).size)
        assertNotNull(uploader.lastZip)
        assertTrue(uploader.lastZip!!.exists())
    }

    @Test
    fun failedUploadLeavesEventsBundledForRetry() {
        val daRoot = temporaryFolder.newFolder("da").toPath()
        val outputRoot = temporaryFolder.newFolder("exports").toPath()
        val index = RoomEventIndex(database)
        seedTwoEventSession(daRoot, index)
        val uploader = RecordingUploader(BundleUploadResult(ok = false, statusCode = 503, responseBody = "retry"))

        val result = BundleDispatchCoordinator(index = index, uploader = uploader).exportAndUploadPending(
            request = BundleDispatchRequest(
                sessionId = "dispatch-session",
                sequence = 1,
                sourceDaRoot = daRoot,
                outputRoot = outputRoot,
            ),
            destination = BundleUploadDestination(URL("https://example.invalid/bundles/test")),
        )

        assertEquals(2, result.export.pointerCount)
        assertEquals(0, result.ackedEvents)
        assertEquals(0, index.eventsByDispatchState(DispatchState.PENDING).size)
        assertEquals(2, index.eventsByDispatchState(DispatchState.BUNDLED).size)
        assertEquals(0, index.eventsByDispatchState(DispatchState.ACKED).size)
        assertNotNull(uploader.lastZip)

        val retryUploader = RecordingUploader(BundleUploadResult(ok = true, statusCode = 200, responseBody = "ok"))
        val retry = BundleDispatchCoordinator(index = index, uploader = retryUploader).exportAndUploadPending(
            request = BundleDispatchRequest(
                sessionId = "dispatch-session",
                sequence = 2,
                sourceDaRoot = daRoot,
                outputRoot = outputRoot,
            ),
            destination = BundleUploadDestination(URL("https://example.invalid/bundles/test")),
        )

        assertEquals(2, retry.export.pointerCount)
        assertEquals(2, retry.ackedEvents)
        assertEquals(0, index.eventsByDispatchState(DispatchState.PENDING).size)
        assertEquals(0, index.eventsByDispatchState(DispatchState.BUNDLED).size)
        assertEquals(2, index.eventsByDispatchState(DispatchState.ACKED).size)
        assertNotNull(retryUploader.lastZip)
    }

    @Test
    fun retentionCollectorRequiresEveryReferenceToBeAckedAndMarksKeyframes() {
        val daRoot = temporaryFolder.newFolder("da").toPath()
        val index = RoomEventIndex(database)
        val keyframeCid = seedSharedKeyframeSession(daRoot, index)
        val rows = index.allEvents()
        val root = rows.first { it.valueKind == "session-start" }
        val firstScene = rows.first { it.valueKind == "scene-change" }
        val secondScene = rows.last { it.valueKind == "scene-change" }
        index.updateDispatchState(listOf(root.eventId, firstScene.eventId), DispatchState.ACKED)

        val pendingKeyframe = DaRetentionCandidateCollector(index).collect(daRoot).single { it.cid == keyframeCid }
        assertTrue(pendingKeyframe.keyframe)
        assertFalse(pendingKeyframe.acked)

        index.updateDispatchState(listOf(secondScene.eventId), DispatchState.ACKED)
        val ackedKeyframe = DaRetentionCandidateCollector(index).collect(daRoot).single { it.cid == keyframeCid }
        assertTrue(ackedKeyframe.keyframe)
        assertTrue(ackedKeyframe.acked)
    }

    private fun seedTwoEventSession(daRoot: Path, index: RoomEventIndex) {
        val ingestor = EventIngestor(FileDA(daRoot), index)
        val root = ingestor.ingestEvent(
            rawPayload = sessionStartPayload("dispatch-session"),
            observedAt = "2026-07-04T12:00:00Z",
            actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
            channelPath = listOf("perception", "dispatch-session", "session"),
            valueKind = "session-start",
            provenance = androidProvenance(),
        )
        ingestor.ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-audio-tag-v0.1"),
                "sessionId" to CString("dispatch-session"),
                "label" to CString("Speech"),
                "labelSpace" to CString("audioset"),
                "scorePerMille" to CLong(821),
                "tStartNanos" to CLong(0),
                "tEndNanos" to CLong(1_000_000_000),
                "observedAt" to CString("2026-07-04T12:00:01Z"),
            ),
            observedAt = "2026-07-04T12:00:01Z",
            actorPath = listOf("device", "moto-g84-5g", "microphone", "0"),
            channelPath = listOf("perception", "dispatch-session", "audio"),
            valueKind = "audio-tag-segment",
            parentEventIds = listOf(root.eventId),
            rootEventId = root.eventId,
            provenance = androidProvenance("yamnet-synthetic-v0"),
        )
    }

    private fun seedSharedKeyframeSession(daRoot: Path, index: RoomEventIndex): String {
        val da = FileDA(daRoot)
        val ingestor = EventIngestor(da, index)
        val keyframeCid = da.putBytes("shared-keyframe".toByteArray(), codec = "raw")
        val root = ingestor.ingestEvent(
            rawPayload = sessionStartPayload("retention-session"),
            observedAt = "2026-07-04T12:00:00Z",
            actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
            channelPath = listOf("perception", "retention-session", "session"),
            valueKind = "session-start",
            provenance = androidProvenance(),
        )

        listOf(1L, 2L).forEach { sceneIndex ->
            ingestor.ingestEvent(
                rawPayload = cMap(
                    "kind" to CString("raw-payload"),
                    "schema" to CString("perception-scene-v0.1"),
                    "sessionId" to CString("retention-session"),
                    "sceneIndex" to CLong(sceneIndex),
                    "tNanos" to CLong(sceneIndex * 1_000_000_000),
                    "gateMetricPerMille" to CLong(650),
                    "observedAt" to CString("2026-07-04T12:00:0${sceneIndex}Z"),
                ),
                observedAt = "2026-07-04T12:00:0${sceneIndex}Z",
                actorPath = listOf("device", "moto-g84-5g", "camera", "0"),
                channelPath = listOf("perception", "retention-session", "video"),
                valueKind = "scene-change",
                parentEventIds = listOf(root.eventId),
                rootEventId = root.eventId,
                outputArtifactIds = listOf(keyframeCid),
                provenance = androidProvenance("scene-gate-synthetic-v0"),
            )
        }
        return keyframeCid
    }

    private fun sessionStartPayload(sessionId: String) = cMap(
        "kind" to CString("raw-payload"),
        "schema" to CString("perception-session-v0.1"),
        "device" to CString("moto-g84-5g"),
        "sessionId" to CString(sessionId),
        "monotonicAnchorNanos" to CLong(123456789000),
        "observedAt" to CString("2026-07-04T12:00:00Z"),
    )

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

    private class RecordingUploader(
        private val result: BundleUploadResult,
    ) : BundleUploader {
        var lastZip: Path? = null

        override fun upload(bundleZip: Path, destination: BundleUploadDestination): BundleUploadResult {
            lastZip = bundleZip
            return result
        }
    }
}
