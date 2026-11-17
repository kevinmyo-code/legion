package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import kotlinx.serialization.json.JsonObject

/**
 * What the ENGINE wrote to the homes (its agent tools: set_goal, close_goal,
 * log_pending_transaction, clear_pending_transaction) brought down into Room by `?since=`, so the
 * phone's replica shows it offline (engine-brain ticket 03). Advice and raises are written only by
 * the phone today; they get a pull when the engine starts writing them (tickets 08 and 12).
 */
object HomesPull {
    private const val KEY_CURSOR = "pull_since_"

    /** What one pull changed in Room, in counts only; [reached] is false when the engine did not answer. */
    data class PullReport(val reached: Boolean, val inserted: Int = 0, val updated: Int = 0, val removed: Int = 0) {
        fun line(table: HomeTable): String =
            "homes_pull table=${table.key} reached=$reached inserted=$inserted updated=$updated removed=$removed"
    }

    private fun cursor(context: Context, table: HomeTable): String? =
        context.getSharedPreferences(HomesSync.PREFS, Context.MODE_PRIVATE).getString(KEY_CURSOR + table.key, null)

    private fun advance(context: Context, table: HomeTable, rows: List<JsonObject>) {
        val newest = rows.mapNotNull { HomesFromEngine.textOf(it, "updated_at") }.maxOrNull() ?: return
        context.getSharedPreferences(HomesSync.PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CURSOR + table.key, newest).apply()
    }

    /** New engine goals are inserted; a goal the engine changed later than Room did is updated. */
    internal suspend fun goals(context: Context, backend: HomesBackend): PullReport {
        val remote = backend.changedSince(HomeTable.GOALS, cursor(context, HomeTable.GOALS))
            .getOrElse { return PullReport(reached = false) }
        var inserted = 0
        var updated = 0
        val dao = CarDatabase.getDatabase(context).goalDao()
        val local = dao.getAllIncludingDeleted().associateBy { it.syncId }.toMutableMap()
        for (row in remote) {
            val supersedes = HomesFromEngine.textOf(row, "supersedes_guid")?.let { local[it]?.id }
            val incoming = HomesFromEngine.goal(row, supersedes) ?: continue
            val existing = local[incoming.syncId]
            when {
                existing == null && !incoming.deleted -> {
                    local[incoming.syncId] = incoming.copy(id = dao.insert(incoming))
                    inserted++
                }
                existing != null && incoming.updatedAt > existing.updatedAt -> {
                    dao.update(merged(existing, incoming))
                    updated++
                }
            }
        }
        advance(context, HomeTable.GOALS, remote)
        return PullReport(reached = true, inserted = inserted, updated = updated)
    }

    private fun merged(existing: Goal, incoming: Goal): Goal = existing.copy(
        statement = incoming.statement,
        targetValue = incoming.targetValue,
        unit = incoming.unit,
        metricKey = incoming.metricKey,
        deadlineEpoch = incoming.deadlineEpoch,
        status = incoming.status,
        closedAt = incoming.closedAt,
        updatedAt = incoming.updatedAt,
        serverId = incoming.serverId,
        deleted = incoming.deleted,
    )

    /** An engine pending row Room lacks is inserted (unless this phone is clearing it); a cleared
     * one is removed from Room through the `pendingLoggedAt` guard. */
    internal suspend fun pending(context: Context, backend: HomesBackend): PullReport {
        val remote = backend.changedSince(HomeTable.PENDING, cursor(context, HomeTable.PENDING))
            .getOrElse { return PullReport(reached = false) }
        var inserted = 0
        var removed = 0
        val dao = CarDatabase.getDatabase(context).ledgerTransactionDao()
        val have = dao.pendingRows().map { it.syncId }.toSet()
        val clearing = HomesWriteThrough.queuedClears(context)
        for (row in remote) {
            val guid = HomesFromEngine.guidOf(row)
            when {
                guid == null -> Unit
                HomesFromEngine.isDeleted(row) -> if (guid in have) removed += dao.deletePendingBySyncId(guid)
                guid !in have && guid !in clearing -> HomesFromEngine.pending(row)?.let {
                    dao.insertAll(listOf(it))
                    inserted++
                }
            }
        }
        advance(context, HomeTable.PENDING, remote)
        return PullReport(reached = true, inserted = inserted, removed = removed)
    }
}
