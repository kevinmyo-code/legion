package com.kevin.midnightai.navigation

import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // probeEs3Guarded: the one way the native probe may be reached (review A1, 2026-11-24). These go
    // through that function, not through GlProbeGuard, because the holes it closes were in the
    // CALLER: a second unguarded run, and a run on a unit whose answer is thrown away.

    /** A fake probe, never the real EGL one: counts its runs and whether the flag was armed. */
    private class FakeProbe(private val answer: Boolean?, private val prefs: android.content.SharedPreferences) {
        var runs = 0
            private set
        var armedWhenRun = false
            private set

        fun probe(): Boolean? {
            runs++
            if (prefs.getBoolean("gl_probing", false)) armedWhenRun = true
            return answer
        }
    }

    /**
     * The A1 regression. The probe answers null on every EGL early return (no display, no
     * initialise, no config), and the old `guarded { probe() } ?: probe()` read that null as "no
     * guard" and ran the native call a SECOND time, unguarded. Once, inside the guard, is the rule.
     */
    @Test fun aProbeThatAnswersNullRunsOnceAndOnlyInsideTheGuard() {
        val fake = FakeProbe(null, prefs)
        val answer = probeEs3Guarded(0x20000, guard, fake::probe)

        assertEquals(1, fake.runs)
        assertTrue("armed while probing", fake.armedWhenRun)
        assertNull(answer)
        assertFalse("flag cleared after", guard.crashedBefore())
        // What the driver gets: the map off, in words, not a retry of the call that did not come back.
        assertEquals(NavGles.Es3.UNKNOWN, NavGles.decide(0x20000, answer))
    }

    /** A real no is one guarded run too, and it is the answer that keeps the map off for good. */
    @Test fun aProbeThatAnswersNoRunsOnceInsideTheGuard() {
        val fake = FakeProbe(false, prefs)
        assertEquals(false, probeEs3Guarded(0x20000, guard, fake::probe))
        assertEquals(1, fake.runs)
        assertTrue(fake.armedWhenRun)
        assertEquals(NavGles.Es3.NO, NavGles.decide(0x20000, false))
    }

    /**
     * A unit declaring ES 3 is never probed. `decide` answers DECLARED off the declaration alone, so
     * the probe's answer was discarded there - and a native fault would have armed a flag that only
     * the `declared < GLES_3` branch ever reads, leaving that unit no loop protection at all.
     */
    @Test fun aUnitThatDeclaresEs3IsNeverProbed() {
        val fake = FakeProbe(true, prefs)
        assertNull(probeEs3Guarded(0x30000, guard, fake::probe))
        assertEquals(0, fake.runs)
        assertEquals(NavGles.Es3.DECLARED, NavGles.decide(0x30000, null))
    }

    /** No guard (before init) means no probe: unguarded native work is what F2 exists to stop. */
    @Test fun withNoGuardThereIsNoProbeAtAll() {
        val fake = FakeProbe(true, prefs)
        assertNull(probeEs3Guarded(0x20000, guard = null, probe = fake::probe))
        assertEquals(0, fake.runs)
    }
}
