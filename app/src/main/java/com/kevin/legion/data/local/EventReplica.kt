package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * The Room replica of a `public.events` row (backend-erp Phase 4, aspect 4 of 5 - Notes+Dates
 * merged, `.scratch/backend-erp/issues/05-migration-path.md` phase 4, field mapping in
 * `supabase/migrations/20260825000400_aspect_dates_notes_merged.sql`'s own header comment).
 * Mirrors [com.kevin.legion.backend.RemoteEvent] field for field - see that data class's own doc
 * comment for the source of truth this is a cache of.
 *
 * **[id] is a LOCAL surrogate, never the server row's identity - [serverId] is that.** Two
 * different callers depend on a STABLE Long across repeated upserts of the same server row:
 * [com.kevin.legion.notes.NotesController]'s callers treat `ListItem.id` as a durable handle (it
 * is the alarm `PendingIntent` request code in `notes/AlarmScheduler.kt`), and a naive
 * `OnConflictStrategy.REPLACE` keyed on a unique [serverId] index would DELETE-then-REINSERT the
 * conflicting row, handing back a brand new autoincrement id every single refresh and silently
 * orphaning any alarm already scheduled against the old one. [upsert] (this file's own top-level
 * extension function, not a DAO method - Room does not let a `@Dao` interface member do two
 * sequential queries) reads any existing row by [serverId] first and reuses its [id] on the
 * `UPDATE` branch, exactly so a re-synced row keeps the same local identity every place that
 * matters gets fabricated. Same posture as [PantryReceipt.syncId]/[TaggedPlace]'s own local-vs-
 * server key split, adapted for a table where the LOCAL key (not the server one) is what the rest
 * of the app depends on staying put.
 *
 * [deleted] mirrors the server's `deleted_at IS NOT NULL`, matching [TaggedPlace.deleted]'s own
 * soft-delete-mirror convention - active reads filter `deleted = 0` here exactly as they do there.
 *
 * **[startsAt] is nullable (v39 -> v40, backend-erp ticket 07, "RULED 2026-08-26: option 1").**
 * `public.events.starts_at` was widened to nullable on the live project
 * (`supabase/migrations/20260826000400_events_starts_at_nullable.sql`) so a genuinely dateless
 * Notes `Item` (measured 53 of 56 real rows) has somewhere to live - see [MIGRATION_39_40]'s own
 * doc comment for the schema change and [EventsReconcile]'s class doc for why this is now an
 * ordinary row rather than a skipped one. **A null [startsAt] is never a guessed date** - it means
 * the source record stated none, and CLAUDE.md section 4 rule 5 forbids storing an inferred one.
 * **NULLS LAST is the ordering policy** for every read that sorts by this column: a dated item
 * outranks an undated one on a timeline. SQLite's own default for `ORDER BY x ASC` is NULLS FIRST
 * (the opposite), and minSdk 24's bundled SQLite predates the `NULLS LAST` keyword (added upstream
 * in 3.30), so [EventReplicaDao.getAllActive] spells the policy out with the portable
 * `ORDER BY (startsAt IS NULL), startsAt ASC` idiom rather than relying on syntax this app's oldest
 * supported device cannot parse.
 */
