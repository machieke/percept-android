package org.takopi.percept.dispatch

import java.nio.file.Path
import kotlin.io.path.deleteIfExists

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
