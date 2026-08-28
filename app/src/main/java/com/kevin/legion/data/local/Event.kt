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
 * A merged Notes+Dates row (backend-erp Phase 4, aspect 4 of 5 - `.scratch/backend-erp/issues/05-migration-path.md`
 * phase 4, field mapping in `supabase/migrations/20260825000400_aspect_dates_notes_merged.sql`'s
 * own header comment). Mirrors [com.kevin.legion.backend.RemoteEvent] field for field on the
 * CONFIGURED path - see that data class's own doc comment for the source of truth this is a cache
 * of there.
 *
 * **RENAMED from `EventReplica`/`events_replica` (v44 -> v45, engine retirement step 4,
 * `.scratch/backend-erp/issues/15-engine-retirement-sequence.md`, "RULED 2026-08-27: notes gets
 * ONE local table").** A table called "replica" that is ALSO the primary local store on an
 * unconfigured install is a misdescription - this codebase has been bitten twice by a name that
 * promised something the code did not deliver ([upsert]'s own defeated guarantee before `b17bc88`,
 * `GeneratedFormScreen`'s "PHOTO ON FILE"). This entity is now the SINGLE local store for both
 * paths: **configured** writes land here only after a genuine server ACK (unchanged from before
 * the rename - see [com.kevin.legion.notes.NotesController.applyChange]); **unconfigured** writes
 * land here directly, with no network and no engine involved at all
 * ([com.kevin.legion.engine.migration.EngineNotesRetirementCopy] is the one-time reconcile that
 * seeds this table from the engine's own Notes `Item`/Dates `Event` records before the unconfigured
 * path is ever read from here, so nothing tagged while engine-backed is silently dropped by the
 * repoint - see that object's own class doc).
 *
 * **[id] is a LOCAL surrogate, never the server row's identity - [serverId] is that.** Two
 * different callers depend on a STABLE Long across repeated writes of the same logical row:
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
 * **On the UNCONFIGURED path there is no server at all, so [serverId] is a client-minted
 * placeholder** - a fresh `UUID.randomUUID().toString()`, same posture [PantryReceipt.syncId]
 * already established for a column that is unique/non-null but has no real server counterpart to
 * carry. It satisfies the unique index and is never looked anyone up by; nothing on the
 * unconfigured path calls [upsert]/[getByServerId] at all (direct row-by-[id] reads/writes only -
 * see [com.kevin.legion.notes.NotesController]'s own class doc for why the two paths' write shapes
 * differ even though their read shape is now identical).
 *
 * **CORRECTED 2026-08-26 (ticket 11, `.scratch/backend-erp/issues/11-notes-write-path-rewire.md`):
 * the paragraph above describes the UPDATE branch only, and until this fix the INSERT branch
 * defeated it completely.** [com.kevin.legion.backend.EventsReconcile.run] deletes every replica
 * row before refilling it (a wholesale refresh, not a diff), which means [getByServerId] inside
 * [upsert] always returns null during a refill and EVERY row took the INSERT branch - reminting
 * every local [id] on every single reconcile, not just on first sync. [id] is now DERIVED rather
 * than autoincrement-allocated wherever a source is known: [com.kevin.legion.backend.EventsReconcile]
 * looks the carried id up from the originating engine record's `records.id` (via `origin_guid`)
 * and hands it to [Event] before the insert. A derived id cannot drift between reconciles
 * because it is not chosen by SQLite at all, it is read back out of the same engine row every
 * time - that is what makes it safe for [com.kevin.legion.notes.AlarmScheduler] to keep pointing
 * at it. A row with no engine ancestor (no `origin_guid`, or one absent from the current engine
 * set) still autoincrements a fresh id, which is correct: there is nothing to derive it from.
 * **The unconfigured path's own copier ([com.kevin.legion.engine.migration.EngineNotesRetirementCopy])
 * reuses this exact idea, not [upsert] itself** - it seats each engine record directly at its OWN
 * `records.id`, skipping any id already occupied, rather than going through the serverId-keyed
 * dance that has no meaning for a row with no server counterpart at all.
 *
 * [deleted] mirrors the server's `deleted_at IS NOT NULL`, matching [TaggedPlace.deleted]'s own
 * soft-delete-mirror convention - active reads filter `deleted = 0` here exactly as they do there.
 * **On the unconfigured path a delete is a real row deletion, not a flag flip** - see
 * [com.kevin.legion.notes.NotesController.removeItem]'s own doc comment for why: this table has no
 * 30-day-restorable trash mechanism the way the engine's `RecordStore.delete` did, and
 * [PlaceController.forgetPlace]'s own repoint (engine retirement step 1) already established the
 * same "hard delete on the local table, regardless of what the pre-repoint engine path did"
 * precedent for the identical reason.
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
 * in 3.30), so [EventDao.getAllActive] spells the policy out with the portable
 * `ORDER BY (startsAt IS NULL), startsAt ASC` idiom rather than relying on syntax this app's oldest
 * supported device cannot parse.
 */
@Entity(
    tableName = "events",
    indices = [Index("serverId", unique = true)],
)
data class Event(
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
    /** The server's own `updated_at` on the configured path, or [System.currentTimeMillis] at
     * write time on the unconfigured one - the "as of" clock for the cache-first read path (ticket
     * 01 ruling 9), same role as [RemotePlace.updatedAtMs]/[TaggedPlace.timestamp]. */
    val updatedAtMs: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /**
     * The row's own creation instant - the server's `created_at` on the configured path, or the
     * unconfigured write's own `now` at [com.kevin.legion.notes.NotesController.addItem] time.
     * `public.events.created_at` is `timestamptz NOT NULL DEFAULT now()` - v40 -> v41,
     * `.scratch/backend-erp/issues/11-notes-write-path-rewire.md`'s own follow-up. **This is NOT
     * cosmetic.** [com.kevin.legion.notes.NotesController.allItems] is the funnel
     * [com.kevin.legion.advisor.GoalChecklistSync]'s "already materialized today" gate and
     * [com.kevin.legion.advisor.digest.LogDigestBuilder]'s FRESH/AGING/STALE age buckets both read
     * through, and both key entirely off [com.kevin.legion.data.local.ListItem.createdAt].
     * `DEFAULT 0` on the additive column is a schema-validity placeholder for pre-existing rows
     * only; every row written after this column exists carries a real value.
     */
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    /**
     * `reminder` or `appointment` ([com.kevin.legion.backend.EventKind]) - v42 -> v43, ticket 11's
     * 2026-08-27 ruling #1. **This is why the column exists at all**, traced before adding it (a
     * new Room migration is not free): [com.kevin.legion.notes.NotesController]'s read path used
     * to read this ENTIRE table unfiltered, so a Notes reminder and a Dates appointment were
     * indistinguishable the moment they landed in the same table - the 2026-08-26 incident traced
     * 51 false "missed" marks to exactly that (50 already-deleted todos plus every genuine calendar
     * appointment, all read back as reminders `AlarmScheduler`'s sweep owned). `getById`/
     * `getAllActive` stay unfiltered (still used by [com.kevin.legion.backend.EventsReconcile]'s
     * own diff, which legitimately needs both kinds, and by
     * [com.kevin.legion.engine.migration.EngineNotesRetirementCopy]'s own occupancy check);
     * [getActiveByKind] is the query `NotesController` actually reads through. `DEFAULT 'reminder'`
     * on the additive column mirrors the server's own default (`kind text not null default
     * 'reminder'`, `supabase/migrations/20260827000200_events_kind.sql`) - the conservative
     * direction, since an unrecognized row is safer treated as something the app owns than
     * silently dropped.
     */
    @ColumnInfo(defaultValue = "'reminder'") val kind: String = "reminder",
)

/**
 * One skipped occurrence of a recurring [Event] on the CONFIGURED path only - the replica of a
 * `public.event_skips` row. Keyed on the pair, matching that table's own `(event_id, skip_date)`
 * unique constraint; there is no separate surrogate id because nothing needs to reference one row
 * here individually, unlike [Event] and its alarm-request-code caller.
 *
 * **The UNCONFIGURED path's skip data lives elsewhere, deliberately untouched by the v44 -> v45
 * rename** (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`'s own ID CONTRACT
 * section): `list_item_skips.itemId` is a soft foreign key against the SAME `records.id`/[Event.id]
 * space this file's rename preserves by construction (see [Event]'s own class doc), so the
 * existing legacy `list_item_skips` table keeps serving the unconfigured path exactly as before -
 * see [com.kevin.legion.notes.NotesController.skipOccurrence]/[NotesController.skippedDates] for
 * where that branch still lives.
 */
@Entity(
    tableName = "event_skips",
    primaryKeys = ["eventServerId", "skipDateEpochMs"],
)
data class EventSkip(
    val eventServerId: String,
    /** UTC-midnight epoch ms of the skipped calendar date - same convention as
     * [ListItemSkip.skippedDate]. */
    val skipDateEpochMs: Long,
)

@Dao
interface EventDao {
    /** Active (not tombstoned) events - what every unfiltered read renders from (both kinds; a
     * caller that owns only one, like [com.kevin.legion.notes.NotesController], reads
     * [getActiveByKind] instead). **Ordered NULLS LAST on [Event.startsAt]** (see that field's own
     * doc comment for why this app cannot use the `NULLS LAST` keyword directly): `(startsAt IS
     * NULL)` evaluates to `0` for a dated row and `1` for an undated one, so ordering by that
     * boolean first, then by the timestamp itself, puts every dated row ahead of every undated one
     * without inventing an order among the dated rows or the undated ones beyond "earliest first". */
    @Query("SELECT * FROM events WHERE deleted = 0 ORDER BY (startsAt IS NULL), startsAt ASC")
    suspend fun getAllActive(): List<Event>

    /** Active events of ONE [Event.kind] - what [com.kevin.legion.notes.NotesController]'s read
     * path actually reads through (`kind = 'reminder'`, on BOTH the configured and unconfigured
     * branch since the v45 rename), so a Dates appointment merged into this same table is never
     * mistaken for something `NotesController`/[com.kevin.legion.notes.AlarmScheduler] owns. Same
     * NULLS-LAST ordering as [getAllActive] - see that function's own doc comment for why the
     * `(startsAt IS NULL)` idiom is spelled out by hand rather than using the `NULLS LAST` keyword. */
    @Query("SELECT * FROM events WHERE deleted = 0 AND kind = :kind ORDER BY (startsAt IS NULL), startsAt ASC")
    suspend fun getActiveByKind(kind: String): List<Event>

    /** Every row including tombstones - used only by [com.kevin.legion.backend.EventsReconcile] to
     * diff against the engine's own guid set, same shape as [PantryReceiptDao.getAll]. */
    @Query("SELECT * FROM events")
    suspend fun getAll(): List<Event>

    @Query("SELECT * FROM events WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): Event?

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): Event?

    @Insert
    suspend fun insert(row: Event): Long

    @Update
    suspend fun update(row: Event)

    @Query("DELETE FROM events WHERE serverId = :serverId")
    suspend fun deleteByServerId(serverId: String)

    /** The unconfigured path's own delete - [com.kevin.legion.notes.NotesController.removeItem]'s
     * hard-delete branch, matched by the LOCAL surrogate [Event.id] rather than [Event.serverId]
     * (there is no server row to key off of on this path at all). Returns nothing because the
     * caller already knows whether a matching row existed - it read it by id first (see that
     * function's own doc comment for why a stale/missing id must be reported as "nothing to
     * remove" rather than a false-success delete-of-zero-rows). */
    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Wipes the table clean before [com.kevin.legion.backend.EventsReconcile] refills it - same
     * role as [PantryReceiptDao.deleteAllForReplicaRefresh]. Never called from the regular
     * read/write path. */
    @Query("DELETE FROM events")
    suspend fun deleteAllForReplicaRefresh()
}

/**
 * [EventDao] has no `@Dao`-annotated upsert of its own - see [Event]'s own doc comment for why a
 * naive `OnConflictStrategy.REPLACE` on the unique [Event.serverId] index is wrong here (it would
 * mint a new local [Event.id] on every refresh). This does the read-then-write by hand instead,
 * and is a plain suspend function precisely because Room does not let a `@Dao` interface member
 * run two sequential queries. **Used only by the CONFIGURED path** ([com.kevin.legion.backend.EventsReconcile],
 * [com.kevin.legion.notes.NotesController]'s own `backend != null` branches) - the unconfigured
 * path writes [EventDao.insert]/[EventDao.update]/[EventDao.deleteById] directly, since there is no
 * `serverId` to key an upsert on in the first place (see [Event]'s own doc comment).
 *
 * Not wrapped in `withTransaction` - matches [PlaceController.tagPlace]'s own single-row-at-a-time
 * posture (only [com.kevin.legion.backend.PantryReconcile]'s multi-table receipt+lines refill
 * needs a real transaction). Returns the local [Event.id] the row now has, so a caller can hand it
 * straight to [com.kevin.legion.notes.AlarmScheduler] or a `ListItem.id`.
 *
 * **Three branches, in order, added 2026-08-26 (ticket 11) for [row]'s incoming [Event.id]
 * (0 = "no id known", set by [com.kevin.legion.backend.EventsReconcile.toReplica]'s default):**
 * 1. A row already exists for [Event.serverId] - unchanged from before. The EXISTING local id wins
 *    even if [row] carries a different one, because an established id must never move, not even to
 *    a "more correct" carried one - anything already pointing at it (a scheduled alarm, a soft
 *    foreign key) would break.
 * 2. No existing row, and [row]'s carried id is non-zero AND unoccupied ([getById] returns null
 *    for it) - insert AT that id. This is the new branch: it is what lets a wholesale replica
 *    refresh put a migrated row back at its originating engine record's id instead of an
 *    autoincremented one.
 * 3. Otherwise - no carried id, or the carried id collides with a row already sitting on it -
 *    insert with `id = 0` and let SQLite autoincrement. **The collision guard matters on its own:**
 *    a post-cutover row with no engine ancestor autoincrements freely and can legitimately land on
 *    any id, including one a not-yet-processed migrated row wants to carry. Clobbering that row
 *    (silently reassigning or deleting it) would be strictly worse than handing the migrated row a
 *    fresh id instead - the whole point of carrying the id is to avoid rekeying an EXISTING
 *    consumer's reference, and there is no existing consumer for a row that has not been inserted
 *    yet. A fresh id here costs nothing; a stolen id would cost the other row's own alarm/reference.
 */
suspend fun EventDao.upsert(row: Event): Long {
    val existing = getByServerId(row.serverId)
    if (existing != null) {
        update(row.copy(id = existing.id))
        return existing.id
    }
    if (row.id != 0L && getById(row.id) == null) {
        return insert(row)
    }
    return insert(row.copy(id = 0))
}

@Dao
interface EventSkipDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: EventSkip)

    @Query("SELECT skipDateEpochMs FROM event_skips WHERE eventServerId = :eventServerId")
    suspend fun forEvent(eventServerId: String): List<Long>

    /** Wipes the table clean before [com.kevin.legion.backend.EventsReconcile] refills it - same
     * role as [EventDao.deleteAllForReplicaRefresh]. */
    @Query("DELETE FROM event_skips")
    suspend fun deleteAllForReplicaRefresh()
}
