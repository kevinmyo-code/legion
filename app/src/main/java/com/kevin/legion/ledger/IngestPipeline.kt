package com.kevin.legion.ledger

import java.security.MessageDigest

/**
 * **Stripped to a single shared utility, backend-erp ticket 25 ("statement ingestion leaves the
 * phone entirely").** This object used to be the per-file classify/stage/commit core both
 * `service/IngestScanner`'s folder scan and [LedgerController]'s single-file pick funnel through -
 * see git history for that shape if it's ever needed again. Kevin ruled the phone never ingests a
 * bank statement at all any more: statement ingestion is a web-app-only feature now, against
 * `public.commit_statement`'s own SQL gate. Every piece of that machinery
 * ([stage]/[commit]/`StageOutcome`/`CommitOutcome`/`stripAccountPrefix`/`reattachAccountPrefix`/
 * `isAcceptableStatementFile`/`markNeedsLlm`) went with it, along with `LedgerFolderPreferences`,
 * `service/IngestScanner`, `service/ScanState`, and every deterministic/LLM statement parser under
 * `ledger/parsers/`.
 *
 * [sha256] is the one function this object still has a real caller for -
 * [com.kevin.legion.pantry.PantryController.writeReceipt] hashes a receipt photo's bytes with the
 * exact same algorithm the (now-deleted) statement ingestion path used, for the same reason: a
 * stable content identity independent of a filename or a Drive document id. Kept under this
 * object's original name/package rather than moved, so pantry's own import line and doc references
 * did not need to change for a deletion that has nothing to do with pantry.
 */
object IngestPipeline {
    /** SHA-256 over [bytes], hex-encoded. Computed once bytes are already in memory to be parsed - costs nothing extra. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
