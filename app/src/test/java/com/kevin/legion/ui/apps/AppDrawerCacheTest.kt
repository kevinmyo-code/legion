package com.kevin.legion.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDrawerCacheTest {

    private fun icons() = mutableMapOf(
        iconKey(0, "com.whatsapp", "com.whatsapp.Main") to 1,
        iconKey(0, "com.spotify.music", "com.spotify.Main") to 2,
        iconKey(10, "com.whatsapp", "com.whatsapp.Main") to 3, // same app, work profile
        iconKey(0, "com.whatsapp.w4b", "com.whatsapp.w4b.Main") to 4,
    )

    @Test
    fun `an updated app loses its icons in every profile, and nothing else does`() {
        val m = icons()
        evictIcons(m, listOf("com.whatsapp"))
        assertEquals(setOf(iconKey(0, "com.spotify.music", "com.spotify.Main"), iconKey(0, "com.whatsapp.w4b", "com.whatsapp.w4b.Main")), m.keys)
    }

    @Test
    fun `a package name that is a prefix of another does not evict the other`() {
        // "com.whatsapp" must not take "com.whatsapp.w4b" with it.
        val m = icons()
        evictIcons(m, listOf("com.whatsapp"))
        assertEquals(true, m.containsKey(iconKey(0, "com.whatsapp.w4b", "com.whatsapp.w4b.Main")))
    }

    @Test
    fun `an unknown package evicts nothing`() {
        val m = icons()
        evictIcons(m, listOf("com.nothing"))
        assertEquals(4, m.size)
    }
}
