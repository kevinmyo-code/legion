package com.kevin.legion.backend.engine

import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The JSON shape every Phase 5 backend in this package encodes and decodes with.
 *
 * `explicitNulls = true` is the load-bearing setting and it is the same one
 * [DjangoEventsBackend]/[DjangoChecklistsBackend] already use, for the same reason their own DTO
 * doc comments spell out: a nullable property left off the wire is a field DRF never sees, and on
 * the `PUT` this file drives that means the value ALREADY STORED survives a caller asking for it
 * to be cleared. Every writable column goes out on every write, nulls included, which is what
 * reproduces the whole-row-replace semantics `BodyweightLogFields` and its twelve siblings promise.
 *
 * `ignoreUnknownKeys = true` because DRF renders `id`, `provenance` and `created_at` on every row
 * and only some of the `Remote*` shapes carry them.
 */
internal val engineSyncedJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

/**
 * `GET <table>/`'s envelope - `{"results": [...], "next": <iso or null>}` (`server/api/sync.py`'s
 * `paginate_since`). [next] is the LAST row's own `updated_at` when a full 500-row page came back,
 * and null when this was the last page.
 *
 * Generic rather than one copy per table: `EnginePage.serializer(rowSerializer)` builds the
 * concrete serializer at each call site, so thirteen tables share one envelope declaration.
 */
@Serializable
internal data class EnginePage<ROW>(
    val results: List<ROW> = emptyList(),
    val next: String? = null,
)

/**
 * One table on the Django engine's generic synced shape (`server/api/synced.py`'s
 * `SyncedModelViewSet`), which is the shape thirteen of its tables share: places, voice notes, the
 * eight body tables and the three memory ones.
 *
 * The four routes, quoting that module's own summary of what each replaces on the phone:
 *
 * - `GET <table>/?since=<iso>` replaces `fetchChanged*Since`: **tombstones included**, ordered by
 *   `updated_at`, 500 rows per page with a `next` cursor.
 * - `GET <table>/?active=1` replaces `fetchActive*`: the same feed narrowed to `deleted_at is null`.
 * - `PUT <table>/<identity>/` replaces `upsert*(originGuid, fields)`: idempotent, 200 whether it
 *   inserted or updated.
 * - `DELETE <table>/<identity>/` replaces `softDelete*(originGuid)`: sets the tombstone, 204,
 *   idempotent. Never a hard delete.
 *
 * **Why this exists rather than thirteen hand-written page loops.** [DjangoEventsBackend] owns its
 * own `fetchPagesSince` because `/api/events` predates this generic shape and answers a
 * hand-written view; every table below it answers one view class, so the loop, the cursor rule and
 * the identity encoding live once here. That is not tidying - a page loop copied thirteen times is
 * thirteen chances to get the "stop when `next` repeats" guard wrong, and the guard is what stops
 * a whole page sharing one `updated_at` from serving itself forever (`api/synced.py`'s `list`
 * documents that limit in its own words).
 *
 * **`/api/changes?aspects=` is deliberately NOT what this uses**, even though all four aspects
 * appear there. `server/api/changes.py` says why in its own header: *"Unlike the per-table routes,
 * this feed is NOT paged: it answers with every changed row for every requested aspect... it is
 * the reason a client syncing a large first pull should use the per-table `?since=` routes, which
 * do page."* `memory_audit` alone is past 360 rows on the live engine today and only grows, so a
 * feed that cannot page is a feed that will one day truncate silently. The per-table routes also
 * take one watermark EACH, which is exactly the shape [com.kevin.legion.backend.BodyBackend]'s
 * eight independent `fetchChanged*Since(sinceMs)` cursors need; the changes feed takes one
 * watermark for a whole aspect.
 *
 * **No `object` singleton** - a plain class taking its collaborators as constructor parameters,
 * per CLAUDE.md section 8's controller rule.
 */
