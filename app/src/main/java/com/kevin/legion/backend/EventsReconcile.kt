package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EventReplica
import com.kevin.legion.data.local.upsert
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.engine.notes.NotesAspectSeeder
import org.json.JSONObject

/**
 * The one-time (and re-runnable) Phase 4 step 1/2 job for Notes+Dates, cut over TOGETHER because
 * the merge itself (ticket 01 ruling 4, `supabase/migrations/20260825000400_aspect_dates_notes_merged.sql`'s
 * own header comment) has to happen exactly once regardless of which aspect a given engine record
 * came from - a Dates `Event` and a Notes `Item` both become one `public.events` row. Same overall
 * shape as [PlacesReconcile]/[PantryReconcile]: upload, diff, refill the replica, report.
 *
 * **Never touches, trashes, or deletes an engine record.** The engine stays the source of truth
 * until [Report.isClean] - deleting the engine's copy is a LATER phase (phase 6).
 *
 * **The merge decision this file has to make, stated plainly (task brief's own ask).** `public.events`
 * declared exactly one required pair, `title` and `starts_at` (both `NOT NULL`), and a Notes `Item`
 * record does not always have the second - a plain checklist entry with no time trigger at all has
 * [NotesAspectSeeder.FIELD_STARTS_AT] null. **RULED 2026-08-26 (Kevin), ticket 07: `starts_at` is
 * now NULLABLE server-side** (`supabase/migrations/20260826000400_events_starts_at_nullable.sql`,
 * applied and verified against the live project - `title` is still `NOT NULL`). Measured against
 * Kevin's real database, 53 of 56 Notes `Item` rows have no start date, so the old "skip it"
 * posture below was leaving behind 95% of everything this aspect actually holds.
 *
 * **An undated Notes `Item` is now an ordinary row: uploaded with a null `starts_at`, never a
 * guessed one.** CLAUDE.md section 4 rule 5 forbids inventing a fact the source record does not
 * state (the same principle ticket 01 ruling 2 already applied to a related but READ-side case: an
 * undated todo may be RENDERED as "showing tomorrow", but that inferred default is never stored as
 * if it were real, and storage is exactly what synthesizing a date here would be). Storing NULL,
 * not a sentinel and not a guess, is what keeps this within rule 5 while still giving the row a
 * home. [Report.uploadedUndated] is a plain count of how many uploaded rows this run are undated -
 * useful information, not an exception, and it does NOT hold [Report.isClean] false, same posture
 * [PantryReconcile.Report.uploadedUnreconciled] already established for its own renamed
 * once-was-an-exception field.
 *
 * **`starts_at` is the agenda's sort key, so nullable means every ordering query needs an explicit
 * null policy.** The policy is NULLS LAST (a dated item outranks an undated one on a timeline) -
 * see [com.kevin.legion.data.local.EventReplica]'s own doc comment for why that has to be spelled
 * out by hand rather than relying on the `NULLS LAST` keyword.
 */
object EventsReconcile {

    /**
     * @param datesEngineCount how many active Dates `Event` engine records existed.
     * @param notesEngineCount how many active Notes `Item` engine records existed.
     * @param uploaded how many of the reconciling records were genuinely NEW server-side this run
     *   (idempotent re-run reports 0 new, matching [PantryReconcile.Report.uploaded]'s own
     *   "already migrated" semantics - see [EventsBackend.uploadMigratedEvent]'s doc comment).
     * @param uploadedUndated a plain COUNT of how many of [uploaded]'s rows this run are Notes
     *   `Item`s with no `startsAt` - stored with a null `starts_at`, never a guessed one. Renamed
     *   from the old `skippedUndated` (a list, held [isClean] false, was never uploaded at all) now
     *   that ticket 07's ruling makes an undated item an ordinary row - see this object's own class
     *   doc.
     * @param serverCountAfter the server's active event count after the upload.
     * @param replicaCountAfter the Room replica's active event count after being refreshed.
     * @param onlyOnEngine `records.guid`s the engine has (from either aspect, reconciling or not)
     *   that the server does not - same posture as [PantryReconcile.Report.onlyOnEngine].
     * @param onlyOnServer `origin_guid`s the server has that the engine does not - a migrated row
     *   whose engine original has since vanished, or a stale engine read. A row created directly
     *   through [EventsBackend.upsert] carries no `origin_guid` and is correctly excluded from this
     *   comparison, same as [PantryReconcile.Report.onlyOnServer].
     */
    data class Report(
        val datesEngineCount: Int,
        val notesEngineCount: Int,
        val uploaded: Int,
        val uploadedUndated: Int,
        val serverCountAfter: Int,
        val replicaCountAfter: Int,
        val onlyOnEngine: List<String>,
        val onlyOnServer: List<String>,
    ) {
        /** True only when every reconciling engine record landed on the server and nothing is
         * left over on either side. [uploadedUndated] does NOT keep this false on its own - an
         * undated item genuinely lands on the server now, it is just reported separately so a
         * caller can never mistake it for a dated row (same posture
         * [PantryReconcile.Report.isClean]'s own doc comment states for `uploadedUnreconciled`). */
        val isClean: Boolean get() = onlyOnEngine.isEmpty() && onlyOnServer.isEmpty()
    }

