package org.takopi.percept.perception.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class YamnetClassMapTest {
    @Test
    fun parsesHeaderPlainAndQuotedCommaNames() {
        val labels = YamnetClassMap.parse(
            sequenceOf(
                "index,mid,display_name",
                "0,/m/09x0r,Speech",
                "1,/m/0k4j,\"Boat, Water vehicle\"",
                "2,/m/04rlf,Music",
            ),
        )
        assertEquals(listOf("Speech", "Boat, Water vehicle", "Music"), labels)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSparseIndexes() {
        YamnetClassMap.parse(
            sequenceOf(
                "0,/m/09x0r,Speech",
                "2,/m/04rlf,Music",
            ),
        ).size
    }
}
