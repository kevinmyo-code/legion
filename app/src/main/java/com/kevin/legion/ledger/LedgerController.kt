package com.kevin.legion.ledger

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import androidx.room.withTransaction
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.engine.ledger.LedgerRecordBridge
import com.kevin.legion.ledger.parsers.DeterministicResult
import com.kevin.legion.ledger.parsers.PdfWords
import com.kevin.legion.ledger.parsers.StatementDispatcher
import com.kevin.legion.service.SpendEstimate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import org.json.JSONObject

/**
 * Orchestrates ledger statement ingestion - mirrors
 * [com.kevin.legion.vehicle.VehicleController]/
 * [com.kevin.legion.vehicle.BuildSheetController]'s naming and shape.
 * `.claude/plans/wiggly-beaming-quasar.md`.
 *
 * **Cutover 3** (`docs/architecture/cutover3-2026-08-24.md`). Every function below keeps its
 * ORIGINAL signature and return type (`LedgerTransaction`/`AccountBalance`/`BudgetVsActual`/etc,
 * the legacy Room-row-shaped value objects) so every caller - `ui/LedgerImportActivity`, the Money
 * tab screens, `service/LiveToolbox.kt`'s ledger tools - flips onto the engine with this file,
 * unchanged (ADR 0035: "the controller keeps its seam"). What changed is entirely internal: every
 * read now goes through [RecordStore]/[com.kevin.legion.data.local.EngineRecordDao] against the
 * Ledger aspect's `Transaction` record type (`docs/architecture/wave3-carve-2026-08-23.md`'s field
 * mapping, applied through [LedgerRecordBridge] - not a second mapping), and every write
 * ([logPendingTransaction], [clearPendingTransaction], the category-application functions, and
 * [importStatement]'s commit path via [IngestPipeline]) goes through [RecordStore] instead of
 * [com.kevin.legion.data.local.LedgerTransactionDao]. The legacy `ledger_transactions` table has
 * ZERO writers after this branch - see the cutover doc's reader/writer table.
 *
 * **Read volume**: every read function below funnels through [allTransactions], which loads every
 * active `Transaction` engine record into memory and filters/aggregates in Kotlin rather than SQL -
 * the same "personal household app's data volume, not an enterprise table scan" tradeoff
 * [RecordStore]'s own class doc already accepts, and the same one
 * [com.kevin.legion.pantry.PantryController] already applies to pantry's equivalent reads.
 *
 * `ingested_files` (the per-file ingestion ledger), `categories`/`category_rules` (the
 * classification vocabulary), and `budget_targets` are all **plugin-internal and untouched by this
 * cutover** - `docs/architecture/wave3-carve-2026-08-23.md`'s carve table already ruled all three
 * stay off the engine, and nothing here changes that ruling.
 */
object LedgerController {
    private const val TAG = "LedgerController"

    /**
     * Shortest merchant name [setCategory] will act on (audit fix, 2026-08-07).
     *
     * Four characters, chosen because the update is an unconditional
     * `LIKE '%key%'` over every stored description that rewrites history (D19),
     * and the key arrives as free text from a half-duplex speech pipe where a
     * garble is routine. Two and three character fragments ("AT", "CO", "ONE",
     * "WAL") are substrings of a great many real bank descriptions; four is
     * where an accidental match stops being likely without refusing names a
     * driver would plausibly say.
     *
     * It is a floor, not a guarantee - [CategorySetResult.merchantsTouched]
     * exists because a long key can still be too broad, and the surface has to
     * say how far the correction actually reached.
     */
    const val MIN_MERCHANT_KEY_LENGTH = 4

    // ---------------------------------------------------------------- engine <-> value-object bridge

    private fun db(context: Context) = CarDatabase.getDatabase(context)
    private fun store(context: Context): RecordStore {
        val database = db(context)
        return RecordStore(database.engineRecordDao(), database.fieldDefDao(), database.recordTypeDao())
    }

    private suspend fun schema(context: Context) = LedgerAspectSeeder.ensureSeeded(context)

    /**
     * Every non-trashed `Transaction` record, converted to the legacy [LedgerTransaction] shape via
     * [LedgerRecordBridge.toTransaction] - the ONE place every read below funnels through, so there
     * is exactly one query against the engine per read (matching
     * [com.kevin.legion.pantry.PantryController.allReceipts]'s own precedent).
     */
    private suspend fun allTransactions(context: Context): List<LedgerTransaction> {
        val sch = schema(context)
        return db(context).engineRecordDao().activeByRecordType(sch.transaction.recordTypeId)
            .map { LedgerRecordBridge.toTransaction(it, sch.transaction.fieldIds) }
    }

    /**
     * Writes one [LedgerTransaction] through [RecordStore] - the shared write shape
     * [logPendingTransaction] uses. Not used by [IngestPipeline]'s own commit path, which
     * constructs its own [RecordStore]/schema pair so every write in a multi-row statement commit
     * participates in the SAME `db.withTransaction` block - see that object's own doc comment.
     */
    private suspend fun writeTransaction(context: Context, txn: LedgerTransaction): RecordStore.WriteResult {
        val sch = schema(context)
        return store(context).create(
            recordTypeId = sch.transaction.recordTypeId,
            fieldValues = LedgerRecordBridge.fieldValuesFor(txn, sch.transaction.fieldIds),
            provenance = LedgerRecordBridge.provenanceFor(txn.ingestMethod),
            now = txn.txnDate,
            guid = txn.syncId,
        )
    }

