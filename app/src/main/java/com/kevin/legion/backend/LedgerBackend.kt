package com.kevin.legion.backend

import com.kevin.legion.data.local.IngestMethod

/**
 * A `public.ledger_transactions` row as Postgres reports it
 * (`supabase/migrations/20260825000300_aspect_ledger_pantry.sql`,
 * `supabase/migrations/20260826000100_origin_guid.sql` for [originGuid]). Unlike every other
 * `Remote*` shape in this package, there is no `updatedAtMs`/`deleted` pair here - this table has
 * neither an `updated_at` column nor a `deleted_at` one. It is append-only by design (the
 * immutability trigger blocks `UPDATE` unconditionally and blocks `DELETE` except on an
 * `UNRECONCILED` row - `20260825000300_aspect_ledger_pantry.sql`'s own comment), so "active" for
 * [LedgerBackend.fetchActiveTransactions] is simply "exists": a row a rule-7 supersession later
 * deletes is physically gone, not soft-deleted, and every other row is permanent by construction.
 *
 * [statementId] is null for a rule-7 provisional row (and a voice-logged pending charge) - see
 * [MigratedLedgerTransaction]'s own doc comment for why [LedgerReconcile] never actually mints a
 * non-null one this wave.
 */
data class RemoteLedgerTransaction(
    val serverId: String,
    val statementId: String?,
    val accountLast4: String,
    val accountNickname: String,
    val currency: String,
    val txnDateEpochMs: Long,
    val description: String,
    val amountCents: Long,
    val balanceCents: Long?,
    val lineRef: String,
    val category: String?,
    val categoryPending: Boolean,
    val pendingLoggedAtMs: Long?,
    /** `public.provenance`'s own text, one of `DETERMINISTIC`/`LLM_RECONCILED`/`UNRECONCILED`/`USER` -
     * carried as a raw string rather than [IngestMethod] because a row created directly server-side
     * (post-cutover, outside this migration) could in principle carry `USER`, which [IngestMethod]
     * cannot represent (see [com.kevin.legion.engine.ledger.LedgerRecordBridge.ingestMethodFor]'s own
     * doc comment for the same asymmetry on the read side). */
    val provenance: String,
    val createdAtMs: Long,
    val originGuid: String?,
)

/**
 * One already-seeded engine `Transaction` record, ready for the one-time migration upload
 * ([LedgerBackend.uploadMigratedTransaction]). [originGuid] is the record's own `records.guid`,
 * the same idempotency key every other Phase 4 aspect uses.
 *
 * **[statementId] is a real, general field - the type can represent a reconciled row that belongs
 * to a statement - but [LedgerReconcile] never actually constructs one this wave, and that is a
 * traced blocker, not an oversight.** `public.statements.ingested_file_id` is `NOT NULL
 * REFERENCES public.ingested_files`, and this migration has no server-side `ingested_files` row to
 * point at for a PRE-EXISTING local statement: the three anchors a header needs
 * (`stated_total_cents`/`opening_balance_cents`/`closing_balance_cents`) were checked, at ingestion
 * time, inside each parser (the `ledger/parsers` package) and never persisted anywhere recoverable -
 * `IngestedFile` carries no anchor columns and there is no local `statements` table at all. Three of
 * the four parsers that produce `DETERMINISTIC` rows
 * ([com.kevin.legion.ledger.parsers.BofaStatementParser],
 * [com.kevin.legion.ledger.parsers.BofaCardStatementParser]) and the one that produces
 * `LLM_RECONCILED` rows ([com.kevin.legion.ledger.parsers.LegionCsvStatementParser]) also never set
 * [com.kevin.legion.data.local.LedgerTransaction.balanceCents] at all, so even a best-effort
 * per-file reconstruction of opening/closing balance from stored running balances is unavailable for
 * most rows - and fabricating one (`opening = 0`, `closing = sum(lines)`) would be exactly CLAUDE.md
 * section 4 rule 6's forbidden shape, an identity rather than an independent check. This is the same
 * failure shape section 4 rule 7's 2026-08-26 pantry amendment names ("the gate's own inputs were
 * never persisted and the check cannot be reproduced from storage"), general enough that it applies
 * to every currently-stored ledger row, not a handful. **[LedgerReconcile] therefore only ever
 * uploads a row whose local [com.kevin.legion.data.local.IngestMethod] is already `UNRECONCILED`**
 * (schema-legal with `statementId = null` by construction, no header required) and reports every
 * `DETERMINISTIC`/`LLM_RECONCILED` row as blocked rather than fabricating a header or reclassifying
 * its provenance. See [LedgerReconcile]'s own class doc for the full reasoning and what a follow-up
 * ticket would need to define to lift this.
 */
