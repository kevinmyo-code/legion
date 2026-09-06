package com.kevin.legion.backend

/**
 * One `checklists` row as the engine reports it (`server/checklists/models.py`,
 * `checklists/serializers.py`). Field-for-field mirror of
 * [com.kevin.legion.data.local.Checklist]'s own synced columns, with three deliberate differences
 * the server's own module doc comment already states and this one repeats so a reader here need
 * not go looking:
 *
 * - **[serverId] is the row's own uuid `id`.** There is no separate "server id" column server-side
 *   the way Room needs one; Room keeps a local autoincrement `id` AND a `serverId`, the engine has
 *   only the one.
 * - **[syncId] is NULLABLE.** It is the client-minted idempotency key
 *   ([com.kevin.legion.data.local.Checklist.syncId]) a POST is recognised by on retry, and a row
 *   created on the ENGINE (the PWA, a future Canvas import) has none at all. Nothing here may
 *   assume it is present - see [ChecklistsSync]'s own matching order.
 * - **[deleted] is `deleted_at IS NOT NULL`.** The engine stores a tombstone TIMESTAMP where Room
 *   stores a BOOLEAN; this type carries the boolean Room needs, and the instant is not currently
 *   read by anything on the phone.
 *
 * **[createdAtMs] is the ENGINE's `created_at`, and it is not the checklist's real creation
 * instant for any row this phone backfilled.** `ChecklistSerializer` lists `created_at` in
 * `read_only_fields`, so the value the phone holds ([com.kevin.legion.data.local.Checklist.createdAt])
 * cannot be sent; the engine stamps the upload instant instead. That matters because
 * [com.kevin.legion.checklists.ChecklistController]'s "trap 1" gate reads `createdAt` to decide
 * which days a checklist may show history for. [ChecklistsSync] therefore NEVER overwrites an
 * existing local `createdAt` on merge - see its own comment at that line - and the residual gap is
 * the second device, which has no truer value to insert than the one the engine states.
 */
data class RemoteChecklist(
    val serverId: String,
    val syncId: String?,
    val name: String,
    val scheduleKind: String?,
    val scheduleEvery: Int?,
    val scheduleDaysOfWeek: String?,
    val sortOrder: Int,
    val archived: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
)

/** Every writable column on `checklists` except the ones a caller never states
 * (`id`/`created_at`/`updated_at`/`deleted_at` are server facts; `sync_id` is passed separately
 * because it is an identity, not a field). Mirrors [EventFields]'s own split for the same reason. */
data class ChecklistFields(
    val name: String,
    val scheduleKind: String?,
    val scheduleEvery: Int?,
    val scheduleDaysOfWeek: String?,
    val sortOrder: Int,
    val archived: Boolean,
)

/** One `checklist_items` row as the engine reports it. [checklistServerId] is the parent's own
 * uuid (`checklist` on the wire) - never the parent's `sync_id`, which is what
 * [com.kevin.legion.backend.RemoteListItem.listSyncId] uses for its own table; the difference is
 * not a choice this file made, it is what `ChecklistItemSerializer` actually emits. */
data class RemoteChecklistItem(
    val serverId: String,
    val syncId: String?,
    val checklistServerId: String,
    val text: String,
    val sortOrder: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val measureUnit: String?,
    val measureTarget: Double?,
    val measureDirection: String?,
)

data class ChecklistItemFields(
    val text: String,
    val sortOrder: Int,
    val measureUnit: String?,
    val measureTarget: Double?,
    val measureDirection: String?,
)

/**
 * One `checklist_ticks` row as the engine reports it.
 *
 * **[syncId] is very nearly always null on a tick, and that is the server's shape, not a bug.**
 * `TickRequestSerializer` accepts exactly `{day, value?, source?}` - there is no `sync_id` field on
 * the tick endpoint at all, so a client-minted [com.kevin.legion.data.local.ChecklistTick.syncId]
 * never reaches the engine. [ChecklistsSync] therefore matches a tick on `(item, day)`, which is
 * unique on BOTH sides (`checklist_ticks_item_day_uniq` server-side, the `(itemId, day)` unique
 * index in Room) and is the identity that actually exists, rather than on a key one side never
 * receives.
 */
data class RemoteChecklistTick(
    val serverId: String,
    val syncId: String?,
    val itemServerId: String,
    val day: Int,
    val tickedAtMs: Long,
    val updatedAtMs: Long,
    val deleted: Boolean,
    val value: Double?,
    val source: String,
)

/**
 * One `GET /api/changes?aspects=checklists` response - all three tables in one round trip, which
 * is the whole point of that endpoint (`server/api/changes.py`: "the ONE endpoint the phone's
 * cache is meant to live on").
 *
 * [serverTime] is the engine's own `server_time`, captured BEFORE any query ran, and it is what a
 * caller stores as the next `since` - never a watermark derived from the rows that happened to
 * come back. That is the engine's own instruction, in its own words: "a row committed the same
 * instant this request is being served is never silently skipped by a client-computed
 * max(updated_at) that ran a moment too early." Kept as the raw ISO string rather than parsed to
 * millis so nothing is lost to truncation - Django stamps microseconds.
 */
