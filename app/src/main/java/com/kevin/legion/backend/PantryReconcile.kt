package com.kevin.legion.backend

import android.content.Context
import androidx.room.withTransaction
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.pantry.PantryAspectSeeder
import com.kevin.legion.pantry.PantryReceiptAgent
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The one-time (and re-runnable) Phase 4 step 1/2 job for Pantry
 * (`.scratch/backend-erp/issues/05-migration-path.md`, "Each aspect follows the identical
 * shape... 1. Upload... 2. Diff until clean") - the aspect-2-of-5 sibling of [PlacesReconcile],
 * same shape, one addition [PlacesReconcile] did not need (see [run]'s own doc comment).
 *
 * **Never touches, trashes, or deletes an engine record.** The engine stays the source of truth
 * until [Report.isClean] - deleting the engine's copy is a LATER phase (phase 6).
 */
object PantryReconcile {

    /**
     * @param engineCount how many active engine `Receipt` records this aspect had, BEFORE the
     *   local re-check below sorts them into reconciling / unreconciled-but-uploaded / rejected.
     * @param uploaded how many of the RECONCILING receipts were genuinely NEW server-side this run
     *   (a re-run reporting `0` is the expected, idempotent outcome per ticket 05 phase 4 step 1 -
     *   unlike [PlacesReconcile.Report.uploaded], which counts every successful upsert call because
     *   places are mutable, [PantryBackend.uploadMigratedReceipt] returns `false` for "already
     *   migrated", and that `false` does NOT increment this count). A failed upload still
     *   short-circuits into [Result.failure] rather than returning a report with a lower count.
     * @param uploadedUnreconciled one entry per stored receipt whose figures fall SHORT of the
     *   printed total when re-checked against [PantryReceiptAgent]'s own arithmetic - CLAUDE.md
     *   section 4 rule 7's 2026-08-26 amendment (ticket 08). **Renamed from `skippedUnreconciled`,
     *   because these rows are no longer skipped** - they are uploaded with
     *   `provenance = 'UNRECONCILED'` and a non-null `unaccounted_cents`, same as [uploaded]'s
     *   count, but reported here SEPARATELY so a caller can never mistake one for an ordinary
     *   reconciling row. A re-run's idempotency here rides on
     *   [PantryBackend.uploadMigratedReceipt]'s own existing-row check, exactly like [uploaded].
     * @param rejectedOveraccounted one entry per stored receipt whose lines sum to MORE than the
     *   printed total - a different and more alarming shape than falling short (money the receipt
     *   did not charge would be invented as an "explanation"), so unlike [uploadedUnreconciled]
     *   these are never uploaded at all, same posture the old `skippedUnreconciled` had for every
     *   failure shape before this ticket.
     * @param serverCountAfter the server's active receipt count after the upload.
     * @param replicaCountAfter the Room replica's active receipt count after being refreshed from
     *   the server's active set.
     * @param onlyOnEngine `records.guid`s the engine has (reconciling or not) that the server does
     *   not - non-empty after a clean run only for entries also present in
     *   [rejectedOveraccounted] (an over-accounted receipt is never uploaded, so it is expected to
     *   show up here rather than being hidden; an entry in [uploadedUnreconciled] IS uploaded, so
     *   it does not appear here).
     * @param onlyOnServer `origin_guid`s the server has that the engine does not - a migrated row
     *   whose engine original has since vanished, or a stale engine read. Server rows created
     *   directly through [PantryBackend.commitReceipt] carry no `origin_guid` and are correctly
     *   excluded from this comparison entirely (see [PantryBackend.uploadMigratedReceipt]'s own doc
     *   comment).
     */
    data class Report(
        val engineCount: Int,
        val uploaded: Int,
        val uploadedUnreconciled: List<String>,
        val rejectedOveraccounted: List<String>,
        val serverCountAfter: Int,
        val replicaCountAfter: Int,
        val onlyOnEngine: List<String>,
        val onlyOnServer: List<String>,
    ) {
        /** True only when every reconciling AND every unreconciled-but-uploaded engine receipt
         * landed on the server and nothing is left over on either side. A non-empty
         * [rejectedOveraccounted] always keeps this false - an over-accounted receipt this
         * migration refused to trust is not a clean diff, it is a named exception. An
         * [uploadedUnreconciled] entry does NOT keep this false on its own - it genuinely landed
         * on the server, just under a different provenance. */
        val isClean: Boolean get() = onlyOnEngine.isEmpty() && onlyOnServer.isEmpty()
    }

    private data class EngineLineItem(
        val guid: String,
        val name: String,
        val quantity: Double,
        val unitPriceCents: Long?,
        val totalPriceCents: Long,
        val estimatedCaloriesKcal: Double?,
        val estimatedProteinG: Double?,
        val estimatedCarbsG: Double?,
        val estimatedFatG: Double?,
    )

    private data class EngineReceipt(
        val guid: String,
        val store: String,
        val purchaseDateEpochMs: Long,
        val currency: String,
        val totalCents: Long,
        val subtotalCents: Long?,
        val taxCents: Long?,
        val otherChargesCents: Long?,
        val items: List<EngineLineItem>,
    )

    /**
     * **The one addition [PlacesReconcile] did not need.** Every stored engine receipt is
     * re-checked against [PantryReceiptAgent.reconciliationFailure] - the SAME arithmetic the gate
     * ran at extraction time - BEFORE it is ever uploaded. A place has no arithmetic to go stale;
     * a receipt's stored total/subtotal/tax/items came from an LLM extraction that, however
     * unlikely, could have been hand-edited or corrupted since. Re-running the gate here turns this
     * migration into a verification pass rather than a bulk trust exercise (CLAUDE.md section 4
     * rule 2 is exactly why this is not optional). **Never a hard abort.**
     *
     * **AMENDED 2026-08-26 (CLAUDE.md section 4 rule 7, ticket 08).** A receipt that fails the
     * re-check is no longer a uniform skip. Its OWN shortfall decides what happens:
     * - `totalCents > sum(lines)`: the receipt charged more than its lines explain - the exact
     *   shape of the three real rows this ticket exists for (a legacy table with no subtotal/tax
     *   columns, so the gate's own anchors were never persisted). Uploaded anyway, tagged
     *   `provenance = 'UNRECONCILED'` with `unaccounted_cents` set to the residual, reported in
     *   [Report.uploadedUnreconciled] - never silently folded into [Report.uploaded].
     * - `totalCents <= sum(lines)`: a different and more alarming shape (money the lines claim that
     *   the receipt never charged, or - rarer - some other anchor failing while items and total
     *   already agree). Never uploaded, and never explained away with a negative or zero
     *   `unaccounted_cents` (the column's own check constraint forbids zero, and a negative value
     *   would silently hide an overcounted receipt as an undercounted one). Reported in
     *   [Report.rejectedOveraccounted].
     */
    suspend fun run(context: Context, backend: PantryBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)
        val sch = PantryAspectSeeder.ensureSeeded(context)

        val receiptRecords = db.engineRecordDao().activeByRecordType(sch.receipt.recordTypeId)
        val lineItemRecords = db.engineRecordDao().activeByRecordType(sch.lineItem.recordTypeId)

        val itemsByReceiptEngineId = lineItemRecords.groupBy { record ->
            PayloadCodec.readReferenceId(JSONObject(record.payload), sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT))
        }

        val engineReceipts = receiptRecords.mapNotNull { record ->
            val payload = JSONObject(record.payload)
            fun s(name: String) = PayloadCodec.readString(payload, sch.receipt.fieldIds.getValue(name))
            fun l(name: String) = PayloadCodec.readLong(payload, sch.receipt.fieldIds.getValue(name))

            val store = s(PantryAspectSeeder.FIELD_STORE) ?: return@mapNotNull null
            val currency = s(PantryAspectSeeder.FIELD_CURRENCY) ?: return@mapNotNull null
            val total = l(PantryAspectSeeder.FIELD_TOTAL) ?: return@mapNotNull null

            val items = (itemsByReceiptEngineId[record.id] ?: emptyList()).map { itemRecord ->
                val itemPayload = JSONObject(itemRecord.payload)
                fun si(name: String) = PayloadCodec.readString(itemPayload, sch.lineItem.fieldIds.getValue(name))
                fun li(name: String) = PayloadCodec.readLong(itemPayload, sch.lineItem.fieldIds.getValue(name))
                fun di(name: String) = PayloadCodec.readDouble(itemPayload, sch.lineItem.fieldIds.getValue(name))
                EngineLineItem(
                    guid = itemRecord.guid,
                    name = si(PantryAspectSeeder.FIELD_NAME).orEmpty(),
                    quantity = di(PantryAspectSeeder.FIELD_QUANTITY) ?: 1.0,
                    unitPriceCents = li(PantryAspectSeeder.FIELD_UNIT_PRICE),
                    totalPriceCents = li(PantryAspectSeeder.FIELD_TOTAL_PRICE) ?: 0L,
                    estimatedCaloriesKcal = di(PantryAspectSeeder.FIELD_ESTIMATED_CALORIES_KCAL),
                    estimatedProteinG = di(PantryAspectSeeder.FIELD_ESTIMATED_PROTEIN_G),
                    estimatedCarbsG = di(PantryAspectSeeder.FIELD_ESTIMATED_CARBS_G),
                    estimatedFatG = di(PantryAspectSeeder.FIELD_ESTIMATED_FAT_G),
                )
            }

            EngineReceipt(
                guid = record.guid,
                store = store,
                purchaseDateEpochMs = l(PantryAspectSeeder.FIELD_PURCHASE_DATE) ?: record.createdAt,
                currency = currency,
                totalCents = total,
                subtotalCents = l(PantryAspectSeeder.FIELD_SUBTOTAL),
                taxCents = l(PantryAspectSeeder.FIELD_TAX),
                otherChargesCents = l(PantryAspectSeeder.FIELD_OTHER_CHARGES),
                items = items,
            )
        }

        // The owed re-check, ahead of any network call - see this function's own doc comment.
        // Three outcomes, never two: a receipt either reconciles cleanly (uploaded as
        // LLM_RECONCILED, unaccountedCents null), falls short (uploaded anyway as UNRECONCILED
        // with the residual named), or its lines exceed its total (rejected outright - see the
        // doc comment above for why that shape is never given an unaccounted_cents value).
        val reconciling = mutableListOf<EngineReceipt>()
        val unreconciledShortfall = mutableListOf<Pair<EngineReceipt, Long>>()
        val rejected = mutableListOf<String>()
        for (receipt in engineReceipts) {
            val currency = LedgerCurrency.entries.firstOrNull { it.name == receipt.currency } ?: LedgerCurrency.USD
            val itemsTotal = receipt.items.sumOf { it.totalPriceCents }
            val failure = PantryReceiptAgent.reconciliationFailure(
                itemCount = receipt.items.size,
                itemsTotalCents = itemsTotal,
                subtotalCents = receipt.subtotalCents,
                taxCents = receipt.taxCents,
                otherChargesCents = receipt.otherChargesCents,
                totalCents = receipt.totalCents,
                currency = currency,
            )
            if (failure == null) {
                reconciling.add(receipt)
                continue
            }
            // The residual is deliberately computed from totalCents and the line items alone,
            // never from [failure]'s wording or from subtotal/tax - it is the one number rule 7's
            // amendment authorises storing, and it must stand on the same two anchors the ticket
            // names (printed total, captured lines), nothing else.
            val shortfall = receipt.totalCents - itemsTotal
            if (shortfall > 0) {
                unreconciledShortfall.add(receipt to shortfall)
            } else {
                // shortfall <= 0: lines meet or exceed the total while some OTHER anchor still
                // failed. Never uploaded, and never given a zero or negative unaccounted_cents -
                // see this function's own doc comment.
                rejected.add("${receipt.store} (${receipt.guid}): $failure")
            }
        }

        // Shared by both upload loops below - the only difference between a reconciling receipt
        // and an unreconciled-shortfall one is [unaccountedCents], never the line-item shape.
        fun toMigrated(receipt: EngineReceipt, unaccountedCents: Long?) = MigratedReceipt(
            originGuid = receipt.guid,
            store = receipt.store,
            purchaseDateEpochMs = receipt.purchaseDateEpochMs,
            currency = receipt.currency,
            totalCents = receipt.totalCents,
            subtotalCents = receipt.subtotalCents,
            taxCents = receipt.taxCents,
            otherChargesCents = receipt.otherChargesCents,
            unaccountedCents = unaccountedCents,
            lines = receipt.items.map { item ->
                MigratedReceiptLine(
                    originGuid = item.guid,
                    name = item.name,
                    quantity = item.quantity,
                    unitPriceCents = item.unitPriceCents,
                    totalPriceCents = item.totalPriceCents,
                    estimatedCaloriesKcal = item.estimatedCaloriesKcal,
                    estimatedProteinG = item.estimatedProteinG,
                    estimatedCarbsG = item.estimatedCarbsG,
                    estimatedFatG = item.estimatedFatG,
                )
            },
        )

        var uploaded = 0
        for (receipt in reconciling) {
            val migrated = toMigrated(receipt, unaccountedCents = null)
            // Unlike PlacesReconcile's upsert (always a meaningful write, mutable rows), a `false`
            // here means "already migrated on a previous run" - a real no-op, not a fresh upload,
            // so it must not inflate [uploaded] on a re-run (see Report.uploaded's own doc comment).
            val wasNewUpload = backend.uploadMigratedReceipt(migrated)
                .getOrElse { return Result.failure(it) }
            if (wasNewUpload) uploaded++
        }

        val uploadedUnreconciled = mutableListOf<String>()
        for ((receipt, shortfall) in unreconciledShortfall) {
            val migrated = toMigrated(receipt, unaccountedCents = shortfall)
            // Same idempotency shape as the loop above - a re-run against an already-migrated
            // unreconciled row is still a no-op, not a re-report.
            backend.uploadMigratedReceipt(migrated).getOrElse { return Result.failure(it) }
            uploadedUnreconciled.add(
                "${receipt.store} (${receipt.guid}): uploaded UNRECONCILED, ${receipt.currency} " +
                    "${shortfall}c unaccounted for (total ${receipt.totalCents}c, lines " +
                    "${receipt.totalCents - shortfall}c).",
            )
        }

        val serverReceipts = backend.fetchActiveReceipts().getOrElse { return Result.failure(it) }

        // Full clear-and-refill, not a per-row upsert - see PantryReceiptDao/PantryLineItemDao's
        // deleteAllForReplicaRefresh doc comments for why there is no natural key to upsert against.
        db.withTransaction {
            db.pantryReceiptDao().deleteAllForReplicaRefresh()
            db.pantryLineItemDao().deleteAllForReplicaRefresh()
            for (serverReceipt in serverReceipts) {
                val currency = LedgerCurrency.entries.firstOrNull { it.name == serverReceipt.currency } ?: LedgerCurrency.USD
                val localReceiptId = db.pantryReceiptDao().insert(
                    PantryReceipt(
                        store = serverReceipt.store,
                        purchaseDate = serverReceipt.purchaseDateEpochMs,
                        currency = currency,
                        totalCents = serverReceipt.totalCents,
                        sourceImagePath = "",
                        syncId = serverReceipt.serverId,
                        provenance = serverReceipt.provenance,
                        unaccountedCents = serverReceipt.unaccountedCents,
                    ),
                )
                db.pantryLineItemDao().insertAll(
                    serverReceipt.lines.map { line ->
                        PantryLineItem(
                            receiptId = localReceiptId,
                            name = line.name,
                            quantity = line.quantity,
                            unitPriceCents = line.unitPriceCents,
                            totalPriceCents = line.totalPriceCents,
                            caloriesKcal = line.estimatedCaloriesKcal?.toInt(),
                            proteinG = line.estimatedProteinG,
                            carbsG = line.estimatedCarbsG,
                            fatG = line.estimatedFatG,
                        )
                    },
                )
            }
        }

        val engineGuids = engineReceipts.map { it.guid }.toSet()
        val serverGuids = serverReceipts.mapNotNull { it.originGuid }.toSet()

        return Result.success(
            Report(
                engineCount = engineReceipts.size,
                uploaded = uploaded,
                uploadedUnreconciled = uploadedUnreconciled,
                rejectedOveraccounted = rejected,
                serverCountAfter = serverReceipts.size,
                replicaCountAfter = db.pantryReceiptDao().getAll().size,
                onlyOnEngine = (engineGuids - serverGuids).sorted(),
                onlyOnServer = (serverGuids - engineGuids).sorted(),
            ),
        )
    }

    private val autoRunScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastAutoRunAt = 0L

    /** Same floor and reasoning as [LedgerReconcile]'s own `AUTO_RUN_MIN_INTERVAL_MS` - a
     * full-table scan of every stored receipt, not a queue drain, so a floor exists to stop every
     * foreground return from re-scanning the whole engine `Receipt` set and re-running the
     * reconciliation-gate arithmetic against each one. */
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** The throttle half of [autoRunGate], pulled out as a pure predicate so a test can exercise
     *  the floor's own arithmetic directly against [setLastAutoRunAtForTest] - same reasoning as
     *  [ObdSampleReconcile.isThrottled]'s own doc comment (a configured [SupabaseClient] throws
     *  under Robolectric). */
    internal fun isThrottled(now: Long): Boolean = now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS

    /** Test-only escape hatch for [isThrottled]'s own test - never called from [autoRunGate] or
     *  [maybeAutoRun]. */
    internal fun setLastAutoRunAtForTest(atMs: Long) {
        lastAutoRunAt = atMs
    }

    /**
     * The synchronous half of [maybeAutoRun] - the throttle floor ([isThrottled]) and the "is
     * Supabase even configured" check, extracted so a test can assert on this gate's own return
     * value directly. [lastAutoRunAt] is reserved here, before [maybeAutoRun] ever launches
     * anything async - the "reserved before any awaiting" property that makes a cold start
     * immediately followed by a foreground resume one run, not two.
     */
    internal fun autoRunGate(context: Context, now: Long = System.currentTimeMillis()): SupabaseClient? {
        if (isThrottled(now)) return null
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return null
        lastAutoRunAt = now
        return client
    }

    /**
     * The async half of [maybeAutoRun] - resolves who is signed in via
     * [SupabaseAuth.resolveSignedInUserId] (never the raw `currentUserId() == null` guard) and,
     * if anyone is, runs [run] and reports the result via [MidnightEvents]. Extracted so a test
     * can drive "signed out" and "signed in" directly against a fake [SupabaseAuth] gatewayProvider.
     * Fails to a logged [MidnightEvents] event, never a crash or a dialog, matching every sibling
     * `maybeAutoRun`'s posture.
     */
    internal suspend fun runIfSignedIn(context: Context, backend: PantryBackend, auth: SupabaseAuth) {
        try {
            if (auth.resolveSignedInUserId() == null) return
            val report = run(context, backend).getOrThrow()
            MidnightEvents.pantryAutoReconcileSucceeded(
                report.uploaded,
                report.uploadedUnreconciled.size,
                report.rejectedOveraccounted.size,
                report.serverCountAfter,
            )
        } catch (e: Exception) {
            MidnightEvents.pantryAutoReconcileFailed(e)
        }
    }

    /**
     * `MainActivity.onResume`'s hook - this reconcile's only production caller before this ticket
     * was a Settings row nobody had wired up to run automatically (the `BackendMigrationScreen`
     * migration row). No-ops silently, with a logged breadcrumb rather than a dialog or a crash,
     * when Supabase is not configured or nobody is signed in - see [autoRunGate]/[runIfSignedIn]
     * for the two halves this delegates to. Fire-and-forget on [autoRunScope]; never suspends the
     * caller.
     */
    fun maybeAutoRun(context: Context) {
        val client = autoRunGate(context) ?: return
        val app = context.applicationContext
        autoRunScope.launch {
            runIfSignedIn(app, SupabasePantryBackend(client), SupabaseAuth(app))
        }
    }
}
