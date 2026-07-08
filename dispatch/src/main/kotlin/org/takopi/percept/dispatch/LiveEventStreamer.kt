package org.takopi.percept.dispatch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.takopi.percept.core.da.DAStore
import org.takopi.percept.core.index.DispatchState
import org.takopi.percept.core.index.RoomEventIndex
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Sub-second delivery to the memory server: every locally ingested event is
 * POSTed to /events as it happens, so server-side reasoning sees the trace
 * live instead of at bundle cadence. Delivery is best-effort — a failed send
 * is simply left PENDING for the idempotent bundle backfill, and a success
 * is ACKed immediately so bundles only carry what the stream missed.
 */
class LiveEventStreamer(
    private val da: DAStore,
    private val index: RoomEventIndex,
    endpointUrl: String,
    private val bearerToken: String? = null,
    private val connectTimeoutMillis: Int = 2_000,
    private val readTimeoutMillis: Int = 4_000,
    capacity: Int = DEFAULT_CAPACITY,
    private val circuitBreakAfter: Int = DEFAULT_CIRCUIT_BREAK_AFTER,
    private val onError: ((String) -> Unit)? = null,
) {
    data class StreamedEvent(
        val eventId: String,
        val pointerJson: String,
        val objectCids: List<String>,
    )

    data class StreamerStats(
        val streamed: Long,
        val failed: Long,
        val dropped: Long,
    )

    private val eventsUrl = URL(endpointUrl.trimEnd('/') + "/events")
    private val channel = Channel<StreamedEvent>(capacity)
    private val streamed = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private var senderJob: Job? = null

    @Volatile
    private var errorReported = false

    // Once the endpoint has failed this many times in a row, stop attempting
    // POSTs for the rest of this streamer's life — a mall walk queued ~1700
    // events and each one otherwise burned a full timeout against a dead
    // endpoint (~10 min of radio). Undelivered events stay PENDING → bundles.
    @Volatile
    private var circuitOpen = false
    private var consecutiveFailures = 0

    fun start(scope: CoroutineScope) {
        check(senderJob == null) { "streamer already started" }
        senderJob = scope.launch {
            for (event in channel) {
                send(event)
            }
        }
    }

    fun trySubmit(event: StreamedEvent): Boolean {
        val accepted = channel.trySend(event).isSuccess
        if (!accepted) {
            dropped.incrementAndGet()
        }
        return accepted
    }

    /** Closes the stream and waits for queued events to flush (tests). */
    suspend fun stop() {
        channel.close()
        senderJob?.join()
    }

    /**
     * Non-blocking shutdown: close the channel and let the sender drain what
     * is queued in the background, then exit. Undelivered events remain
     * PENDING for bundle backfill — stop must never block on the network.
     */
    fun close() {
        channel.close()
    }

    fun stats(): StreamerStats =
        StreamerStats(streamed.get(), failed.get(), dropped.get())

    private fun send(event: StreamedEvent) {
        // Endpoint declared dead: skip the POST entirely. The event stays
        // PENDING (only success ACKs) and bundle backfill delivers it, so
        // draining a large queue against a down endpoint is instant.
        if (circuitOpen) {
            dropped.incrementAndGet()
            return
        }
        try {
            val objects = JSONObject()
            for (cid in event.objectCids) {
                val digest = cid.substringAfterLast(':')
                objects.put(digest, Base64.getEncoder().encodeToString(da.getBytes(cid)))
            }
            val body = JSONObject()
                .put("pointer", JSONObject(event.pointerJson))
                .put("objects", objects)
                .toString()
                .toByteArray(Charsets.UTF_8)

            val connection = eventsUrl.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.setRequestProperty("Content-Type", "application/json")
                bearerToken?.takeIf { it.isNotBlank() }?.let { token ->
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { output -> output.write(body) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IOException("memory server returned HTTP $status")
                }
                connection.inputStream.use { input -> input.readBytes() }
            } finally {
                connection.disconnect()
            }

            index.updateDispatchState(listOf(event.eventId), DispatchState.ACKED)
            streamed.incrementAndGet()
            consecutiveFailures = 0
        } catch (t: Exception) {
            failed.incrementAndGet()
            consecutiveFailures += 1
            if (!errorReported) {
                errorReported = true
                onError?.invoke("live stream failing (${t.message}); bundles will backfill")
            }
            if (consecutiveFailures >= circuitBreakAfter) {
                circuitOpen = true
            }
        }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 128
        const val DEFAULT_CIRCUIT_BREAK_AFTER: Int = 3
    }
}
