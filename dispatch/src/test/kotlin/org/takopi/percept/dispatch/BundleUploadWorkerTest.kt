package org.takopi.percept.dispatch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import java.nio.file.Path
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundleUploadWorkerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: EventPointerDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, EventPointerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        BundleUploadWorker.databaseFactory = { database }
    }

    @After
    fun tearDown() {
        BundleUploadWorker.databaseFactory = null
        BundleUploadWorker.uploaderFactory = ::HttpBundleUploader
        database.close()
    }

    private fun runWorker(): ListenableWorker.Result {
        val daRoot = temporaryFolder.root.toPath().resolve("da")
        val outputRoot = temporaryFolder.root.toPath().resolve("exports")
        val worker = TestWorkerBuilder<BundleUploadWorker>(
            context = context,
            executor = Executors.newSingleThreadExecutor(),
            inputData = BundleUploadWorker.inputData(
                endpoint = "https://example.invalid/api",
                token = "secret",
                daRoot = daRoot.toString(),
                outputRoot = outputRoot.toString(),
            ),
        ).build()
        return worker.doWork()
    }

    @Test
    fun uploadsEverySessionWithRetryableEventsAndAcks() {
        val daRoot = temporaryFolder.root.toPath().resolve("da")
        val index = RoomEventIndex(database)
        seedSession(daRoot, index, "sess-a")
        seedSession(daRoot, index, "sess-b")
        val uploader = RecordingUploader(BundleUploadResult(ok = true, statusCode = 200, responseBody = "ok"))
        BundleUploadWorker.uploaderFactory = { uploader }

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(2, uploader.uploads.size)
        assertEquals(0, index.eventsByDispatchState(DispatchState.PENDING).size)
        assertEquals(0, index.eventsByDispatchState(DispatchState.BUNDLED).size)
        assertEquals(4, index.eventsByDispatchState(DispatchState.ACKED).size)
    }

    @Test
    fun failedUploadRequestsRetryAndKeepsRowsBundled() {
        val daRoot = temporaryFolder.root.toPath().resolve("da")
        val index = RoomEventIndex(database)
        seedSession(daRoot, index, "sess-a")
        val uploader = RecordingUploader(BundleUploadResult(ok = false, statusCode = 503, responseBody = "later"))
        BundleUploadWorker.uploaderFactory = { uploader }

        val result = runWorker()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(0, index.eventsByDispatchState(DispatchState.PENDING).size)
        assertEquals(2, index.eventsByDispatchState(DispatchState.BUNDLED).size)
        assertEquals(0, index.eventsByDispatchState(DispatchState.ACKED).size)
    }

    @Test
    fun nothingToUploadSucceedsWithoutTouchingNetwork() {
        val uploader = RecordingUploader(BundleUploadResult(ok = true, statusCode = 200, responseBody = "ok"))
        BundleUploadWorker.uploaderFactory = { uploader }

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, uploader.uploads.size)
    }

    @Test
    fun sessionIdParsesFromCanonicalChannelPath() {
        assertEquals(
            "sess-20260704-120000",
            BundleUploadWorker.sessionIdFromChannelPath(
                """["perception","sess-20260704-120000","video"]""",
            ),
        )
        assertNull(BundleUploadWorker.sessionIdFromChannelPath("""["other","x"]"""))
    }

    @Test
    fun schedulePeriodicEnqueuesUniqueConstrainedWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        BundleUploadWorker.schedulePeriodic(
            context = context,
            endpoint = "https://example.invalid/api",
            token = null,
            daRoot = "/data/da",
            outputRoot = "/data/exports",
        )

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(BundleUploadWorker.UNIQUE_WORK_NAME)
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    private fun seedSession(daRoot: Path, index: RoomEventIndex, sessionId: String) {
        val ingestor = EventIngestor(FileDA(daRoot), index)
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
            provenance = provenance(),
        )
        ingestor.ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-audio-tag-v0.1"),
                "sessionId" to CString(sessionId),
                "label" to CString("Speech"),
                "labelSpace" to CString("audioset"),
                "scorePerMille" to CLong(821),
                "tStartNanos" to CLong(0),
                "tEndNanos" to CLong(1_000_000_000),
                "observedAt" to CString("2026-07-04T12:00:01Z"),
            ),
            observedAt = "2026-07-04T12:00:01Z",
            actorPath = listOf("device", "moto-g84-5g", "microphone", "0"),
            channelPath = listOf("perception", sessionId, "audio"),
            valueKind = "audio-tag-segment",
            parentEventIds = listOf(root.eventId),
            rootEventId = root.eventId,
            provenance = provenance(),
        )
    }

    private fun provenance() = cMap(
        "source" to CString("android-percept"),
        "observedBy" to CString("percept-app"),
        "ingestionPipeline" to CString("event-trace-v0"),
    )

    private class RecordingUploader(
        private val result: BundleUploadResult,
    ) : BundleUploader {
        val uploads = mutableListOf<Path>()

        override fun upload(bundleZip: Path, destination: BundleUploadDestination): BundleUploadResult {
            uploads.add(bundleZip)
            return result
        }
    }
}
