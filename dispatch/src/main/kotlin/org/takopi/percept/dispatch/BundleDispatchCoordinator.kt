package org.takopi.percept.dispatch

import org.takopi.percept.core.index.DispatchState
import org.takopi.percept.core.index.RoomEventIndex
import java.nio.file.Path

data class BundleDispatchRequest(
    val sessionId: String,
    val sequence: Int,
    val sourceDaRoot: Path,
    val outputRoot: Path,
    val maxEvents: Int = Int.MAX_VALUE,
    val includeBundledRetries: Boolean = true,
)

data class BundleUploadDispatchResult(
    val export: BundleExportResult,
    val upload: BundleUploadResult?,
    val ackedEvents: Int,
)

class BundleDispatchCoordinator(
    private val index: RoomEventIndex,
    private val exporter: BundleExporter = BundleExporter(index),
    private val uploader: BundleUploader = HttpBundleUploader(),
) {
    fun exportPendingLocal(request: BundleDispatchRequest): BundleExportResult =
        exporter.exportPending(
            sessionId = request.sessionId,
            sequence = request.sequence,
            sourceDaRoot = request.sourceDaRoot,
            outputRoot = request.outputRoot,
            maxEvents = request.maxEvents,
        )

    fun exportAndUploadPending(
        request: BundleDispatchRequest,
        destination: BundleUploadDestination,
    ): BundleUploadDispatchResult {
        val export = if (request.includeBundledRetries) {
            exporter.exportRetryable(
                sessionId = request.sessionId,
                sequence = request.sequence,
                sourceDaRoot = request.sourceDaRoot,
                outputRoot = request.outputRoot,
                maxEvents = request.maxEvents,
            )
        } else {
            exportPendingLocal(request)
        }
        if (export.eventIds.isEmpty()) {
            return BundleUploadDispatchResult(export = export, upload = null, ackedEvents = 0)
        }

        val upload = uploader.upload(export.zipPath, destination)
        val ackedEvents = if (upload.ok) {
            index.updateDispatchState(export.eventIds, DispatchState.ACKED)
        } else {
            0
        }
        return BundleUploadDispatchResult(export = export, upload = upload, ackedEvents = ackedEvents)
    }

    fun markAcked(eventIds: List<String>): Int =
        if (eventIds.isEmpty()) 0 else index.updateDispatchState(eventIds, DispatchState.ACKED)
}
