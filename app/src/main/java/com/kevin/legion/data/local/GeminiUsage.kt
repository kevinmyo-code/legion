package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * What one Gemini call actually cost, in tokens Google itself reported.
 *
 * **Why this table exists at all (2026-09-06).** Kevin asked "my gemini credits burned really
 * fast - are we wasting a lot?" and nothing in this app could answer him. Every figure any
 * surface had ever shown was an ESTIMATE derived from prompt length, because
 * [com.kevin.legion.service.GeminiLiveSession]'s server-message handler parsed `setupComplete`,
 * `serverContent`, `toolCall`, `goAway` and `sessionResumptionUpdate` and dropped the
 * `usageMetadata` the Live API sends alongside them, and because
 * [com.kevin.legion.ai.SubAgent.parseUsageMetadata] existed but was reachable from exactly one
 * caller ([com.kevin.legion.ai.SubAgent.askWithUsage], used only by
 * [com.kevin.legion.ledger.CategoryAgent]) out of the thirty-odd REST call sites. This is the
 * first thing in LEGION that records a MEASURED token count rather than a guessed one.
 *
 * **Nulls are load-bearing and must never be rendered as zero.** Gemini omits `usageMetadata`
 * entirely on some response shapes, including inside an otherwise-200 body -
 * [com.kevin.legion.ai.SubAgent.parseUsageMetadata]'s own doc says so and returns nulls rather
 * than zeros for exactly this reason. "The API did not tell us" and "the call cost nothing" are
 * different sentences (CLAUDE.md section 1, the `ContentResolver` rule), and the Setup screen's
 * spend sentence is required to say which one it is looking at.
 *
 * **[sessionKey] is the join back to what was actually said.** For a Live socket it is
 * `GeminiLiveSession.episodicSessionId`, the same id [EpisodicTurn.sessionId] groups a
 * conversation's transcript by, so a spend row can be lined up against the turns that produced
 * it. For a REST sub-agent call it is a UUID minted for that one call, which is why a REST row is
 * never merged with anything.
 *
 * **[surface] decides how a row is aggregated, and the two rules genuinely differ.**
 * - [SURFACE_REST] - one row per `generateContent` round trip, and a day's REST spend is their
 *   SUM. Each call reports its own independent counts.
 * - [SURFACE_LIVE] - one row per socket, upserted in place as reports arrive, holding the
 *   GREATEST figures seen ([reports] counts how many arrived). **Whether the Live API's
 *   `usageMetadata` is cumulative-per-session or incremental-per-message is NOT verified on
 *   device**, and the two demand opposite arithmetic: summing cumulative reports would multiply
 *   the real figure by the number of messages, while maxing incremental ones undercounts. Taking
 *   the max is the choice that can only ever UNDERSTATE - exact if the reports are cumulative, a
 *   floor if they are incremental - and understating is the failure that does not tell Kevin he
 *   is fine when he is not. [reports] is stored so the question stays answerable from the data
 *   later: a session with one report proves nothing, a session with forty reports whose total
 *   never moved proves the reports are cumulative.
 */
@Entity(
    tableName = "gemini_usage",
    indices = [
        Index(value = ["sessionKey"], unique = true),
        Index(value = ["at"]),
    ],
)
data class GeminiUsage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Live: `episodicSessionId`. REST: a UUID for that single call. See the class doc. */
    val sessionKey: String,
    /** [SURFACE_LIVE] or [SURFACE_REST] - decides sum-vs-max aggregation, see the class doc. */
    val surface: String,
    /** The model string as sent, so a later price change can be applied to the right rows. */
    val model: String,
    /** `usageMetadata.promptTokenCount`. Null means the API did not report it - never zero. */
    val promptTokens: Int? = null,
    /** `usageMetadata.responseTokenCount`/`candidatesTokenCount`. Null means not reported. */
    val responseTokens: Int? = null,
    /**
     * `usageMetadata.totalTokenCount`. Null means not reported. It is NOT derived from the two
     * fields above: a derived total would make "prompt + response = total" true by construction -
     * CLAUDE.md section 4 rule 6's failure shape - and would hide a third component, which the
     * Live API genuinely has (audio tokens are reported in their own modality breakdown).
     */
    val totalTokens: Int? = null,
    /** How many `usageMetadata` messages folded into this row. 1 for REST. See the class doc for
     *  why this number is the evidence that makes the max-vs-sum choice re-checkable later. */
    val reports: Int = 1,
    /** When the row was first written (the connect, or the REST call). Day and month bucketing use
     *  this, so a Live session spanning midnight counts wholly on the day it OPENED. */
    val at: Long,
    /** When the row was last folded into. Equals [at] for REST. */
    val updatedAt: Long,
) {
    companion object {
        const val SURFACE_LIVE = "live"
        const val SURFACE_REST = "rest"
    }
}

/**
 * One day's Live socket connects, and how many of them a person actually spoke into.
 *
 * **The reconnect storm of August was found by reading logcat by hand**, because
 * [com.kevin.legion.MidnightEvents.sessionStart] and [com.kevin.legion.MidnightEvents.sessionEnd]
 * are `Log.d` and nothing else - a breadcrumb that survives exactly as long as the log buffer. A
 * socket that opens, bills its setup prompt and closes without anyone saying a word is the shape
 * that costs money for nothing, and [connectsWithoutTurn] is the number that makes it visible
 * without a cable attached.
 *
 * Per-day aggregate rather than per-connect rows on purpose: bounded at 365 rows a year, where
 * per-connect rows would grow fastest in precisely the storm this exists to catch.
 *
 * [day] is a local-calendar `yyyy-MM-dd`, not a UTC instant: the question is "what did I spend
 * today", and today is a thing that happens in the user's own timezone.
 */
