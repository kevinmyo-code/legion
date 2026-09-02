package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A durable, at-least-once queue for a server write this device could not send when it was made -
 * CLAUDE.md's clone-and-run/offline posture (§7: "network calls degrade gracefully offline")
 * applied to a write, not just a read. v58 -> v59 ([MIGRATION_58_59]), the events-outbox ticket.
 *
 * **Generic on purpose - `kind = EVENT` is the first user, not the only one.** [targetTable] names
 * which local table's write is pending (see [OutboxTarget]), so fleet and places (both of which
 * have the identical "write locally, push to Supabase, might be offline" shape) can enqueue into
 * this SAME table later without a schema change - only a new [OutboxTarget] constant and a new
 * drain loop that knows how to decode that target's own [payload] shape. [payload] is deliberately
 * opaque JSON here (this table doesn't know or care what it means) rather than a column per
 * possible field - the same posture [RemoteEvent.structuredMeta] already takes for the identical
 * reason.
 *
 * **[operation]/[localId] are stored, not inferred**, so a drain never has to guess "what kind of
 * write was this" from the payload's shape alone.
 *
 * **[attempts]/[lastError] exist so a drain is never an unbounded retry loop** (the brief's own
 * "a poison row that retries every foreground forever is worse than one that stops and says so").
 * A row whose [attempts] has reached the drain's own cap is left in the table, tagged, and simply
 * excluded from the next drain's candidate query - never deleted (there is no other durable record
 * of what failed to reach the server) and never silently retried past the cap.
 */
@Entity(tableName = "sync_outbox")
data class OutboxEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Which local table this write targets - see [OutboxTarget]. */
    val targetTable: String,
    /** What kind of write this is - see [OutboxOperation]. */
    val operation: String,
    /** The LOCAL row id (this table's own surrogate key on [targetTable]) this write concerns -
     * carried so a drain can, if it ever needs to, look the local row back up rather than trusting
     * only [payload]'s own copy of the same facts. */
    val localId: Long,
    /** Opaque JSON, shaped per [targetTable]/[operation] - see this class's own doc comment. */
    val payload: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    /** The most recent failure's message, human-readable - null until the first failed attempt.
     * Never inspected programmatically to classify a failure (see [MAX_OUTBOX_ATTEMPTS]'s own doc
     * comment for why attempt-count, not error-type, is what bounds retrying here) - this exists
     * purely so a poisoned row's own row can say in words why it stopped, CLAUDE.md §7's "a new
     * tool's failure result says in words what did NOT happen" applied to a queued one instead of
     * a live one. */
    val lastError: String? = null,
)

/** [OutboxEntry.targetTable] values. A string, not an enum, matching every other `kind`-shaped
 * column in this codebase ([com.kevin.legion.backend.EventKind] etc.) - see that object's own class
 * doc for why a TEXT column with no CHECK constraint is what lets this vocabulary grow with no
 * migration later. */
object OutboxTarget {
    const val EVENTS = "events"

    /** Body-supabase ticket - one constant per body table, so [OutboxEntry.targetTable] tells a
     * drain exactly which of the eight [com.kevin.legion.backend.BodyBackend] upsert/softDelete
     * calls a queued payload decodes into, same role [EVENTS] plays for `events`. */
    const val BODY_BODYWEIGHT_LOGS = "bodyweight_logs"
    const val BODY_MEAL_LOGS = "meal_logs"
    const val BODY_MEAL_TARGETS = "meal_targets"
    const val BODY_SLEEP_LOGS = "sleep_logs"
    const val BODY_SLEEP_TARGETS = "sleep_targets"
    const val BODY_WORKOUT_PLANS = "workout_plans"
    const val BODY_WORKOUT_PLAN_ITEMS = "workout_plan_items"
    const val BODY_WORKOUT_SET_LOGS = "workout_set_logs"

