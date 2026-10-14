package com.kevin.midnightai.media

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One tile on the Cruise playlist row. Only strings - the cover bitmap is decoded at render time. */
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    /** `spotify:playlist:<id>` - what App Remote's `playerApi.play` takes. */
    val uri: String,
    val coverUrl: String?,
    /** Track count from the list response, or null when Spotify did not say (older cache, odd payload). */
    val trackCount: Int? = null,
)

/**
 * What the Cruise grid renders. [coverStamps] exists because covers land on disk AFTER the
 * list does and the UI decodes from the file path: a playlist's stamp changing is the signal to
 * re-decode THAT tile's cover (and only that one), so no Bitmap ever has to ride a StateFlow.
 */
data class PlaylistsState(
    /** What the Cruise grid shows: the pins in pin order, else the first ten of [library]. */
    val playlists: List<SpotifyPlaylist> = emptyList(),
    /** The driver's whole fetched library (capped), for the Settings picker. */
    val library: List<SpotifyPlaylist> = emptyList(),
    /** Authorized, but the stored token predates (or was denied) the playlist scopes. */
    val needsReconnect: Boolean = false,
    /** Playlist id -> when its cover file last landed on disk. Missing = no new download this run. */
    val coverStamps: Map<String, Long> = emptyMap(),
    /**
     * Whether a Spotify Web grant exists: true / false once checked, null before the first
     * check. Cruise shows its "Connect Spotify" card only on a definite false, so it never
     * flashes on start-up.
     */
    val authorized: Boolean? = null,
    /**
     * Why the last refresh did NOT produce a list, as a sentence (null = the last attempt
     * succeeded, or none has run). The Cruise row stays silent by design; Settings reads this so
     * an empty row is explainable rather than a mystery.
     */
    val lastError: String? = null,
)

/** Result of one `/me/playlists` call, classified by what the CALLER should do next. */
sealed class PlaylistFetch {
    data class Ok(val playlists: List<SpotifyPlaylist>, val hasNext: Boolean = false) : PlaylistFetch()
    /** 401/403: the token is valid enough to reach Spotify but cannot read playlists. Re-auth fixes it. */
    object NeedsReconnect : PlaylistFetch()
    /** Offline, 429, 5xx, unparseable. Keep whatever list we have; try again later. [reason] says which. */
    data class Failed(val reason: String) : PlaylistFetch()
}

/**
 * Pure pieces of the playlist feature - no Android types, so they run in plain JVM unit tests.
 */
object SpotifyPlaylistLogic {
    /** Grid capacity: Cruise's pinned grid is 2 x 5. Matches [SpotifyPinLogic.MAX_PINS]. */
    const val MAX_PLAYLISTS = 10

    /** Spotify's page size for /me/playlists (its documented maximum). */
    const val PAGE_SIZE = 50

    /** Whole-library cap for the picker: a driver with 400 playlists pins from the first 100. */
    const val MAX_LIBRARY = 100

    /** Smallest cover edge (px) worth using: tiles render ~72dp, so 200px is sharp without hauling 640px art. */
    const val MIN_COVER_PX = 200

    /**
     * Picks a cover URL from Spotify's `images` array: the smallest image whose width is at
     * least [MIN_COVER_PX]; if none qualifies, the largest available; if no sizes are given at
     * all (Spotify returns null width/height for user-uploaded or auto-mosaic covers), the first
     * entry. Null for an empty/missing array (brand-new playlists have none).
     */
    fun pickCoverUrl(images: JSONArray?): String? {
        if (images == null) return null
        val candidates = (0 until images.length())
            .mapNotNull { images.optJSONObject(it) }
            .filter { it.optString("url").isNotBlank() }
        if (candidates.isEmpty()) return null
        // optInt on a JSON null yields the default; 0 here means "size not given".
        fun width(o: JSONObject) = o.optInt("width", 0)
        val sized = candidates.filter { width(it) > 0 }
        if (sized.isEmpty()) return candidates.first().optString("url")
        val big = sized.filter { width(it) >= MIN_COVER_PX }
        val pick = if (big.isNotEmpty()) big.minByOrNull { width(it) } else sized.maxByOrNull { width(it) }
        return pick?.optString("url")
    }

