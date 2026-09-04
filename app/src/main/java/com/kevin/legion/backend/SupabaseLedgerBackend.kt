package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val LEDGER_TRANSACTIONS_TABLE = "ledger_transactions"

private fun tsOrNull(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }
private fun parseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
private fun parseTsOrNull(s: String?): Long? = s?.let { parseTs(it) }
// txn_date is a `date`, not a `timestamptz` - the same UTC-midnight convention
// FleetBackend's service_date/EventUpsertDto's repeat_end_date use, reused verbatim.
private fun dateStr(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
private fun parseDate(s: String): Long = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * The wire shape for [SupabaseLedgerBackend.uploadMigratedTransaction]. Every nullable property is
 * deliberately required (no `= null` default) - same trick [RemoteEvent]'s wire DTOs explain at
 * length: `encodeDefaults = false` drops a property equal to its declared default, and `null` is
 * still a default, so an un-set nullable would silently vanish from the outgoing JSON. This is a
 * one-shot INSERT, not a partial PATCH, so every column must be present.
 */
@Serializable
private data class LedgerTransactionInsertDto(
    @SerialName("statement_id") val statementId: String?,
    @SerialName("account_last4") val accountLast4: String,
    @SerialName("account_nickname") val accountNickname: String,
    val currency: String,
    @SerialName("txn_date") val txnDate: String,
    val description: String,
    @SerialName("amount_cents") val amountCents: Long,
    @SerialName("balance_cents") val balanceCents: Long?,
    @SerialName("line_ref") val lineRef: String,
    val category: String?,
    @SerialName("category_pending") val categoryPending: Boolean,
    @SerialName("pending_logged_at") val pendingLoggedAt: String?,
    val provenance: String,
    @SerialName("origin_guid") val originGuid: String,
)

/** The wire shape read back off `public.ledger_transactions` for every operation. */
@Serializable
private data class LedgerTransactionRowDto(
    val id: String,
    @SerialName("statement_id") val statementId: String? = null,
    @SerialName("account_last4") val accountLast4: String,
    @SerialName("account_nickname") val accountNickname: String,
    val currency: String,
    @SerialName("txn_date") val txnDate: String,
    val description: String,
    @SerialName("amount_cents") val amountCents: Long,
    @SerialName("balance_cents") val balanceCents: Long? = null,
    @SerialName("line_ref") val lineRef: String,
    val category: String? = null,
    @SerialName("category_pending") val categoryPending: Boolean = true,
    @SerialName("pending_logged_at") val pendingLoggedAt: String? = null,
    val provenance: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("origin_guid") val originGuid: String? = null,
) {
    fun toRemoteLedgerTransaction() = RemoteLedgerTransaction(
        serverId = id,
        statementId = statementId,
        accountLast4 = accountLast4,
        accountNickname = accountNickname,
        currency = currency,
        txnDateEpochMs = parseDate(txnDate),
        description = description,
        amountCents = amountCents,
        balanceCents = balanceCents,
        lineRef = lineRef,
        category = category,
        categoryPending = categoryPending,
        pendingLoggedAtMs = parseTsOrNull(pendingLoggedAt),
        provenance = provenance,
        createdAtMs = parseTs(createdAt),
        originGuid = originGuid,
    )
}

/**
 * [LedgerBackend]'s real implementation over Postgrest, against `public.ledger_transactions`
 * (`supabase/migrations/20260825000300_aspect_ledger_pantry.sql`). This is the deliberately
 * untested seam in the ledger cutover, same posture as [SupabaseFleetBackend]/
 * [SupabasePantryBackend] - exercising it for real needs a live project. [LedgerBackend] is the
 * fake-friendly interface; every branch here does nothing but translate exceptions and decode DTOs.
 */
class SupabaseLedgerBackend(private val client: SupabaseClient) : LedgerBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(LedgerBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(LedgerBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(LedgerBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    /** No `deleted_at` filter - see [RemoteLedgerTransaction]'s own doc comment for why every row
     * this table has IS the active set, by construction. */
    override suspend fun fetchActiveTransactions(): Result<List<RemoteLedgerTransaction>> =
        translating("load your ledger transactions") {
            client.postgrest.from(LEDGER_TRANSACTIONS_TABLE)
                .select()
                .decodeList<LedgerTransactionRowDto>()
                .map { it.toRemoteLedgerTransaction() }
        }

    /** Same "select by origin_guid first" shape as [SupabaseFleetBackend.uploadMigratedVehicle] -
     * this is a re-run guard, not a gate-immutability workaround, and `Result.success(false)` on an
     * existing row means exactly "already migrated", never a failure. */
    override suspend fun uploadMigratedTransaction(txn: MigratedLedgerTransaction): Result<Boolean> =
        translating("upload a migrated transaction") {
            val existing = client.postgrest.from(LEDGER_TRANSACTIONS_TABLE)
                .select {
                    filter { eq("origin_guid", txn.originGuid) }
                }
                .decodeList<LedgerTransactionRowDto>()
            if (existing.isNotEmpty()) return@translating false

            client.postgrest.from(LEDGER_TRANSACTIONS_TABLE).insert(
                LedgerTransactionInsertDto(
                    statementId = txn.statementId,
                    accountLast4 = txn.accountLast4,
                    accountNickname = txn.accountNickname,
                    currency = txn.currency,
                    txnDate = dateStr(txn.txnDateEpochMs),
                    description = txn.description,
                    amountCents = txn.amountCents,
                    balanceCents = txn.balanceCents,
                    lineRef = txn.lineRef,
                    category = txn.category,
                    categoryPending = txn.categoryPending,
                    pendingLoggedAt = tsOrNull(txn.pendingLoggedAtMs),
                    provenance = txn.provenance.name,
                    originGuid = txn.originGuid,
                ),
            )
            true
        }

    /** See [LedgerBackend.fetchChangedTransactionsSince]'s own doc comment for why this is a plain
     * `created_at gte` filter with no tombstone branch. */
    override suspend fun fetchChangedTransactionsSince(sinceMs: Long): Result<List<RemoteLedgerTransaction>> =
        translating("load changed ledger transactions") {
            client.postgrest.from(LEDGER_TRANSACTIONS_TABLE)
                .select { filter { gte("created_at", Instant.ofEpochMilli(sinceMs).toString()) } }
                .decodeList<LedgerTransactionRowDto>()
                .map { it.toRemoteLedgerTransaction() }
        }
}
