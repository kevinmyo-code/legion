package com.kevin.legion.ui.home

import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.Loaded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildDockSlots] resolved against a drawer snapshot - installed, not installed (uninstalled or
 * the wrong profile), and work-profile-paused, per ticket 06's own "A pinned app that is no longer
 * installed... stays in its slot, dimmed" and "Work-profile apps may be pinned" sections. Icon
 * resolution itself is [com.kevin.legion.ui.apps.iconKey] plus a map lookup, already pinned by
 * [com.kevin.legion.ui.apps.AppDrawerCacheTest]'s own eviction tests - this file only carries icons
 * as an empty map, since building a real `ImageBitmap` needs no Android framework work worth
 * pulling in for what is otherwise a pure lookup (traced, not separately re-tested here).
 */
class AppDockTest {

    private val personal = DrawerApp("WhatsApp", "com.whatsapp", "com.whatsapp.Main", isWork = false, profileKey = 0)
    private val workApp = DrawerApp("Outlook", "com.outlook", "com.outlook.Main", isWork = true, profileKey = 10)

    private fun loaded(apps: List<DrawerApp>, workPaused: Boolean = false) = Loaded(
        apps = apps,
        icons = emptyMap(),
        handles = emptyMap(),
        workProfile = null,
        workPaused = workPaused,
    )

    @Test
    fun `an installed personal app resolves with its real label, not installed, not paused`() {
        val slots = buildDockSlots(listOf(DockPin("com.whatsapp", 0)), loaded(listOf(personal)))
        val slot = slots.single()
        assertEquals("WhatsApp", slot.label)
        assertTrue(slot.app != null)
        assertFalse(slot.paused)
    }

    @Test
    fun `a pin with no matching installed app resolves to not installed, dimmed`() {
        val slots = buildDockSlots(listOf(DockPin("com.gone", 0)), loaded(listOf(personal)))
        val slot = slots.single()
        assertNull(slot.app)
        // Falls back to the package name when there is no label to show - ticket's own "stays in
        // its slot" rather than the row disappearing entirely.
        assertEquals("com.gone", slot.label)
    }

    @Test
    fun `the same package in a DIFFERENT profile does not match - profileKey is part of identity`() {
        val slots = buildDockSlots(listOf(DockPin("com.whatsapp", 10)), loaded(listOf(personal)))
        assertNull(slots.single().app)
    }

    @Test
    fun `a work app while the work profile is paused resolves paused, not installed still true`() {
        val slots = buildDockSlots(listOf(DockPin("com.outlook", 10)), loaded(listOf(workApp), workPaused = true))
        val slot = slots.single()
        assertTrue(slot.app != null)
        assertTrue(slot.paused)
    }

    @Test
    fun `a personal app is never marked paused even when the work profile is`() {
        val slots = buildDockSlots(listOf(DockPin("com.whatsapp", 0)), loaded(listOf(personal), workPaused = true))
        assertFalse(slots.single().paused)
    }

    @Test
    fun `a null drawer snapshot resolves every slot to loading, never not installed`() {
        val slot = buildDockSlots(listOf(DockPin("com.whatsapp", 0)), null).single()
        assertNull(slot.app)
        assertTrue(slot.loading)
        assertEquals("", slot.label)
    }

    @Test
    fun `a loaded snapshot missing the package is not installed, not loading`() {
        assertFalse(buildDockSlots(listOf(DockPin("com.gone", 0)), loaded(listOf(personal))).single().loading)
    }

    @Test
    fun `an empty pin list is an empty dock`() {
        assertTrue(buildDockSlots(emptyList(), loaded(listOf(personal))).isEmpty())
    }
}
