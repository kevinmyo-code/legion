package com.kevin.legion.backend.engine

import com.kevin.legion.backend.CommitOutcome
import com.kevin.legion.backend.MigratedReceipt
import com.kevin.legion.backend.PantryBackend
import com.kevin.legion.backend.RemoteReceipt
import com.kevin.legion.backend.RemoteReceiptLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val RECEIPTS_PATH = "/api/pantry/receipts/"
private const val LINE_ITEMS_PATH = "/api/pantry/line-items/"
private const val INGEST_RECEIPT_PATH = "/api/ingest/receipt"

/**
 * One `public.receipts` header as `server/api/pantry.py`'s `ReceiptSerializer` renders it, read off
 * `server/openapi.yaml`'s `Receipt` schema. Every field is `readOnly` in the contract: this is a
 * gated table and its viewset carries `get` and nothing else.
 *
 * [purchaseDate] is a bare DATE, converted at UTC midnight in both directions - the same convention
 * `SupabasePantryBackend`'s own row DTO uses, so a migrated row and a freshly-committed one land on
 * an identical clock whichever transport read them.
 */
@Serializable
private data class DjangoReceiptRow(
    val id: String,
    val store: String,
    @SerialName("purchase_date") val purchaseDate: String,
    val currency: String,
    @SerialName("total_cents") val totalCents: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("origin_guid") val originGuid: String? = null,
    val provenance: String,
    @SerialName("unaccounted_cents") val unaccountedCents: Long? = null,
) {
    fun toRemote(lines: List<DjangoReceiptLineRow>) = RemoteReceipt(
        serverId = id,
        store = store,
        purchaseDateEpochMs = ledgerParseDate(purchaseDate),
        currency = currency,
        totalCents = totalCents,
        createdAtMs = ledgerParseTs(createdAt),
        originGuid = originGuid,
        provenance = provenance,
        unaccountedCents = unaccountedCents,
        lines = lines.map { it.toRemote() },
    )
}

/**
 * One `public.receipt_line_items` row.
 *
 * **[quantity] and the four `estimated_*` fields are STRINGS on the wire, and that is the server's
 * deliberate choice rather than an accident to work around.** `api/pantry.py`'s own module doc:
 * those five columns are bare `numeric` in Postgres, are rendered through DRF's `DecimalField`, and
 * `COERCE_DECIMAL_TO_STRING` leaves them as strings because *"a decimal rendered as a JSON number is
 * a decimal handed to a float parser, and these are the fields where '2 for the price of 3'
 * quantities live"*. They are declared `String` here and converted once, at the boundary, because
 * [RemoteReceiptLine] has carried `Double` since the Supabase transport and changing that would be a
 * change to the pantry domain rather than to this transport.
 *
 * The money columns are NOT among them - `unit_price_cents` and `total_price_cents` are `bigint`
 * server-side and JSON integers on the wire, and stay `Long` here (CLAUDE.md section 4 rule 3).
 *
 * The four `estimated_*` values are ESTIMATES a model guessed from the product name, never printed
 * on the receipt and never part of any reconciliation arithmetic (section 4 rule 5). Nothing here
 * rounds or re-derives them; any surface rendering one must say "estimate".
 */
@Serializable
private data class DjangoReceiptLineRow(
    val id: String,
    @SerialName("receipt_id") val receiptId: String,
    val name: String,
    val quantity: String,
    @SerialName("unit_price_cents") val unitPriceCents: Long? = null,
    @SerialName("total_price_cents") val totalPriceCents: Long,
    @SerialName("estimated_calories_kcal") val estimatedCaloriesKcal: String? = null,
    @SerialName("estimated_protein_g") val estimatedProteinG: String? = null,
    @SerialName("estimated_carbs_g") val estimatedCarbsG: String? = null,
    @SerialName("estimated_fat_g") val estimatedFatG: String? = null,
) {
    fun toRemote() = RemoteReceiptLine(
        name = name,
        quantity = quantity.toDouble(),
        unitPriceCents = unitPriceCents,
        totalPriceCents = totalPriceCents,
        estimatedCaloriesKcal = estimatedCaloriesKcal?.toDouble(),
        estimatedProteinG = estimatedProteinG?.toDouble(),
        estimatedCarbsG = estimatedCarbsG?.toDouble(),
        estimatedFatG = estimatedFatG?.toDouble(),
    )
}

