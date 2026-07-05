package org.takopi.percept.perception.audio

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote ASR over HTTP (server/asr): POSTs one PCM16 LE window per request
 * and maps the JSON response into a segment. The Moto G84 tops out at
 * whisper-tiny RTF ~2.6 on-device; the LAN Parakeet endpoint answers the
 * same window in ~250 ms at whisper-large-class quality. Failures throw
 * IOException — wrap with [FallbackAsrEngine] for offline resilience.
 */
class RemoteAsrEngine(
    private val endpointUrl: String,
    private val connectTimeoutMillis: Int = 2_000,
    private val readTimeoutMillis: Int = 15_000,
) : CancellableAsrEngine {
    init {
        require(endpointUrl.isNotBlank()) { "endpointUrl must not be blank" }
    }

    /** Requests complete in well under a second; nothing worth aborting. */
    override fun cancelInFlight() {}

    override fun transcribe(samples: ShortArray, sampleRate: Int): List<AsrWindowSegment> {
        val url = URL(endpointUrl.trimEnd('/') + "/transcribe?sampleRate=$sampleRate")
        val body = ByteArray(samples.size * 2)
        var offset = 0
        for (sample in samples) {
            body[offset++] = (sample.toInt() and 0xFF).toByte()
            body[offset++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val connection = url.openConnection() as HttpURLConnection
        val responseText = try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { output -> output.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("ASR endpoint returned HTTP $status")
            }
            connection.inputStream.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(responseText)
        val text = json.optString("text").trim()
        if (text.isEmpty()) return emptyList()
        val windowEndNanos = samples.size * 1_000_000_000L / sampleRate
        val startOffsetNanos = (json.optLong("startMs", 0L) * 1_000_000L)
            .coerceIn(0L, windowEndNanos)
        return listOf(
            AsrWindowSegment(
                text = text,
                startOffsetNanos = startOffsetNanos,
                endOffsetNanos = windowEndNanos,
                avgLogProbMicro = 0,
                langHint = json.optString("lang").ifBlank { "auto" },
            ),
        )
    }
}

/**
 * Primary/fallback composition: remote ASR while the endpoint is reachable,
 * the on-device engine when it is not. Failures are reported per window so
 * connectivity loss degrades quality instead of losing the modality.
 */
class FallbackAsrEngine(
    private val primary: AsrEngine,
    private val fallback: AsrEngine,
    private val onPrimaryFailure: ((Throwable) -> Unit)? = null,
) : CancellableAsrEngine {
    override fun transcribe(samples: ShortArray, sampleRate: Int): List<AsrWindowSegment> =
        try {
            primary.transcribe(samples, sampleRate)
        } catch (t: Exception) {
            onPrimaryFailure?.invoke(t)
            fallback.transcribe(samples, sampleRate)
        }

    override fun cancelInFlight() {
        (primary as? CancellableAsrEngine)?.cancelInFlight()
        (fallback as? CancellableAsrEngine)?.cancelInFlight()
    }
}