    /**
     * Reads [uri] and runs it through [IngestPipeline] as a **one-element
     * run through the same pipeline** the folder scan uses -
     * `.scratch/ledger-drive-ingestion/issues/05-batch-ingestion-mechanics.md`
     * resolution §8. [uri] is expected to be a SAF document URI (the result
     * of `ACTION_OPEN_DOCUMENT`), so its own document id becomes the
     * [com.kevin.legion.data.local.IngestedFile.driveFileId] - a hand-picked
     * file gets a real record, content hash and `sourceFileId` exactly like a
     * scanned one, with `treeUri = null` marking "arrived via a single-file
     * pick" (ticket 03 amendment 1). Concrete payoff: pick a statement by
     * hand today, and if that same file later turns up in a connected
     * folder, the hash check recognises it and records `DUPLICATE_CONTENT`
     * instead of re-parsing (and possibly re-paying for) it.
     *
     * Falls back to the pre-ticket-05 dedup-only behavior (no [IngestedFile]
     * bookkeeping) for the rare case [uri] isn't a SAF document URI at all -
     * this must never crash an import over an identity it can't derive.
     */
    suspend fun importStatement(
        context: Context,
        uri: Uri,
        accountHint: String? = null,
    ): LedgerImportResult = withContext(Dispatchers.IO) {
        PdfWords.init(context)

        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "failed to read $uri: ${e.message}")
            null
        } ?: return@withContext LedgerImportResult(
            success = false,
            message = "I couldn't read that file - try picking it again.",
        )

        // The display name is load-bearing for CSV, not just cosmetic:
        // BofaCardCsvStatementParser reads the card's last-4 out of the
        // filename (`currentTransaction_7823.csv`) because that export prints
        // no account number anywhere in its body. Drive's DocumentsProvider
        // returns the real name here, so a picked file keeps it - but the
        // fallback deliberately stays a PDF name, since a fabricated CSV name
        // would produce a fabricated account id rather than a clean failure.
        val fileName = queryDisplayName(context, uri) ?: "statement.pdf"
        val driveFileId = documentIdFor(uri)?.let { IngestPipeline.stripAccountPrefix(it) }
            ?: return@withContext importWithoutRecord(context, fileName, bytes, accountHint)

        val lastModified = queryLastModified(context, uri)
        when (val staged = IngestPipeline.stage(
            context = context,
            driveFileId = driveFileId,
            treeUri = null,
            displayName = fileName,
            sizeBytes = bytes.size.toLong(),
            lastModified = lastModified,
            // The provider's ACTUAL mime type, not a hardcoded
            // "application/pdf". The old constant made
            // isAcceptableStatementFile's PDF branch pass unconditionally, so
            // the acceptance gate was never really consulted on this path AND
            // every picked file was recorded in `ingested_files` as a PDF
            // regardless of what it was. Falling back to octet-stream when the
            // provider says nothing is safe: acceptance then falls through to
            // the `.csv` extension check, which is the same signal the
            // folder-scan path uses.
            mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
        ) { bytes }) {
            is IngestPipeline.StageOutcome.Skipped -> LedgerImportResult(
                success = false,
                message = "I've already imported this exact file - nothing new to add.",
            )
            is IngestPipeline.StageOutcome.DuplicateContent -> LedgerImportResult(
                success = false,
                message = "This file's contents match a statement I've already imported.",
            )
            is IngestPipeline.StageOutcome.Unreadable -> LedgerImportResult(
                success = false,
                message = "I couldn't read that as a statement: ${staged.reason}",
            )
            is IngestPipeline.StageOutcome.Staged -> when (
                val det = StatementDispatcher.dispatchDeterministic(fileName, bytes, accountHint)
            ) {
                // The numbers reconciled; only the account is unknown. NOT
                // committed as a quarantine - nothing is wrong with the file,
                // and recording it as failed would make the retry look like a
                // second attempt at a broken document rather than the answer to
                // a question. The screen asks which account and calls back with
                // a hint.
                is DeterministicResult.NeedsAccount -> LedgerImportResult(
                    success = false,
                    message = "Which account is this statement for?",
                    needsAccount = true,
                )
                is DeterministicResult.Success -> commitResult(
                    context, driveFileId, fileName, staged, LedgerIngestResult.Success(det.transactions),
                )
                is DeterministicResult.Quarantined -> {
                    IngestPipeline.commit(
                        context, driveFileId, null, fileName, staged,
                        LedgerIngestResult.Quarantined(det.reason),
                    )
                    LedgerImportResult(success = false, message = det.reason)
                }
                is DeterministicResult.NeedsLlm -> {
                    // No approval surface exists yet for a single hand-picked
                    // file (ticket 06's gate UI belongs to ticket 08, out of
                    // scope here). NEEDS_LLM is still the correct terminal
                    // state either way: the gate's rule is "ask every time,
                    // never silently spend" (ticket 06 resolution §5), so
                    // this must never auto-approve. The file is left exactly
                    // where a decline in the folder-scan gate would leave it
                    // - re-offered on the next scan, not lost.
                    IngestPipeline.markNeedsLlm(context, driveFileId, null, fileName)
                    LedgerImportResult(
                        success = false,
                        message = "This statement doesn't match a known layout and needs AI reading, " +
                            "which uses your own Gemini key - connect a statements folder and approve " +
                            "it from a scan.",
                    )
                }
            }
        }
    }

    private suspend fun commitResult(
        context: Context,
        driveFileId: String,
        fileName: String,
        staged: IngestPipeline.StageOutcome.Staged,
        result: LedgerIngestResult,
    ): LedgerImportResult = when (
        val outcome = IngestPipeline.commit(context, driveFileId, null, fileName, staged, result)
    ) {
        is IngestPipeline.CommitOutcome.Ingested -> {
            val message = buildString {
                append("Imported ${outcome.transactionCount} transaction(s) from $fileName.")
                if (outcome.duplicatesSkipped > 0) append(" (${outcome.duplicatesSkipped} already on file, skipped.)")
                // Named separately from the exact-duplicate count: this one is
                // an inference (same account, date and amount inside a span an
                // earlier statement already reconciled), and an inference the
                // driver cannot see is an inference they cannot dispute.
                if (outcome.restatementsSkipped > 0) {
                    append(" ${outcome.restatementsSkipped} of those were the same transactions worded differently by an overlapping statement.")
                }
                // Ticket 12 §5: a reconciled statement replacing card-CSV
                // provisional rows it now covers - said out loud so a total
                // that shrinks (the provisional rows are gone, replaced by
                // this statement's own) never reads as data loss.
                if (outcome.provisionalSuperseded > 0) {
                    append(" ${outcome.provisionalSuperseded} pending transaction(s) replaced by this statement.")
                }
            }
            LedgerImportResult(success = true, message = message, importedCount = outcome.transactionCount)
        }
        is IngestPipeline.CommitOutcome.Quarantined -> LedgerImportResult(success = false, message = outcome.reason)
        // Cutover 3: a gate-passed statement whose engine write failed after reconciliation - the
        // whole commit rolled back, nothing landed, told in words rather than as a false success
        // (CLAUDE.md §7).
        is IngestPipeline.CommitOutcome.EngineWriteFailed -> LedgerImportResult(
            success = false,
            message = "This statement's numbers checked out, but I couldn't save it - try again.",
        )
    }

    /**
     * Pre-ticket-05 fallback for a [Uri] that isn't a SAF document URI, so
     * there is no stable id to key an [com.kevin.legion.data.local.IngestedFile]
     * record on. Only [resolveDedup]'s transaction-level dedup applies here -
     * no file-level skip/duplicate/replace bookkeeping. Expected to be rare
     * in practice (`ACTION_OPEN_DOCUMENT` results are SAF document URIs), but
     * an import must never crash over an identity it can't derive.
     */
    private suspend fun importWithoutRecord(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        accountHint: String? = null,
    ): LedgerImportResult =
        when (val det = StatementDispatcher.dispatchDeterministic(fileName, bytes, accountHint)) {
            is DeterministicResult.NeedsAccount -> LedgerImportResult(
                success = false,
                message = "Which account is this statement for?",
                needsAccount = true,
            )
            is DeterministicResult.Quarantined -> LedgerImportResult(success = false, message = det.reason)
            is DeterministicResult.Success -> commitPlain(context, fileName, det.transactions)
            is DeterministicResult.NeedsLlm -> {
                val llm = StatementDispatcher.runLlm(fileName, det.statementText)
                when (val result = llm.result) {
                    is LedgerIngestResult.Success -> commitPlain(context, fileName, result.transactions)
                    is LedgerIngestResult.Quarantined -> LedgerImportResult(success = false, message = result.reason)
                }
            }
        }

    /** Thrown only to force [androidx.room.withTransaction] to roll back the whole write when a
     * post-gate [RecordStore] write fails inside [commitPlain] - see [IngestPipeline]'s own
     * `EngineWriteFailedException` for the identical shape. */
    private class EngineWriteFailedException(val reason: String) : Exception()

    private suspend fun commitPlain(
        context: Context,
        fileName: String,
        transactions: List<LedgerTransaction>,
    ): LedgerImportResult {
        val existing = dedupAgainstExisting(allTransactions(context), transactions)
        val fresh = existing.first
        val skipped = existing.second

        val database = db(context)
        val sch = schema(context)
        val recordStore = store(context)
        try {
            database.withTransaction {
                for (txn in fresh) {
                    val result = recordStore.create(
                        recordTypeId = sch.transaction.recordTypeId,
                        fieldValues = LedgerRecordBridge.fieldValuesFor(txn, sch.transaction.fieldIds),
                        provenance = LedgerRecordBridge.provenanceFor(txn.ingestMethod),
                        now = txn.txnDate,
                        guid = txn.syncId,
                    )
                    if (result !is RecordStore.WriteResult.Success) {
                        throw EngineWriteFailedException(
                            "row '${txn.description}': ${(result as RecordStore.WriteResult.Failure).reason}",
                        )
                    }
                }
            }
        } catch (e: EngineWriteFailedException) {
            Log.w(TAG, "commitPlain: engine write failed after the gate passed, rolled back - ${e.reason}")
            return LedgerImportResult(success = false, message = "This statement's numbers checked out, but I couldn't save it - try again.")
        }

        val message = buildString {
            append("Imported ${fresh.size} transaction(s) from $fileName.")
            if (skipped > 0) append(" ($skipped already on file, skipped.)")
        }
        return LedgerImportResult(success = true, message = message, importedCount = fresh.size)
    }

    suspend fun latestBalanceCents(context: Context, accountId: String): Long? =
        latestBalanceRow(allTransactions(context), accountId)?.balanceCents

    suspend fun allAccountIds(context: Context): List<String> =
        allTransactions(context).map { it.accountId }.distinct()

    suspend fun recentTransactions(context: Context, limit: Int = 20): List<LedgerTransaction> =
        allTransactions(context).sortedByDescending { it.txnDate }.take(limit)

    /**
     * Every row of [currency] whose `txnDate` falls in `[fromMs, toMs]`, oldest-first with `id` as
     * the tie-break - the engine-backed successor to
     * [com.kevin.legion.data.local.LedgerTransactionDao.getForCurrencyInRange]. Exposed as its own
     * public function (cutover 3) because `advisor/digest/CredDigestBuilder.kt`/
     * `advisor/digest/HomeDigestBuilder.kt` called that DAO method directly, bypassing this
     * controller's seam entirely - a real gap the pre-cutover reader/writer sweep caught (see the
     * cutover doc's ruling table). Those two builders now call this instead of the (now-frozen,
     * zero-writer) legacy table.
     */
    suspend fun transactionsForCurrencyInRange(context: Context, currency: LedgerCurrency, fromMs: Long, toMs: Long): List<LedgerTransaction> =
        allTransactions(context)
            .filter { it.currency == currency && it.txnDate in fromMs..toMs }
            .sortedWith(compareBy<LedgerTransaction> { it.txnDate }.thenBy { it.id })

    /** The `[latestBalanceCents]`/`[provisionalDeltaCentsAfter]` shared anchor row - the row
     * `ORDER BY txnDate DESC, id DESC LIMIT 1` used to pick, replicated in Kotlin over [rows]. Both
     * legacy DAO queries carried the identical `id DESC` tie-break specifically so they would always
     * agree on which row is the anchor - see [LedgerTransactionDao.latestBalanceCents]'s own (now
     * historical) doc comment for why that agreement is load-bearing; this single function is what
     * keeps [accountBalances] reading the SAME anchor for both figures now that both reads funnel
     * through Kotlin instead of two separate SQL queries. */
    private fun latestBalanceRow(rows: List<LedgerTransaction>, accountId: String): LedgerTransaction? =
        rows.filter { it.accountId == accountId && it.balanceCents != null }
            .maxWithOrNull(compareBy<LedgerTransaction> { it.txnDate }.thenBy { it.id })

    /**
     * Per-account balance for ticket 08's balances surface (resolution §5):
     * one row per known [LedgerTransaction.accountId], each carrying its own
     * currency, never combined into a single headline figure across SGD and
     * USD. [AccountBalance.balanceCents] is null when the source format
     * never printed a running balance at all (Bank of America's section
     * layout, per [LedgerTransactionDao.latestBalanceCents]'s doc comment) -
     * the UI must render that as "not stated", never as zero.
     *
     * **Returns the UNGROUPED, one-row-per-`accountId` list** (review finding
     * 4 - spec drift). Grouping on [sameCard] used to happen in here, which
     * looked harmless until [ui.LedgerScreen]'s `knownAccountIds` turned out
     * to read straight off this function's result
     * (`state.balances.map { it.accountId }.distinct()`, `LedgerScreen.kt`)
     * to drive the one-tap "map this folder to an existing account" chips - see
     * [groupAccountBalances]'s own doc comment for the full reasoning.
     */
    suspend fun accountBalances(context: Context): List<AccountBalance> {
        val rows = allTransactions(context)
        return rows.map { it.accountId }.distinct().map { accountId ->
            val anchorRow = latestBalanceRow(rows, accountId)
            val balanceCents = anchorRow?.balanceCents
            // Every id here came from the row set itself, so at least one row exists for it;
            // currencyForAccount can only be null for an account with zero rows, which
            // requireNotNull turns into a loud crash rather than a silently wrong currency.
            val currency = requireNotNull(
                rows.filter { it.accountId == accountId }.maxByOrNull { it.txnDate }?.currency,
            ) { "accountId '$accountId' has no rows to read a currency from" }
            val rawAnchorDate = anchorRow?.txnDate
            // No printed balance at all (BofA card statements never print one) means there is no
            // anchor date either - Long.MIN_VALUE makes "strictly after" true for every
            // UNRECONCILED row that exists, which is correct: with nothing printed, every
            // provisional row is unposted movement, not something a nonexistent balance covers.
            val anchorDate = rawAnchorDate ?: Long.MIN_VALUE
            val provisionalDeltaCents = rows.filter {
                it.ingestMethod == IngestMethod.UNRECONCILED && it.pendingLoggedAt == null &&
                    sameCard(it.accountId, accountId) && it.currency == currency && it.txnDate > anchorDate
            }.sumOf { it.amountCents }
            val pendingDeltaCents = rows.filter {
                it.pendingLoggedAt != null && sameCard(it.accountId, accountId) && it.currency == currency
            }.sumOf { it.amountCents }
            AccountBalance(
                accountId = accountId,
                currency = currency,
                balanceCents = balanceCents,
                asOfMs = rawAnchorDate,
                provisionalDeltaCents = provisionalDeltaCents,
                isProvisional = provisionalDeltaCents != 0L,
                hasReconciledRows = rows.any {
                    it.ingestMethod != IngestMethod.UNRECONCILED && sameCard(it.accountId, accountId) && it.currency == currency
                },
                pendingDeltaCents = pendingDeltaCents,
                hasPendingRows = pendingDeltaCents != 0L,
            )
        }
    }

    /**
     * Writes one voice-logged pending transaction (`log_pending_transaction`). [accountId]/
     * [currency] are expected to have already been resolved against a real, existing account (see
     * [resolveAccountForPending]) - this function does not itself validate them, matching
     * [importStatement]'s split between resolution and the actual write.
     *
     * `sourceFile = "voice"`, `sourceFileId = null`, `balanceCents = null` (nothing printed this),
     * `ingestMethod = UNRECONCILED`, `lineRef = "voice:<uuid>"` (never collides with a real
     * statement's own line numbering, which is what [LedgerTransaction.lineRef] is for) - see
     * [LedgerTransaction.pendingLoggedAt]'s doc comment for why the row is still tagged
     * UNRECONCILED without a new [com.kevin.legion.data.local.IngestMethod] constant.
     *
     * **Cutover 3**: writes through [RecordStore] now, never
     * [com.kevin.legion.data.local.LedgerTransactionDao.insertAll]. A single-row create needs no
     * surrounding `db.withTransaction` the way a multi-row statement commit does.
     */
    suspend fun logPendingTransaction(
        context: Context,
        accountId: String,
        currency: LedgerCurrency,
        description: String,
        amountCents: Long,
        txnDate: Long,
    ) {
        writeTransaction(
            context,
            LedgerTransaction(
                sourceFile = "voice",
                accountId = accountId,
                currency = currency,
                txnDate = txnDate,
                description = description,
                amountCents = amountCents,
                balanceCents = null,
                lineRef = "voice:${java.util.UUID.randomUUID()}",
                ingestMethod = IngestMethod.UNRECONCILED,
                sourceFileId = null,
                pendingLoggedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Every voice-logged pending row, most recently logged first - `list_pending_transactions`'s read. */
    suspend fun pendingTransactions(context: Context): List<LedgerTransaction> =
        allTransactions(context).filter { it.pendingLoggedAt != null }.sortedByDescending { it.pendingLoggedAt }

    /**
     * `clear_pending_transaction`'s write. Returns true only if a row was actually removed.
     *
     * **Cutover 3**: [id] is now the engine record id (every [LedgerTransaction] this controller
     * hands back carries `id = EngineRecord.id`, per [LedgerRecordBridge.toTransaction]'s doc
     * comment). The `AND pendingLoggedAt IS NOT NULL` guard the retired DAO query carried is
     * replicated here in Kotlin - this can never delete anything but a genuinely pending row, even
     * if the caller passed the wrong id.
     */
    suspend fun clearPendingTransaction(context: Context, id: Long): Boolean {
        val database = db(context)
        val record = database.engineRecordDao().getById(id) ?: return false
        val sch = schema(context)
        if (record.recordTypeId != sch.transaction.recordTypeId) return false
        val pendingLoggedAt = PayloadCodec.readLong(JSONObject(record.payload), sch.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_PENDING_LOGGED_AT))
        if (pendingLoggedAt == null) return false
        return store(context).delete(id) is RecordStore.DeleteResult.Trashed
    }

    /**
     * `.scratch/legion-shape/issues/06-budget-versus-actual.md`. Fetches [month]'s rows for
     * [entity]'s currency plus enough padding on either side to catch a transfer whose two legs
     * post in different months, resolves [month]'s currently-effective budget per category
     * (D2's "copy forward"), computes coverage, and hands all three to the pure
     * [buildBudgetVsActual]. Direct successor to the deleted `profitAndLoss` - same fetch shape,
     * same coverage computation, different pure builder at the end.
     *
     * **The pairing window is wider than the period, on purpose.** [monthPairingWindow]
     * filters [allTransactions] ONCE, over the period padded by [PAIRING_WINDOW_DAYS] on each side
     * - not once for the period and again for the window - because [analyzeTransfers]'s own doc
     * comment is explicit that only rows actually in the period may ever appear in its output; the
     * wider fetch exists solely to hand it enough candidate partners to see a transfer whose other
     * leg fell outside the calendar month.
     *
     * **UTC month boundaries throughout**, matching every parser's `atStartOfDay(ZoneOffset.UTC)`
     * convention (CLAUDE.md §3, and MEMORY.md already records a dates-a-day-early bug from a mismatched
     * convention once). `monthEndMs` is the LAST millisecond actually inside the month - the moment
     * before the next month's own `atStartOfDay` - not the next month's start itself, so the `BETWEEN`
     * queries this feeds stay inclusive at both ends without a fencepost error.
     *
     * [accountFilter] (2026-08-18, the Money tab's SPEND toggle) is passed straight through to
     * [buildBudgetVsActual] - see its own doc comment for why every field of the returned
     * [BudgetVsActual] narrows together rather than only the category lines. `null` (the default)
     * is every existing caller's behaviour, unchanged.
     */
    suspend fun budgetVsActual(
        context: Context,
        entity: LedgerEntity,
        month: YearMonth,
        accountFilter: Set<String>? = null,
    ): BudgetVsActual {
        val db = db(context)
        val fileDao = db.ingestedFileDao()
        val rows = allTransactions(context)

        val monthStartMs = monthStartMillis(month)
        val monthEndMs = monthEndMillis(month)
        val (pairingWindow, inPeriod) = monthPairingWindow(rows, entity, month)

        val targets = db.budgetTargetDao().currentTargets(entity.currency, monthStartMs)
            .associate { it.category to it.amountCents }

        // Union every INGESTED file's window per accountId (ingested_files stays plugin-internal
        // legacy, unaffected by this cutover), then keep only the accounts that actually belong to
        // this entity's currency - the coverage query itself has no currency column to filter on
        // (IngestedFile carries none, see its own doc comment), so this is the same per-account
        // currency lookup accountBalances already does above, over the in-memory row set.
        fun currencyForAccount(accountId: String) = rows.filter { it.accountId == accountId }.maxByOrNull { it.txnDate }?.currency

        val coverage = fileDao.coverageInRange(monthStartMs, monthEndMs)
            .groupBy { it.accountId }
            .mapNotNull { (accountId, cRows) ->
                if (currencyForAccount(accountId) != entity.currency) return@mapNotNull null
                val from = cRows.minOf { it.fromMs }
                val to = cRows.maxOf { it.toMs }
                val windows = cRows.map { it.fromMs to it.toMs }
                AccountCoverage(
                    accountId = accountId,
                    // A REAL interval merge, not `from <= start && to >= end`.
                    // Those two look equivalent and are not: an account with a
                    // file covering the 1st-10th and another covering the
                    // 20th-31st has min = 1st and max = 31st, and a nine-day
                    // hole min/max cannot see. See coversMonthWithoutGaps.
                    coversWholeMonth = coversMonthWithoutGaps(windows, monthStartMs, monthEndMs),
                    // Still the outer bounds, because that is what the UI
                    // shows the user as "covered from X to Y". The gap, when
                    // there is one, is what coversWholeMonth reports.
                    coveredFromMs = from,
                    coveredToMs = to,
                    // Same interval merge as coversWholeMonth, walked from the
                    // month's own start (2026-08-18) - "good through this
                    // date", null when coverage never reaches the month start
                    // at all. See coveredThroughMs's own doc comment for why
                    // this cannot just be coveredToMs.
                    coveredThroughMs = coveredThroughMs(windows, monthStartMs, monthEndMs),
                )
            }

        val ownAccountIds = rows.filter { it.currency == entity.currency }.map { it.accountId }.toSet()
        return buildBudgetVsActual(
            entity, month, inPeriod, pairingWindow, targets, coverage,
            ownAccountIds = ownAccountIds, accountFilter = accountFilter,
        )
    }

    /**
     * Ticket 04 (quant-viz): month-over-month total spend for [entity], one [MonthSpend] per month
     * from `max(oldest txnDate month, now - maxMonths + 1)` through the current month. Calls the
     * SAME [budgetVsActual] every screen already reads for each month in range (map taste call 6:
     * "one definition of spend... never a parallel SQL aggregate that could drift from the exclusion
     * rules") - no separate query, no re-derived total.
     *
     * [maxMonths] bounds how far back this walks even on an account with years of history; the true
     * lower bound is still [monthsWithData]'s own earliest scan when that is more recent than
     * `now - maxMonths + 1`.
     *
     * **A month with zero operating-expense rows AND no ingested coverage at all is OMITTED, not
     * returned as a `MonthSpend(totalCents = 0)`** - see [MonthSpend]'s own doc comment for why a
     * month nothing was ever ingested for is a different claim from "ingested, and nothing was
     * spent". A month WITH coverage (even partial) and zero spend still returns as a real
     * `totalCents = 0` entry, because a statement covering that month is itself the anchor a plain
     * zero needs.
     */
    suspend fun monthlySpendTrend(context: Context, entity: LedgerEntity, maxMonths: Int = 24): List<MonthSpend> {
        val allMonths = monthsWithData(context, entity)
        if (allMonths.isEmpty()) return emptyList()
        val now = YearMonth.now(ZoneOffset.UTC)
        val earliestAllowed = now.minusMonths((maxMonths - 1).toLong())
        var cursor = maxOf(allMonths.first(), earliestAllowed)
        val out = mutableListOf<MonthSpend>()
        while (!cursor.isAfter(now)) {
            val budget = budgetVsActual(context, entity, cursor)
            // The gap-vs-zero guard, and the totalCents/isComplete/hasProvisionalRows aggregation,
            // both live in monthSpendFrom - the same pure rule this loop applied inline until it was
            // extracted so a unit test could exercise it without Room (LedgerBudgetTest).
            monthSpendFrom(cursor, budget)?.let { out += it }
            cursor = cursor.plusMonths(1)
        }
        return out
    }

    /**
     * The category drill-down (Kevin, 2026-08-07: "I want to be able to drill down into a category
     * and see the transactions in there") - every operating expense row for [month]/[entity] whose
     * [LedgerTransaction.category] equals [category], newest first. `null` for [category] means the
     * uncategorised bucket (D11's own loud bucket - see [UncategorizedSpend]'s doc comment), NOT
     * "every row" - a caller wanting the whole month's activity should use [recentTransactions]/
     * [budgetVsActual] instead.
     *
     * Reads [operatingExpenses] - the SAME transfer-exclusion and expense-only definition
     * [buildBudgetVsActual] sums each category line from - so a driver drilling into "Travel" sees
     * exactly the rows that produced that line's total, never a re-derived approximation of it (see
     * [operatingExpenses]'s own doc comment for the bug shape two independent definitions caused
     * once already).
     *
     * [accountFilter] (2026-08-18) is the SAME per-account toggle [budgetVsActual] takes - a row
     * list drilled into from a filtered category total must show exactly the rows that made that
     * total, never every account's rows for a total that only counted one.
     */
    suspend fun categoryTransactions(
        context: Context, entity: LedgerEntity, month: YearMonth, category: String?,
        accountFilter: Set<String>? = null,
    ): List<LedgerTransaction> {
        val rows = allTransactions(context)
        val (pairingWindow, inPeriod) = monthPairingWindow(rows, entity, month)
        val ownAccountIds = rows.filter { it.currency == entity.currency }.map { it.accountId }.toSet()
        val expenses = operatingExpenses(entity, inPeriod, pairingWindow, ownAccountIds = ownAccountIds, accountFilter = accountFilter)
        return expenses.filter { it.category == category }.sortedByDescending { it.txnDate }
    }

    /**
     * quant-viz ticket 10's Money-tab hero graphic: every operating expense row for [month]/[entity],
     * ACROSS every category, newest first - the unfiltered sibling of [categoryTransactions]
     * (map taste call 6, "one definition of spend": this calls the SAME [operatingExpenses] the
     * category drill-down and [buildBudgetVsActual]'s own category lines are built from, never a
     * parallel SQL aggregate that could drift from the transfer/own-account exclusion rules - see
     * [categoryTransactions]'s own doc comment for the bug shape two independent definitions caused
     * once already). The Money tab folds this into [com.kevin.legion.ui.ledger.categoryDailySpendBars]
     * ([category] left `null` there has a DIFFERENT meaning - "the uncategorised bucket" - so this
     * function exists rather than the tab reusing `categoryTransactions(..., category = null)`, which
     * would silently narrow the daily bars to only the uncategorised rows).
     */
    suspend fun monthOperatingExpenses(
        context: Context, entity: LedgerEntity, month: YearMonth,
        accountFilter: Set<String>? = null,
    ): List<LedgerTransaction> {
        val rows = allTransactions(context)
        val (pairingWindow, inPeriod) = monthPairingWindow(rows, entity, month)
        val ownAccountIds = rows.filter { it.currency == entity.currency }.map { it.accountId }.toSet()
        return operatingExpenses(entity, inPeriod, pairingWindow, ownAccountIds = ownAccountIds, accountFilter = accountFilter)
    }

    /**
     * The own-account-movements drill-down (Kevin, 2026-08-13) - the SAME [buildBudgetVsActual]
     * call [budgetVsActual] makes, returning only [BudgetVsActual.excludedOwnAccountMovements] so
     * `ui.ledger.ExcludedOwnAccountMovementsScreen` reads the identical classification the caveat
     * sentence itself was built from, never a re-derived approximation (see [categoryTransactions]'s
     * own doc comment for why this repo insists on reusing the one call rather than a second one).
     */
    suspend fun excludedOwnAccountMovements(context: Context, entity: LedgerEntity, month: YearMonth): ExcludedOwnAccountMovements =
        budgetVsActual(context, entity, month).excludedOwnAccountMovements

    /** [YearMonth]'s own UTC start, matching every parser's `atStartOfDay(ZoneOffset.UTC)` convention. */
    private fun monthStartMillis(month: YearMonth): Long =
        month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The last millisecond actually inside [month], UTC - one before the next month's own start. */
    private fun monthEndMillis(month: YearMonth): Long =
        month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1

    /**
     * [budgetVsActual]'s fetch, factored out so [categoryTransactions] reads the identical rows
     * rather than re-querying with its own copy of the same math - "one definition, one place"
     * applied to the FETCH, not only the pure builder underneath it. See [budgetVsActual]'s own doc
     * comment for why the pairing window is padded wider than the period itself. [rows] is the
     * caller's own [allTransactions] read, so a single logical operation never re-reads the engine
     * twice for one call.
     */
    private fun monthPairingWindow(
        rows: List<LedgerTransaction>, entity: LedgerEntity, month: YearMonth,
    ): Pair<List<LedgerTransaction>, List<LedgerTransaction>> {
        val monthStartMs = monthStartMillis(month)
        val monthEndMs = monthEndMillis(month)
        val windowMs = PAIRING_WINDOW_DAYS * 24L * 60 * 60 * 1000
        val pairingWindow = rows.filter {
            it.currency == entity.currency && it.txnDate in (monthStartMs - windowMs)..(monthEndMs + windowMs)
        }
        val inPeriod = pairingWindow.filter { it.txnDate in monthStartMs..monthEndMs }
        return pairingWindow to inPeriod
    }

    /** D9: sets [category]'s budget for [entity]'s currency from [month] onward - D2's "copy forward", written at the point of change rather than duplicated every month. See [com.kevin.legion.data.local.BudgetTarget]'s doc comment for why this is a single upsert, not a per-month row. */
    suspend fun setBudget(context: Context, entity: LedgerEntity, category: String, month: YearMonth, amountCents: Long) {
        val monthStartMs = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        CarDatabase.getDatabase(context).budgetTargetDao().upsert(
            com.kevin.legion.data.local.BudgetTarget(
                category = category,
                currency = entity.currency,
                amountCents = amountCents,
                effectiveFromMonthEpoch = monthStartMs,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * The literal explicit budget target on record for [category] as of [month] (quant-viz ticket
     * 09, Kevin follow-on 2026-08-13: "set a budget target so i can see the meters"). `null` when
     * NO [com.kevin.legion.data.local.BudgetTarget] row has EVER been written for this category -
     * distinct from a row that explicitly recorded `0L` (a driver silencing the meter on purpose).
     * [BudgetLine.gap.target] cannot make this distinction: [buildBudgetVsActual] defaults an
     * untargeted-but-spending category's line to `target = 0L` purely as a display convenience (see
     * its own "$0 budgeted, $42 spent" comment), which would read identically to a true explicit-zero
     * target if the UI tried to derive "was a target ever set" from that field alone. This reads the
     * DAO directly instead - the SAME "latest effective on or before this month" copy-forward read
     * (`currentTargets`) [budgetVsActual] itself already calls, just narrowed to one category.
     */
    suspend fun currentTargetCents(context: Context, entity: LedgerEntity, category: String, month: YearMonth): Long? {
        val monthStartMs = monthStartMillis(month)
        return CarDatabase.getDatabase(context).budgetTargetDao().currentTargets(entity.currency, monthStartMs)
            .firstOrNull { it.category == category }
            ?.amountCents
    }

    /** The fixed category set (ticket 07 D14), for a budget-editing screen or a category-guess prompt. */
    suspend fun allCategories(context: Context): List<com.kevin.legion.data.local.Category> =
        CarDatabase.getDatabase(context).categoryDao().getAll()

    /**
     * Kevin adding a category through the ledger screen (2026-08-07) - see
     * [validateNewCategoryName]'s doc comment for why this is a different door than
     * `set_category`'s D14 fixed-list boundary, not a violation of it. Validates against the
     * CURRENT stored list (never a stale one the caller might be holding), trims, and only then
     * writes - the same "validate against the live DB, then write" order [setCategory] already
     * follows. [isFoodCategory] defaults false; nothing in this UI surface offers Kevin a way to
     * mark a hand-added category as a food category (D15), which is fine - `Pets` and everything
     * else a driver is likely to add by hand is not food, and the flag only ever gates the
     * still-deferred grocery-vs-meals cross-check (ticket 09), not the budget-vs-actual split D15
     * exists for today.
     */
    suspend fun addCategory(context: Context, name: String): NewCategoryValidation {
        val db = CarDatabase.getDatabase(context)
        val existing = db.categoryDao().allNames()
        val result = validateNewCategoryName(name, existing)
        if (result is NewCategoryValidation.Valid) {
            db.categoryDao().insert(com.kevin.legion.data.local.Category(name = result.trimmed, isFoodCategory = false))
        }
        return result
    }

    /**
     * D16: applies every stored [com.kevin.legion.data.local.CategoryRule] against every
     * currently-uncategorised row, oldest rule first (see [com.kevin.legion.data.local.CategoryRule]'s
     * doc comment for why order matters). Idempotent - only ever touches `category IS NULL` rows,
     * so re-running after a scan that added new transactions costs nothing for rows a rule already
     * claimed. Returns how many rows were newly categorised, for a caller that wants to say so.
     *
     * **Cutover 3**: applies each rule inside its own `db.withTransaction`, writing every matched
     * row through [RecordStore.update] - a multi-row write, so it is wrapped for atomicity the same
     * way [IngestPipeline]'s commit is, even though a category correction is not itself money-gated.
     */
    suspend fun applyCategoryRules(context: Context): Int {
        val db = CarDatabase.getDatabase(context)
        val rules = db.categoryRuleDao().getAll()
        var applied = 0
        for (rule in rules) {
            applied += applyToMatching(context) { it.category == null && it.description.uppercase().contains(rule.substring.uppercase()) }
                .let { matched -> updateCategoryOnRows(context, matched, rule.category, categoryPending = false) }
        }
        return applied
    }

    /** Rows in [allTransactions] satisfying [predicate] - a small helper so every category-application
     * function below reads the same way. */
    private suspend fun applyToMatching(context: Context, predicate: (LedgerTransaction) -> Boolean): List<LedgerTransaction> =
        allTransactions(context).filter(predicate)

    /** Writes [category]/[categoryPending] onto every one of [rows] via [RecordStore.update], all
     * inside one `db.withTransaction` - the engine-backed successor to every `UPDATE
     * ledger_transactions SET category = ...` query this cutover retires. Returns how many rows
     * were actually updated (a [RecordStore.WriteResult.Failure] on any one row is logged and
     * skipped, never silently dropped from the count - "in words what did NOT happen" applied to a
     * bulk write, not just a single one). */
    private suspend fun updateCategoryOnRows(context: Context, rows: List<LedgerTransaction>, category: String, categoryPending: Boolean): Int {
        if (rows.isEmpty()) return 0
        val database = db(context)
        val sch = schema(context)
        val recordStore = store(context)
        var updated = 0
        database.withTransaction {
            for (row in rows) {
                val result = recordStore.update(
                    row.id,
                    mapOf(
                        sch.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY) to category,
                        sch.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY_PENDING) to categoryPending,
                    ),
                )
                if (result is RecordStore.WriteResult.Success) updated++
                else Log.w(TAG, "updateCategoryOnRows: couldn't update row #${row.id}: ${(result as RecordStore.WriteResult.Failure).reason}")
            }
        }
        return updated
    }

    /**
     * D17's candidate pool: distinct merchant keys ([extractMerchantKey]) among rows that are
     * STILL uncategorised, with every transfer-shaped row taken OUT of the pool first. Callers are
     * expected to call [applyCategoryRules] first - this function does not re-check rules itself,
     * so what it returns is genuinely rule-less, not just "not yet re-swept".
     *
     * **The transfer gate (`.scratch/car-probe-transfers/`, 2026-08-13).** Moving your own money
     * between your own accounts has no merchant and no category, but before this fix nothing
     * stopped it acquiring one: [analyzeTransfers] existed and correctly flagged a `PAYMENT TO CRD`
     * row as [ExclusionReason.MATCHED_TRANSFER] or [ExclusionReason.SUSPECTED_TRANSFER], but was
     * only ever wired into [budgetVsActual]/[categoryTransactions] - never into this function, so a
     * flagged row still flowed straight into [CategoryAgent] and could come back with a
     * [com.kevin.legion.data.local.CategoryRule] confirming it into "Subscriptions" or whatever the
     * model guessed. On Kevin's real data this was the single largest candidate: 57 rows of `MOBILE
     * BANKING PAYMENT TO CRD` and 18 of `ONLINE BANKING PAYMENT TO CRD`, together the two biggest
     * merchant keys the guesser ever saw.
     *
     * **Reuses [analyzeTransfers] rather than adding a second detector** - the whole defect here
     * was two systems answering "is this a transfer" that should have been one; a third would
     * compound it. Both [ExclusionReason.MATCHED_TRANSFER] and [ExclusionReason.SUSPECTED_TRANSFER]
     * rows are dropped here, which is a DIFFERENT (stricter) rule than [budgetVsActual]'s own -
     * that function keeps a `SUSPECTED_TRANSFER` row IN spend on Kevin's 2026-08-07 decision that an
     * unconfirmed guess must never silently understate a total. Categorisation carries no such
     * downside: refusing to *guess a category* for a row that merely LOOKS like a transfer costs
     * nothing (it stays uncategorised, exactly where an ordinary unrecognised merchant would sit,
     * and a driver can still hand-categorise it via `set_category` if the wording turned out to be
     * a coincidence), while guessing one that turns out wrong writes a permanent
     * [com.kevin.legion.data.local.CategoryRule]. Two different costs of being wrong justify two
     * different thresholds from the SAME classification - this is not a second opinion on what a
     * transfer is, only a different question asked of the identical answer.
     *
     * [pairingWindow] is [allTransactions] itself rather than anything currency- or month-scoped,
     * because a transfer's matching leg can already carry a category (a matched pair must exclude
     * BOTH legs, not only the one still uncategorised) or fall outside any single month's
     * [budgetVsActual] pairing window.
     */
    suspend fun uncategorizedMerchants(context: Context): UncategorizedMerchants {
        val split = uncategorizedTransactionsSplit(context)
        val keys = split.real.map { extractMerchantKey(it.description) }.distinct()

        if (split.transfers.isNotEmpty()) {
            // Visible, not silent (CLAUDE.md §4 rule 6's principle, applied here to a guesser gate
            // rather than a reconciliation gate): a row dropped from the candidate pool with no
            // trace anywhere is indistinguishable from a row that was never uncategorised at all.
            // The caller-facing half of this lives in LiveToolbox's categorizeTransactions response
            // text (UncategorizedMerchants.transfersSkipped); this log line is the one trace that
            // survives even when nobody is listening to the voice response.
            Log.d(TAG, "uncategorizedMerchants: skipped ${split.transfers.size} transfer-shaped row(s), " +
                "${keys.size} merchant key(s) remain")
        }
        return UncategorizedMerchants(keys, split.transfers.size)
    }

    /**
     * The full row-level split behind [uncategorizedMerchants] (2026-08-18, the CATEGORIZE
     * drilldown's "44 uncategorised rows are invisible" fix -
     * `.scratch/ledger-drive-ingestion/issues/` money/categorize screen). [uncategorizedMerchants]
     * only ever needed distinct MERCHANT KEYS for the guesser's candidate pool; the driver-facing
     * "see and hand-categorise" surface needs the actual rows, both the ones that genuinely need a
     * category ([UncategorizedSplit.real]) and the ones [analyzeTransfers] correctly excludes as
     * transfer-shaped ([UncategorizedSplit.transfers]) - shown too, per CLAUDE.md §4 rule 6's
     * principle applied to a UI count: hiding 22 of 44 real rows behind an invisible filter is
     * exactly the shape of the bug this function exists to stop repeating. Same transfer gate as
     * [uncategorizedMerchants]'s own doc comment: both [ExclusionReason.MATCHED_TRANSFER] and
     * [ExclusionReason.SUSPECTED_TRANSFER] rows count as transfers here.
     */
    suspend fun uncategorizedTransactionsSplit(context: Context): UncategorizedSplit {
        val rows = allTransactions(context)
        val candidates = rows.filter { it.category == null }
        if (candidates.isEmpty()) return UncategorizedSplit(emptyList(), emptyList())

        val analysis = analyzeTransfers(inPeriod = candidates, pairingWindow = rows)
        val transferIds = analysis.excluded.mapTo(mutableSetOf()) { it.txn.id }

        val (transfers, real) = candidates.partition { it.id in transferIds }
        return UncategorizedSplit(real = real, transfers = transfers)
    }

    /**
     * The spend-gate estimate for guessing [merchantCount] merchants' categories -
     * `.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md`'s exact-count-before-any-spend
     * shape, reusing [SpendEstimate] rather than a second cost model. A merchant-name batch is a
     * single short prompt, not a whole statement's text - [CATEGORY_GUESS_PROMPT_TOKENS]/
     * [CATEGORY_GUESS_RESPONSE_TOKENS] are `reasoned` constants sized for that (a handful of tokens
     * per merchant name plus its category answer), not measured from any real call, unlike
     * [service.IngestScanner]'s per-file estimate which upgrades to a measured average once real
     * statement calls exist. Nothing here upgrades the same way yet - `basedOnMeasuredAverage` is
     * always false.
     */
    fun categoryGuessEstimate(merchantCount: Int): SpendEstimate = SpendEstimate(
        fileCount = merchantCount,
        estimatedPromptTokensPerFile = CATEGORY_GUESS_PROMPT_TOKENS,
        estimatedResponseTokensPerFile = CATEGORY_GUESS_RESPONSE_TOKENS,
        basedOnMeasuredAverage = false,
    )

    /**
     * Runs the actual Gemini batch. **Only call after the caller has shown [categoryGuessEstimate]
     * and gotten an explicit yes** - ticket 06's "ask every time, never silently spend" (§5)
     * applies to this gate exactly as it does to the statement-reading one; this function itself
     * does not ask, it spends. Every guess lands [com.kevin.legion.data.local.LedgerTransaction.categoryPending]
     * `= true` (D17/D18) - never auto-confirmed. Returns how many rows were touched.
     */
    suspend fun applyCategoryGuesses(context: Context, merchantKeys: List<String>): CategoryGuessResult {
        val db = CarDatabase.getDatabase(context)
        val categories = db.categoryDao().allNames()
        val outcome = CategoryAgent.guessBatch(merchantKeys, categories)
        var rows = 0
        for ((merchantKey, category) in outcome.guesses) {
            val matched = applyToMatching(context) { it.category == null && it.description.uppercase().contains(merchantKey.uppercase()) }
            rows += updateCategoryOnRows(context, matched, category, categoryPending = true)
        }
        return CategoryGuessResult(rowsCategorized = rows, merchantsCategorized = outcome.guesses.size)
    }

    /**
     * Every row still carrying a pending AI guess (D17/D18) - `categorize_transactions`'s "see and
     * confirm" surface, both the voice tool and the ledger screen's own section (B2), read this
     * rather than re-deriving it from [uncategorizedMerchants], which only sees rows with NO
     * category at all.
     */
    suspend fun pendingCategoryGuesses(context: Context): List<LedgerTransaction> =
        allTransactions(context).filter { it.categoryPending }.sortedByDescending { it.txnDate }

    /**
     * `set_category`'s write (ticket B1, 2026-08-07): confirms OR corrects a category for every
     * transaction matching [merchant], by substring against the UPPERCASED description - the same
     * matching convention [extractMerchantKey]/[matchCategory] already use. Writes a
     * [com.kevin.legion.data.local.CategoryRule] first (D18's "a merchant is guessed at most once,
     * ever" - a hand-set category is as durable a fact as a confirmed guess, so it governs future
     * transactions from the same merchant too), then applies it to every matching row on file,
     * unconditionally (D19's "recategorising rewrites history").
     *
     * [category] is assumed already validated against the fixed set (D14) by the caller - this
     * function does not itself re-check, matching [confirmCategoryGuess]'s split.
     */
    suspend fun setCategory(context: Context, merchant: String, category: String): CategorySetResult {
        val merchantKey = merchant.trim().uppercase()
        val db = CarDatabase.getDatabase(context)

        // Audit fix 2026-08-07, and the order of what follows is the fix.
        //
        // This used to write the CategoryRule FIRST and unconditionally, then
        // run the update. Three defects fell out of that, all of them silent:
        //
        //  1. A key too short to mean anything ("AT") is a substring of a huge
        //     number of real bank descriptions, and the update rewrites every
        //     match unconditionally (D19). One garbled utterance at a
        //     half-duplex voice pipe could re-file a large slice of the ledger.
        //  2. The rule was written even when the update matched NOTHING, so
        //     `set_category` could answer "I don't see any transactions from X"
        //     while having just installed a permanent rule that fires on every
        //     future import. The spoken answer and the stored effect disagreed.
        //  3. `CategoryRuleDao.insert` has no conflict strategy and there is no
        //     unique index on `substring`, so correcting the same merchant twice
        //     stacked a second rule that `getAll` kept applying forever.
        //
        // So: refuse a key too short to be meant, MEASURE the blast radius,
        // update first, and only then write the rule - replacing any rule for
        // the same key rather than stacking one.
        if (merchantKey.length < MIN_MERCHANT_KEY_LENGTH) {
            return CategorySetResult(rowsTouched = 0, merchantsTouched = 0, keyTooShort = true)
        }

        // 2026-08-13 fix: refuse a rule whose substring IS bank-generated boilerplate
        // (CHECKCARD/CHKCARD/PURCHASE, see isBankNoiseKey's doc comment). Checked
        // separately from the length floor above - "CHECKCARD" is nine characters,
        // well past MIN_MERCHANT_KEY_LENGTH, so the floor alone never would have caught
        // this. This is the systemic half of the fix: extractMerchantKey no longer
        // GUESSES this key, but a driver or voice command can still say it directly,
        // and without this check the trap re-arms itself exactly as before.
        if (isBankNoiseKey(merchantKey)) {
            return CategorySetResult(rowsTouched = 0, merchantsTouched = 0, isNoiseKey = true)
        }

        val rows = allTransactions(context)
        val matched = rows.filter { it.description.uppercase().contains(merchantKey) }
        val merchantsTouched = matched.map { it.description }.distinct().size
        val rowsTouched = updateCategoryOnRows(context, matched, category, categoryPending = false)

        if (rowsTouched > 0) {
            db.categoryRuleDao().deleteBySubstring(merchantKey)
            db.categoryRuleDao().insert(
                com.kevin.legion.data.local.CategoryRule(
                    category = category, substring = merchantKey, createdAt = System.currentTimeMillis(),
                )
            )
        }
        return CategorySetResult(rowsTouched = rowsTouched, merchantsTouched = merchantsTouched)
    }

    /**
     * The live blast-radius preview [setCategory]'s hand-entry surface shows BEFORE committing
     * (`ui/ledger/LedgerCategoryDrilldown.kt`, 2026-08-07: "Kevin must see and be able to correct
     * the key before it is applied"). Mirrors [setCategory]'s own `merchant.trim().uppercase()`
     * normalisation and [MIN_MERCHANT_KEY_LENGTH] floor exactly - a key this reports as reaching N
     * rows must reach the SAME N rows if [setCategory] is then actually called with it, or the
     * preview is a lie. Read-only: never writes a [com.kevin.legion.data.local.CategoryRule], never
     * touches a row.
     */
    suspend fun previewRecategorizeCount(context: Context, merchant: String): Int {
        val merchantKey = merchant.trim().uppercase()
        if (merchantKey.length < MIN_MERCHANT_KEY_LENGTH) return 0
        // Mirrors setCategory's noise-key refusal (2026-08-13) - a preview that shows a real row
        // count for a key [setCategory] would then refuse to act on is a lie by omission.
        if (isBankNoiseKey(merchantKey)) return 0
        return allTransactions(context).count { it.description.uppercase().contains(merchantKey) }
    }

    /**
     * The undo path for [setCategory] (audit fix, 2026-08-07). Removes every
     * stored rule for [merchant] so it stops governing future imports.
     *
     * **Deliberately does NOT un-categorise the rows the rule already reached.**
     * Deleting a rule and rewriting history are different intentions, and
     * guessing which one was meant would risk wiping categories the driver
     * actually wanted. Correcting the rows is what [setCategory] is for; this
     * only stops the bleeding.
     */
    suspend fun clearCategoryRules(context: Context, merchant: String): Int {
        val merchantKey = merchant.trim().uppercase()
        if (merchantKey.isEmpty()) return 0
        return CarDatabase.getDatabase(context).categoryRuleDao().deleteBySubstring(merchantKey)
    }

    /**
     * D18: "confirming a guess creates the rule automatically... the whole stability answer: a
     * merchant is guessed at most once, ever." Clears [com.kevin.legion.data.local.LedgerTransaction.categoryPending]
     * on every row this [merchantKey] was guessed onto (not just one row - the guess was made once
     * per merchant key, [applyCategoryGuesses] above, not once per row) and writes the
     * [com.kevin.legion.data.local.CategoryRule] that means the same merchant is never sent to
     * [CategoryAgent] again.
     */
    suspend fun confirmCategoryGuess(context: Context, merchantKey: String, category: String) {
        val db = CarDatabase.getDatabase(context)
        db.categoryRuleDao().insert(
            com.kevin.legion.data.local.CategoryRule(
                category = category, substring = merchantKey, createdAt = System.currentTimeMillis(),
            )
        )
        val matched = applyToMatching(context) {
            it.categoryPending && it.category == category && it.description.uppercase().contains(merchantKey.uppercase())
        }
        updateCategoryOnRows(context, matched, category, categoryPending = false)
    }

    /**
     * D19: "recategorising REWRITES HISTORY... last month's figure was always wrong and is now
     * right." Touches exactly the one transaction, unconditionally - past months included, no
     * "future only" guard - and always lands as confirmed, never pending: a driver picking a
     * category directly is as confirmed a fact as this record has.
     */
    suspend fun recategorize(context: Context, transactionId: Long, category: String) {
        store(context).update(
            transactionId,
            mapOf(
                schema(context).transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY) to category,
                schema(context).transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY_PENDING) to false,
            ),
        )
    }

    /** Reasoned "typical" constants for a short merchant-name-plus-category prompt - see [categoryGuessEstimate]'s doc comment for why these aren't measured. */
    private const val CATEGORY_GUESS_PROMPT_TOKENS = 60
    private const val CATEGORY_GUESS_RESPONSE_TOKENS = 20

    /**
     * Every calendar month from [entity]'s earliest to latest transaction, inclusive - the month
     * picker's paging bound (ticket resolution §5: "Disabled past the ends of `monthsWithData`; never
     * let the user page into months that cannot have data."). Empty when [entity] has zero rows for
     * its currency, which the caller reads as "nothing to page to" rather than an error.
     */
    suspend fun monthsWithData(context: Context, entity: LedgerEntity): List<YearMonth> {
        val rows = allTransactions(context).filter { it.currency == entity.currency }
        val earliestMs = rows.minOfOrNull { it.txnDate } ?: return emptyList()
        val latestMs = rows.maxOfOrNull { it.txnDate } ?: return emptyList()
        val start = YearMonth.from(Instant.ofEpochMilli(earliestMs).atZone(ZoneOffset.UTC))
        val end = YearMonth.from(Instant.ofEpochMilli(latestMs).atZone(ZoneOffset.UTC))
        val months = mutableListOf<YearMonth>()
        var cursor = start
        while (!cursor.isAfter(end)) {
            months += cursor
            cursor = cursor.plusMonths(1)
        }
        return months
    }

    /** Matches [analyzeTransfers]'s default `maxDaysApart` - kept as one named constant rather than duplicated so the fetch window and the pairing tolerance can never silently drift apart. */
    private const val PAIRING_WINDOW_DAYS = 5

    /**
     * Drops EVERY ledger transaction and EVERY ingested-file record, so the
     * next folder scan starts from nothing. Returns what was removed, for the
     * caller to report - a destructive action that says nothing about what it
     * destroyed is indistinguishable from one that silently failed.
     *
     * **Cutover 3**: "every ledger transaction" now means every active engine `Transaction`
     * record, trashed via [RecordStore.delete] (30-day restorable, matching every other engine
     * delete in this codebase - a strictly SAFER guarantee than the legacy hard `DELETE`, not a
     * weaker one) rather than a single `DELETE FROM ledger_transactions`. Both the transaction
     * trashing loop and the [IngestedFileDao.deleteAll] wipe happen inside ONE `db.withTransaction`
     * - same "both tables or neither" invariant the legacy version already stated, preserved exactly
     * across the cutover:
     *
     * Dropping only the transactions leaves `ingested_files` claiming every file is already
     * INGESTED, so a rescan skips them all and the ledger stays permanently empty with no way to
     * refill it short of this function again. Dropping only the file records re-imports every file
     * on the next scan and `resolveDedup` has no prior rows to count against, so nothing is caught
     * as a duplicate. Either half alone is worse than neither.
     *
     * **Scope is the ledger aspect and nothing else.** Fleet (vehicles, OBD
     * samples, the 2026-08-04 Midnight AI import), pantry, companion memory
     * and profiles are untouched - which is the whole reason this exists as a
     * function rather than as an app-data wipe.
     *
     * Not undoable BY THE DRIVER-FACING SURFACE - the caller is responsible for confirming intent
     * before calling; nothing here asks. (A 30-day engine-side trash restore exists as a technical
     * safety net, but no UI path exposes it for this action.)
     */
    suspend fun purgeAll(context: Context): PurgeResult {
        val database = db(context)
        val sch = schema(context)
        val recordStore = store(context)
        var transactions = 0
        var files = 0
        database.withTransaction {
            val active = database.engineRecordDao().activeByRecordType(sch.transaction.recordTypeId)
            for (rec in active) {
                if (recordStore.delete(rec.id) is RecordStore.DeleteResult.Trashed) transactions++
            }
            files = database.ingestedFileDao().deleteAll()
        }
        Log.d(TAG, "purgeAll: dropped $transactions transactions and $files file records")
        return PurgeResult(transactionsDeleted = transactions, filesDeleted = files)
    }

    /** Every currently-quarantined file, for ticket 08's quarantine surface (resolution §6, provisional). */
    suspend fun quarantinedFiles(context: Context): List<IngestedFile> =
        CarDatabase.getDatabase(context).ingestedFileDao().listQuarantined()

    /** Just the count - mission-control ticket 04's shell ALARM segment, see
     * [com.kevin.legion.data.local.IngestedFileDao.countQuarantined]'s own doc for why this is a
     * count query rather than [quarantinedFiles] with the list thrown away, and for why DTC is
     * deliberately not a second source here. */
    suspend fun quarantinedCount(context: Context): Int =
        CarDatabase.getDatabase(context).ingestedFileDao().countQuarantined()

    /**
     * The quarantine row's RETRY action (resolution §6). Only flips the
     * record back to [com.kevin.legion.data.local.IngestState.NEW] - it does
     * not re-read or re-parse [driveFileId] itself. See
     * [com.kevin.legion.data.local.IngestedFileDao.retryQuarantined]'s doc
     * comment for why that split is deliberate rather than a shortcut.
     */
    suspend fun retryQuarantined(context: Context, driveFileId: String) {
        CarDatabase.getDatabase(context).ingestedFileDao().retryQuarantined(driveFileId)
    }

    /**
     * The quarantine header's RETRY ALL action. Same one-file semantics as
     * [retryQuarantined], applied across the whole quarantine list, returning
     * how many records moved back to [com.kevin.legion.data.local.IngestState.NEW]
     * so the caller can say so rather than leaving a bulk action silent.
     *
     * Deliberately does NOT kick off a scan, matching the per-file RETRY it
     * sits beside - see
     * [com.kevin.legion.data.local.IngestedFileDao.retryAllQuarantined].
     */
    suspend fun retryAllQuarantined(context: Context): Int =
        CarDatabase.getDatabase(context).ingestedFileDao().retryAllQuarantined()

    /**
     * Fetches the existing-row candidate set per account across [incoming]'s
     * own date range, then hands off to [resolveDedup] - ticket 04's pure
     * per-tuple counting comparison, run in Kotlin rather than SQL. Grouped by
     * [LedgerTransaction.accountId] first because a single statement is one
     * account in practice, but this stays correct even if a future producer
     * ever mixes them. Returns the rows to insert and how many were dropped as
     * duplicates. Only used by [importWithoutRecord]/[commitPlain] now -
     * [IngestPipeline.commit] does the equivalent for anything with an
     * [com.kevin.legion.data.local.IngestedFile] record, reading its own engine
     * snapshot directly rather than through this function.
     */
    private fun dedupAgainstExisting(
        existing: List<LedgerTransaction>,
        incoming: List<LedgerTransaction>,
    ): Pair<List<LedgerTransaction>, Int> {
        val toInsert = mutableListOf<LedgerTransaction>()
        var skipped = 0
        for ((accountId, group) in incoming.groupBy { it.accountId }) {
            val minDate = group.minOf { it.txnDate }
            val maxDate = group.maxOf { it.txnDate }
            val existingForAccount = existing.filter { it.accountId == accountId && it.txnDate in minDate..maxDate }
            val resolution = resolveDedup(existingForAccount, group)
            toInsert += resolution.toInsert
            skipped += resolution.duplicatesSkipped
        }
        return toInsert to skipped
    }

    /** Best-effort human-readable filename for the import confirmation message. */
    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** [uri]'s SAF document id, or null if it isn't a document URI at all (not expected for an `ACTION_OPEN_DOCUMENT` result, but never crash the import over it). */
    private fun documentIdFor(uri: Uri): String? = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: Exception) {
        null
    }

    /** Best-effort last-modified for [uri], used only as a change signal - 0L (never used for identity) if the provider doesn't report one. */
    private fun queryLastModified(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) {
        0L
    }
}

