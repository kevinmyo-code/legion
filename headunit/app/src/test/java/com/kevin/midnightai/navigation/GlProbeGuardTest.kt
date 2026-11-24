package com.kevin.midnightai.navigation

import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The EGL probe's crash guard (review F2, 2026-11-23). A native fault inside a vendor driver's
 * `eglCreateContext` is not catchable, and the unit it would hit has no ADB, so the flag is what
 * stops a crash at app start from repeating on every start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class GlProbeGuardTest {

    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("nav_guard", Context.MODE_PRIVATE)
    private val guard get() = GlProbeGuard(prefs)

    @Test fun aFreshUnitHasNothingToRecoverFrom() {
        assertFalse(guard.crashedBefore())
    }

    /**
     * The flag is on disk WHILE the probe runs, which is the only moment a native fault can land,
     * and gone after. A commit, not an apply: an async write loses the race with the crash.
     */
    @Test fun theFlagIsArmedForTheProbeAndClearedAfterIt() {
        val seen = guard.guarded { prefs.getBoolean("gl_probing", false) }
        assertTrue("armed while probing", seen)
        assertFalse(guard.crashedBefore())
    }

    @Test fun theFlagIsClearedEvenWhenTheProbeThrows() {
        runCatching { guard.guarded<Unit> { error("boom") } }
        assertFalse(guard.crashedBefore())
    }

    /** A process that died inside the probe: the next start finds the flag and the map stays off. */
    @Test fun aProbeThatNeverCameBackIsNotRunAgainAndTheMapStaysOff() {
        prefs.edit().putBoolean("gl_probing", true).commit()
        assertTrue(guard.crashedBefore())
        // What the caller does with that answer: no probe, and no map.
        assertEquals(NavGles.Es3.NO, NavGles.decide(0x20000, es3Context = false))
        assertFalse(NavGles.decide(0x20000, es3Context = false).mapAllowed)
    }

    /** TRY MAPS AGAIN is the way back: one bad start never takes the map away for good. */
    @Test fun clearingLetsTheNextOpenProbeAgain() {
        prefs.edit().putBoolean("gl_probing", true).commit()
        guard.clear()
        assertFalse(guard.crashedBefore())
    }
}
