package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.OutboxEntry
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.data.local.OutboxTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The push half of a category a PERSON sets on a ledger row (backend-etl ticket 14, option 2 - Kevin,
 * 2026-09-28: "yes 2"). `ledger_transactions` may never be updated on the server (the gate's
 * `forbid_mutation_of_facts` trigger), so the choice goes to `ledger_transaction_categories`, a
 * separate table the engine lays over the row on every read. Before this, a hand-set category never
 * left the phone.
 *
 * **Local write first, always** - [com.kevin.legion.ledger.LedgerController] has already written Room
 * by the time this runs, the same posture as [LedgerConfigWriteThrough].
 *
 * **Which rows can be sent:** only server-origin rows ([SYNCED_SOURCE_FILE]), whose `syncId` IS the
 * server's transaction id. A row this phone minted (a voice-logged pending charge, a statement it
 * parsed itself) has no server id to key an override on, so its category stays on this phone, as it
 * always did; [Report.phoneOnly] counts those and [Report.sentence] says so.
 *
 * **Which transport:** the Django engine only. Supabase has no such table, so on it nothing is sent
 * or queued - queueing would only retry into a refusal until the entry poisoned.
 *
 * **Server down:** the entry is queued ([OutboxTarget.LEDGER_TRANSACTION_CATEGORIES]) and
 * [LedgerTransactionCategoryOutboxDrain] retries it on resume and on SYNC NOW, BEFORE the mirror
 * runs. [Report.sentence] says it in words, and [pendingSentence] keeps saying it on the Money
 * screen until the queue drains (CLAUDE.md section 7).
 */
object LedgerTransactionCategoryWriteThrough {
    /** Test seam, same mechanism as [LedgerConfigWriteThrough.backendOverride]. */
    @Volatile
    internal var backendOverride: LedgerBackend? = null

    private fun backend(context: Context): LedgerBackend? {
        val app = context.applicationContext
        val onDjango = EngineTransport(app).transportFor(EngineBackends.ASPECT_LEDGER) == Transport.DJANGO
        return backendOverride ?: if (onDjango) EngineBackends(app).ledgerBackend() else null
    }

    @Serializable
    internal data class Payload(val serverId: String, val category: String)

    /** What one push did, and the sentence a surface says about it. */
    data class Report(val sent: Int, val queued: Int, val phoneOnly: Int) {
        /** Null when everything that could be sent was sent and nothing stayed behind. */
        fun sentence(): String? {
            val parts = mutableListOf<String>()
            if (queued > 0) {
                parts += "Saved on this phone; the server couldn't be reached, so " +
                    "${countOf(queued)} will be sent when it can"
            }
            if (phoneOnly > 0) {
                parts += "${countOf(phoneOnly)} not on the server yet, so that category stays on this phone"
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(". ", postfix = ".")
        }

        companion object {
            val NONE = Report(0, 0, 0)
        }
    }

    private fun countOf(n: Int) = if (n == 1) "1 transaction" else "$n transactions"

    /**
     * Sends [category] as a person's choice for every server-origin row in [rows]. One failure
     * stops the network calls for the rest of the batch - an unreachable server is unreachable for
     * the next row too, and a merchant recategorisation can touch a hundred rows - and everything
     * after it is queued without trying.
     *
     * A queued entry for the same row is cancelled first: the newest choice is the one that must
     * land, and a stale entry draining after it would put the old category back.
     */
    suspend fun push(context: Context, rows: List<LedgerTransaction>, category: String): Report {
        // Not on the engine: nothing to send to, exactly as before this existed, so nothing to say.
        val backend = backend(context) ?: return Report.NONE
        val sendable = rows.filter { it.sourceFile == SYNCED_SOURCE_FILE }
        val phoneOnly = rows.size - sendable.size

        val db = CarDatabase.getDatabase(context)
        var sent = 0
        var queued = 0
        var unreachable: String? = null
        for (row in sendable) {
            cancelPending(db, row.id)
            val error = unreachable ?: backend.setTransactionCategory(row.syncId, category)
                .exceptionOrNull()?.let { it.message ?: "failed, with no message" }
            if (error == null) {
                sent++
            } else {
                unreachable = error
                enqueue(db, row.id, Payload(row.syncId, category), error)
                queued++
            }
        }
        return Report(sent, queued, phoneOnly)
    }

