package com.kevin.legion.games.race

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Which invites already raised a notification, so a 30 s re-poll does not raise the same one again. */
interface InviteSeenStore {
    fun load(): Set<String>
    fun save(ids: Set<String>)
}

/** What one poll did, in words a log line or a test can read. */
sealed interface PollOutcome {
    /** No usable engine on this device: nothing was asked. */
    data object NoEngine : PollOutcome

    /** The engine could not be asked or answered badly; nothing was raised. [sentence] says why. */
    data class Failed(val sentence: String) : PollOutcome

    data class Polled(val raised: Int, val retracted: Int) : PollOutcome
}

/**
 * Polls `GET /api/games/matches/pending/` and turns each NEW invite into a notification (ADR 0060:
 * the phone has no push channel, so it asks). The backend is resolved on every poll, so a sign-in
 * or sign-out between polls is picked up; null means this device has no usable engine.
 *
 * **Dedupe is by match id, and the seen set is rewritten to exactly the invites still pending.**
 * An invite that stops being pending (answered elsewhere, cancelled, expired) is retracted from
 * the shade, so a stale "invited you to Tetris" never outlives the invite. A failed poll changes
 * nothing: no notification, and the seen set is kept, so an invite is neither lost nor doubled.
 *
 * Where this runs is [GameInvitePolling]'s concern, not this class's.
 */
class InvitePoller(
    private val backend: () -> GamesBackend?,
    private val seen: InviteSeenStore,
    private val raise: (PendingInvite) -> Unit,
    private val retract: (String) -> Unit,
    /** Told of an exception that escaped a poll; the loop carries on regardless. */
    private val onCrash: (Throwable) -> Unit = {},
) {
    // The foreground loop and the service loop can both be alive, and two polls interleaving their
    // load and save would raise one invite twice. One lock per poller; callers share one poller.
    private val lock = Mutex()

    suspend fun pollOnce(): PollOutcome = lock.withLock { poll() }

    private suspend fun poll(): PollOutcome {
        val games = backend() ?: return PollOutcome.NoEngine
        return games.pending().fold(
            onSuccess = { settle(it) },
            onFailure = { PollOutcome.Failed(it.engineSentence()) },
        )
    }

    private fun settle(pending: List<PendingInvite>): PollOutcome {
        val already = seen.load()
        val fresh = pending.filter { it.matchId !in already }
        val gone = already - pending.map { it.matchId }.toSet()
        fresh.forEach(raise)
        gone.forEach(retract)
        seen.save(pending.map { it.matchId }.toSet())
        return PollOutcome.Polled(raised = fresh.size, retracted = gone.size)
    }

    /**
     * Polls forever at [intervalMs] until cancelled. A failing poll must not end the loop - it runs
     * on a long-lived scope with no exception handler - but cancellation must, so that one
     * exception type is rethrown and every other is dropped until the next tick.
     */
    @Suppress("TooGenericExceptionCaught") // a poll must never end the loop; cancellation is rethrown first
    suspend fun run(intervalMs: Long = POLL_INTERVAL_MS) {
        while (coroutineContext.isActive) {
            try {
                pollOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // A failed poll's sentence is already in PollOutcome.Failed; this is only the crash guard.
                onCrash(e)
            }
            delay(intervalMs)
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L
    }
}