/**
 * [needsAccount] means the file reconciled and only its account is unknown -
 * the caller should ask which one and retry with an `accountHint`, NOT report a
 * failure. It is a separate flag rather than a message the screen pattern-matches
 * on, because the folder-scan path and the single-file path need different words
 * for the same condition: a hand-picked file has no folder to map.
 */
data class LedgerImportResult(
    val success: Boolean,
    val message: String,
    val importedCount: Int = 0,
    val needsAccount: Boolean = false,
)

/**
 * One account's latest known balance, in its own currency. See
 * [LedgerController.accountBalances]'s doc comment for why [balanceCents]
 * being null is a real, distinct state from it being zero.
 *
 * [provisionalDeltaCents] (ticket 12 §4/§6) is the sum of
 * [com.kevin.legion.data.local.IngestMethod.UNRECONCILED] rows dated after
 * whatever row [balanceCents] itself came from - mid-cycle card activity the
 * printed balance (or its total absence) hasn't accounted for yet. Zero when
 * there is none. [isProvisional] is [provisionalDeltaCents] `!= 0` - the UI
 * label is driven by this field, never by inferring it from a colour, per
 * the resolution §4 fix-3 rule [com.kevin.legion.ui.ledger.LedgerRows]
 * already follows for [com.kevin.legion.data.local.IngestMethod.LLM_RECONCILED].
 *
 * [hasReconciledRows] (review finding 5) answers "has this physical
 * card/account ever been reconciled against a real statement", which is a
 * DIFFERENT question from "[balanceCents] is non-null" - Bank of America's
 * card PDF reconciles against a printed TOTAL and never prints a running
 * balance at all, so a card account can be fully reconciled and still carry
 * `balanceCents == null` forever. The UI must ask this field the "has a
 * statement ever landed" question, never infer it from [balanceCents].
 */
