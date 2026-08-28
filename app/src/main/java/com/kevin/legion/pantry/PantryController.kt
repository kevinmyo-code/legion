package com.kevin.legion.pantry

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.kevin.legion.backend.CommitOutcome
import com.kevin.legion.backend.PantryBackend
import com.kevin.legion.backend.PantryPhotoBackend
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabasePantryBackend
import com.kevin.legion.backend.SupabasePhotoBackend
import com.kevin.legion.data.PantryPhotoStore
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryCurrencyTotal
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryLineItemWithCurrency
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.data.local.PantryReceiptSummary
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.migration.EnginePantryRetirementCopy
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
 * `.scratch/aspect-engine/issues/22-cutover-per-aspect.md`) moved every function below onto the
 * engine. **That cutover is itself retired as of engine retirement step 2**
 * (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`) - see the class doc's "Not
 * configured" bullet below for the current shape, which is `pantry_receipts`/`pantry_line_items`
 * for both branches. Every function below keeps its ORIGINAL signature and return type
 * (`PantryReceipt`/`PantryLineItem`/`PantryCurrencyTotal`/`PantryReceiptSummary` - the legacy Room
 * entity/row shapes) so every caller - `ui/PantryScreen.kt`, `service/LiveToolbox.kt`'s
 * `list_recent_groceries`/`get_grocery_spend` - never had to change across either cutover.
 *
 * **The reconciliation gate is unaffected by either cutover.**
 * [PantryReceiptAgent.parseAndReconcile] runs entirely upstream of any write in this file, and a
 * [PantryIngestResult.Quarantined] causes [importReceipt] to write NOTHING, in every era.
 *
 * **Backend-erp Phase 4, aspect 2 of 5** (`.scratch/backend-erp/issues/05-migration-path.md`).
 * DUAL-PATH, exactly [com.kevin.legion.location.PlaceController]'s shape - every function checks
 * [backend] first:
 * - **Not configured**: **repointed onto the SAME `pantry_receipts`/`pantry_line_items` tables as
 *   of engine retirement step 2** - the engine cutover above is retired.
 *   [ensureLegacyReconciled] runs [EnginePantryRetirementCopy] once, first, so any receipt
 *   imported directly through the engine since cutover 2 is not silently lost the moment this read
 *   flips. **This file no longer touches [com.kevin.legion.engine.RecordStore] or
 *   `engineRecordDao()` at all** - the engine's Receipt/LineItem records are left exactly where
 *   they are (ticket 15: nothing is deleted until every aspect is repointed and soaked), just no
 *   longer read or written from here. **v44 (coordinator-authorised follow-up, same ticket):**
 *   [PantryReceipt] gained `subtotalCents`/`taxCents`/`otherChargesCents` (`MIGRATION_43_44`)
 *   specifically so this repoint would not recreate CLAUDE.md section 4 rule 7's 2026-08-26
 *   amendment (ticket 08) prospectively - the first version of this repoint left those three
 *   engine-only fields (see [com.kevin.legion.engine.pantry.PantryAspectSeeder]'s own doc comment,
 *   "the gate invariant is re-checkable post-hoc") with nowhere to land on the legacy entity, which
 *   would have silently discarded the gate's own inputs for every receipt an unconfigured install
 *   wrote from then on - the exact "new ingestion path" that amendment refuses to license. Fixed
 *   before shipping: [writeReceipt] now stamps them onto the receipt it inserts, and
 *   [EnginePantryRetirementCopy] carries them through for any receipt already sitting in the
 *   engine. `null` still means "not printed" (or, for a genuinely pre-v44 row, "predates this
 *   column"), never a fabricated zero - the arithmetic itself was never affected, only whether it
 *   could be re-checked from storage after the fact.
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
 * **Photos get a durable copy as of ticket 09** (`.scratch/backend-erp/issues/09-backups-do-not-
 * cover-files.md`, ticket 01 ruling 10 as amended). [commitReceiptRemote] uploads the receipt bytes
 * to [SupabasePhotoBackend] (the household's private `receipt-photos` bucket,
 * `supabase/migrations/20260827000400_receipt_photos_bucket.sql`) BEFORE building the commit
 * payload, so a successful upload's object path rides along as `photo_object_path` on the same RPC
 * call, and [writeReceipt]'s local counterpart is a no-op (the unconfigured path has nowhere to
 * upload to - [PantryPhotoStore] stays exactly as it was there). **A failed photo upload never
 * loses the receipt**: [commitReceiptRemote] commits the receipt's figures regardless of the
 * upload outcome, with `photo_object_path` left null and the failure worded into
 * [PantryImportResult.message] - losing financial data to a photo backup failure would be strictly
 * worse than the durability gap ticket 09 exists to close. [PantryPhotoStore]'s own local file is
 * UNCHANGED by any of this - still deleted on a successful commit exactly as before (that file was
 * always staging-only, never the durable copy; see [PantryReceipt.sourceImagePath]'s own comment) -
 * this ticket only makes sure the bytes reach a durable home BEFORE that deletion happens.
 */
object PantryController {
    private const val TAG = "PantryController"

    private fun db(context: Context) = CarDatabase.getDatabase(context)

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

    /** Test seam: settable from a unit test so a [PantryPhotoBackend] fake can be injected without
     * a real [SupabaseClientProvider] / network - the photo-upload sibling of [backendOverride].
     * Defaults to null, meaning "resolve normally"; production code never sets this. */
    @Volatile
    internal var photoBackendOverride: PantryPhotoBackend? = null

    /** Resolves the active photo backend, or null when Supabase is not configured. Deliberately
     * its OWN resolution (not derived from [backend]'s null-ness) so a test can exercise
     * [commitReceiptRemote]'s upload branch with a [PantryBackend] fake and a real/absent
     * [PantryPhotoBackend] independently - same posture as keeping [backendOverride] and
     * [photoBackendOverride] as two separate volatiles rather than one combined "configured" flag. */
    private fun photoBackend(context: Context): PantryPhotoBackend? {
        photoBackendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabasePhotoBackend(client)
    }

    private fun receiptDao(context: Context) = db(context).pantryReceiptDao()
    private fun lineItemDao(context: Context) = db(context).pantryLineItemDao()

    /** One-time reconcile gate for the unconfigured path (engine retirement step 2): before EVER
     * reading or writing `pantry_receipts`/`pantry_line_items` from an unconfigured branch, make
     * sure any engine-only receipt has already landed there. Cheap after the first call -
     * [EnginePantryRetirementCopy.copyIfNeeded] itself short-circuits on its own completion flag,
     * so this is a SharedPreferences read on every later call, not a repeat scan. Every unconfigured
     * function below calls this first so none of them can read the legacy tables before the copy
     * has run, regardless of call order - same shape as
     * [com.kevin.legion.location.PlaceController.ensureLegacyReconciled]. */
    private suspend fun ensureLegacyReconciled(context: Context) {
        EnginePantryRetirementCopy.copyIfNeeded(context)
    }

    /** Every receipt - the one place every receipt read below funnels through. **Configured**:
     * reads the Room replica, never the network - cache-first (ticket 01 ruling 9). **Unconfigured**:
     * `pantry_receipts` too, as of engine retirement step 2 - reconciled against the engine first
     * (see [ensureLegacyReconciled]) so nothing imported while engine-backed is silently dropped by
     * the repoint. Both branches now read the exact same query, matching
     * [com.kevin.legion.location.PlaceController.all]'s own convergence. */
    private suspend fun allReceipts(context: Context): List<PantryReceipt> {
        if (backend(context) == null) ensureLegacyReconciled(context)
        return receiptDao(context).getAll()
    }

    /** Every line item - same cache-first/legacy split as [allReceipts]. */
    private suspend fun allLineItems(context: Context): List<PantryLineItem> {
        if (backend(context) == null) ensureLegacyReconciled(context)
        return lineItemDao(context).getAll()
    }

    // ------------------------------------------------------------------------------------ ingestion

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
                // **Only delete the staging file when a durable copy actually exists**, which
                // means: configured, so the Storage upload had somewhere to go. This used to
                // delete unconditionally, and on an UNCONFIGURED install that left every committed
                // receipt's `sourceImagePath` pointing at a file the app had just removed itself -
                // the exact "row pointing at nothing" shape ticket 09 is named after, arrived at
                // by design rather than by the uninstall that ticket investigated.
                //
                // Deleting after a successful upload is right: the bucket holds the durable copy
                // and the local file is only staging. Deleting with no upload is throwing the
                // only copy away. A clone-and-run install has no bucket, so it keeps its photos.
                if (written.success && backend != null) PantryPhotoStore.delete(context, imageFile)
                written
            }
        }
    }

    /**
     * The network-free half of [importReceipt] - writes an already-gate-passed
     * [PantryIngestResult.Success] straight into `pantry_receipts`/`pantry_line_items` (receipt
     * first, then its line items stamped with the new receipt id) inside one
     * [androidx.room.withTransaction] block - repointed here at engine retirement step 2 from the
     * [com.kevin.legion.engine.RecordStore] write this function used between cutover 2 and this
     * ticket. Split out as its own function, mirroring [PantryReceiptAgent.parseAndReconcile]'s own
     * "network-free... unit-tested directly" split (see that function's doc comment) -
     * [PantryControllerTest] exercises this directly with a hand-built
     * [PantryIngestResult.Success], with no Gemini key or network call needed, exactly as
     * [PantryReceiptAgentTest] already does for the gate itself.
     *
     * Never called for a [PantryIngestResult.Quarantined] - [importReceipt]'s `when` only reaches
     * this branch after the gate has already passed, so "quarantine writes nothing" is enforced
     * structurally (there is no path from a `Quarantined` result to this function at all), not by a
     * runtime check inside it.
     *
     * **A Room `@Insert` throws on failure rather than returning a false/failed result**, but
     * unlike [com.kevin.legion.location.PlaceController.tagPlace] this function still catches it
     * and words it - checked, not assumed, against this function's ONE production caller.
     * `tagPlace`'s "let it throw" is safe for its VOICE path because
     * [com.kevin.legion.service.LiveSessionController]'s tool dispatch already wraps every call in
     * a catch-all (`"Something went wrong running that."`); its UI caller in `ui/FleetScreen.kt`
     * has no such wrapper either, a pre-existing gap out of scope here. [importReceipt] has NO
     * voice tool at all - `import_receipt` only opens `ui/PantryScreen.kt`'s
     * `PantryImportScreen`, which calls this function from a bare `LaunchedEffect` with no
     * try/catch of its own and reads `.message` straight onto the screen. A raw throw here would
     * crash that composition instead of showing the worded failure the pre-repoint
     * `EngineWriteFailedException` branch used to produce - strictly worse than the wording it
     * would have replaced, which is exactly what CLAUDE.md §7 exists to prevent (a failure result
     * must say in words what did NOT happen). So the whole write is wrapped below: a genuine
     * failure - Room throws, [androidx.room.withTransaction] rolls the whole block back on it, same
     * as before - is caught and turned into the SAME worded message the engine-backed version used.
     */
    suspend fun writeReceipt(context: Context, result: PantryIngestResult.Success): PantryImportResult {
        ensureLegacyReconciled(context)
        val database = db(context)
        var itemCount = 0

        try {
            database.withTransaction {
                // v44 (coordinator-authorised follow-up to engine retirement step 2): the gate's
                // own subtotal/tax/otherCharges anchors live on [result] itself, not on
                // [result.receipt] - stamped on here before the insert so this unconfigured write
                // persists them exactly as the engine did pre-repoint. Dropping them would recreate
                // ticket 08's defect for every receipt written from this call on - see this
                // function's own doc comment.
                val receiptWithAnchors = result.receipt.copy(
                    subtotalCents = result.subtotalCents,
                    taxCents = result.taxCents,
                    otherChargesCents = result.otherChargesCents,
                )
                val newReceiptId = receiptDao(context).insert(receiptWithAnchors)
                if (result.items.isNotEmpty()) {
                    lineItemDao(context).insertAll(result.items.map { it.copy(receiptId = newReceiptId) })
                }
                itemCount = result.items.size
            }
        } catch (e: Exception) {
            // See this function's own doc comment: PantryImportScreen has no try/catch of its own,
            // so a genuine write failure must be worded HERE, not left to propagate.
            Log.w(TAG, "writeReceipt: legacy write failed after the gate passed, rolled back - ${e.message}")
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
     *
     * **Photo upload happens FIRST, before the commit RPC** (ticket 09), so a successful upload's
     * object path can ride along on the SAME [buildCommitReceiptPayload] call as `photo_object_path`
     * rather than needing a second round trip to attach it after the fact. [PantryPhotoBackend]
     * resolves via [photoBackend] independently of [backend] (a test can supply a
     * [PantryPhotoBackend] fake through [photoBackendOverride] without touching [backendOverride],
     * or leave it unset - unset resolves to null in a Robolectric context with no Supabase
     * configured, in which case the upload step is skipped entirely and every existing
     * `PantryControllerBackendTest` case keeps its exact old behaviour with `photo_object_path`
     * staying null). **A failed or skipped upload NEVER blocks the commit** - see this function's
     * own doc comment above for why that ordering is load-bearing.
     */
    internal suspend fun commitReceiptRemote(
        context: Context,
        backend: PantryBackend,
        bytes: ByteArray,
        result: PantryIngestResult.Success,
    ): PantryImportResult {
        val sha256 = IngestPipeline.sha256(bytes)
        var photoObjectPath: String? = null
        var photoUploadFailed = false
        photoBackend(context)?.let { photos ->
            photos.uploadReceiptPhoto(sha256, bytes).fold(
                onSuccess = { objectPath -> photoObjectPath = objectPath },
                onFailure = { e ->
                    // Worded, never thrown - see this function's own doc comment: losing the
                    // receipt to a photo backup failure would be strictly worse than the gap
                    // ticket 09 exists to close. The receipt itself still commits below.
                    Log.w(TAG, "commitReceiptRemote: photo upload failed, committing without it - ${e.message}")
                    photoUploadFailed = true
                },
            )
        }

        val payload = buildCommitReceiptPayload(result, sha256, photoObjectPath)
        val outcome = backend.commitReceipt(payload).getOrElse {
            Log.w(TAG, "commitReceiptRemote: request failed - ${it.message}")
            return PantryImportResult(
                success = false,
                message = "This receipt's numbers checked out, but I couldn't reach the server to save it - try again.",
            )
        }

        // Appended to a successful commit's message when the photo backup step failed - the
        // receipt's figures are safe either way, but the driver should know the photo is only on
        // this device for now (CLAUDE.md §7: a failure must say in words what did NOT happen).
        val photoNote = if (photoUploadFailed) {
            " (Couldn't back up the receipt photo - it's only on this device for now.)"
        } else {
            ""
        }

        return when (outcome) {
            is CommitOutcome.Quarantined -> PantryImportResult(success = false, message = outcome.reason)
            is CommitOutcome.AlreadyCommitted -> PantryImportResult(
                success = true,
                message = "This receipt was already logged.$photoNote",
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
                        photoObjectPath = photoObjectPath,
                    ),
                )
                lineItemDao(context).insertAll(result.items.map { it.copy(receiptId = localReceiptId) })
                PantryImportResult(
                    success = true,
                    message = "Logged ${result.items.size} item(s) from ${result.receipt.store}.$photoNote",
                    itemCount = result.items.size,
                )
            }
        }
    }

    /**
     * The JSON body `public.commit_receipt(payload jsonb)` expects
     * (`supabase/migrations/20260825000700_commit_receipt_rpc.sql`), built from a gate-passed
     * [result] - [sha256] is what makes the RPC idempotent, computed by the caller (once, in
     * [commitReceiptRemote]) with the exact same [IngestPipeline.sha256] the ledger ingestion path
     * already uses, over the same photo bytes that were handed to [PantryReceiptAgent] - the raw
     * bytes stay out of this function's signature entirely now that both of its uses (the
     * idempotency key AND, since ticket 09, the photo's object path) are covered by [sha256] alone.
     * [photoObjectPath] is null when the photo upload was skipped (not configured) or failed (see
     * [commitReceiptRemote]'s own doc comment) - either way `photo_object_path` genuinely has
     * nothing to report, never a fabricated value. Lives here rather than in `backend/` because it
     * is the one place that already holds a [PantryIngestResult.Success] in this exact shape -
     * [PantryBackend.commitReceipt] stays free of any pantry-package type (see that function's own
     * doc comment).
     */
    private fun buildCommitReceiptPayload(
        result: PantryIngestResult.Success,
        sha256: String,
        photoObjectPath: String?,
    ): String {
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
            put("content_sha256", sha256)
            put("store", result.receipt.store)
            put("purchase_date", purchaseDateStr)
            put("currency", result.receipt.currency.name)
            put("total_cents", result.receipt.totalCents)
            put("subtotal_cents", result.subtotalCents ?: JSONObject.NULL)
            put("tax_cents", result.taxCents ?: JSONObject.NULL)
            put("other_charges_cents", result.otherChargesCents ?: JSONObject.NULL)
            put("photo_object_path", photoObjectPath ?: JSONObject.NULL)
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
            PantryCurrencyTotal(
                currency = currency,
                totalCents = receipts.sumOf { it.totalCents },
                // See PantryCurrencyTotal's own doc comment: this total is unverified as a whole
                // the moment ANY receipt behind it is (CLAUDE.md section 4 rule 7 condition 3).
                hasUnreconciled = receipts.any { it.unaccountedCents != null },
            )
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
