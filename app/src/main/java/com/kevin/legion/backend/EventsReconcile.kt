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
 * declares exactly one required pair: `title` and `starts_at` (both `NOT NULL`). A Dates `Event`
 * record always has both - [DatesAspectSeeder.FIELD_START] is itself `required = true`. A Notes
 * `Item` record does NOT - a plain checklist entry with no time trigger at all has
 * [NotesAspectSeeder.FIELD_STARTS_AT] null, and CLAUDE.md's own estimate/anchor discipline forbids
 * inventing a fact the source record does not state (the same principle ticket 01 ruling 2 already
 * applied to a related but READ-side case: an undated todo may be RENDERED as "showing tomorrow",
 * but that inferred default is explicitly never stored as if it were a real date, and storage is
 * exactly what this migration would be doing). **The safest option, and the one this file takes:
 * a Notes `Item` with no `startsAt` is not uploaded at all.** It is reported in
 * [Report.skippedUndated], the run continues for every other record, and the item stays exactly
 * where it already lives - on the engine, read the unchanged way, until either it gains a date or
 * a later ticket revisits whether "one merged table" can also hold a genuinely dateless row (it
 * cannot, today, without either a schema change loosening `starts_at` or a policy decision to
 * synthesize one - and CLAUDE.md forbids the second outright). This mirrors
 * [PantryReconcile.Report.skippedUnreconciled]'s own precedent: a named, counted exception is not
 * a silent drop, and a non-empty list here is an EXPECTED steady state for as long as undated
 * checklist items exist, not a bug to chase to zero.
 */
object EventsReconcile {

    /**
     * @param datesEngineCount how many active Dates `Event` engine records existed.
     * @param notesEngineCount how many active Notes `Item` engine records existed, BEFORE
     *   [skippedUndated] removes the dateless ones from the upload set.
     * @param uploaded how many of the reconciling records were genuinely NEW server-side this run
     *   (idempotent re-run reports 0 new, matching [PantryReconcile.Report.uploaded]'s own
     *   "already migrated" semantics - see [EventsBackend.uploadMigratedEvent]'s doc comment).
     * @param skippedUndated one entry per Notes `Item` with no `startsAt` - see this object's own
     *   class doc for why these are never uploaded, never invented a date for.
     * @param serverCountAfter the server's active event count after the upload.
     * @param replicaCountAfter the Room replica's active event count after being refreshed.
     * @param onlyOnEngine `records.guid`s the engine has (from either aspect, reconciling or not)
     *   that the server does not - non-empty after a clean run only for guids also present in
     *   [skippedUndated] (same posture as [PantryReconcile.Report.onlyOnEngine]).
     * @param onlyOnServer `origin_guid`s the server has that the engine does not - a migrated row
     *   whose engine original has since vanished, or a stale engine read. A row created directly
     *   through [EventsBackend.upsert] carries no `origin_guid` and is correctly excluded from this
     *   comparison, same as [PantryReconcile.Report.onlyOnServer].
     */
    data class Report(
        val datesEngineCount: Int,
        val notesEngineCount: Int,
        val uploaded: Int,
        val skippedUndated: List<String>,
        val serverCountAfter: Int,
        val replicaCountAfter: Int,
        val onlyOnEngine: List<String>,
        val onlyOnServer: List<String>,
    ) {
        /** True only when every reconciling engine record landed on the server and nothing is
         * left over on either side. A non-empty [skippedUndated] always keeps this false - an
         * undated item this migration refused to invent a date for is not a clean diff, it is a
         * named, expected exception (see this file's own class doc). */
        val isClean: Boolean get() = onlyOnEngine.isEmpty() && onlyOnServer.isEmpty() && skippedUndated.isEmpty()
    }

    private data class EngineEvent(val guid: String, val fields: EventFields, val skipDatesEpochMs: List<Long> = emptyList())

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

            val title = s(DatesAspectSeeder.FIELD_TITLE) ?: return@mapNotNull null
            val start = l(DatesAspectSeeder.FIELD_START) ?: return@mapNotNull null
            EngineEvent(
                guid = record.guid,
                fields = EventFields(
                    title = title,
                    startsAtMs = start,
                    endsAtMs = l(DatesAspectSeeder.FIELD_END),
                    allDay = false,
                    location = s(DatesAspectSeeder.FIELD_LOCATION),
                    notes = s(DatesAspectSeeder.FIELD_NOTES),
                    source = s(DatesAspectSeeder.FIELD_SOURCE) ?: DatesAspectSeeder.SOURCE_LEGION,
                    googleEventId = s(DatesAspectSeeder.FIELD_GOOGLE_EVENT_ID),
                ),
            )
        }

        // ---- Notes aspect Item records: the merge. See this object's own class doc for the
        // undated-record ruling.
        val itemRecords = db.engineRecordDao().activeByRecordType(notesSch.recordTypeId)
        val skippedUndated = mutableListOf<String>()
        val noteEvents = itemRecords.mapNotNull { record ->
            val payload = JSONObject(record.payload)
            fun s(name: String) = PayloadCodec.readString(payload, notesSch.fieldIds.getValue(name))
            fun l(name: String) = PayloadCodec.readLong(payload, notesSch.fieldIds.getValue(name))
            fun i(name: String) = PayloadCodec.readDouble(payload, notesSch.fieldIds.getValue(name))?.toInt()
            fun b(name: String, default: Boolean = false) = PayloadCodec.readBoolean(payload, notesSch.fieldIds.getValue(name), default)

            val text = s(NotesAspectSeeder.FIELD_TEXT) ?: return@mapNotNull null
            val startsAt = l(NotesAspectSeeder.FIELD_STARTS_AT)
            if (startsAt == null) {
                skippedUndated.add("$text (${record.guid})")
                return@mapNotNull null
            }

            val skips = db.listItemSkipDao().skippedDatesForItem(record.id)

            EngineEvent(
                guid = record.guid,
                fields = EventFields(
                    title = text,
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
        for (engineEvent in reconciling) {
            val migrated = MigratedEvent(
                originGuid = engineEvent.guid,
                fields = engineEvent.fields,
                skipDatesEpochMs = engineEvent.skipDatesEpochMs,
            )
            val wasNewUpload = backend.uploadMigratedEvent(migrated).getOrElse { return Result.failure(it) }
            if (wasNewUpload) uploaded++
        }

        val serverEvents = backend.fetchActive().getOrElse { return Result.failure(it) }

        db.eventReplicaDao().deleteAllForReplicaRefresh()
        db.eventSkipReplicaDao().deleteAllForReplicaRefresh()
        for (row in serverEvents) {
            db.eventReplicaDao().upsert(row.toReplica())
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
                skippedUndated = skippedUndated,
                serverCountAfter = serverEvents.size,
                replicaCountAfter = db.eventReplicaDao().getAllActive().size,
                onlyOnEngine = (engineGuids - serverGuids).sorted(),
                onlyOnServer = (serverGuids - engineGuids).sorted(),
            ),
        )
    }

    /** [RemoteEvent] -> [EventReplica], field for field - the Room side of the same shape. */
    private fun RemoteEvent.toReplica() = EventReplica(
        serverId = serverId,
        title = title,
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
    startsAtMs: Long,
    endsAtMs: Long?,
    allDay: Boolean,
    location: String?,
    notes: String?,
    source: String,
    googleEventId: String?,
) = EventFields(
    title = title,
    startsAtMs = startsAtMs,
    endsAtMs = endsAtMs,
    allDay = allDay,
    location = location,
    notes = notes,
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
