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

/** The owner PIN (Kevin, 2026-11-20). Made-up PINs; a reversible fake stands in for the Keystore. */
@RunWith(RobolectricTestRunner::class)
class OwnerPinTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var now = 1_800_000_000_000L
    private var keystoreWorks = true

    private fun pin() = OwnerPin(
        context,
        encrypt = { if (keystoreWorks) "e:" + it.reversed() else null },
        decrypt = { if (keystoreWorks && it.startsWith("e:")) it.removePrefix("e:").reversed() else null },
        nowMs = { now },
        iterations = 1_000,
    )

    @Before fun clean() = pin().clear()

    @Test fun onlyFourToSixDigitsAreAPin() {
        assertFalse(pin().set("123"))
        assertFalse(pin().set("1234567"))
        assertFalse(pin().set("12a4"))
        assertFalse(pin().isSet())
        assertTrue(pin().set("4821"))
        assertTrue(pin().isSet())
        assertEquals(OwnerPin.Check.Ok, pin().check("4821"))
    }

    @Test fun onlyASaltedHashIsKeptAndItIsEncrypted() {
        assertTrue(pin().set("482193"))
        val raw = context.getSharedPreferences(OwnerPin.PREFS, Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw, raw.contains("482193") || raw.contains("391284"))
        assertTrue(raw.contains("e:"))
    }

    @Test fun aBrokenKeystoreSetsNoPin() {
        keystoreWorks = false
        assertFalse(pin().set("4821"))
        assertFalse(pin().isSet())
    }

    @Test fun fiveWrongEntriesLockItForTenMinutes() {
        assertTrue(pin().set("4821"))
        for (left in 4 downTo 1) assertEquals(OwnerPin.Check.Wrong(left), pin().check("0000"))
        assertTrue(pin().check("0000") is OwnerPin.Check.Locked)
        // The right PIN does not open it while locked.
        now += OwnerPin.LOCK_MS - 1
        assertTrue(pin().check("4821") is OwnerPin.Check.Locked)
        now += 1
        assertEquals(OwnerPin.Check.Ok, pin().check("4821"))
    }

    @Test fun aClockThatJumpedBackNeverLocksLongerThanTenMinutes() {
        assertTrue(pin().set("4821"))
        repeat(5) { pin().check("0000") }
        now -= 6 * 3_600_000L // the clock came back hours earlier after a power-off
        assertTrue(pin().check("4821") is OwnerPin.Check.Locked)
        now += OwnerPin.LOCK_MS
        assertEquals(OwnerPin.Check.Ok, pin().check("4821"))
    }

    @Test fun anUnreadableRecordNeverOpens() {
        assertTrue(pin().set("4821"))
        keystoreWorks = false
        assertEquals(OwnerPin.Check.Unreadable, pin().check("4821"))
    }

    @Test fun theGateNeverAsksWhileDrivingAndAFirstPairingNeedsNoPin() {
        assertEquals(OwnerPinGate.Gate.ALLOWED, OwnerPinGate.gate(pinSet = false, driving = false))
        assertEquals(OwnerPinGate.Gate.ALLOWED, OwnerPinGate.gate(pinSet = false, driving = true))
        assertEquals(OwnerPinGate.Gate.NEEDS_PIN, OwnerPinGate.gate(pinSet = true, driving = false))
        assertEquals(OwnerPinGate.Gate.PARK_FIRST, OwnerPinGate.gate(pinSet = true, driving = true))
    }
}
