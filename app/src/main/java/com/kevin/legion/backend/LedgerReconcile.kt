package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.engine.ledger.LedgerRecordBridge

/**
 * The one-time (and re-runnable) Phase 4 step 1/2 job for Ledger's one record type, `Transaction`
 * (`.scratch/backend-erp/issues/05-migration-path.md`, "Each aspect follows the identical shape...
 * 1. Upload... 2. Diff until clean"). There is only one wave here, unlike fleet's multi-wave split -
 * [com.kevin.legion.engine.ledger.LedgerAspectSeeder] defines exactly one record type.
 *
 * **This does NOT move `IngestPipeline.commit` onto `public.commit_statement`.** That RPC move is a
 * separate, much larger job (`.scratch/backend-erp/issues/03-the-gate-server-side.md`'s "Consequences
 * for whoever builds this": order-of-magnitude 200+ statements for a 40-row statement, because of
 * `RecordStore`'s per-row fan-out, and the ticket's own sequencing says the engine retirement should
 * land before or with it). This reconcile only migrates what already exists locally.
 *
 * **The account identity every uploaded row carries.** `public.ledger_transactions.account_last4`/
 * `account_nickname` are both `NOT NULL` (ticket 03 ruling 5: last-4 plus a nickname, because
 * last-4 alone collides between two accounts sharing it). Neither is stored as such locally -
 * [com.kevin.legion.data.local.LedgerTransaction.accountId] is a single free-text field that, per
 * parser, holds either a bare last-4 ([com.kevin.legion.ledger.parsers.BofaCardCsvStatementParser])
 * or a full printed account number ([com.kevin.legion.ledger.parsers.DbsStatementParser] and
 * friends) - see `ledger/LedgerAccountIdentity.kt:1-24`'s own doc comment for the whole reason
 * [sameCard][com.kevin.legion.ledger.sameCard] exists. **[accountLast4] is derived by taking the
 * last 4 digits of `accountId`; [accountNickname] is `accountId` itself, verbatim, never a
 * fabricated human-readable name.** This is deliberate, not a placeholder: CLAUDE.md section 4 rule
 * 5 forbids inventing a fact the source does not state, and a real nickname (what ticket 03 ruling 5
 * actually wants for disambiguation) was never captured locally for these rows. Using the full
 * `accountId` string as the nickname is the least-invented choice available AND it happens to
 * preserve exactly the disambiguating information ruling 5 wants: two local rows whose `accountId`
 * strings differ (the classic case - one parser's full PAN vs another's bare last-4 for the SAME
 * physical card) end up on two different `(account_last4, account_nickname)` server identities
 * rather than silently colliding into one.
 *
 * **The consequence worth stating out loud, not hiding.** A future REAL statement committed through
 * the not-yet-built `commit_statement` RPC will carry a human-TYPED nickname (Kevin's own words in
 * his LEGION CSV, ticket 03 ruling 5), which will essentially never equal a migrated row's
 * `accountId`-shaped nickname for the same physical account. Since rule-7 supersession keys on
 * `(account_last4, account_nickname)` together (ruling 7: "the key... is therefore
 * `(account_last4, account_nickname)` together"), **a migrated provisional row for an account will
 * not be superseded by a later real statement for that same account** unless the nicknames happen to
 * match. This reconcile does not attempt to fix that - it is a known, accepted gap in exactly the
 * style `LedgerAccountIdentity.kt`'s own doc comments state theirs, not an oversight.
 *
 * **Why this wave uploads ONLY `UNRECONCILED` rows, never `DETERMINISTIC`/`LLM_RECONCILED` ones.**
 * See [MigratedLedgerTransaction]'s own doc comment for the full trace: every currently-stored
 * ledger row's original three anchors (stated total, opening balance, closing balance) were checked
 * inside a parser at ingestion time and never persisted anywhere recoverable, and
 * `public.statements.ingested_file_id` is `NOT NULL` with nothing this migration can honestly point
 * it at. A `DETERMINISTIC`/`LLM_RECONCILED` row therefore cannot get a legitimate `statement_id`,
 * and the schema's own `ledger_txn_header_matches_provenance` check forbids uploading one with
 * `statement_id = null`. Rather than fabricate a header (CLAUDE.md section 4 rule 6's forbidden
 * "identity, not a check" shape) or silently reclassify a row's provenance, this reconcile reports
 * every such row in [Report.skipped], worded, and excludes it from [Report.isClean] the same way
 * [FleetReconcile.ServiceHistoryReport.skippedUnresolvedVehicle] excludes a "not yet migrated" row -
 * a genuinely later ticket (defining how a pre-existing local statement gets a server-side
 * `ingested_files` row, or some other honest anchor path) is what would let a future run of this
 * same reconcile pick these up with zero code changes here.
 *
 * **No Room replica this wave - a deliberate, evidence-based deferral, not a gap.** The obvious
 * candidate is reusing the legacy `ledger_transactions` Room table (`LedgerTransaction`/
 * `LedgerTransactionDao`), the way `Drive`/`TaggedPlace`/`PantryReceipt` reuse themselves elsewhere
 * in Phase 4. **Traced and rejected**: `MidnightApplication.onCreate()` still runs
 * `EngineDataMigrationWave3.copyLedgerIfNeeded`/`catchUpOnce` on every app launch (fire-and-forget,
 * per that object's own doc comment - the legacy table is "zero writers" but very much NOT
 * zero-readers), and `catchUpOnce`'s reconciliation pass reads `LedgerTransactionDao.allSyncIds()`/
 * `getAll()` off THIS SAME TABLE as its own comparison baseline for which pre-cutover legacy rows
 * still exist. Wiping and refilling that table with server rows (a plain replica refresh, the same
 * shape every sibling reconcile uses) would corrupt that unrelated, still-live process's baseline -
 * a failure mode none of [FleetReconcile]/[PantryReconcile]/[EventsReconcile] had to consider,
 * because none of their legacy tables had an ongoing reader outside their own aspect. A
 * purpose-built replica (the `VehicleReplica`/`ServiceHistoryReplica` shape) would need a real Room
 * schema migration, which the brief for this ticket requires stopping and reporting before writing -
 * so it is deferred here, exactly as fleet's own wave 1 deferred `VehicleReplica`/
 * `ServiceHistoryReplica` before wave 2 built them.
 *
 * **The id-exposure question, checked rather than assumed.** Grepped every call site referencing a
 * ledger transaction's local id: the only one is
 * `com.kevin.legion.ledger.LedgerController.recategorize(transactionId: Long, ...)`, a live-session
 * voice-tool round trip (the model reads an id off one tool result and passes it to the next call in
 * the SAME conversation) - never a persisted, cross-restart reference the way
 * [com.kevin.legion.data.local.EventReplica]'s alarm request codes are. This is the same "nothing
 * references it" shape commit 90887e8 found for fleet's vehicles/service-history replicas, so even
 * once a replica table exists, no id-preserving refill machinery
 * ([com.kevin.legion.backend.EventsReconcile]'s carried-id trick) would be warranted for it.
 *
 * **Never touches, trashes, or deletes an engine record.** Same posture as every other Phase 4
 * reconcile - the engine stays the source of truth until [Report.isClean].
 */