/**
 * What [LedgerController.purgeAll] actually removed. Reported back rather than
 * swallowed so the surface can say "nothing to purge" and "dropped 40 rows"
 * differently - a destructive action that reports nothing looks identical to
 * one that quietly did nothing.
 */
data class PurgeResult(val transactionsDeleted: Int, val filesDeleted: Int) {
    val isEmpty: Boolean get() = transactionsDeleted == 0 && filesDeleted == 0
}

/**
 * [LedgerController.applyCategoryGuesses]'s result: rows actually touched versus distinct
 * merchants the model returned a guess for (never per-row - the whole point of the batching D17
 * demands, see that function's doc comment). The two numbers can legitimately differ - one
 * merchant key often matches several transactions (`KROGER #115`/`KROGER #122` both reduce to
 * `KROGER`) - so a caller reporting only one of them would understate or overstate what actually
 * happened.
 */
data class CategoryGuessResult(val rowsCategorized: Int, val merchantsCategorized: Int)

/**
 * [LedgerController.uncategorizedMerchants]'s result. [keys] is exactly what a caller previously
 * got back as a bare `List<String>` - every distinct merchant key still worth guessing a category
 * for. [transfersSkipped] is new (2026-08-13, `.scratch/car-probe-transfers/`): how many
 * transfer-shaped rows were excluded from the pool that produced [keys], so a caller can say so
 * out loud rather than have them vanish with no trace - see that function's own doc comment for
 * why this matters and why it is a row count, not a merchant-key count (one transfer wording,
 * `PAYMENT TO CRD`, can be dozens of rows across many months).
 */