    /**
     * Parses a `/v1/me/playlists` body. Tolerates the documented quirks: `items` can contain
     * null entries, `images` can be null, and a playlist can lack a name. Entries without an id
     * are dropped (nothing to play).
     */
    fun parsePlaylists(body: String, max: Int = PAGE_SIZE): List<SpotifyPlaylist> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<SpotifyPlaylist>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out += SpotifyPlaylist(
                id = id,
                name = o.optString("name").ifBlank { "Playlist" },
                uri = o.optString("uri").ifBlank { "spotify:playlist:$id" },
                coverUrl = pickCoverUrl(o.optJSONArray("images")),
                trackCount = parseTrackCount(o),
            )
            if (out.size >= max) break
        }
        return out
    }

    /**
     * A playlist's track count from its `tracks: {total}` object. Also accepts `items: {total}`,
     * the name Spotify's newer playlist payloads use for the same reference object. Null when
     * neither carries a non-negative total, so the tile shows no count rather than a wrong "0".
     */
    fun parseTrackCount(playlist: JSONObject): Int? {
        val ref = playlist.optJSONObject("tracks") ?: playlist.optJSONObject("items") ?: return null
        if (!ref.has("total") || ref.isNull("total")) return null
        return ref.optInt("total", -1).takeIf { it >= 0 }
    }

    /** True if the page body says another page follows (`next` is a non-null URL). */
    fun hasNext(body: String): Boolean = runCatching {
        val o = JSONObject(body)
        !o.isNull("next") && o.optString("next").isNotBlank()
    }.getOrDefault(false)

    /**
     * Maps an HTTP status to the follow-up. 401/403 both mean "this token cannot do this":
     * Spotify answers a missing scope with 403 and a dead token with 401, and from the row's
     * point of view the cure is identical - re-run the consent flow.
     */
    fun classifyStatus(code: Int): Boolean? = when (code) {
        in 200..299 -> true          // ok
        401, 403 -> false            // needs reconnect
        else -> null                 // transient
    }

    /**
     * True when the granted scope string lacks ANY scope the app needs ([SpotifyScopes.REQUIRED]),
     * not just the playlist one: the voice music tools and the playlist row share one reconnect
     * prompt, so an old token trips it once and one approval covers everything. A blank string is
     * a token stored before this app recorded scopes - and before it ASKED for any: search needed
     * none - so it certainly lacks them. Treating blank as "unknown, just call" was wrong: a
     * scopeless token most likely gets a 200 with public playlists only, so the row would quietly
     * show a partial list and never offer the reconnect.
     */
    /** Why a SUCCESSFUL fetch still left the row empty, or null when there is a list. */
    fun emptyListNote(playlists: List<SpotifyPlaylist>): String? =
        if (playlists.isEmpty()) "Spotify returned no playlists for this account." else null

    fun scopeMissing(granted: String): Boolean = SpotifyScopes.anyMissing(granted)

    /**
     * True when the grant cannot read playlists AT ALL (no `playlist-read-private`). Narrower than
     * [scopeMissing]: a token missing only the newer library/queue scopes can still list playlists,
     * so the Cruise row keeps refreshing for it while the reconnect prompt asks for the rest.
     */
    fun playlistScopeMissing(granted: String): Boolean =
        granted.split(' ', ',').none { it == "playlist-read-private" }

    // --- Name matching, for "play my Roadtrip playlist" (ported from LEGION's SpotifyWebApi) --------

    /**
     * Case/whitespace/punctuation-insensitive form shared by [matchScore]: a driver's spoken name
     * and Spotify's stored title routinely differ only in casing or a possessive apostrophe
     * ("kev's roadtrip" vs "Kev's Roadtrip"), and neither is a real mismatch.
     */
    fun normalizeName(s: String): String =
        s.lowercase().trim().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    /** Classic edit-distance DP, iterative (no recursion-depth risk on a long playlist name). */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }

    /**
     * 0.0 (nothing alike) to 1.0 (identical after normalisation). Three tiers, most confident
     * first: exact normalised match, then containment either way (a driver saying just "roadtrip"
     * for "Summer Roadtrip 2024"), then a normalised edit-distance ratio for typos and mis-heard
     * words.
     */
    fun matchScore(spoken: String, candidate: String): Double {
        val ns = normalizeName(spoken)
        val nc = normalizeName(candidate)
        if (ns.isEmpty() || nc.isEmpty()) return 0.0
        if (ns == nc) return 1.0
        if (containmentCredit(ns, nc)) return 0.9
        return 1.0 - levenshtein(ns, nc).toDouble() / maxOf(ns.length, nc.length)
    }

    /**
     * Whether one normalised name sitting inside the other is strong enough to count as a match.
     * Bare containment let "some city pop" pick a library playlist named "Pop"; the contained side
     * must be at least 2 tokens or at least 60% of the longer side's characters, so a one-word
     * generic name never swallows a longer request and the call falls through to public search.
     */
    private fun containmentCredit(a: String, b: String): Boolean {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (!long.contains(short)) return false
        val tokens = short.split(' ').size
        return tokens >= 2 || short.length * 10 >= long.length * 6
    }

    /**
     * What [bestMatch] needs before it trusts a fuzzy hit over falling through to public search.
     * High enough that two different short names ("Gym" vs "Chill") do not collide, low enough
     * that a couple of typos or a dropped word still lands.
     */
    const val MATCH_THRESHOLD = 0.6

    /**
     * The best-scoring playlist for [spoken], or null when nothing clears [MATCH_THRESHOLD] - null
     * means "fall through to search", NOT "the driver has no playlists".
     */
    fun bestMatch(playlists: List<SpotifyPlaylist>, spoken: String): SpotifyPlaylist? {
        if (playlists.isEmpty() || spoken.isBlank()) return null
        val best = playlists.maxByOrNull { matchScore(spoken, it.name) } ?: return null
        return if (matchScore(spoken, best.name) >= MATCH_THRESHOLD) best else null
    }

    /** Cache <-> JSON, so the row can show instantly on a cold, offline start. */
    fun toJson(list: List<SpotifyPlaylist>): String = JSONArray().also { arr ->
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri)
                    .put("coverUrl", it.coverUrl ?: JSONObject.NULL)
                    .put("trackCount", it.trackCount ?: JSONObject.NULL),
            )
        }
    }.toString()

    fun fromJson(json: String): List<SpotifyPlaylist> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull {
            val id = it.optString("id")
            if (id.isBlank()) null
            else SpotifyPlaylist(
                id, it.optString("name"), it.optString("uri"),
                if (it.isNull("coverUrl")) null else it.optString("coverUrl").ifBlank { null },
                if (!it.has("trackCount") || it.isNull("trackCount")) null else it.optInt("trackCount"),
            )
        }
    }.getOrDefault(emptyList())

    /** Whole-number power-of-two sample size so the decoded cover is >= [targetPx] on its short edge. */
    fun sampleSizeFor(srcPx: Int, targetPx: Int): Int {
        if (srcPx <= 0 || targetPx <= 0) return 1
        var s = 1
        while (srcPx / (s * 2) >= targetPx) s *= 2
        return s
    }
}

