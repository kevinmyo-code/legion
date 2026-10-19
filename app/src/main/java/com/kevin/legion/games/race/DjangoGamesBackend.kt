package com.kevin.legion.games.race

import android.content.Context
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.engineRefusalSentence
import com.kevin.legion.backend.engine.translatingEngineCall
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MATCHES_PATH = "/api/games/matches/"
private const val OPPONENTS_PATH = "/api/games/opponents/"

/**
 * [GamesBackend] over the household engine's `games` app (ticket 05, ADR 0060).
 *
 * **The wire shapes are the engine's own, read from `server/games/views.py`** (`MatchSerializer`,
 * `OpponentSerializer`, `InviteResponseSerializer`) and pinned by `DjangoGamesBackendContractTest`.
 * An earlier version guessed field names from the ticket sketch and read the invite's id from the
 * top level, where the engine wraps it as `{"match": {...}, "push": ..., "detail": ...}`; the
 * invite succeeded on the server and the phone never followed it. There is deliberately no
 * "try several names" lookup any more: a renamed field must fail the contract test, not be
 * quietly defaulted.
 */
class DjangoGamesBackend(private val http: EngineHttp) : GamesBackend {

    override suspend fun opponents(): Result<List<Opponent>> =
        translatingEngineCall("load who you can race") {
            parseOpponents(http.get(OPPONENTS_PATH).getOrThrow().body)
        }

    override suspend fun pending(): Result<List<PendingInvite>> =
        translatingEngineCall("check for Tetris invites") {
            parsePending(http.get("${MATCHES_PATH}pending/").getOrThrow().body)
        }

    override suspend fun invite(opponentUserId: String): Result<InviteSent> =
        translatingEngineCall("send that invite") {
            val body = buildJsonObject { put("opponent_user_id", opponentUserId) }.toString()
            parseInvite(http.post(MATCHES_PATH, body).getOrThrow().body)
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
            parseMatch(http.get(path(matchId)).getOrThrow().body)
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
