package com.kevin.legion.ui.apps

import com.kevin.legion.ui.home.buildDockSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryPicksTest {

    private val gmail = DockPin("com.google.android.gm", 0)
    private val outlookWork = DockPin("com.microsoft.office.outlook", 10)
    private val outlookPersonal = DockPin("com.microsoft.office.outlook", 0)

    @Test
    fun `nothing picked opens the chooser`() {
        assertEquals(CategoryTap.Choose, CategoryPicks.tap(emptyList()))
    }

    @Test
    fun `one pick launches straight away`() {
        assertEquals(CategoryTap.Launch(gmail), CategoryPicks.tap(listOf(gmail)))
    }

    @Test
    fun `several picks ask which`() {
        assertEquals(CategoryTap.Ask(listOf(gmail, outlookWork)), CategoryPicks.tap(listOf(gmail, outlookWork)))
    }

    @Test
    fun `toggle ticks in pick order and unticks again`() {
        val one = CategoryPicks.toggle(emptyList(), gmail)
        val two = CategoryPicks.toggle(one, outlookWork)
        assertEquals(listOf(gmail, outlookWork), two)
        assertEquals(listOf(outlookWork), CategoryPicks.toggle(two, gmail))
        assertTrue(CategoryPicks.toggle(listOf(gmail), gmail).isEmpty())
    }

    @Test
    fun `a work twin is a different pick from its personal app`() {
        val both = CategoryPicks.toggle(listOf(outlookPersonal), outlookWork)
        assertEquals(2, both.size)
    }

    @Test
    fun `format and parse round-trip, and garbage reads as nothing`() {
        val picks = listOf(gmail, outlookWork)
        assertEquals(picks, parseDockPins(formatDockPins(picks)))
        assertTrue(parseDockPins("").isEmpty())
        assertTrue(parseDockPins("nonsense,:3,pkg:x").isEmpty())
    }

    @Test
    fun `categories have distinct storage keys`() {
        val ids = HomeCategory.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(5, ids.size)
    }

    @Test
    fun `an uninstalled pick stays listed with no app`() {
        val installed = DrawerApp("Gmail", gmail.packageName, "gm.Main", false, 0)
        val loaded = Loaded(listOf(installed), emptyMap(), emptyMap(), null, false)
        val slots = buildDockSlots(listOf(gmail, outlookWork), loaded)
        assertEquals(2, slots.size)
        assertEquals(installed, slots[0].app)
        assertNull(slots[1].app)
    }
}