/**
 * Loads the driver's top playlists for the Cruise row and keeps the last good list.
 *
 * Offline/blip contract (CLAUDE.md sec 9): a failed refresh NEVER clears the list - the
 * cached one stays on screen. Only a definite 401/403 flips [PlaylistsState.needsReconnect].
 * Refresh is rate-limited to [REFRESH_INTERVAL_MS] so re-entering Cruise on every foreground
 * return does not hammer the API.
 */
object SpotifyPlaylists {
    private const val TAG = "SpotifyPlaylists"
    private const val API_BASE = "https://api.spotify.com"
    private const val CACHE_FILE = "spotify_playlists.json"
    private const val COVER_DIR = "spotify_covers"
    const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    private const val NO_TOKEN_REASON =
        "No Spotify access token: offline, or Spotify rejected the saved sign-in. No list was loaded."

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val _state = MutableStateFlow(PlaylistsState())
    val state: StateFlow<PlaylistsState> = _state.asStateFlow()

    @Volatile private var lastAttemptMs = 0L
    @Volatile private var loadedDisk = false

    fun coverFile(context: Context, id: String): File =
        File(File(context.applicationContext.cacheDir, COVER_DIR), "$id.jpg")

    /** One page of `GET /v1/me/playlists`. [baseUrl] is a parameter only so tests can aim it at a MockWebServer. */
    fun fetch(client: OkHttpClient, baseUrl: String, token: String, offset: Int = 0): PlaylistFetch = try {
        val req = Request.Builder()
            .url("$baseUrl/v1/me/playlists?limit=${SpotifyPlaylistLogic.PAGE_SIZE}&offset=$offset")
            .addHeader("Authorization", "Bearer $token")
            .get().build()
        client.newCall(req).execute().use { resp ->
            when (SpotifyPlaylistLogic.classifyStatus(resp.code)) {
                true -> {
                    val body = resp.body?.string().orEmpty()
                    PlaylistFetch.Ok(SpotifyPlaylistLogic.parsePlaylists(body), SpotifyPlaylistLogic.hasNext(body))
                }
                false -> PlaylistFetch.NeedsReconnect
                null -> PlaylistFetch.Failed(
                    "Spotify answered the playlist request with HTTP ${resp.code} (busy or erroring); no list was loaded.",
                ).also { runCatching { Log.w(TAG, it.reason) } }
            }
        }
    } catch (e: Exception) {
        // runCatching: android.util.Log is unmocked on the plain-JVM test classpath.
        runCatching { Log.w(TAG, "playlists fetch failed: ${e.message}") }
        PlaylistFetch.Failed("Could not reach Spotify (${e.message ?: "no detail"}); no list was loaded.")
    }

