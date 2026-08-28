package com.kevin.legion.ui.generated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM - [PhotoFieldResolver] is Android-free by design (see its own doc comment), the
 * `fileExistsAt` check is injected rather than performed with a real `java.io.File`. */
class PhotoFieldResolverTest {

    @Test
    fun `a null path is NONE - the record never had a photo`() {
        assertEquals(PhotoFieldResolver.Status.NONE, PhotoFieldResolver.status(null) { true })
    }

    @Test
    fun `a blank path is also NONE, not ON_FILE`() {
        assertEquals(PhotoFieldResolver.Status.NONE, PhotoFieldResolver.status("") { true })
    }

    @Test
    fun `a non-blank path with a file present is ON_FILE`() {
        assertEquals(
            PhotoFieldResolver.Status.ON_FILE,
            PhotoFieldResolver.status("/data/x/y.jpg") { path -> path == "/data/x/y.jpg" },
        )
    }

    @Test
    fun `a non-blank path with no file present is MISSING, not NONE`() {
        val status = PhotoFieldResolver.status("/data/x/y.jpg") { false }
        assertEquals(PhotoFieldResolver.Status.MISSING, status)
    }

    @Test
    fun `NONE renders no label at all`() {
        assertNull(PhotoFieldResolver.label(PhotoFieldResolver.Status.NONE))
    }

    @Test
    fun `ON_FILE and MISSING render distinct, worded labels - never the same sentence`() {
        val onFile = PhotoFieldResolver.label(PhotoFieldResolver.Status.ON_FILE)!!
        val missing = PhotoFieldResolver.label(PhotoFieldResolver.Status.MISSING)!!
        assertTrue(onFile != missing)
        assertTrue(missing.contains("MISSING"))
        assertTrue(missing.contains("gone"))
    }

    // --------------------------------------------------------------------- ticket 09: ON_SERVER

    @Test
    fun `no local file but a remote copy on record is ON_SERVER, not MISSING`() {
        val status = PhotoFieldResolver.status("/data/x/y.jpg", hasRemoteCopy = true) { false }
        assertEquals(PhotoFieldResolver.Status.ON_SERVER, status)
    }

    @Test
    fun `a blank local path with a remote copy on record is ON_SERVER, not NONE`() {
        val status = PhotoFieldResolver.status(null, hasRemoteCopy = true) { false }
        assertEquals(PhotoFieldResolver.Status.ON_SERVER, status)
    }

    @Test
    fun `a local file present wins over a remote copy - ON_FILE, not ON_SERVER`() {
        val status = PhotoFieldResolver.status("/data/x/y.jpg", hasRemoteCopy = true) { true }
        assertEquals(PhotoFieldResolver.Status.ON_FILE, status)
    }

    @Test
    fun `no local file and no remote copy is still MISSING - hasRemoteCopy defaulting to false changes nothing`() {
        val status = PhotoFieldResolver.status("/data/x/y.jpg") { false }
        assertEquals(PhotoFieldResolver.Status.MISSING, status)
    }

    @Test
    fun `ON_SERVER's label never reads as MISSING or as no-photo - the three worded states stay distinct`() {
        val onServer = PhotoFieldResolver.label(PhotoFieldResolver.Status.ON_SERVER)!!
        val missing = PhotoFieldResolver.label(PhotoFieldResolver.Status.MISSING)!!
        assertTrue(onServer != missing)
        assertFalse(onServer.contains("MISSING"))
        assertTrue(onServer.contains("Supabase"))
    }
}
