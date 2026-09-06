package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The retry bookkeeping for an unattended background pass: how far it has got, how many times it
 * has failed since, when it may try again, and whether it has been set aside for good.
 *
 * **Why one table for two loops (2026-09-06).** [com.kevin.legion.ai.ReflectionEngine] and
 * [com.kevin.legion.ai.MemoryConsolidator] both run off
 * [com.kevin.legion.service.AriaForegroundService]'s five-minute timer - 288 passes a day - and
 * both could spend forever with nobody present:
 *
 * - Reflection derived its watermark from the newest `REFLECTION` [CompanionMemory] row. A model
 *   that returned an EMPTY insight list wrote nothing, so the watermark never advanced, the same
 *   consolidated memories were still there, the importance sum was still over threshold, and the
 *   identical set was re-synthesized every five minutes. The model did not have to FAIL; it only
 *   had to judge there was nothing worth saying. Found on the A25's own database: the newest
 *   reflection row is 2026-08-20 20:01:33Z, and the running importance sum for the vehicle that
 *   was active crossed the threshold at 2026-08-30 02:04:26Z, so the precondition sat true for a
 *   week with nothing ever written.
 * - Consolidation left a session's turns pending on a null distill and retried next pass with no
 *   attempt counter, no backoff and no quarantine.
 *
 * They need the same four facts, so they share one table rather than two near-identical ones.
 *
 * **[watermark] is the fix for "a successful call that produced nothing".** An empty answer from
 * a model that ANSWERED is a real result and must advance the mark; only a call that FAILED is
 * retryable. Storing the mark here rather than inferring it from written rows is the whole point:
 * inferring it from output means an empty output is indistinguishable from no run at all.
 *
 * **[setAsideReason] is recorded, never merely implied.** A poisoned input that has burned its
 * attempts stops being tried and says why, the same skip-and-record shape
 * [com.kevin.legion.backend.ChecklistsBackfill] uses for a row the engine refuses. A pass that
 * silently stopped and a pass that never ran look identical from the outside, which is exactly
 * how the reflection loop went unnoticed for a week.
 */
@Entity(tableName = "background_pass_state")
data class BackgroundPassState(
    /** Namespaced so the two loops cannot collide - see [reflectionKey] and [consolidationKey],
     *  which are the only two things that should ever build one. */
    @PrimaryKey val passKey: String,
    /** How far the pass has successfully got. Reflection stores the newest `createdAt` among the
     *  consolidated memories a successful call considered. Consolidation does not use it (its
     *  unit of work is a session id, which is the key itself) and leaves it 0. */
    val watermark: Long = 0,
    /** CONSECUTIVE failed attempts. Reset to 0 by any success, and by fresh input arriving. */
    val attempts: Int = 0,
    /** Wall-clock time before which the pass must not try again. 0 means "no wait". */
    val nextAttemptAt: Long = 0,
    /** When the pass was set aside for good. 0 means it has not been. */
    val setAsideAt: Long = 0,
    /** Why it was set aside, in words. Empty exactly when [setAsideAt] is 0. */
    val setAsideReason: String = "",
    val updatedAt: Long = 0,
) {
    /** True once the pass has burned its attempts and must not be tried again without new input. */
    val isSetAside: Boolean get() = setAsideAt > 0L

    companion object {
        /**
         * How many consecutive FAILURES a unit of work gets before it is set aside.
         *
         * Five, not one: a failure here is usually the network or a 429, which is transient and
         * genuinely worth another go. And not unbounded, which is what it was - an invalid key or
         * a transcript the model will never parse fails identically forever, and at 288 passes a
         * day "forever" is what burned the credits.
         */
        const val MAX_ATTEMPTS = 5

        /** First backoff step. Doubles per attempt up to [MAX_BACKOFF_MS]. */
        const val BASE_BACKOFF_MS = 15L * 60L * 1000L

        /**
         * Backoff ceiling. Six hours rather than something larger because the work is still worth
         * doing eventually, and rather than something smaller because the caller is a five-minute
         * timer and anything under it changes nothing.
         */
        const val MAX_BACKOFF_MS = 6L * 60L * 60L * 1000L

        /** Reflection's unit of work is one vehicle. */
        fun reflectionKey(vehicleId: String) = "reflection:$vehicleId"

        /** Consolidation's unit of work is one episodic session. */
        fun consolidationKey(sessionId: String) = "consolidate:$sessionId"

        /**
         * The delay owed after [attempts] consecutive failures: exponential from
         * [BASE_BACKOFF_MS], capped at [MAX_BACKOFF_MS].
         *
         * Computed by doubling in a loop rather than with `shl`, because an attempt count large
         * enough to shift a Long past 63 bits produces a NEGATIVE delay - i.e. no backoff at all,
         * which is the bug this function exists to prevent, arriving by overflow.
         */
        fun backoffMs(attempts: Int): Long {
            var delay = 0L
            if (attempts > 0) {
                delay = BASE_BACKOFF_MS
                // Stops doubling at the ceiling rather than after `attempts` rounds, so the loop
                // cannot overflow however large the counter gets.
                repeat(attempts - 1) {
                    if (delay < MAX_BACKOFF_MS) delay *= 2
                }
            }
            return delay.coerceAtMost(MAX_BACKOFF_MS)
        }
    }
}

