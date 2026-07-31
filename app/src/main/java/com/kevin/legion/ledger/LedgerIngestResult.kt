package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerTransaction

/**
 * Outcome of ingesting one statement file. [Quarantined] is not exceptional -
 * a statement that doesn't reconcile (corrupted export, an LLM extraction
 * that couldn't be verified) is an expected, common outcome, not a crash:
 * nothing is written to the ledger and the driver gets a plain reason why.
 */
sealed class LedgerIngestResult {
    data class Success(val transactions: List<LedgerTransaction>) : LedgerIngestResult()
    data class Quarantined(val reason: String) : LedgerIngestResult()
}
