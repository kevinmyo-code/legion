package com.kevin.legion.backend

/**
 * One line on a [RemoteReceipt] - a `public.receipt_line_items` row
 * (`supabase/migrations/20260825000300_aspect_ledger_pantry.sql`). The four `estimated*` fields
 * are exactly that - ESTIMATES a model guessed from the product name, never printed on the
 * receipt and never part of any reconciliation arithmetic (CLAUDE.md section 4 rule 5). Any
 * surface rendering one must say "estimate".
 */
data class RemoteReceiptLine(
    val name: String,
    val quantity: Double,
    val unitPriceCents: Long?,
    val totalPriceCents: Long,
    val estimatedCaloriesKcal: Double?,
    val estimatedProteinG: Double?,
    val estimatedCarbsG: Double?,
    val estimatedFatG: Double?,
)

/**
 * A `public.receipts` header plus its lines, as Postgres reports them - the shape
 * [SupabasePantryBackend.fetchActiveReceipts] hands back, and the shape [PantryReconcile] copies
 * into the Room replica ([com.kevin.legion.data.local.PantryReceiptDao]/
 * [com.kevin.legion.data.local.PantryLineItemDao]).
 *
 * [currency] is kept as the server's raw wire string ("SGD"/"USD") rather than
 * [com.kevin.legion.data.local.LedgerCurrency] deliberately - this package stays free of a
 * pantry-domain enum dependency, matching [RemotePlace]'s own posture; the caller maps it.
 * [createdAtMs] is the "as of" clock for the cache-first read path (ticket 01 ruling 9) - receipts
 * are immutable once gated (the `forbid_mutation_of_facts` trigger blocks UPDATE outright), so
 * there is no `updated_at` column to prefer over it, unlike [RemotePlace.updatedAtMs].
 * [originGuid] is the phase 4 migration-provenance column (`supabase/migrations/
 * 20260826000100_origin_guid.sql`) - null for anything committed after cutover through
 * [commitReceipt], set only on a row [uploadMigratedReceipt] wrote. [PantryReconcile]'s diff reads
 * it to tell "not yet migrated" apart from "created directly against the server".
 */
/**
 * [unaccountedCents] is `receipts.unaccounted_cents`
 * (`supabase/migrations/20260826000300_receipt_unaccounted.sql`) - null on every healthy row.
 * Non-null means this receipt charged more than its captured lines explain and could not be
 * re-verified (CLAUDE.md section 4 rule 7's 2026-08-26 amendment); [provenance] will read
 * `"UNRECONCILED"` on exactly those rows, by the server's own check constraint, so the two fields
 * always agree. **Never fold [unaccountedCents] into any anchor arithmetic** - it is the residual
 * the gate could not explain, not a fact to reconcile against. Every surface rendering a receipt
 * where this is non-null must say so in words (rule 7 condition 3).
 */
data class RemoteReceipt(
    val serverId: String,
    val store: String,
    val purchaseDateEpochMs: Long,
    val currency: String,
    val totalCents: Long,
    val createdAtMs: Long,
    val originGuid: String?,
    val provenance: String,
    val unaccountedCents: Long?,
    val lines: List<RemoteReceiptLine>,
)

/** [MigratedReceipt]'s per-line shape for [PantryBackend.uploadMigratedReceipt]. [originGuid] is
 * the line's OWN engine record guid (`PantryAspectSeeder.LINE_ITEM_RECORD_TYPE_NAME` records each
 * get their own), never the parent receipt's - see `receipt_line_items.origin_guid`'s migration
 * comment on why this table has no natural key even in principle. */
data class MigratedReceiptLine(
    val originGuid: String,
    val name: String,
    val quantity: Double,
    val unitPriceCents: Long?,
    val totalPriceCents: Long,
    val estimatedCaloriesKcal: Double?,
    val estimatedProteinG: Double?,
    val estimatedCarbsG: Double?,
    val estimatedFatG: Double?,
)

/**
 * One already-gated engine receipt, ready for the one-time migration upload
 * ([PantryBackend.uploadMigratedReceipt]) - never for a new import, which goes through
 * [PantryBackend.commitReceipt] instead so the gate runs server-side exactly once (ticket 05's
 * "two distinct write paths, and keeping them distinct is the point"). [originGuid] is the
 * receipt's own `records.guid`; every field here is a figure that already passed
 * [com.kevin.legion.pantry.PantryReceiptAgent]'s gate at extraction time, so this upload is a
 * verification-and-transfer, not a second gate pass - [PantryReconcile] re-runs the gate's own
 * arithmetic locally before ever constructing one of these.
 *
 * **AMENDED 2026-08-26 (CLAUDE.md section 4 rule 7, the same amendment as
 * `receipts.unaccounted_cents`).** A receipt that fails [PantryReconcile]'s re-check no longer
 * disqualifies itself from [MigratedReceipt] entirely - it becomes one WITH [unaccountedCents]
 * set, so [SupabasePantryBackend.uploadMigratedReceipt] can insert it as `UNRECONCILED` rather
 * than silently dropping it. [unaccountedCents] is null on every ordinary (reconciling) receipt;
 * it is the one field on this type that must NEVER be fed back into [PantryReceiptAgent]'s
 * arithmetic - it is the residual that arithmetic could not explain, not a new anchor.
 */
