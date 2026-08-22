package com.kevin.legion.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The microphone arbiter's priority logic, exhaustively.
 *
 * **Why exhaustively rather than a few cases.** The bug that justified this object was a single
 * boolean describing a state the app was no longer in: `duckNow()`'s `ducked` flag stayed true after
 * Android took audio focus away, so every later turn short-circuited and the assistant spoke
 * inaudibly for the rest of the session while subtitles kept rendering
 * (`.scratch/proactive-mode/issues/13-silent-after-focus-loss.md`).
 *
 * The fix for that class of bug is not "a better flag" - it is one object that can actually answer
 * *who holds the microphone*, which nothing could before. That answer is only worth anything if
 * every transition is pinned, so every claimant is tested against every holder below.
 *
 * Priority, settled by ticket 05 and not re-openable here:
 * `LIVE_TURN` > `RING_LISTENING` > `WAKE_WORD`.
 */
class MicArbiterTest {

    private val all = MicArbiter.Claimant.entries

    @Before
    fun setUp() = releaseAll()

    @After
    fun tearDown() = releaseAll()

    private fun releaseAll() = all.forEach { MicArbiter.release(it) }

    // ------------------------------------------------------------------ the free case

    @Test
    fun `anyone can take a free microphone`() {
        all.forEach { claimant ->
            releaseAll()
            assertTrue("$claimant should get a free mic", MicArbiter.request(claimant))
            assertEquals(claimant, MicArbiter.current())
        }
    }

    @Test
    fun `nobody holds it to begin with`() {
        assertNull(MicArbiter.current())
    }

    // ------------------------------------------------------- every claimant vs every holder

    /**
     * The exhaustive grid. Nine combinations, and the rule is one line: a request wins if it
     * outranks the holder, or IS the holder.
     */
    @Test
    fun `every claimant against every holder follows the settled priority`() {
        all.forEach { holder ->
            all.forEach { asker ->
                releaseAll()
                assertTrue(MicArbiter.request(holder))

                val expected = asker.ordinal <= holder.ordinal
                val actual = MicArbiter.request(asker)

                assertEquals(
                    "$asker requesting while $holder holds: expected granted=$expected",
                    expected, actual,
                )
                // Whoever legitimately holds it afterwards must be reflected in current().
                assertEquals(if (expected) asker else holder, MicArbiter.current())
            }
        }
    }

    @Test
    fun `a live turn preempts both of the others`() {
        MicArbiter.request(MicArbiter.Claimant.WAKE_WORD)
        assertTrue(MicArbiter.request(MicArbiter.Claimant.LIVE_TURN))
        assertEquals(MicArbiter.Claimant.LIVE_TURN, MicArbiter.current())

        releaseAll()
        MicArbiter.request(MicArbiter.Claimant.RING_LISTENING)
        assertTrue(MicArbiter.request(MicArbiter.Claimant.LIVE_TURN))
    }

    @Test
    fun `ring listening never interrupts a live turn`() {
        // Deliberate: the ring build already refuses to tear down a running conversation to
        // announce a call - taking the mic from someone mid-sentence to tell them the phone is
        // ringing, which they can already hear. Inverting that here would contradict it.
        MicArbiter.request(MicArbiter.Claimant.LIVE_TURN)
        assertFalse(MicArbiter.request(MicArbiter.Claimant.RING_LISTENING))
        assertEquals(MicArbiter.Claimant.LIVE_TURN, MicArbiter.current())
    }

    @Test
    fun `the wake word yields to everything`() {
        all.filter { it != MicArbiter.Claimant.WAKE_WORD }.forEach { higher ->
            releaseAll()
            MicArbiter.request(higher)
            assertFalse("WAKE_WORD must not take the mic from $higher",
                MicArbiter.request(MicArbiter.Claimant.WAKE_WORD))
        }
    }

    // ------------------------------------------------------------------ preemption callback

    @Test
    fun `the preempted holder is told, and the taker is not`() {
        var wakeWordLost = 0
        var liveTurnLost = 0
        MicArbiter.request(MicArbiter.Claimant.WAKE_WORD) { wakeWordLost++ }
        MicArbiter.request(MicArbiter.Claimant.LIVE_TURN) { liveTurnLost++ }

        assertEquals("the wake word lost the mic and must be told", 1, wakeWordLost)
        assertEquals("the taker must not be notified of its own success", 0, liveTurnLost)
    }

    @Test
    fun `re-requesting your own hold does not fire your own preemption`() {
        // Idempotent re-request is expected from defensive call sites; telling a claimant it lost
        // the mic to itself would be a lie, and one that would tear down its own capture.
        var lost = 0
        MicArbiter.request(MicArbiter.Claimant.LIVE_TURN) { lost++ }
        assertTrue(MicArbiter.request(MicArbiter.Claimant.LIVE_TURN) { lost++ })
        assertEquals(0, lost)
        assertEquals(MicArbiter.Claimant.LIVE_TURN, MicArbiter.current())
    }

    @Test
    fun `a refused request never disturbs the holder's listener`() {
        var lost = 0
        MicArbiter.request(MicArbiter.Claimant.LIVE_TURN) { lost++ }
        assertFalse(MicArbiter.request(MicArbiter.Claimant.WAKE_WORD))
        assertEquals("a losing request must not preempt anything", 0, lost)
    }

    // ------------------------------------------------------------------ release

    @Test
    fun `release frees the mic for a lower claimant`() {
        MicArbiter.request(MicArbiter.Claimant.LIVE_TURN)
        assertFalse(MicArbiter.request(MicArbiter.Claimant.WAKE_WORD))
        MicArbiter.release(MicArbiter.Claimant.LIVE_TURN)
        assertNull(MicArbiter.current())
        assertTrue(MicArbiter.request(MicArbiter.Claimant.WAKE_WORD))
    }

    @Test
    fun `releasing something you do not hold is a harmless no-op`() {
        // Teardown paths call this defensively without knowing whether they still held the mic -
        // a preemption already cleared them out. It must not steal the current holder's claim.
        MicArbiter.request(MicArbiter.Claimant.LIVE_TURN)
        MicArbiter.release(MicArbiter.Claimant.WAKE_WORD)
        assertEquals(MicArbiter.Claimant.LIVE_TURN, MicArbiter.current())
    }

    @Test
    fun `a preempted claimant releasing later does not evict its successor`() {
        // The exact stale-state shape this whole object exists to prevent: WAKE_WORD is preempted,
        // then its own teardown calls release(). If that cleared the holder, LIVE_TURN would
        // silently lose the mic it legitimately took.
        MicArbiter.request(MicArbiter.Claimant.WAKE_WORD)
        MicArbiter.request(MicArbiter.Claimant.LIVE_TURN)
        MicArbiter.release(MicArbiter.Claimant.WAKE_WORD)
        assertEquals(MicArbiter.Claimant.LIVE_TURN, MicArbiter.current())
    }

    @Test
    fun `priority order is the settled one, in declaration order`() {
        // ordinal doubles as priority, so a reorder silently changes behaviour everywhere.
        assertEquals(
            listOf(
                MicArbiter.Claimant.LIVE_TURN,
                MicArbiter.Claimant.RING_LISTENING,
                MicArbiter.Claimant.WAKE_WORD,
            ),
            all,
        )
    }
}
