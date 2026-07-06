package org.takopi.percept.dispatch

import android.content.Context
import androidx.room.Room
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.takopi.percept.core.index.DispatchState
import org.takopi.percept.core.index.EventPointerDatabase
import org.takopi.percept.core.index.RoomEventIndex
import java.net.URL
import java.nio.file.Paths
import java.time.Duration

/**
 * §3.6 transport v1b: exports every session with retryable events and PUTs
 * the zips to the configured endpoint. Success ACKs rows; any failed upload
 * returns retry so WorkManager's exponential backoff drives the next attempt.
 */
class BundleUploadWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val endpoint = inputData.getString(KEY_ENDPOINT) ?: return Result.failure()
        val daRoot = inputData.getString(KEY_DA_ROOT) ?: return Result.failure()
        val outputRoot = inputData.getString(KEY_OUTPUT_ROOT) ?: return Result.failure()
        val token = inputData.getString(KEY_TOKEN)
        val databaseName = inputData.getString(KEY_DB_NAME) ?: DEFAULT_DB_NAME

        val database = databaseFactory?.invoke(applicationContext)
            ?: Room.databaseBuilder(applicationContext, EventPointerDatabase::class.java, databaseName)
                .build()
        val ownsDatabase = databaseFactory == null
        try {
            val index = RoomEventIndex(database)
            val coordinator = BundleDispatchCoordinator(
                index = index,
                uploader = uploaderFactory(),
            )
            val sessionIds = retryableSessionIds(index)
            var anyFailure = false
            var anyAcked = false
            for ((offset, sessionId) in sessionIds.withIndex()) {
                val result = coordinator.exportAndUploadPending(
                    request = BundleDispatchRequest(
                        sessionId = sessionId,
                        sequence = sequenceBase() + offset,
                        sourceDaRoot = Paths.get(daRoot),
                        outputRoot = Paths.get(outputRoot),
                    ),
                    destination = BundleUploadDestination(
                        url = URL(endpoint.trimEnd('/') + "/bundles/" + sessionId),
                        bearerToken = token?.ifBlank { null },
                    ),
                )
                val upload = result.upload
                if (upload != null && !upload.ok) {
                    anyFailure = true
                }
                if (result.ackedEvents > 0) {
                    anyAcked = true
                }
            }
            if (anyAcked) {
                // The phone is a buffer, not the archive: shed acked bytes.
                evictAcked(index, Paths.get(daRoot))
            }
            return if (anyFailure) Result.retry() else Result.success()
        } finally {
            if (ownsDatabase) {
                database.close()
            }
        }
    }

    private fun retryableSessionIds(index: RoomEventIndex): List<String> {
        val rows = index.eventsByDispatchState(DispatchState.PENDING) +
            index.eventsByDispatchState(DispatchState.BUNDLED)
        return rows.mapNotNull { row -> sessionIdFromChannelPath(row.channelPath) }.distinct()
    }

    private fun evictAcked(index: RoomEventIndex, daRoot: java.nio.file.Path) {
        val candidates = DaRetentionCandidateCollector(index).collect(daRoot)
        val plan = RetentionPlanner().planEviction(candidates, capBytes = LEAN_CAP_BYTES)
        FileRetentionEvictor().evict(plan.evict)
    }

    private fun sequenceBase(): Int = (System.currentTimeMillis() / 1000L % Int.MAX_VALUE).toInt()

    companion object {
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TOKEN = "token"
        const val KEY_DA_ROOT = "daRoot"
        const val KEY_OUTPUT_ROOT = "outputRoot"
        const val KEY_DB_NAME = "dbName"
        const val DEFAULT_DB_NAME = "event-pointers.db"
        const val UNIQUE_WORK_NAME = "percept-bundle-upload"
        const val UNIQUE_ONE_SHOT_NAME = "percept-bundle-upload-now"

        /** Lean-buffer cap: acked artifacts are shed beyond this. */
        const val LEAN_CAP_BYTES: Long = 256L * 1024L * 1024L

        /** Test hooks; production uses the real uploader and file-backed Room. */
        var uploaderFactory: () -> BundleUploader = ::HttpBundleUploader
        var databaseFactory: ((Context) -> EventPointerDatabase)? = null

        /** Channel paths are canonical JSON like ["perception","sess-1","video"]. */
        fun sessionIdFromChannelPath(channelPathJson: String): String? {
            val match = Regex("""^\["perception","([^"]+)"""").find(channelPathJson)
            return match?.groupValues?.get(1)
        }

        fun inputData(
            endpoint: String,
            token: String?,
            daRoot: String,
            outputRoot: String,
            databaseName: String = DEFAULT_DB_NAME,
        ): Data = Data.Builder()
            .putString(KEY_ENDPOINT, endpoint)
            .putString(KEY_TOKEN, token)
            .putString(KEY_DA_ROOT, daRoot)
            .putString(KEY_OUTPUT_ROOT, outputRoot)
            .putString(KEY_DB_NAME, databaseName)
            .build()

        /**
         * Continuous-accumulation sweeper: hourly retry of anything not yet
         * acked, on any network. Bundles are small (~1 MB per 5 min) and the
         * tailnet makes cellular a first-class path, so the original
         * charging+unmetered constraints would defeat the purpose.
         */
        fun schedulePeriodic(
            context: Context,
            endpoint: String,
            token: String?,
            daRoot: String,
            outputRoot: String,
            repeatInterval: Duration = Duration.ofHours(1),
        ) {
            val request = PeriodicWorkRequestBuilder<BundleUploadWorker>(repeatInterval)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(10))
                .setInputData(inputData(endpoint, token, daRoot, outputRoot))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Immediate dispatch, e.g. right after a session stops. */
        fun enqueueImmediate(
            context: Context,
            endpoint: String,
            token: String?,
            daRoot: String,
            outputRoot: String,
        ) {
            val request = OneTimeWorkRequestBuilder<BundleUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .setInputData(inputData(endpoint, token, daRoot, outputRoot))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_SHOT_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
