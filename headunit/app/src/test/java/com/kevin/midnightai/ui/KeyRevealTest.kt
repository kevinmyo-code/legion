package com.kevin.midnightai.ui

import com.kevin.midnightai.ui.KeyReveal.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Review L-c: with an owner PIN set, SHOW on the typed Gemini key asks for the PIN first, never while driving. */
class KeyRevealTest {
    @Test fun withAPinSetShowAsksForThePin() {
        assertEquals(Action.ASK_PIN, KeyReveal.onShow(revealed = false, pinUnlocked = false, pinSet = true, driving = false))
        assertEquals(Action.PARK_FIRST, KeyReveal.onShow(revealed = false, pinUnlocked = false, pinSet = true, driving = true))
    }

    @Test fun noPinOrAnUnlockedPinShowsAndHides() {
        assertEquals(Action.REVEAL, KeyReveal.onShow(revealed = false, pinUnlocked = false, pinSet = false, driving = false))
        assertEquals(Action.REVEAL, KeyReveal.onShow(revealed = false, pinUnlocked = true, pinSet = true, driving = false))
        assertEquals(Action.HIDE, KeyReveal.onShow(revealed = true, pinUnlocked = true, pinSet = true, driving = false))
    }
}
