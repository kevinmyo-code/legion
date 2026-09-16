package com.kevin.legion.checklists

import android.content.Context
import com.kevin.legion.data.local.CarDatabase

/**
 * Fetches every tick across every checklist ever created - live or soft-deleted, ticket 03's "read
 * through tombstones" - and hands the rows to [matchTickHistory]. All the judgement lives in
 * `TickMatch.kt`, which is pure and tested; this file only fetches, the same split
 * [com.kevin.legion.outstanding.OutstandingController] already uses on this codebase.
 *
 * **Nothing new is written and nothing new is read that did not already exist.**
 * [com.kevin.legion.data.local.ChecklistTick.tickedAt] is the column ticket 04's brief names by
 * hand ("it looks back at when it was ticked" - the wall-clock tap, not
 * [com.kevin.legion.data.local.ChecklistTick.day], the day the tick counts for). No migration, on
 * either side (map.md's own finding).
 */
object TickHistoryController {

    private fun db(context: Context) = CarDatabase.getDatabase(context)

    /**
     * Every past tick whose item text matches [query] (ticket 03's normalised-text rule), newest
     * first.
     *
     * Walks EVERY checklist ever created -
     * [com.kevin.legion.data.local.ChecklistDao.getAllIncludingDeleted] - and every item that ever
     * belonged to each, live or tombstoned
     * ([com.kevin.legion.data.local.ChecklistItemDao.forChecklistIncludingDeleted]), so a list
     * Kevin cleared after finishing it still answers - ticket 03's "read through tombstones", and
     * the whole premise map.md's finding rests on: deleting a checklist does not cascade to its
     * items or ticks.
     *
     * Only [com.kevin.legion.data.local.ChecklistTickDao.allForItem]'s rows count, which already
     * filters `deleted = 0` - an unticked tick is not a record of a tap that still stands.
     */
    suspend fun lastTicked(context: Context, query: String): List<TickMatch> {
        val checklistDao = db(context).checklistDao()
        val itemDao = db(context).checklistItemDao()
        val tickDao = db(context).checklistTickDao()

        val candidates = mutableListOf<TickMatch>()
        for (checklist in checklistDao.getAllIncludingDeleted()) {
            for (item in itemDao.forChecklistIncludingDeleted(checklist.id)) {
                for (tick in tickDao.allForItem(item.id)) {
                    candidates += TickMatch(
                        itemId = item.id,
                        itemText = item.text,
                        checklistName = checklist.name,
                        tickedAt = tick.tickedAt,
                    )
                }
            }
        }
        return matchTickHistory(query, candidates)
    }
}