/**
 * `POST /api/ingest/receipt`'s response, both statuses.
 *
 * **One DTO for a 201 and a 200 because the shapes agree on the four keys this reads.**
 * `ReceiptCommitted` (201) carries `outcome`/`receipt_id`/`inserted` plus `anchors` and `estimates`;
 * `IngestOutcome` (200) carries `outcome`/`inserted` plus `content_sha256`/`note`/`reason`. The
 * extras on either side are dropped by [engineSyncedJson]'s `ignoreUnknownKeys`, and the caller
 * branches on `outcome`, exactly as the contract instructs ("a client branches on `outcome`, not on
 * the shape").
 *
 * **This is field-for-field `SupabasePantryBackend`'s own `CommitReceiptResponseDto`**, which
 * decoded `public.commit_receipt(payload jsonb)`'s single jsonb result. The RPC moved into Django
 * unchanged, so the response did too - the transport swapped and the DTO did not.
 */
@Serializable
private data class DjangoReceiptCommitResponse(
    val outcome: String,
    @SerialName("receipt_id") val receiptId: String? = null,
    val inserted: Int = 0,
    val reason: String? = null,
)

/**
 * [PantryBackend] over the household Django engine. Interchangeable with
 * [com.kevin.legion.backend.SupabasePantryBackend] by construction: same interface, same
 * [RemoteReceipt]/[CommitOutcome] out, so [com.kevin.legion.pantry.PantryController] cannot tell
 * which one it holds.
 *
 * **The two write paths stay exactly as far apart as [PantryBackend]'s own doc comment demands, and
 * on this transport the server enforces the distance rather than this class promising it.**
 * `receipts` and `receipt_line_items` are `GatedReadViewSet` tables: their detail routes carry `get`
 * and nothing else, so a PUT or DELETE is answered with the gate's own address. A NEW receipt goes
 * to `POST /api/ingest/receipt`, which is `public.commit_receipt(payload jsonb)` moved into Django
 * and runs CLAUDE.md section 4's gate server-side exactly once. There is no route at all for
 * [uploadMigratedReceipt]'s direct insert - see that function for what it does instead.
 *
 * **A quarantine is a 200, never a failure.** The contract is explicit that the gate refusing is a
 * VERDICT and not a transport problem, which is the same distinction [CommitOutcome]'s own doc
 * comment draws between [CommitOutcome.Quarantined] and a `Result.failure`. A 400 IS a failure here
 * and means something different again: the gate could not RUN (a missing `content_sha256`, money
 * that is not an integer number of cents, an `UNRECONCILED` provenance on a path that has no
 * provisional branch), so nothing was written and no quarantine was recorded either.
 */
class DjangoPantryBackend(private val http: EngineHttp) : PantryBackend {

    private val receipts = EngineSyncedTable(
        http = http,
        path = RECEIPTS_PATH,
        rowSerializer = DjangoReceiptRow.serializer(),
        idOf = { it.id },
    )

    private val lineItems = EngineSyncedTable(
        http = http,
        path = LINE_ITEMS_PATH,
        rowSerializer = DjangoReceiptLineRow.serializer(),
        idOf = { it.id },
    )

    /**
     * Every gated receipt, header plus lines.
     *
     * **`?since=` with no value rather than `?active=1`**, for the reason
     * [DjangoLedgerBackend]'s own class doc gives at length: `GatedReadViewSet` sets
     * `has_tombstones = False` because `receipts` has no `deleted_at` column, so there is no active
     * subset to narrow to and the unnarrowed feed already IS it.
     *
     * Two feeds and an in-memory join, the same shape `SupabasePantryBackend.fetchActiveReceipts`
     * uses - `/api/pantry/line-items/` is its own route and there is no nested representation to
     * ask for.
     */
    override suspend fun fetchActiveReceipts(): Result<List<RemoteReceipt>> =
        translatingEngineCall("load your grocery receipts") {
            joinReceipts(receipts.fetchChangedSince(null), lineItems.fetchChangedSince(null))
        }