object LedgerReconcile {

    /** @param engineCount active engine `Transaction` records this device had.
     * @param uploaded how many `UNRECONCILED` rows were genuinely NEW server-side this run (a
     *   re-run reporting 0 is the expected idempotent outcome - see
     *   [PantryReconcile.Report.uploaded]'s own doc for why a `false` from
     *   [LedgerBackend.uploadMigratedTransaction] must not inflate this count).
     * @param skipped one worded entry per engine `Transaction` this run did NOT attempt to upload -
     *   either its provenance is `DETERMINISTIC`/`LLM_RECONCILED` with no recoverable statement
     *   header (see this object's own class doc), or its `accountId` has fewer than 4 digits and
     *   `account_last4` cannot be derived at all (a defensive case; every real parser today emits an
     *   all-digit `accountId`). Excluded from [onlyOnEngine]/[onlyOnServer] the same way
     *   [FleetReconcile.ServiceHistoryReport.skippedUnresolvedVehicle] excludes a "not yet migrated"
     *   row - a state this reconcile expects to resolve itself once a later ticket lifts the block,
     *   not a permanent rejection.
     * @param serverCountAfter the server's total transaction count after the upload (this table has
     *   no soft-delete, so every row IS the active set - see [RemoteLedgerTransaction]'s own doc).
     * @param onlyOnEngine `records.guid`s of ATTEMPTED (i.e. not [skipped]) engine rows the server
     *   has no matching `origin_guid` for.
     * @param onlyOnServer server `origin_guid`s (migrated only - a server-native row carries a null
     *   `origin_guid` and is correctly excluded, same as every sibling reconcile's own note) with no
     *   matching attempted engine row. */
    data class Report(
        val engineCount: Int,
        val uploaded: Int,
        val skipped: List<String>,
        val serverCountAfter: Int,
        val onlyOnEngine: List<String>,
        val onlyOnServer: List<String>,
    ) {
        val isClean: Boolean get() = onlyOnEngine.isEmpty() && onlyOnServer.isEmpty()
    }

