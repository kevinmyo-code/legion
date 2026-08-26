package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val RECEIPTS_TABLE = "receipts"
private const val LINE_ITEMS_TABLE = "receipt_line_items"

/** The wire shape read back off `public.receipts` for every operation. */
@Serializable
private data class ReceiptRowDto(
    val id: String,
    val store: String,
    @SerialName("purchase_date") val purchaseDate: String,
    val currency: String,
    @SerialName("total_cents") val totalCents: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("origin_guid") val originGuid: String? = null,
) {
    fun toRemoteReceipt(lines: List<ReceiptLineRowDto>) = RemoteReceipt(
        serverId = id,
        store = store,
        // A plain `date` column, not timestamptz - midnight UTC on the printed date, matching
        // PantryReceiptAgent.parseAndReconcile's own LocalDate -> epoch-millis conversion so a
        // migrated row and a freshly-committed one land on an identical clock.
        purchaseDateEpochMs = java.time.LocalDate.parse(purchaseDate)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        currency = currency,
        totalCents = totalCents,
        createdAtMs = OffsetDateTime.parse(createdAt).toInstant().toEpochMilli(),
        originGuid = originGuid,
        lines = lines.map { it.toRemoteReceiptLine() },
    )
}

/** The wire shape read back off `public.receipt_line_items`. */
@Serializable
private data class ReceiptLineRowDto(
    @SerialName("receipt_id") val receiptId: String,
    val name: String,
    val quantity: Double,
    @SerialName("unit_price_cents") val unitPriceCents: Long? = null,
    @SerialName("total_price_cents") val totalPriceCents: Long,
    @SerialName("estimated_calories_kcal") val estimatedCaloriesKcal: Double? = null,
    @SerialName("estimated_protein_g") val estimatedProteinG: Double? = null,
    @SerialName("estimated_carbs_g") val estimatedCarbsG: Double? = null,
    @SerialName("estimated_fat_g") val estimatedFatG: Double? = null,
) {
    fun toRemoteReceiptLine() = RemoteReceiptLine(
        name = name,
        quantity = quantity,
        unitPriceCents = unitPriceCents,
        totalPriceCents = totalPriceCents,
        estimatedCaloriesKcal = estimatedCaloriesKcal,
        estimatedProteinG = estimatedProteinG,
        estimatedCarbsG = estimatedCarbsG,
        estimatedFatG = estimatedFatG,
    )
}

/** The wire shape sent by [SupabasePantryBackend.uploadMigratedReceipt] for the header insert. No
 * `deletedAt`-style required-null trick needed here (unlike [PlaceUpsertDto]) - this is a genuine
 * INSERT, never an upsert-with-conflict, because the gated tables' immutability trigger blocks the
 * UPDATE half of an upsert outright. See [SupabasePantryBackend.uploadMigratedReceipt]'s own doc
 * comment for why a select-then-insert replaces Places' onConflict upsert here. */
@Serializable
private data class ReceiptInsertDto(
    val store: String,
    @SerialName("purchase_date") val purchaseDate: String,
    val currency: String,
    @SerialName("total_cents") val totalCents: Long,
    @SerialName("subtotal_cents") val subtotalCents: Long?,
    @SerialName("tax_cents") val taxCents: Long?,
    @SerialName("other_charges_cents") val otherChargesCents: Long?,
    val provenance: String,
    @SerialName("origin_guid") val originGuid: String,
)

@Serializable
private data class ReceiptLineInsertDto(
    @SerialName("receipt_id") val receiptId: String,
    val name: String,
    val quantity: Double,
    @SerialName("unit_price_cents") val unitPriceCents: Long?,
    @SerialName("total_price_cents") val totalPriceCents: Long,
    @SerialName("estimated_calories_kcal") val estimatedCaloriesKcal: Double?,
    @SerialName("estimated_protein_g") val estimatedProteinG: Double?,
    @SerialName("estimated_carbs_g") val estimatedCarbsG: Double?,
    @SerialName("estimated_fat_g") val estimatedFatG: Double?,
    val provenance: String,
    @SerialName("origin_guid") val originGuid: String,
)

/** `public.commit_receipt(payload jsonb)`'s response shape, decoded from the single jsonb object
 * the RPC returns. See that function's own SQL comment for the three `outcome` values. */
@Serializable
private data class CommitReceiptResponseDto(
    val outcome: String,
    @SerialName("receipt_id") val receiptId: String? = null,
    val inserted: Int = 0,
    val reason: String? = null,
)

/**
 * [PantryBackend]'s real implementation over Postgrest, against `public.receipts`/
 * `public.receipt_line_items` (`supabase/migrations/20260825000300_aspect_ledger_pantry.sql`) and
 * `public.commit_receipt` (`supabase/migrations/20260825000700_commit_receipt_rpc.sql`). This is
 * the deliberately untested seam in the pantry cutover, same posture as
 * [SupabasePlacesBackend] - exercising it for real needs a live project. [PantryBackend] is the
 * fake-friendly interface; every branch here does nothing but translate exceptions, build the RPC
 * payload envelope, and decode DTOs.
 */
