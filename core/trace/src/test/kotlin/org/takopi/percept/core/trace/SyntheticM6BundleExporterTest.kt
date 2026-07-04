package org.takopi.percept.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import kotlin.io.path.readText

class SyntheticM6BundleExporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exportsFiveMinuteMultimodalBundleWithExpectedCounts() {
        val outputDir = temporaryFolder.newFolder("synthetic-m6").toPath()

        SyntheticM6BundleExporter.export(outputDir)

        val pointerLines = outputDir.resolve("pointers.jsonl")
            .readText(StandardCharsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() }
        assertEquals(SyntheticM6BundleExporter.EXPECTED_EVENT_COUNT, pointerLines.size)
        assertEquals(
            SyntheticM6BundleExporter.EXPECTED_SESSION_EVENTS,
            pointerLines.count { """"channelPath":["perception","sess-synthetic-m6","session"]""" in it },
        )
        assertEquals(
            SyntheticM6BundleExporter.EXPECTED_VIDEO_EVENTS,
            pointerLines.count { """"channelPath":["perception","sess-synthetic-m6","video"]""" in it },
        )
        assertEquals(
            SyntheticM6BundleExporter.EXPECTED_AUDIO_EVENTS,
            pointerLines.count { """"channelPath":["perception","sess-synthetic-m6","audio"]""" in it },
        )

        val manifestFiles = outputDir.resolve("manifests").toFile().listFiles().orEmpty()
        val rawManifests = manifestFiles.count { manifest ->
            """"codec": "raw"""" in manifest.readText(Charsets.UTF_8)
        }
        assertEquals(10, rawManifests)
        assertEquals(154, outputDir.resolve("objects").toFile().listFiles().orEmpty().size)
        assertTrue(pointerLines.first().contains("session-start"))
        assertTrue(pointerLines.last().contains("session-stop"))
    }
}
