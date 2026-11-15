package com.kevin.legion.vehicle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake ELM327 transcripts for Mode 09 PID 02. Every VIN here is made up and obviously so
 * ("1A2B3C4D5E6F7G8H9" and friends): the repo is public.
 */
class VinResponseTest {

    private val vin = "1A2B3C4D5E6F7G8H9"
    private val otherVin = "9Z8Y7X6W5V4U3T2S1"

    // ---- CAN, headers off (the ELM default after ATZ) --------------------------------------

    private val canHeadersOff = "SEARCHING...\r014\r0: 49 02 01 31 41 32\r" +
        "1: 42 33 43 34 44 35 45\r2: 36 46 37 47 38 48 39\r\r"

    @Test
    fun `CAN with headers off and a SEARCHING line reads the VIN`() {
        assertEquals(VinResponse.Result.Vin(vin), VinResponse.parse(canHeadersOff))
    }

    @Test
    fun `CAN with headers off and spaces off reads the VIN`() {
        val raw = "014\r0:490201314132\r1:42334334443545\r2:36463747384839\r"
        assertEquals(VinResponse.Result.Vin(vin), VinResponse.parse(raw))
    }

    @Test
    fun `indexed lines that arrive out of order are put back in order`() {
        val raw = "014\r1: 42 33 43 34 44 35 45\r0: 49 02 01 31 41 32\r2: 36 46 37 47 38 48 39\r"
        assertEquals(VinResponse.Result.Vin(vin), VinResponse.parse(raw))
    }

    @Test
    fun `a reply cut off before the last frame is unreadable, not a short VIN`() {
        val raw = "014\r0: 49 02 01 31 41 32\r1: 42 33 43 34 44 35 45\r"
        assertTrue(VinResponse.parse(raw) is VinResponse.Result.Unreadable)
    }

    // ---- CAN, headers on --------------------------------------------------------------------

    private val canHeadersOn = "7E8 10 14 49 02 01 31 41 32\r7E8 21 42 33 43 34 44 35 45\r" +
        "7E8 22 36 46 37 47 38 48 39\r"

    @Test
    fun `CAN with 11-bit headers reassembles the ISO-TP frames`() {
        assertEquals(VinResponse.Result.Vin(vin), VinResponse.parse(canHeadersOn))
    }

    @Test
    fun `CAN with headers on and spaces off reads the VIN`() {
        val raw = canHeadersOn.split('\r').joinToString("\r") { it.replace(" ", "") }
        assertEquals(VinResponse.Result.Vin(vin), VinResponse.parse(raw))
    }

    @Test
    fun `CAN with 29-bit headers reads the VIN`() {
        val raw = "18DAF110 10 14 49 02 01 31 41 32\r18DAF110 21 42 33 43 34 44 35 45\r" +
            "18DAF110 22 36 46 37 47 38 48 39\r"
        assertEquals(VinResponse.Result.Vin(vin), VinResponse.parse(raw))
    }

    @Test
    fun `two control units interleaving the same VIN still read as that VIN`() {
        val raw = "7E8 10 14 49 02 01 31 41 32\r7E9 10 14 49 02 01 31 41 32\r" +
            "7E8 21 42 33 43 34 44 35 45\r7E9 21 42 33 43 34 44 35 45\r" +
            "7E8 22 36 46 37 47 38 48 39\r7E9 22 36 46 37 47 38 48 39\r"
        assertEquals(VinResponse.Result.Vin(vin), VinResponse.parse(raw))
    }

    @Test
    fun `two control units giving different VINs are unreadable, never a pick`() {
        val raw = "7E8 10 14 49 02 01 31 41 32\r7E9 10 14 49 02 01 39 5A 38\r" +
            "7E8 21 42 33 43 34 44 35 45\r7E9 21 59 37 58 36 57 35 56\r" +
            "7E8 22 36 46 37 47 38 48 39\r7E9 22 34 55 33 54 32 53 31\r"
        assertTrue(VinResponse.parse(raw) is VinResponse.Result.Unreadable)
    }

    @Test
    fun `the other made-up VIN reads too, so the test VIN is not special`() {
        val raw = "7E8 10 14 49 02 01 39 5A 38\r7E8 21 59 37 58 36 57 35 56\r7E8 22 34 55 33 54 32 53 31\r"
        assertEquals(VinResponse.Result.Vin(otherVin), VinResponse.parse(raw))
    }

    // ---- legacy protocols (J1850 / ISO 9141-2 / KWP) ------------------------------------------

    private val legacy = "49 02 01 00 00 00 31\r49 02 02 41 32 42 33\r49 02 03 43 34 44 35\r" +
        "49 02 04 45 36 46 37\r49 02 05 47 38 48 39\r"

    @Test
    fun `legacy five-message reply reads the VIN without the repeated 49 becoming an I`() {
        assertEquals(VinResponse.Result.Vin(vin), VinResponse.parse(legacy))
    }

