package com.kevin.legion.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [maskedAccountLabel] exists because mission-control ticket 16 rendered a real 16-digit card
 * number on the CRED root. These pin the two halves of the rule: a long digit run is masked, and
 * everything a statement legitimately stores as a SHORT id is left alone.
 */
class MaskedAccountLabelTest {

    @Test fun `full card number is masked to its last four`() {
        assertEquals("****7823", maskedAccountLabel("4111111111117823"))
    }

    @Test fun `bare last-four is left alone`() {
        // BofaCardCsvStatementParser stores exactly this. It is not a secret, and "****7823"
        // would read as though four more digits had been hidden.
        assertEquals("7823", maskedAccountLabel("7823"))
    }

    @Test fun `already-masked label survives untouched`() {
        assertEquals("BOFA ****4471", maskedAccountLabel("BOFA ****4471"))
    }

    @Test fun `a named account keeps its name`() {
        assertEquals("DBS Multiplier", maskedAccountLabel("DBS Multiplier"))
    }

    @Test fun `a bank-prefixed number keeps the prefix`() {
        assertEquals("BOFA CHK ****8802", maskedAccountLabel("BOFA CHK 123458802"))
    }

    @Test fun `masking does not change the stored identity relation`() {
        // The point of the whole file: display is not identity. sameCard still matches on the
        // stored strings, whatever the UI paints.
        assertEquals(true, sameCard("4111111111117823", "7823"))
    }
}
