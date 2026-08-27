package com.kevin.legion.ui.generated

import org.junit.Assert.assertEquals
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
}
