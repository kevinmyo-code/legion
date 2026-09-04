package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Install-scoped high-water mark for [PantryReceiptsSync.pull] - one table (`receipts`, lines ride
 * along with their header), same single-key shape as [LedgerTransactionsPullCursor]. `created_at`
 * is the clock; see this file's own class doc for why there is no `updated_at` to prefer.
 */
internal object PantryReceiptsPullCursor {
    private const val PREFS = "pantry_receipts_pull_cursor"
    private const val KEY = "receipts"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPulledAtMs(context: Context): Long = prefs(context).getLong(KEY, 0L)

    fun advance(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY, atMs).apply()
    }
}

/**
 * Live-sync's pantry-RECEIPTS pull (`.scratch/live-sync/map.md`'s ticket "give LEGION's pantry and
 * ledger transactions a pull"). Same append-only, insert-if-absent shape as
 * [LedgerTransactionsSync] - see that object's own class doc for the full reasoning, which applies
 * here without modification: `receipts`/`receipt_line_items` carry the identical
 * `forbid_mutation_of_facts` trigger (`supabase/migrations/20260825000300_aspect_ledger_pantry.sql`),
 * so there is no `updated_at`, no `deleted_at`, and a rule-7 supersession of an `UNRECONCILED`
 * receipt is a genuine DELETE this pull cannot observe - named, not fixed, same as
 * [LedgerTransactionsSync]'s own gap.
 *
 * **Identity, in the absence of a local `serverId` column.** [PantryReceipt] already carries a
 * `syncId` that DOUBLES as the server id for any receipt committed through the live
 * [PantryBackend.commitReceipt] path -
 * [com.kevin.legion.pantry.PantryController.commitReceiptRemote] sets `syncId = outcome.receiptId`
 * on every successful commit (`PantryController.kt:373`). A migrated (pre-cutover) receipt instead
 * carries its ENGINE guid as `syncId`, matching `remote.originGuid`
 * ([com.kevin.legion.backend.PantryReconcile]'s own upload direction). So the same two-way match
 * [LedgerTransactionsSync.pull] uses applies here unmodified: `remote.serverId` OR
 * (`remote.originGuid` when non-null) already present in [PantryReceiptDao.getAllSyncIds] means this
 * device has already seen the receipt, in either shape. A genuinely new row gets
 * `syncId = remote.serverId`, so it round-trips through this same check on the next pull without a
 * second column.
 *
 * **Lines are never matched individually - only ever inserted alongside a receipt this pull is
 * inserting for the FIRST time.** [com.kevin.legion.backend.RemoteReceiptLine] carries no id or
 * guid of its own (`receipt_line_items.origin_guid`'s own migration comment: "no natural key even
 * in principle"), so there is nothing to compare an existing line against - and nothing to compare,
 * because a receipt already known locally already has every line it will ever have (the same
 * append-only fact that makes the header insert-if-absent). This mirrors
 * [FleetSync]'s own "insert lines only when the parent vehicle/receipt is new" posture for its
 * six append-only tables.
 *
 * **Provenance and `unaccountedCents` both survive exactly, or the receipt is refused.**
 * [PantryReceipt.provenance] is a plain `String`, not an enum (unlike ledger's [IngestMethod]), so
 * this validates against the two values the schema's own check constraint allows
 * (`LLM_RECONCILED`/`UNRECONCILED` - `receipts_not_provisional` forbids a header from EVER being
 * `DETERMINISTIC` server-side, matching pantry's "no deterministic extraction path" posture) rather
 * than storing an unrecognised string as fact. `unaccountedCents` is carried through byte-for-byte,
 * never derived, never zeroed - CLAUDE.md section 4 rule 7's amendment.
 *
 * **Does NOT restore `subtotalCents`/`taxCents`/`otherChargesCents`.** [RemoteReceipt] does not
 * carry them - [com.kevin.legion.backend.SupabasePantryBackend]'s own `ReceiptRowDto` never decoded
 * those three columns, on either the existing `fetchActiveReceipts` or this pull's new
 * `fetchChangedReceiptsSince` - so a receipt this pull restores has those three fields null even
 * though the server itself may hold them. A genuine, narrow, pre-existing gap this ticket did not
 * introduce and was not asked to close; named here rather than silently inherited.
 *
 * **Does NOT restore the photo.** [RemoteReceipt] carries no photo reference at all (the ticket's
 * own instruction: "do not pull photos in this ticket"). A restored receipt gets
 * `photoObjectPath = null`, `sourceImagePath = ""` - the same "no local file" shape
 * [PantryReceipt.sourceImagePath]'s own doc comment already describes as inert provenance text, not
 * a live reference. Traced: neither `ui/pantry/PantryRows.kt` nor `ui/PantryScreen.kt` renders an
 * image off either field today, so a restored receipt with no photo shows no broken image and makes
 * no claim a photo exists - it simply has nothing to show, honestly.
 */