    private suspend fun cancelPending(db: CarDatabase, localId: Long) {
        val dao = db.outboxDao()
        dao.pendingForTable(OutboxTarget.LEDGER_TRANSACTION_CATEGORIES, Int.MAX_VALUE)
            .filter { it.localId == localId }
            .forEach { dao.delete(it.id) }
    }

    private suspend fun enqueue(db: CarDatabase, localId: Long, payload: Payload, error: String?) {
        db.outboxDao().insert(
            OutboxEntry(
                targetTable = OutboxTarget.LEDGER_TRANSACTION_CATEGORIES,
                operation = OutboxOperation.UPSERT,
                localId = localId,
                payload = Json.encodeToString(Payload.serializer(), payload),
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                lastError = error,
            ),
        )
    }

    /** The server ids of rows whose person-set category is still queued. [LedgerTransactionsSync.mirror]
     * reads this so an older server category cannot overwrite a newer choice that has not landed. */
    suspend fun pendingServerIds(context: Context): Set<String> =
        CarDatabase.getDatabase(context).outboxDao()
            .pendingForTable(OutboxTarget.LEDGER_TRANSACTION_CATEGORIES, Int.MAX_VALUE)
            .mapNotNull { runCatching { Json.decodeFromString(Payload.serializer(), it.payload).serverId }.getOrNull() }
            .toSet()

    /**
     * The Money screen's standing line while any hand-set category is still queued, or null. Counts
     * poisoned entries too: a choice that will never be retried has still not reached the server,
     * and saying nothing would read as if it had.
     */
    suspend fun pendingSentence(context: Context): String? {
        val pending = CarDatabase.getDatabase(context).outboxDao()
            .pendingForTable(OutboxTarget.LEDGER_TRANSACTION_CATEGORIES, Int.MAX_VALUE)
        if (pending.isEmpty()) return null
        val stuck = pending.count { it.attempts >= LedgerTransactionCategoryOutboxDrain.MAX_ATTEMPTS }
        val base = "${countOf(pending.size)} with a category you set ${if (pending.size == 1) "is" else "are"} " +
            "saved on this phone and not yet on the server"
        return if (stuck == 0) "$base." else "$base; $stuck stopped retrying after repeated refusals."
    }
}

/**
 * Retries every queued person-set category. Runs BEFORE the mirror on resume and on SYNC NOW, so
 * the mirror then reads the server with the person's choice already in it - the same drain-then-pull
 * order every other outbox in this package keeps.
 */
object LedgerTransactionCategoryOutboxDrain {
    const val MAX_ATTEMPTS = EventsOutboxDrain.MAX_ATTEMPTS

    data class DrainReport(val succeeded: Int, val stillPending: Int, val poisoned: Int)

    suspend fun drain(context: Context, backend: LedgerBackend): DrainReport {
        val dao = CarDatabase.getDatabase(context).outboxDao()
        val pending = dao.pendingForTable(OutboxTarget.LEDGER_TRANSACTION_CATEGORIES, MAX_ATTEMPTS)
        var succeeded = 0
        var stillPending = 0
        var poisoned = 0
        for (entry in pending) {
            val payload = Json.decodeFromString(
                LedgerTransactionCategoryWriteThrough.Payload.serializer(),
                entry.payload,
            )
            val result = backend.setTransactionCategory(payload.serverId, payload.category)
            if (result.isSuccess) {
                dao.delete(entry.id)
                succeeded++
                continue
            }
            val attempts = entry.attempts + 1
            dao.recordAttempt(entry.id, attempts, result.exceptionOrNull()?.message ?: "unknown error")
            if (attempts >= MAX_ATTEMPTS) poisoned++ else stillPending++
        }
        return DrainReport(succeeded, stillPending, poisoned)
    }

    /** `MainActivity.onResume`'s hook. Django only, silently nothing otherwise. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun maybeDrain(context: Context) {
        val app = context.applicationContext
        if (EngineTransport(app).transportFor(EngineBackends.ASPECT_LEDGER) != Transport.DJANGO) return
        val backend = EngineBackends(app).ledgerBackend() ?: return
        try {
            drain(app, backend)
        } catch (e: Exception) {
            android.util.Log.w("LedgerCategoryDrain", "drain failed: ${e.message}")
        }
    }
}