    @Test
    fun `legacy reply with headers and checksums reads the VIN`() {
        val raw = legacy.split('\r').filter { it.isNotBlank() }
            .joinToString("\r") { "48 6B 10 $it E6" }
        assertEquals(VinResponse.Result.Vin(vin), VinResponse.parse(raw))
    }

    @Test
    fun `the old parser's output on the legacy reply is fixed`() {
        // The old ObdResponseParser.vin read every 49 after the first as the letter I.
        assertEquals(vin, ObdResponseParser.vin(legacy))
    }

    // ---- a flat line from a clone ----------------------------------------------------------

    @Test
    fun `a flat single-line reply reads the VIN`() {
        val raw = "49 02 01 31 41 32 42 33 43 34 44 35 45 36 46 37 47 38 48 39"
        assertEquals(VinResponse.Result.Vin(vin), VinResponse.parse(raw))
    }

    // ---- refusals ---------------------------------------------------------------------------

    @Test
    fun `NO DATA is the car not answering`() {
        assertEquals(VinResponse.Result.NoData, VinResponse.parse("SEARCHING...\rNO DATA\r\r"))
    }

    @Test
    fun `a question mark is the adapter not understanding`() {
        assertEquals(VinResponse.Result.Unsupported, VinResponse.parse("?\r"))
    }

    @Test
    fun `blank is no response`() {
        assertEquals(VinResponse.Result.NoResponse, VinResponse.parse(""))
        assertEquals(VinResponse.Result.NoResponse, VinResponse.parse("\r\r>"))
    }

    @Test
    fun `bus errors are unreadable`() {
        assertTrue(VinResponse.parse("CAN ERROR\r") is VinResponse.Result.Unreadable)
        assertTrue(VinResponse.parse("BUS INIT: ...ERROR\r") is VinResponse.Result.Unreadable)
        assertTrue(VinResponse.parse("STOPPED\r") is VinResponse.Result.Unreadable)
    }

    @Test
    fun `garbage is unreadable`() {
        assertTrue(VinResponse.parse("ELM327 v1.5\r") is VinResponse.Result.Unreadable)
        assertTrue(VinResponse.parse("41 00 BE 3E B8 11\r") is VinResponse.Result.Unreadable)
        assertTrue(VinResponse.parse("0: 49 02 01 ZZ\r") is VinResponse.Result.Unreadable)
    }

    @Test
    fun `a VIN with I O or Q in it is refused`() {
        // 0x49 = I in the last position.
        val raw = "014\r0: 49 02 01 31 41 32\r1: 42 33 43 34 44 35 45\r2: 36 46 37 47 38 48 49\r"
        assertTrue(VinResponse.parse(raw) is VinResponse.Result.Unreadable)
    }

    @Test
    fun `an unprogrammed ECU's filler is refused`() {
        val zeros = "014\r0: 49 02 01 00 00 00\r1: 00 00 00 00 00 00 00\r2: 00 00 00 00 00 00 00\r"
        assertTrue(VinResponse.parse(zeros) is VinResponse.Result.Unreadable)
        val ones = "014\r0: 49 02 01 30 30 30\r1: 30 30 30 30 30 30 30\r2: 30 30 30 30 30 30 30\r"
        assertTrue(VinResponse.parse(ones) is VinResponse.Result.Unreadable)
    }

    @Test
    fun `the check digit is not required`() {
        // The made-up VIN's ninth character fails the North American check digit; it still reads.
        assertTrue(VinResponse.isValidVin(vin))
        assertFalse(VinResponse.isValidVin("1A2B3C4D5E6F7G8H")) // 16 chars
        assertFalse(VinResponse.isValidVin("1A2B3C4D5E6F7G8HO")) // O
    }

    // ---- the read: one retry, bounded, never throws ------------------------------------------

    @Test
    fun `a timeout then a good reply reads the VIN on the retry`() = runBlocking {
        val replies = ArrayDeque(listOf("", canHeadersOff))
        var calls = 0
        val result = VinReader.read { calls++; replies.removeFirst() }
        assertEquals(VinResponse.Result.Vin(vin), result)
        assertEquals(2, calls)
    }

    @Test
    fun `a good first reply is not read twice`() = runBlocking {
        var calls = 0
        val result = VinReader.read { calls++; canHeadersOn }
        assertEquals(VinResponse.Result.Vin(vin), result)
        assertEquals(1, calls)
    }

    @Test
    fun `NO DATA twice gives up after one retry`() = runBlocking {
        var calls = 0
        val result = VinReader.read { calls++; "NO DATA\r" }
        assertEquals(VinResponse.Result.NoData, result)
        assertEquals(2, calls)
    }

    @Test
    fun `a send that throws is no response, not a crash`() = runBlocking {
        val result = VinReader.read { throw java.io.IOException("socket closed") }
        assertEquals(VinResponse.Result.NoResponse, result)
    }
}
