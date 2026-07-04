package org.takopi.percept.dispatch

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

data class BundleUploadDestination(
    val url: URL,
    val bearerToken: String? = null,
)

data class BundleUploadResult(
    val ok: Boolean,
    val statusCode: Int,
    val responseBody: String,
)

interface BundleUploader {
    fun upload(bundleZip: Path, destination: BundleUploadDestination): BundleUploadResult
}

class HttpBundleUploader : BundleUploader {
    override fun upload(bundleZip: Path, destination: BundleUploadDestination): BundleUploadResult {
        require(bundleZip.exists()) { "bundle zip does not exist: $bundleZip" }
        val connection = destination.url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/zip")
            connection.setFixedLengthStreamingMode(bundleZip.toFile().length())
            destination.bearerToken?.takeIf { it.isNotBlank() }?.let { token ->
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            bundleZip.inputStream().use { input ->
                connection.outputStream.use { output -> input.copyTo(output) }
            }

            val statusCode = connection.responseCode
            val bodyStream = if (statusCode in 200..399) connection.inputStream else connection.errorStream
            BundleUploadResult(
                ok = statusCode in 200..299,
                statusCode = statusCode,
                responseBody = bodyStream?.use {
                    it.readBytes().toString(StandardCharsets.UTF_8)
                }.orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }
}