class SupabasePantryBackend(private val client: SupabaseClient) : PantryBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(PantryBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(PantryBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(PantryBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    override suspend fun fetchActiveReceipts(): Result<List<RemoteReceipt>> =
        translating("load your grocery receipts") {
            val receipts = client.postgrest.from(RECEIPTS_TABLE).select().decodeList<ReceiptRowDto>()
            val lines = client.postgrest.from(LINE_ITEMS_TABLE).select().decodeList<ReceiptLineRowDto>()
            val linesByReceiptId = lines.groupBy { it.receiptId }
            receipts.map { it.toRemoteReceipt(linesByReceiptId[it.id].orEmpty()) }
        }

    /**
     * [payload] is already the raw JSON object `commit_receipt` expects (see [PantryBackend.commitReceipt]'s
     * own doc comment on why this interface takes a plain string rather than a pantry-package
     * type). The RPC's one SQL parameter is named `payload`, so the outgoing body is
     * `{"payload": <payload>}` - a plain string concatenation would risk producing invalid JSON if
     * [payload] itself needed escaping, so this decodes it back into a [JsonObject] and re-wraps it
     * structurally instead.
     */
    override suspend fun commitReceipt(payload: String): Result<CommitOutcome> =
        translating("commit this receipt") {
            val parsed = Json.parseToJsonElement(payload) as? JsonObject
                ?: throw PantryBackendException("the receipt payload was not a JSON object")
            val params = buildJsonObject { put("payload", parsed) }
            val response = client.postgrest.rpc("commit_receipt", params).decodeAs<CommitReceiptResponseDto>()
            when (response.outcome) {
                "COMMITTED" -> CommitOutcome.Committed(
                    receiptId = response.receiptId
                        ?: throw PantryBackendException("commit_receipt reported COMMITTED with no receipt_id"),
                    insertedLines = response.inserted,
                )
                "ALREADY_COMMITTED" -> CommitOutcome.AlreadyCommitted
                "QUARANTINED" -> CommitOutcome.Quarantined(
                    reason = response.reason ?: "This receipt's numbers didn't reconcile.",
                )
                else -> throw PantryBackendException("commit_receipt returned an unrecognised outcome: ${response.outcome}")
            }
        }

    /**
     * Never an upsert. [Places][SupabasePlacesBackend.upsert] can lean on `ON CONFLICT (label) DO
     * UPDATE` because places are mutable; `receipts`/`receipt_line_items` carry the
     * `forbid_mutation_of_facts` trigger (CLAUDE.md section 4's gate made structural), which blocks
     * the UPDATE half of any upsert outright. So this selects first - a row with [MigratedReceipt.originGuid]
     * already present means a previous run already migrated it, and the correct action is to touch
     * nothing and report `false`, not to attempt (and fail) an update.
     */
    override suspend fun uploadMigratedReceipt(receipt: MigratedReceipt): Result<Boolean> =
        translating("upload a migrated receipt") {
            val existing = client.postgrest.from(RECEIPTS_TABLE)
                .select {
                    filter { eq("origin_guid", receipt.originGuid) }
                }
                .decodeList<ReceiptRowDto>()
            if (existing.isNotEmpty()) return@translating false

            val purchaseDateStr = java.time.Instant.ofEpochMilli(receipt.purchaseDateEpochMs)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()

            val inserted = client.postgrest.from(RECEIPTS_TABLE)
                .insert(
                    ReceiptInsertDto(
                        store = receipt.store,
                        purchaseDate = purchaseDateStr,
                        currency = receipt.currency,
                        totalCents = receipt.totalCents,
                        subtotalCents = receipt.subtotalCents,
                        taxCents = receipt.taxCents,
                        otherChargesCents = receipt.otherChargesCents,
                        provenance = "LLM_RECONCILED",
                        originGuid = receipt.originGuid,
                    ),
                ) { select() }
                .decodeSingle<ReceiptRowDto>()

            if (receipt.lines.isNotEmpty()) {
                client.postgrest.from(LINE_ITEMS_TABLE).insert(
                    receipt.lines.map { line ->
                        ReceiptLineInsertDto(
                            receiptId = inserted.id,
                            name = line.name,
                            quantity = line.quantity,
                            unitPriceCents = line.unitPriceCents,
                            totalPriceCents = line.totalPriceCents,
                            estimatedCaloriesKcal = line.estimatedCaloriesKcal,
                            estimatedProteinG = line.estimatedProteinG,
                            estimatedCarbsG = line.estimatedCarbsG,
                            estimatedFatG = line.estimatedFatG,
                            provenance = "LLM_RECONCILED",
                            originGuid = line.originGuid,
                        )
                    },
                )
            }
            true
        }
}
