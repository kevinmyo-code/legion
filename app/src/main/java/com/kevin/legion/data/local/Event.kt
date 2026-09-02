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
    /**
     * **Two disjoint id ranges live in this one column, by construction, not by guard** (coordinator
     * follow-up round 2 on backend-erp ticket 17, 2026-08-28 - the collision-safety-net round found
     * this was not enough: for an appointment losing the tie a reassigned id is cosmetic, but
     * [id] for a REMINDER is an `AlarmManager` `PendingIntent` request code, a notification id, and
     * a soft foreign key from `list_item_skips`/`workout_set_logs`/`muted_reminders` - the exact
     * class of harm `b17bc88` and the 51-false-missed incident both came from, and "unlikely" is not
     * "impossible" for a silent failure).
     *
     * - **Reminders (`kind = reminder`) keep the LOW range, always** - a reminder's carried id, on
     *   the configured refill path, IS [com.kevin.legion.data.local.EngineRecord.id] (`records.id`),
     *   the engine's own single global autoincrement shared across every aspect - see
     *   [com.kevin.legion.backend.EventsReconcile]'s own class doc for why that source stays
     *   authoritative. This column never chooses that value; it only ever RECEIVES it.
     * - **Appointments (`kind = appointment`) are pinned to [APPOINTMENT_ID_BASE] and above,
     *   always**, by [EventDao.nextAppointmentId]'s own allocator - one-today ticket 01 moved this
     *   off the retired `calendar/CalendarImportController.kt` onto this DAO extension directly;
     *   `service/LiveToolbox.kt`'s `addAppointment` is the only LIVE caller now (a historical
     *   Google-imported row already carries an id this allocator minted at import time). See [APPOINTMENT_ID_BASE]'s
     *   own doc comment for the property this establishes and why a constant offset (not a runtime
     *   guard) is what makes the two ranges disjoint BY CONSTRUCTION - there is nothing for either
     *   side to check against the other, because there is nothing left to collide over.
     * - **The one exception, and it is provably safe, not overlooked:**
     *   [com.kevin.legion.engine.migration.EngineNotesRetirementCopy] seats a HISTORICAL, pre-repoint
     *   Dates appointment at its OWN original `records.id` (a LOW value) - this looks like it breaks
     *   the rule above, but it cannot collide with any reminder's carried id either, because BOTH
     *   values are drawn from the exact same single `records.id` counter, and two DIFFERENT engine
     *   records (a Dates one and a Notes one) can never share that counter's value by construction -
     *   the same "no two engine records ever collide with each other" property
     *   [com.kevin.legion.backend.EventsReconcile] already relies on elsewhere. This is the only
     *   appointment-creating path exempt from [APPOINTMENT_ID_BASE], and it is exempt because it
     *   inherits an EQUALLY strong disjointness guarantee from a different source, not because the
     *   rule was relaxed for it.
     */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * **WIDENED to nullable, v58 -> v59 ([MIGRATION_58_59]), events-outbox ticket.** Used to be
     * `NOT NULL` and every locally-authored row (before a real server round trip) carried a
     * client-minted placeholder UUID here instead - `service/LiveToolbox.kt`'s `addAppointment`
     * and this file's own [com.kevin.legion.notes.NotesController]-equivalent unconfigured branch
     * both did this, and [com.kevin.legion.backend.EventsSync]'s own class doc names the exact cost:
     * "both are syntactically identical UUID strings; there is no structural way to tell them
     * apart" from a genuine server uuid. **A row that has never touched the server now carries
     * `null` here instead of a fake value that reads exactly like a real one** - [guid] is (and
     * always was) that row's real, stable sync identity; a null [serverId] is simply "no round trip
     * yet", filled in for real the next time [com.kevin.legion.backend.EventsSync.pull] matches
     * this row by [guid] against the server's own [com.kevin.legion.backend.RemoteEvent.originGuid].
     * **Existing fake-UUID rows already on a device are NOT migrated to null by [MIGRATION_58_59]**
     * - out of scope for that migration (nothing distinguishes a fake UUID from a real one at the
     * value level, so there is nothing safe to null out after the fact); only NEW rows written after
     * this change ever carry null. A caller that must act on "genuinely synced or not" for an
     * OLD row cannot use this column's nullness as that signal and has no way to ask the question
     * at all - the same documented limitation [EventsSync]'s own class doc already states.
     */
    val serverId: String?,
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
     * **WIDENED 2026-09-01 (one-today ticket 08, "events are not todos") from a two-value column
     * this doc comment used to describe as `reminder` or `appointment`.** That framing answered
     * "who owns this row, Notes or Dates" and quietly stood in for a different question the app
     * actually needed answered - "does this row have a completion state at all" - and the two
     * questions came apart on real data: some Google-imported `appointment` rows were assignments
     * genuinely completed (a submitted homework), not classes that pass whether or not you engage
     * with them. Three values now ([com.kevin.legion.backend.EventKind] - see that object's own
     * class doc for the full account):
     * - `reminder` - unchanged, user-set, alarm-bearing, completable.
     * - `event` - passes, never completable, no checkbox anywhere in the UI. Every row that used to
     *   read `appointment` was reclassified here by [MIGRATION_56_57], with `done`/`doneAt` CLEARED
     *   (not merely hidden) on every one of them.
     * - `task` - completable, may carry a due date with no alarm. Nothing writes one yet; Canvas is
     *   its own ticket.
     *
     * v42 -> v43, ticket 11's 2026-08-27 ruling #1, is when the column itself was added. **This is
     * why the column exists at all**, traced before adding it (a new Room migration is not free):
     * [com.kevin.legion.notes.NotesController]'s read path used to read this ENTIRE table
     * unfiltered, so a Notes reminder and a Dates appointment were indistinguishable the moment
     * they landed in the same table - the 2026-08-26 incident traced 51 false "missed" marks to
     * exactly that (50 already-deleted todos plus every genuine calendar appointment, all read back
     * as reminders `AlarmScheduler`'s sweep owned). `getById`/`getAllActive` stay unfiltered (still
     * used by [com.kevin.legion.backend.EventsReconcile]'s own diff, which legitimately needs every
     * kind, and by [com.kevin.legion.engine.migration.EngineNotesRetirementCopy]'s own occupancy
     * check); [getActiveByKind] is the query `NotesController` actually reads through. `DEFAULT
     * 'reminder'` on the additive column mirrors the server's own default (`kind text not null
     * default 'reminder'`, `supabase/migrations/20260827000200_events_kind.sql`) - the conservative
     * direction, since an unrecognized row is safer treated as something the app owns than
     * silently dropped. **No CHECK constraint exists here** (confirmed against this table's own
     * `createSql` in `app/schemas/`), which is what let the vocabulary widen with no schema change
     * beyond the data-only [MIGRATION_56_57] - CLAUDE.md §5's "widening a TEXT-stored enum is not a
     * migration" rule, applied.
     */
    @ColumnInfo(defaultValue = "'reminder'") val kind: String = "reminder",
    /**
     * The parsed `LEGION::v1` description-block map (v47 -> v48,
     * `.scratch/backend-erp/issues/17-dates-is-engine-only.md`'s "RULED 2026-08-28") - a compact
     * JSON object string, or null for the common case of an event with no such block. Mirrors
     * [com.kevin.legion.backend.RemoteEvent.structuredMeta] field for field; see [MIGRATION_47_48]'s
     * own doc comment for why this column exists now when [com.kevin.legion.backend.EventsReconcile]
     * once deliberately declined to add it. Notes `Item` rows never carry one - Google Calendar is
     * the only source of a `LEGION::v1` block, and Notes has no Google side at all. */
    val structuredMeta: String? = null,
    /**
     * A locally-minted, immutable identity for this row - v48 -> v49
     * (`.scratch/backend-erp/issues/17-dates-is-engine-only.md`, coordinator follow-up after the
     * initial repoint: "EventsReconcile's Dates branch reads exactly stale engine rows... imported
     * appointment reaches the server by no route at all"). Plays the exact role
     * [com.kevin.legion.data.local.EngineRecord.guid] played for a Dates event before this repoint -
     * a stable key [com.kevin.legion.backend.EventsReconcile] can hand to
     * [com.kevin.legion.backend.MigratedEvent.originGuid] so a re-run recognizes "already uploaded"
     * instead of minting a duplicate server row.
     *
     * **Deliberately NOT [serverId].** [serverId] starts as a client-minted placeholder but gets
     * OVERWRITTEN with the server's own real uuid the moment [com.kevin.legion.backend.EventsReconcile]'s
     * wholesale refill re-seats this row from server data (see that class's own `toReplica`) - a
     * value that mutates over the row's lifetime cannot also be the identity a re-run's idempotency
     * check depends on staying constant, or a second reconcile would upload every Dates appointment
     * a second time under a "new" identity. [guid] is never touched by that refill except to be
     * carried forward unchanged (from [com.kevin.legion.backend.RemoteEvent.originGuid], which is
     * exactly the value this column supplied on the upload that created it) - same posture
     * `service_records.syncId` already established for the identical problem, ticket 16's own
     * precedent (`FleetReconcile`'s class doc: "a SOURCE change, not an identity change").
     *
     * **Minted at row creation** - historically by the retired `calendar/CalendarImportController.
     * buildEventRow` (one-today ticket 01 deleted that file; the 261 rows it created keep their
     * already-minted [guid] unchanged, per this column's own "never regenerated" rule below), now by
     * `service/LiveToolbox.kt`'s `addAppointment` for a freshly voice-created one - for every
     * `kind = `[com.kevin.legion.backend.EventKind.EVENT] row and preserved on every later
     * update via `copy` - never regenerated for a row
     * that already has one. A `kind = `[com.kevin.legion.backend.EventKind.REMINDER] row (Notes)
     * never has this read - [com.kevin.legion.backend.EventsReconcile]'s Notes branch stays
     * engine-sourced (see that object's own class doc for why the two branches are not symmetric),
     * so it is left at its Kotlin default there; nothing currently reads it for that kind. `DEFAULT
     * ''` on the additive column is a schema-validity placeholder only - [MIGRATION_48_49] backfills
     * every pre-existing row a real one, matching [MIGRATION_36_37]'s identical `records.guid`
     * recipe, so the column is never actually blank in a live database as of that backfill.
     *
     * **Deliberately NOT a unique index, unlike [records.guid][com.kevin.legion.data.local.EngineRecord.guid]/
     * [serverId] - caught by the real build, not designed in.** Every `kind = `[com.kevin.legion.backend.EventKind.REMINDER]
     * row (Notes) leaves this at its Kotlin default (blank) by design (see this doc comment's own
     * "never has this read" paragraph above), so more than one such row sharing the value `""`
     * is normal, expected, and must never fail a constraint - a unique index here would reject the
     * SECOND Notes reminder ever created on an unconfigured install. Nothing queries `events` BY
     * this column (every reader already has the row in hand before consulting it), so no index is
     * needed for lookup performance either.
     */
    @ColumnInfo(defaultValue = "''") val guid: String = "",
) {
    companion object {
        /**
         * **The property this establishes: no [id] a reminder might ever carry can equal an [id]
         * an appointment might ever be allocated - unconditionally, by construction, never by a
         * runtime check on either side.** Coordinator follow-up round 2 on backend-erp ticket 17
         * (2026-08-28): a runtime collision GUARD (round 1's fix) resolves a collision after it
         * happens and was judged not good enough - for a reminder specifically, the loser of that
         * guard's tie-break gets a silently reassigned id, orphaning an armed `AlarmManager` alarm
         * that can then never be cancelled while the real one drops - the exact class of harm
         * `b17bc88` and the 2026-08-26 51-false-missed incident both came from. A disjoint range
         * removes the contest entirely: there is nothing for either side to check, because there is
         * nothing left that could collide.
         *
         * **Why a constant offset, not something cleverer.** A reminder's id is
         * [com.kevin.legion.data.local.EngineRecord.id] (`records.id`) - the engine's single global
         * autoincrement, shared across EVERY aspect this app has ever had or will have (fleet,
         * ledger, pantry, notes, dates, drives...). This is a personal, single-user app; even
         * decades of heavy use across every aspect combined would not plausibly approach the low
         * millions of engine records, let alone this base. `100_000_000L` (one hundred million) is
         * chosen to sit comfortably clear of any realistic `records.id`, while leaving over two
         * billion values of headroom below `Long`'s own range and, more immediately, below
         * `Int.MAX_VALUE` (~2.147 billion) - appointment ids are cast via `.toInt()` for
         * `AlarmManager`/notification request codes exactly as reminder ids are
         * (`service/DatesReminderAlarmReceiver.kt`'s `postNotification`), and a base near that
         * ceiling would have traded one collision class for another.
         *
         * **What allocates from here, and what does not.**
         * [EventDao.nextAppointmentId] is the ONLY allocator (moved from the retired
         * `calendar/CalendarImportController.kt` by one-today ticket 01) - every NEW appointment row
         * is explicitly inserted at a value that function returns, never left to the table's own
         * natural autoincrement (which would draw from whatever the table's shared, cross-kind
         * high-water mark happens to be, defeating the whole point).
         * [com.kevin.legion.engine.migration.EngineNotesRetirementCopy] seats
         * HISTORICAL Dates appointments at their own `records.id` instead - see [Event.id]'s own
         * doc comment for why that is a documented, provably-safe exception rather than a gap.
         *
         * **A bare magic number invites someone to "simplify" it later - it must not be lowered or
         * removed without re-establishing this exact property some other way.**
         */
        const val APPOINTMENT_ID_BASE = 100_000_000L
    }
}

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

    /** Bounded next-batch read for one [kind], dated rows only, ascending - the candidate batch
     * [com.kevin.legion.engine.dates.DatesAgenda.nextUnmuted] filters down to the single soonest
     * UNMUTED appointment (backend-erp ticket 17's repoint, "RULED 2026-08-28": Dates now reads
     * `events` directly instead of the engine). `startsAt IS NOT NULL` mirrors
     * [com.kevin.legion.data.local.EngineRecordDao.activeWithDueAtFrom]'s identical guard for the
     * identical reason - an inferred ("tomorrow") row is never eligible for the alarm scheduler,
     * see that method's own doc comment and CLAUDE.md sec 7's compulsion test clause (a). */
    @Query(
        "SELECT * FROM events WHERE deleted = 0 AND kind = :kind " +
            "AND startsAt IS NOT NULL AND startsAt >= :afterMs ORDER BY startsAt ASC LIMIT :limit",
    )
    suspend fun activeByKindFrom(kind: String, afterMs: Long, limit: Int): List<Event>

    /** Every row including tombstones - used only by [com.kevin.legion.backend.EventsReconcile] to
     * diff against the engine's own guid set, same shape as [PantryReceiptDao.getAll]. */
    @Query("SELECT * FROM events")
    suspend fun getAll(): List<Event>

    @Query("SELECT * FROM events WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): Event?

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): Event?

    /** The highest [Event.id] at or above [floor], or null when nothing occupies that range yet -
     * the raw read behind [EventDao.nextAppointmentId], which uses it (with [floor] =
     * [Event.APPOINTMENT_ID_BASE]) to allocate the next appointment id from the disjoint high
     * range. See [Event.APPOINTMENT_ID_BASE]'s own doc comment for the
     * property this exists to serve - nothing else in this codebase should call this with a
     * different [floor]. */
    @Query("SELECT MAX(id) FROM events WHERE id >= :floor")
    suspend fun maxIdAtOrAbove(floor: Long): Long?

    /** Active [Event]s of one [kind] whose own [Event.startsAt] falls in `[fromMs, toMs]` -
     * one-today ticket 01's replacement for the old live `CalendarContract.Instances` window
     * query: every screen/tool that used to call [com.kevin.legion.calendar.CalendarProvider.eventsInWindow]
     * now reads this table directly instead, since every Google-imported appointment already lives
     * here (`kind = 'appointment'`) and a voice-created one is written straight here too (see
     * `service/LiveToolbox.kt`'s `addAppointment`). An undated row never matches - every appointment
     * this app has ever created or imported carries a real [Event.startsAt] - so `startsAt IS NOT
     * NULL` is a defensive floor, not a filter that excludes real rows. */
    @Query(
        "SELECT * FROM events WHERE deleted = 0 AND kind = :kind " +
            "AND startsAt IS NOT NULL AND startsAt >= :fromMs AND startsAt <= :toMs ORDER BY startsAt ASC",
    )
    suspend fun activeByKindInWindow(kind: String, fromMs: Long, toMs: Long): List<Event>

    /** Active, TIMED (never all-day) [Event]s of one [kind] whose `[startsAt, endsAt)` span covers
     * [nowMs] - `service/ProactiveDelivery.kt`'s "is an appointment running right now" check,
     * repointed off `CalendarProvider.eventsInWindow(now, now+1)` onto this table by one-today
     * ticket 01. An all-day row is deliberately excluded, matching the exact filter the old live
     * check applied (`!it.allDay && it.startMs <= now && it.endMs > now`) - an all-day event is
     * never "in progress" in the sense that should block an unprompted spoken raise. */
    @Query(
        "SELECT * FROM events WHERE deleted = 0 AND kind = :kind AND allDay = 0 " +
            "AND startsAt IS NOT NULL AND startsAt <= :nowMs AND endsAt IS NOT NULL AND endsAt > :nowMs",
    )
    suspend fun activeTimedByKindRunningAt(kind: String, nowMs: Long): List<Event>

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
     * read/write path. **Unused by [com.kevin.legion.backend.EventsReconcile] since the 2026-08-28
     * coordinator follow-up** - that refill now wipes only [deleteByKindForReplicaRefresh]'s
     * `kind = reminder` rows, never appointments (see that function's own doc comment). Left in
     * place rather than deleted: still the correct shape for a caller that genuinely wants the
     * whole table gone (none exists today), and CLAUDE.md's "nothing deleted" discipline for a
     * shared write door extends to not deleting a still-correct, still-compiling DAO method with
     * no live caller either. */
    @Query("DELETE FROM events")
    suspend fun deleteAllForReplicaRefresh()

    /** Wipes only one [kind]'s rows before [com.kevin.legion.backend.EventsReconcile]'s refill -
     * added 2026-08-28 (coordinator follow-up on backend-erp ticket 17) because the wholesale
     * [deleteAllForReplicaRefresh] forces every refilled row through a single shared "carry or
     * derive an id" scheme, and Dates appointments and Notes reminders now draw their carried ids
     * from two INDEPENDENT autoincrement spaces (`Event.id` itself for appointments, the engine's
     * `records.id` for reminders) that can coincidentally collide. [com.kevin.legion.backend.EventsReconcile]
     * wipes only `kind = reminder` here and refills those through the existing carry/derive dance;
     * appointment rows are never deleted at all - they already live at a known, stable local id and
     * are updated in place instead (see that file's own `run` for the split). */
    @Query("DELETE FROM events WHERE kind = :kind")
    suspend fun deleteByKindForReplicaRefresh(kind: String)
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
 *    foreign key) would break. **[row]'s [Event.serverId] must be non-null to reach this branch at
 *    all** (v58 -> v59 widening, [MIGRATION_58_59]) - a row with no serverId yet has nothing to look
 *    up by, so the null case skips straight to branch 2/3 below. Every real caller of this function
 *    already only ever passes a genuine, post-ACK serverId here (see
 *    [com.kevin.legion.notes.NotesController.applyChange]/`addItem`); the null-safe `?.let` exists
 *    for type-correctness against the now-nullable field, not because this function is expected to
 *    receive one in practice.
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
    val existing = row.serverId?.let { getByServerId(it) }
    if (existing != null) {
        update(row.copy(id = existing.id))
        return existing.id
    }
    if (row.id != 0L && getById(row.id) == null) {
        return insert(row)
    }
    return insert(row.copy(id = 0))
}

/**
 * The next id a genuinely NEW appointment row must be explicitly inserted at -
 * [Event.APPOINTMENT_ID_BASE]'s own doc comment for the disjoint-range property this serves.
 * **Moved here from the now-deleted `calendar/CalendarImportController.kt`** (one-today ticket 01,
 * "cut Google entirely") - `service/LiveToolbox.kt`'s `addAppointment` is now the only LIVE
 * allocator of a new appointment row (the historical import is retired; its own rows already carry
 * an id from this same allocator, minted when they were imported). Reads the table's current
 * appointment high-water mark fresh rather than caching one - correct regardless of how many
 * appointments a caller creates in a row, simple over clever at this app's personal scale.
 */
suspend fun EventDao.nextAppointmentId(): Long {
    val current = maxIdAtOrAbove(Event.APPOINTMENT_ID_BASE)
    return (current ?: (Event.APPOINTMENT_ID_BASE - 1)) + 1
}

/**
 * [activeByKindInWindow], corrected for the two incompatible ways this table encodes a row's date
 * (found on-device 2026-09-01, Kevin: "the due dates seem to be advanced by 1 day some how" -
 * every one of 98 rows sitting at exactly UTC midnight rendered on the previous local day). An
 * [Event.allDay] row's [Event.startsAt] is **UTC midnight of its calendar date**
 * (`service/LiveToolbox.kt`'s `addAppointment` comment: "Android's own all-day convention...
 * preserved here even though the write is local now"), the same convention every already-imported
 * Google appointment row uses; a TIMED row's [Event.startsAt] is an ordinary device-zone instant.
 * A plain `startsAt BETWEEN fromMs AND toMs` compares both conventions as if they were the same
 * one, which is only correct for the timed half - an all-day row can sit up to a day outside
 * `[fromMs, toMs]` purely because of the device's own UTC offset, silently moving it to the
 * adjacent day (or, at a month/window boundary, dropping it from the result entirely: UTC midnight
 * of the 1st is `Aug 31, 19:00` local at UTC-5, before any window that starts at local midnight on
 * the 1st).
 *
 * **[com.kevin.legion.ui.notes.agendaDayStart] already solved exactly this problem for the ONE
 * caller that merges into an [com.kevin.legion.ui.AgendaEntry]** (fixed 2026-08-18, back when the
 * two conventions were Google-vs-local rather than allDay-vs-timed) - see that function's own doc
 * comment for the identical UTC-recovery idiom. This is the same rule, generalized to every OTHER
 * caller that queries [Event] rows directly by window rather than through that merge (`ui/CalendarScreen.kt`'s
 * day SCHEDULE section, `ui/notes/InboxScreen.kt`'s forward/day-filtered fetches,
 * `service/LiveToolbox.kt`'s `read_calendar`, `advisor/digest/LogDigestBuilder.kt`'s calendar
 * line) - each of those re-implemented the same naive window query and inherited the same bug.
 *
 * The raw SQL window is widened by a full day on each side (covers every real UTC offset, +-14h,
 * inside one extra day of slack) so no genuinely in-range all-day row can fall outside the widened
 * query, then every returned row is re-checked against the CALLER's real `[fromMs, toMs]` using its
 * own correct anchor: the UTC-interpreted calendar date, re-anchored to [zone]'s local day-start,
 * for an all-day row; the raw [Event.startsAt] instant, unchanged, for a timed one.
 */
suspend fun EventDao.activeByKindInLocalWindow(kind: String, fromMs: Long, toMs: Long, zone: java.time.ZoneId): List<Event> {
    val widenMs = java.time.Duration.ofDays(1).toMillis()
    return activeByKindInWindow(kind, fromMs - widenMs, toMs + widenMs).filter { row ->
        val at = row.startsAt ?: return@filter false
        val anchor = if (row.allDay) {
            java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            at
        }
        anchor in fromMs..toMs
    }
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
