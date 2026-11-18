package com.kevin.legion.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kevin.legion.ui.apps.AppMonochrome.Source
import com.kevin.legion.ui.apps.AppMonochrome.source

/**
 * Kevin, 2026-11-18: app shortcuts are small MONOCHROME icons, never colour logos (ADR 0081 holds).
 * Which layer becomes the mark, and when a silhouette is too solid to read as one.
 */
class AppMonochromeTest {

    private fun pixels(opaque: Int, total: Int = 100): IntArray =
        IntArray(total) { i -> if (i < opaque) 0xFF000000.toInt() else 0x00000000 }

    @Test
    fun `the app's own monochrome layer wins when it ships one (Android 13 and later)`() {
        assertEquals(Source.MONOCHROME, source(api = 33, adaptive = true, hasMonochrome = true))
    }

    @Test
    fun `before Android 13, or with no monochrome layer, an adaptive icon gives its foreground`() {
        assertEquals(Source.FOREGROUND, source(api = 31, adaptive = true, hasMonochrome = true))
        assertEquals(Source.FOREGROUND, source(api = 34, adaptive = true, hasMonochrome = false))
    }

    @Test
    fun `a legacy icon can only give its whole picture`() {
        assertEquals(Source.WHOLE, source(api = 34, adaptive = false, hasMonochrome = false))
    }

    @Test
    fun `coverage counts the pixels at least half opaque`() {
        assertEquals(0.3f, AppMonochrome.coverage(pixels(opaque = 30)), 0.0001f)
        val halfAlpha = IntArray(4) { 0x80FFFFFF.toInt() } + IntArray(4) { 0x7FFFFFFF }
        assertEquals(0.5f, AppMonochrome.coverage(halfAlpha), 0.0001f)
    }

    @Test
    fun `a mark reads as a shape, neither empty nor a filled tile`() {
        assertTrue(AppMonochrome.readable(pixels(opaque = 30)))
        // A legacy icon's square background, or a full-bleed foreground: a blob in one ink.
        assertFalse(AppMonochrome.readable(pixels(opaque = 75)))
        // A layer that drew nothing in the visible area.
        assertFalse(AppMonochrome.readable(pixels(opaque = 1)))
    }
}