data class UncategorizedMerchants(val keys: List<String>, val transfersSkipped: Int)

/**
 * [LedgerController.uncategorizedTransactionsSplit]'s result - every `category IS NULL` row, split
 * into [real] (needs a category, either by rule, by guess, or by hand) and [transfers] (correctly
 * excluded by [analyzeTransfers], never sent to a rule or a guess, but not hidden from the driver
 * either - see that function's own doc comment for why both are surfaced).
 */
data class UncategorizedSplit(val real: List<LedgerTransaction>, val transfers: List<LedgerTransaction>)

/**
 * [LedgerController.setCategory]'s result.
 *
 * [rowsTouched] is how many already-committed rows the correction reached.
 * [merchantsTouched] is how many DISTINCT descriptions it reached, and it is
 * the number that reveals a runaway match: forty rows from one merchant is
 * ordinary, forty rows from twelve merchants means the spoken key was a
 * substring of things the driver never named. **The surface must report it**,
 * because the update is unconditional and rewrites history (D19).
 *
 * [keyTooShort] means the correction was REFUSED before touching anything -
 * see [LedgerController.MIN_MERCHANT_KEY_LENGTH]. Distinct from
 * `rowsTouched == 0`, which means a long-enough key simply matched nothing.
 * A caller that conflates the two will tell the driver "no transactions found"
 * when the real answer is "that name was too short to be safe".
 *
 * [isNoiseKey] means the correction was REFUSED because the key is bank-generated boilerplate
 * (`CHECKCARD`, `CHKCARD`, `PURCHASE` - see [isBankNoiseKey]'s doc comment), not a merchant at all.
 * A THIRD distinct reason from the two above: the key was long enough and isn't a length problem,
 * it's a category-of-thing problem. 2026-08-13 fix, closing the exact bug shape the `CHECKCARD`
 * `category_rules` row caused: it confirmed 48 unrelated transactions into "Subscriptions".
 */
