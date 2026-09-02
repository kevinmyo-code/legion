package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * One line in the memory audit trail (2026-08-20, Kevin: *"leave an audit trail for us to check"*).
 *
 * **Why a table rather than a log line.** On 2026-08-20 the assistant said the Jeep was at 142k
 * when the record said 227,612, and it could not be diagnosed at all: logcat had rolled,
 * `episodic_turns` is emptied by [com.kevin.legion.ai.MemoryConsolidator] once it has distilled a
 * drive, and nothing anywhere recorded what the assistant had said or which memories it was
 * holding when it said it. An audit that survives only until the log buffer wraps is an audit that
 * is never there on the day something goes wrong.
 *
 * **This is a record of what happened, never an input to behaviour.** Nothing reads it back into a
 * prompt. That boundary is deliberate: CLAUDE.md sec 7 requires memory to stay anchored to
 * external falsifiable facts, and a trail the companion could read and reason about would be a
 * second, ungated memory store wearing a different name.
 *
 * Rows are cheap and bounded by [MemoryAuditDao.trim] rather than kept forever.
 */
@Entity(tableName = "memory_audit")
data class MemoryAudit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** What happened - see [Event]. */
    val event: String,
    /** Which store, so the two are never conflated when reading the trail back. */
    val store: String,
    /**
     * The memory text, or the recall query. Truncated by the writer, not here: this is the column
     * that makes the trail readable by a human, so it holds words rather than ids.
     */
    val detail: String,
    /** Row id in the originating store where there is one, for joining back. 0 when not applicable. */
    @ColumnInfo(defaultValue = "0") val refId: Long = 0,
    /** Active vehicle at the time - context, never a filter. */
    @ColumnInfo(defaultValue = "''") val vehicleId: String = "",
    val at: Long,
    // memory-supabase ticket (v60 -> v61, MIGRATION_60_61): a fresh column, unlike
    // [MemoryEntry.syncId]/[com.kevin.legion.data.local.CompanionMemory.syncId] - this table never
    // had a portable identity column to reuse, so [guid] is minted here for the first time,
    // backfilled for every pre-existing row the same way [MIGRATION_59_60] backfills `guid` on the
    // body tables.
    @ColumnInfo(defaultValue = "''") val guid: String = java.util.UUID.randomUUID().toString(),
    val serverId: String? = null,
    /** LWW clock - backfilled from [at] for pre-existing rows. This table has no in-place edit
     * path (a written line is never revised), so in practice this only ever equals [at] at
     * insert time and is never touched again - kept as its own column rather than reusing [at]
     * anyway, for the same "sync clock is its own concern" reasoning [MemoryEntry.updatedAtMs]'s
     * own doc comment gives. */
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0,
    /** Soft-delete tombstone. **No local writer ever sets this true** - see
     * `backend/MemoryBackfill.kt`'s own class doc for why memory_audit gets backfill-only sync
     * (push, never a per-row delete outbox) and [MemoryAuditDao.trim]'s own doc comment for why
     * routine local pruning is deliberately NOT a tombstone. Present so a defensive remote
     * tombstone (one applied directly in Postgres) still has somewhere to land locally, and so
     * [com.kevin.legion.backend.MemorySync.pull]'s generic merge can treat this table exactly like
     * the other two rather than needing a delete-less special case. */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
) {
    object Event {
        /** A memory was written by consolidation, reflection, or the remember tool. */
        const val WRITTEN = "written"
        /** The driver deleted a memory from the memory screen. */
        const val DELETED = "deleted"
        /** A recall ran: [detail] is the query, and one RECALLED row follows per memory returned. */
        const val RECALL = "recall"
        /** One memory that a recall actually returned - this is what the model was handed. */
        const val RECALLED = "recalled"
        /** A line the assistant SPOKE. The thing whose absence made the 142k undiagnosable. */
        const val SPOKEN = "spoken"
    }

    object Store {
        const val FLAT = "memories"
        const val COMPANION = "companion_memories"
        /** Not a store - used by [Event.SPOKEN], which belongs to no memory table. */
        const val SPEECH = "speech"
    }
}

