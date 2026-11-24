package com.kevin.legion.backend

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.ProactiveRaiseRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The engine homes' write-through (engine-brain ticket 03): best-effort and fire-and-forget after
 * the local write committed, never failing it. A row that does not reach the engine is sent again
 * by the next [HomesSync.run], whose batch re-sends the Room table itself. A pending clear is the one
 * write the Room table cannot re-derive (the row is gone), so it is queued in preferences first and
 * dequeued only when the engine answered.
 */
object HomesWriteThrough {
    private const val KEY_CLEARS = "pending_clears"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun later(context: Context, block: suspend (HomesBackend) -> Unit) {
        val backend = HomesSync.backend(context) ?: return
        scope.launch {
            try {
                block(backend)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Best-effort by design: the next HomesSync.run re-sends the row from Room.
                Log.w(HomesSync.TAG, "homes_write_through_failed ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** After a goal was set, revised or closed: the lineage's rows, current one included. */
    fun goalLineageChanged(context: Context, lineageId: Long) = later(context) { backend ->
        val history = CarDatabase.getDatabase(context).goalDao().history(lineageId)
        val byId = history.associateBy { it.id }
        history.filter { !it.deleted }.forEach { row ->
            val supersedes = row.supersedesId?.let { byId[it]?.syncId }
            backend.put(HomeTable.GOALS, row.syncId, HomesRows.goal(row, supersedes))
        }
    }

    fun adviceAdded(context: Context, id: Long) = later(context) { backend ->
        CarDatabase.getDatabase(context).advisorAdviceDao().pending(id)?.let { row ->
            backend.put(HomeTable.ADVICE, row.syncId, HomesRows.advice(row))
        }
    }

    fun pendingLogged(context: Context, row: LedgerTransaction) = later(context) { backend ->
        HomesRows.pending(row)?.let { backend.put(HomeTable.PENDING, row.syncId, it) }
    }

    /** Queued first, so a clear made offline is still sent; dequeued only when the engine answered. */
    fun pendingCleared(context: Context, syncId: String) {
        val prefs = context.getSharedPreferences(HomesSync.PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val queued = prefs.getStringSet(KEY_CLEARS, emptySet()).orEmpty() + syncId
            prefs.edit().putStringSet(KEY_CLEARS, queued).apply()
        }
        later(context) { backend -> retryClears(context, backend) }
    }

    fun raiseRecorded(context: Context, row: ProactiveRaiseRow) = later(context) { backend ->
        backend.put(HomeTable.RAISES, HomesRows.raiseGuid(row.ruleId, row.spokenAt), HomesRows.raise(row))
    }

    fun raiseDeclined(context: Context, ruleId: String) = later(context) { backend ->
        CarDatabase.getDatabase(context).proactiveRaiseDao().lastForRule(ruleId)?.let { row ->
            backend.put(HomeTable.RAISES, HomesRows.raiseGuid(row.ruleId, row.spokenAt), HomesRows.raise(row))
        }
    }

    /** Sends every queued clear. The backend answers a clear of a row the engine never held (logged
     * and cleared offline) as done, so only an unreachable engine keeps one queued. */
    internal suspend fun retryClears(context: Context, backend: HomesBackend) {
        val prefs = context.getSharedPreferences(HomesSync.PREFS, Context.MODE_PRIVATE)
        val queued = prefs.getStringSet(KEY_CLEARS, emptySet()).orEmpty()
        val sent = queued.filter { backend.delete(HomeTable.PENDING, it).isSuccess }.toSet()
        if (sent.isEmpty()) return
        synchronized(this) {
            val left = prefs.getStringSet(KEY_CLEARS, emptySet()).orEmpty() - sent
            prefs.edit().putStringSet(KEY_CLEARS, left).apply()
        }
    }

    internal fun queuedClears(context: Context): Set<String> =
        context.getSharedPreferences(HomesSync.PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_CLEARS, emptySet()).orEmpty()
}
