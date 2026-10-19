package com.kevin.legion.games.race

/** Another member of my household I can invite to a race. */
data class Opponent(val userId: String, val name: String)

/** A live invite addressed to me, from `GET /api/games/matches/pending/`. */
data class PendingInvite(val matchId: String, val fromName: String)

/** A created invite: the match to follow and the engine's sentence about whether a notification went out. */
data class InviteSent(val matchId: String, val note: String)

/** The match's lifecycle on the engine (ticket 05's `status`). */
enum class MatchStatus { INVITED, PLAYING, ENDED, DECLINED, CANCELLED, EXPIRED }

/** The caller's outcome once a match has ended. */
enum class RaceResult { WON, LOST, DRAW }

/** One player's live numbers, as sent to and read back from the engine. */
data class PlayerProgress(
    val score: Int = 0,
    val lines: Int = 0,
    val level: Int = 1,
    val toppedOut: Boolean = false,
    val finished: Boolean = false,
)

/**
 * `GET /api/games/matches/<id>/`. Times are epoch milliseconds. [serverNowMs] is the engine's own
 * clock at the moment it answered, so a client can correct for skew: a phone clock that is 3 s off
 * would otherwise start its race 3 s away from the opponent's.
 */
data class MatchState(
    val id: String,
    val status: MatchStatus,
    val seed: Long,
    val opponentName: String,
    val startAtMs: Long?,
    val endsAtMs: Long?,
    val serverNowMs: Long,
    val me: PlayerProgress,
    val opponent: PlayerProgress,
    val result: RaceResult?,
)

/**
 * The seam to the engine's match API (ticket 05). Every call returns a [Result] whose failure
 * message is a sentence that can be shown as is, saying what did NOT happen. A seam rather than a
 * concrete class because the race is tested against a fake (CLAUDE.md section 8: interfaces at
 * seams that need a fake).
 */
interface GamesBackend {
    suspend fun opponents(): Result<List<Opponent>>
    suspend fun pending(): Result<List<PendingInvite>>

    /** Creates the invite; the result carries the new match's id and what happened to the notification. */
    suspend fun invite(opponentUserId: String): Result<InviteSent>
    suspend fun accept(matchId: String): Result<Unit>
    suspend fun decline(matchId: String): Result<Unit>
    suspend fun cancel(matchId: String): Result<Unit>
    suspend fun progress(matchId: String, progress: PlayerProgress): Result<Unit>
    suspend fun match(matchId: String): Result<MatchState>
}
