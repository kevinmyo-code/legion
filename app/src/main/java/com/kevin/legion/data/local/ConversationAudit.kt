package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One row in the conversation-and-tool-call audit trail (ticket 23, hands-and-senses map, Kevin
 * 2026-08-21: *"i want a way to record all the voice conversations i have with the ai as a log +
 * all the tool calls etc so we have an audit trail and debug"*).
 *
 * **Why ticket 20 could not be closed without this.** `memory_audit` ([MemoryAudit], v27) recorded
 * the assistant's spoken lines and proved its worth the same night - but ticket 20's own words are
 * "did the model skip the tool, or get the right answer and say a different one?", and nothing
 * anywhere recorded a tool's ARGUMENTS or its RESULT. `MidnightEvents` logs that `ask_fleet` was
 * dispatched and nothing about what it was asked or what it returned - "the tool returned the
 * right number and the model ignored it" was indistinguishable from "the tool returned nothing".
 * [Kind.TOOL_RESULT] rows are the field that closes that gap.
 *
 * **Why this is a NEW table and not an extension of [MemoryAudit].** Both are flat, trimmed,
 * append-only event logs, which made "just add rows to memory_audit" the first thing worth ruling
 * out. It was ruled out for three reasons, not one:
 * 1. [MemoryAudit] is scoped to the MEMORY system specifically - writes, deletes, recalls, and the
 *    line the assistant spoke - and its readers ([com.kevin.legion.ui.companions.MemoryScreen],
 *    [com.kevin.legion.ai.ReflectionEngine], [com.kevin.legion.ai.MemoryConsolidator]) all assume
 *    that scope. This table's job is broader and orthogonal to memory entirely: every tool call's
 *    NAME, ARGUMENTS and RESULT, a thing memory_audit has never recorded and was never asked to.
 * 2. **Retention differs on purpose.** [MemoryAudit] trims to a fixed ROW COUNT ([AUDIT_KEEP])
 *    because a memory event is cheap and unbounded count is the risk there. Ticket 23's own
 *    decision 4 asks for a rolling TIME window ([CONVERSATION_AUDIT_RETENTION_DAYS]) because
 *    debugging needs "what happened in the last two weeks", not "the last N events regardless of
 *    when" - a busy day on a row-count trim could evict a quiet week's worth of history, including
 *    the one bad turn someone is trying to find.
 * 3. Cramming {tool name, args, result} triples into [MemoryAudit.detail] as an ad hoc JSON blob
 *    would silently repurpose a column three other files already treat as "one line of memory
 *    text", which is exactly the kind of drift CLAUDE.md's read-order rule exists to prevent.
 *
 * **Read-through redaction is per-ROW, not uniformly per-turn, and that split is deliberate.**
 * [com.kevin.legion.service.LiveToolbox.EPISODIC_EXCLUDED_TOOLS] tools return content this app has
 * already decided must never leave the device (mail bodies, sitrep news) - see
 * [com.kevin.legion.service.GeminiLiveSession.captureEpisodicTurn]'s doc comment for why an
 * earlier version of this rule drops a WHOLE conversational turn rather than trying to scrub just
 * the mail-shaped half of free text. A TOOL_RESULT row is not free text - the row already carries
 * the tool's own name, so a call to `list_vehicles` in the same turn as `ask_mail` can be told
 * apart precisely and does not need to be sacrificed to protect the call it sits next to. Only a
 * [Kind.TOOL_RESULT] row whose own [toolName] is itself excluded gets its [content] replaced. The
 * [Kind.COMPANION] row for that turn is different: free text cannot be reliably attributed back to
 * one tool call among several, so it follows the existing whole-turn precedent and is redacted
 * whenever ANY tool this turn was excluded, mirroring
 * [com.kevin.legion.service.GeminiLiveSession.readThroughToolTouchedThisTurn]'s existing scope
 * exactly - this table reuses that flag rather than inventing a second notion of read-through
 * (ticket 23 decision 2, explicit). [Kind.USER] rows are never redacted: the user's own words
 * are not fetched content, whatever he says about the tool he just asked for.
 *
 * Rows from one exchange share [turnSeq] so an export or a query can regroup them without a
 * foreign key: one USER row, at most one COMPANION row, and zero or more TOOL_RESULT rows, all
 * minted while [com.kevin.legion.service.GeminiLiveSession] processes a single `turnComplete`. See
 * [com.kevin.legion.service.GeminiLiveSession]'s `turnSeq` field for how the correlation is kept
 * consistent across the two different classes ([com.kevin.legion.service.LiveSessionController]
 * writes the tool rows, [com.kevin.legion.service.GeminiLiveSession] writes the turn's own rows)
 * that each mint some of these rows.
 *
 * **This is a record of what happened, never an input to behaviour**, same posture as
 * [MemoryAudit]: nothing reads it back into a prompt.
 */
@Entity(
    tableName = "conversation_audit",
    indices = [Index(value = ["clientUuid"], unique = true)],
)
data class ConversationAudit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Groups every row from the same exchange - see the class doc. Not a `@ForeignKey`: nothing
     *  else needs to join against this table, and a plain `Long` is enough to regroup on read. */
    val turnSeq: Long,
    /** USER, COMPANION, or TOOL_RESULT - see [Kind]. */
    val kind: String,
    /** The tool's name for a TOOL_RESULT row, blank for USER/COMPANION. Kept even when
     *  [content] is redacted - the whole point of ticket 20 is knowing WHICH tool ran, and that
     *  survives redaction by construction since only the RESULT is ever replaced. */
    @ColumnInfo(defaultValue = "''") val toolName: String = "",
    /**
     * The tool's arguments, JSON-encoded, for a TOOL_RESULT row. Never redacted - arguments are
     * what the model chose to ASK for, not fetched content, and are exactly what ticket 20 needs
     * to tell "asked the right thing" apart from "asked, then said something else anyway".
     */
    @ColumnInfo(defaultValue = "''") val args: String = "",
    /**
     * The user's words, the companion's words, or the tool's JSON result - or, when [redacted]
     * is true, the literal string [READ_THROUGH_REDACTED]. Untruncated, unlike [MemoryAudit.detail]:
     * this trail exists specifically to be read verbatim during a debugging session, and truncating
     * a tool result would reopen exactly the "returned the right number, ignored it" ambiguity this
     * table exists to close.
     */
    val content: String,
    /** True when [content] was replaced with [READ_THROUGH_REDACTED] rather than stored - its own
     *  column, not something a reader infers by string-matching the placeholder later. */
    @ColumnInfo(defaultValue = "0") val redacted: Boolean = false,
    /** Active vehicle at the time - context, never a filter, same convention as [MemoryAudit.vehicleId]. */
    @ColumnInfo(defaultValue = "''") val vehicleId: String = "",
    val at: Long,
    /**
     * This row's identity ON THE SERVER, minted here at insert and never re-derived - the same
     * client-minted-UUID shape every other synced table in this codebase already uses
     * ([MemoryEntry.syncId], [Goal.syncId], [Category.guid], `origin_guid` server-side).
     *
     * **Added v67 because `(device_id, local_id)` was not an identity.** The upload's server-side
     * natural key used to be this device's `ANDROID_ID` paired with [id], and [id] is an
     * `AUTOINCREMENT` rowid: it restarts at 1 whenever this table is emptied, while `ANDROID_ID`
     * does not. On 2026-09-03 exactly that happened, and the 142 rows recorded afterwards carried
     * ids 1..142 that the server had already issued to 142 COMPLETELY DIFFERENT rows from August.
     * The upload posts `on conflict do nothing`, so Postgres matched them on the old key, discarded
     * every one, and returned success - three days of the only durable record of what a tool call
     * did, silently dropped, with a 14-day delete timer already running on them
     * ([CONVERSATION_AUDIT_RETENTION_DAYS]). A UUID cannot collide across a table reset, which
     * removes the class of bug rather than that one instance of it.
     */
    @ColumnInfo(defaultValue = "''") val clientUuid: String = java.util.UUID.randomUUID().toString(),
) {
    object Kind {
        const val USER = "user"
        const val COMPANION = "companion"
        const val TOOL_RESULT = "tool_result"
    }
}

/**
 * What a [ConversationAudit.Kind.USER] row holds when the person demonstrably spoke and Gemini
 * returned no transcript for it (2026-09-07).
 *
 * **Why a row exists at all in that case.** [ConversationAudit] rows were written only when
 * `inputAudioTranscription` came back non-blank, and it sometimes comes back empty on a turn the
 * model clearly heard and acted on - the evidence is a 09-05 turn where the assistant answered
 * "I've created the grocery list and added milk for you" with no USER row anywhere beside it. Two
 * things broke at once: the audit trail silently lost turns, and `live_connect_day`'s
 * "connects that carried a turn" undercounted, so the spend meter's own headline understated real
 * use. **No row is a lie by omission** - it reads, later, as a turn that never happened.
 *
 * **This is a marker, never invented speech.** It is not a guess at what was said and must never be
 * treated as one; it records only the two facts the app actually observed - the microphone
 * forwarded audio, and no transcript came back. Same shape and same reasoning as
 * [READ_THROUGH_REDACTED] just below: a fixed, recognisable string in the content column, saying in
 * words what is missing rather than leaving a gap for a reader to misread.
 *
 * Deliberately NOT a new [ConversationAudit.Kind]. `conversation_audit.kind` carries a server-side
 * `check (kind in ('user', 'companion', 'tool_result'))`
 * (`supabase/migrations/20260829000300_conversation_audit_kind_lowercase.sql`), so a fourth value
 * would be rejected on upload until that migration was applied - and a rejected row in a batch is
 * how this table already lost three days once. A `user` row whose content says it was not
 * transcribed needs no migration on either side.
 */
const val UNTRANSCRIBED_USER_TURN = "[user spoke; no transcription returned]"

/** What a redacted [ConversationAudit.content] holds - ticket 23 decision 2, verbatim. Also reused
 *  by [MemoryAuditDao.record]'s caller in [com.kevin.legion.service.GeminiLiveSession.auditSpokenTurn],
 *  which had the identical leak (storing a mail-touched spoken line in full) and is fixed
 *  alongside this table rather than left inconsistent with it. */
const val READ_THROUGH_REDACTED = "[read-through content omitted]"

/**
 * What actually gets STORED for a piece of turn content: the words, or the redaction marker.
 *
 * A one-line decision, pulled out as a function purely so it can be unit-tested. It is the rule the
 * whole audit trail hangs on - ticket 23 calls read-through redaction "load-bearing", because a full
 * transcript would otherwise store mail bodies and the sitrep's news summary, which
 * `LiveToolbox.EPISODIC_EXCLUDED_TOOLS` and `ProactiveRaise.carriesReadThroughContent` exist to keep
 * out of storage. An inline `if` at the call site would be correct today and untestable forever.
 *
 * **Blank content is left blank rather than marked redacted.** A redaction marker where nothing was
 * ever said would read, later, as "something was hidden here" - which is its own small lie in a
 * record whose entire purpose is being trustworthy after the fact.
 */
fun auditContent(content: String, readThrough: Boolean): String =
    if (readThrough && content.isNotBlank()) READ_THROUGH_REDACTED else content

@Dao
interface ConversationAuditDao {
    @Insert
    suspend fun insert(row: ConversationAudit)

    /** Newest first - for any future on-device viewer. */
    @Query("SELECT * FROM conversation_audit ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ConversationAudit>

    /** Every row at or after [sinceMillis], OLDEST first - the export path wants a readable
     *  chronological file, the opposite ordering from [recent]'s newest-first UI shape. */
    @Query("SELECT * FROM conversation_audit WHERE at >= :sinceMillis ORDER BY at ASC")
    suspend fun since(sinceMillis: Long): List<ConversationAudit>

    /**
     * Drops rows older than [cutoffMillis] **that the server already has** - the rolling retention
     * window (ticket 23 decision 4: [CONVERSATION_AUDIT_RETENTION_DAYS]). Called after every
     * insert, same "trim on write" convention as [MemoryAuditDao.trim], deliberately NOT filtered
     * by whether a row looks "interesting": the ticket's own motivating incident (the 142k claim)
     * looked like an ordinary successful turn, so a relevance filter would have deleted the one row
     * that mattered before anyone knew to look for it.
     *
     * **`id <= :uploadedThroughId` added 2026-09-06, and it is the load-bearing half.** This
     * previously read `WHERE at < :cutoffMillis` alone, which meant retention deleted on a timer
     * whether or not a row had ever reached the server. That is a fine rule for a cache and a
     * terrible one for the only durable record of what a tool call did: the upload had been
     * silently discarding every row for three days (see [ConversationAudit.clientUuid] for the
     * key collision that caused it), and nothing about the trim would have hesitated before
     * destroying the sole surviving copy of that evidence on day fourteen. **A silent upload
     * failure plus a blind timer is a shredder.** So the trim now stops at the upload watermark:
     * an un-uploaded row is KEPT past its window, growing the table rather than losing evidence,
     * and the growth is surfaced to a person by
     * [com.kevin.legion.backend.ConversationAuditReconcile.pendingSummary] rather than left to be
     * discovered. Keeping a row costs disk; deleting it costs the answer to "what did it actually
     * do?", which is the entire reason this table exists.
     *
     * @param uploadedThroughId the highest local [ConversationAudit.id] the server has confirmed -
     *   [com.kevin.legion.backend.ConversationAuditUploadCursor]'s watermark. Pass 0 to keep
     *   everything, which is what an install that has never synced correctly gets.
     */
    @Query("DELETE FROM conversation_audit WHERE at < :cutoffMillis AND id <= :uploadedThroughId")
    suspend fun trimUploadedOlderThan(cutoffMillis: Long, uploadedThroughId: Long)

    @Query("SELECT COUNT(*) FROM conversation_audit")
    suspend fun count(): Int

    /** How many rows sit past the upload watermark - the number
     *  [com.kevin.legion.backend.ConversationAuditReconcile.pendingSummary] puts in front of a
     *  person. Counts rows, never bytes: "how much evidence is not backed up" is a count question. */
    @Query("SELECT COUNT(*) FROM conversation_audit WHERE id > :afterId")
    suspend fun countAfterId(afterId: Long): Int

    /** [ConversationAudit.at] of the OLDEST row past the watermark, or null when nothing is
     *  pending. The age of this one is what says whether the backlog is minutes old or a fortnight
     *  old, and a fortnight is the number that matters ([CONVERSATION_AUDIT_RETENTION_DAYS]). */
    @Query("SELECT MIN(at) FROM conversation_audit WHERE id > :afterId")
    suspend fun oldestAtAfterId(afterId: Long): Long?

    /**
     * The largest local id in the table, or null when it is empty - the one read that can tell a
     * watermark left over from a PREVIOUS incarnation of this table apart from a legitimate one.
     * See [com.kevin.legion.backend.ConversationAuditReconcile.resolveCursor] for why a stale
     * watermark is otherwise undetectable and skips rows forever.
     */
    @Query("SELECT MAX(id) FROM conversation_audit")
    suspend fun maxId(): Long?

    /**
     * Rows with a local [ConversationAudit.id] greater than [afterId], oldest-first, capped at
     * [limit] - the next batch [com.kevin.legion.backend.ConversationAuditReconcile]'s resumable
     * upload reads. Same "id, not timestamp" reasoning as
     * [com.kevin.legion.data.local.OdbSampleDao.getAfterId]: `id` is this device's own monotonic
     * sequence, and the server's real identity is `(device_id, local_id)` - `local_id` IS this
     * column, carried verbatim by the reconcile, never re-derived.
     */
    @Query("SELECT * FROM conversation_audit WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getAfterId(afterId: Long, limit: Int): List<ConversationAudit>
}

/** The rolling retention window (ticket 23 decision 4). 14 days per the ticket's own suggested
 *  default: enough to catch a recurrence within a normal fortnight of use, bounded on a phone. */
const val CONVERSATION_AUDIT_RETENTION_DAYS = 14L

/**
 * Appends one row and trims the window - the single writer, same shape as [MemoryAuditDao.record]
 * for the same reason: three call sites (user text, companion text, one per tool call) writing
 * this table directly would risk the trim cutoff or the timestamp source drifting between them.
 *
 * **Never throws into its caller.** Same posture as [MemoryAuditDao.record]: an audit trail
 * failing must never take the real conversation or a real tool dispatch down with it.
 *
 * @param uploadedThroughId the upload watermark, handed in rather than read here because this
 *   function lives in `data.local` and the watermark lives in `backend` - see
 *   [trimUploadedOlderThan] for why the trim needs it at all. Both production call sites read it
 *   from [com.kevin.legion.backend.ConversationAuditUploadCursor]. It defaults to 0, and 0 means
 *   "trim nothing", so a caller that forgets it over-retains rather than over-deletes: the failure
 *   direction for an evidence table has to be a bigger table, never a missing row.
 */
suspend fun ConversationAuditDao.record(
    turnSeq: Long,
    kind: String,
    content: String,
    toolName: String = "",
    args: String = "",
    redacted: Boolean = false,
    vehicleId: String = "",
    uploadedThroughId: Long = 0L,
) {
    runCatching {
        val now = System.currentTimeMillis()
        insert(
            ConversationAudit(
                turnSeq = turnSeq,
                kind = kind,
                toolName = toolName,
                args = args,
                content = content,
                redacted = redacted,
                vehicleId = vehicleId,
                at = now,
            ),
        )
        trimUploadedOlderThan(now - CONVERSATION_AUDIT_RETENTION_DAYS * 24 * 60 * 60 * 1000, uploadedThroughId)
    }
}