    suspend fun run(context: Context, backend: LedgerBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)
        val sch = LedgerAspectSeeder.ensureSeeded(context)

        val engineRecords = db.engineRecordDao().activeByRecordType(sch.transaction.recordTypeId)
        // LedgerRecordBridge is the ONE place this decode happens - see its own class doc for why
        // reusing it here (rather than a fourth hand-rolled field map) matters.
        val engineTransactions = engineRecords.map { LedgerRecordBridge.toTransaction(it, sch.transaction.fieldIds) }

        var uploaded = 0
        val skipped = mutableListOf<String>()
        val skippedGuids = mutableSetOf<String>()

        for (txn in engineTransactions) {
            if (txn.ingestMethod != IngestMethod.UNRECONCILED) {
                // Blocked on a recoverable statement header - see this object's own class doc and
                // MigratedLedgerTransaction's for the full trace of why this is a "not yet", never a
                // fabrication.
                skipped.add(
                    "${txn.description} (${txn.syncId}): ${txn.ingestMethod} row with no recoverable " +
                        "statement header - not uploaded this run.",
                )
                skippedGuids.add(txn.syncId)
                continue
            }

            val digits = txn.accountId.filter { it.isDigit() }
            if (digits.length < 4) {
                // Defensive only - every real parser today emits an all-digit accountId (confirmed
                // by reading every ledger/parsers/*.kt accountId assignment).
                skipped.add(
                    "${txn.description} (${txn.syncId}): accountId '${txn.accountId}' has fewer " +
                        "than 4 digits, cannot derive account_last4 - not uploaded this run.",
                )
                skippedGuids.add(txn.syncId)
                continue
            }

            val migrated = MigratedLedgerTransaction(
                originGuid = txn.syncId,
                // Always null - see this object's own class doc for why an UNRECONCILED row is the
                // only shape this wave ever uploads, which is exactly what the schema's own
                // ledger_txn_header_matches_provenance check requires.
                statementId = null,
                accountLast4 = digits.takeLast(4),
                accountNickname = txn.accountId,
                currency = txn.currency.name,
                txnDateEpochMs = txn.txnDate,
                description = txn.description,
                amountCents = txn.amountCents,
                balanceCents = txn.balanceCents,
                lineRef = txn.lineRef,
                category = txn.category,
                categoryPending = txn.categoryPending,
                pendingLoggedAtMs = txn.pendingLoggedAt,
                provenance = txn.ingestMethod,
            )
            val wasNew = backend.uploadMigratedTransaction(migrated).getOrElse { return Result.failure(it) }
            if (wasNew) uploaded++
        }

        val serverTransactions = backend.fetchActiveTransactions().getOrElse { return Result.failure(it) }

        val engineGuids = engineTransactions.map { it.syncId }.toSet() - skippedGuids
        val serverGuids = serverTransactions.mapNotNull { it.originGuid }.toSet()

        return Result.success(
            Report(
                engineCount = engineTransactions.size,
                uploaded = uploaded,
                skipped = skipped,
                serverCountAfter = serverTransactions.size,
                onlyOnEngine = (engineGuids - serverGuids).sorted(),
                onlyOnServer = (serverGuids - engineGuids).sorted(),
            ),
        )
    }
}