    /** @param engineRecordId the engine's own `records.id` this row came from - carried through so
     * the refilled replica row can be minted at THAT id rather than a fresh autoincrement one. See
     * this file's own `run` for why: [com.kevin.legion.data.local.EventReplica.id] is load-bearing
     * (alarm request codes, notification ids, soft foreign keys), and a wholesale replica refresh
     * used to remint every one of them on every reconcile. */
    private data class EngineEvent(val guid: String, val engineRecordId: Long, val fields: EventFields, val skipDatesEpochMs: List<Long> = emptyList())

    suspend fun run(context: Context, backend: EventsBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)
        val datesSch = DatesAspectSeeder.ensureSeeded(context)
        val notesSch = NotesAspectSeeder.ensureSeeded(context)

        // ---- Dates aspect Event records: a direct field-for-field carry, every field required
        // on this side already lines up with public.events' own required pair.
        val dateRecords = db.engineRecordDao().activeByRecordType(datesSch.recordTypeId)
        val dateEvents = dateRecords.mapNotNull { record ->
            val payload = JSONObject(record.payload)
            fun s(name: String) = PayloadCodec.readString(payload, datesSch.fieldIds.getValue(name))
            fun l(name: String) = PayloadCodec.readLong(payload, datesSch.fieldIds.getValue(name))

            fun bool(name: String) = PayloadCodec.readBoolean(payload, datesSch.fieldIds.getValue(name))

            val title = s(DatesAspectSeeder.FIELD_TITLE) ?: return@mapNotNull null
            val start = l(DatesAspectSeeder.FIELD_START) ?: return@mapNotNull null
            EngineEvent(
                guid = record.guid,
                engineRecordId = record.id,
                fields = EventFields(
                    title = title,
                    createdAtMs = record.createdAt,
                    startsAtMs = start,
                    endsAtMs = l(DatesAspectSeeder.FIELD_END),
                    // Coordinator-caught defect (2026-08-27): this was hardcoded `false` for every
                    // Dates Event, silently discarding an all-day Google import on the exact field
                    // CalendarImportController's own widening was supposed to rescue. The Notes
                    // branch below already read its own allDay field correctly; Dates simply had
                    // none to read until DatesAspectSeeder.FIELD_ALL_DAY existed.
                    allDay = bool(DatesAspectSeeder.FIELD_ALL_DAY),
                    location = s(DatesAspectSeeder.FIELD_LOCATION),
                    notes = s(DatesAspectSeeder.FIELD_NOTES),
                    // The LEGION::v1 block, already parsed into its own engine field by
                    // CalendarImportController - carried through verbatim (still JSON text) rather
                    // than re-parsed, so it reaches public.events.structured_meta and survives past
                    // the engine's own eventual retirement (ticket 01 ruling 11 / ruling 7). See
                    // RemoteEvent.structuredMeta's own doc comment.
                    structuredMeta = s(DatesAspectSeeder.FIELD_STRUCTURED_META),
                    source = s(DatesAspectSeeder.FIELD_SOURCE) ?: DatesAspectSeeder.SOURCE_LEGION,
                    googleEventId = s(DatesAspectSeeder.FIELD_GOOGLE_EVENT_ID),
                ),
            )
        }