object PantryReceiptsSync {

    data class PullReport(
        val inserted: Int,
        val alreadyPresent: Int,
        val unrecognizedProvenance: List<String>,
        val linesInserted: Int,
    )

    private val VALID_PROVENANCE = setOf("LLM_RECONCILED", "UNRECONCILED")

    suspend fun pull(context: Context, backend: PantryBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = PantryReceiptsPullCursor.lastPulledAtMs(context)
        val remote = backend.fetchChangedReceiptsSince(sinceMs).getOrThrow()
        val known = db.pantryReceiptDao().getAllSyncIds().toSet()

        var inserted = 0
        var alreadyPresent = 0
        var linesInserted = 0
        val unrecognized = mutableListOf<String>()

        for (r in remote) {
            val seenByServerId = r.serverId in known
            val seenByOriginGuid = r.originGuid != null && r.originGuid in known
            if (seenByServerId || seenByOriginGuid) {
                alreadyPresent++
                continue
            }

            if (r.provenance !in VALID_PROVENANCE) {
                unrecognized.add("${r.store} (${r.serverId}): unrecognised provenance '${r.provenance}' - not inserted")
                continue
            }

            val localReceiptId = db.pantryReceiptDao().insert(
                PantryReceipt(
                    store = r.store,
                    purchaseDate = r.purchaseDateEpochMs,
                    currency = LedgerCurrency.valueOf(r.currency),
                    totalCents = r.totalCents,
                    // No local file for a row this device is downloading, not photographing - see
                    // this file's own class doc on why that is an honest empty, never a broken
                    // reference.
                    sourceImagePath = "",
                    syncId = r.serverId,
                    provenance = r.provenance,
                    unaccountedCents = r.unaccountedCents,
                    // Not carried by RemoteReceipt - see this file's own class doc's named gap.
                    subtotalCents = null,
                    taxCents = null,
                    otherChargesCents = null,
                    // Photos are out of this ticket's scope - see this file's own class doc.
                    photoObjectPath = null,
                ),
            )
            if (r.lines.isNotEmpty()) {
                db.pantryLineItemDao().insertAll(
                    r.lines.map { line ->
                        PantryLineItem(
                            receiptId = localReceiptId,
                            name = line.name,
                            quantity = line.quantity,
                            unitPriceCents = line.unitPriceCents,
                            totalPriceCents = line.totalPriceCents,
                            // Estimates, never fact - CLAUDE.md section 4 rule 5. caloriesKcal is
                            // stored as Int locally (PantryLineItem's own long-standing shape); the
                            // server's estimated_calories_kcal is numeric, so this rounds rather
                            // than truncates.
                            caloriesKcal = line.estimatedCaloriesKcal?.roundToInt(),
                            proteinG = line.estimatedProteinG,
                            carbsG = line.estimatedCarbsG,
                            fatG = line.estimatedFatG,
                        )
                    },
                )
                linesInserted += r.lines.size
            }
            inserted++
        }

        remote.maxOfOrNull { it.createdAtMs }?.let { PantryReceiptsPullCursor.advance(context, it) }
        return PullReport(inserted, alreadyPresent, unrecognized, linesInserted)
    }

    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastAutoPullAt = 0L

    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val AUTO_PULL_RETRY_DELAY_MS = 1_000L

    /** Thin delegation to [SupabaseAuth.resolveSignedInUserId], same shape as
     * [LedgerTransactionsSync.resolveUserIdForAutoPull] - `internal` so a test can drive it
     * directly. */
    internal suspend fun resolveUserIdForAutoPull(
        auth: SupabaseAuth,
        retryDelayMs: Long = AUTO_PULL_RETRY_DELAY_MS,
    ): String? = auth.resolveSignedInUserId(retryDelayMs)

    /** `MainActivity.onResume`'s hook. No-ops silently when Supabase is not configured or nobody is
     * signed in. No outbox to drain first - [PantryController]'s live write path
     * ([PantryBackend.commitReceipt]) is synchronous, never queued, matching
     * [FleetSync.maybeAutoPull]'s own "nothing this pull needs to wait on" reasoning. */
    fun maybeAutoPull(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoPullAt < AUTO_PULL_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        lastAutoPullAt = now
        autoPullScope.launch {
            try {
                val userId = resolveUserIdForAutoPull(SupabaseAuth(app))
                if (userId == null) return@launch
                val report = pull(app, SupabasePantryBackend(client))
                MidnightEvents.pantryReceiptsAutoPullSucceeded(
                    report.inserted, report.alreadyPresent, report.unrecognizedProvenance.size, report.linesInserted,
                )
            } catch (e: Exception) {
                MidnightEvents.pantryReceiptsAutoPullFailed(e)
            }
        }
    }
}
