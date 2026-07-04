package org.takopi.percept.core.index

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cList
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.da.FileDA
import org.takopi.percept.core.trace.EventIngestor
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

class RoomEventIndexInstrumentedTest {
    private lateinit var database: EventPointerDatabase
    private lateinit var daDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EventPointerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        daDir = File(context.cacheDir, "m3-da-test").also {
            it.deleteRecursively()
            it.mkdirs()
        }
    }

    @After
    fun tearDown() {
        database.close()
        daDir.deleteRecursively()
    }

    @Test
    fun writesOneThousandEventsAndQueriesByPrefixesAndParent() {
        val da = FileDA(daDir.toPath())
        val index = RoomEventIndex(database)
        val ingestor = EventIngestor(da, index)

        val root = ingestor.ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-session-v0.1"),
                "device" to CString("moto-g84-5g"),
                "sessionId" to CString("m3-session"),
                "monotonicAnchorNanos" to CLong(123456789000),
                "observedAt" to CString(observedAt(0)),
            ),
            observedAt = observedAt(0),
            actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
            channelPath = listOf("perception", "m3-session", "session"),
            valueKind = "session-start",
            preview = "session m3-session start",
            provenance = androidProvenance(),
        )

        val emitted = mutableListOf(root)
        for (trackId in 1..999) {
            emitted += ingestor.ingestEvent(
                rawPayload = cMap(
                    "kind" to CString("raw-payload"),
                    "schema" to CString("perception-track-segment-v0.1"),
                    "sessionId" to CString("m3-session"),
                    "trackId" to CLong(trackId.toLong()),
                    "label" to CString("person"),
                    "labelSpace" to CString("coco-80"),
                    "scorePerMille" to CLong(800),
                    "tStartNanos" to CLong((trackId - 1) * 1_000_000_000L),
                    "tEndNanos" to CLong(trackId * 1_000_000_000L),
                    "frameCount" to CLong(12),
                    "boxFirst" to cList(CLong(100), CLong(80), CLong(220), CLong(420)),
                    "boxLast" to cList(CLong(110), CLong(84), CLong(230), CLong(424)),
                    "observedAt" to CString(observedAt(trackId)),
                ),
                observedAt = observedAt(trackId),
                actorPath = listOf("device", "moto-g84-5g", "camera", "0"),
                channelPath = listOf("perception", "m3-session", "video"),
                valueKind = "track-segment",
                parentEventIds = listOf(root.eventId),
                rootEventId = root.eventId,
                provenance = cMap(
                    "source" to CString("android-percept"),
                    "observedBy" to CString("percept-app"),
                    "ingestionPipeline" to CString("event-trace-v0"),
                    "extractionRunId" to CString("instrumented-synthetic-v0"),
                ),
            )
        }

        assertEquals(1000, index.eventsByTimePrefix("/2026/07/04/12").size)
        assertEquals(999, index.eventsByChannelPrefix("/perception/m3-session/video").size)
        assertEquals(999, index.childrenOf(root.eventId).size)

        listOf(0, 127, 511, 999).forEach { sampleIndex ->
            val event = emitted[sampleIndex]
            assertArrayEquals(da.getBytes(event.eventCid), da.getBytes(event.eventCid))
            assertTrue(da.verify(event.payloadCid).ok)
            assertTrue(da.verify(event.eventCid).ok)
        }
    }

    private fun observedAt(secondOffset: Int): String {
        val time = OffsetDateTime.of(2026, 7, 4, 12, 0, 0, 0, ZoneOffset.UTC)
            .plusSeconds(secondOffset.toLong())
        return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(
            Locale.US,
            time.year,
            time.monthValue,
            time.dayOfMonth,
            time.hour,
            time.minute,
            time.second,
        )
    }

    private fun androidProvenance() = cMap(
        "source" to CString("android-percept"),
        "observedBy" to CString("percept-app"),
        "ingestionPipeline" to CString("event-trace-v0"),
    )
}
