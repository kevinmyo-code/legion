package com.kevin.legion.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned shortcuts with LEGION as the home app (2026-11-18: Chrome hid "Add to Home screen" and
 * "Install app" because the default launcher could not pin). Made-up packages and labels only.
 */
class PinnedShortcutsTest {

    private fun shortcut(id: String, label: String, pkg: String = "example.browser", serial: Int = 0) =
        PinnedShortcuts.asDrawerApp(pkg, id, label, isWork = false, profileKey = serial)

    private class FakeRequest(
        override val isShortcut: Boolean = true,
        override val isValid: Boolean = true,
        override val label: String = "Weather board",
        override val fromPackage: String = "example.browser",
        private val accepts: Boolean = true,
    ) : PinRequest {
        var accepted = 0
        override fun accept(): Boolean {
            accepted++
            return accepts
        }
    }

    @Test
    fun `a shortcut reads like an app, keyed apart from the app it belongs to`() {
        val s = shortcut("web-1", "Weather board")
        assertEquals("web-1", s.shortcutId)
        assertEquals("Weather board", s.label)
        assertEquals("example.browser", s.packageName)
        // Never the same row, pin or icon key as the browser itself.
        val browser = DrawerApp("Browser", "example.browser", "example.browser.Main", false, 0)
        assertFalse(s.className == browser.className)
        assertNull(browser.shortcutId)
    }

    @Test
    fun `a shortcut carries where it came from, or says it is a shortcut`() {
        val fromBrowser = PinnedShortcuts.asDrawerApp("example.browser", "w", "Bank login", false, 0, origin = "Browser")
        assertEquals("Browser", fromBrowser.origin)
        assertEquals("shortcut", PinnedShortcuts.asDrawerApp("example.x", "x", "X", false, 0, origin = null).origin)
        // An app has no origin line.
        assertNull(DrawerApp("Bank", "example.bank", "example.bank.Main", false, 0).origin)
    }

    @Test
    fun `removing one shortcut keeps the package's others pinned (pinShortcuts replaces the set)`() {
        val a = shortcut("a", "Alpha")
        val b = shortcut("b", "Beta")
        val other = shortcut("c", "Gamma", pkg = "example.other")
        val work = shortcut("d", "Delta", serial = 10)
        assertEquals(listOf("b"), PinnedShortcuts.remainingIds(listOf(a, b, other, work), remove = a))
        assertEquals(emptyList<String>(), PinnedShortcuts.remainingIds(listOf(a), remove = a))
    }

    @Test
    fun `the home row is the pinned apps in their order, then shortcuts, up to the cap`() {
        assertEquals(listOf("a1", "a2", "s1"), PinnedShortcuts.homeRow(listOf("a1", "a2"), listOf("s1"), max = 6))
        assertEquals(
            listOf("a1", "a2", "a3", "a4", "a5", "s1"),
            PinnedShortcuts.homeRow(listOf("a1", "a2", "a3", "a4", "a5"), listOf("s1", "s2"), max = 6),
        )
    }

    @Test
    fun `All apps files a shortcut under its own letter beside the apps`() {
        val apps = listOf(DrawerApp("Wallet", "example.wallet", "example.wallet.Main", false, 0))
        val folders = letterFolders(apps + shortcut("w", "Weather board"))
        assertEquals(listOf("W"), folders.map { it.letter })
        assertEquals(listOf("Wallet", "Weather board"), folders.single().apps.map { it.label })
    }

    @Test
    fun `home-row slots for shortcuts are by label and never share the browser's pin`() {
        val loaded = Loaded(
            apps = emptyList(),
            icons = emptyMap(),
            handles = emptyMap(),
            workProfile = null,
            workPaused = false,
            shortcuts = listOf(shortcut("z", "Zebra notes"), shortcut("a", "Atlas board")),
        )
        val slots = com.kevin.legion.ui.home.shortcutSlots(loaded)
        assertEquals(listOf("Atlas board", "Zebra notes"), slots.map { it.label })
        assertFalse(slots.any { it.pin == DockPin("example.browser", 0) })
        assertEquals(2, slots.map { it.pin }.toSet().size)
        assertTrue(com.kevin.legion.ui.home.shortcutSlots(null).isEmpty())
    }

    @Test
    fun `only a valid shortcut request is offered, and Add accepts it once`() {
        assertTrue(PinConfirm.offer(FakeRequest()))
        assertFalse(PinConfirm.offer(FakeRequest(isShortcut = false)))
        assertFalse(PinConfirm.offer(FakeRequest(isValid = false)))
        val request = FakeRequest()
        assertEquals(PinConfirm.Outcome.ADDED, PinConfirm.add(request))
        assertEquals(1, request.accepted)
    }

    @Test
    fun `a request that went stale or was refused says so and adds nothing`() {
        val stale = FakeRequest(isValid = false)
        assertEquals(PinConfirm.Outcome.GONE, PinConfirm.add(stale))
        assertEquals(0, stale.accepted)
        assertEquals(PinConfirm.Outcome.REFUSED, PinConfirm.add(FakeRequest(accepts = false)))
    }
}
