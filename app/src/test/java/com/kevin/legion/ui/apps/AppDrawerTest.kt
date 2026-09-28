package com.kevin.legion.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDrawerTest {

    private fun app(label: String, pkg: String, work: Boolean = false) =
        DrawerApp(label, pkg, "$pkg.Main", work, if (work) 10 else 0)

    private val apps = listOf(
        app("WhatsApp", "com.whatsapp"),
        app("BofA", "com.infonow.bofa"),
        app("Outlook", "com.microsoft.office.outlook", work = true),
        app("Outlook", "com.microsoft.office.outlook"),
        app("authenticator", "com.azure.authenticator", work = true),
    )

    @Test
    fun `blank query lists everything, alphabetical and case-insensitive`() {
        val labels = filterDrawer(apps, "  ").map { it.label }
        assertEquals(listOf("authenticator", "BofA", "Outlook", "Outlook", "WhatsApp"), labels)
    }

    @Test
    fun `on a tie the personal app comes before the work one`() {
        val outlooks = filterDrawer(apps, "outlook")
        assertEquals(listOf(false, true), outlooks.map { it.isWork })
    }

    @Test
    fun `search matches the package name too`() {
        // "infonow" appears nowhere in the label BofA.
        assertEquals(listOf("BofA"), filterDrawer(apps, "infonow").map { it.label })
    }

    @Test
    fun `no match is an empty list, not everything`() {
        assertTrue(filterDrawer(apps, "zzzz").isEmpty())
    }
}
