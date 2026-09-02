package com.kevin.legion.notes

import android.content.Context
import android.util.Log
import com.kevin.legion.backend.EventFields
import com.kevin.legion.backend.EventKind
import com.kevin.legion.backend.EventsAppointmentWriter
import com.kevin.legion.backend.EventsBackend
import com.kevin.legion.backend.RemoteEvent
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabaseEventsBackend
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.EventSkip
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.ListItemSkip
import com.kevin.legion.data.local.upsert
import com.kevin.legion.engine.migration.EngineNotesRetirementCopy
import java.util.UUID

/**
 * **Cutover 1** (`docs/architecture/cutover1-2026-08-24.md`) originally moved every read/write in
 * this file onto the engine (ADR 0035) - every caller (screens, voice tools, digests, the
 * goal-checklist sweep) kept its ORIGINAL signature and return type throughout and never had to
 * change (ADR 0035: "the controller keeps its seam"). **That engine cutover is itself retired as
 * of ticket 15 step 4** - see the class doc's second paragraph below for the current shape, which
 * is the local `events` table for both branches, exactly [com.kevin.legion.location.PlaceController]/
 * [com.kevin.legion.pantry.PantryController]'s own step 1/2 repoints.
 *
 * **`item_lists` keeps exactly one reader here ([theList]) and gains no new writer.** `ItemList`
 * itself was never migrated onto the engine (wave 1 carve: "dies... every list VERB is already
 * dead code"), and this file's own pre-cutover version already treated it as vestigial - the only
 * two writes it ever made ([theList]'s fallback insert and every mutator's `touch()` call) are both
 * gone now. [theList] still reads the legacy row so an existing install's list NAME survives
 * cutover unchanged, but a fresh install (or one where the legacy row is somehow absent) gets an
 * in-memory placeholder instead of inserting one - "the one list" is now a display convenience, not
 * a real foreign key anything points at. Every converted [ListItem]'s [ListItem.listId] is set to
 * [theList]'s id, so every caller that ever compared `item.listId == list.id` (`GoalChecklistSync`,
 * the `advisor/digest` builders) keeps working with no change of its own.
 *
 * Two rules this file is still the ONLY enforcement point for, exactly as before cutover:
 * - **A recurring item can never be ticked** ([tick] refuses and returns `false`) - ticket 04.
 * - **At most one trigger per item** - [setTime] always clears any place trigger first - charting
 *   decision 4.
 *
 * **Backend-erp Phase 4, aspect 4 of 5 - Notes+Dates merged
 * (`.scratch/backend-erp/issues/05-migration-path.md`, `.scratch/backend-erp/issues/11-notes-write-path-rewire.md`,
 * `.scratch/backend-erp/issues/15-engine-retirement-sequence.md`).** DUAL-PATH, exactly
 * [com.kevin.legion.location.PlaceController]/[com.kevin.legion.pantry.PantryController]'s shape -
 * every function funnels through [backend] first, resolved the same way (an override for tests,
 * else [SupabaseClientProvider.get] wrapped in [SupabaseEventsBackend], else null meaning "not
 * configured"):
 * - **Configured**: reads come from the local [Event] table ([allNotesItems], [itemById],
 *   [skippedDates] - see each one's own doc comment); writes go straight to
 *   [com.kevin.legion.backend.EventsBackend] and the local row is written **only on a genuine
 *   server ACK** ([applyChange]'s single write funnel) - never ahead of it, never on a failure. A
 *   failed remote write returns null/false exactly like a failed local write already does (CLAUDE.md
 *   section 7's outcome-verb rule), and Room is left completely untouched.
 * - **Not configured** (no Supabase project saved): **repointed onto the SAME `events` table as of
 *   ticket 15 step 4** (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`, "RULED
 *   2026-08-27: notes gets ONE local table") - the earlier cutover-1 engine path
 *   ([com.kevin.legion.engine.RecordStore]/`engineRecordDao()`) is retired. **This file no longer
 *   touches the engine at all** - the engine's Notes `Item` records are left exactly where they are
 *   (ticket 15: nothing is deleted until every aspect is repointed and soaked), just no longer read
 *   or written from here. [ensureLegacyReconciled] runs [EngineNotesRetirementCopy] once, first, so
 *   any item created directly through the engine since cutover 1 is not silently lost the moment
 *   this read flips - see that copier's own class doc for the id-preservation contract this whole
 *   step lives or dies on.
 *
 * **The two paths' READS are now IDENTICAL** ([allNotesItems]/[itemById] both just query `events`
 * filtered to [EventKind.REMINDER], regardless of [backend]) - only the WRITE funnel differs
 * ([applyChange]'s two branches): configured detours through the server first and writes Room only
 * on ACK; unconfigured writes the local row directly, with no network and no engine involved at
 * all. This mirrors [com.kevin.legion.location.PlaceController]'s own post-repoint shape exactly.
 *
 * **`ListItem.syncId` carries a DIFFERENT meaning depending on which path produced the [ListItem]**
 * - the configured path's [Event.toListItem] sets it to [Event.serverId], the uuid every mutator
 * hands [EventsBackend] as the row to act on; the unconfigured path's SAME mapper sets it to the
 * SAME [Event.serverId] column, but there it is a client-minted placeholder with no server meaning
 * at all (see [Event]'s own doc comment) - nothing on the unconfigured path ever reads [ListItem.syncId]
 * back out, since every unconfigured write below addresses its row by [ListItem.id] instead.
 *
 * **[ListItem.id] preservation is the entire point of the retirement step's id-carry contract**
 * (ticket 15's "THE ID CONTRACT", mirrored by ticket 11's `records.id`/[Event.id] carry,
 * commit `b17bc88`) - `notes/AlarmScheduler.kt` uses it as an `AlarmManager` `PendingIntent`
 * request code, and `list_item_skips.itemId`/`workout_set_logs.sourceListItemId`/
 * `muted_reminders.recordId` are all soft foreign keys against it. [applyChange] always writes the
 * local row at [ListItem.id]'s OWN existing value, never a fresh one - see that function's own doc
 * comment.
 */
object NotesController {
    private const val TAG = "NotesController"

    private fun db(context: Context) = CarDatabase.getDatabase(context)

    /** Test seam: settable from a unit test so an [EventsBackend] fake can be injected without a
     * real [SupabaseClientProvider] / network - same mechanism as
     * [com.kevin.legion.location.PlaceController.backendOverride]/
     * [com.kevin.legion.pantry.PantryController.backendOverride]. Defaults to null, meaning
     * "resolve normally"; production code never sets this. */
    @Volatile
    internal var backendOverride: EventsBackend? = null