data class MigratedReceipt(
    val originGuid: String,
    val store: String,
    val purchaseDateEpochMs: Long,
    val currency: String,
    val totalCents: Long,
    val subtotalCents: Long?,
    val taxCents: Long?,
    val otherChargesCents: Long?,
    val unaccountedCents: Long?,
    val lines: List<MigratedReceiptLine>,
)

/**
 * The outcome of [PantryBackend.commitReceipt]. Three genuinely different shapes, and collapsing
 * any two of them is the mistake this type exists to prevent:
 * - [Committed]: the gate ran server-side and passed. Only here may [PantryController] write the
 *   Room replica, and only with the real [Committed.receiptId] (ticket 01 ruling 9 - never ahead
 *   of a genuine ACK).
 * - [Quarantined]: the gate ran and REFUSED - CLAUDE.md section 4 doing its job, not a transport
 *   failure. Must never be reported to the user as "something went wrong" or retried blindly; the
 *   reason is the same wording [com.kevin.legion.pantry.PantryReceiptAgent] would have produced
 *   for the identical figures (see `commit_receipt`'s own SQL comment: it is a line-by-line mirror).
 * - [AlreadyCommitted]: idempotent hit on `content_sha256` - a retry of a request that already
 *   landed. Nothing was written again server-side, and per this class's own doc comment the
 *   response carries no `receipt_id`, so [PantryController] cannot write a fresh Room row for it
 *   either; that gap closes on the next [PantryReconcile] pass or read refresh, not by inventing an
 *   id here.
 */
sealed class CommitOutcome {
    data class Committed(val receiptId: String, val insertedLines: Int) : CommitOutcome()
    data class Quarantined(val reason: String) : CommitOutcome()
    data object AlreadyCommitted : CommitOutcome()
}

/**
 * The Phase 4 pantry seam, mirroring [PlacesBackend]'s shape exactly (see that interface's own
 * doc comment for the reasoning - narrow, no [io.github.jan.supabase.SupabaseClient] in any
 * signature, every function returns [Result] rather than throwing). [com.kevin.legion.pantry.PantryController]
 * is the one production caller.
 *
 * **Two distinct write paths, deliberately kept apart** (ticket 05's migration-path doc): a NEW
 * receipt import always goes through [commitReceipt] so CLAUDE.md section 4's gate runs
 * server-side exactly once; the one-time migration of receipts already gated on-device goes
 * through [uploadMigratedReceipt], which never re-runs the gate remotely - it inserts directly,
 * keyed on the engine's own `records.guid`, because those rows were already reconciled when they
 * were first ingested. Never call [uploadMigratedReceipt] for a fresh import - it would bypass the
 * gate entirely, which is the one thing section 4 does not permit.
 */
interface PantryBackend {
    /** Every gated receipt, header plus lines. Used to refresh the Room replica - never called
     * from a hot-path read; [com.kevin.legion.pantry.PantryController]'s read functions read the
     * replica instead. */
    suspend fun fetchActiveReceipts(): Result<List<RemoteReceipt>>

    /**
     * Commits one NEW receipt through `public.commit_receipt(payload jsonb)`
     * (`supabase/migrations/20260825000700_commit_receipt_rpc.sql`). [payload] is the raw JSON
     * object the RPC expects (content_sha256, store, purchase_date, currency, total_cents,
     * subtotal_cents, tax_cents, other_charges_cents, items[], provenance) - built by the caller,
     * which already holds a [com.kevin.legion.pantry.PantryIngestResult.Success] in the exact shape
     * needed; this interface stays free of that pantry-package type so a fake implementation in a
     * test never needs to construct one either. `Result.failure` means the REQUEST itself did not
     * complete (offline, rejected) - a gate refusal is [CommitOutcome.Quarantined] inside a
     * `Result.success`, never a failure, because the gate running and refusing is not a transport
     * problem.
     */
    suspend fun commitReceipt(payload: String): Result<CommitOutcome>

    /**
     * The one-time migration upload for a receipt the engine already gated. `Result.success(false)`
     * means a row with this [MigratedReceipt.originGuid] was already present server-side - a normal,
     * expected outcome on a re-run (ticket 05 phase 4 step 1: "a re-run is free"), never reported as
     * a fresh upload. `Result.failure` means the request itself did not complete.
     */
    suspend fun uploadMigratedReceipt(receipt: MigratedReceipt): Result<Boolean>
}

/** Thrown (wrapped in [Result.failure]) by [SupabasePantryBackend] for every failure branch - owned
 * by this package, never a raw supabase-kt/Ktor exception, same posture as [PlacesBackendException]. */
class PantryBackendException(message: String) : Exception(message)
