package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for [EngineRecord]. **Not the write door** -
 * [com.kevin.legion.engine.RecordStore] is, and this DAO's insert/update/trash methods are
 * `internal` in spirit even though Kotlin's module visibility cannot enforce that across a single
 * `app` module (this codebase has one module - see CLAUDE.md §3). The convention this repo already
 * leans on for a similar case is [LedgerTransaction]'s comment discipline, not a language feature;
 * matched here the same way - every doc comment on the write methods below says, in words, "call
 * [com.kevin.legion.engine.RecordStore], not this directly".
 */
@Dao
interface EngineRecordDao {
    /** Raw insert. Callers: [com.kevin.legion.engine.RecordStore] only. */
    @Insert
    suspend fun insert(record: EngineRecord): Long

    /** Raw update, including trash/restore (both just move [EngineRecord.deletedAt]) and computed
     * materialization writes. Callers: [com.kevin.legion.engine.RecordStore] only. */
    @Update
    suspend fun update(record: EngineRecord)

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getById(id: Long): EngineRecord?

    /** Looks a record up by its cross-device [EngineRecord.guid] rather than its per-database
     * [EngineRecord.id] - the ONLY lookup [com.kevin.legion.engine.mirror.MirrorSync] uses to match
     * an imported mirror row against a local record. See [EngineRecord]'s own doc comment for why
     * [id] itself is never safe to match across two phones. */
    @Query("SELECT * FROM records WHERE guid = :guid")
    suspend fun getByGuid(guid: String): EngineRecord?

    /** Live (non-trashed) records of one type - the shape both list screens and
     * [com.kevin.legion.engine.RecordStore]'s aggregate recompute read against. */
    @Query("SELECT * FROM records WHERE recordTypeId = :recordTypeId AND deletedAt IS NULL ORDER BY id ASC")
    suspend fun activeByRecordType(recordTypeId: Long): List<EngineRecord>

    @Query("SELECT * FROM records WHERE recordTypeId = :recordTypeId AND deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun trashedByRecordType(recordTypeId: Long): List<EngineRecord>

    @Query("UPDATE records SET deletedAt = :now, updatedAt = :now WHERE id = :id AND deletedAt IS NULL")
    suspend fun trash(id: Long, now: Long): Int

    @Query("UPDATE records SET deletedAt = NULL, updatedAt = :now WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun restore(id: Long, now: Long): Int

    /** The 30-day hard purge (ticket 03 answer point 4). Only ever reaches a record trashed at
     * least 30 days ago - see [com.kevin.legion.engine.RecordStore.purgeExpiredTrash]. */
    @Query("DELETE FROM records WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeDeletedBefore(cutoff: Long): Int

    /** Simple substring search over the promoted [EngineRecord.searchText] column - the whole
     * reason that column is promoted rather than left inside the JSON payload. */
    @Query(
        "SELECT * FROM records WHERE deletedAt IS NULL AND searchText LIKE '%' || :query || '%' " +
            "ORDER BY updatedAt DESC",
    )
    suspend fun search(query: String): List<EngineRecord>

    /** Every active record with a promoted [EngineRecord.dueAt] inside `[fromMs, toMs]`, across
     * EVERY record type/aspect, ascending - the raw read behind
     * [com.kevin.legion.engine.dates.DatesAgenda.windowed] (ticket 19/ticket 05 answer point 4:
     * "agenda is a query... across the Dates aspect plus every record's dueAt column"). A read,
     * not a write, so this stays on the DAO rather than needing [com.kevin.legion.engine.RecordStore]. */
    @Query(
        "SELECT * FROM records WHERE deletedAt IS NULL AND dueAt IS NOT NULL " +
            "AND dueAt BETWEEN :fromMs AND :toMs ORDER BY dueAt ASC",
    )
    suspend fun activeWithDueAtInWindow(fromMs: Long, toMs: Long): List<EngineRecord>

    /** The next [limit] active records due at or after [afterMs], ascending - the candidate batch
     * [com.kevin.legion.engine.dates.DatesAgenda.nextUnmuted] filters down to the single soonest
     * UNMUTED one. Bounded rather than unbounded for the same "personal app's data volume" reason
     * [com.kevin.legion.engine.RecordStore]'s own class doc already accepts for its aggregate scans. */
    @Query(
        "SELECT * FROM records WHERE deletedAt IS NULL AND dueAt IS NOT NULL " +
            "AND dueAt >= :afterMs ORDER BY dueAt ASC LIMIT :limit",
    )
    suspend fun activeWithDueAtFrom(afterMs: Long, limit: Int): List<EngineRecord>
}
