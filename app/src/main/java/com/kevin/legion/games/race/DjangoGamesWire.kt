@file:Suppress("TooManyFunctions") // one small parser per response shape; splitting scatters the contract

package com.kevin.legion.games.race

import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// The engine's `games` response shapes, read strictly (see DjangoGamesBackend's class comment).

/** A reply that must be one JSON object; anything else is an IllegalArgumentException, which the call wrapper words. */
private fun jsonObjectOf(body: String): JsonObject =
    Json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("the engine's reply was not a match")

/** A list reply: the engine's views return a bare JSON array (no pagination envelope). */
private fun rowsOf(body: String): List<JsonObject> {
    val array = Json.parseToJsonElement(body) as? JsonArray
        ?: throw IllegalArgumentException("the engine's reply was not a list")
    return array.filterIsInstance<JsonObject>()
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.require(key: String): String =
    requireNotNull(string(key)) { "the engine's reply carried no $key" }

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun instantMs(text: String?): Long? =
    text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** `PlayerSerializer`: score, lines, level, topped_out, finished (plus user_id, name, updated_at). */
private fun JsonElement?.toProgress(): PlayerProgress {
    val o = this as? JsonObject ?: throw IllegalArgumentException("the engine's match carried no player block")
    return PlayerProgress(
        score = (o["score"] as? JsonPrimitive)?.intOrNull ?: 0,
        lines = (o["lines"] as? JsonPrimitive)?.intOrNull ?: 0,
        level = (o["level"] as? JsonPrimitive)?.intOrNull ?: 1,
        toppedOut = (o["topped_out"] as? JsonPrimitive)?.booleanOrNull ?: false,
        finished = (o["finished"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}

private fun statusOf(text: String?): MatchStatus = when (text) {
    "invited" -> MatchStatus.INVITED
    "playing" -> MatchStatus.PLAYING
    "ended" -> MatchStatus.ENDED
    "declined" -> MatchStatus.DECLINED
    "cancelled" -> MatchStatus.CANCELLED
    "expired" -> MatchStatus.EXPIRED
    else -> throw IllegalArgumentException("the engine sent a match status this app does not know")
}

/** `result` is "won" | "lost" | "draw" for the caller, null until the match ends. */
private fun resultOf(text: String?): RaceResult? = when (text) {
    "won" -> RaceResult.WON
    "lost" -> RaceResult.LOST
    "draw" -> RaceResult.DRAW
    else -> null
}

/** `GET /api/games/opponents/`: `[{"user_id", "name"}]`. */
internal fun parseOpponents(body: String): List<Opponent> =
    rowsOf(body).map { Opponent(it.require("user_id"), it.require("name")) }

/**
 * `GET /api/games/matches/pending/`: a list of full matches seen by the invitee, so the inviter's
 * name is `opponent.name`.
 */
internal fun parsePending(body: String): List<PendingInvite> =
    rowsOf(body).map { row ->
        val inviter = row["opponent"] as? JsonObject
            ?: throw IllegalArgumentException("the engine's invite carried no inviter")
        PendingInvite(row.require("id"), inviter.require("name"))
    }

/**
 * `POST /api/games/matches/` (201): `{"match": {...}, "push": "sent|no_device|...", "detail": "..."}`.
 * [InviteSent.note] is the engine's own sentence about the notification, shown to the inviter.
 */
internal fun parseInvite(body: String): InviteSent {
    val reply = jsonObjectOf(body)
    val match = reply["match"] as? JsonObject
        ?: throw IllegalArgumentException("the engine's reply carried no match")
    return InviteSent(matchId = match.require("id"), note = reply.string("detail").orEmpty())
}

internal fun parseMatch(body: String): MatchState = toMatch(jsonObjectOf(body))

internal fun toMatch(o: JsonObject): MatchState {
    val opponent = o["opponent"] as? JsonObject
        ?: throw IllegalArgumentException("the engine's match carried no opponent")
    return MatchState(
        id = o.require("id"),
        status = statusOf(o.string("status")),
        seed = requireNotNull(o.long("seed")) { "the engine's match carried no seed" },
        opponentName = opponent.require("name"),
        startAtMs = instantMs(o.string("start_at")),
        endsAtMs = instantMs(o.string("ends_at")),
        serverNowMs = requireNotNull(instantMs(o.string("server_now"))) {
            "the engine's match carried no server_now"
        },
        me = o["you"].toProgress(),
        opponent = opponent.toProgress(),
        result = resultOf(o.string("result")),
    )
}