data class CategorySetResult(
    val rowsTouched: Int,
    val merchantsTouched: Int = 0,
    val keyTooShort: Boolean = false,
    val isNoiseKey: Boolean = false,
)

/**
 * [pendingDeltaCents] is the sum of [com.kevin.legion.data.local.LedgerTransaction.pendingLoggedAt]
 * rows - what the driver has logged BY VOICE, not read from any file. [hasPendingRows] is
 * `pendingDeltaCents != 0` (same convention, and same known limitation, as [isProvisional] below:
 * two pending rows that happen to net to exactly zero would read as "none", which is judged an
 * acceptable edge case rather than worth a second query per account).
 *
 * **The available figure is `balanceCents + provisionalDeltaCents + pendingDeltaCents`.** A
 * surface rendering that combined figure must say IN WORDS that it includes unconfirmed activity
 * (CLAUDE.md §4 rule 7: never by colour or a glyph alone) - and [provisionalDeltaCents] and
 * [pendingDeltaCents] must NEVER be collapsed into one field before that figure is built, because
 * they are two different claims that need two different sentences: "not yet on a statement" (a
 * file said this, unconfirmed) versus "not yet confirmed by the bank" (nobody but the driver has
 * said this at all). See [com.kevin.legion.data.local.LedgerTransaction.pendingLoggedAt]'s doc
 * comment for the full reasoning.
 */
