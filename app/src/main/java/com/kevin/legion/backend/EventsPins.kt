package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.calendar.SuggestionPin
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.OutboxEntry
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.data.local.OutboxTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a pin or unpin tap did to this phone. [Applied] may be [queued]: the local row already
 * shows the change and the engine has not heard it; [Refused] changed nothing. */
sealed interface PinWrite {
    data class Applied(val row: Event, val queued: Boolean) : PinWrite

    data class Refused(val sentence: String) : PinWrite
}

/**
 * A queued pin or unpin. Carries the phone's own [userId] as it was when the tap happened, so a
 * later drain can roll back exactly that member's entry even if the signed-in account changed.
 */
@Serializable
internal data class EventPinOutboxPayload(val serverId: String, val userId: String)

/**
 * Suggestion pins (Kevin, 2026-10-09: *"pin it on the household so i can see which ones she wanna
 * go to"*), the write half. A pin is one member's "I want to go" on one `kind = suggestion` event;
 * the engine is the only place it truly exists, so this is offline-first the same way
 * [EventsAppointmentWriter] is: the local [Event.pinnedByJson] changes at once, the engine call is
 * tried at once, and a call that could not be made is queued ([OutboxOperation.PIN] /
 * [OutboxOperation.UNPIN]) for [EventsOutboxDrain].
 *
 * **A row that has never reached the engine cannot be pinned.** Unlike a create, a pin names a
 * server row, and nothing in the outbox can resolve a server id later for it, so the tap is
 * refused in words instead of queued against nothing. In practice a suggestion only ever arrives
 * FROM the engine, so this is a guard, not a path anyone walks.
 *
 * **The optimistic change never touches `updatedAtMs`**, so a pull of the same row (server stamp
 * equal or newer) merges over it rather than being skipped as "local newer" - the engine's
 * `pinned_by` always wins, except while a pin op for that row is still queued
 * ([rowsWithPendingPin], read by [EventsSync.pull]).
 */
object EventsPins {

    /** The two outbox operations this file owns. */
    internal val OPERATIONS = setOf(OutboxOperation.PIN, OutboxOperation.UNPIN)

    private const val STATUS_NOT_A_SUGGESTION = 400
    private const val STATUS_NOT_FOUND = 404

    private fun backend(context: Context): EventsBackend? =
        EventsAppointmentWriter.backendOverride ?: EngineBackends(context).eventsBackendNow()

    /** The local row ids with a pin or unpin still waiting to be sent. */
    suspend fun rowsWithPendingPin(db: CarDatabase): Set<Long> =
        db.outboxDao().pendingForTable(OutboxTarget.EVENTS, Int.MAX_VALUE)
            .filter { it.operation in OPERATIONS }
            .map { it.localId }
            .toSet()

    /** A 400 (no longer a suggestion) or 404 (gone / not visible) will never succeed on a retry. */
    internal fun isTerminalRefusal(error: Throwable?): Boolean {
        val failure = (error as? EngineHttpException)?.failure as? EngineFailure.Refused ?: return false
        return failure.status == STATUS_NOT_A_SUGGESTION || failure.status == STATUS_NOT_FOUND
    }

    /**
     * Pins ([pinned] true) or unpins the signed-in member on [existing]. Returns what happened in
     * enough detail for the caller to say so: sent, queued, or refused with the reason.
     */
    suspend fun setPinned(context: Context, existing: Event, pinned: Boolean): PinWrite {
        val verb = if (pinned) "pinned" else "unpinned"
        val backend = backend(context)
        val serverId = existing.serverId
        val me = EngineConfig(context.applicationContext).userId()
        if (backend == null || serverId == null || me == null) {
            return PinWrite.Refused(unreachableSentence(backend == null, serverId == null, verb))
        }

        val db = CarDatabase.getDatabase(context)
        val optimistic = existing.copy(pinnedByJson = SuggestionPin.withMine(existing.pinnedByJson, me, pinned))
        db.eventDao().update(optimistic)

        val result = if (pinned) backend.pin(serverId) else backend.unpin(serverId)
        val remote = result.getOrNull()
        return when {
            remote != null -> {
                val settled = optimistic.copy(pinnedByJson = remote.pinnedByJson ?: optimistic.pinnedByJson)
                db.eventDao().update(settled)
                PinWrite.Applied(settled, queued = false)
            }
            isTerminalRefusal(result.exceptionOrNull()) -> {
                db.eventDao().update(optimistic.copy(pinnedByJson = existing.pinnedByJson))
                PinWrite.Refused(
                    "Nothing was $verb: that is no longer a suggestion, or it is no longer on the engine.",
                )
            }
            else -> {
                enqueue(db, optimistic.id, serverId, me, pinned, result.exceptionOrNull()?.message)
                PinWrite.Applied(optimistic, queued = true)
            }
        }
    }