    /** Resolves the active backend, or null when Supabase is not configured - the signal every
     * function below branches on. Never performs network I/O itself. */
    private fun backend(context: Context): EventsBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabaseEventsBackend(client)
    }

    /** Public sibling of [backend] that only exposes the yes/no, never the backend instance
     * itself - [AlarmScheduler.rescheduleAll] needs to know which path it is walking (server-ack'd
     * vs. local-direct writes) without needing an [EventsBackend] to call anything on. See that
     * function's own doc comment for why the answer used to change what the start-up sweep was
     * allowed to do. */
    fun isBackendConfigured(context: Context): Boolean = backend(context) != null

    /**
     * One-time reconcile gate for the unconfigured path (ticket 15 step 4): before EVER reading or
     * writing `events` from an unconfigured branch, make sure any item/event created directly
     * through the engine has already landed there. Cheap after the first call -
     * [EngineNotesRetirementCopy.copyIfNeeded] itself short-circuits on its own completion flag, so
     * this is a SharedPreferences read on every later call, not a repeat scan. Every unconfigured
     * function below calls this first so none of them can read `events` before the copy has run,
     * regardless of call order - same posture as [com.kevin.legion.location.PlaceController]'s own
     * `ensureLegacyReconciled`.
     */
    private suspend fun ensureLegacyReconciled(context: Context) {
        EngineNotesRetirementCopy.copyIfNeeded(context)
    }

    /** Name of the one and only list - see [theList]. Kept as a name rather than dropped because
     * the row still has a `name` column and a sync peer still reads it. */
    const val LIST_NAME = "List"

    /** The id every converted [ListItem] carries when no legacy `item_lists` row exists at all
     * (a fresh install that never had one) - a stable, arbitrary constant rather than a real
     * foreign key, since nothing on the `events` side references it. */
    private const val FALLBACK_LIST_ID = 1L

    // ------------------------------------------------------------------------------- the list

    /**
     * **The** list. There is exactly one (Kevin, 2026-08-11). **Read-only since cutover 1** - see
     * this object's own class doc for why the pre-cutover fallback insert and every `touch()` call
     * are both gone. Returns the legacy row if one exists (so an existing install's list name
     * survives unchanged), or an in-memory placeholder otherwise. Every function below that used to
     * take `listId` from this and touch it now only reads [ItemList.id]/[ItemList.name] off the
     * result. **Stays legacy either way** - the backend-erp dual path does not touch this function;
     * a display convenience, not a real foreign key, has nothing for a server row to back.
     */
    suspend fun theList(context: Context): ItemList {
        val existing = db(context).itemListDao().getAll(includeArchived = true)
            .filter { !it.archived }
            .minByOrNull { it.createdAt }
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        return ItemList(id = FALLBACK_LIST_ID, name = LIST_NAME, tickable = true, lastUsedAt = now, createdAt = now, updatedAt = now)
    }

    // ---------------------------------------------------------------------- events <-> ListItem bridge

    /**
     * [Event] -> [ListItem] - the ONE mapper both paths share since ticket 15 step 4 (before that,
     * the unconfigured path had its own [com.kevin.legion.data.local.EngineRecord]-based mapper;
     * see this object's own class doc for why the reads are now identical). **[ListItem.syncId]
     * carries a DIFFERENT meaning on each path**: on the configured path it is the server row's own
     * uuid ([Event.serverId]), the id every write-path mutator hands back to
     * [EventsBackend.upsert]/[EventsBackend.softDelete]/[EventsBackend.skipOccurrence] as the row to
     * act on; on the unconfigured path it is the SAME column, but a client-minted placeholder with
     * no server meaning - nothing on that path ever reads it back out. Two different backing
     * meanings under one property name is exactly the kind of thing that reads as a promise the
     * code does not keep unless it is written down here, in the one place both meanings originate.
     *
     * [ListItem.id] is [Event.id] - preserved, never reminted, on every call site that builds one
     * of these (see this object's own class doc's "id preservation" paragraph).
     */
    private fun Event.toListItem(listId: Long): ListItem = ListItem(
        id = id,
        listId = listId,
        text = title,
        done = done,
        doneAt = doneAt,
        sortOrder = sortOrder ?: 0,
        createdAt = createdAt,
        updatedAt = updatedAtMs,
        // v58 -> v59 (MIGRATION_58_59) widened Event.serverId to nullable so a genuinely
        // unsynced kind=event row (events-outbox ticket) is never confused for one that has
        // touched the server. ListItem.syncId itself stays non-null (ListItem predates that
        // widening and nothing about its own contract changed) - "" is this mapper's existing
        // convention for "nothing meaningful here" (see this function's own class doc on the
        // unconfigured path's identical placeholder-serverId posture), reused rather than
        // inventing a second empty-string-shaped meaning.
        syncId = serverId ?: "",
        deleted = deleted,
        startsAt = startsAt,
        endsAt = endsAt,
        allDay = allDay,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDate = repeatEndDate,
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAt = missedAt,
        missedDismissedAt = missedDismissedAt,
        loggedAt = loggedAt,
    )

    /**
     * [ListItem] -> [EventFields] - the wire shape [EventsBackend.upsert] expects on the
     * CONFIGURED path, mirroring [com.kevin.legion.backend.EventsReconcile]'s own Notes `Item` ->
     * [EventFields] mapping field for field (that object's `noteEvents` block) so the live write
     * path built here and the one-time migration path agree on what a Notes item looks like
     * server-side. [source] is left at [EventFields]'s own default ("legion") rather than repeating
     * `DatesAspectSeeder.SOURCE_LEGION` by name, matching [com.kevin.legion.backend.EventsReconcile]'s
     * identical value for a Notes-originated row. `location`/`notes`/`googleEventId` have no
     * [ListItem] counterpart - a Notes item never carries them - matching that same mapping's nulls
     * for the identical fields.
     *
     * [EventFields.createdAtMs] is always [ListItem.createdAt] here, never null - by the time any
     * mutator calls this, the item already exists and already has a real creation time (from
     * [Event.toListItem]), so there is nothing "unknown" left to omit.
     */
    private fun ListItem.toEventFields(): EventFields = EventFields(
        title = text,
        startsAtMs = startsAt,
        createdAtMs = createdAt,
        endsAtMs = endsAt,
        allDay = allDay,
        location = null,
        notes = null,
        googleEventId = null,
        // Every mutator that reaches this file has an existing ListItem in hand, and every
        // ListItem this file ever produces is a reminder - see allNotesItems/itemById's own kind
        // filters. Explicit for the same reason as addItem's identical value.
        kind = EventKind.REMINDER,
        done = done,
        doneAtMs = doneAt,
        sortOrder = sortOrder,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDateMs = repeatEndDate,
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAtMs = missedAt,
        missedDismissedAtMs = missedDismissedAt,
        loggedAtMs = loggedAt,
    )

    /**
     * [RemoteEvent] -> [Event], field for field - this file's own copy of
     * [com.kevin.legion.backend.EventsReconcile]'s identical private mapper. That one is private to
     * its own file and shaped for a batch refill with a different id-carry contract (derive-or-
     * autoincrement across many rows at once); duplicating twenty field assignments here is the
     * smaller cost against exporting a reconciliation-only helper or complicating a batch-oriented
     * signature for this file's single-row caller. **Used by the CONFIGURED path only** - the
     * unconfigured path never has a [RemoteEvent] to map from at all, see [ListItem.toEventRow].
     * @param id the [Event.id]/[ListItem.id] this row must be minted or updated at - see
     * [applyChange]'s own doc comment for why this is always [ListItem.id]'s existing value on a
     * live write, never freshly allocated.
     */
    private fun RemoteEvent.toReplica(id: Long) = Event(
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

    /**
     * [ListItem] -> [Event], for the UNCONFIGURED write path only - built by copying [existing]'s
     * row (never invented from scratch) and overwriting only the columns a live edit can actually
     * change. **This is why it takes [existing] rather than standing alone**: [ListItem] carries no
     * [Event.serverId]/[Event.source]/[Event.googleEventId]/[Event.location]/[Event.notes]/
     * [Event.kind] at all (see [Event.toListItem]'s own doc comment - those columns simply have no
     * [ListItem] counterpart), so a mapper with no [existing] to copy them from would have to invent
     * values for a live edit, which is exactly the kind of asserted-not-stated fact CLAUDE.md
     * section 4 rule 5 forbids applied to schema plumbing rather than content. [updatedAtMs] is
     * always refreshed to `now` - a local write is itself the "as of" instant, the same role the
     * server's own `updated_at` plays on the configured path.
     */
    private fun ListItem.toEventRow(existing: Event, now: Long = System.currentTimeMillis()): Event = existing.copy(
        title = text,
        startsAt = startsAt,
        endsAt = endsAt,
        allDay = allDay,
        done = done,
        doneAt = doneAt,
        sortOrder = sortOrder,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDate = repeatEndDate,
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAt = missedAt,
        missedDismissedAt = missedDismissedAt,
        loggedAt = loggedAt,
        updatedAtMs = now,
    )

    /** Every non-trashed ITEM (never an appointment), converted - the one place every read below
     * funnels through, so there is exactly one query regardless of which path answers it. **Both
     * paths read the SAME `events` table since ticket 15 step 4** ([EventKind.REMINDER] filtered -
     * `events` also holds every Dates `Event`/Google import, and this file must never treat one as
     * something it owns, the 2026-08-26 incident: [AlarmScheduler]'s sweep once read the whole
     * unfiltered table and marked calendar appointments "missed"). The unconfigured branch runs
     * [ensureLegacyReconciled] first so an item created directly through the engine before this
     * step is not silently missing the moment this read flips - see that function's own doc
     * comment. */
    private suspend fun allNotesItems(context: Context): List<ListItem> {
        if (backend(context) == null) ensureLegacyReconciled(context)
        val listId = theList(context).id
        return db(context).eventDao().getActiveByKind(EventKind.REMINDER).map { it.toListItem(listId) }
    }

    // ------------------------------------------------------------------------------- the write funnel

    /**
     * The one write funnel every field-updating mutator below passes through - collapsing what
     * would otherwise be an identical branch repeated in every mutator into a single
     * configured/unconfigured split, same role
     * [com.kevin.legion.location.PlaceController]/[com.kevin.legion.pantry.PantryController] give
     * their own per-function backend branches.
     *
     * - **Configured**: [backend]'s target is [item]'s OWN [ListItem.syncId], which on the
     *   configured read path IS the server row's uuid (see [Event.toListItem]'s own doc comment for
     *   why that is a DIFFERENT meaning of the same property than the unconfigured path uses - this
     *   only holds because every caller reaches [item] through one of this file's own read
     *   functions, never `events` directly). [mutated] - the SAME change, expressed as the
     *   post-write [ListItem] rather than a field map - becomes the [EventFields] sent over the
     *   wire. The local row is written ONLY on the genuine ACK, and ALWAYS at [item]'s own existing
     *   [ListItem.id] - never a freshly minted one, since that id is an `AlarmManager`
     *   `PendingIntent` request code and a soft foreign key elsewhere (this object's own class
     *   doc). A failed remote write returns null and touches Room not at all.
     * - **Unconfigured** (ticket 15 step 4): [ensureLegacyReconciled] runs first, then the EXISTING
     *   local row is read by [item]'s own [ListItem.id] and [ListItem.toEventRow] copies it forward
     *   with [mutated]'s new field values - see that mapper's own doc comment for why it needs the
     *   existing row rather than building one from scratch. The write is wrapped in a `try`/`catch`
     *   (matching [com.kevin.legion.location.PlaceController.tagPlace]'s own "worded, not thrown"
     *   fix) because this file's mutators are reachable from bare UI coroutines with no handler of
     *   their own, not only through `LiveSessionController.dispatch`'s catch-all - CLAUDE.md
     *   section 7 wants a failure result that says in words what did not happen, and an unhandled
     *   throw says nothing at all.
     *
     * Returns null on ANY failure, written or not - callers must never treat a null as "unchanged
     * and safe to carry on with the stale item", the same discipline this file's mutators already
     * established for their own null-on-failure contracts.
     */
    private suspend fun applyChange(
        context: Context,
        item: ListItem,
        mutated: ListItem,
        now: Long = System.currentTimeMillis(),
    ): ListItem? {
        val backend = backend(context)
        if (backend != null) {
            val remote = backend.upsert(item.syncId.ifEmpty { null }, mutated.toEventFields()).getOrElse { return null }
            // The local row is written ONLY here, after a genuine server ACK - never ahead of it,
            // and never on the failure branch above. id = item.id PRESERVES the existing local
            // identity; see this function's own doc comment for why that is load-bearing, not tidy.
            val replicaRow = remote.toReplica(id = item.id)
            db(context).eventDao().upsert(replicaRow)
            return replicaRow.toListItem(theList(context).id)
        }

        ensureLegacyReconciled(context)
        val existingRow = db(context).eventDao().getById(item.id) ?: return null
        val newRow = mutated.toEventRow(existingRow, now)
        return try {
            db(context).eventDao().update(newRow)
            newRow.toListItem(theList(context).id)
        } catch (e: Exception) {
            Log.w(TAG, "unconfigured write failed for item ${item.id}: ${e.message}")
            null
        }
    }

    // --------------------------------------------------------------------------------- items

    /** [listId] is accepted for signature compatibility only - since cutover there is exactly one
     * list, every item lives in it regardless of which id is passed (matching the live app's own
     * pre-cutover "one list" reality the wave 1 carve already documented). */
    suspend fun itemsForList(context: Context, listId: Long): List<ListItem> = allNotesItems(context)

    /**
     * Every non-deleted item, EXCLUDING [com.kevin.legion.advisor.GoalChecklistSync]'s own
     * `"Plan: "`-prefixed materialized lines - the inbox stream's source (found 2026-09-01, Kevin:
     * "theres a bunch of events that probably came from google calendar... i cant remove them
     * either from the app... the list is kinda useless as is"). Those lines have a dedicated home
     * now, `ui/goals/GoalChecklistPanel.kt`'s "Today's plan"/"Checklist" panel, which reads them
     * through [allItemsIncludingChecklistLines] instead - Kevin's ruling was that a SECOND copy
     * sitting in this general stream (Inbox, `ui/NotesScreen.kt`'s open count, the Calendar day
     * view's Yet-to-do/Done, and `read_list`'s spoken echo of the same stream) IS the duplication
     * he wants gone, not a second bug to chase. **The underlying [ListItem] rows are UNCHANGED
     * by this filter** - ticking, untick, and the completion-history record all still work exactly
     * as before, entirely through [com.kevin.legion.advisor.GoalChecklistSync.toggle]; this only
     * removes them from the stream every OTHER surface reads, mirroring [AlarmScheduler]'s own
     * "never treat a row this file does not own as one of its own" posture (see [allNotesItems]'s
     * doc comment for the 2026-08-26 incident that established it) - a checklist line is not a
     * reminder to sweep, mark missed, or show twice, even though it is stored in the same table. */
    suspend fun allItems(context: Context): List<ListItem> =
        allNotesItems(context).filterNot {
            it.text.startsWith(com.kevin.legion.advisor.GoalChecklistSync.ITEM_PREFIX)
        }

    /**
     * [allNotesItems], unfiltered - [com.kevin.legion.advisor.GoalChecklistSync]'s own read path
     * (`materializeToday`/`sweepPastDayAutoLog`/`trimExpiredPlanItems`/`currentItems`), and the
     * ONLY caller that may ever use it. It must see its own already-materialized `"Plan: "` lines
     * to stay idempotent - reading through the now-filtered [allItems] instead would make it think
     * none exist and re-add every one on every single call, which is a WORSE bug than the one this
     * exists to fix (repeated duplicate inserts, not merely a duplicate render). Every other caller
     * wants [allItems].
     */
    suspend fun allItemsIncludingChecklistLines(context: Context): List<ListItem> = allNotesItems(context)

    suspend fun addItemDue(
        context: Context,
        listId: Long,
        text: String,
        startsAt: Long?,
        allDay: Boolean = true,
    ): ListItem {
        val item = addItem(context, listId, text)
        // setTime returning null (a failed write) keeps the freshly-added, undated item rather
        // than fabricating a due date that never actually landed - the append itself already
        // succeeded (addItem does not itself return nullable), so this is a partial, not a total,
        // failure, and the caller still gets a real item back.
        return if (startsAt == null) item else setTime(context, item, startsAt, null, allDay) ?: item
    }

    /** Reads `events` by [Event.id] on EITHER path - both share the same physical id space (this
     * object's own class doc's "id preservation" paragraph), so a caller never needs to know which
     * path produced the id it is looking up. [Event.kind] is checked for the same reason
     * [allNotesItems] filters by it - an id this file hands out must never resolve to a Dates
     * appointment, even when looked up directly rather than found through a list. */
    suspend fun itemById(context: Context, id: Long): ListItem? {
        if (backend(context) == null) ensureLegacyReconciled(context)
        val listId = theList(context).id
        val row = db(context).eventDao().getById(id) ?: return null
        if (row.deleted || row.kind != EventKind.REMINDER) return null
        return row.toListItem(listId)
    }

    suspend fun openItemsForList(context: Context, listId: Long): List<ListItem> =
        allNotesItems(context).filter { !it.done }

    /** **Configured**: [EventsBackend.upsert] with `serverId = null` creates the row; the local
     * [ListItem.id] then comes straight from the local insert
     * ([com.kevin.legion.data.local.EventDao.upsert]'s own return value), never a value this
     * function invents. **Unconfigured** (ticket 15 step 4): a plain [com.kevin.legion.data.local.EventDao.insert]
     * of a brand-new row - no server, no engine. Both branches `error(...)` on a failed write - same
     * contract as before this fix, since a caller of this specific function has never had a way to
     * receive "it didn't save" other than a thrown error (unlike every other mutator in this file,
     * which returns null/false). */
    suspend fun addItem(context: Context, listId: Long, text: String): ListItem {
        val trimmed = text.trim()
        val now = System.currentTimeMillis()
        val nextOrder = allNotesItems(context).size

        val backend = backend(context)
        if (backend != null) {
            val fields = EventFields(
                title = trimmed,
                startsAtMs = null,
                createdAtMs = now,
                done = false,
                sortOrder = nextOrder,
                // Explicit even though it matches EventFields' own default - every live write
                // through this file IS a Notes Item, never an appointment (ticket 11's 2026-08-27
                // ruling #1; NotesController has no path that produces the other kind).
                kind = EventKind.REMINDER,
            )
            val remote = backend.upsert(serverId = null, fields = fields).getOrElse {
                error("NotesController.addItem: remote write failed - ${it.message}")
            }
            val replicaRow = remote.toReplica(id = 0)
            val id = db(context).eventDao().upsert(replicaRow)
            return replicaRow.copy(id = id).toListItem(listId)
        }

        // Unconfigured (ticket 15 step 4): `events` is the single local store for this branch too.
        // serverId is a client-minted placeholder (see [Event]'s own doc comment) - nothing on this
        // path ever looks a row up by it.
        ensureLegacyReconciled(context)
        val row = Event(
            serverId = UUID.randomUUID().toString(),
            title = trimmed,
            startsAt = null,
            allDay = true,
            source = "legion",
            kind = EventKind.REMINDER,
            done = false,
            sortOrder = nextOrder,
            updatedAtMs = now,
            createdAt = now,
            deleted = false,
        )
        val id = try {
            db(context).eventDao().insert(row)
        } catch (e: Exception) {
            error("NotesController.addItem: local write failed - ${e.message}")
        }
        return row.copy(id = id).toListItem(listId)
    }

    /** Fuzzy-matches [query] against OPEN items only - a done item can't be ticked or removed by voice this way. */
    suspend fun findItem(context: Context, listId: Long, query: String): ItemMatch =
        matchItem(query, openItemsForList(context, listId))

    /** Refuses (returns false, writes nothing) for a recurring item - ticket 04. Also returns false,
     * writing nothing further, if the write itself fails on either path (senior review, 2026-08-24:
     * a §7 outcome-verb violation - a failed write must never look like a successful tick to the
     * spoken layer). */
    suspend fun tick(context: Context, item: ListItem): Boolean {
        if (item.repeatKind != null) return false
        val now = System.currentTimeMillis()
        val mutated = item.copy(done = true, doneAt = now)
        val ok = applyChange(context, item, mutated, now) != null
        if (ok) AlarmScheduler.cancel(context, item.id)
        return ok
    }

    /** Returns false, writing nothing further, on a failed write on either path - same §7 fix as [tick]. */
    suspend fun untick(context: Context, item: ListItem): Boolean {
        val now = System.currentTimeMillis()
        val mutated = item.copy(done = false, doneAt = null, loggedAt = null)
        val result = applyChange(context, item, mutated, now)
        if (result == null) return false
        // Ticket 09 ("a ticked workout is one act, not two rows"): a correction propagates to the
        // training history - see the pre-cutover version of this comment, unchanged reasoning,
        // still a DAO call rather than a WorkoutController import (notes/ stays a foundation layer).
        db(context).workoutSetLogDao().deleteBySourceListItemId(item.id)
        return true
    }

    /** No confirmation (ticket 05). **Configured**: [EventsBackend.softDelete], and the local row
     * is removed only on a genuine `true` ACK - `Result.success(false)` (already deleted, or a
     * stale id) is a normal outcome, never reported as a delete having happened, same posture
     * [com.kevin.legion.location.PlaceController.forgetPlace] already established. **Unconfigured**
     * (ticket 15 step 4): the row's existence is checked first (matching
     * [com.kevin.legion.location.PlaceController.forgetPlace]'s own "no server ACK to check against,
     * so check the local table directly" posture), then hard-deleted -
     * [com.kevin.legion.data.local.Event]'s own doc comment explains why a delete here is a real
     * row removal rather than a soft-delete flag flip: this table has no 30-day-restorable trash
     * the way the engine's `RecordStore.delete` did. Only a confirmed delete on either path cancels
     * the alarm and reports success. */
    suspend fun removeItem(context: Context, item: ListItem): Boolean {
        val backend = backend(context)
        val trashed = if (backend != null) {
            val didDelete = backend.softDelete(item.syncId).getOrElse { return false }
            if (didDelete) db(context).eventDao().deleteByServerId(item.syncId)
            didDelete
        } else {
            ensureLegacyReconciled(context)
            if (db(context).eventDao().getById(item.id) == null) {
                false
            } else {
                try {
                    db(context).eventDao().deleteById(item.id)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "unconfigured removeItem failed for ${item.id}: ${e.message}")
                    false
                }
            }
        }
        if (trashed) AlarmScheduler.cancel(context, item.id)
        return trashed
    }

    suspend fun renameItem(context: Context, item: ListItem, text: String): Boolean {
        val trimmed = text.trim()
        val mutated = item.copy(text = trimmed)
        return applyChange(context, item, mutated) != null
    }

    /** Returns false (writing nothing further) the moment EITHER swap-half fails on either path,
     * rather than reporting success on a half-applied reorder - the two writes are not wrapped in a
     * single transaction on either path, so this is the closest this function can get to "no false
     * success" without a new capability on both [EventsBackend] and the local write. */
    suspend fun moveItem(context: Context, item: ListItem, siblingsInOrder: List<ListItem>, up: Boolean): Boolean {
        val index = siblingsInOrder.indexOfFirst { it.id == item.id }
        if (index < 0) return false
        val swapIndex = if (up) index - 1 else index + 1
        if (swapIndex !in siblingsInOrder.indices) return false
        val other = siblingsInOrder[swapIndex]
        val now = System.currentTimeMillis()

        val firstMutated = item.copy(sortOrder = other.sortOrder)
        val firstOk = applyChange(context, item, firstMutated, now) != null
        val secondMutated = other.copy(sortOrder = item.sortOrder)
        val secondOk = applyChange(context, other, secondMutated, now) != null
        return firstOk && secondOk
    }

    /** Returns null (writing nothing further - no alarm scheduled) on a failed write on either
     * path, the `ListItem`-returning counterpart of [tick]'s Boolean fix. A non-null return is a
     * genuine, confirmed write - callers must not treat a null as "unchanged" and silently carry on
     * with the stale pre-call item, the same "no false success" posture as every Boolean fix in this
     * file. */
    suspend fun setTime(context: Context, item: ListItem, startsAt: Long, endsAt: Long?, allDay: Boolean): ListItem? {
        val now = System.currentTimeMillis()
        val mutated = item.copy(
            startsAt = startsAt, endsAt = endsAt, allDay = allDay,
            triggerPlaceLabel = null, missedAt = null, missedDismissedAt = null,
        )
        val result = applyChange(context, item, mutated, now) ?: return null
        scheduleAlarmFor(context, result)
        return result
    }

    suspend fun clearTime(context: Context, item: ListItem): Boolean {
        val mutated = item.copy(startsAt = null, endsAt = null, triggerPlaceLabel = null)
        val ok = applyChange(context, item, mutated) != null
        if (ok) AlarmScheduler.cancel(context, item.id)
        return ok
    }

    /** See [setTime]'s doc comment for the null-on-failure contract this shares. */
    suspend fun setPlaceTrigger(context: Context, item: ListItem, placeLabel: String): ListItem? {
        val mutated = item.copy(startsAt = null, endsAt = null, triggerPlaceLabel = placeLabel)
        val result = applyChange(context, item, mutated) ?: return null
        // A place trigger has no clock to watch - nothing for AlarmManager to schedule.
        AlarmScheduler.cancel(context, item.id)
        return result
    }

    /** See [setTime]'s doc comment for the null-on-failure contract this shares. */
    suspend fun setRepeat(context: Context, item: ListItem, rule: RepeatRule?, end: RepeatEnd): ListItem? {
        val cols = repeatColumnsFor(rule, end)
        val mutated = item.copy(
            repeatKind = cols.repeatKind, repeatEvery = cols.repeatEvery, repeatDaysOfWeek = cols.repeatDaysOfWeek,
            repeatDay = cols.repeatDay, repeatMonth = cols.repeatMonth, repeatEndKind = cols.repeatEndKind,
            repeatEndDate = cols.repeatEndDate, repeatEndCount = cols.repeatEndCount,
        )
        val result = applyChange(context, item, mutated) ?: return null
        scheduleAlarmFor(context, result)
        return result
    }

    /** See [setTime]'s doc comment for the null-on-failure contract this shares. */
    suspend fun setExact(context: Context, item: ListItem, exact: Boolean): ListItem? {
        val mutated = item.copy(exact = exact)
        val result = applyChange(context, item, mutated) ?: return null
        scheduleAlarmFor(context, result)
        return result
    }

    /** [AlarmScheduler.schedule]'s own downgrade-persistence write - routed through [applyChange]
     * like every other field write in this file. Takes [itemId] rather than a [ListItem] (matching
     * its pre-existing signature), so it reads the item once itself; a missing/deleted item is a
     * plain false, matching what a direct write against a nonexistent id already returned before
     * this fix. */
    suspend fun setExactDowngraded(context: Context, itemId: Long, downgraded: Boolean): Boolean {
        val item = itemById(context, itemId) ?: return false
        val mutated = item.copy(exactDowngraded = downgraded)
        return applyChange(context, item, mutated) != null
    }

    /** [AlarmScheduler.rescheduleAll]'s own missed-marking write - ticket 12: "stored once, never
     * recomputed." No `missedAt IS NULL` guard here (unlike the legacy DAO query) - callers
     * ([AlarmScheduler.rescheduleAll]) already check that before calling, matching the legacy
     * comment's own "idempotent by construction... only calls this when missedAt IS NULL". Same
     * itemId-reads-itself shape as [setExactDowngraded]. */
    suspend fun markMissed(context: Context, itemId: Long): Boolean {
        val item = itemById(context, itemId) ?: return false
        val now = System.currentTimeMillis()
        val mutated = item.copy(missedAt = now)
        return applyChange(context, item, mutated, now) != null
    }

    /** [GoalChecklistSync]'s own idempotence-anchor write - see this file's own class doc for why
     * `loggedAt` needed a new home when `list_items` stopped being written. Same itemId-reads-itself
     * shape as [setExactDowngraded]. */
    suspend fun markLogged(context: Context, itemId: Long, loggedAt: Long = System.currentTimeMillis()): Boolean {
        val item = itemById(context, itemId) ?: return false
        val mutated = item.copy(loggedAt = loggedAt)
        return applyChange(context, item, mutated, loggedAt) != null
    }

    /** Skip a single occurrence, never move one (ticket 04). **Configured**:
     * [EventsBackend.skipOccurrence] against [item]'s server uuid, and the skip row is written only
     * on a genuine ACK - the legacy `list_item_skips` write stays on the unconfigured path only,
     * matching every other write in this file's "Room only on ACK" posture. **Unconfigured**:
     * unchanged by ticket 15 step 4 - [ListItemSkip.itemId] is a soft foreign key against the SAME
     * `records.id`/[Event.id] space the step's own ID CONTRACT preserves by construction, so the
     * existing legacy `list_item_skips` table keeps serving this path exactly as it did before the
     * repoint (see `.scratch/backend-erp/issues/15-engine-retirement-sequence.md`'s own "THE ID
     * CONTRACT" section for why that table was deliberately left out of scope for this step).
     * Fire-and-forget on either path, matching this function's pre-existing `Unit` return -
     * [scheduleAlarmFor] always runs afterward regardless of whether the skip write itself landed. */
    suspend fun skipOccurrence(context: Context, item: ListItem, skippedDateEpochMillis: Long) {
        val backend = backend(context)
        if (backend != null) {
            val ok = backend.skipOccurrence(item.syncId, skippedDateEpochMillis).isSuccess
            if (ok) {
                db(context).eventSkipDao().insert(
                    EventSkip(eventServerId = item.syncId, skipDateEpochMs = skippedDateEpochMillis),
                )
            }
        } else {
            db(context).listItemSkipDao().insert(ListItemSkip(itemId = item.id, skippedDate = skippedDateEpochMillis))
        }
        scheduleAlarmFor(context, item)
    }

    private suspend fun scheduleAlarmFor(context: Context, item: ListItem) {
        val startsAt = item.startsAt
        if (startsAt == null || item.done) {
            AlarmScheduler.cancel(context, item.id)
            return
        }
        if (item.repeatKind == null) {
            if (startsAt < System.currentTimeMillis()) {
                AlarmScheduler.cancel(context, item.id)
                return
            }
            AlarmScheduler.schedule(context, item, startsAt)
        } else {
            val rule = ruleFromItem(item) ?: run { AlarmScheduler.cancel(context, item.id); return }
            val end = endFromItem(item)
            // Routed through skippedDates() rather than the DAO directly, so a configured install's
            // skip data (which lives in eventSkipDao, not listItemSkipDao - see skipOccurrence's own
            // doc comment) is consulted correctly here too.
            val skips = skippedDates(context, item)
            val next = NextOccurrence.compute(startsAt, rule, end, skips, System.currentTimeMillis())
            if (next == null) {
                AlarmScheduler.cancel(context, item.id)
            } else {
                AlarmScheduler.schedule(context, item, next)
            }
        }
    }

    // ------------------------------------------------------------------------- missed / notifications

    suspend fun missedItems(context: Context): List<ListItem> =
        allNotesItems(context).filter { !it.done && it.missedAt != null && it.missedDismissedAt == null }

    suspend fun dismissMissed(context: Context, item: ListItem) {
        val now = System.currentTimeMillis()
        val mutated = item.copy(missedDismissedAt = now)
        applyChange(context, item, mutated, now)
    }

    fun notificationsBlocked(context: Context, item: ListItem): Boolean {
        val hasTrigger = item.startsAt != null || item.triggerPlaceLabel != null
        if (!hasTrigger) return false
        return permissionRefused(context)
    }

    suspend fun anyNotificationsBlocked(context: Context): Boolean {
        if (!permissionRefused(context)) return false
        val items = allNotesItems(context)
        return items.any { it.startsAt != null && !it.done } || items.any { it.triggerPlaceLabel != null && !it.done }
    }

    private fun permissionRefused(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return false
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** **Configured**: [item]'s server uuid's skips, from the local [EventSkip] table - never the
     * network. **Unconfigured**: unchanged by ticket 15 step 4 - see [skipOccurrence]'s own doc
     * comment for why `list_item_skips` stays this path's skip store. */
    suspend fun skippedDates(context: Context, item: ListItem): Set<Long> {
        if (backend(context) != null) {
            return db(context).eventSkipDao().forEvent(item.syncId).toSet()
        }
        return db(context).listItemSkipDao().skippedDatesForItem(item.id).toSet()
    }

    // ----------------------------------------------------------------------------- agenda (ticket 08)

    suspend fun timedItemsInWindow(context: Context, from: Long, to: Long): List<ListItem> =
        allNotesItems(context).filter { !it.done && it.repeatKind == null && it.startsAt != null && it.startsAt in from..to }
            .sortedBy { it.startsAt }

    suspend fun allRecurringItems(context: Context): List<ListItem> =
        allNotesItems(context).filter { it.repeatKind != null }

    suspend fun listNamesById(context: Context): Map<Long, String> {
        val list = theList(context)
        return mapOf(list.id to list.name)
    }

    // ------------------------------------------------------------------ place-trigger reads (ReminderController)

    /** Active (not-yet-done) items carrying a place trigger matching [label] - was a direct
     * `ListItemDao.openWithPlaceTrigger` call from `location/ReminderController.kt` before
     * cutover; that controller now calls here instead so `list_items` gains no reader outside this
     * file either. */
    suspend fun openWithPlaceTrigger(context: Context, label: String): List<ListItem> =
        allNotesItems(context).filter { !it.done && it.triggerPlaceLabel == label }

    /** Every open item carrying ANY place trigger - same rewiring as [openWithPlaceTrigger]. */
    suspend fun openWithAnyPlaceTrigger(context: Context): List<ListItem> =
        allNotesItems(context).filter { !it.done && it.triggerPlaceLabel != null }

    /** Every open, non-deleted item with a time trigger - [AlarmScheduler.rescheduleAll]'s one full
     * scan, rewired off `ListItemDao.allWithTimeTrigger`. Includes done items too, matching that
     * DAO method's own "a done item's stale alarm still needs to be recognized and skipped"
     * reasoning - filtered by the caller, not here. */
    suspend fun allWithTimeTrigger(context: Context): List<ListItem> =
        allNotesItems(context).filter { it.startsAt != null }

    // ----------------------------------------------------------------------- awareness helper

    suspend fun openItemCount(context: Context): Int = allNotesItems(context).count { !it.done }

    // ------------------------------------------------------------------- appointments (one-today ticket 02/08)

    /**
     * One-today ticket 02, "ticking an appointment": every function in this section is a
     * DELIBERATELY separate, narrower funnel from the reminder-only one above - never through
     * [applyChange], never through [allNotesItems]'s `kind = 'reminder'` filter. **Why separate,
     * not extended:** ticket 11's `kind` discriminator exists so [AlarmScheduler]'s sweep never
     * mistakes a calendar-table row for something it owns (the 2026-08-26 incident, 51 rows falsely
     * marked missed) - ticket 02 separates the QUESTION this file conflated ("whose alarm is this"
     * vs "can this be ticked") rather than widening the reminder funnel and hoping nothing in it
     * ever reaches [AlarmScheduler]/[scheduleAlarmFor] by accident. None of the functions below
     * calls either.
     *
     * **NARROWED 2026-09-01 (one-today ticket 08, "events are not todos"): [tickAppointment]/
     * [untickAppointment] now refuse [EventKind.EVENT] outright, not just [EventKind.REMINDER].**
     * Ticket 02's own premise - "you cannot cross off a calendar item, and the field is not what is
     * missing" - was right that the field was not missing and wrong about the fix: half the rows it
     * made tickable were never appointments at all, and Kevin's own ruling is that an event "just
     * passes whether or not I do it, like classes". [tickAppointment]/[untickAppointment] now only
     * ever succeed for [EventKind.TASK] (nothing writes one yet - Canvas is its own ticket); every
     * OTHER function in this section ([appointmentById]/[openAppointments]/[findAppointment]/
     * [updateAppointment]/[removeAppointment]) still covers both [EventKind.EVENT] and
     * [EventKind.TASK] - renaming a calendar entry or deleting it outright is still a legitimate
     * edit even though ticking one off is not.
     *
     * **Local-only, by design, not merely by omission** (ticket 02 point 3) - **for [tickAppointment]/
     * [untickAppointment] only.** A calendar-table row has no live server write funnel of its own
     * even on the "configured" (Supabase) path - the retired `calendar/CalendarImportController.kt`'s
     * own class doc established this ("unlike [NotesController], there is no configured-vs-
     * unconfigured branch here at all... a Google-imported event is already synced cross-device by
     * Google itself"), and a voice-created one ([com.kevin.legion.service.LiveToolbox]'s
     * `addAppointment`) follows the identical shape. So a tick here writes straight to the local
     * [Event] row, on every install, matching that precedent rather than inventing a live-sync path
     * ticket 02 never asked for. **The accepted cost, stated rather than hidden:** a task ticked on
     * one phone does not appear ticked on a second phone until the next pull - nothing pushes a tick
     * today, a follow-up rather than a silent gap.
     *
     * **REVERSED 2026-09-02 (live-sync ticket 04 follow-up, Kevin) for [updateAppointment]/
     * [removeAppointment] only - the paragraph above no longer describes them.** It was written when
     * nothing synced at all, so a local-only edit lost nothing; it became a real hole the moment
     * [com.kevin.legion.backend.EventsAppointmentWriter.addEvent] started syncing CREATION, because
     * the two devices then silently diverge on exactly the edit a user is most likely to make next.
     * Both now route through [com.kevin.legion.backend.EventsAppointmentWriter.updateEvent]/
     * [com.kevin.legion.backend.EventsAppointmentWriter.deleteEvent] - write-through plus the same
     * durable outbox [addEvent] already uses, soft-deleting rather than hard-deleting so a pull that
     * propagates tombstones (live-sync ticket 03) never resurrects a locally-hard-deleted row. See
     * `memory/library/decisions.md`'s 2026-09-02 entry for the full reversal record.
     *
     * **Recurring calendar-table rows needed no new handling** (ticket 02 point 2): a row here never
     * carries [Event.repeatKind] - every stored one (Google-imported historically, or voice-created
     * today) is already a single expanded occurrence, matching
     * [com.kevin.legion.ui.notes.AppointmentEvent]'s own doc comment - so `events_recurring_not_done`'s
     * `repeat_kind is null or done = false` constraint can never fire for one. A LEGION recurring
     * REMINDER is unaffected: [tick] above already refuses it (`item.repeatKind != null`),
     * unchanged by this section.
     */
    suspend fun appointmentById(context: Context, id: Long): ListItem? {
        val listId = theList(context).id
        val row = db(context).eventDao().getById(id) ?: return null
        if (row.deleted || !isCalendarTableKind(row.kind)) return null
        return row.toListItem(listId)
    }

    /** Every active (undone) [EventKind.TASK] row, shaped for [matchItem] -
     * `service/LiveToolbox.kt`'s `manage_item` tick/untick fallback reads this ONLY after
     * [findItem] (reminders) comes back [ItemMatch.NoMatch], so a title that matches a reminder is
     * never shadowed by a calendar-table row of the same name. Ticket 02 point 4: "the voice tools
     * need it both ways". **Deliberately [EventKind.TASK] only, not every calendar-table row**
     * (narrowed by one-today ticket 08): an [EventKind.EVENT] must never surface here at all - if it
     * did, the voice tick/untick fallback below would find a title match for a class and try to mark
     * it done, exactly the bug this ticket exists to close. */
    suspend fun openAppointments(context: Context): List<ListItem> {
        val listId = theList(context).id
        return db(context).eventDao().getActiveByKind(EventKind.TASK)
            .filter { !it.done }
            .map { it.toListItem(listId) }
    }

    /** Fuzzy-matches [query] against open tasks only (see [openAppointments]'s own doc comment for
     * why an event is excluded), same shape as [findItem]. */
    suspend fun findAppointment(context: Context, query: String): ItemMatch =
        matchItem(query, openAppointments(context))

    /** Ticks a task "done" - ticket 02 point 1's original framing was "I attended", not "I
     * completed a task"; one-today ticket 08 narrowed this to [EventKind.TASK] specifically, since
     * an [EventKind.EVENT] genuinely has no "I attended" to record (Kevin: "i dont mark an event
     * done, it just passes"). Same [Event.done]/[Event.doneAt] columns a reminder tick uses, worded
     * differently at the UI/voice layer only. Returns false, writing nothing, on a stale id, a row
     * that is not a task, or a genuine write failure - same "no false success" contract [tick]
     * holds for a reminder. Never touches [AlarmScheduler] - see this section's own class doc for
     * why a row here never armed one to begin with. */
    suspend fun tickAppointment(context: Context, item: Event): Boolean {
        val now = System.currentTimeMillis()
        val existing = db(context).eventDao().getById(item.id) ?: return false
        if (existing.deleted || existing.kind != EventKind.TASK) return false
        return try {
            db(context).eventDao().update(existing.copy(done = true, doneAt = now, updatedAtMs = now))
            true
        } catch (e: Exception) {
            Log.w(TAG, "tickAppointment failed for ${item.id}: ${e.message}")
            false
        }
    }

    /** The undo of [tickAppointment] - same failure contract, same [EventKind.TASK]-only guard. */
    suspend fun untickAppointment(context: Context, item: Event): Boolean {
        val now = System.currentTimeMillis()
        val existing = db(context).eventDao().getById(item.id) ?: return false
        if (existing.deleted || existing.kind != EventKind.TASK) return false
        return try {
            db(context).eventDao().update(existing.copy(done = false, doneAt = null, updatedAtMs = now))
            true
        } catch (e: Exception) {
            Log.w(TAG, "untickAppointment failed for ${item.id}: ${e.message}")
            false
        }
    }

    /** `ui/notes/InboxScreen.kt`'s calendar-row edit dialog save - one-today ticket 01's local
     * replacement for the retired `CalendarProvider.updateEventSeries`/`updateEventOccurrence` pair.
     * Title/start/end/all-day only, matching that dialog's own "title and time" surface (ticket 22
     * point 1) - everything else about the row is preserved via `copy`. Returns false, writing
     * nothing, on a stale id or a row that is a reminder (renaming/rescheduling stays legitimate for
     * both [EventKind.EVENT] and [EventKind.TASK] even though ticking one off is not - see this
     * section's own class doc). **Routed through
     * [com.kevin.legion.backend.EventsAppointmentWriter.updateEvent] since the 2026-09-02 reversal**
     * (this section's own class doc) - local write always happens; a configured install also pushes
     * the rename, enqueuing on failure rather than losing it. */
    suspend fun updateAppointment(
        context: Context,
        id: Long,
        title: String,
        startMs: Long,
        endMs: Long,
        allDay: Boolean,
    ): Boolean {
        val existing = db(context).eventDao().getById(id) ?: return false
        if (existing.deleted || !isCalendarTableKind(existing.kind)) return false
        return try {
            EventsAppointmentWriter.updateEvent(
                context = context,
                existing = existing,
                title = title,
                startsAtMs = startMs,
                endsAtMs = endMs,
                allDay = allDay,
            )
        } catch (e: Exception) {
            Log.w(TAG, "updateAppointment failed for $id: ${e.message}")
            false
        }
    }

    /** Whether [kind] is a calendar-table row (event or task) rather than a reminder - the guard
     * shape shared by [appointmentById]/[updateAppointment]/[removeAppointment], which edit or
     * delete a row regardless of whether it happens to be completable ([openAppointments]/
     * [tickAppointment]/[untickAppointment] are narrower on purpose - see this section's own class
     * doc). */
    private fun isCalendarTableKind(kind: String): Boolean = kind == EventKind.EVENT || kind == EventKind.TASK

    /** `ui/notes/InboxScreen.kt`'s appointment DELETE - one-today ticket 01's local replacement for
     * the retired `CalendarProvider.deleteEventSeries`/`deleteEventOccurrence` pair.
     * **CORRECTED 2026-09-02 (the reversal this section's own class doc records): no longer
     * hard-deletes on a configured install.** [removeItem]'s "no 30-day restorable trash" convention
     * this comment used to cite was about the UNCONFIGURED path having nothing to reconcile against
     * - true when this ruling was made, no longer true once a pull propagates tombstones (ticket
     * 03): a hard local delete there would simply resurrect the row on the next pull. Routed through
     * [com.kevin.legion.backend.EventsAppointmentWriter.deleteEvent], which still hard-deletes on an
     * unconfigured install (nothing ever to reconcile there) and soft-deletes-plus-pushes on a
     * configured one. Returns false, deleting nothing, when the id is already gone or is not an
     * appointment. */
    suspend fun removeAppointment(context: Context, id: Long): Boolean {
        val existing = db(context).eventDao().getById(id) ?: return false
        if (!isCalendarTableKind(existing.kind)) return false
        return try {
            EventsAppointmentWriter.deleteEvent(context, existing)
        } catch (e: Exception) {
            Log.w(TAG, "removeAppointment failed for $id: ${e.message}")
            false
        }
    }
}
