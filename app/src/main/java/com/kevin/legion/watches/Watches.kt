package com.kevin.legion.watches

import kotlinx.serialization.json.JsonObject

/**
 * Standing watches (ADR 0083, `.scratch/watches/`): "Alfred, tell me when my paycheck lands". A
 * watch is one kind from the ENGINE's closed registry (`server/watches/registry.py`) with typed
 * params, evaluated deterministically on the engine (or, for [KIND_MAIL_REPLY], on this phone);
 * the model only turns a request into a kind and words the line when one fires.
 *
 * The engine is the store: this phone keeps no watch table and no Room row (the Watches screen and
 * the voice tool both read the engine through [WatchesController]).
 */
data class Watch(
    val id: String,
    val kind: String,
    val title: String,
    val label: String,
    val params: JsonObject,
    val repeat: Boolean,
    val paused: Boolean,
    /** on, paused, done (a one-shot that fired) or expired. */
    val status: String,
    /** The built-in it is (an engine signal), or null for one the member set. */
    val builtin: String?,
    /** engine or phone. */
    val evaluatedOn: String,
    val lastFiredAtMs: Long?,
)

/** One kind as the engine's registry describes it, for the screen's add form. */
data class WatchKind(
    val kind: String,
    val title: String,
    val says: String,
    val needs: String,
    val evaluatedOn: String,
)

data class WatchList(val watches: List<Watch>, val kinds: List<WatchKind>)

/** A watch the engine just wrote, and what it cannot see yet in words (null when nothing). */
data class WatchSet(val watch: Watch, val note: String?)

/**
 * One fact a watch found, as the engine worded it from its own rows ([facts]); [watchLabel] is the
 * watch that found it, which is what "why did you say that?" names.
 */
data class WatchFiring(
    val id: String,
    val watchId: String,
    val watchLabel: String,
    val kind: String,
    val facts: String,
    val firedAtMs: Long,
    val expiresAtMs: Long,
)

/** The four ways an engine call can end, the [com.kevin.legion.memory.MemoryOutcome] shape. */
sealed interface WatchOutcome<out T> {
    data class Ok<T>(val value: T) : WatchOutcome<T>
    data class Unreachable(val sentence: String) : WatchOutcome<Nothing>
    data class Refused(val sentence: String, val status: Int = 0) : WatchOutcome<Nothing>
    data class Unconfirmed(val sentence: String) : WatchOutcome<Nothing>
}

/** The engine's `/api/watches/` routes, behind a seam so the controller is tested with a fake. */
interface WatchesBackend {
    suspend fun list(): WatchOutcome<WatchList>
    suspend fun create(kind: String, params: JsonObject, label: String?, source: String): WatchOutcome<WatchSet>
    suspend fun update(id: String, fields: JsonObject): WatchOutcome<Watch>
    suspend fun delete(id: String): WatchOutcome<Unit>
    suspend fun pending(): WatchOutcome<List<WatchFiring>>
    suspend fun recent(): WatchOutcome<List<WatchFiring>>
    suspend fun ack(ids: List<String>, outcome: String): WatchOutcome<Int>
}

const val KIND_CHARGE_OVER = "charge_over"
const val KIND_DEPOSIT_OVER = "deposit_over"
const val KIND_BALANCE_UNDER = "balance_under"
const val KIND_CANVAS_DUE = "canvas_due"
const val KIND_GRADE_POSTED = "grade_posted"
const val KIND_SERVICE_DUE = "service_due"
const val KIND_MAIL_REPLY = "mail_reply"

const val SOURCE_VOICE = "voice"
const val SOURCE_SCREEN = "screen"

const val OUTCOME_SPOKEN = "spoken"
const val OUTCOME_NOTIFIED = "notified"
const val OUTCOME_SILENCED = "silenced"
