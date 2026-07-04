package org.takopi.percept.core.canonical

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

const val CID_PREFIX: String = "cidv0-local-sha256:"

sealed interface CanonicalValue

data class CMap(val entries: Map<String, CanonicalValue>) : CanonicalValue {
    init {
        entries.keys.forEach(::requireAsciiKey)
    }
}

data class CList(val values: List<CanonicalValue>) : CanonicalValue
data class CString(val value: String) : CanonicalValue
data class CLong(val value: Long) : CanonicalValue
data class CBool(val value: Boolean) : CanonicalValue
data object CNull : CanonicalValue

data class TimeParts(
    val iso: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    fun toCanonicalValue(): CMap = cMap(
        "iso" to CString(iso),
        "year" to CLong(year.toLong()),
        "month" to CLong(month.toLong()),
        "day" to CLong(day.toLong()),
        "hour" to CLong(hour.toLong()),
        "minute" to CLong(minute.toLong()),
        "second" to CLong(second.toLong()),
    )
}

fun cMap(vararg pairs: Pair<String, CanonicalValue>): CMap = CMap(linkedMapOf(*pairs))

fun cList(vararg values: CanonicalValue): CList = CList(values.toList())

fun stringList(values: List<String>): CList = CList(values.map(::CString))

fun longList(values: List<Long>): CList = CList(values.map(::CLong))

fun canonicalBytes(v: CanonicalValue): ByteArray =
    buildString { appendCanonical(v) }.toByteArray(StandardCharsets.UTF_8)

fun canonicalBytes(value: Any?): ByteArray = canonicalBytes(toCanonicalValue(value))

fun toCanonicalValue(value: Any?): CanonicalValue = when (value) {
    null -> CNull
    is CanonicalValue -> value
    is String -> CString(value)
    is Boolean -> CBool(value)
    is Byte -> CLong(value.toLong())
    is Short -> CLong(value.toLong())
    is Int -> CLong(value.toLong())
    is Long -> CLong(value)
    is Float -> throw IllegalArgumentException("Float is not allowed in canonical JSON")
    is Double -> throw IllegalArgumentException("Double is not allowed in canonical JSON")
    is Map<*, *> -> {
        val mapped = linkedMapOf<String, CanonicalValue>()
        for ((key, item) in value) {
            require(key is String) { "canonical JSON map keys must be strings: $key" }
            requireAsciiKey(key)
            mapped[key] = toCanonicalValue(item)
        }
        CMap(mapped)
    }
    is Iterable<*> -> CList(value.map(::toCanonicalValue))
    is Array<*> -> CList(value.map(::toCanonicalValue))
    is IntArray -> CList(value.map { CLong(it.toLong()) })
    is LongArray -> CList(value.map(::CLong))
    is ShortArray -> CList(value.map { CLong(it.toLong()) })
    is ByteArray -> CList(value.map { CLong(it.toLong()) })
    is BooleanArray -> CList(value.map(::CBool))
    is FloatArray -> throw IllegalArgumentException("FloatArray is not allowed in canonical JSON")
    is DoubleArray -> throw IllegalArgumentException("DoubleArray is not allowed in canonical JSON")
    else -> throw IllegalArgumentException("unsupported canonical JSON value: ${value::class.qualifiedName}")
}

fun sha256Hex(b: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(b)
    return buildString(digest.size * 2) {
        for (byte in digest) {
            append("%02x".format(Locale.US, byte.toInt() and 0xff))
        }
    }
}

fun cidForBytes(b: ByteArray): String = CID_PREFIX + sha256Hex(b)

fun contentId(kind: String, v: CanonicalValue): String = "$kind:${sha256Hex(canonicalBytes(v))}"

fun digestFromCid(cid: String): String {
    require(cid.startsWith(CID_PREFIX)) { "unsupported CID format: $cid" }
    val digest = cid.removePrefix(CID_PREFIX)
    require(digest.length == 64) { "invalid sha256 digest length in CID: $cid" }
    require(digest.all { it in '0'..'9' || it in 'a'..'f' }) {
        "invalid sha256 digest in CID: $cid"
    }
    return digest
}

fun parseUtcTime(iso: String): TimeParts {
    val normalized = if (iso.endsWith("Z")) iso.dropLast(1) + "+00:00" else iso
    val utc = OffsetDateTime.parse(normalized).withOffsetSameInstant(ZoneOffset.UTC)
    return TimeParts(
        iso = utc.renderUtcIso(),
        year = utc.year,
        month = utc.monthValue,
        day = utc.dayOfMonth,
        hour = utc.hour,
        minute = utc.minute,
        second = utc.second,
    )
}

fun prefixKeys(path: List<String>): List<String> =
    path.indices.map { index -> pathKey(path.take(index + 1)) }

fun timePrefixKeys(t: TimeParts): List<String> = listOf(
    "/%04d".format(Locale.US, t.year),
    "/%04d/%02d".format(Locale.US, t.year, t.month),
    "/%04d/%02d/%02d".format(Locale.US, t.year, t.month, t.day),
    "/%04d/%02d/%02d/%02d".format(Locale.US, t.year, t.month, t.day, t.hour),
)

fun pathKey(path: List<String>): String =
    if (path.isEmpty()) "/" else "/" + path.joinToString("/") { encodeSegment(it) }

fun encodeSegment(segment: String): String {
    val bytes = segment.toByteArray(StandardCharsets.UTF_8)
    return buildString {
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xff
            if (isUnreserved(unsigned)) {
                append(unsigned.toChar())
            } else {
                append('%')
                append(uppercaseHex(unsigned ushr 4))
                append(uppercaseHex(unsigned and 0x0f))
            }
        }
    }
}

private fun StringBuilder.appendCanonical(value: CanonicalValue) {
    when (value) {
        is CMap -> {
            append('{')
            value.entries.toSortedMap().entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                appendJsonString(entry.key)
                append(':')
                appendCanonical(entry.value)
            }
            append('}')
        }
        is CList -> {
            append('[')
            value.values.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendCanonical(item)
            }
            append(']')
        }
        is CString -> appendJsonString(value.value)
        is CLong -> append(value.value)
        is CBool -> append(if (value.value) "true" else "false")
        CNull -> append("null")
    }
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char <= '\u001f') {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
    append('"')
}

private fun requireAsciiKey(key: String) {
    require(key.all { it.code in 0..0x7f }) { "canonical JSON map keys must be ASCII: $key" }
}

private fun isUnreserved(byte: Int): Boolean =
    byte in 'A'.code..'Z'.code ||
        byte in 'a'.code..'z'.code ||
        byte in '0'.code..'9'.code ||
        byte == '_'.code ||
        byte == '.'.code ||
        byte == '-'.code ||
        byte == '~'.code

private fun uppercaseHex(value: Int): Char = "0123456789ABCDEF"[value]

private fun OffsetDateTime.renderUtcIso(): String {
    val wholeSeconds = "%04d-%02d-%02dT%02d:%02d:%02d".format(
        Locale.US,
        year,
        monthValue,
        dayOfMonth,
        hour,
        minute,
        second,
    )
    if (nano == 0) return "${wholeSeconds}Z"
    val fractional = nano.toString().padStart(9, '0').trimEnd('0')
    return "$wholeSeconds.${fractional}Z"
}
