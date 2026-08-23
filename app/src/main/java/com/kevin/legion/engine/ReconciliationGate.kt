package com.kevin.legion.engine

import com.kevin.legion.data.local.RecordProvenance

/**
 * The reconciliation gate, rehomed as engine infrastructure
 * (`.scratch/aspect-engine/issues/11-capability-plugin-api.md` answer point 2/3, this ticket's
 * item 4). CLAUDE.md §4 is the binding rule; this interface is what turns it from a convention a
 * future ingestion plugin could forget into a door every plugin write must pass through -
 * "quarantine, provenance tagging, and the rule-7 provisional path live in the engine."
 *
 * **This ticket rehomes the CONTRACT, not the ledger/pantry implementations.** `LedgerStatementAgent`
 * and `PantryReceiptAgent` are not rewired onto this interface here - that is migration-wave work
 * (ticket 16's brief, explicit: "do NOT rewire ledger/pantry onto it yet"). A future ingestion
 * plugin implements [ReconciliationGate] and hands its [GateResult] to [RecordStore] the same way
 * ticket 03's answer already assumes every write goes through one door.
 *
 * [T] is left generic on purpose - a gate implementation reconciles whatever row shape its own
 * ingestion plugin extracts (a ledger transaction, a pantry line item, a future plugin's own
 * shape); this interface only fixes the SHAPE of the outcome, never the row type.
 */
interface ReconciliationGate<T> {
    /**
     * Reconciles [extracted] against [statedAnchorCents] - the source document's OWN printed total
     * or balance, in cents (CLAUDE.md §4 rule 3: `Long`, never `Double`, because the check depends
     * on exact equality). Returns [GateResult.Reconciled] only when every row parsed AND the sum
     * matches [statedAnchorCents] exactly; anything else is [GateResult.Quarantined] with a reason
     * stated in words (rule 2/rule 6 - "nothing partial is ever written", and an unrecognised line
     * inside an otherwise-matching section is a hard failure, never a silent skip).
     *
     * This function cannot itself detect a caller that silently dropped a row it failed to parse
     * before calling [reconcile] at all (rule 6's own blind spot, the BofA interest-row defect
     * CLAUDE.md §4 rule 6 documents) - an implementation MUST pass every recognised-section line
     * into [extracted], including ones it could not fully parse, represented as a value that makes
     * the sum fail to match rather than being omitted. That obligation lives on the implementation,
     * not on this interface, which is why it is written out here rather than assumed silently.
     */
    fun reconcile(extracted: List<T>, statedAnchorCents: Long): GateResult<T>

    /**
     * CLAUDE.md §4 rule 7: a source that states NO anchor at all (no balance, no total, nothing to
     * check against, ever) may still ingest, PROVISIONALLY, under four conditions the CALLER must
     * already have satisfied before invoking this - deterministic extraction (no LLM in the loop,
     * because an LLM would add cost and nondeterminism to rows that are already unverifiable and
     * cannot manufacture an anchor). This function only tags the result [RecordProvenance.UNRECONCILED]
     * and hands it back as [GateResult.Provisional]; it does not and cannot verify determinism
     * itself, and it has no visibility into "a later gated file covers the same window" - the
     * transience half of rule 7 (deleting superseded provisional rows) is the calling plugin's job,
     * the same way [com.kevin.legion.data.local.LedgerTransactionDao.deleteSupersededProvisional]
     * already is for ledger's BofA card-CSV case.
     */
    fun provisional(extracted: List<T>): GateResult.Provisional<T>
}

/**
 * The outcome of a [ReconciliationGate] pass. [Reconciled] and [Provisional] both carry rows ready
 * for [RecordStore] to write verbatim, tagged [RecordProvenance.DETERMINISTIC]/
 * [RecordProvenance.LLM_RECONCILED] or [RecordProvenance.UNRECONCILED] respectively by the calling
 * plugin (this type does not itself carry a provenance tag - it is a gate result, not a write
 * request, and the same [GateResult.Reconciled] shape serves a deterministic OR an LLM-assisted
 * extraction, per CLAUDE.md §4 rule 1).
 */
sealed class GateResult<out T> {
    data class Reconciled<T>(val rows: List<T>) : GateResult<T>()
    data class Provisional<T>(val rows: List<T>) : GateResult<T>()

    /**
     * Nothing was written - CLAUDE.md §4 rule 2's "nothing partial is ever written", and rule 6's
     * "a check that passes when nothing parsed is not a gate" made checkable: this variant carries
     * ZERO rows by construction, so there is no [rows] field to accidentally read as "what did get
     * written". [reason] states IN WORDS what did not happen and why - CLAUDE.md §7's outcome-verb
     * rule ("the assistant never asserts an outcome it did not observe"), applied to an ingestion
     * pipeline instead of speech: a caller surfacing [reason] to a person is the ingestion-side
     * twin of the spoken CANNOT_CLAUSE.
     */
    data class Quarantined(val reason: String) : GateResult<Nothing>()
}