@Entity(tableName = "live_connect_day")
data class LiveConnectDay(
    @PrimaryKey val day: String,
    /** Sockets that reached `onOpen` - a real connection, not an attempt. */
    val connects: Int = 0,
    /** Of [connects], how many carried at least one non-blank user turn. A connect is counted here
     *  at most once no matter how many turns it carried; the per-socket guard lives in
     *  [com.kevin.legion.service.GeminiLiveSession] because the socket object is what knows. */
    val connectsWithTurn: Int = 0,
    val updatedAt: Long = 0,
) {
    /** Connects nobody spoke into. Derived, never stored - a stored copy could disagree with its
     *  own two operands, and this is the number the whole table exists to show. */
    val connectsWithoutTurn: Int get() = (connects - connectsWithTurn).coerceAtLeast(0)
}

/** Reads and writes for [GeminiUsage]. Writes go through [com.kevin.legion.ai.GeminiUsageMeter],
 *  never straight from a call site. */
@Dao
interface GeminiUsageDao {
    @Query("SELECT * FROM gemini_usage WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun bySessionKey(sessionKey: String): GeminiUsage?

    @Query(
        "INSERT OR IGNORE INTO gemini_usage " +
            "(sessionKey, surface, model, promptTokens, responseTokens, totalTokens, reports, at, updatedAt) " +
            "VALUES (:sessionKey, :surface, :model, :promptTokens, :responseTokens, :totalTokens, :reports, :at, :at)"
    )
    suspend fun insertIfAbsent(
        sessionKey: String,
        surface: String,
        model: String,
        promptTokens: Int?,
        responseTokens: Int?,
        totalTokens: Int?,
        reports: Int,
        at: Long,
    )

    /**
     * Folds a fresh report into an existing Live row, keeping the GREATEST value seen for each
     * count and bumping [GeminiUsage.reports].
     *
     * `MAX(x, y)` in SQLite returns NULL if EITHER argument is NULL, which is the wrong answer
     * here - a report that omits `promptTokenCount` must not erase a figure an earlier report DID
     * give. Coalescing both sides first makes the null case mean "no information", not "zero",
     * while the outer CASE keeps a column NULL only while NOTHING has ever reported it.
     */
    @Query(
        "UPDATE gemini_usage SET " +
            "promptTokens = CASE WHEN promptTokens IS NULL AND :promptTokens IS NULL THEN NULL " +
            "ELSE MAX(COALESCE(promptTokens, 0), COALESCE(:promptTokens, 0)) END, " +
            "responseTokens = CASE WHEN responseTokens IS NULL AND :responseTokens IS NULL THEN NULL " +
            "ELSE MAX(COALESCE(responseTokens, 0), COALESCE(:responseTokens, 0)) END, " +
            "totalTokens = CASE WHEN totalTokens IS NULL AND :totalTokens IS NULL THEN NULL " +
            "ELSE MAX(COALESCE(totalTokens, 0), COALESCE(:totalTokens, 0)) END, " +
            "reports = reports + 1, updatedAt = :at " +
            "WHERE sessionKey = :sessionKey"
    )
    suspend fun foldReport(
        sessionKey: String,
        promptTokens: Int?,
        responseTokens: Int?,
        totalTokens: Int?,
        at: Long,
    )

    /** Total measured tokens at or after [since]. NULL when no row in the window reported a total
     *  at all - a different answer from 0, and the caller must keep it that way. */
    @Query("SELECT SUM(totalTokens) FROM gemini_usage WHERE at >= :since")
    suspend fun totalTokensSince(since: Long): Long?

    /** Rows at or after [since] whose total the API never reported, so a sum can say how much of
     *  itself is missing rather than quietly under-reporting. */
    @Query("SELECT COUNT(*) FROM gemini_usage WHERE at >= :since AND totalTokens IS NULL")
    suspend fun unreportedCountSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM gemini_usage WHERE at >= :since")
    suspend fun rowCountSince(since: Long): Int

    /** Retention: this is diagnostics about the app, not evidence about the user's data, so it is
     *  trimmed on a plain age rule with no upload watermark to respect (unlike `conversation_audit`,
     *  whose trim must not outrun what the server has confirmed). */
    @Query("DELETE FROM gemini_usage WHERE at < :before")
    suspend fun trimOlderThan(before: Long)
}

/** Reads and writes for [LiveConnectDay]. */
@Dao
interface LiveConnectDayDao {
    @Query("SELECT * FROM live_connect_day WHERE day = :day LIMIT 1")
    suspend fun byDay(day: String): LiveConnectDay?

    @Query(
        "INSERT OR IGNORE INTO live_connect_day (day, connects, connectsWithTurn, updatedAt) " +
            "VALUES (:day, 0, 0, :at)"
    )
    suspend fun ensureDay(day: String, at: Long)

    @Query("UPDATE live_connect_day SET connects = connects + 1, updatedAt = :at WHERE day = :day")
    suspend fun bumpConnect(day: String, at: Long)

    @Query(
        "UPDATE live_connect_day SET connectsWithTurn = connectsWithTurn + 1, updatedAt = :at " +
            "WHERE day = :day"
    )
    suspend fun bumpConnectWithTurn(day: String, at: Long)

    @Query("SELECT SUM(connects) FROM live_connect_day WHERE day >= :fromDay")
    suspend fun connectsSince(fromDay: String): Int?

    @Query("SELECT SUM(connectsWithTurn) FROM live_connect_day WHERE day >= :fromDay")
    suspend fun connectsWithTurnSince(fromDay: String): Int?
}
