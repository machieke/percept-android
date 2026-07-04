package org.takopi.percept.dispatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.io.path.exists
import kotlin.io.path.writeText

class RetentionPlannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun evictsOnlyAckedDataOldestFirstWithKeyframesLast() {
        val candidates = listOf(
            candidate(cid = "cid-old-unacked", sizeBytes = 30, acked = false, keyframe = false, lastModifiedMillis = 1),
            candidate(cid = "cid-old-keyframe", sizeBytes = 40, acked = true, keyframe = true, lastModifiedMillis = 2),
            candidate(cid = "cid-old-non-keyframe", sizeBytes = 20, acked = true, keyframe = false, lastModifiedMillis = 3),
            candidate(cid = "cid-new-non-keyframe", sizeBytes = 30, acked = true, keyframe = false, lastModifiedMillis = 4),
        )

        val plan = RetentionPlanner().planEviction(candidates, capBytes = 70)

        assertEquals(120, plan.currentBytes)
        assertEquals(70, plan.projectedBytes)
        assertEquals(
            listOf("cid-old-non-keyframe", "cid-new-non-keyframe"),
            plan.evict.map { it.cid },
        )
    }

    @Test
    fun evictorDeletesObjectAndManifestFiles() {
        val objectPath = temporaryFolder.newFile("object").toPath()
        val manifestPath = temporaryFolder.newFile("manifest.json").toPath()
        objectPath.writeText("object")
        manifestPath.writeText("manifest")

        val removed = FileRetentionEvictor().evict(
            listOf(
                RetentionCandidate(
                    cid = "cid",
                    objectPath = objectPath,
                    manifestPath = manifestPath,
                    sizeBytes = 6,
                    acked = true,
                    lastModifiedMillis = 1,
                ),
            ),
        )

        assertEquals(1, removed)
        assertFalse(objectPath.exists())
        assertFalse(manifestPath.exists())
    }

    @Test
    fun leavesDataInPlaceWhenCapIsAlreadySatisfied() {
        val plan = RetentionPlanner().planEviction(
            candidates = listOf(
                candidate(cid = "cid-a", sizeBytes = 10, acked = true, keyframe = false, lastModifiedMillis = 1),
            ),
            capBytes = 10,
        )

        assertTrue(plan.evict.isEmpty())
        assertEquals(10, plan.projectedBytes)
    }

    private fun candidate(
        cid: String,
        sizeBytes: Long,
        acked: Boolean,
        keyframe: Boolean,
        lastModifiedMillis: Long,
    ) = RetentionCandidate(
        cid = cid,
        objectPath = temporaryFolder.root.toPath().resolve(cid),
        manifestPath = temporaryFolder.root.toPath().resolve("$cid.json"),
        sizeBytes = sizeBytes,
        acked = acked,
        keyframe = keyframe,
        lastModifiedMillis = lastModifiedMillis,
    )
}