    /** Memory-supabase ticket - one constant per write-through-covered memory table. No entry for
     * `memory_audit`: that table is pushed by `backend/MemoryBackfill.kt` alone (backfill-only
     * sync, no per-row outbox) - see that file's own class doc for why. */
    const val MEMORY_MEMORIES = "memories"
    const val MEMORY_COMPANION_MEMORIES = "companion_memories"

    /** Ledger-config-supabase ticket - one constant per synced ledger CONFIG table (categorisation
     * rules and budgets, never `ledger_transactions` itself - see
     * [com.kevin.legion.backend.LedgerConfigBackend]'s own class doc for why that table is out of
     * scope). */
    const val LEDGER_CATEGORIES = "categories"
    const val LEDGER_CATEGORY_RULES = "category_rules"
    const val LEDGER_BUDGET_TARGETS = "budget_targets"
}

/** [OutboxEntry.operation] values. **The comment this replaces said only [UPSERT] was produced as
 * of v59, and that `removeAppointment`/`updateAppointment` stayed local-only - a standing ruling
 * from one-today ticket 02 point 3.** Kevin reversed that ruling on 2026-09-02 (live-sync ticket
 * 04 follow-up): the two devices silently diverge on exactly the edit a user is most likely to
 * make once creation syncs but rename/delete do not, so both now route through this same outbox.
 * [UPDATE] and [SOFT_DELETE] are that reversal's two new producers - [SOFT_DELETE] was already
 * named here, unused, precisely so this moment would not need a schema change; [UPDATE] is new
 * for the identical reason a rename cannot go through [EventsBackend.uploadMigratedEvent] (that
 * function only ever inserts-if-absent, by design - see [EventsBackend.uploadMigratedEvent]'s own
 * doc comment - so it silently no-ops on an existing row instead of renaming it). See
 * `memory/library/decisions.md`'s 2026-09-02 entry for the full reversal record. */
object OutboxOperation {
    const val UPSERT = "upsert"
    /** A whole-row-replace update against an already-round-tripped event (`serverId` known) -
     * [com.kevin.legion.backend.EventsBackend.upsert] with a non-null id, never
     * [com.kevin.legion.backend.EventsBackend.uploadMigratedEvent] (see this object's own class
     * doc for why that function cannot express a rename). */
    const val UPDATE = "update"
    const val SOFT_DELETE = "soft_delete"
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntry): Long

    /** Every row for [targetTable] still under the drain's own attempt cap, oldest first - a row
     * at or past [maxAttempts] is a poisoned row (see [OutboxEntry]'s own doc comment) and is
     * deliberately excluded here rather than filtered out later, so "what will the next drain try"
     * and "what does this query return" never drift apart. */
    @Query(
        "SELECT * FROM sync_outbox WHERE targetTable = :targetTable AND attempts < :maxAttempts " +
            "ORDER BY createdAt ASC",
    )
    suspend fun pendingForTable(targetTable: String, maxAttempts: Int): List<OutboxEntry>

    /** Drains a successfully-sent entry - the ONLY delete this table's regular operation performs.
     * A poisoned row is never deleted (see [OutboxEntry]'s own doc comment); this is reserved for
     * "the server now has this write". */
    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun delete(id: Long)

    /** Records one more failed attempt - called after every unsuccessful drain try, whether or not
     * this attempt happens to be the one that reaches the cap (the cap itself is enforced by
     * [pendingForTable]'s own WHERE clause on the NEXT drain, not by this method refusing to
     * record). */
    @Query("UPDATE sync_outbox SET attempts = :attempts, lastError = :lastError WHERE id = :id")
    suspend fun recordAttempt(id: Long, attempts: Int, lastError: String?)

    /** Every row regardless of table or attempt count - test/diagnostic use only, same role as
     * [EventDao.getAll] plays for its own table. */
    @Query("SELECT * FROM sync_outbox")
    suspend fun getAll(): List<OutboxEntry>
}