        // ---- Notes aspect Item records: the merge. See this object's own class doc for the
        // undated-record ruling - a null startsAt is uploaded as-is, never invented.
        val itemRecords = db.engineRecordDao().activeByRecordType(notesSch.recordTypeId)
        val noteEvents = itemRecords.mapNotNull { record ->
            val payload = JSONObject(record.payload)
            fun s(name: String) = PayloadCodec.readString(payload, notesSch.fieldIds.getValue(name))
            fun l(name: String) = PayloadCodec.readLong(payload, notesSch.fieldIds.getValue(name))
            fun i(name: String) = PayloadCodec.readDouble(payload, notesSch.fieldIds.getValue(name))?.toInt()
            fun b(name: String, default: Boolean = false) = PayloadCodec.readBoolean(payload, notesSch.fieldIds.getValue(name), default)

            val text = s(NotesAspectSeeder.FIELD_TEXT) ?: return@mapNotNull null
            val startsAt = l(NotesAspectSeeder.FIELD_STARTS_AT)

            val skips = db.listItemSkipDao().skippedDatesForItem(record.id)

            EngineEvent(
                guid = record.guid,
                engineRecordId = record.id,
                fields = EventFields(
                    title = text,
                    createdAtMs = record.createdAt,
                    startsAtMs = startsAt,
                    endsAtMs = l(NotesAspectSeeder.FIELD_ENDS_AT),
                    allDay = b(NotesAspectSeeder.FIELD_ALL_DAY, default = true),
                    location = null,
                    notes = null,
                    source = DatesAspectSeeder.SOURCE_LEGION,
                    googleEventId = null,
                    done = b(NotesAspectSeeder.FIELD_DONE),
                    doneAtMs = l(NotesAspectSeeder.FIELD_DONE_AT),
                    sortOrder = i(NotesAspectSeeder.FIELD_SORT_ORDER),
                    triggerPlaceLabel = s(NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL),
                    repeatKind = s(NotesAspectSeeder.FIELD_REPEAT_KIND),
                    repeatEvery = i(NotesAspectSeeder.FIELD_REPEAT_EVERY),
                    repeatDaysOfWeek = s(NotesAspectSeeder.FIELD_REPEAT_DAYS_OF_WEEK),
                    repeatDay = i(NotesAspectSeeder.FIELD_REPEAT_DAY),
                    repeatMonth = i(NotesAspectSeeder.FIELD_REPEAT_MONTH),
                    repeatEndKind = s(NotesAspectSeeder.FIELD_REPEAT_END_KIND),
                    repeatEndDateMs = l(NotesAspectSeeder.FIELD_REPEAT_END_DATE),
                    repeatEndCount = i(NotesAspectSeeder.FIELD_REPEAT_END_COUNT),
                    exact = b(NotesAspectSeeder.FIELD_EXACT),
                    exactDowngraded = b(NotesAspectSeeder.FIELD_EXACT_DOWNGRADED),
                    missedAtMs = l(NotesAspectSeeder.FIELD_MISSED_AT),
                    missedDismissedAtMs = l(NotesAspectSeeder.FIELD_MISSED_DISMISSED_AT),
                    loggedAtMs = l(NotesAspectSeeder.FIELD_LOGGED_AT),
                ),
                skipDatesEpochMs = skips,
            )
        }

        val reconciling = dateEvents + noteEvents

        var uploaded = 0
        var uploadedUndated = 0
        for (engineEvent in reconciling) {
            val migrated = MigratedEvent(
                originGuid = engineEvent.guid,
                fields = engineEvent.fields,
                skipDatesEpochMs = engineEvent.skipDatesEpochMs,
            )
            val wasNewUpload = backend.uploadMigratedEvent(migrated).getOrElse { return Result.failure(it) }
            if (wasNewUpload) {
                uploaded++
                if (engineEvent.fields.startsAtMs == null) uploadedUndated++
            }
        }

        val serverEvents = backend.fetchActive().getOrElse { return Result.failure(it) }

        // Kevin's ruling 2026-08-26 (ticket 11): carry the engine id into the replica instead of
        // letting the wholesale refresh below remint one for every row. guid -> engine records.id,
        // built from `reconciling` (both aspects) so either origin resolves.
        val engineIdByGuid = reconciling.associate { it.guid to it.engineRecordId }

        db.eventReplicaDao().deleteAllForReplicaRefresh()
        db.eventSkipReplicaDao().deleteAllForReplicaRefresh()

        // Rows WITH a carried id are refilled first, and this ordering is load-bearing rather than
        // tidy. The table was just emptied, so an ancestor-less row (created after the cutover on
        // another surface, no `origin_guid`) autoincrements from 1 and can land on precisely the id
        // a not-yet-processed migrated row needs to carry. `upsert`'s collision guard then does the
        // safe thing and hands the migrated row a fresh id instead - which is exactly the alarm
        // orphaning the carry exists to prevent, arrived at by a different route. Seating every
        // derivable id before any id is allocated removes the contest instead of adjudicating it:
        // an ancestor-less row has nothing pointing at its local id yet, so it is the one that can
        // afford to move.
        val (carried, ancestorless) = serverEvents.partition { row ->
            row.originGuid?.let { engineIdByGuid.containsKey(it) } == true
        }
        for (row in carried + ancestorless) {
            val carriedId = row.originGuid?.let { engineIdByGuid[it] } ?: 0L
            db.eventReplicaDao().upsert(row.toReplica(id = carriedId))
            val skips = backend.fetchSkips(row.serverId).getOrElse { return Result.failure(it) }
            for (skipMs in skips) {
                db.eventSkipReplicaDao().insert(
                    com.kevin.legion.data.local.EventSkipReplica(eventServerId = row.serverId, skipDateEpochMs = skipMs),
                )
            }
        }