    /**
     * See [PantryBackend.fetchChangedReceiptsSince]'s own doc comment for the contract.
     * `GatedReadViewSet.cursor_field` is `created_at`, so the engine's `?since=` filters the same
     * column `SupabasePantryBackend` filters with a `gte` - the two transports answer the same
     * question, not merely similar ones.
     *
     * **The LINE feed is deliberately unfiltered**, matching the Supabase implementation's own note:
     * a line item's `created_at` is its parent's, so filtering both would be redundant, and the
     * changed-receipts set is a handful of rows per pull either way.
     */
    override suspend fun fetchChangedReceiptsSince(sinceMs: Long): Result<List<RemoteReceipt>> =
        translatingEngineCall("load changed grocery receipts") {
            joinReceipts(
                receipts.fetchChangedSince(ledgerTs(sinceMs)),
                lineItems.fetchChangedSince(null),
            )
        }

    /**
     * `POST /api/ingest/receipt` with [payload] as the body, unchanged.
     *
     * **Unwrapped, unlike the Supabase transport.** `SupabasePantryBackend` had to re-wrap the same
     * string as `{"payload": <payload>}` because the RPC's one SQL parameter is named `payload`;
     * the HTTP endpoint takes the payload as the whole body, so this sends what the caller built and
     * nothing around it. [payload] is still parsed on the way in, so a caller that handed over
     * something that is not a JSON object fails here rather than at the server.
     */
    override suspend fun commitReceipt(payload: String): Result<CommitOutcome> =
        translatingEngineCall("commit this receipt") {
            // Parsed and re-rendered rather than forwarded verbatim, purely so a payload that is not
            // a JSON object is caught before it is sent - the same guard SupabasePantryBackend has,
            // where it also served to build the RPC envelope.
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(payload)
            val response = http.post(INGEST_RECEIPT_PATH, parsed.toString()).getOrThrow()
            val decoded = engineSyncedJson.decodeFromString(
                DjangoReceiptCommitResponse.serializer(),
                response.body,
            )
            when (decoded.outcome) {
                "COMMITTED" -> CommitOutcome.Committed(
                    receiptId = decoded.receiptId
                        ?: error("the engine reported COMMITTED with no receipt_id"),
                    insertedLines = decoded.inserted,
                )
                "ALREADY_COMMITTED" -> CommitOutcome.AlreadyCommitted
                "QUARANTINED" -> CommitOutcome.Quarantined(
                    reason = decoded.reason ?: "This receipt's numbers didn't reconcile.",
                )
                else -> error("the engine returned an unrecognised outcome: ${decoded.outcome}")
            }
        }

    /**
     * **Refused without sending anything, because there is no route to send it to** - the same
     * shape and the same reasoning as [DjangoLedgerBackend.uploadMigratedTransaction].
     *
     * `receipts` and `receipt_line_items` are gated: `POST /api/ingest/receipt` is the only way a row
     * reaches either, and it runs the gate. A direct insert keyed on `origin_guid` is precisely what
     * the gate exists to prevent on this transport, so the honest answer is that nothing was sent -
     * not a silently skipped upload, and not a call that pretends the migration ran.
     */
    override suspend fun uploadMigratedReceipt(receipt: MigratedReceipt): Result<Boolean> =
        Result.failure(
            EngineHttpException(
                EngineFailure.Refused(
                    status = HTTP_NOT_FOUND,
                    body = "The engine takes no direct upload into receipts - that table is behind " +
                        "the section 4 gate and only POST /api/ingest/receipt writes it. Nothing " +
                        "was sent and nothing was migrated.",
                ),
            ),
        )

    private fun joinReceipts(
        headers: List<DjangoReceiptRow>,
        lines: List<DjangoReceiptLineRow>,
    ): List<RemoteReceipt> {
        val byReceiptId = lines.groupBy { it.receiptId }
        return headers.map { it.toRemote(byReceiptId[it.id].orEmpty()) }
    }
}
