package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.upsert
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.engine.notes.NotesAspectSeeder
import org.json.JSONObject
import java.util.UUID

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
 * see [com.kevin.legion.data.local.Event]'s own doc comment for why that has to be spelled
 * out by hand rather than relying on the `NULLS LAST` keyword.
 *
 * **The two branches read from DIFFERENT sources, deliberately, and that asymmetry is confirmed
 * rather than assumed (coordinator follow-up on backend-erp ticket 17, 2026-08-28).** The Dates
 * branch reads the local `events` table directly (`kind = appointment`) because
 * [com.kevin.legion.calendar.CalendarImportController] - the only writer of that kind - was itself
 * repointed off the engine onto `events` by ticket 17; reading the engine here after that repoint
 * would have kept reading a frozen historical snapshot that stopped receiving new rows the instant
 * the importer's write target moved, which is exactly what happened between this file's own
 * previous version and this one (a real, live regression: an imported appointment reached the
 * server by no route at all - CLAUDE.md's "run the real build" lesson applied to a data-flow gap
 * rather than a compile error). **The Notes branch stays engine-sourced, and that is a separate,
 * confirmed answer, not symmetry copied from the Dates fix.** [com.kevin.legion.notes.NotesController]'s
 * CONFIGURED path writes straight through [EventsBackend.upsert] on every live edit (see that
 * object's own `applyChange`) - a Notes item never needs THIS reconcile to reach the server at all
 * once a device is configured, so this branch is a genuine one-time/re-runnable MIGRATION source
 * (existing pre-configuration local data, or an unconfigured install's engine-era history), never
 * the live upload path Notes items depend on day to day. That is a materially different situation
 * from Dates, which never had (and still does not have) a configured live-write path of its own -
 * [com.kevin.legion.calendar.CalendarImportController] is unconditional, local-only, regardless of
 * whether Supabase is configured (see that object's own class doc) - so THIS reconcile is the ONLY
 * route a Dates appointment ever reaches the server by, which is why it cannot be left reading a
 * source that stopped updating. **A known gap - INVESTIGATED, not just flagged (coordinator
 * follow-up round 2, 2026-08-28: "that is the same regression a third time and I would rather
 * close it now than file it").** [com.kevin.legion.notes.NotesController]'s UNCONFIGURED write path
 * was ALSO repointed off the engine onto `events` directly (backend-erp ticket 15 step 4) before
 * this file's Notes branch was ever revisited - a Notes item created on an unconfigured install
 * after that repoint never reaches the engine either, so at first glance this branch's engine scan
 * looks stale for that case in the identical shape the Dates branch just had fixed.
 *
 * **It is NOT the same fix, and repointing it the same way would be UNSAFE - checked, not
 * assumed.** The Dates repoint was safe specifically because [com.kevin.legion.calendar.CalendarImportController]
 * has NO configured live-write path at all - every local `events` row it produces is, by
 * construction, "not yet on the server," so uploading every one of them via `uploadMigratedEvent`
 * is always correct. Notes has no such guarantee: on the CONFIGURED path,
 * [com.kevin.legion.notes.NotesController]'s own class doc states the reads are "now IDENTICAL" -
 * BOTH configured and unconfigured read `events` directly - which means a `kind = reminder` row in
 * `events` on a configured install is, in the ordinary case, ALREADY live on the server via its own
 * REAL [Event.serverId] (set on creation by [com.kevin.legion.notes.NotesController.applyChange]'s
 * configured branch, straight from the server's own ACK). A row created while the SAME device was
 * still unconfigured (before it was ever configured, or between the two) instead carries a
 * CLIENT-MINTED PLACEHOLDER in that same column - see [Event]'s own class doc for why that
 * distinction exists at all. **Both are syntactically identical UUID strings; there is no local,
 * reliable way to tell "already live via its own serverId" apart from "still a placeholder, needs
 * migrating" by inspecting the row alone.** Repointing this branch onto `events` unconditionally
 * would scan BOTH kinds together and upload the already-live ones a second time through
 * `uploadMigratedEvent` - which mints a brand-new server row every time, never upserts by serverId -
 * duplicating every already-synced reminder on the household's Postgres. That is a strictly WORSE
 * failure than the gap it would close: the current gap is incomplete (misses some unconfigured-then-
 * later-configured history), a naive repoint would be actively corrupting (duplicates live data).
 *
 * **The engine stays the correct, LOWER-RISK source for Notes precisely because it is frozen.**
 * Nothing has written a NEW Notes `Item` to the engine since ticket 15 step 4, so anything still
 * sitting there is unambiguously old, historical, and not-yet-migrated - no row there can EVER be
 * "already live," because the engine plays no part in any live write path any more. `events` cannot
 * offer that same guarantee for `kind = reminder` rows, and won't be able to until there is a real
 * way to mark "already migrated" independent of [Event.serverId]'s own overloaded meaning (a
 * dedicated flag, or reusing [Event.guid] the way the Dates branch now does - but a reminder's
 * [Event.guid] is deliberately left blank on the configured live-write path today, per that
 * property's own doc comment, so this would need its own schema and write-path change, not a read
 * repoint). Left on the engine. Flagged for its own ticket - the fix here is a real design decision
 * (a migrated-flag or a guid-based signal), not a repoint.
 */
object EventsReconcile {

    /**
     * @param datesEngineCount how many active Dates appointments existed in the LOCAL `events`
     *   table (`kind = appointment`) - named for history/API stability; no longer an engine count
     *   as of the 2026-08-28 coordinator follow-up (see this object's own class doc).
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
     * @param onlyOnServer `origin_guid`s the server has that the engine does not, AFTER
     *   [deletedOnServer]'s retraction pass has already run. A migrated row whose engine original
     *   has since vanished is retracted (soft-deleted), not left as a leftover, as of ticket 11's
     *   2026-08-27 ruling #2 - so in the ordinary case this is empty even when engine records were
     *   trashed this run; a non-empty value here means [EventsBackend.softDelete] itself reported
     *   `false` for one of [deletedOnServer]'s candidates (already gone server-side by some other
     *   path), not that the retraction was skipped. A row created directly through
     *   [EventsBackend.upsert] carries no `origin_guid` and is correctly excluded from this
     *   comparison, same as [PantryReconcile.Report.onlyOnServer].
     * @param deletedOnServer how many server rows this run soft-deleted because their `origin_guid`
     *   named an engine record that is now trashed or absent - ticket 11's 2026-08-27 ruling #2,
     *   the actual fix for the 2026-08-26 incident's root cause (a deleted todo staying "live" in
     *   `events_replica` forever, resurrected by every refill). Reported as its own field rather
     *   than folded into [onlyOnServer] or [uploaded] - a deletion is neither an upload nor a
     *   leftover, and conflating it with either would make [isClean] mean something subtly
     *   different than "everything matches on both sides".
     */
    data class Report(
        val datesEngineCount: Int,
        val notesEngineCount: Int,
        val uploaded: Int,
        val uploadedUndated: Int,
        val serverCountAfter: Int,
        val replicaCountAfter: Int,
        val deletedOnServer: Int = 0,
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

    /** @param guid the stable identity this row uploads under - EngineRecord.guid for the Notes
     * branch (still engine-sourced), Event.guid for the Dates branch (events-table-sourced since
     * backend-erp ticket 17's coordinator follow-up, 2026-08-28 - see Event.guid's own doc comment
     * for why Event.serverId cannot play this role). @param localId the LOCAL id this row already
     * has - the engine's own records.id for Notes, the events table's own Event.id for Dates -
     * carried through so the refilled replica row can be minted at THAT id rather than a fresh
     * autoincrement one. See this file's own `run` for why: Event.id is load-bearing (alarm request
     * codes, notification ids, soft foreign keys), and a wholesale replica refresh used to remint
     * every one of them on every reconcile. */
    private data class EngineEvent(val guid: String, val localId: Long, val fields: EventFields, val skipDatesEpochMs: List<Long> = emptyList())

    suspend fun run(context: Context, backend: EventsBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)
        // No DatesAspectSeeder.ensureSeeded(context) call any more - the Dates branch below reads
        // the local `events` table directly, no engine schema lookup needed (see that branch's own
        // comment). DatesAspectSeeder.SOURCE_LEGION is still used by the Notes branch, which is why
        // the import survives.
        val notesSch = NotesAspectSeeder.ensureSeeded(context)

        // ---- Dates aspect appointments: read the LOCAL `events` table directly, kind = appointment.
        // CORRECTED 2026-08-28 (coordinator follow-up, same day as ticket 17's initial repoint):
        // this branch used to read the engine's Dates record type, which was correct only until
        // CalendarImportController's OWN write target moved off the engine onto `events` in that
        // same ticket - after that this branch was reading a frozen historical snapshot that stopped
        // receiving new rows the instant the importer's write target moved. Ticket 16's shape one
        // ticket later: a repoint moved the writes, this reconcile kept reading where they used to
        // be, and the projection silently stopped receiving new rows - there it was oil changes,
        // here it was appointments never reaching the server at all. See this object's own class
        // doc for why the Notes branch below stays engine-sourced instead of following this repoint
        // - a genuinely different question, confirmed rather than assumed symmetric.
        //
        // No datesSch/PayloadCodec/JSONObject involved any more - an Event row already carries
        // every field flat, no schema lookup needed. Every active appointment is included
        // regardless of startsAt (nullable now, matching the Notes branch's own "an undated item
        // is an ordinary row" posture, ticket 07's ruling) - the old engine-schema-era "no start,
        // skip it" filter does not apply to a table where startsAt is legitimately nullable.
        val dateRows = db.eventDao().getActiveByKind(EventKind.APPOINTMENT)
        val dateEvents = dateRows.map { row ->
            EngineEvent(
                guid = row.guid,
                localId = row.id,
                fields = EventFields(
                    title = row.title,
                    createdAtMs = row.createdAt,
                    startsAtMs = row.startsAt,
                    endsAtMs = row.endsAt,
                    allDay = row.allDay,
                    location = row.location,
                    notes = row.notes,
                    // The LEGION::v1 block - CalendarImportController writes it straight into
                    // Event.structuredMeta now (MIGRATION_47_48), carried through verbatim here so
                    // it reaches public.events.structured_meta and survives past Google's own
                    // eventual removal (ticket 01 ruling 11 / ruling 7).
                    structuredMeta = row.structuredMeta,
                    source = row.source,
                    googleEventId = row.googleEventId,
                    // A Dates `Event` row is always an appointment - ticket 11's 2026-08-27 ruling
                    // #1. Explicit here even though it is the ONLY value this branch ever produces,
                    // so a reader never has to chase the private EventFields(...) helper below to
                    // learn what every row from this branch is tagged.
                    kind = EventKind.APPOINTMENT,
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
                localId = record.id,
                fields = EventFields(
                    title = text,
                    createdAtMs = record.createdAt,
                    startsAtMs = startsAt,
                    endsAtMs = l(NotesAspectSeeder.FIELD_ENDS_AT),
                    allDay = b(NotesAspectSeeder.FIELD_ALL_DAY, default = true),
                    location = null,
                    notes = null,
                    source = DatesAspectSeeder.SOURCE_LEGION,
                    // A Notes `Item` is always a reminder - ticket 11's 2026-08-27 ruling #1. This
                    // matches EventFields' own default, spelled out explicitly for the same reason
                    // as the Dates branch above.
                    kind = EventKind.REMINDER,
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
        val engineGuids = reconciling.map { it.guid }.toSet()

        // Ticket 11's 2026-08-27 ruling #2, and the actual root cause of the 2026-08-26 incident
        // (see AlarmScheduler.rescheduleAll's own doc comment for the incident account - that fix
        // was only ever a guard against this gap's SYMPTOM). A server row this phone once created
        // (a non-null origin_guid) whose engine record is now trashed or absent - not present in
        // this run's `reconciling` set - gets retracted here. **Bounded strictly by origin_guid,
        // and that bound is the whole safety argument**: a row with a NULL origin_guid was created
        // somewhere else (the laptop, a future surface) and this phone has no standing to delete
        // it, so it is never even considered. Soft delete only, via EventsBackend.softDelete -
        // never a hard delete, so the retraction is itself auditable. This never touches, trashes,
        // or reads the engine differently than the upload pass above already did - the engine
        // stays the one thing this whole file never writes to (this object's own class doc).
        var deletedOnServer = 0
        val toRetract = serverEvents.filter { it.originGuid != null && it.originGuid !in engineGuids }
        for (row in toRetract) {
            val didDelete = backend.softDelete(row.serverId).getOrElse { return Result.failure(it) }
            if (didDelete) deletedOnServer++
        }
        // Everything below (the id-carry map, the replica refill, and the onlyOnServer diff) must
        // see the POST-retraction server state, or a row just soft-deleted above would be written
        // straight back into the replica by the refill three lines down - undoing the retraction
        // in the same run that performed it.
        val retractedServerIds = toRetract.map { it.serverId }.toSet()
        val activeServerEvents = if (retractedServerIds.isEmpty()) serverEvents else
            serverEvents.filterNot { it.serverId in retractedServerIds }

        // ---- The refill, split by kind since 2026-08-28 (coordinator follow-up) - a REAL id
        // collision, not a theoretical one, forced this split. Reminder ids are drawn from the
        // engine's own `records.id` space; appointment ids are drawn from `events`' OWN
        // autoincrement space (Event.id) - two INDEPENDENT counters that can and did coincidentally
        // both mint "1" in a real test run. The old single wipe-and-derive scheme assumed one shared
        // id space (true before this repoint, when every row's carried id came from the SAME
        // `records.id` counter) and silently stranded whichever row lost the `upsert` collision
        // guard's tie-break onto a fresh autoincremented id - orphaning any alarm/mute pointing at
        // the old one, the exact class of bug ticket 11/15 exists to prevent. Splitting the refill
        // by kind removes the collision by construction instead of adjudicating it.
        val noteLocalIdByGuid = noteEvents.associate { it.guid to it.localId }
        val dateLocalIdByGuid = dateEvents.associate { it.guid to it.localId }
        val (serverAppointments, serverReminders) = activeServerEvents.partition { it.kind == EventKind.APPOINTMENT }

        // Reminders: UNCHANGED shape from before this fix - wipe just the reminder rows, refill
        // from server data, deriving each row's carried id from the engine's records.id space via
        // noteLocalIdByGuid. See the historical comment this replaces (still accurate for THIS half
        // alone): an ancestor-less row (no origin_guid) is seated LAST so it can never occupy an id
        // a carried row still needs.
        db.eventDao().deleteByKindForReplicaRefresh(EventKind.REMINDER)
        db.eventSkipDao().deleteAllForReplicaRefresh()
        val (carriedReminders, ancestorlessReminders) = serverReminders.partition { row ->
            row.originGuid?.let { noteLocalIdByGuid.containsKey(it) } == true
        }
        for (row in carriedReminders + ancestorlessReminders) {
            val carriedId = row.originGuid?.let { noteLocalIdByGuid[it] } ?: 0L
            db.eventDao().upsert(row.toReplica(id = carriedId))
            val skips = backend.fetchSkips(row.serverId).getOrElse { return Result.failure(it) }
            for (skipMs in skips) {
                db.eventSkipDao().insert(
                    com.kevin.legion.data.local.EventSkip(eventServerId = row.serverId, skipDateEpochMs = skipMs),
                )
            }
        }

        // Appointments: NEVER wiped. Every appointment row already lives in `events` at a KNOWN,
        // stable local id (dateRows was read from that exact table moments ago, before any upload
        // happened) - there is nothing to derive and nothing to re-seat, so this is a plain UPDATE
        // at the row's own existing id, not an upsert through a shared collision-prone id space.
        // Dates has no skip concept (skipDatesEpochMs is only ever populated for the Notes branch
        // above), so there is no skips fetch here at all. An ancestor-less appointment (no
        // origin_guid) has no local row to update and is left alone - see EventDao.upsert's own doc
        // comment / this file's toReplica doc comment for why nothing currently produces one
        // (Dates has no configured live-write path of its own today).
        for (row in serverAppointments) {
            val localId = row.originGuid?.let { dateLocalIdByGuid[it] } ?: continue
            db.eventDao().update(row.toReplica(id = localId))
        }

        val serverGuids = activeServerEvents.mapNotNull { it.originGuid }.toSet()

        return Result.success(
            Report(
                datesEngineCount = dateEvents.size,
                notesEngineCount = itemRecords.size,
                uploaded = uploaded,
                uploadedUndated = uploadedUndated,
                serverCountAfter = activeServerEvents.size,
                replicaCountAfter = db.eventDao().getAllActive().size,
                deletedOnServer = deletedOnServer,
                onlyOnEngine = (engineGuids - serverGuids).sorted(),
                onlyOnServer = (serverGuids - engineGuids).sorted(),
            ),
        )
    }

    /** [RemoteEvent] -> [Event], field for field - the Room side of the same shape.
     * @param id the id to mint this row at, when known - a carried engine `records.id` for a row
     * that has one, or 0 to let [EventDao.upsert] autoincrement (a post-cutover row with no
     * engine ancestor). Kept as a parameter rather than mutated at each call site so the mapping
     * from "which id" stays in the one place ([run]'s `engineIdByGuid` lookup).
     *
     * **CORRECTED 2026-08-28 (backend-erp ticket 17): this WAS deliberately not field-for-field for
     * [RemoteEvent.structuredMeta], and that reasoning is now stale.** It held only while the
     * server was `structuredMeta`'s one surviving home; now that
     * [com.kevin.legion.calendar.CalendarImportController] writes `events` directly with no server
     * round-trip at all, a Room column is the only place a Dates event's `LEGION::v1` block can
     * live on the unconfigured path, and [MIGRATION_47_48] added it precisely so this branch is not
     * the one place left silently dropping it. See that migration's own doc comment for the full
     * account. */
    private fun RemoteEvent.toReplica(id: Long = 0) = Event(
        id = id,
        serverId = serverId,
        title = title,
        createdAt = createdAtMs,
        startsAt = startsAtMs,
        endsAt = endsAtMs,
        allDay = allDay,
        location = location,
        notes = notes,
        structuredMeta = structuredMeta,
        // Restores the row's own stable local identity across the wholesale refill this function
        // feeds - originGuid IS that identity for a row this device (or any device) migrated or
        // uploaded through this reconcile, carried straight back into Event.guid so the NEXT run's
        // Dates-branch re-scan (`db.eventDao().getActiveByKind`) recognizes it as already-uploaded
        // rather than minting a duplicate server row (see Event.guid's own doc comment). A row with
        // no originGuid at all (ancestorless - created directly against the server by some OTHER
        // surface, never through this device) has no identity to restore and gets a fresh one
        // minted here instead; nothing currently produces such a row for kind=appointment (Dates has
        // no configured live-write path of its own today), so this branch is a documented,
        // currently-unreachable safety net rather than a live path - see EventsReconcile's own
        // class doc.
        guid = originGuid?.ifBlank { null } ?: UUID.randomUUID().toString(),
        source = source,
        kind = kind,
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
    kind: String,
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
    kind = kind,
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
