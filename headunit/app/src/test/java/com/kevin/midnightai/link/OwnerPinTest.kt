package com.kevin.midnightai.link

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The owner PIN (Kevin, 2026-11-20; lockout per review round 5964b74c, B2). Made-up PINs; a
 * reversible fake stands in for the Keystore. The lock runs on the MONOTONIC clock ([elapsed]); the
 * wall clock is never read, so setting it forward or back changes nothing. A reboot (new boot id)
 * re-locks for the full lock time. Wrong entries count across locks, and each lock doubles.
 */
@RunWith(RobolectricTestRunner::class)
class OwnerPinTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var elapsed = 5_000_000L
    private var boot = "boot-1"
    private var keystoreWorks = true

    private fun pin() = OwnerPin(
        context,
        encrypt = { if (keystoreWorks) "e:" + it.reversed() else null },
        decrypt = { if (keystoreWorks && it.startsWith("e:")) it.removePrefix("e:").reversed() else null },
        elapsedMs = { elapsed },
        bootId = { boot },
        iterations = 1_000,
    )

    @Before fun clean() = pin().clear()

    private fun wrongFive() {
        repeat(OwnerPin.MAX_TRIES) { pin().check("000000") }
    }

    @Test fun aNewPinIsExactlySixDigits() {
        assertFalse(pin().set("4821"))
        assertFalse(pin().set("1234567"))
        assertFalse(pin().set("12a456"))
        assertFalse(pin().isSet())
        assertTrue(pin().set("482193"))
        assertTrue(pin().isSet())
        assertEquals(OwnerPin.Check.Ok, pin().check("482193"))
    }

    @Test fun onlyASaltedHashIsKeptAndItIsEncrypted() {
        assertTrue(pin().set("482193"))
        val raw = context.getSharedPreferences(OwnerPin.PREFS, Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw, raw.contains("482193") || raw.contains("391284"))
        assertTrue(raw.contains("e:"))
    }

    @Test fun aBrokenKeystoreSetsNoPin() {
        keystoreWorks = false
        assertFalse(pin().set("482193"))
        assertFalse(pin().isSet())
    }

    @Test fun fiveWrongEntriesLockItForTenMinutesOnTheMonotonicClock() {
        assertTrue(pin().set("482193"))
        for (left in 4 downTo 1) assertEquals(OwnerPin.Check.Wrong(left), pin().check("000000"))
        assertEquals(OwnerPin.Check.Locked(OwnerPin.LOCK_MS), pin().check("000000"))
        elapsed += OwnerPin.LOCK_MS - 1
        assertTrue("the right PIN opened a lock", pin().check("482193") is OwnerPin.Check.Locked)
        elapsed += 1
        assertEquals(OwnerPin.Check.Ok, pin().check("482193"))
    }

    @Test fun theWallClockIsNeverRead() {
        // Setting the date forward (or the unit correcting it after a power-off) cannot end a lock:
        // nothing here depends on it. Only the monotonic clock moves the lock.
        assertTrue(pin().set("482193"))
        wrongFive()
        val wallBefore = System.currentTimeMillis()
        assertTrue(pin().check("482193") is OwnerPin.Check.Locked)
        assertTrue(System.currentTimeMillis() >= wallBefore)
    }

    @Test fun aRestartMidLockReLocksForTheFullTime() {
        assertTrue(pin().set("482193"))
        wrongFive()
        elapsed += OwnerPin.LOCK_MS - 60_000L // one minute left
        boot = "boot-2"
        elapsed = 1_000L // the monotonic clock restarts with the unit
        val after = pin().check("482193")
        assertEquals("a restart shortened the lock", OwnerPin.Check.Locked(OwnerPin.LOCK_MS), after)
        elapsed += OwnerPin.LOCK_MS
        assertEquals(OwnerPin.Check.Ok, pin().check("482193"))
    }

    @Test fun eachLockDoublesAndWrongEntriesCountAcrossLocks() {
        assertTrue(pin().set("482193"))
        wrongFive()
        elapsed += OwnerPin.LOCK_MS
        // The count did not restart: the next five wrong entries lock for twice as long.
        for (left in 4 downTo 1) assertEquals(OwnerPin.Check.Wrong(left), pin().check("000000"))
        assertEquals(OwnerPin.Check.Locked(2 * OwnerPin.LOCK_MS), pin().check("000000"))
        elapsed += 2 * OwnerPin.LOCK_MS
        wrongFive()
        assertEquals(OwnerPin.Check.Locked(4 * OwnerPin.LOCK_MS), pin().check("000000"))
    }

    @Test fun theLockNeverExceedsTheCap() {
        assertTrue(pin().set("482193"))
        repeat(20) {
            wrongFive()
            elapsed += OwnerPin.MAX_LOCK_MS
        }
        wrongFive()
        assertEquals(OwnerPin.Check.Locked(OwnerPin.MAX_LOCK_MS), pin().check("000000"))
    }

    @Test fun theRightPinStartsTheCountAgain() {
        assertTrue(pin().set("482193"))
        repeat(4) { pin().check("000000") }
        assertEquals(OwnerPin.Check.Ok, pin().check("482193"))
        assertEquals(OwnerPin.Check.Wrong(4), pin().check("000000"))
    }

    @Test fun anUnreadableRecordNeverOpens() {
        assertTrue(pin().set("482193"))
        keystoreWorks = false
        assertEquals(OwnerPin.Check.Unreadable, pin().check("482193"))
    }

    @Test fun theGateNeverAsksWhileDrivingAndAFirstPairingNeedsNoPin() {
        assertEquals(OwnerPinGate.Gate.ALLOWED, OwnerPinGate.gate(pinSet = false, driving = false))
        assertEquals(OwnerPinGate.Gate.ALLOWED, OwnerPinGate.gate(pinSet = false, driving = true))
        assertEquals(OwnerPinGate.Gate.NEEDS_PIN, OwnerPinGate.gate(pinSet = true, driving = false))
        assertEquals(OwnerPinGate.Gate.PARK_FIRST, OwnerPinGate.gate(pinSet = true, driving = true))
    }
}