data class AccountBalance(
    val accountId: String,
    val currency: LedgerCurrency,
    val balanceCents: Long?,
    // 2026-08-18 (Kevin: "say when the balance was last known"): the txnDate of the exact row
    // [balanceCents] was read from, straight off the same anchor row [accountBalances]'s own
    // [provisionalDeltaCents] read is computed against - never a second, independently-derived
    // one, because that would risk the two disagreeing on which row is the anchor. Null is a
    // REAL, DISTINCT state, not "unknown" and not "today": it means [accountId] has never printed
    // a balance at all (Bank of America's card layout, the same case [balanceCents] being null
    // already covers) - never render a missing [asOfMs] as current.
    val asOfMs: Long? = null,
    val provisionalDeltaCents: Long = 0L,
    val isProvisional: Boolean = false,
    val hasReconciledRows: Boolean = false,
    val pendingDeltaCents: Long = 0L,
    val hasPendingRows: Boolean = false,
) {
    /**
     * The available figure: the posted balance plus BOTH kinds of unposted
     * movement. **Every surface must read it from here rather than adding the
     * terms itself.**
     *
     * That rule was written the hard way. `get_balance` and
     * `ui.ledger.AccountBalanceRow` each computed this independently, and on
     * 2026-08-07 the UI's copy was short the `pendingDeltaCents` term: Kevin
     * logged three pending charges totalling 123.79, the note under the figure
     * changed to mention them, and the headline stayed at 440.68 while his bank
     * showed 316.89. The voice tool had it right the whole time. Two call sites
     * computing one definition is the bug; one property with two callers is the
     * fix.
     *
     * [balanceCents] being null is NOT zero - a format that never prints a
     * running balance (Bank of America's card layout) still has real unposted
     * movement, and this returns that movement alone. Ask [hasAnyFigure] first;
     * when it is false there is genuinely nothing to state and the surface must
     * render "not stated", never `0.00`.
     */
    val availableCents: Long
        get() = (balanceCents ?: 0L) + provisionalDeltaCents + pendingDeltaCents

    /**
     * True when there is any figure at all worth showing - a printed balance,
     * file-derived provisional activity, or voice-logged pending rows. False
     * means "not stated", which is a real state distinct from zero.
     */
    val hasAnyFigure: Boolean
        get() = balanceCents != null || isProvisional || hasPendingRows

    /** True when [availableCents] contains anything unconfirmed, so the surface must say so in words. */
    val isUnconfirmed: Boolean
        get() = isProvisional || hasPendingRows
}