internal class EngineSyncedTable<ROW>(
    private val http: EngineHttp,
    /** The collection root, trailing slash included - `/api/body/meal_logs/`. `api/synced.py`'s
     * `route_prefix` builds `<aspect>/<table>/`, collapsed to `<table>/` where the two would
     * repeat the same word (places, voice notes). A missing trailing slash is a 404, not a
     * redirect. */
    private val path: String,
    private val rowSerializer: KSerializer<ROW>,
    /** The row's server-side primary key, used ONLY to de-duplicate rows that appear on two
     * consecutive pages (see [fetchPages]). Never sent anywhere. */
    private val idOf: (ROW) -> String,
) {

    /** `GET <table>/?since=<iso>` - **tombstones included**, because a soft-deleted row is exactly
     * what a merge's tombstone branch is waiting for. A null [sinceIso] omits the parameter, which
     * `api/sync.parse_since` reads as "fetch everything", never as "fetch nothing". */
    suspend fun fetchChangedSince(sinceIso: String?): List<ROW> = fetchPages(sinceIso, activeOnly = false)

    /** `GET <table>/?active=1` - live rows only. Narrowed SERVER-side (unlike
     * [DjangoEventsBackend.fetchActive], which has to filter tombstones in the client because
     * `/api/events` is the older hand-written view and takes no `active` parameter). */
    suspend fun fetchActive(): List<ROW> = fetchPages(sinceIso = null, activeOnly = true)

    /**
     * Every page, followed to the end.
     *
     * **The loop is bounded three ways and cannot spin**, the same three [DjangoEventsBackend]'s
     * own `fetchPagesSince` documents: `paginate_since` hands back the last row's own `updated_at`
     * and `parse_since` compares with `>=`, so the last row of each page reappears as the first
     * row of the next - rows are therefore collected into a map keyed by server id (a repeat
     * overwrites itself rather than duplicating), the loop stops the moment `next` repeats the
     * cursor it was just given, and [MAX_PAGES] caps it outright.
     */
    private suspend fun fetchPages(sinceIso: String?, activeOnly: Boolean): List<ROW> {
        val collected = LinkedHashMap<String, ROW>()
        var cursor = sinceIso
        var pages = 0
        while (pages < MAX_PAGES) {
            val query = buildMap {
                cursor?.let { put("since", it) }
                if (activeOnly) put("active", "1")
            }
            val page = engineSyncedJson.decodeFromString(
                EnginePage.serializer(rowSerializer),
                http.get(path, query).getOrThrow().body,
            )
            page.results.forEach { collected[idOf(it)] = it }
            val next = page.next
            if (next == null || next == cursor) break
            cursor = next
            pages++
        }
        return collected.values.toList()
    }

    /**
     * `PUT <table>/<identity>/` with [jsonBody]. Idempotent by construction - the identity is in
     * the URL, so a retry cannot make a second row - which is what makes an outbox drain of the
     * same queued entry twice safe.
     *
     * The response is the row AS STORED, which is why the caller decodes it rather than echoing
     * back what it sent: `updated_at` comes from the `touch_updated_at` trigger and both
     * timestamps from Postgres's own clock, and none of the three exists until the write lands.
     */
    suspend fun put(identity: String, jsonBody: String): ROW =
        decodeRow(http.put(detailPath(identity), jsonBody).getOrThrow().body)

    /** `POST <table>/` - routed only where the identity IS the server's own primary key (voice
     * notes), because a `PUT` to an id the server never minted cannot sensibly insert. Answers
     * 201 with the row it made. */
    suspend fun post(jsonBody: String): ROW = decodeRow(http.post(path, jsonBody).getOrThrow().body)

    /** `DELETE <table>/<identity>/`, raw. The caller wraps this in [deletingEngineRow] so a 404
     * becomes `Result.success(false)` ("there was no such row") rather than a failure. */
    suspend fun deleteRow(identity: String): Result<EngineOk> = http.delete(detailPath(identity))

    private fun decodeRow(body: String): ROW = engineSyncedJson.decodeFromString(rowSerializer, body)

    /**
     * `<path><identity>/`, with [identity] percent-encoded as a PATH segment.
     *
     * **The encoding is not decoration: `places` is keyed by a label a person spoke, and the live
     * engine holds one called `fancy walmart`.** A raw space in a request line is not a legal URL
     * and `java.net.URLEncoder` is the wrong tool here - it renders a space as `+`, which is the
     * `application/x-www-form-urlencoded` convention for QUERY strings and decodes to a literal
     * `+` inside a path segment, so it would address a place nobody ever tagged. Ktor's
     * [encodeURLPathPart] uses `%20` and leaves an `origin_guid` UUID untouched, so one call is
     * right for all thirteen tables. This is the path-segment sibling of the `+`-in-a-query
     * footgun [EngineHttp.get]'s own doc comment traces.
     */
    private fun detailPath(identity: String): String = "$path${identity.encodeURLPathPart()}/"

    private companion object {
        /** 200 pages x 500 rows = 100k rows in one table, two orders of magnitude past the 364
         * `memory_audit` holds on the live engine today - a ceiling so a server-side pagination
         * bug can never hang a foreground pull, not a number with meaning of its own. Same value
         * and same reasoning as [DjangoEventsBackend]'s own cap. */
        const val MAX_PAGES = 200
    }
}
