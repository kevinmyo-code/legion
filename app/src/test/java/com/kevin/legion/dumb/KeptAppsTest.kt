package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matrix batch item 3: WhatsApp, the Maps setting and the clock are kept apps, resolved at run time. */
class KeptAppsTest {
    private class FakeResolver(
        var whatsapp: String? = null,
        var maps: String? = null,
        var clocks: Set<String> = emptySet(),
    ) : KeptAppResolver {
        override fun dialerPackage() = "dial.pkg"
        override fun smsPackage() = "sms.pkg"
        override fun cameraPackage() = null
        override fun musicPackage() = null
        override fun whatsappPackage() = whatsapp
        override fun googleMapsPackage() = maps
        override fun clockPackages() = clocks
    }

    @Test
    fun `WhatsApp is kept when installed and absent from the kept set when not`() {
        assertTrue("com.whatsapp" in FakeResolver(whatsapp = "com.whatsapp").keptPackages())
        assertFalse("com.whatsapp" in FakeResolver().keptPackages())
    }

    @Test
    fun `the WhatsApp row shows only when installed, right after Messages`() {
        val shown = DumbUiStates.visibleEntries(whatsappInstalled = true)
        assertEquals(KeptEntry.WHATSAPP, shown[shown.indexOf(KeptEntry.MESSAGES) + 1])
        assertFalse(KeptEntry.WHATSAPP in DumbUiStates.visibleEntries(whatsappInstalled = false))
        assertEquals(KeptEntry.entries.size - 1, DumbUiStates.visibleEntries(false).size)
    }

    @Test
    fun `the clock packages are whatever the resolver found, none hard-coded`() {
        val oem = "some.oem.clock"
        assertTrue(oem in FakeResolver(clocks = setOf(oem)).keptPackages())
        assertFalse(FakeResolver().keptPackages().any { "clock" in it })
    }

    @Test
    fun `Google Maps chosen and installed opens Google Maps`() {
        assertEquals(
            MapsRoute.Google(MapsRoute.GOOGLE_MAPS_PACKAGE),
            MapsRoute.decide(MapsChoice.GOOGLE_MAPS, MapsRoute.GOOGLE_MAPS_PACKAGE),
        )
    }

    @Test
    fun `Google Maps chosen but not installed opens LEGION navigation and says so in words`() {
        val route = MapsRoute.decide(MapsChoice.GOOGLE_MAPS, null)
        assertEquals(MapsRoute.LegionNav(MapsRoute.NOT_INSTALLED_NOTICE), route)
        assertTrue((route as MapsRoute.LegionNav).notice!!.contains("not installed"))
    }

    @Test
    fun `LEGION navigation chosen opens it with no notice even when Google Maps is installed`() {
        assertEquals(
            MapsRoute.LegionNav(null),
            MapsRoute.decide(MapsChoice.LEGION_NAV, MapsRoute.GOOGLE_MAPS_PACKAGE),
        )
    }

    @Test
    fun `the bounce reads the Maps setting live, not a set computed once`() {
        var now = 0L
        var choice = MapsChoice.GOOGLE_MAPS
        val resolver = FakeResolver(maps = MapsRoute.GOOGLE_MAPS_PACKAGE)
        val kept = LiveKeptPackages(resolver, { choice }, ttlMs = 60_000L, clock = { now })

        assertTrue(MapsRoute.GOOGLE_MAPS_PACKAGE in kept.get())
        // Same instant, inside the cache window: the setting flip must still show at once.
        choice = MapsChoice.LEGION_NAV
        assertFalse(MapsRoute.GOOGLE_MAPS_PACKAGE in kept.get())
        choice = MapsChoice.GOOGLE_MAPS
        assertTrue(MapsRoute.GOOGLE_MAPS_PACKAGE in kept.get())
    }

    @Test
    fun `Google Maps not installed is never added to the kept set`() {
        val kept = LiveKeptPackages(FakeResolver(maps = null), { MapsChoice.GOOGLE_MAPS }, 60_000L) { 0L }
        assertFalse(kept.get().any { "maps" in it })
    }

    @Test
    fun `a kept WhatsApp is never bounced but another hidden app still is`() {
        val ctx = BounceContext(
            modeOn = true,
            ownPackage = "com.kevin.legion",
            keptPackages = FakeResolver(whatsapp = "com.whatsapp").keptPackages(),
            systemPackages = emptySet(),
            inCall = false,
            graceActive = false,
            allowedAfterChosenPerson = false,
        )
        assertFalse(BounceRule.shouldBounce("com.whatsapp", ctx) { true })
        assertTrue(BounceRule.shouldBounce("com.instagram.android", ctx) { true })
    }
}
