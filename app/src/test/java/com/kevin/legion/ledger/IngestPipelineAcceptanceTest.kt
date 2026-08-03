package com.kevin.legion.ledger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM, no Robolectric - [IngestPipeline.isAcceptableStatementFile] is
 * pure string logic, no Android calls. Covers the per-account-subfolder +
 * CSV ticket's file-acceptance rule: PDF stays gated on mime type alone,
 * CSV is gated on extension because SAF providers are inconsistent about
 * what mime type a `.csv` gets served as - see the function's own doc
 * comment for the full reasoning.
 */
class IngestPipelineAcceptanceTest {
    @Test
    fun `a real PDF mime type is accepted regardless of extension`() {
        assertTrue(IngestPipeline.isAcceptableStatementFile("eStmt_2026-07.pdf", "application/pdf"))
        // Even a misnamed file is accepted if the provider reports the mime correctly - PDF stays mime-gated, unchanged from before this ticket.
        assertTrue(IngestPipeline.isAcceptableStatementFile("weird_name", "application/pdf"))
    }

    @Test
    fun `a csv extension is accepted under every mime type a real SAF provider has been seen to report`() {
        assertTrue(IngestPipeline.isAcceptableStatementFile("stmt.csv", "text/csv"))
        assertTrue(IngestPipeline.isAcceptableStatementFile("stmt.csv", "text/comma-separated-values"))
        assertTrue(IngestPipeline.isAcceptableStatementFile("stmt.csv", "application/vnd.ms-excel"))
        assertTrue(IngestPipeline.isAcceptableStatementFile("stmt.csv", "application/octet-stream"))
        // Case-insensitive extension match.
        assertTrue(IngestPipeline.isAcceptableStatementFile("STMT.CSV", "application/octet-stream"))
    }

    @Test
    fun `application-octet-stream alone, with no csv extension, is not accepted`() {
        // The "not everything" requirement: octet-stream is the generic
        // fallback for countless non-statement binaries and must not be
        // accepted on mime type alone.
        assertFalse(IngestPipeline.isAcceptableStatementFile("random.bin", "application/octet-stream"))
        assertFalse(IngestPipeline.isAcceptableStatementFile("no_extension_at_all", "application/octet-stream"))
    }

    @Test
    fun `an unrelated document type is rejected`() {
        assertFalse(IngestPipeline.isAcceptableStatementFile("notes.gdoc", "application/vnd.google-apps.document"))
        assertFalse(IngestPipeline.isAcceptableStatementFile("photo.jpg", "image/jpeg"))
    }
}
