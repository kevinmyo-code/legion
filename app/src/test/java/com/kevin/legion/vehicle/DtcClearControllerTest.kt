package com.kevin.legion.vehicle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DtcClearController.clear]'s five-outcome transaction plus the `confirmed=false` gate, over a
 * fake transport (`.scratch/hands-and-senses/issues/01-clear-dtc.md`'s verification section).
 *
 * **Why the fake sits at the raw-response seam, not [ObdTransport]'s literal
 * inputStream/outputStream shape.** [DtcClearController.clear] is injected with three suspend
 * functions returning RAW strings/maps - the exact values [ObdBluetoothManager.getDtcCodesRaw],
 * [ObdBluetoothManager.getFreezeFrame], and [ObdBluetoothManager.clearDtcCodes] hand back once
 * `Elm327Io` has already turned bytes into text. The five-outcome logic under test here is entirely
 * about INTERPRETING that text via [ObdResponseParser] - REFUSED off [ObdResponseParser
 * .isFailureResponse], the code set off [ObdResponseParser.dtcCodes] - not about the socket
 * underneath it, so [FakeDtcTransport] plays a real transport's role at the layer this class
 * actually depends on, the same way [ObdTransport] itself is a byte-stream seam one level further
 * down that [ObdBluetoothManager] alone talks to.
 */
class DtcClearControllerTest {

    // Mode 03 raw response shapes, matching ObdResponseParser.dtcCodes' own vocabulary
    // (no dedicated ObdResponseParserTest exists as of this ticket - the byte decoding itself is
    // exercised implicitly below via the "decodes to P0133, P0420" assertions, not pinned separately).
    private val NO_CODES = "43 00\r\r"
    private val TWO_CODES = "43 01 33 04 20\r\r" // decodes to P0133, P0420
    private val QUIET_LINK = "NO DATA\r\r"

    /**
     * Stands in for a real transport at the seam [DtcClearController.clear] depends on - see this
     * class' own doc comment. [codeReads] is consumed IN ORDER, one raw response per
     * `readCodesRaw()` call: a full transaction reads codes up to three times (the prompt read,
     * D4.2's fresh pre-send re-read, and the post-send re-read), so each test scripts exactly as
     * many responses as its scenario needs.
     */
    private class FakeDtcTransport(
        codeReads: List<String>,
        private val clearAck: String = "44\r\r",
        private val freezeFrame: Map<String, Double> = emptyMap(),
    ) {
        private val remaining = codeReads.toMutableList()
        var clearSent = false
            private set

        suspend fun readCodesRaw(): String {
            check(remaining.isNotEmpty()) { "test scripted fewer code reads than the transaction attempted" }
            return remaining.removeAt(0)
        }

        suspend fun readFreezeFrame(): Map<String, Double> = freezeFrame

        suspend fun sendClearRaw(): String {
            clearSent = true
            return clearAck
        }
    }

    private fun run(
        confirmed: Boolean,
        codeReads: List<String>,
        engineRunning: Boolean = false,
        clearAck: String = "44\r\r",
    ): Pair<DtcClearController.ClearResult, FakeDtcTransport> {
        val fake = FakeDtcTransport(codeReads, clearAck)
        val result = runBlocking {
            DtcClearController.clear(
                confirmed = confirmed,
                engineRunning = engineRunning,
                readCodesRaw = { fake.readCodesRaw() },
                readFreezeFrame = { fake.readFreezeFrame() },
                sendClearRaw = { fake.sendClearRaw() },
            )
        }
        return result to fake
    }

    // ------------------------------------------------------------- the confirmed=false gate

    @Test
    fun `confirmed=false with real codes asks and never sends`() {
        val (result, fake) = run(confirmed = false, codeReads = listOf(TWO_CODES))

        assertNull("the confirm-prompt turn is not yet one of the five ClearOutcome states", result.outcome)
        assertEquals(listOf("P0133", "P0420"), result.codesBefore)
        assertNull(result.codesAfter)
        assertTrue("the prompt names the actual stored codes", result.message.contains("P0133"))
        assertTrue(result.message.contains("P0420"))
        assertTrue("the prompt ends by asking", result.message.endsWith("Do you want me to clear?"))
        assertFalse("confirmed=false must NEVER send Mode 04 (D4.1)", fake.clearSent)
    }

    @Test
    fun `confirmed=false adds the engine-running clause when RPM is over zero`() {
        val (result, _) = run(confirmed = false, codeReads = listOf(TWO_CODES), engineRunning = true)
        assertTrue(result.message.contains("Engine is running"))
    }

    @Test
    fun `confirmed=false omits the engine clause when RPM is zero`() {
        val (result, _) = run(confirmed = false, codeReads = listOf(TWO_CODES), engineRunning = false)
        assertFalse(result.message.contains("Engine is running"))
    }

    // ------------------------------------------------------------------- the five outcomes

    @Test
    fun `NOTHING_TO_CLEAR when the snapshot has zero codes, never sends`() {
        val (result, fake) = run(confirmed = false, codeReads = listOf(NO_CODES))

        assertEquals(DtcClearController.ClearOutcome.NOTHING_TO_CLEAR, result.outcome)
        assertTrue(result.codesBefore.isEmpty())
        assertNull(result.codesAfter)
        assertFalse("NOTHING_TO_CLEAR must never send Mode 04 (D2: refuse early)", fake.clearSent)
    }

    @Test
    fun `NOTHING_TO_CLEAR also short-circuits a confirmed=true call - never asked, never sends`() {
        // D2's own text: sending Mode 04 against a car with nothing stored resets readiness
        // monitors for zero benefit. A stray confirmed=true (e.g. a race) must not send either.
        val (result, fake) = run(confirmed = true, codeReads = listOf(NO_CODES))
        assertEquals(DtcClearController.ClearOutcome.NOTHING_TO_CLEAR, result.outcome)
        assertFalse(fake.clearSent)
    }

    @Test
    fun `REFUSED when the link is quiet before ever sending`() {
        val (result, fake) = run(confirmed = false, codeReads = listOf(QUIET_LINK))

        assertEquals(DtcClearController.ClearOutcome.REFUSED, result.outcome)
        assertTrue(result.codesBefore.isEmpty())
        assertNull(result.codesAfter)
        assertEquals("The car is not answering. I have not sent anything.", result.message)
        assertFalse(fake.clearSent)
    }

    @Test
    fun `REFUSED on the fresh pre-send re-read too, even after a real confirm`() {
        // D4.2: call 2 re-reads FRESH immediately before sending. A link that answered at the
        // prompt but goes quiet by confirm time must still refuse, not fall back on the stale read.
        val (result, fake) = run(confirmed = true, codeReads = listOf(TWO_CODES, QUIET_LINK))
        assertEquals(DtcClearController.ClearOutcome.REFUSED, result.outcome)
        assertFalse(fake.clearSent)
    }

    @Test
    fun `CLEARED when the post-send re-read comes back empty`() {
        val (result, fake) = run(confirmed = true, codeReads = listOf(TWO_CODES, TWO_CODES, NO_CODES))

        assertEquals(DtcClearController.ClearOutcome.CLEARED, result.outcome)
        assertEquals(listOf("P0133", "P0420"), result.codesBefore)
        assertEquals(emptyList<String>(), result.codesAfter)
        assertTrue(fake.clearSent)
        assertTrue(
            "D1's anti-overclaim sentence is not optional",
            result.message.contains("not that the fault is gone"),
        )
    }

    @Test
    fun `RETURNED when the post-send re-read still shows a code`() {
        val (result, fake) = run(confirmed = true, codeReads = listOf(TWO_CODES, TWO_CODES, TWO_CODES))

        assertEquals(DtcClearController.ClearOutcome.RETURNED, result.outcome)
        assertEquals(listOf("P0133", "P0420"), result.codesAfter)
        assertTrue(fake.clearSent)
        assertTrue(result.message.contains("came straight back"))
        assertTrue(result.message.contains("active, not stored"))
    }

    @Test
    fun `UNVERIFIED when the post-send re-read goes quiet`() {
        val (result, fake) = run(confirmed = true, codeReads = listOf(TWO_CODES, TWO_CODES, QUIET_LINK))

        assertEquals(DtcClearController.ClearOutcome.UNVERIFIED, result.outcome)
        assertNull("UNVERIFIED never captures a trustworthy after-read", result.codesAfter)
        assertTrue(fake.clearSent)
        assertEquals(
            "I sent the clear, but the car stopped answering, so I do not know whether it took.",
            result.message,
        )
    }

    @Test
    fun `the 44 ack never upgrades UNVERIFIED to CLEARED`() {
        // D1/D2: a quiet-link re-read after a normal-looking ack must still read UNVERIFIED, never
        // CLEARED - this is the exact defect the whole transaction shape exists to prevent.
        val (result, _) = run(
            confirmed = true,
            codeReads = listOf(TWO_CODES, TWO_CODES, QUIET_LINK),
            clearAck = "44\r\r",
        )
        assertEquals(DtcClearController.ClearOutcome.UNVERIFIED, result.outcome)
    }
}
