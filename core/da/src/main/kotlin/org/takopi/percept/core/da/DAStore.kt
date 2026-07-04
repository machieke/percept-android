package org.takopi.percept.core.da

import org.takopi.percept.core.canonical.CMap
import org.takopi.percept.core.canonical.canonicalBytes
import org.takopi.percept.core.canonical.cidForBytes
import org.takopi.percept.core.canonical.digestFromCid
import org.takopi.percept.core.canonical.sha256Hex
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

interface DAStore {
    fun putBytes(data: ByteArray, codec: String = "raw"): String
    fun putJson(value: CMap, codec: String = "dag-json"): String
    fun getBytes(cid: String): ByteArray
    fun has(cid: String): Boolean
    fun stat(cid: String): DaManifest
    fun verify(cid: String): DaVerifyResult
}

data class DaManifest(
    val cid: String,
    val codec: String,
    val size: Long,
    val digest: String,
)

data class DaVerifyResult(
    val ok: Boolean,
    val cid: String,
    val codec: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val checks: Map<String, Boolean> = emptyMap(),
    val error: String? = null,
)

class FileDA(root: Path) : DAStore {
    private val objectsDir: Path = root.resolve("objects")
    private val manifestsDir: Path = root.resolve("manifests")

    init {
        objectsDir.createDirectories()
        manifestsDir.createDirectories()
    }

    override fun putBytes(data: ByteArray, codec: String): String {
        val cid = cidForBytes(data)
        val digest = digestFromCid(cid)
        val objectPath = objectsDir.resolve(digest)
        if (!objectPath.exists()) {
            objectPath.writeBytes(data)
        }
        val manifestPath = manifestsDir.resolve("$digest.json")
        if (!manifestPath.exists()) {
            manifestPath.writeText(renderManifest(manifest(cid, codec, data)), StandardCharsets.UTF_8)
        }
        return cid
    }

    override fun putJson(value: CMap, codec: String): String =
        putBytes(canonicalBytes(value), codec)

    override fun getBytes(cid: String): ByteArray {
        val digest = digestFromCid(cid)
        val objectPath = objectsDir.resolve(digest)
        if (!objectPath.exists()) throw NoSuchElementException(cid)
        val data = objectPath.readBytes()
        val actual = sha256Hex(data)
        if (actual != digest) {
            throw IllegalStateException("DA object digest mismatch for $cid: $actual")
        }
        return data
    }

    override fun has(cid: String): Boolean = objectsDir.resolve(digestFromCid(cid)).exists()

    override fun stat(cid: String): DaManifest {
        val digest = digestFromCid(cid)
        val manifestPath = manifestsDir.resolve("$digest.json")
        if (!manifestPath.exists()) throw NoSuchElementException(cid)
        return parseManifest(manifestPath.readText(StandardCharsets.UTF_8))
    }

    override fun verify(cid: String): DaVerifyResult {
        return try {
            val manifest = stat(cid)
            val data = getBytes(cid)
            verifyManifest(cid, manifest, data)
        } catch (exception: Exception) {
            DaVerifyResult(ok = false, cid = cid, error = exception.message)
        }
    }
}

class MemoryDA : DAStore {
    private val objects = linkedMapOf<String, ByteArray>()
    private val manifests = linkedMapOf<String, DaManifest>()

    override fun putBytes(data: ByteArray, codec: String): String {
        val cid = cidForBytes(data)
        val digest = digestFromCid(cid)
        objects.putIfAbsent(digest, data.copyOf())
        manifests.putIfAbsent(digest, manifest(cid, codec, data))
        return cid
    }

    override fun putJson(value: CMap, codec: String): String =
        putBytes(canonicalBytes(value), codec)

    override fun getBytes(cid: String): ByteArray {
        val digest = digestFromCid(cid)
        val data = objects[digest] ?: throw NoSuchElementException(cid)
        val actual = sha256Hex(data)
        if (actual != digest) {
            throw IllegalStateException("DA object digest mismatch for $cid: $actual")
        }
        return data.copyOf()
    }

    override fun has(cid: String): Boolean = objects.containsKey(digestFromCid(cid))

    override fun stat(cid: String): DaManifest =
        manifests[digestFromCid(cid)] ?: throw NoSuchElementException(cid)

    override fun verify(cid: String): DaVerifyResult {
        return try {
            val manifest = stat(cid)
            val data = getBytes(cid)
            verifyManifest(cid, manifest, data)
        } catch (exception: Exception) {
            DaVerifyResult(ok = false, cid = cid, error = exception.message)
        }
    }
}

private fun manifest(cid: String, codec: String, data: ByteArray): DaManifest {
    val digest = digestFromCid(cid)
    return DaManifest(
        cid = cid,
        codec = codec,
        size = data.size.toLong(),
        digest = "sha256:$digest",
    )
}

private fun verifyManifest(cid: String, manifest: DaManifest, data: ByteArray): DaVerifyResult {
    val digest = digestFromCid(cid)
    val actualDigest = sha256Hex(data)
    val checks = linkedMapOf(
        "cid" to (manifest.cid == cid),
        "digest" to (actualDigest == digest && manifest.digest == "sha256:$digest"),
        "size" to (manifest.size == data.size.toLong()),
    )
    return DaVerifyResult(
        ok = checks.values.all { it },
        cid = cid,
        codec = manifest.codec,
        size = data.size.toLong(),
        digest = "sha256:$actualDigest",
        checks = checks,
    )
}

private fun renderManifest(manifest: DaManifest): String = """
{
  "cid": "${manifest.cid}",
  "codec": "${manifest.codec}",
  "digest": "${manifest.digest}",
  "size": ${manifest.size}
}
""".trimIndent()

private fun parseManifest(text: String): DaManifest {
    fun stringField(name: String): String {
        val match = Regex(""""$name"\s*:\s*"([^"]*)"""").find(text)
        return match?.groupValues?.get(1) ?: throw IllegalArgumentException("missing manifest field: $name")
    }

    fun longField(name: String): Long {
        val match = Regex(""""$name"\s*:\s*(\d+)""").find(text)
        return match?.groupValues?.get(1)?.toLong()
            ?: throw IllegalArgumentException("missing manifest field: $name")
    }

    return DaManifest(
        cid = stringField("cid"),
        codec = stringField("codec"),
        digest = stringField("digest"),
        size = longField("size"),
    )
}
