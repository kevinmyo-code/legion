package com.kevin.legion.pantry

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.kevin.legion.backend.CommitOutcome
import com.kevin.legion.backend.PantryBackend
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabasePantryBackend
import com.kevin.legion.data.PantryPhotoStore
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryCurrencyTotal
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryLineItemWithCurrency
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.data.local.PantryReceiptSummary
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.pantry.PantryAspectSeeder
import com.kevin.legion.ledger.IngestPipeline
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orchestrates receipt-photo ingestion - mirrors
 * [com.kevin.legion.ledger.LedgerController]'s shape.
 * `.claude/plans/wiggly-beaming-quasar.md`.
 *
 * **Cutover 2** (`docs/architecture/cutover2-2026-08-24.md`,
 * `.scratch/aspect-engine/issues/22-cutover-per-aspect.md`). Every function below keeps its
 * ORIGINAL signature and return type (`PantryReceipt`/`PantryLineItem`/`PantryCurrencyTotal`/
 * `PantryReceiptSummary` - the legacy Room entity/row shapes) so every caller - `ui/PantryScreen.kt`,
 * `service/LiveToolbox.kt`'s `list_recent_groceries`/`get_grocery_spend` - flips onto the engine
 * with this file, unchanged (ADR 0035: "the controller keeps its seam"). What changed is entirely
 * internal: reads and writes now go through [RecordStore] against the Pantry aspect's `Receipt`/
 * `LineItem` record types (`docs/architecture/wave2-carve-2026-08-23.md`'s field mapping, reused
 * verbatim - not reinvented), and every value object this file hands back is assembled in-memory
 * from an [EngineRecord]'s JSON payload, never a row actually persisted in the legacy
 * `pantry_receipts`/`pantry_line_items` tables. **Those two tables have ZERO writers from this file
 * after cutover** - see the cutover doc's reader/writer table for the full grep-proven account.
 *
 * **The reconciliation gate moves with it.** [PantryReceiptAgent.parseAndReconcile] is completely
 * untouched - it still runs entirely upstream of any write, and a [PantryIngestResult.Quarantined]
 * still causes [importReceipt] to write NOTHING, exactly as before cutover. What changed is only
 * where a [PantryIngestResult.Success] lands: through [RecordStore.create] inside one
 * [androidx.room.withTransaction] block (receipt first, then its line items, referencing the
 * receipt's real engine id), rather than straight `Insert` DAO calls. A `RecordStore` write CAN
 * fail post-gate (a corrupted field schema, a reference-validation edge) in a way the old plain
 * `Insert` calls structurally could not - CLAUDE.md §7's outcome-verb rule applies here just as much
 * as to a voice tool: [importReceipt] never reports success unless every write in the transaction
 * actually landed, and rolls the whole transaction back rather than leaving a receipt with some but
 * not all of its line items.
 *
 * **Backend-erp Phase 4, aspect 2 of 5** (`.scratch/backend-erp/issues/05-migration-path.md`).
 * DUAL-PATH, exactly [com.kevin.legion.location.PlaceController]'s shape - every function checks
 * [backend] first:
 * - **Not configured**: the ENGINE path above, completely unchanged - clone-and-run with zero
 *   Supabase setup still works.
 * - **Configured**: reads come from the Room [PantryReceipt]/[PantryLineItem] replica (cache-first,
 *   ticket 01 ruling 9); writes go straight to the server. **Two distinct write paths, kept apart
 *   on purpose:** a NEW receipt import always goes through [PantryBackend.commitReceipt], so
 *   CLAUDE.md §4's gate runs server-side exactly once - [importReceipt] never inserts a receipt
 *   directly when configured, because that would bypass the gate. The one-time migration upload of
 *   receipts already gated on-device is [com.kevin.legion.backend.PantryReconcile]'s job, entirely
 *   separate, keyed on `origin_guid` rather than the RPC. Room is written **only on a genuine server
 *   ACK**, never ahead of it, never on a failure - a failed write is reported as failed in words and
 *   leaves Room untouched (CLAUDE.md §7).
 *
 * **Photos stay on the device in this ticket.** `photo_object_path` is left NULL server-side;
 * [PantryPhotoStore] is unchanged. Supabase Storage is not installed
 * ([com.kevin.legion.backend.SupabaseClientProvider] only installs Auth and Postgrest) and wiring
 * it is its own piece of work, owed separately.
 */
object PantryController {
    private const val TAG = "PantryController"

    private fun db(context: Context) = CarDatabase.getDatabase(context)
    private fun store(context: Context): RecordStore {
        val database = db(context)
        return RecordStore(database.engineRecordDao(), database.fieldDefDao(), database.recordTypeDao())
    }

    /** Test seam: settable from a unit test so a [PantryBackend] fake can be injected without a
     * real [SupabaseClientProvider] / network - same mechanism as
     * [com.kevin.legion.location.PlaceController.backendOverride]. Defaults to null, meaning
     * "resolve normally"; production code never sets this. */
    @Volatile
    internal var backendOverride: PantryBackend? = null

    /** Resolves the active backend, or null when Supabase is not configured - the signal every
     * function below branches on. Never performs network I/O itself. */
    private fun backend(context: Context): PantryBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabasePantryBackend(client)
    }

    private fun receiptDao(context: Context) = db(context).pantryReceiptDao()
    private fun lineItemDao(context: Context) = db(context).pantryLineItemDao()

    private suspend fun schema(context: Context) = PantryAspectSeeder.ensureSeeded(context)

    // ---------------------------------------------------------------- engine <-> value-object bridge

    /** Matches `docs/architecture/wave2-carve-2026-08-23.md`'s field mapping table exactly -
     * nothing here invents a second mapping. Falls back to [LedgerCurrency.USD] only if a stored
     * currency string somehow doesn't match either enum name - should never happen (the field is a
     * locked `CHOICE` of exactly `["SGD", "USD"]`), but a read function must still return SOMETHING
     * rather than throw on a record it can't fully decode. */
    private fun toReceipt(record: EngineRecord, fieldIds: Map<String, Long>): PantryReceipt {
        val payload = JSONObject(record.payload)
        fun s(name: String) = PayloadCodec.readString(payload, fieldIds.getValue(name))
        fun l(name: String) = PayloadCodec.readLong(payload, fieldIds.getValue(name))
        val currency = LedgerCurrency.entries.firstOrNull { it.name == s(PantryAspectSeeder.FIELD_CURRENCY) }
            ?: LedgerCurrency.USD
        return PantryReceipt(
            id = record.id,
            store = s(PantryAspectSeeder.FIELD_STORE).orEmpty(),
            purchaseDate = l(PantryAspectSeeder.FIELD_PURCHASE_DATE) ?: record.createdAt,
            currency = currency,
            totalCents = l(PantryAspectSeeder.FIELD_TOTAL) ?: 0L,
            sourceImagePath = s(PantryAspectSeeder.FIELD_SOURCE_IMAGE_PATH).orEmpty(),
            syncId = record.guid,
        )
    }

    private fun toLineItem(record: EngineRecord, fieldIds: Map<String, Long>): PantryLineItem {
        val payload = JSONObject(record.payload)
        fun s(name: String) = PayloadCodec.readString(payload, fieldIds.getValue(name))
        fun l(name: String) = PayloadCodec.readLong(payload, fieldIds.getValue(name))
        fun d(name: String) = PayloadCodec.readDouble(payload, fieldIds.getValue(name))
        return PantryLineItem(
            id = record.id,
            receiptId = PayloadCodec.readReferenceId(payload, fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT)) ?: 0L,
            name = s(PantryAspectSeeder.FIELD_NAME).orEmpty(),
            quantity = d(PantryAspectSeeder.FIELD_QUANTITY) ?: 1.0,
            unitPriceCents = l(PantryAspectSeeder.FIELD_UNIT_PRICE),
            totalPriceCents = l(PantryAspectSeeder.FIELD_TOTAL_PRICE) ?: 0L,
            caloriesKcal = d(PantryAspectSeeder.FIELD_ESTIMATED_CALORIES_KCAL)?.toInt(),
            proteinG = d(PantryAspectSeeder.FIELD_ESTIMATED_PROTEIN_G),
            carbsG = d(PantryAspectSeeder.FIELD_ESTIMATED_CARBS_G),
            fatG = d(PantryAspectSeeder.FIELD_ESTIMATED_FAT_G),
            syncId = record.guid,
        )
    }

    /** Every receipt - the one place every receipt read below funnels through. **Configured**:
     * reads the Room replica, never the network - cache-first (ticket 01 ruling 9). **Unconfigured**:
     * every non-trashed engine `Receipt` record, converted; exactly one query against the engine
     * per read, as before cutover 2. */
    private suspend fun allReceipts(context: Context): List<PantryReceipt> {
        if (backend(context) != null) return receiptDao(context).getAll()
        val sch = schema(context)
        return db(context).engineRecordDao().activeByRecordType(sch.receipt.recordTypeId)
            .map { toReceipt(it, sch.receipt.fieldIds) }
    }

    /** Every line item - same cache-first/engine split as [allReceipts]. */
    private suspend fun allLineItems(context: Context): List<PantryLineItem> {
        if (backend(context) != null) return lineItemDao(context).getAll()
        val sch = schema(context)
        return db(context).engineRecordDao().activeByRecordType(sch.lineItem.recordTypeId)
            .map { toLineItem(it, sch.lineItem.fieldIds) }
    }

    // ------------------------------------------------------------------------------------ ingestion

    /** Thrown only to force [androidx.room.withTransaction] to roll back the WHOLE receipt+items
     * write in [importReceipt] - Room's transaction helper rolls back on a thrown exception, never
     * on a plain early return, so a partial engine write (receipt landed, a later line item did
     * not) needs a real throw to undo, not just a guard clause. Caught immediately inside
     * [importReceipt] and never escapes it. */
    private class EngineWriteFailedException(val reason: String) : Exception()

    /**
     * Reads [imageFile] (already saved via [PantryPhotoStore]), extracts it
     * through [PantryReceiptAgent], and - only on a fully-reconciled result -
     * writes the receipt then its line items through [RecordStore] inside one
     * transaction (reference intact, provenance `LLM_RECONCILED`), deleting
     * the source photo. On a quarantine, the photo is kept so the driver can
     * inspect or retry without re-taking it. **On a genuine engine-write
     * failure after the gate already passed** (a real possibility post-cutover
     * that plain `Insert` calls never had - see this object's class doc), the
     * whole transaction rolls back, nothing is written, and the caller is told
     * in words that nothing was saved - never a false success (CLAUDE.md §7).
     *
     * **Configured**: the gate-passed [PantryIngestResult.Success] goes to
     * [PantryBackend.commitReceipt] instead of [writeReceipt] - CLAUDE.md §4's gate then runs a
     * SECOND time, server-side, against `public.commit_receipt` (ticket 05's "two distinct write
     * paths, and keeping them distinct is the point": a NEW import always goes through the RPC, so
     * the server-side gate is never bypassed by a direct insert). [writeReceipt] itself is
     * untouched and stays the unconfigured path's writer.
     */
    suspend fun importReceipt(context: Context, imageFile: File): PantryImportResult {
        val bytes = try {
            imageFile.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "failed to read ${imageFile.path}: ${e.message}")
            return PantryImportResult(success = false, message = "I couldn't read that photo - try again.")
        }

        return when (val result = PantryReceiptAgent.extract(bytes, imageFile.path)) {
            is PantryIngestResult.Quarantined -> PantryImportResult(success = false, message = result.reason)
            is PantryIngestResult.Success -> {
                val backend = backend(context)
                val written = if (backend != null) {
                    commitReceiptRemote(context, backend, bytes, result)
                } else {
                    writeReceipt(context, result)
                }
                if (written.success) PantryPhotoStore.delete(context, imageFile)
                written
            }
        }
    }

    /**
     * The network-free half of [importReceipt] - writes an already-gate-passed
     * [PantryIngestResult.Success] through [RecordStore] inside one transaction (receipt then its
     * line items, referencing the receipt's real engine id, provenance `LLM_RECONCILED`). Split out
     * as its own function, mirroring [PantryReceiptAgent.parseAndReconcile]'s own "network-free...
     * unit-tested directly" split (see that function's doc comment) - [PantryControllerTest]
     * exercises this directly with a hand-built [PantryIngestResult.Success], with no Gemini key or
     * network call needed, exactly as [PantryReceiptAgentTest] already does for the gate itself.
     *
     * Never called for a [PantryIngestResult.Quarantined] - [importReceipt]'s `when` only reaches
     * this branch after the gate has already passed, so "quarantine writes nothing" is enforced
     * structurally (there is no path from a `Quarantined` result to this function at all), not by a
     * runtime check inside it.
     */
    suspend fun writeReceipt(context: Context, result: PantryIngestResult.Success): PantryImportResult {
        val database = db(context)
        val sch = schema(context)
        val recordStore = store(context)
        var itemCount = 0

        try {
            database.withTransaction {
                val receiptResult = recordStore.create(
                    recordTypeId = sch.receipt.recordTypeId,
                    fieldValues = mapOf(
                        sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_STORE) to result.receipt.store,
                        sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_PURCHASE_DATE) to result.receipt.purchaseDate,
                        sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_CURRENCY) to result.receipt.currency.name,
                        sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL) to result.receipt.totalCents,
                        sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SOURCE_IMAGE_PATH) to result.receipt.sourceImagePath,
                        // Cutover 2's owed anchor persistence - see PantryAspectSeeder's own
                        // doc comment on these three fields.
                        sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SUBTOTAL) to result.subtotalCents,
                        sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TAX) to result.taxCents,
                        sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_OTHER_CHARGES) to result.otherChargesCents,
                    ),
                    provenance = RecordProvenance.LLM_RECONCILED,
                    guid = result.receipt.syncId,
                )
                val receiptId = (receiptResult as? RecordStore.WriteResult.Success)?.recordId
                    ?: throw EngineWriteFailedException(
                        "receipt: ${(receiptResult as RecordStore.WriteResult.Failure).reason}",
                    )

                for (item in result.items) {
                    val itemResult = recordStore.create(
                        recordTypeId = sch.lineItem.recordTypeId,
                        fieldValues = mapOf(
                            sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT) to receiptId,
                            sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_NAME) to item.name,
                            sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_QUANTITY) to item.quantity,
                            sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_UNIT_PRICE) to item.unitPriceCents,
                            sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL_PRICE) to item.totalPriceCents,
                            sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_CALORIES_KCAL) to item.caloriesKcal,
                            sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_PROTEIN_G) to item.proteinG,
                            sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_CARBS_G) to item.carbsG,
                            sch.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_FAT_G) to item.fatG,
                        ),
                        provenance = RecordProvenance.LLM_RECONCILED,
                        guid = item.syncId,
                    )
                    if (itemResult !is RecordStore.WriteResult.Success) {
                        throw EngineWriteFailedException(
                            "line item '${item.name}': ${(itemResult as RecordStore.WriteResult.Failure).reason}",
                        )
                    }
                    itemCount++
                }
            }
        } catch (e: EngineWriteFailedException) {
            Log.w(TAG, "writeReceipt: engine write failed after the gate passed, rolled back - ${e.reason}")
            return PantryImportResult(
                success = false,
                message = "This receipt's numbers checked out, but I couldn't save it - try again.",
            )
        }

        return PantryImportResult(
            success = true,
            message = "Logged $itemCount item(s) from ${result.receipt.store}.",
            itemCount = itemCount,
        )
    }

    /**
     * The CONFIGURED half of [importReceipt] - commits [result] through [PantryBackend.commitReceipt]
     * and writes the Room replica only on [CommitOutcome.Committed], the one branch that hands back
     * a real server id (ticket 01 ruling 9: never ahead of a genuine ACK). [CommitOutcome.Quarantined]
     * is the gate refusing, not a transport failure, and is reported with the SAME wording
     * [PantryReceiptAgent] would have produced for identical figures - never as "something went
     * wrong". [CommitOutcome.AlreadyCommitted] is a retried request that already landed; per that
     * outcome's own doc comment the server hands back no id for it, so this cannot write a fresh
     * Room row here either - the gap closes on the next [com.kevin.legion.backend.PantryReconcile]
     * pass or replica refresh, not by inventing one.
     *
     * `internal`, not `private` - the network-free half of the CONFIGURED path, exercised directly
     * by `PantryControllerBackendTest` with a [com.kevin.legion.backend.PantryBackend] fake, same
     * posture as [writeReceipt] being the network-free half of the unconfigured one.
     */
    internal suspend fun commitReceiptRemote(
        context: Context,
        backend: PantryBackend,
        bytes: ByteArray,
        result: PantryIngestResult.Success,
    ): PantryImportResult {
        val payload = buildCommitReceiptPayload(bytes, result)
        val outcome = backend.commitReceipt(payload).getOrElse {
            Log.w(TAG, "commitReceiptRemote: request failed - ${it.message}")
            return PantryImportResult(
                success = false,
                message = "This receipt's numbers checked out, but I couldn't reach the server to save it - try again.",
            )
        }

        return when (outcome) {
            is CommitOutcome.Quarantined -> PantryImportResult(success = false, message = outcome.reason)
            is CommitOutcome.AlreadyCommitted -> PantryImportResult(
                success = true,
                message = "This receipt was already logged.",
            )
            is CommitOutcome.Committed -> {
                // Room is written ONLY here, after a genuine server ACK - never ahead of it.
                val localReceiptId = receiptDao(context).insert(
                    PantryReceipt(
                        store = result.receipt.store,
                        purchaseDate = result.receipt.purchaseDate,
                        currency = result.receipt.currency,
                        totalCents = result.receipt.totalCents,
                        sourceImagePath = result.receipt.sourceImagePath,
                        syncId = outcome.receiptId,
                    ),
                )
                lineItemDao(context).insertAll(result.items.map { it.copy(receiptId = localReceiptId) })
                PantryImportResult(
                    success = true,
                    message = "Logged ${result.items.size} item(s) from ${result.receipt.store}.",
                    itemCount = result.items.size,
                )
            }
        }
    }

    /**
     * The JSON body `public.commit_receipt(payload jsonb)` expects
     * (`supabase/migrations/20260825000700_commit_receipt_rpc.sql`), built from a gate-passed
     * [result] - [content_sha256] is what makes the RPC idempotent, computed with the exact same
     * [IngestPipeline.sha256] the ledger ingestion path already uses, over the same photo [bytes]
     * that were handed to [PantryReceiptAgent]. Lives here rather than in `backend/` because it is
     * the one place that already holds a [PantryIngestResult.Success] in this exact shape -
     * [PantryBackend.commitReceipt] stays free of any pantry-package type (see that function's own
     * doc comment).
     */
    private fun buildCommitReceiptPayload(bytes: ByteArray, result: PantryIngestResult.Success): String {
        val itemsArray = JSONArray()
        for (item in result.items) {
            itemsArray.put(
                JSONObject().apply {
                    put("name", item.name)
                    put("quantity", item.quantity)
                    put("unit_price_cents", item.unitPriceCents ?: JSONObject.NULL)
                    put("total_price_cents", item.totalPriceCents)
                    put("estimated_calories_kcal", item.caloriesKcal ?: JSONObject.NULL)
                    put("estimated_protein_g", item.proteinG ?: JSONObject.NULL)
                    put("estimated_carbs_g", item.carbsG ?: JSONObject.NULL)
                    put("estimated_fat_g", item.fatG ?: JSONObject.NULL)
                },
            )
        }
        val purchaseDateStr = java.time.Instant.ofEpochMilli(result.receipt.purchaseDate)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val root = JSONObject().apply {
            put("content_sha256", IngestPipeline.sha256(bytes))
            put("store", result.receipt.store)
            put("purchase_date", purchaseDateStr)
            put("currency", result.receipt.currency.name)
            put("total_cents", result.receipt.totalCents)
            put("subtotal_cents", result.subtotalCents ?: JSONObject.NULL)
            put("tax_cents", result.taxCents ?: JSONObject.NULL)
            put("other_charges_cents", result.otherChargesCents ?: JSONObject.NULL)
            put("items", itemsArray)
            put("provenance", RecordProvenance.LLM_RECONCILED.name)
        }
        return root.toString()
    }

    // ------------------------------------------------------------------------------------ reads

    suspend fun recentLineItems(context: Context, limit: Int = 20): List<PantryLineItem> {
        val receipts = allReceipts(context).associateBy { it.id }
        return allLineItems(context)
            .sortedByDescending { receipts[it.receiptId]?.purchaseDate ?: Long.MIN_VALUE }
            .take(limit)
    }

    /** [recentLineItems], each tagged with its own receipt's currency - see [PantryLineItemWithCurrency]'s doc comment. */
    suspend fun recentLineItemsWithCurrency(context: Context, limit: Int = 20): List<PantryLineItemWithCurrency> {
        val receipts = allReceipts(context).associateBy { it.id }
        return allLineItems(context)
            .sortedByDescending { receipts[it.receiptId]?.purchaseDate ?: Long.MIN_VALUE }
            .take(limit)
            .map { PantryLineItemWithCurrency(item = it, currency = receipts[it.receiptId]?.currency ?: LedgerCurrency.USD) }
    }

    /** Combines every currency into one bare cents figure - kept only for signature compatibility
     * (see [com.kevin.legion.data.local.PantryReceiptDao.totalSpendCents]'s own pre-cutover doc
     * comment: "left in place only because nothing besides this new query needs to change it, not
     * because it's still safe to call on its own"). Prefer [totalSpendCentsByCurrency]. */
    suspend fun totalSpendCents(context: Context): Long = allReceipts(context).sumOf { it.totalCents }

    /** Total grocery spend PER currency, never combined - see [com.kevin.legion.data.local.PantryReceiptDao.totalSpendCentsByCurrency]'s doc comment. */
    suspend fun totalSpendCentsByCurrency(context: Context): List<PantryCurrencyTotal> =
        allReceipts(context).groupBy { it.currency }.map { (currency, receipts) ->
            PantryCurrencyTotal(currency = currency, totalCents = receipts.sumOf { it.totalCents })
        }

    /**
     * The [limitReceipts] most recent receipts, each paired with its own line
     * items - what ticket 09's pantry screen (resolution §2, TREATMENT B
     * SEGREGATED) groups by, one receipt per `ON THE RECEIPT` / `ESTIMATED,
     * NOT ON THE RECEIPT` pair.
     */
    suspend fun recentReceiptsWithItems(context: Context, limitReceipts: Int = 10): List<Pair<PantryReceipt, List<PantryLineItem>>> {
        val receipts = allReceipts(context).sortedByDescending { it.purchaseDate }.take(limitReceipts)
        val items = allLineItems(context).groupBy { it.receiptId }
        return receipts.map { receipt -> receipt to (items[receipt.id] ?: emptyList()) }
    }

    /**
     * Every receipt's date/total/currency (quant-viz ticket 07) - the SPEND panel's monthly bars
     * need the driver's whole ingestion history, not [recentReceiptsWithItems]'s capped list.
     */
    suspend fun allReceiptSummaries(context: Context): List<PantryReceiptSummary> =
        allReceipts(context).map { PantryReceiptSummary(purchaseDate = it.purchaseDate, totalCents = it.totalCents, currency = it.currency) }
}

data class PantryImportResult(val success: Boolean, val message: String, val itemCount: Int = 0)