data class ChecklistChanges(
    val serverTime: String,
    val checklists: List<RemoteChecklist>,
    val items: List<RemoteChecklistItem>,
    val ticks: List<RemoteChecklistTick>,
)

/**
 * The checklists seam - narrow, no HTTP type in any signature, every function returns [Result],
 * matching [EventsBackend]/[LastAspectsBackend]'s own shape.
 *
 * **There is exactly one implementation and it is Django's**
 * ([com.kevin.legion.backend.engine.DjangoChecklistsBackend]). Checklists have never had a
 * Supabase home at all - `server/checklists/models.py` calls them "the FIRST tables Django owns
 * end to end, no legacy to honour" - so unlike events, this interface is not a switch between two
 * transports. It exists for the reason CLAUDE.md section 8 permits an interface at all: this is a
 * seam that needs a fake, and `ChecklistsSyncTest`/`ChecklistsBackfillTest` are it.
 *
 * **The parent's SERVER id is a parameter on every item and tick call**, never derived inside an
 * implementation. `checklists/urls.py` nests every item and tick route under `<uuid:checklist_id>`,
 * so a write cannot be addressed without it - making that explicit here means a caller with an
 * unpushed parent finds out at the call site (and pushes the parent first) instead of an
 * implementation silently inventing a URL.
 */
interface ChecklistsBackend {
    /** Everything changed at or after [sinceIso], across all three tables. Null [sinceIso] omits
     * the parameter entirely, which the engine reads as "fetch everything" (`api/sync.parse_since`
     * degrades a missing OR unparsable value to EPOCH, deliberately: "a missing watermark means
     * fetch everything, never fetch nothing"). */
    suspend fun fetchChanges(sinceIso: String?): Result<ChecklistChanges>

    /** `POST /api/checklists/` carrying [syncId], which the engine treats as an idempotency key -
     * a retry of the same create returns the row that already exists rather than a second one. */
    suspend fun upsertChecklist(syncId: String, fields: ChecklistFields): Result<RemoteChecklist>

    /** [syncId] is re-stated on every patch, never omitted. The wire body sends every writable
     * column explicitly (a clear-to-null has to actually clear), so leaving `sync_id` out is not
     * an option - it would go out as a literal null and WIPE the row's idempotency key, which is
     * the one thing a later outbox retry needs to find it by. */
    suspend fun patchChecklist(
        serverId: String,
        syncId: String,
        fields: ChecklistFields,
    ): Result<RemoteChecklist>

    /** Soft-deletes. `Result.success(false)` means no row with that id (a 404) - never "it was
     * already deleted", which the engine's own idempotent 204 does not distinguish. */
    suspend fun deleteChecklist(serverId: String): Result<Boolean>

    suspend fun upsertItem(
        checklistServerId: String,
        syncId: String,
        fields: ChecklistItemFields,
    ): Result<RemoteChecklistItem>

    /** [syncId] is re-stated for the same reason as on [patchChecklist] - see that doc comment. */
    suspend fun patchItem(
        checklistServerId: String,
        itemServerId: String,
        syncId: String,
        fields: ChecklistItemFields,
    ): Result<RemoteChecklistItem>

    suspend fun deleteItem(checklistServerId: String, itemServerId: String): Result<Boolean>

    /**
     * `POST .../items/<item>/tick` with `{day, value, source}`. **A measured item with a null
     * [value] is refused by the engine with the exact sentence
     * [com.kevin.legion.checklists.ChecklistController.tick] uses** (`checklists/serializers.py`
     * copies it verbatim, on purpose) - that refusal arrives here as a `Result.failure` carrying
     * the server's own words, and nothing on this path may soften it into a success.
     */
    suspend fun tick(
        checklistServerId: String,
        itemServerId: String,
        day: Int,
        value: Double?,
        source: String,
    ): Result<RemoteChecklistTick>

    /** `DELETE .../items/<item>/tick/<day>`, the untick. Idempotent server-side: no tick on that
     * day is still a 204. */
    suspend fun untick(checklistServerId: String, itemServerId: String, day: Int): Result<Boolean>
}

/** Thrown (wrapped in [Result.failure]) for a failure this package raised itself rather than one
 * the transport reported - same role [EventsBackendException] plays for the Supabase events path.
 * A transport failure arrives as
 * [com.kevin.legion.backend.engine.EngineHttpException] instead, carrying its own
 * [com.kevin.legion.backend.engine.EngineFailure] branch, because a caller deciding whether to
 * QUEUE a write has to be able to tell "the engine was unreachable" from "the engine said no". */
class ChecklistsBackendException(message: String) : Exception(message)
