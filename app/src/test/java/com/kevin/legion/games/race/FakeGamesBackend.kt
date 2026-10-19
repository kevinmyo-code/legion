package com.kevin.legion.games.race

/** A scripted [GamesBackend]: tests set what each call returns and read what was sent. */
class FakeGamesBackend : GamesBackend {
    var opponentsResult: Result<List<Opponent>> = Result.success(emptyList())
    var pendingResult: Result<List<PendingInvite>> = Result.success(emptyList())
    var inviteResult: Result<String> = Result.success("m1")
    var matchResult: Result<MatchState> = Result.failure(IllegalStateException("no match scripted"))
    var actionResult: Result<Unit> = Result.success(Unit)

    val progressSent = mutableListOf<PlayerProgress>()
    val actions = mutableListOf<String>()

    override suspend fun opponents() = opponentsResult
    override suspend fun pending() = pendingResult
    override suspend fun invite(opponentUserId: String): Result<String> {
        actions += "invite:$opponentUserId"
        return inviteResult
    }

    override suspend fun accept(matchId: String): Result<Unit> = actionResult.also { actions += "accept:$matchId" }
    override suspend fun decline(matchId: String): Result<Unit> = actionResult.also { actions += "decline:$matchId" }
    override suspend fun cancel(matchId: String): Result<Unit> = actionResult.also { actions += "cancel:$matchId" }
    override suspend fun progress(matchId: String, progress: PlayerProgress): Result<Unit> {
        progressSent += progress
        return actionResult
    }

    override suspend fun match(matchId: String) = matchResult
}

fun matchState(
    status: MatchStatus,
    startAtMs: Long? = null,
    endsAtMs: Long? = null,
    serverNowMs: Long = 0,
    result: RaceResult? = null,
    opponentName: String = "Mia",
) = MatchState(
    id = "m1",
    status = status,
    seed = 42,
    opponentName = opponentName,
    startAtMs = startAtMs,
    endsAtMs = endsAtMs,
    serverNowMs = serverNowMs,
    me = PlayerProgress(),
    opponent = PlayerProgress(),
    result = result,
)
