package org.takopi.percept.dispatch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.da.FileDA
import org.takopi.percept.core.index.DispatchState
import org.takopi.percept.core.index.EventPointerDatabase
import org.takopi.percept.core.index.RoomEventIndex
import org.takopi.percept.core.trace.EventIngestor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveEventStreamerTest {
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
    fun deadEndpointOpensCircuitAfterThreeFailuresAndSkipsTheRest() = runBlocking {
        val daRoot = temporaryFolder.newFolder("da").toPath()
        val da = FileDA(daRoot)
        val index = RoomEventIndex(database)
        val ingestor = EventIngestor(da, index)

        // A closed port: every POST fails fast (connection refused).
        val deadPort = java.net.ServerSocket(0).use { it.localPort }
        val streamer = LiveEventStreamer(
            da = da,
            index = index,
            endpointUrl = "http://127.0.0.1:$deadPort",
            connectTimeoutMillis = 200,
            readTimeoutMillis = 200,
            circuitBreakAfter = 3,
        )
        streamer.start(this)

        val eventIds = (0 until 20).map { i ->
            val ingested = ingestor.ingestEvent(
                rawPayload = cMap(
                    "kind" to CString("raw-payload"),
                    "schema" to CString("perception-audio-tag-v0.1"),
                    "sessionId" to CString("sess-x"),
                    "label" to CString("Speech"),
                    "labelSpace" to CString("audioset"),
                    "scorePerMille" to CLong(500),
                    "tStartNanos" to CLong(i.toLong()),
                    "tEndNanos" to CLong(i + 1L),
                    "observedAt" to CString("2026-07-08T10:00:0${i % 10}Z"),
                ),
                observedAt = "2026-07-08T10:00:0${i % 10}Z",
                actorPath = listOf("device", "d", "microphone", "0"),
                channelPath = listOf("perception", "sess-x", "audio"),
                valueKind = "audio-tag-segment",
            )
            streamer.trySubmit(
                LiveEventStreamer.StreamedEvent(
                    eventId = ingested.eventId,
                    pointerJson = "{}",
                    objectCids = emptyList(),
                ),
            )
            ingested.eventId
        }
        streamer.stop()

        val stats = streamer.stats()
        // At most `circuitBreakAfter` real attempts; the rest skip instantly.
        assertEquals(0, stats.streamed)
        assertTrue("failed=${stats.failed}", stats.failed <= 3)
        assertTrue("dropped=${stats.dropped}", stats.dropped >= 17)
        // Nothing ACKed — everything stays PENDING for bundle backfill.
        val pending = index.eventsByDispatchState(DispatchState.PENDING).map { it.eventId }
        assertTrue(pending.containsAll(eventIds))
    }
}