/**
 * Collapses [balances] on [sameCard] so the same physical card - its monthly
 * PDF's full printed account id and its mid-cycle CSV export's filename
 * last-4 (ticket 12 §0) - renders as one row, not two. This is the third of
 * the three places §0 names the suffix relation must be used; the other two
 * (the supersede delete and the per-account provisional-delta sum) are
 * already suffix-aware, now inside [LedgerController]'s own in-memory reads
 * (cutover 3) rather than at the SQL layer.
 *
 * **Call this at the render site, never inside [LedgerController.accountBalances]**
 * (review finding 4 - spec drift, corrected). Ticket 12 §6 literally says
 * "`BalancesSection` groups on `sameCard`" - it does not say `accountBalances`
 * does. Grouping used to live inside `accountBalances` and it broke a second
 * consumer of that same list silently: `ui.LedgerScreen`'s
 * `knownAccountIds = state.balances.map { it.accountId }.distinct()` drives
 * the one-tap "map this Drive folder to an existing account" chips, which
 * exist specifically so mapping a folder is a tap instead of a typo-prone
 * retype. An id [groupAccountBalances] absorbed into another cluster stopped
 * appearing as a chip - the exact account someone would most want to tap
 * (one already carrying rows) silently lost its shortcut. The fix is call-site
 * discipline, not a smarter grouping function: `accountBalances` returns the
 * ungrouped one-row-per-`accountId` list, and `ui.LedgerScreen`'s
 * `BalancesSection(state.balances)` call site wraps it in
 * `groupAccountBalances(...)` right before rendering, matching the ticket.
 *
 * The representative kept per cluster is whichever entry states a real
 * printed [AccountBalance.balanceCents] - there is at most one in practice,
 * since a provisional-only account id (a card's bare filename last-4, before
 * its own PDF has ever been imported) never has one. Falls back to the entry
 * with the longer `accountId` (the full printed PAN reads better in the UI
 * than a bare last-4) when no cluster member has a printed balance at all.
 */
fun groupAccountBalances(balances: List<AccountBalance>): List<AccountBalance> {
    val clusters = mutableListOf<MutableList<AccountBalance>>()
    for (balance in balances) {
        // Currency guard (review finding 3): accountId is free text from the
        // folder-mapping field, not a bank-assigned identifier, so two
        // completely different accounts can share a last-4 by pure
        // coincidence of naming - "BOFA-CHECKING" and "DBS-CHECKING" both end
        // "KING". Without also requiring the same currency, sameCard alone
        // would merge them: one account vanishes from the list and the
        // survivor shows the wrong currency for its combined figure, which
        // is exactly what this function's own doc comment (and the
        // BalancesSection UI text "Not combined. No exchange rate is
        // applied.") promises never happens.
        val cluster = clusters.firstOrNull { existing ->
            existing.any { it.currency == balance.currency && sameCard(it.accountId, balance.accountId) }
        }
        if (cluster != null) cluster.add(balance) else clusters.add(mutableListOf(balance))
    }
    return clusters.map { cluster ->
        if (cluster.size == 1) return@map cluster.first()
        val representative = cluster.firstOrNull { it.balanceCents != null }
            ?: cluster.maxBy { it.accountId.length }
        // BLOCKING review fix: use ONLY representative.provisionalDeltaCents,
        // never a sibling's. Each cluster member's delta was computed in
        // accountBalances() against its OWN anchor date - the entry with a
        // printed balance is anchored at that balance's own txnDate, an
        // entry with no printed balance at all (provisional-only, e.g. the
        // bare filename-last-4 accountId before its card's PDF ever landed)
        // is anchored at Long.MIN_VALUE, i.e. "every UNRECONCILED row for
        // this id counts". Picking whichever cluster member's delta happened
        // to be non-zero (the old `firstOrNull { != 0L }`) paired
        // representative.balanceCents - anchored at date D - with a
        // sibling's delta - anchored at MIN_VALUE - which double-counts any
        // provisional row dated before D: it is already inside
        // representative.balanceCents (that's what "anchored at D" means)
        // AND gets re-added because the sibling's sum never excluded it.
        // representative.provisionalDeltaCents is the only value anchored to
        // representative.balanceCents's own date, so it is the only one that
        // composes correctly with it.
        val provisionalDeltaCents = representative.provisionalDeltaCents
        // pendingDeltaCents does NOT get summed across the cluster the way it
        // might look like it should from provisionalDeltaCents's shape just
        // above - it takes the OPPOSITE fix for a reason specific to its own
        // query. LedgerTransactionDao.pendingDeltaCents (now retired - see
        // cutover 3) had no per-accountId date anchor at all, only a
        // suffix+currency match, so every member of a same-last-4/
        // same-currency cluster already computed the IDENTICAL total in
        // accountBalances() - summing them here would multiply a single
        // driver-logged charge by the cluster's own size. representative's
        // value already IS the cluster's total, exactly the same reasoning
        // provisionalDeltaCents's "use ONLY representative, never a sibling's"
        // fix above states, just arriving at "don't touch it" instead of
        // "pick one" because there is nothing here that could differ member
        // to member in the first place.
        representative.copy(
            provisionalDeltaCents = provisionalDeltaCents,
            isProvisional = provisionalDeltaCents != 0L,
        )
    }
}
