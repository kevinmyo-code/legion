package com.kevin.legion.backend

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.ledger.LedgerRecordBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Plaid rows arrive as `BANK_API` (Kevin, 2026-10-09: stored as fact, no tag on screen). The
 * phone must accept them rather than refuse them as unrecognised provenance. */
class BankApiProvenanceTest {

    @Test
    fun `the server's BANK_API text maps to IngestMethod BANK_API`() {
        assertEquals(IngestMethod.BANK_API, ledgerIngestMethodFor("BANK_API"))
    }

    @Test
    fun `an unknown provenance is still refused`() {
        assertNull(ledgerIngestMethodFor("PLAID"))
    }

    @Test
    fun `BANK_API survives the bridge both ways`() {
        assertEquals(RecordProvenance.BANK_API, LedgerRecordBridge.provenanceFor(IngestMethod.BANK_API))
        assertEquals(IngestMethod.BANK_API, LedgerRecordBridge.ingestMethodFor(RecordProvenance.BANK_API))
    }
}
