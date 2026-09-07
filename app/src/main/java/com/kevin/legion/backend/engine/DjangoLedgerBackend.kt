package com.kevin.legion.backend.engine

import com.kevin.legion.backend.LedgerBackend
import com.kevin.legion.backend.MigratedLedgerTransaction
import com.kevin.legion.backend.RemoteLedgerTransaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val TRANSACTIONS_PATH = "/api/ledger/transactions/"

/**
 * One `public.ledger_transactions` row as `server/api/ledger.py`'s `LedgerTransactionSerializer`
 * renders it, read off `server/openapi.yaml`'s `LedgerTransaction` schema.
 *
 * **Every field on it is `readOnly` in the contract, and that is the whole shape of this table.**
 * `GatedReadViewSet` gives it `GET` and nothing else, so there is no write DTO in this file to pair
 * with this one - unlike every other `Django*Backend` in this package, which has a `*Write` beside
 * each `*Row`.
 *
 * [txnDate] is a bare DATE (`"2026-08-04"`), not a timestamp - the same column
 * `SupabaseLedgerBackend` reads, and it is converted at UTC midnight in both transports so a row
 * cannot shift a day by changing which one fetched it. [reversalOf] is on the wire and dropped by
 * [engineSyncedJson]'s `ignoreUnknownKeys`, because [RemoteLedgerTransaction] has no such field.
 */
@Serializable
private data class DjangoLedgerTransactionRow(
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
    @SerialName("category_pending") val categoryPending: Boolean,
    @SerialName("pending_logged_at") val pendingLoggedAt: String? = null,
    val provenance: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("origin_guid") val originGuid: String? = null,
) {
    fun toRemote() = RemoteLedgerTransaction(
        serverId = id,
        statementId = statementId,
        accountLast4 = accountLast4,
        accountNickname = accountNickname,
        currency = currency,
        txnDateEpochMs = ledgerParseDate(txnDate),
        description = description,
        amountCents = amountCents,
        balanceCents = balanceCents,
        lineRef = lineRef,
        category = category,
        categoryPending = categoryPending,
        pendingLoggedAtMs = pendingLoggedAt?.let { ledgerParseTs(it) },
        provenance = provenance,
        createdAtMs = ledgerParseTs(createdAt),
        originGuid = originGuid,
    )
}

/**
 * [LedgerBackend] over the household Django engine - **read-only, because the table is.**
 *
 * `ledger_transactions` is one of the five tables `server/api/synced.py`'s `GatedReadViewSet` owns:
 * its detail route carries `get` and nothing else, so a PUT or DELETE reaches
 * `http_method_not_allowed` and is answered with the gate's own address. The single write path into
 * this table is `POST /api/ingest/statement`, which runs CLAUDE.md section 4's gate server-side.
 * **This class therefore offers no write function that could be called by accident** - see
 * [uploadMigratedTransaction], the one place the interface asks for one, for what happens instead.
 *
 * **Two departures from the generic synced shape, both forced by the schema rather than chosen:**
 *
 * - **No `?active=1`.** `GatedReadViewSet` sets `has_tombstones = False` because this table has no
 *   `deleted_at` column at all - it is append-only (the `forbid_mutation_of_facts` trigger blocks
 *   `UPDATE` outright and blocks `DELETE` except on an `UNRECONCILED` row), so "active" here is
 *   simply "exists", exactly as [RemoteLedgerTransaction]'s own doc comment states. Both fetches
 *   below go through [EngineSyncedTable.fetchChangedSince], never `fetchActive`, and a full fetch is
 *   a `since`-less one.
 * - **`?since=` filters `created_at`, not `updated_at`.** `GatedReadViewSet` sets
 *   `cursor_field = "created_at"` for the same reason - there is no `updated_at` to sort by. That
 *   matches [LedgerBackend.fetchChangedTransactionsSince]'s own contract (`created_at >= sinceMs`,
 *   inclusive) exactly, so [com.kevin.legion.backend.LedgerTransactionsSync]'s insert-if-absent
 *   merge behaves identically on either transport.
 */
class DjangoLedgerBackend(http: EngineHttp) : LedgerBackend {

    private val transactions = EngineSyncedTable(
        http = http,
        path = TRANSACTIONS_PATH,
        rowSerializer = DjangoLedgerTransactionRow.serializer(),
        idOf = { it.id },
    )

    /** Every row, ever. A null `since` is "fetch everything" to `api/sync.parse_since`, never
     * "fetch nothing" - the rule that module holds for the whole API. */
    override suspend fun fetchActiveTransactions(): Result<List<RemoteLedgerTransaction>> =
        translatingEngineCall("load your transactions") {
            transactions.fetchChangedSince(null).map { it.toRemote() }
        }

    override suspend fun fetchChangedTransactionsSince(sinceMs: Long): Result<List<RemoteLedgerTransaction>> =
        translatingEngineCall("load changed transactions") {
            transactions.fetchChangedSince(ledgerTs(sinceMs)).map { it.toRemote() }
        }

    /**
     * **Refused without sending anything, because there is no route to send it to.**
     *
     * The Supabase transport could insert a migrated row directly (`SupabaseLedgerBackend` does a
     * select-then-insert on `origin_guid`); the engine cannot be asked to, by design. Every row in
     * `ledger_transactions` arrives through `POST /api/ingest/statement` and no other way, so an
     * upload that bypassed the gate has no endpoint - and inventing one by POSTing to the list route
     * would earn a 405 naming the gate, which is the server saying the same thing this refusal says.
     *
     * The refusal happens BEFORE the request, matching
     * [DjangoEventsBackend.uploadMigratedEvent]'s own pre-flight refusal for skip dates: the check
     * costs one comparison rather than a round trip, and the sentence can name the endpoint that
     * WOULD take this data, which a bare 405 body would not.
     *
     * [HTTP_NOT_FOUND] rather than 405 is the status this file reports it under, matching the
     * convention [HTTP_NOT_FOUND]'s own doc comment records for
     * [DjangoEventsBackend]'s missing endpoints - the code is not the server's here, it is this
     * client's own "there is no such endpoint", and using 405 would imply a real response was read.
     */
    override suspend fun uploadMigratedTransaction(txn: MigratedLedgerTransaction): Result<Boolean> =
        Result.failure(
            EngineHttpException(
                EngineFailure.Refused(
                    status = HTTP_NOT_FOUND,
                    body = "The engine takes no direct upload into ledger_transactions - that table " +
                        "is behind the section 4 gate and only POST /api/ingest/statement writes it. " +
                        "Nothing was sent and nothing was migrated.",
                ),
            ),
        )
}
