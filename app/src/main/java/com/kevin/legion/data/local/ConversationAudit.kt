package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
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
@Entity(tableName = "conversation_audit")
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
) {
    object Kind {
        const val USER = "user"
        const val COMPANION = "companion"
        const val TOOL_RESULT = "tool_result"
    }
}

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
     * Drops everything older than [cutoffMillis] - the rolling retention window (ticket 23
     * decision 4: [CONVERSATION_AUDIT_RETENTION_DAYS]). Called after every insert, same
     * "trim on write" convention as [MemoryAuditDao.trim], deliberately NOT filtered by whether a
     * row looks "interesting": the ticket's own motivating incident (the 142k claim) looked like
     * an ordinary successful turn, so a relevance filter would have deleted the one row that
     * mattered before anyone knew to look for it.
     */
    @Query("DELETE FROM conversation_audit WHERE at < :cutoffMillis")
    suspend fun trimOlderThan(cutoffMillis: Long)

    @Query("SELECT COUNT(*) FROM conversation_audit")
    suspend fun count(): Int
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
 */
suspend fun ConversationAuditDao.record(
    turnSeq: Long,
    kind: String,
    content: String,
    toolName: String = "",
    args: String = "",
    redacted: Boolean = false,
    vehicleId: String = "",
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
        trimOlderThan(now - CONVERSATION_AUDIT_RETENTION_DAYS * 24 * 60 * 60 * 1000)
    }
}
