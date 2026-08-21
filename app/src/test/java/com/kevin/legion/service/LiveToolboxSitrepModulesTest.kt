package com.kevin.legion.service

import com.kevin.legion.sitrep.SitrepModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-logic coverage for `get_sitrep`'s `modules` argument parsing
 * ([LiveToolbox.parseSitrepModules]) - ticket 22 part C/verification ("`get_sitrep` covered where
 * it is pure ... module filtering"). Robolectric only because [LiveToolbox] is the same object
 * every other `service` test in this package already loads under it - see
 * [LiveToolboxDeclarationSetTest]'s own doc comment for why touching that object at all needs the
 * runner, even for a call that never opens a [android.content.Context].
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxSitrepModulesTest {

    @Test
    fun `blank or omitted argument means every enabled module - the null sentinel`() {
        assertNull(LiveToolbox.parseSitrepModules(null))
        assertNull(LiveToolbox.parseSitrepModules(""))
        assertNull(LiveToolbox.parseSitrepModules("   "))
    }

    @Test
    fun `parses a comma-separated subset, case-insensitively and trimmed`() {
        val modules = LiveToolbox.parseSitrepModules("Calendar, WEATHER ,fleet")
        assertEquals(setOf(SitrepModule.CALENDAR, SitrepModule.WEATHER, SitrepModule.FLEET), modules)
    }

    @Test
    fun `an unrecognised token is dropped, never fails the whole call`() {
        val modules = LiveToolbox.parseSitrepModules("calendar,not_a_real_module")
        assertEquals(setOf(SitrepModule.CALENDAR), modules)
    }

    @Test
    fun `a single module parses to a set of one`() {
        assertEquals(setOf(SitrepModule.NEWS), LiveToolbox.parseSitrepModules("news"))
    }
}