    /** Why a tap could not even start, in words; the first thing missing wins. */
    private fun unreachableSentence(noEngine: Boolean, notSynced: Boolean, verb: String): String = when {
        noEngine -> "Not connected to the engine, so it can't be $verb. Nothing was changed."
        notSynced -> "Not on the engine yet, so it can't be pinned. Try again after it syncs."
        else -> "Not signed in to the engine, so it can't be $verb. Nothing was changed."
    }

    /** Latest tap wins: an older queued pin or unpin for the same row is dropped first, so a
     * pin-then-unpin offline sends one DELETE, not two calls in an order that could matter. */
    private suspend fun enqueue(
        db: CarDatabase,
        localId: Long,
        serverId: String,
        userId: String,
        pinned: Boolean,
        error: String?,
    ) {
        val dao = db.outboxDao()
        dao.pendingForTable(OutboxTarget.EVENTS, Int.MAX_VALUE)
            .filter { it.operation in OPERATIONS && it.localId == localId }
            .forEach { dao.delete(it.id) }
        val payload = EventPinOutboxPayload(serverId, userId)
        dao.insert(
            OutboxEntry(
                targetTable = OutboxTarget.EVENTS,
                operation = if (pinned) OutboxOperation.PIN else OutboxOperation.UNPIN,
                localId = localId,
                payload = Json.encodeToString(EventPinOutboxPayload.serializer(), payload),
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                lastError = error,
            ),
        )
    }

    /** [EventsOutboxDrain]'s send of one queued pin or unpin. When the engine accepts it, its own
     * `pinned_by` list replaces the optimistic one on the row. */
    internal suspend fun drainOne(db: CarDatabase, entry: OutboxEntry, backend: EventsBackend): Result<RemoteEvent> {
        val payload = Json.decodeFromString(EventPinOutboxPayload.serializer(), entry.payload)
        val result = if (entry.operation == OutboxOperation.PIN) {
            backend.pin(payload.serverId)
        } else {
            backend.unpin(payload.serverId)
        }
        val settled = result.getOrNull()?.pinnedByJson
        val row = if (settled == null) null else db.eventDao().getById(entry.localId)
        if (row != null && row.pinnedByJson != settled) db.eventDao().update(row.copy(pinnedByJson = settled))
        return result
    }

    /** True when [error] is a terminal refusal of a queued pin: the entry is deleted and the
     * optimistic pin rolled back. False means leave it for the ordinary retry accounting. */
    internal suspend fun dropIfRefused(db: CarDatabase, entry: OutboxEntry, error: Throwable?): Boolean {
        if (entry.operation !in OPERATIONS || !isTerminalRefusal(error)) return false
        rollBackRefused(db, entry)
        db.outboxDao().delete(entry.id)
        return true
    }

    /**
     * A drained pin the engine refused for good: the optimistic pin is taken back off the row (an
     * unpin that was refused needs nothing - the row already shows no pin from this member).
     */
    private suspend fun rollBackRefused(db: CarDatabase, entry: OutboxEntry) {
        if (entry.operation != OutboxOperation.PIN) return
        val payload = Json.decodeFromString(EventPinOutboxPayload.serializer(), entry.payload)
        val row = db.eventDao().getById(entry.localId) ?: return
        db.eventDao().update(
            row.copy(pinnedByJson = SuggestionPin.withMine(row.pinnedByJson, payload.userId, pinned = false)),
        )
    }
}
