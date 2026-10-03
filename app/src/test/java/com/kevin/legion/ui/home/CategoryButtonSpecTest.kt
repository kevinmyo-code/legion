package com.kevin.legion.ui.home

import com.kevin.legion.ui.apps.DockPin
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.HomeCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home-launcher ticket 07: a category button states unset / not installed / paused in words. */
class CategoryButtonSpecTest {

    private val app = DrawerApp("Gmail", "com.google.android.gm", "gm.Main", isWork = false, profileKey = 0)
    private val work = DrawerApp("Outlook", "com.ms.outlook", "o.Main", isWork = true, profileKey = 10)

    private fun slot(a: DrawerApp?, paused: Boolean = false) =
        DockSlotUi(DockPin(a?.packageName ?: "com.gone", a?.profileKey ?: 0), a, a?.label ?: "com.gone", null, paused)

    @Test
    fun `no picks is unset, outlined, and says to tap to choose`() {
        val spec = categoryButtonSpec(CategoryUi(HomeCategory.BANK, emptyList()))
        assertTrue(spec.unset)
        assertEquals("Bank", spec.label)
        assertEquals("Banking, not set up. Tap to choose apps.", spec.description)
    }

    @Test
    fun `one working pick names the app it opens`() {
        val spec = categoryButtonSpec(CategoryUi(HomeCategory.MAIL, listOf(slot(app))))
        assertFalse(spec.unset)
        assertFalse(spec.dimmed)
        assertEquals("Email, opens Gmail", spec.description)
    }

    @Test
    fun `one pick that is gone reads Not installed and is dimmed`() {
        val spec = categoryButtonSpec(CategoryUi(HomeCategory.MAIL, listOf(slot(null))))
        assertEquals("Not installed", spec.label)
        assertTrue(spec.dimmed)
    }

    @Test
    fun `one pick in a paused work profile reads Paused`() {
        val spec = categoryButtonSpec(CategoryUi(HomeCategory.MAIL, listOf(slot(work, paused = true))))
        assertEquals("Paused", spec.label)
        assertTrue(spec.dimmed)
    }

    @Test
    fun `several picks show no count and a description that says it asks`() {
        val spec = categoryButtonSpec(CategoryUi(HomeCategory.MAIL, listOf(slot(app), slot(work))))
        assertEquals("Email, 2 apps, asks which to open", spec.description)
        assertFalse(spec.dimmed)
    }

    @Test
    fun `several picks none of which can open say Unavailable`() {
        val spec = categoryButtonSpec(CategoryUi(HomeCategory.MAIL, listOf(slot(null), slot(work, paused = true))))
        assertEquals("Unavailable", spec.label)
        assertTrue(spec.dimmed)
    }

    @Test
    fun `several picks with one still working are not dimmed`() {
        val spec = categoryButtonSpec(CategoryUi(HomeCategory.MAIL, listOf(slot(null), slot(app))))
        assertFalse(spec.dimmed)
        assertEquals("Mail", spec.label)
    }

    @Test
    fun `loading picks read as the category, never Not installed or Unavailable`() {
        val loading = buildDockSlots(listOf(DockPin("a", 0)), null)
        val one = categoryButtonSpec(CategoryUi(HomeCategory.MAIL, loading))
        assertEquals(HomeCategory.MAIL.short, one.label)
        assertFalse(one.dimmed)
        val many = categoryButtonSpec(
            CategoryUi(HomeCategory.MAIL, buildDockSlots(listOf(DockPin("a", 0), DockPin("b", 0)), null)),
        )
        assertEquals(HomeCategory.MAIL.short, many.label)
        assertFalse(many.dimmed)
    }
}
