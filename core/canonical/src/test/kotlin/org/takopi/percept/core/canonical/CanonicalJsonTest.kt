package org.takopi.percept.core.canonical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets

class CanonicalJsonTest {
    @Test
    fun canonicalEdgeVectorSortsKeysAndKeepsUnicodeLiteral() {
        val value = cMap(
            "a" to CLong(1),
            "b" to cList(CBool(true), CBool(false), CNull),
            "c" to CString("héllo/世界"),
            "d" to cMap(
                "z" to CLong(1),
                "y" to CLong(2),
            ),
        )
        val bytes = canonicalBytes(value)

        assertEquals(
            """{"a":1,"b":[true,false,null],"c":"héllo/世界","d":{"y":2,"z":1}}""",
            bytes.toString(StandardCharsets.UTF_8),
        )
        assertEquals(
            "eec415221dd0565eb75dbe922a01f0a53dd0de3427d28bbcfff8319780f6a178",
            sha256Hex(bytes),
        )
    }

    @Test
    fun canonicalEdgeVectorEscapesLikePythonJson() {
        val value = cMap(
            "emoji" to CString("🚲"),
            "quote" to CString("\"q\""),
            "slash" to CString("a\\b"),
            "ctrl" to CString("line\nbreak\ttab"),
        )
        val bytes = canonicalBytes(value)

        assertEquals(
            """{"ctrl":"line\nbreak\ttab","emoji":"🚲","quote":"\"q\"","slash":"a\\b"}""",
            bytes.toString(StandardCharsets.UTF_8),
        )
        assertEquals(
            "99a0a0f93e7d568c7cf56fcc1a110bad4f5043b7c7acd9bc23d52e70dc936095",
            sha256Hex(bytes),
        )
    }

    @Test
    fun canonicalEdgeVectorNumbersAreIntegersOnly() {
        val value = cMap(
            "neg" to CLong(-42),
            "zero" to CLong(0),
            "big" to CLong(9007199254740991),
        )
        val bytes = canonicalBytes(value)

        assertEquals(
            """{"big":9007199254740991,"neg":-42,"zero":0}""",
            bytes.toString(StandardCharsets.UTF_8),
        )
        assertEquals(
            "2c94976a5419a37e61093c89e04772ce50c87ba9dfc421685a394a6a39dc54c9",
            sha256Hex(bytes),
        )
    }

    @Test
    fun canonicalConversionRejectsFloatsAndNonAsciiKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            canonicalBytes(mapOf("score" to 0.5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalBytes(mapOf("é" to 1))
        }
    }

    @Test
    fun pathAndTimeHelpersMatchReferenceShapes() {
        assertEquals(
            listOf(
                "/a%2Fb",
                "/a%2Fb/white%20space",
                "/a%2Fb/white%20space/%E4%B8%96%E7%95%8C",
            ),
            prefixKeys(listOf("a/b", "white space", "世界")),
        )

        val time = parseUtcTime("2026-07-04T14:00:41+02:00")
        assertEquals("2026-07-04T12:00:41Z", time.iso)
        assertEquals(listOf("/2026", "/2026/07", "/2026/07/04", "/2026/07/04/12"), timePrefixKeys(time))
    }

    @Test
    fun parseUtcTimeTruncatesFractionalSecondsForEnvelopePurity() {
        val time = parseUtcTime("2026-07-04T12:00:41.987654321Z")

        assertEquals("2026-07-04T12:00:41Z", time.iso)
        assertEquals(41, time.second)
    }
}