@Entity(
    tableName = "events_replica",
    indices = [Index("serverId", unique = true)],
)
data class EventReplica(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val title: String,
    val startsAt: Long?,
    val endsAt: Long? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    val source: String,
    val googleEventId: String? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val sortOrder: Int? = null,
    val triggerPlaceLabel: String? = null,
    val repeatKind: String? = null,
    val repeatEvery: Int? = null,
    val repeatDaysOfWeek: String? = null,
    val repeatDay: Int? = null,
    val repeatMonth: Int? = null,
    val repeatEndKind: String? = null,
    val repeatEndDate: Long? = null,
    val repeatEndCount: Int? = null,
    val exact: Boolean = false,
    val exactDowngraded: Boolean = false,
    val missedAt: Long? = null,
    val missedDismissedAt: Long? = null,
    val loggedAt: Long? = null,
    /** The server's own `updated_at` - the "as of" clock for the cache-first read path (ticket 01
     * ruling 9), same role as [RemotePlace.updatedAtMs]/[TaggedPlace.timestamp]. */
    val updatedAtMs: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/**
 * One skipped occurrence of a recurring [EventReplica] - the replica of a `public.event_skips`
 * row. Keyed on the pair, matching that table's own `(event_id, skip_date)` unique constraint;
 * there is no separate surrogate id because nothing needs to reference one row here individually,
 * unlike [EventReplica] and its alarm-request-code caller.
 */
@Entity(
    tableName = "event_skips_replica",
    primaryKeys = ["eventServerId", "skipDateEpochMs"],
)
data class EventSkipReplica(
    val eventServerId: String,
    /** UTC-midnight epoch ms of the skipped calendar date - same convention as
     * [ListItemSkip.skippedDate]. */
    val skipDateEpochMs: Long,
)

@Dao
interface EventReplicaDao {
    /** Active (not tombstoned) events - what every read in the CONFIGURED path renders from.
     * **Ordered NULLS LAST on [EventReplica.startsAt]** (see that field's own doc comment for why
     * this app cannot use the `NULLS LAST` keyword directly): `(startsAt IS NULL)` evaluates to
     * `0` for a dated row and `1` for an undated one, so ordering by that boolean first, then by
     * the timestamp itself, puts every dated row ahead of every undated one without inventing an
     * order among the dated rows or the undated ones beyond "earliest first". */
    @Query("SELECT * FROM events_replica WHERE deleted = 0 ORDER BY (startsAt IS NULL), startsAt ASC")
    suspend fun getAllActive(): List<EventReplica>

    /** Every row including tombstones - used only by
     * [com.kevin.legion.backend.EventsReconcile] to diff against the engine's own guid set, same
     * shape as [PantryReceiptDao.getAll]. */
    @Query("SELECT * FROM events_replica")
    suspend fun getAll(): List<EventReplica>

    @Query("SELECT * FROM events_replica WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): EventReplica?

    @Query("SELECT * FROM events_replica WHERE id = :id")
    suspend fun getById(id: Long): EventReplica?

    @Insert
    suspend fun insert(row: EventReplica): Long

    @Update
    suspend fun update(row: EventReplica)

    @Query("DELETE FROM events_replica WHERE serverId = :serverId")
    suspend fun deleteByServerId(serverId: String)

    /** Wipes the replica clean before [com.kevin.legion.backend.EventsReconcile] refills it - same
     * role as [PantryReceiptDao.deleteAllForReplicaRefresh]. Never called from the regular
     * read/write path. */
    @Query("DELETE FROM events_replica")
    suspend fun deleteAllForReplicaRefresh()
}

/**
 * [EventReplicaDao] has no `@Dao`-annotated upsert of its own - see [EventReplica]'s own doc
 * comment for why a naive `OnConflictStrategy.REPLACE` on the unique [EventReplica.serverId] index
 * is wrong here (it would mint a new local [EventReplica.id] on every refresh). This does the
 * read-then-write by hand instead, and is a plain suspend function precisely because Room does not
 * let a `@Dao` interface member run two sequential queries.
 *
 * Not wrapped in `withTransaction` - matches [PlaceController.tagPlace]'s own single-row-at-a-time
 * posture (only [com.kevin.legion.backend.PantryReconcile]'s multi-table receipt+lines refill
 * needs a real transaction). Returns the local [EventReplica.id] the row now has, so a caller can
 * hand it straight to [com.kevin.legion.notes.AlarmScheduler] or a `ListItem.id`.
 */
suspend fun EventReplicaDao.upsert(row: EventReplica): Long {
    val existing = getByServerId(row.serverId)
    return if (existing != null) {
        update(row.copy(id = existing.id))
        existing.id
    } else {
        insert(row.copy(id = 0))
    }
}

@Dao
interface EventSkipReplicaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: EventSkipReplica)

    @Query("SELECT skipDateEpochMs FROM event_skips_replica WHERE eventServerId = :eventServerId")
    suspend fun forEvent(eventServerId: String): List<Long>

    /** Wipes the replica clean before [com.kevin.legion.backend.EventsReconcile] refills it - same
     * role as [EventReplicaDao.deleteAllForReplicaRefresh]. */
    @Query("DELETE FROM event_skips_replica")
    suspend fun deleteAllForReplicaRefresh()
}