@Dao
interface MemoryAuditDao {
    @Insert
    suspend fun insert(row: MemoryAudit): Long

    /** Whole-row update - [com.kevin.legion.backend.MemorySync.pull]'s merge write. No local writer
     * ever produces a tombstone for this table (see [MemoryAudit.deleted]'s own doc comment), but
     * the generic merge still needs an update path for the "server row is newer" branch. */
    @Update
    suspend fun update(row: MemoryAudit)

    /** Every row, active AND soft-deleted - [com.kevin.legion.backend.MemorySync.pull]'s own local
     * match scan; see [MemoryDao.getAll]'s own doc comment for why this is never active-only. */
    @Query("SELECT * FROM memory_audit")
    suspend fun getAll(): List<MemoryAudit>

    /** Newest first - the read behind any audit view, and behind pulling the trail off a device.
     * `deleted = 0` added memory-supabase ticket, defensive - see [MemoryAudit.deleted]'s own doc
     * comment for why nothing local ever sets this true today. */
    @Query("SELECT * FROM memory_audit WHERE deleted = 0 ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryAudit>

    /** Just one kind of event, newest first - e.g. every line the assistant has spoken. */
    @Query("SELECT * FROM memory_audit WHERE deleted = 0 AND event = :event ORDER BY at DESC LIMIT :limit")
    suspend fun recentOf(event: String, limit: Int): List<MemoryAudit>

    @Query("SELECT COUNT(*) FROM memory_audit WHERE deleted = 0")
    suspend fun count(): Int

    /**
     * Drops everything older than the newest [keep] rows. Called after writes rather than on a
     * schedule: an audit trail that grows without limit on a phone eventually becomes the reason
     * someone turns auditing off.
     *
     * **A physical DELETE, not a tombstone - deliberately.** This bounds LOCAL storage only; the
     * server keeps the full history once a row has pushed (`backend/MemoryBackfill.kt`'s own class
     * doc). A row trimmed here is never re-fetched by a later [com.kevin.legion.backend.MemorySync.pull]
     * in steady state, because that pull's own watermark has already advanced past every row this
     * trim removes (the newest [keep] survive; trim only removes rows OLDER than ones already
     * merged) - the one case it CAN reappear is a fresh install's first pull (watermark defaults to
     * 0, fetch-everything), which is the intended behaviour: a reinstalled phone re-downloading the
     * server's full audit history is a feature of this table having a server home at all, not a
     * resurrection bug.
     */
    @Query("DELETE FROM memory_audit WHERE id NOT IN (SELECT id FROM memory_audit ORDER BY at DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    /** Part of the "forget everything" reset - the trail of deleted memories goes with them.
     * Local-only; unlike [MemoryDao.deleteAll]/[CompanionMemoryDao.deleteAll] this has no
     * write-through counterpart because nothing currently calls it (traced, not wired into
     * [com.kevin.legion.ai.CompanionReset.resetMemories] despite this doc's own claim) - left
     * exactly as found rather than newly wiring an unrelated pre-existing gap into this ticket. */
    @Query("DELETE FROM memory_audit")
    suspend fun deleteAll()
}

/** How much of a memory or spoken line the trail keeps - enough to recognise, not a transcript. */
const val AUDIT_DETAIL_LIMIT = 300

/** Rows retained. A few thousand is months of use and single-digit megabytes. */
const val AUDIT_KEEP = 2_000

/**
 * Appends one line to the trail. The single writer, so truncation and trimming cannot drift
 * between the three places that record memory activity.
 *
 * **Never throws into its caller.** An audit is a record ABOUT the work: losing a line is bad,
 * losing the driver's memory because the audit failed would be worse.
 */
suspend fun MemoryAuditDao.record(
    event: String,
    store: String,
    detail: String,
    refId: Long = 0,
    vehicleId: String = "",
) {
    runCatching {
        insert(
            MemoryAudit(
                event = event,
                store = store,
                detail = detail.trim().take(AUDIT_DETAIL_LIMIT),
                refId = refId,
                vehicleId = vehicleId,
                at = System.currentTimeMillis(),
            ),
        )
        trim(AUDIT_KEEP)
    }
}
