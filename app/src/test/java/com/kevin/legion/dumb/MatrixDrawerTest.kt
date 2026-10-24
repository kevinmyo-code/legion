package com.kevin.legion.dumb

import com.kevin.legion.ui.apps.DrawerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The All apps drawer's filter (matrix-open ticket 02): blocked apps are never listed. */
class MatrixDrawerTest {
    private fun app(label: String, pkg: String, work: Boolean = false) = DrawerApp(label, pkg, "$pkg.Main", work, 0)

    private val apps = listOf(
        app("Instagram", "com.instagram.android"),
        app("Bank", "com.bank.app"),
        app("chrome", "com.android.chrome"),
        app("Discord", "com.discord"),
        app("Bank", "com.bank.app", work = true),
    )
    private val blocked = setOf("com.instagram.android", "com.discord")

    @Test
    fun `a blocked app is never listed and the rest are alphabetical ignoring case`() {
        val shown = MatrixDrawer.listed(apps) { it in blocked }
        assertEquals(listOf("Bank", "Bank", "chrome"), shown.map { it.label })
        assertFalse(shown.any { it.packageName in blocked })
    }

    @Test
    fun `a personal app comes before its work twin`() {
        val shown = MatrixDrawer.listed(apps) { it in blocked }
        assertFalse(shown[0].isWork)
        assertTrue(shown[1].isWork)
    }

    @Test
    fun `every blocked app is hidden when everything is blocked`() {
        assertTrue(MatrixDrawer.listed(apps) { true }.isEmpty())
    }

    @Test
    fun `the blocked count counts packages once, not work twins`() {
        assertEquals(2, MatrixDrawer.blockedCount(apps) { it in blocked })
        assertEquals(1, MatrixDrawer.blockedCount(apps) { it == "com.bank.app" })
    }

    @Test
    fun `the footer says how many are blocked and where to change it`() {
        assertEquals("2 apps blocked. Change in Matrix settings.", MatrixDrawer.footer(2))
        assertEquals("1 app blocked. Change in Matrix settings.", MatrixDrawer.footer(1))
        assertEquals("No apps blocked. Change in Matrix settings.", MatrixDrawer.footer(0))
    }

    @Test
    fun `All apps sits right after Books and opens inside LEGION`() {
        val entries = KeptEntry.entries
        assertEquals(KeptEntry.ALL_APPS, entries[entries.indexOf(KeptEntry.BOOKS) + 1])
        assertFalse(KeptEntry.ALL_APPS.isExternal)
        assertEquals("All apps", KeptEntry.ALL_APPS.label)
    }
}
