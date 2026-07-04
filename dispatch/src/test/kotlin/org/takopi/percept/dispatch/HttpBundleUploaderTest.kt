package org.takopi.percept.dispatch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.writeBytes

class HttpBundleUploaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun streamsZipWithPutAndBearerToken() {
        val receivedMethod = AtomicReference<String>()
        val receivedAuthorization = AtomicReference<String>()
        val receivedBody = AtomicReference<ByteArray>()
        val serverSocket = ServerSocket().also {
            it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        val serverThread = Thread {
            serverSocket.use { socket ->
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    val headers = readHeaders(input)
                    val lines = headers.lines().filter { it.isNotBlank() }
                    receivedMethod.set(lines.first().substringBefore(' '))
                    val headerMap = lines.drop(1).associate { line ->
                        val name = line.substringBefore(':').lowercase()
                        val value = line.substringAfter(':').trim()
                        name to value
                    }
                    receivedAuthorization.set(headerMap["authorization"])
                    val contentLength = headerMap.getValue("content-length").toInt()
                    receivedBody.set(input.readNBytes(contentLength))

                    val responseBody = "stored".toByteArray()
                    val response = (
                        "HTTP/1.1 201 Created\r\n" +
                            "Content-Length: ${responseBody.size}\r\n" +
                            "Connection: close\r\n" +
                            "\r\n"
                        ).toByteArray()
                    client.getOutputStream().use { output ->
                        output.write(response)
                        output.write(responseBody)
                    }
                }
            }
        }
        serverThread.start()

        val zipBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00)
        val bundleZip = temporaryFolder.newFile("bundle.zip").toPath()
        bundleZip.writeBytes(zipBytes)

        val result = HttpBundleUploader().upload(
            bundleZip = bundleZip,
            destination = BundleUploadDestination(
                url = URL("http://127.0.0.1:${serverSocket.localPort}/bundles/test"),
                bearerToken = "token-123",
            ),
        )
        serverThread.join(5_000)

        assertTrue(result.ok)
        assertEquals(201, result.statusCode)
        assertEquals("stored", result.responseBody)
        assertFalse(serverThread.isAlive)
        assertEquals("PUT", receivedMethod.get())
        assertEquals("Bearer token-123", receivedAuthorization.get())
        assertArrayEquals(zipBytes, receivedBody.get())
    }

    private fun readHeaders(input: InputStream): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val next = input.read()
            if (next == -1) break
            bytes += next.toByte()
            val size = bytes.size
            if (
                size >= 4 &&
                bytes[size - 4] == '\r'.code.toByte() &&
                bytes[size - 3] == '\n'.code.toByte() &&
                bytes[size - 2] == '\r'.code.toByte() &&
                bytes[size - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }
}
