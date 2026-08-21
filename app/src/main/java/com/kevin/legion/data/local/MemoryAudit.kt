package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

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
    suspend fun insert(row: MemoryAudit)

    /** Newest first - the read behind any audit view, and behind pulling the trail off a device. */
    @Query("SELECT * FROM memory_audit ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryAudit>

    /** Just one kind of event, newest first - e.g. every line the assistant has spoken. */
    @Query("SELECT * FROM memory_audit WHERE event = :event ORDER BY at DESC LIMIT :limit")
    suspend fun recentOf(event: String, limit: Int): List<MemoryAudit>

    @Query("SELECT COUNT(*) FROM memory_audit")
    suspend fun count(): Int

    /**
     * Drops everything older than the newest [keep] rows. Called after writes rather than on a
     * schedule: an audit trail that grows without limit on a phone eventually becomes the reason
     * someone turns auditing off.
     */
    @Query("DELETE FROM memory_audit WHERE id NOT IN (SELECT id FROM memory_audit ORDER BY at DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    /** Part of the "forget everything" reset - the trail of deleted memories goes with them. */
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