data class MigratedLedgerTransaction(
    val originGuid: String,
    val statementId: String?,
    val accountLast4: String,
    val accountNickname: String,
    val currency: String,
    val txnDateEpochMs: Long,
    val description: String,
    val amountCents: Long,
    val balanceCents: Long?,
    val lineRef: String,
    val category: String?,
    val categoryPending: Boolean,
    val pendingLoggedAtMs: Long?,
    val provenance: IngestMethod,
)

/**
 * The Phase 4 ledger seam for THIS wave - the one `Transaction` record type
 * [com.kevin.legion.engine.ledger.LedgerAspectSeeder] defines. Mirrors [FleetBackend]/
 * [PantryBackend]'s shape exactly: narrow, no [io.github.jan.supabase.SupabaseClient] in any
 * signature, every function returns [Result] rather than throwing or returning a nullable.
 *
 * **Deliberately has no `uploadMigratedStatement`/`fetchActiveStatements` pair.** [FleetBackend]'s
 * own doc comment warns against building "an untested, uncalled live-edit path" ahead of a real
 * caller - the same principle applies here in reverse: [LedgerReconcile] has no caller for a
 * statement-header upload this wave (see [MigratedLedgerTransaction]'s own doc comment for why), so
 * adding one now would be speculation wearing the pattern's clothes, not established shape.
 */
interface LedgerBackend {
    /** Every ledger transaction row, server-side - see [RemoteLedgerTransaction]'s own doc comment
     * for why this table has no `deleted_at` to filter on. Never called from a hot-path read. */
    suspend fun fetchActiveTransactions(): Result<List<RemoteLedgerTransaction>>

    /** The one-time migration upload for an engine `Transaction` record not yet mirrored
     * server-side. `Result.success(false)` means a row with this
     * [MigratedLedgerTransaction.originGuid] was already present (a re-run, per ticket 05 phase 4
     * step 1: "a re-run is free"). `Result.failure` means the request itself did not complete. */
    suspend fun uploadMigratedTransaction(txn: MigratedLedgerTransaction): Result<Boolean>

    /**
     * live-sync ticket "pantry and ledger get a pull": every row this table has ever HAD created
     * on or after [sinceMs] (`created_at >= sinceMs`, inclusive - the same re-fetch-the-boundary-row
     * shape every other `fetchChangedXSince` in this codebase has, see [FleetSync]'s own class doc).
     * **"Includes tombstones" does not apply here** - see [RemoteLedgerTransaction]'s own doc
     * comment: this table has no `deleted_at` at all, `created_at` is the only clock it has, and a
     * row a rule-7 supersession later deletes is physically gone from this result the same way it is
     * from [fetchActiveTransactions]. [LedgerTransactionsSync.pull] is therefore insert-if-absent
     * only, never a merge with an update or tombstone branch - see that object's own class doc for
     * why that is the correct, not merely simplified, treatment of an append-only table.
     * Defaults to an empty result - every fake/test [LedgerBackend] implementation keeps compiling
     * without overriding this, same convention [FleetBackend.fetchChangedVehiclesSince] set.
     */
    suspend fun fetchChangedTransactionsSince(sinceMs: Long): Result<List<RemoteLedgerTransaction>> =
        Result.success(emptyList())
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseLedgerBackend] for every failure branch - owned
 * by this package, never a raw supabase-kt/Ktor exception, same posture as [FleetBackendException]/
 * [PantryBackendException]. */
class LedgerBackendException(message: String) : Exception(message)
