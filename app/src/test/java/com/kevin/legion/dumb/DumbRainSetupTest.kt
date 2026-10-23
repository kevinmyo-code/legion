package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumbRainSetupTest {
    private fun rows(set: Boolean) = DumbSetup.rows(
        DumbGrantFacts(
            notificationAccess = true,
            dndAccess = true,
            callScreeningRole = true,
            rainWallpaperSet = set,
        ),
    )

    @Test
    fun `the wallpaper row opens the live wallpaper preview and warns about Samsung's lock screen`() {
        val row = rows(false).single { it.id == DumbGrantRow.Id.RAIN_WALLPAPER }
        assertEquals(GrantAction.PickWallpaper, row.action)
        assertTrue(row.state.contains("Samsung"))
        assertTrue(row.state.contains("lock screen"))
        assertFalse("must not claim the lock screen works", row.state.contains("works on the lock"))
        assertTrue(row.state.startsWith("Not set."))
    }

    @Test
    fun `the wallpaper row says when it is set on the home screen`() {
        val row = rows(true).single { it.id == DumbGrantRow.Id.RAIN_WALLPAPER }
        assertTrue(row.state.startsWith("Set as the home wallpaper."))
    }

    @Test
    fun `the screensaver row opens screen saver settings and turns nothing on by itself`() {
        val row = rows(false).single { it.id == DumbGrantRow.Id.RAIN_SCREENSAVER }
        assertEquals(GrantAction.OpenSettings("android.settings.DREAM_SETTINGS"), row.action)
        assertTrue(row.state.contains("does not turn it on for you"))
    }

    @Test
    fun `the optional looks never count as a missing grant`() {
        assertNull(DumbSetup.notActive(rows(false)))
    }
}
