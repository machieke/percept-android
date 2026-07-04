package org.takopi.percept.core.index

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.takopi.percept.core.canonical.CBool
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomEventIndexRobolectricTest {
    private lateinit var database: EventPointerDatabase
    private lateinit var daDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EventPointerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        daDir = File(context.cacheDir, "m3-robolectric-da-test").also {
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
    fun writesOneThousandEventsAndQueriesByPrefixesAndParentOnHostJvm() {
        val da = FileDA(daDir.toPath())
        val index = RoomEventIndex(database)
        val ingestor = EventIngestor(da, index)

        val root = ingestor.ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-session-v0.1"),
                "device" to CString("moto-g84-5g"),
                "sessionId" to CString("m3-robolectric-session"),
                "monotonicAnchorNanos" to CLong(123456789000),
                "observedAt" to CString(observedAt(0)),
            ),
            observedAt = observedAt(0),
            actorPath = listOf("device", "moto-g84-5g", "app", "percept"),
            channelPath = listOf("perception", "m3-robolectric-session", "session"),
            valueKind = "session-start",
            preview = "session m3-robolectric-session start",
            provenance = androidProvenance(),
        )

        val emitted = mutableListOf(root)
        for (trackId in 1..999) {
            emitted += ingestor.ingestEvent(
                rawPayload = cMap(
                    "kind" to CString("raw-payload"),
                    "schema" to CString("perception-track-segment-v0.1"),
                    "sessionId" to CString("m3-robolectric-session"),
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
                channelPath = listOf("perception", "m3-robolectric-session", "video"),
                valueKind = "track-segment",
                parentEventIds = listOf(root.eventId),
                rootEventId = root.eventId,
                provenance = cMap(
                    "source" to CString("android-percept"),
                    "observedBy" to CString("percept-app"),
                    "ingestionPipeline" to CString("event-trace-v0"),
                    "extractionRunId" to CString("robolectric-synthetic-v0"),
                ),
            )
        }

        assertEquals(1000, index.eventsByTimePrefix("/2026/07/04/12").size)
        val videoRows = index.eventsByChannelPrefix("/perception/m3-robolectric-session/video")
        assertEquals(999, videoRows.size)
        assertEquals(emitted[1].eventId, videoRows.first().eventId)
        assertEquals(emitted[999].eventId, videoRows.last().eventId)
        val children = index.childrenOf(root.eventId)
        assertEquals(999, children.size)
        assertEquals(emitted[1].eventId, children.first().eventId)
        assertEquals(999, index.updateDispatchState(emitted.drop(1).map { it.eventId }, DispatchState.BUNDLED))
        assertEquals(1, index.eventsByDispatchState(DispatchState.PENDING).size)
        assertEquals(999, index.eventsByDispatchState(DispatchState.BUNDLED).size)
        assertEquals(1000, index.allEvents().size)

        listOf(0, 127, 511, 999).forEach { sampleIndex ->
            val event = emitted[sampleIndex]
            assertTrue(da.verify(event.payloadCid).ok)
            assertTrue(da.verify(event.eventCid).ok)
        }
    }

    @Test
    fun storesLosslessCanonicalPathArraysForSegmentsContainingSlashes() {
        val da = FileDA(daDir.toPath())
        val index = RoomEventIndex(database)
        val event = EventIngestor(da, index).ingestEvent(
            rawPayload = cMap(
                "kind" to CString("raw-payload"),
                "schema" to CString("perception-session-v0.1"),
                "device" to CString("moto-g84-5g"),
                "sessionId" to CString("slash-session"),
                "monotonicAnchorNanos" to CLong(123456789000),
                "observedAt" to CString(observedAt(0)),
            ),
            observedAt = observedAt(0),
            actorPath = listOf("device", "a/b"),
            channelPath = listOf("perception", "slash/session"),
            valueKind = "session-start",
            provenance = androidProvenance(),
        )

        val rows = index.eventsByActorPrefix("/device/a%2Fb")
        assertEquals(event.eventId, rows.single().eventId)
        assertEquals("""["device","a/b"]""", rows.single().actorPath)
        assertEquals("""["perception","slash/session"]""", rows.single().channelPath)
        assertEquals(CBool(true), event.ack.entries["ok"])
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