        val engineGuids = reconciling.map { it.guid }.toSet()
        val serverGuids = serverEvents.mapNotNull { it.originGuid }.toSet()

        return Result.success(
            Report(
                datesEngineCount = dateEvents.size,
                notesEngineCount = itemRecords.size,
                uploaded = uploaded,
                uploadedUndated = uploadedUndated,
                serverCountAfter = serverEvents.size,
                replicaCountAfter = db.eventReplicaDao().getAllActive().size,
                onlyOnEngine = (engineGuids - serverGuids).sorted(),
                onlyOnServer = (serverGuids - engineGuids).sorted(),
            ),
        )
    }

    /** [RemoteEvent] -> [EventReplica], field for field - the Room side of the same shape.
     * @param id the id to mint this row at, when known - a carried engine `records.id` for a row
     * that has one, or 0 to let [EventReplicaDao.upsert] autoincrement (a post-cutover row with no
     * engine ancestor). Kept as a parameter rather than mutated at each call site so the mapping
     * from "which id" stays in the one place ([run]'s `engineIdByGuid` lookup).
     *
     * **Deliberately NOT "field for field" for [RemoteEvent.structuredMeta]** - traced every
     * reader of [EventReplica] and [com.kevin.legion.notes.NotesController.allItems]/every screen
     * built on it, and nothing on the phone renders a `LEGION::v1` block today (the only live
     * consumer is the `read_calendar` voice tool at `service/LiveToolbox.kt:3114`, which reads
     * straight off a LIVE Google description via [com.kevin.legion.calendar.CalendarReadToolLogic.structuredBlock],
     * never off this replica). Adding an unread column to [EventReplica] would be a Room v42 -> v43
     * migration bought for nothing; the value still reaches the one store that needs to outlive
     * both Google and the engine (`public.events.structured_meta`, via [RemoteEvent.structuredMeta]
     * above) even though this replica does not carry it. Revisit if a screen or widget is ever
     * built to render it. */
    private fun RemoteEvent.toReplica(id: Long = 0) = EventReplica(
        id = id,
        serverId = serverId,
        title = title,
        createdAt = createdAtMs,
        startsAt = startsAtMs,
        endsAt = endsAtMs,
        allDay = allDay,
        location = location,
        notes = notes,
        source = source,
        googleEventId = googleEventId,
        done = done,
        doneAt = doneAtMs,
        sortOrder = sortOrder,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDate = repeatEndDateMs,
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAt = missedAtMs,
        missedDismissedAt = missedDismissedAtMs,
        loggedAt = loggedAtMs,
        updatedAtMs = updatedAtMs,
        deleted = deleted,
    )
}

/** [EventFields] with defaults for the Notes-only columns a Dates `Event` record never carries -
 * kept as a small secondary constructor rather than widening [EventFields] itself, since every
 * other caller (the live [com.kevin.legion.notes.NotesController] write path) always has a real
 * value for every field. */
private fun EventFields(
    title: String,
    createdAtMs: Long?,
    startsAtMs: Long?,
    endsAtMs: Long?,
    allDay: Boolean,
    location: String?,
    notes: String?,
    structuredMeta: String?,
    source: String,
    googleEventId: String?,
) = EventFields(
    title = title,
    createdAtMs = createdAtMs,
    startsAtMs = startsAtMs,
    endsAtMs = endsAtMs,
    allDay = allDay,
    location = location,
    notes = notes,
    structuredMeta = structuredMeta,
    source = source,
    googleEventId = googleEventId,
    done = false,
    doneAtMs = null,
    sortOrder = null,
    triggerPlaceLabel = null,
    repeatKind = null,
    repeatEvery = null,
    repeatDaysOfWeek = null,
    repeatDay = null,
    repeatMonth = null,
    repeatEndKind = null,
    repeatEndDateMs = null,
    repeatEndCount = null,
    exact = false,
    exactDowngraded = false,
    missedAtMs = null,
    missedDismissedAtMs = null,
    loggedAtMs = null,
)