/** Reads and writes for [BackgroundPassState]. */
@Dao
interface BackgroundPassStateDao {
    @Query("SELECT * FROM background_pass_state WHERE passKey = :passKey LIMIT 1")
    suspend fun byKey(passKey: String): BackgroundPassState?

    @Query(
        "INSERT OR IGNORE INTO background_pass_state " +
            "(passKey, watermark, attempts, nextAttemptAt, setAsideAt, setAsideReason, updatedAt) " +
            "VALUES (:passKey, 0, 0, 0, 0, '', :at)"
    )
    suspend fun ensure(passKey: String, at: Long)

    /**
     * A pass that ran and got a real answer - including an EMPTY one. Advances the mark, clears
     * the failure count and any set-aside, because the thing evidently works.
     *
     * [watermark] never moves backwards: two passes can overlap (the five-minute timer does not
     * wait for the previous one), and the later-finishing call is not necessarily the one that
     * considered the newer material.
     */
    @Query(
        "UPDATE background_pass_state SET " +
            "watermark = MAX(watermark, :watermark), attempts = 0, nextAttemptAt = 0, " +
            "setAsideAt = 0, setAsideReason = '', updatedAt = :at " +
            "WHERE passKey = :passKey"
    )
    suspend fun recordSuccess(passKey: String, watermark: Long, at: Long)

    /** A pass that FAILED - the call did not come back with an answer. Counts the attempt and
     *  parks the next one behind [nextAttemptAt]. */
    @Query(
        "UPDATE background_pass_state SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt, " +
            "updatedAt = :at WHERE passKey = :passKey"
    )
    suspend fun recordFailure(passKey: String, nextAttemptAt: Long, at: Long)

    /** Stop trying, and say why. */
    @Query(
        "UPDATE background_pass_state SET setAsideAt = :at, setAsideReason = :reason, " +
            "updatedAt = :at WHERE passKey = :passKey"
    )
    suspend fun setAside(passKey: String, reason: String, at: Long)

    /** Fresh input arrived for a unit of work that had been set aside or was backing off - it
     *  earns a clean slate, because the thing that was failing may not be the thing being tried
     *  now. The watermark is deliberately NOT touched. */
    @Query(
        "UPDATE background_pass_state SET attempts = 0, nextAttemptAt = 0, setAsideAt = 0, " +
            "setAsideReason = '', updatedAt = :at WHERE passKey = :passKey"
    )
    suspend fun clearRetryState(passKey: String, at: Long)

    /** Everything currently set aside, for the Setup screen to say so in words. */
    @Query("SELECT * FROM background_pass_state WHERE setAsideAt > 0 ORDER BY setAsideAt DESC")
    suspend fun setAsideRows(): List<BackgroundPassState>

    /** Drops the row for a unit of work that no longer exists - a consolidated session whose turns
     *  have been deleted. Without this the table would accumulate one dead row per conversation. */
    @Query("DELETE FROM background_pass_state WHERE passKey = :passKey")
    suspend fun forget(passKey: String)
}
