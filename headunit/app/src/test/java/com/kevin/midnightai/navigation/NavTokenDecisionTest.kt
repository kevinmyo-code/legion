package com.kevin.midnightai.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review C-1: a Keystore read that fails for a moment must not end a running trip or tear the
 * navigator down. Only a real absence (Forget) or a different token drops it.
 */
class NavTokenDecisionTest {
    @Test fun aFailedReadKeepsTheTokenAndTheNavigator() =
        assertEquals(NavTokenDecision.KEEP, NavTokenDecision.of(before = "pk.a", read = TokenRead.Unreadable))

    @Test fun aRealAbsenceDrops() =
        assertEquals(NavTokenDecision.DROP, NavTokenDecision.of(before = "pk.a", read = TokenRead.Absent))

    @Test fun aDifferentTokenDrops() =
        assertEquals(NavTokenDecision.DROP, NavTokenDecision.of(before = "pk.a", read = TokenRead.Present("pk.b")))

    @Test fun theSameTokenOrAFirstTokenNeedsNoDrop() {
        assertEquals(NavTokenDecision.SET, NavTokenDecision.of(before = "pk.a", read = TokenRead.Present("pk.a")))
        assertEquals(NavTokenDecision.SET, NavTokenDecision.of(before = null, read = TokenRead.Present("pk.a")))
        assertEquals(NavTokenDecision.KEEP, NavTokenDecision.of(before = null, read = TokenRead.Unreadable))
    }
}
