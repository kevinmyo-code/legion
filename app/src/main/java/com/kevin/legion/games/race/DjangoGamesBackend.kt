package com.kevin.legion.games.race

import android.content.Context
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.engineRefusalSentence
import com.kevin.legion.backend.engine.translatingEngineCall
import io.ktor.http.encodeURLPathPart
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val MATCHES_PATH = "/api/games/matches/"
private const val OPPONENTS_PATH = "/api/games/opponents/"

/**
 * [GamesBackend] over the household engine's `games` app (ticket 05, ADR 0060).
 *
 * **The wire shapes below are the contract as ticket 05 words it, not measured against a running
 * engine** - that ticket was being built in parallel when this was written and `server/games` did
 * not exist yet. The URLs and the request bodies follow its table. The RESPONSES are therefore
 * read leniently on purpose: a field is looked up under the names it plausibly carries, a missing
 * one takes a default, and an unreadable match becomes a failure sentence rather than a guess. The
 * first race against a real engine must confirm the names; every one that is a guess is marked
 * "guess" in [toMatch] and [toProgress].
 */
class DjangoGamesBackend(private val http: EngineHttp) : GamesBackend {

    override suspend fun opponents(): Result<List<Opponent>> =
        translatingEngineCall("load who you can race") {
            val rows = rowsOf(http.get(OPPONENTS_PATH).getOrThrow().body)
            rows.mapNotNull { row ->
                val id = row.string("id", "user_id") ?: return@mapNotNull null
                Opponent(id, row.string("name", "display_name", "username", "email") ?: "Someone")
            }
        }

    override suspend fun pending(): Result<List<PendingInvite>> =
        translatingEngineCall("check for Tetris invites") {
            rowsOf(http.get("${MATCHES_PATH}pending/").getOrThrow().body).mapNotNull { row ->
                val id = row.string("id", "match_id") ?: return@mapNotNull null
                PendingInvite(id, row.string("inviter_name", "from_name", "inviter") ?: "Someone")
            }
        }

    override suspend fun invite(opponentUserId: String): Result<String> =
        translatingEngineCall("send that invite") {
            val body = buildJsonObject { put("opponent_user_id", opponentUserId) }.toString()
            val reply = jsonObjectOf(http.post(MATCHES_PATH, body).getOrThrow().body)
            reply.string("id", "match_id") ?: throw IllegalArgumentException("the engine's reply carried no match id")
        }

    override suspend fun accept(matchId: String): Result<Unit> = action(matchId, "accept", "accept that invite")

    override suspend fun decline(matchId: String): Result<Unit> = action(matchId, "decline", "decline that invite")

    override suspend fun cancel(matchId: String): Result<Unit> = action(matchId, "cancel", "cancel that invite")

    override suspend fun progress(matchId: String, progress: PlayerProgress): Result<Unit> =
        translatingEngineCall("send your score") {
            val body = buildJsonObject {
                put("score", progress.score)
                put("lines", progress.lines)
                put("level", progress.level)
                put("topped_out", progress.toppedOut)
                put("finished", progress.finished)
            }.toString()
            http.post("${path(matchId)}progress/", body).getOrThrow()
            Unit
        }

    override suspend fun match(matchId: String): Result<MatchState> =
        translatingEngineCall("load the match") {
            toMatch(jsonObjectOf(http.get(path(matchId)).getOrThrow().body))
        }

    private suspend fun action(matchId: String, verb: String, what: String): Result<Unit> =
        translatingEngineCall(what) {
            http.post("${path(matchId)}$verb/", "{}").getOrThrow()
            Unit
        }

    private fun path(matchId: String) = "$MATCHES_PATH${matchId.encodeURLPathPart()}/"
}

/**
 * The backend for this device's engine, or null when there is no engine address or no signed-in
 * token. Null is the "race unavailable, said in words" case; solo never asks.
 */
fun djangoGamesBackendOrNull(context: Context): GamesBackend? {
    val http = EngineHttp(EngineConfig(context.applicationContext))
    return if (http.isUsable()) DjangoGamesBackend(http) else null
}

/** Why a race cannot start on a phone with no usable engine. */
const val NO_ENGINE_SENTENCE =
    "Racing needs the household engine, and this phone is not signed in to one. Solo still works."

/** A reply that must be one JSON object; anything else is an IllegalArgumentException, which the call wrapper words. */
private fun jsonObjectOf(body: String): JsonObject =
    Json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("the engine's reply was not a match")

/** The rows of a list reply, whether the engine paginates (`{"results": [...]}`) or not. */
private fun rowsOf(body: String): List<JsonObject> {
    val array = when (val root = Json.parseToJsonElement(body)) {
        is JsonArray -> root
        is JsonObject -> root["results"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return array.filterIsInstance<JsonObject>()
}

private fun JsonObject.string(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun instantMs(text: String?): Long? =
    text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

private fun JsonElement?.toProgress(): PlayerProgress {
    val o = this as? JsonObject ?: return PlayerProgress()
    // guess: score, lines, level, topped_out, finished - the names ticket 05 uses for the progress POST.
    return PlayerProgress(
        score = (o["score"] as? JsonPrimitive)?.intOrNull ?: 0,
        lines = (o["lines"] as? JsonPrimitive)?.intOrNull ?: 0,
        level = (o["level"] as? JsonPrimitive)?.intOrNull ?: 1,
        toppedOut = (o["topped_out"] as? JsonPrimitive)?.booleanOrNull ?: false,
        finished = (o["finished"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}

private fun statusOf(text: String?): MatchStatus = when (text?.lowercase()) {
    "invited" -> MatchStatus.INVITED
    "playing" -> MatchStatus.PLAYING
    "ended" -> MatchStatus.ENDED
    "declined" -> MatchStatus.DECLINED
    "cancelled" -> MatchStatus.CANCELLED
    "expired" -> MatchStatus.EXPIRED
    else -> throw IllegalArgumentException("the engine sent a match status this app does not know")
}

// guess: `result` is "won" | "lost" | "draw" for the caller, null until the match ends.
private fun resultOf(text: String?): RaceResult? = when (text?.lowercase()) {
    "won" -> RaceResult.WON
    "lost" -> RaceResult.LOST
    "draw" -> RaceResult.DRAW
    else -> null
}

internal fun toMatch(o: JsonObject): MatchState {
    val opponent = o["opponent"] as? JsonObject
    return MatchState(
        id = requireNotNull(o.string("id", "match_id")) { "the engine's match carried no id" },
        status = statusOf(o.string("status")),
        seed = requireNotNull(o.long("seed")) { "the engine's match carried no seed" },
        // guess: the opponent's name rides on `opponent.name` or a flat `opponent_name`.
        opponentName = opponent?.string("name", "display_name") ?: o.string("opponent_name") ?: "Your opponent",
        startAtMs = instantMs(o.string("start_at")),
        endsAtMs = instantMs(o.string("ends_at")),
        serverNowMs = requireNotNull(instantMs(o.string("server_now"))) {
            "the engine's match carried no server_now"
        },
        // guess: the caller's progress is `me` and the other player's is `opponent`.
        me = o["me"].toProgress(),
        opponent = opponent.toProgress(),
        result = resultOf(o.string("result")),
    )
}

/**
 * The sentence for any failure from this backend: the engine's own words where it gave some,
 * stripped of DRF's JSON envelope, else the exception's message.
 */
fun Throwable.engineSentence(): String {
    val failure = (this as? EngineHttpException)?.failure
    return when (failure) {
        is EngineFailure.Refused -> engineRefusalSentence(failure.body)
        null -> message ?: "Something went wrong talking to the engine."
        else -> failure.sentence
    }
}
