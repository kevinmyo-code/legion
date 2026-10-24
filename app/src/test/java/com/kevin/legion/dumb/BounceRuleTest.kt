package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OWN = "com.kevin.legion"

class BounceRuleTest {
    private val launchers = setOf(
        "com.instagram.android",
        "com.android.chrome",
        "com.spotify.music",
        "com.android.settings",
    )
    private val hasLauncher: (String) -> Boolean = { it in launchers }
    // Blocklist model: only the feed apps are blocked; the rest is open (BlockModelTest owns the why).
    private val blocked = setOf("com.instagram.android")
    private val ctx = BounceContext(
        modeOn = true,
        ownPackage = OWN,
        isBlocked = { it in blocked },
        inCall = false,
        graceActive = false,
        allowedAfterChosenPerson = false,
    )

    private fun bounce(pkg: String?, c: BounceContext = ctx) = BounceRule.shouldBounce(pkg, c, hasLauncher)

    @Test
    fun `a blocked app with a launcher entry is bounced`() {
        assertTrue(bounce("com.instagram.android"))
    }

    @Test
    fun `an app that is not blocked is never bounced`() {
        assertFalse(bounce("com.android.chrome"))
        assertFalse(bounce("com.spotify.music"))
    }

    @Test
    fun `LEGION itself is never bounced`() {
        assertFalse(bounce(OWN))
    }

    @Test
    fun `a package with no launcher entry is never bounced`() {
        for (pkg in listOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.samsung.android.incallui",
        )) {
            assertFalse(pkg, bounce(pkg))
        }
    }

    @Test
    fun `nothing is bounced during a call`() {
        assertFalse(bounce("com.instagram.android", ctx.copy(inCall = true)))
    }

    @Test
    fun `nothing is bounced when the mode is off`() {
        assertFalse(bounce("com.instagram.android", ctx.copy(modeOn = false)))
    }

    @Test
    fun `a picker LEGION just opened is not bounced while the grace window is open`() {
        assertFalse(bounce("com.instagram.android", ctx.copy(graceActive = true)))
    }

    @Test
    fun `an app allowed after a chosen person's message is not bounced`() {
        assertFalse(bounce("com.instagram.android", ctx.copy(allowedAfterChosenPerson = true)))
    }

    @Test
    fun `a null or blank package is ignored`() {
        assertFalse(bounce(null))
        assertFalse(bounce(""))
        assertFalse(bounce("  "))
    }

    @Test
    fun `the launcher lookup is skipped when a cheaper check already decided`() {
        var lookups = 0
        BounceRule.shouldBounce("com.spotify.music", ctx) { lookups++; true } // not blocked
        BounceRule.shouldBounce(OWN, ctx) { lookups++; true }
        BounceRule.shouldBounce("com.instagram.android", ctx.copy(inCall = true)) { lookups++; true }
        assertEquals(0, lookups)
    }
}

class DumbBounceTest {
    private var now = 10_000L
    private var on = true
    private var inCall = false
    private val env = object : BounceEnvironment {
        override fun isBlocked(packageName: String) = packageName in setOf(
            "com.instagram.android", "com.reddit.frontpage", "com.google.android.contacts", "com.whatsapp",
        )
        override fun inCall() = inCall
        override fun hasLauncherEntry(packageName: String) = packageName != "com.android.systemui"
    }
    private val allowance = BounceAllowance { now }
    private val bounce = DumbBounce({ on }, OWN, env, allowance) { now }

    @Test
    fun `a hidden app bounces once and the burst of events after it is debounced`() {
        assertTrue(bounce.shouldBounce("com.instagram.android"))
        now += 500
        assertFalse(bounce.shouldBounce("com.instagram.android"))
        now += DumbBounce.DEBOUNCE_MS
        assertTrue(bounce.shouldBounce("com.instagram.android"))
    }

    @Test
    fun `two different hidden apps are debounced separately`() {
        assertTrue(bounce.shouldBounce("com.instagram.android"))
        assertTrue(bounce.shouldBounce("com.reddit.frontpage"))
    }

    @Test
    fun `off, in a call, open and no-launcher packages never bounce`() {
        assertFalse(bounce.shouldBounce("com.spotify.music"))
        assertFalse(bounce.shouldBounce("com.android.settings"))
        assertFalse("blocked but no launcher entry", bounce.shouldBounce("com.android.systemui"))
        inCall = true
        assertFalse(bounce.shouldBounce("com.instagram.android"))
        inCall = false
        on = false
        assertFalse(bounce.shouldBounce("com.instagram.android"))
    }

    @Test
    fun `the grace window lets a picker through and then lapses`() {
        allowance.armGrace(forMs = 1_000L)
        assertFalse(bounce.shouldBounce("com.google.android.contacts"))
        now += 1_001L
        assertTrue(bounce.shouldBounce("com.google.android.contacts"))
    }

    @Test
    fun `the chosen person allowance is off by default so the rulings are followed literally`() {
        assertFalse(BounceAllowance.ALLOW_APP_AFTER_CHOSEN_PERSON)
        allowance.allowPackage("com.whatsapp")
        assertTrue(allowance.packageAllowed("com.whatsapp"))
        assertTrue("allowed packages still bounce while the switch is off", bounce.shouldBounce("com.whatsapp"))
        now += BounceAllowance.PACKAGE_ALLOWANCE_MS + 1
        assertFalse(allowance.packageAllowed("com.whatsapp"))
    }

    @Test
    fun `the cache returns a value until it expires`() {
        var loads = 0
        var t = 0L
        val cache = TtlCache(1_000L, { t }) { ++loads }
        assertEquals(1, cache.get())
        t = 999L
        assertEquals(1, cache.get())
        t = 1_000L
        assertEquals(2, cache.get())
        assertEquals(2, loads)
    }
}
