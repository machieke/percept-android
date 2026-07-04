package org.takopi.percept.dispatch

import org.takopi.percept.core.canonical.CID_PREFIX
import org.takopi.percept.core.canonical.digestFromCid
import org.takopi.percept.core.index.DispatchState
import org.takopi.percept.core.index.EventPointerRow
import org.takopi.percept.core.index.RoomEventIndex
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

const val DEFAULT_LOCAL_CAP_BYTES: Long = 2L * 1024L * 1024L * 1024L

data class RetentionCandidate(
    val cid: String,
    val objectPath: Path,
    val manifestPath: Path?,
    val sizeBytes: Long,
    val acked: Boolean,
    val keyframe: Boolean = false,
    val lastModifiedMillis: Long,
)

data class RetentionPlan(
    val currentBytes: Long,
    val capBytes: Long,
    val evict: List<RetentionCandidate>,
) {
    val bytesToFree: Long = evict.sumOf { it.sizeBytes }
    val projectedBytes: Long = (currentBytes - bytesToFree).coerceAtLeast(0L)
}

class RetentionPlanner {
    fun planEviction(
        candidates: List<RetentionCandidate>,
        capBytes: Long = DEFAULT_LOCAL_CAP_BYTES,
    ): RetentionPlan {
        require(capBytes >= 0) { "capBytes must be non-negative" }
        val currentBytes = candidates.sumOf { it.sizeBytes }
        if (currentBytes <= capBytes) {
            return RetentionPlan(currentBytes = currentBytes, capBytes = capBytes, evict = emptyList())
        }

        var projectedBytes = currentBytes
        val evict = mutableListOf<RetentionCandidate>()
        candidates
            .asSequence()
            .filter { it.acked }
            .sortedWith(compareBy<RetentionCandidate> { it.keyframe }.thenBy { it.lastModifiedMillis }.thenBy { it.cid })
            .forEach { candidate ->
                if (projectedBytes > capBytes) {
                    evict += candidate
                    projectedBytes -= candidate.sizeBytes
                }
            }

        return RetentionPlan(currentBytes = currentBytes, capBytes = capBytes, evict = evict)
    }
}

class FileRetentionEvictor {
    fun evict(candidates: List<RetentionCandidate>): Int {
        var removed = 0
        candidates.forEach { candidate ->
            if (candidate.objectPath.deleteIfExists()) {
                removed += 1
            }
            candidate.manifestPath?.deleteIfExists()
        }
        return removed
    }
}

class DaRetentionCandidateCollector(
    private val index: RoomEventIndex,
) {
    fun collect(daRoot: Path): List<RetentionCandidate> {
        val references = linkedMapOf<String, MutableList<EventPointerRow>>()
        val keyframeCids = linkedSetOf<String>()

        index.allEvents().forEach { row ->
            references.getOrPut(row.eventCid) { mutableListOf() } += row
            references.getOrPut(row.payloadCid) { mutableListOf() } += row
            row.outputArtifactIds.lineSequence()
                .filter { it.startsWith(CID_PREFIX) }
                .forEach { cid ->
                    references.getOrPut(cid) { mutableListOf() } += row
                    keyframeCids += cid
                }
        }

        return references.mapNotNull { (cid, rows) ->
            val digest = digestFromCid(cid)
            val objectPath = daRoot.resolve("objects").resolve(digest)
            val manifestPath = daRoot.resolve("manifests").resolve("$digest.json")
            if (!objectPath.exists() || !objectPath.isRegularFile()) {
                null
            } else {
                RetentionCandidate(
                    cid = cid,
                    objectPath = objectPath,
                    manifestPath = manifestPath.takeIf { it.exists() },
                    sizeBytes = objectPath.toFile().length(),
                    acked = rows.all { it.dispatchState == DispatchState.ACKED.name },
                    keyframe = cid in keyframeCids,
                    lastModifiedMillis = objectPath.getLastModifiedTime().toMillis(),
                )
            }
        }.sortedBy { it.cid }
    }
}
