package org.takopi.percept.core.da

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.takopi.percept.core.canonical.CLong
import org.takopi.percept.core.canonical.CString
import org.takopi.percept.core.canonical.cMap
import org.takopi.percept.core.canonical.cidForBytes
import java.nio.charset.StandardCharsets

class FileDATest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fileDaStoresAndVerifiesBytesByDigest() {
        val da = FileDA(temporaryFolder.newFolder("da").toPath())
        val data = "hello".toByteArray(StandardCharsets.UTF_8)
        val cid = da.putBytes(data)

        assertEquals(cidForBytes(data), cid)
        assertTrue(da.has(cid))
        assertArrayEquals(data, da.getBytes(cid))
        assertEquals("raw", da.stat(cid).codec)
        assertTrue(da.verify(cid).ok)
    }

    @Test
    fun putJsonUsesCanonicalDagJsonBytes() {
        val da = MemoryDA()
        val cid = da.putJson(cMap("b" to CLong(2), "a" to CString("x")))

        assertEquals("""{"a":"x","b":2}""", da.getBytes(cid).toString(StandardCharsets.UTF_8))
        assertEquals("dag-json", da.stat(cid).codec)
    }
}
