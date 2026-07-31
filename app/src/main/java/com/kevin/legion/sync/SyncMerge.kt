package com.kevin.legion.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure (Android-free) merge planner for cross-device sync (S1). Given the local
 * and remote snapshots of one table, decides what to apply locally - the whole
 * conflict-resolution brain, split out from [SyncEngine]'s DB I/O so it can be
 * unit-tested without a device.
 *
 * Never uses the local autoincrement `id` (not portable across devices); rows are
 * matched on [identity] (a natural key, or the portable `syncId`).
 *  - [Mode.UNION]: a remote row whose identity isn't present locally is inserted;
 *    existing rows are left alone (append-only history just accumulates).
 *  - [Mode.LWW]: a remote row wins only if its [clock] is strictly newer than the
 *    local row's, in which case the local row is updated.
 */
internal object SyncMerge {

    enum class Mode { UNION, LWW }

    sealed interface Action {
        /** Remote row has no local match - insert it. */
        data class Insert(val row: JSONObject) : Action
        /** Remote row wins LWW over the local row identified by [identity]. */
        data class Update(val row: JSONObject, val identity: Map<String, Any?>) : Action
    }

    /** The actions to apply to the LOCAL db so it absorbs [remote]. */
    fun plan(
        local: List<JSONObject>,
        remote: List<JSONObject>,
        identity: List<String>,
        mode: Mode,
        clock: String,
    ): List<Action> {
        val localByKey = HashMap<String, JSONObject>(local.size)
        for (row in local) localByKey[key(row, identity)] = row

        val actions = ArrayList<Action>()
        for (row in remote) {
            val localRow = localByKey[key(row, identity)]
            when {
                localRow == null -> actions.add(Action.Insert(row))
                mode == Mode.LWW && row.optLong(clock, 0L) > localRow.optLong(clock, 0L) ->
                    actions.add(Action.Update(row, identity.associateWith { valueOf(row, it) }))
                // UNION with the row already local, or LWW where local is >= remote: nothing to do.
            }
        }
        return actions
    }

    /**
     * Stable identity string. Serialized as a JSON array of the identity values so
     * column boundaries are unambiguous (proper escaping) - no separator-char
     * collision between e.g. ["a b","c"] and ["a","b c"].
     */
    private fun key(row: JSONObject, identity: List<String>): String {
        val arr = JSONArray()
        for (col in identity) arr.put(if (row.isNull(col)) JSONObject.NULL else row.get(col))
        return arr.toString()
    }

    private fun valueOf(row: JSONObject, key: String): Any? =
        if (row.isNull(key)) null else row.get(key)
}
