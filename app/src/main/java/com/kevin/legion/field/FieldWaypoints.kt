package com.kevin.legion.field

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OutboxEntry
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.location.PlaceController
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A waypoint as the field screen lists it: a saved place, or a mark still waiting on this phone. */
data class Waypoint(val label: String, val lat: Double, val lon: Double, val pending: Boolean)

/** What a mark did, in words, and whether it already reached saved places. */
data class MarkOutcome(val message: String, val synced: Boolean, val ok: Boolean)

@Serializable
internal data class WaypointPayload(val label: String, val lat: Double, val lon: Double, val markedAtMs: Long)

/**
 * Field waypoints are PLACES (CLAUDE.md section 1: seven aspects, an eighth is a ruling; offline
 * hiking ticket 08's recommendation): "car" is a saved place like "home". What this adds is
 * **local first**: a mark is written to the phone's own `sync_outbox` before anything else, so it
 * survives no signal, and drains into [PlaceController.savePlaceAt] (the same door as the Saved
 * places screen and `tag_place`) when the engine answers.
 *
 * **A deliberate exception to `PlaceController`'s write-through rule**, recorded in
 * `memory/library/decisions.md` (2026-11-10): a place tagged anywhere else is still write-through
 * with no queue. Only a field mark queues, because the field is where there is no signal. The
 * outbox table needs no migration: `targetTable` is TEXT with no CHECK (CLAUDE.md section 5).
 *
 * **Marking a name that already exists moves it** (re-parking moves "car"): the drain saves with
 * `confirmed = true`, and PlaceController's own sentence says what was replaced. The screen's Mark
 * button is the explicit act the voice path's confirm question stands in for.
 */
object FieldWaypoints {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** Waits on the phone this many failed tries, then stops and keeps its last error. */
    const val MAX_ATTEMPTS = 8

    const val PENDING_SENTENCE = "It is saved on this phone and joins your saved places when there is signal."

    /** Saved places (Room replica, offline) plus marks still waiting, a waiting mark winning a name. */
    suspend fun all(context: Context): List<Waypoint> {
        val pending = pendingPayloads(context).map { (_, p) -> Waypoint(p.label, p.lat, p.lon, pending = true) }
        val pendingLabels = pending.map { it.label.lowercase() }.toSet()
        val saved = PlaceController.all(context)
            .filter { !it.deleted && it.label.lowercase() !in pendingLabels }
            .map { Waypoint(it.label, it.latitude, it.longitude, false) }
        return pending + saved
    }

    /** Marks [label] at the point: local first, then one drain. */
    suspend fun mark(
        context: Context,
        label: String,
        lat: Double,
        lon: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): MarkOutcome {
        val clean = label.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) {
            return MarkOutcome("A waypoint needs a name; nothing was marked.", synced = false, ok = false)
        }
        val payload = WaypointPayload(clean, lat, lon, nowMs)
        val dao = CarDatabase.getDatabase(context).outboxDao()
        val id = lock.withLock {
            dao.insert(
                OutboxEntry(
                    targetTable = OutboxTarget.FIELD_WAYPOINTS,
                    operation = com.kevin.legion.data.local.OutboxOperation.UPSERT,
                    localId = 0L,
                    payload = json.encodeToString(WaypointPayload.serializer(), payload),
                    createdAt = nowMs,
                ),
            )
        }
        val results = drain(context)
        val mine = results[id]
        return when {
            mine is PlaceController.SaveOutcome.Saved -> MarkOutcome(mine.message, synced = true, ok = true)
            mine != null && isOnline(context) -> MarkOutcome(
                "Marked $clean on this phone, but saved places did not take it yet: ${mine.message} " +
                    "It will try again.",
                synced = false,
                ok = true,
            )
            else -> MarkOutcome("Marked $clean here. $PENDING_SENTENCE", synced = false, ok = true)
        }
    }

    /**
     * Sends every waiting mark through [PlaceController.savePlaceAt]. A mark is removed only when it
     * saved; otherwise it waits with its error, up to [MAX_ATTEMPTS] tries, and is never dropped
     * unsaid (an engine that is down looks like a refusal from here). Returns each tried entry's
     * outcome by outbox id.
     */
    suspend fun drain(context: Context): Map<Long, PlaceController.SaveOutcome> = lock.withLock {
        val dao = CarDatabase.getDatabase(context).outboxDao()
        val out = mutableMapOf<Long, PlaceController.SaveOutcome>()
        for ((entry, p) in pendingPayloads(context)) {
            val outcome = PlaceController.savePlaceAt(
                context, p.label, PlaceController.Spot(p.lat, p.lon, null), confirmed = true,
            )
            out[entry.id] = outcome
            if (outcome is PlaceController.SaveOutcome.Saved) {
                dao.delete(entry.id)
            } else {
                dao.recordAttempt(entry.id, entry.attempts + 1, outcome.message)
            }
        }
        out
    }

    private suspend fun pendingPayloads(context: Context): List<Pair<OutboxEntry, WaypointPayload>> =
        CarDatabase.getDatabase(context).outboxDao()
            .pendingForTable(OutboxTarget.FIELD_WAYPOINTS, MAX_ATTEMPTS)
            .mapNotNull { entry ->
                runCatching { json.decodeFromString(WaypointPayload.serializer(), entry.payload) }
                    .getOrNull()?.let { entry to it }
            }

    /** A validated internet connection, the only kind that can reach the engine. */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
}
