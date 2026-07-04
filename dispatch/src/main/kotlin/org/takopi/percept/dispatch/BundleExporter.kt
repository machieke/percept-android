package org.takopi.percept.dispatch

import org.takopi.percept.core.canonical.CID_PREFIX
import org.takopi.percept.core.canonical.digestFromCid
import org.takopi.percept.core.canonical.prefixKeys
import org.takopi.percept.core.index.DispatchState
import org.takopi.percept.core.index.EventPointerRow
import org.takopi.percept.core.index.RoomEventIndex
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

data class BundleExportRequest(
    val sessionId: String,
    val sequence: Int,
    val sourceDaRoot: Path,
    val outputRoot: Path,
    val rows: List<EventPointerRow>,
    val markBundled: Boolean = true,
)

data class BundleExportResult(
    val bundleId: String,
    val bundleDir: Path,
    val zipPath: Path,
    val pointerCount: Int,
    val objectCount: Int,
    val manifestCount: Int,
    val totalBytes: Long,
)

class BundleExporter(
    private val index: RoomEventIndex? = null,
) {
    fun exportPending(
        sessionId: String,
        sequence: Int,
        sourceDaRoot: Path,
        outputRoot: Path,
        maxEvents: Int = Int.MAX_VALUE,
    ): BundleExportResult {
        require(maxEvents > 0) { "maxEvents must be positive" }
        val eventIndex = requireNotNull(index) { "RoomEventIndex is required for exportPending" }
        val sessionPrefixKey = prefixKeys(listOf("perception", sessionId)).last()
        val rows = eventIndex.eventsByChannelPrefix(sessionPrefixKey)
            .filter { it.dispatchState == DispatchState.PENDING.name }
            .take(maxEvents)
        return export(
            BundleExportRequest(
                sessionId = sessionId,
                sequence = sequence,
                sourceDaRoot = sourceDaRoot,
                outputRoot = outputRoot,
                rows = rows,
                markBundled = true,
            ),
        )
    }

    @OptIn(ExperimentalPathApi::class)
    fun export(request: BundleExportRequest): BundleExportResult {
        require(request.sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(request.sequence >= 0) { "sequence must be non-negative" }

        val bundleId = "bundle-${request.sessionId}-${request.sequence.toString().padStart(4, '0')}"
        val bundleDir = request.outputRoot.resolve(bundleId)
        val zipPath = request.outputRoot.resolve("$bundleId.zip")
        val objectsDir = bundleDir.resolve("objects")
        val manifestsDir = bundleDir.resolve("manifests")

        request.outputRoot.createDirectories()
        if (bundleDir.exists()) {
            bundleDir.deleteRecursively()
        }
        zipPath.deleteIfExists()
        objectsDir.createDirectories()
        manifestsDir.createDirectories()

        val copiedDigests = linkedSetOf<String>()
        val copiedManifestDigests = linkedSetOf<String>()
        request.rows.forEach { row ->
            row.referencedDaCids().forEach { cid ->
                copyDaEntry(cid, request.sourceDaRoot, objectsDir, manifestsDir, copiedDigests, copiedManifestDigests)
            }
        }

        val pointerText = request.rows.joinToString(separator = "\n", postfix = if (request.rows.isEmpty()) "" else "\n") {
            it.pointerJson
        }
        bundleDir.resolve("pointers.jsonl").writeText(pointerText, StandardCharsets.UTF_8)

        zipDirectory(bundleDir, zipPath)
        if (request.markBundled && request.rows.isNotEmpty()) {
            index?.updateDispatchState(request.rows.map { it.eventId }, DispatchState.BUNDLED)
        }

        return BundleExportResult(
            bundleId = bundleId,
            bundleDir = bundleDir,
            zipPath = zipPath,
            pointerCount = request.rows.size,
            objectCount = copiedDigests.size,
            manifestCount = copiedManifestDigests.size,
            totalBytes = copiedDigests.sumOf { digest -> objectsDir.resolve(digest).toFile().length() },
        )
    }

    private fun copyDaEntry(
        cid: String,
        sourceDaRoot: Path,
        objectsDir: Path,
        manifestsDir: Path,
        copiedDigests: MutableSet<String>,
        copiedManifestDigests: MutableSet<String>,
    ) {
        val digest = digestFromCid(cid)
        if (copiedDigests.add(digest)) {
            val sourceObject = sourceDaRoot.resolve("objects").resolve(digest)
            require(sourceObject.exists()) { "missing DA object for $cid at $sourceObject" }
            sourceObject.copyTo(objectsDir.resolve(digest), overwrite = true)
        }

        if (copiedManifestDigests.add(digest)) {
            val sourceManifest = sourceDaRoot.resolve("manifests").resolve("$digest.json")
            require(sourceManifest.exists()) { "missing DA manifest for $cid at $sourceManifest" }
            sourceManifest.copyTo(manifestsDir.resolve("$digest.json"), overwrite = true)
        }
    }

    private fun EventPointerRow.referencedDaCids(): List<String> =
        buildList {
            add(eventCid)
            add(payloadCid)
            outputArtifactIds.lineSequence()
                .filter { it.startsWith(CID_PREFIX) }
                .forEach(::add)
        }

    private fun zipDirectory(bundleDir: Path, zipPath: Path) {
        ZipOutputStream(zipPath.outputStream()).use { zip ->
            bundleDir.walkFilesDeterministically().forEach { file ->
                val relative = file.relativeTo(bundleDir).pathString.replace('\\', '/')
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun Path.walkFilesDeterministically(): List<Path> {
        val entries = listDirectoryEntries().sortedBy { entry ->
            val suffix = if (entry.isRegularFile()) ".${entry.extension}" else ""
            "${entry.name}$suffix"
        }
        return entries.flatMap { entry ->
            if (entry.isRegularFile()) listOf(entry) else entry.walkFilesDeterministically()
        }
    }
}
