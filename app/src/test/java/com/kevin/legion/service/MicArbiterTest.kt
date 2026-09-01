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
 *
 * **Extended by voice-notes ticket 01** (`.scratch/voice-notes/issues/01-the-recorder-and-the-mic.md`)
 * to insert `VOICE_NOTE` between `RING_LISTENING` and `WAKE_WORD`, with one named exception to
 * plain ordinal ranking: `LIVE_TURN` and `VOICE_NOTE` never preempt each other. See
 * [MicArbiter.outranks]'s own doc comment for why that pair cannot be expressed by reordering the
 * enum, and this file's own "the one asymmetric pair" section below for the tests that pin it.
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
     * The exhaustive grid: every claimant against every possible holder. Sixteen combinations
     * now that [MicArbiter.Claimant.VOICE_NOTE] exists. The expectation table is written out by
     * hand here rather than derived from `Claimant.ordinal` - the whole point of this grid is to
     * pin the LIVE_TURN/VOICE_NOTE exception independently of [MicArbiter]'s own `outranks`
     * function, so a bug in that function cannot also be baked into the test that is supposed to
     * catch it.
     */
    @Test
    fun `every claimant against every holder follows the settled priority`() {
        val liveTurn = MicArbiter.Claimant.LIVE_TURN
        val ringListening = MicArbiter.Claimant.RING_LISTENING
        val voiceNote = MicArbiter.Claimant.VOICE_NOTE
        val wakeWord = MicArbiter.Claimant.WAKE_WORD

        all.forEach { holder ->
            all.forEach { asker ->
                releaseAll()
                assertTrue(MicArbiter.request(holder))

                val expected = when {
                    asker == holder -> true
                    // The one pair that is not a plain ordinal comparison - ticket 01: "Yields to
                    // LIVE_TURN? No", and the settled ticket-05 invariant "nothing preempts
                    // LIVE_TURN" applied in the other direction.
                    setOf(asker, holder) == setOf(liveTurn, voiceNote) -> false
                    else -> asker.ordinal <= holder.ordinal
                }
                val actual = MicArbiter.request(asker)

                assertEquals(
                    "$asker requesting while $holder holds: expected granted=$expected",
                    expected, actual,
                )
                // Whoever legitimately holds it afterwards must be reflected in current().
                assertEquals(if (expected) asker else holder, MicArbiter.current())
            }
        }

        // Named individually too, so a failure here reads as "the VOICE_NOTE rule broke" rather
        // than requiring a reader to decode the table above.
        releaseAll()
        MicArbiter.request(voiceNote)
        assertFalse("a recording in progress must refuse an incoming LIVE_TURN request",
            MicArbiter.request(liveTurn))

        releaseAll()
        MicArbiter.request(liveTurn)
        assertFalse("nothing preempts a sitting LIVE_TURN, VOICE_NOTE included",
            MicArbiter.request(voiceNote))

        releaseAll()
        MicArbiter.request(wakeWord)
        assertTrue("VOICE_NOTE preempts WAKE_WORD - ticket 01: \"Preempts WAKE_WORD? Yes\"",
            MicArbiter.request(voiceNote))

        releaseAll()
        MicArbiter.request(voiceNote)
        assertTrue("a call arriving stops the recording - RING_LISTENING preempts VOICE_NOTE",
            MicArbiter.request(ringListening))
    }

    @Test
    fun `a live turn preempts ring listening and the wake word, but not a voice note`() {
        // Renamed from "...preempts both of the others" now that VOICE_NOTE exists as a third
        // "other" and is the one deliberate exception - see the exhaustive grid test above for
        // that case pinned on its own.
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
        // ordinal doubles as priority for every pair except LIVE_TURN/VOICE_NOTE (see MicArbiter's
        // own `outranks` doc comment), so a reorder still silently changes behaviour everywhere
        // else.
        assertEquals(
            listOf(
                MicArbiter.Claimant.LIVE_TURN,
                MicArbiter.Claimant.RING_LISTENING,
                MicArbiter.Claimant.VOICE_NOTE,
                MicArbiter.Claimant.WAKE_WORD,
            ),
            all,
        )
    }
}