    /**
     * Pages through the library up to [SpotifyPlaylistLogic.MAX_LIBRARY]. A failure on page 1
     * is the whole result's failure; a failure on a LATER page keeps the pages already read,
     * since a short library still pins fine and the next refresh completes it.
     */
    fun fetchAll(client: OkHttpClient, baseUrl: String, token: String): PlaylistFetch {
        val all = ArrayList<SpotifyPlaylist>()
        var offset = 0
        while (offset < SpotifyPlaylistLogic.MAX_LIBRARY) {
            when (val page = fetch(client, baseUrl, token, offset)) {
                is PlaylistFetch.Ok -> {
                    all += page.playlists
                    if (!page.hasNext) break
                    offset += SpotifyPlaylistLogic.PAGE_SIZE
                }
                else -> return if (offset == 0) page else PlaylistFetch.Ok(all.take(SpotifyPlaylistLogic.MAX_LIBRARY))
            }
        }
        return PlaylistFetch.Ok(all.take(SpotifyPlaylistLogic.MAX_LIBRARY))
    }

    /**
     * The driver's playlists for a VOICE lookup ("play my Roadtrip playlist"), reusing everything
     * the Cruise row already maintains rather than a second fetcher: the in-memory list the row
     * refreshes, else the on-disk cache, and only when both are empty a live [fetchAll]. A stale
     * list that misses a brand-new playlist is fine - the caller falls through to public search
     * on no match anyway. Does not publish to [state]: a voice turn must not reshuffle the row.
     */
    suspend fun libraryForVoice(context: Context): PlaylistFetch = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        library.takeIf { it.isNotEmpty() }?.let { return@withContext PlaylistFetch.Ok(it) }
        readCache(app).takeIf { it.isNotEmpty() }?.let { return@withContext PlaylistFetch.Ok(it) }
        val token = SpotifyWebApi.accessToken(app) ?: return@withContext PlaylistFetch.Failed(NO_TOKEN_REASON)
        fetchAll(http, API_BASE, token)
    }

    @Volatile private var library: List<SpotifyPlaylist> = emptyList()
    @Volatile private var needsReconnect = false
    @Volatile private var lastError: String? = null
    // Pins survive de-authorization on disk, but the row must not show while Spotify is
    // not authorized (no nag on the driving surface), so publish() gates on this.
    @Volatile private var authorized = false

    private fun publish() {
        val cur = _state.value
        _state.value = PlaylistsState(
            playlists = if (authorized) SpotifyPinLogic.display(SpotifyPins.pinned.value, library) else emptyList(),
            library = library,
            needsReconnect = needsReconnect,
            coverStamps = cur.coverStamps,
            authorized = authorized,
            lastError = lastError,
        )
    }

    /** Call after any pin change: re-derives the row and fetches covers for newly shown tiles. */
    fun onPinsChanged(context: Context) {
        val app = context.applicationContext
        publish()
        scope.launch { downloadCovers(app, _state.value.playlists, onlyMissing = true) }
    }

    /**
     * Call on Cruise entry. Cheap when nothing is due: shows the disk cache the first time,
     * then only touches the network when the last attempt is older than [REFRESH_INTERVAL_MS].
     * Never throws; safe from the main thread (all work hops to IO).
     */
    fun refreshIfStale(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            if (!gate.tryLock()) return@launch
            try {
                authorized = SpotifyWebApi.isAuthorized(app)
                if (!authorized) {
                    // No tiles: Cruise swaps the grid for its one "Connect Spotify" card instead
                    // of ten empty slots. Drop any list from a previous authorization. Pins stay
                    // on disk: re-authorizing should not make the driver re-pick.
                    library = emptyList()
                    needsReconnect = false
                    lastError = null
                    _state.value = PlaylistsState(coverStamps = _state.value.coverStamps, authorized = false)
                    return@launch
                }
                if (!loadedDisk) {
                    loadedDisk = true
                    SpotifyPins.load(app)
                    library = readCache(app)
                    publish()
                }
                val now = System.currentTimeMillis()
                if (!force && now - lastAttemptMs < REFRESH_INTERVAL_MS) return@launch
                lastAttemptMs = now

                // Two different questions. A grant that cannot read playlists at all can't fetch:
                // flag the reconnect and stop. A grant that only lacks the NEWER scopes (queue,
                // library, top, recent) still lists playlists fine, so keep refreshing the row and
                // carry the reconnect flag alongside - otherwise adding scopes would silently
                // freeze every already-connected driver's playlist row.
                val granted = SpotifyWebApi.grantedScope(app)
                val staleScopes = SpotifyPlaylistLogic.scopeMissing(granted)
                if (SpotifyPlaylistLogic.playlistScopeMissing(granted)) {
                    needsReconnect = true
                    lastError = "The saved Spotify grant lacks playlist permission, so playlists were not requested."
                    Log.w(TAG, lastError.orEmpty())
                    publish()
                    return@launch
                }
                val token = SpotifyWebApi.accessToken(app)
                if (token == null) {
                    // Offline, or the refresh token was rejected (accessToken already cleared
                    // it, so isAuthorized flips false next pass). Keep the cached list.
                    lastAttemptMs = 0L
                    lastError = NO_TOKEN_REASON
                    Log.w(TAG, NO_TOKEN_REASON)
                    publish()
                    return@launch
                }
                when (val r = fetchAll(http, API_BASE, token)) {
                    is PlaylistFetch.Ok -> {
                        library = r.playlists
                        needsReconnect = staleScopes
                        // A 200 with zero playlists hides the row just like a failure does; say so
                        // rather than leaving Settings to claim "not fetched yet".
                        lastError = SpotifyPlaylistLogic.emptyListNote(r.playlists)
                        writeCache(app, r.playlists)
                        // Keep pinned tiles' names/covers current, without ever dropping a pin
                        // Spotify no longer returns (see SpotifyPinLogic.refreshed).
                        SpotifyPins.refreshMetadata(app, r.playlists)
                        publish()
                        downloadCovers(app, _state.value.playlists, onlyMissing = false)
                    }
                    PlaylistFetch.NeedsReconnect -> {
                        needsReconnect = true
                        // 401/403 are indistinguishable here, and a 403 also comes from a
                        // dev-mode Spotify app whose owner has not allowlisted this account -
                        // which RE-AUTHORIZE can never fix. Say both so the driver is not
                        // sent round the consent loop forever.
                        lastError = "Spotify refused the playlist request (HTTP 401 or 403). Either the " +
                            "grant lacks playlist permission (use RE-AUTHORIZE), or this Spotify account " +
                            "is not added under User Management in the app's developer dashboard."
                        Log.w(TAG, lastError.orEmpty())
                        publish()
                    }
                    is PlaylistFetch.Failed -> {
                        lastAttemptMs = 0L // retry on next entry, not in 10 min
                        lastError = r.reason
                        Log.w(TAG, "refresh failed: ${r.reason}")
                        publish()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "refresh error: ${e.message}")
                lastError = "Playlist refresh hit an error (${e.message ?: "no detail"}); no list was loaded."
                lastAttemptMs = 0L
                publish() // else Settings never sees lastError
            } finally {
                if (gate.isLocked) gate.unlock()
            }
        }
    }

    private suspend fun downloadCovers(
        app: Context,
        list: List<SpotifyPlaylist>,
        onlyMissing: Boolean,
    ) = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, COVER_DIR).apply { mkdirs() }
        // Drop covers for playlists no longer in the row so the cache cannot grow without bound.
        val keep = list.map { "${it.id}.jpg" }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        for (p in list) {
            val url = p.coverUrl ?: continue
            if (onlyMissing && File(dir, "${p.id}.jpg").exists()) continue
            try {
                val tmp = File(dir, "${p.id}.tmp")
                http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    resp.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                }
                // Overwrite only a file that actually decodes; a truncated download must not
                // replace a good cover.
                if (tmp.exists() && isDecodable(tmp)) {
                    val dest = File(dir, "${p.id}.jpg")
                    dest.delete()
                    tmp.renameTo(dest)
                    val cur = _state.value
                    _state.value = cur.copy(coverStamps = cur.coverStamps + (p.id to System.currentTimeMillis()))
                } else {
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "cover ${p.id} failed: ${e.message}")
            }
        }
    }

    private fun isDecodable(f: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    private fun readCache(app: Context): List<SpotifyPlaylist> =
        runCatching { SpotifyPlaylistLogic.fromJson(File(app.filesDir, CACHE_FILE).readText()) }
            .getOrDefault(emptyList())

    private fun writeCache(app: Context, list: List<SpotifyPlaylist>) {
        runCatching { File(app.filesDir, CACHE_FILE).writeText(SpotifyPlaylistLogic.toJson(list)) }
    }
}
