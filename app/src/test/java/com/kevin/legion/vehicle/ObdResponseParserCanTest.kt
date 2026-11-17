package com.kevin.legion.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mode 03 on CAN carries a count byte; the legacy buses do not (2026-11-15). Before this the CAN
 * answer `43 01 01 33` (one code, P0133) was read as P0101. Legacy replies must read exactly as before.
 */
class ObdResponseParserCanTest {

    @Test
    fun `CAN single frame uses the count byte`() {
        assertEquals(listOf("P0133"), ObdResponseParser.dtcCodes("43 01 01 33", can = true))
        assertEquals(listOf("P0133", "P0420"), ObdResponseParser.dtcCodes("43 02 01 33 04 20", can = true))
        assertEquals(emptyList<String>(), ObdResponseParser.dtcCodes("43 00", can = true))
    }

    @Test
    fun `CAN multi-frame and two ECUs`() {
        val multi = "00A\r0: 43 04 01 33 04 20\r1: 03 00 01 71 00 00 00"
        assertEquals(listOf("P0133", "P0420", "P0300", "P0171"), ObdResponseParser.dtcCodes(multi, can = true))
        assertTrue(ObdResponseParser.isValidDtcReply(multi))
        assertEquals(listOf("P0133", "P0700"), ObdResponseParser.dtcCodes("43 01 01 33\r43 01 07 00", can = true))
    }

    @Test
    fun `legacy replies read exactly as before`() {
        assertEquals(listOf("P0133", "P0420"), ObdResponseParser.dtcCodes("43 01 33 04 20", can = false))
        assertEquals(listOf("P0700", "P1282"), ObdResponseParser.dtcCodes("43 07 00 12 82", can = false))
        assertEquals(
            listOf("P0442", "P1391", "P0135", "P0455"),
            ObdResponseParser.dtcCodes("43 04 42 13 91 01 35\r43 04 55 00 00 00 00", can = false),
        )
    }

    @Test
    fun `a successful K-line bus init is not a failure, a failed one is`() {
        assertFalse(ObdResponseParser.isFailureResponse("BUS INIT: ...OK\r41 00 BE 3F A8 13"))
        assertTrue(ObdResponseParser.isFailureResponse("BUS INIT: ...ERROR"))
        assertTrue(ObdResponseParser.isFailureResponse("BUS INIT: ..."))
        assertTrue(ObdResponseParser.isFailureResponse("BUS INIT: ...OK"))
        assertTrue(ObdResponseParser.isValidDtcReply("BUS INIT: ...OK\r43 01 33 00 00 00 00"))
    }
}
