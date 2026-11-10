package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 3 (2026-11-09): [GeminiLiveSession.carriesReadThroughPastTurn], the pure decision behind
 * `markToolResultReadThrough`. A NON_BLOCKING `ask_fleet` whose answer carried web text is spoken
 * `WHEN_IDLE`: sent while the turn that asked is still open, the answer is the NEXT turn, so the
 * read-through flag must survive that turn's turnComplete or `remember` there is unguarded.
 */
class GeminiLiveSessionWebReadCarryTest {

    @Test
    fun `an async result sent while its turn is still open carries into the answer turn`() {
        assertTrue(GeminiLiveSession.carriesReadThroughPastTurn(async = true, turnStillOpen = true))
    }

    @Test
    fun `an async result sent once the turn closed starts the answer turn, which the flag covers`() {
        assertFalse(GeminiLiveSession.carriesReadThroughPastTurn(async = true, turnStillOpen = false))
    }

    @Test
    fun `a blocking result is answered inside its own turn, so nothing carries`() {
        assertFalse(GeminiLiveSession.carriesReadThroughPastTurn(async = false, turnStillOpen = true))
        assertFalse(GeminiLiveSession.carriesReadThroughPastTurn(async = false, turnStillOpen = false))
    }
}
