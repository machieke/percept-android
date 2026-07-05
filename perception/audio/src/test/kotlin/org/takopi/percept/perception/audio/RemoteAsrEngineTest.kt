package org.takopi.percept.perception.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/** Minimal single-connection HTTP server; android unit tests cannot see com.sun.net.httpserver. */
private class TinyHttpServer {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort

    @Volatile var responseStatus: Int = 200
    @Volatile var responseJson: String = "{}"
    @Volatile var receivedBody: ByteArray = ByteArray(0)
    @Volatile var receivedRequestLine: String = ""

    private val acceptThread = thread(name = "tiny-http") {
        try {
            while (true) {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream()
                    val requestLine = readLine(input)
                    receivedRequestLine = requestLine
                    var contentLength = 0
                    while (true) {
                        val header = readLine(input)
                        if (header.isEmpty()) break
                        if (header.lowercase().startsWith("content-length:")) {
                            contentLength = header.substringAfter(':').trim().toInt()
                        }
                    }
                    receivedBody = ByteArray(contentLength).also { buffer ->
                        var read = 0
                        while (read < contentLength) {
                            val n = input.read(buffer, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                    }
                    val body = responseJson.toByteArray(StandardCharsets.UTF_8)
                    val reason = if (responseStatus == 200) "OK" else "Error"
                    socket.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 $responseStatus $reason\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(StandardCharsets.UTF_8),
                        )
                        write(body)
                        flush()
                    }
                }
            }
        } catch (_: Exception) {
            // Socket closed on shutdown.
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
        }
        return line.toString()
    }

    fun stop() {
        serverSocket.close()
        acceptThread.join(2_000)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteAsrEngineTest {
    private lateinit var server: TinyHttpServer

    @Before
    fun setUp() {
        server = TinyHttpServer()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun engine() = RemoteAsrEngine("http://127.0.0.1:${server.port}")

    @Test
    fun postsPcm16AndMapsJsonResponse() {
        server.responseJson =
            """{"text":"hallo wereld","lang":"nl","startMs":250,"endMs":1000,"modelRunId":"parakeet"}"""
        val samples = ShortArray(16_000) { 1000 }

        val segments = engine().transcribe(samples, 16_000)

        assertEquals(32_000, server.receivedBody.size)
        assertTrue(server.receivedRequestLine.startsWith("POST /transcribe?sampleRate=16000 "))
        val segment = segments.single()
        assertEquals("hallo wereld", segment.text)
        assertEquals("nl", segment.langHint)
        assertEquals(250_000_000L, segment.startOffsetNanos)
        assertEquals(1_000_000_000L, segment.endOffsetNanos)
    }

    @Test
    fun emptyTranscriptYieldsNoSegments() {
        server.responseJson = """{"text":"  ","lang":"auto","startMs":0}"""
        assertTrue(engine().transcribe(ShortArray(1600), 16_000).isEmpty())
    }

    @Test(expected = IOException::class)
    fun serverErrorThrows() {
        server.responseStatus = 503
        server.responseJson = """{"detail":"overloaded"}"""
        engine().transcribe(ShortArray(1600), 16_000)
    }
}

class FallbackAsrEngineTest {
    private class FixedAsr(private val segments: List<AsrWindowSegment>) : AsrEngine {
        var calls = 0

        override fun transcribe(samples: ShortArray, sampleRate: Int): List<AsrWindowSegment> {
            calls += 1
            return segments
        }
    }

    private class FailingAsr : AsrEngine {
        override fun transcribe(samples: ShortArray, sampleRate: Int): List<AsrWindowSegment> =
            throw IOException("endpoint unreachable")
    }

    private val segment = AsrWindowSegment(
        text = "fallback text",
        startOffsetNanos = 0,
        endOffsetNanos = 1_000_000_000L,
        avgLogProbMicro = 0,
    )

    @Test
    fun usesPrimaryWhenItSucceeds() {
        val primary = FixedAsr(listOf(segment.copy(text = "primary text")))
        val fallback = FixedAsr(listOf(segment))
        val engine = FallbackAsrEngine(primary, fallback)

        val result = engine.transcribe(ShortArray(160), 16_000)

        assertEquals("primary text", result.single().text)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun fallsBackAndReportsWhenPrimaryThrows() {
        val fallback = FixedAsr(listOf(segment))
        var reported: Throwable? = null
        val engine = FallbackAsrEngine(FailingAsr(), fallback) { reported = it }

        val result = engine.transcribe(ShortArray(160), 16_000)

        assertEquals("fallback text", result.single().text)
        assertEquals(1, fallback.calls)
        assertTrue(reported is IOException)
    }
}
