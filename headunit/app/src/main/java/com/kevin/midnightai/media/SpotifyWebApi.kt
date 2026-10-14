package com.kevin.midnightai.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.kevin.midnightai.ai.CompanionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** One saved (liked) album. [artist] is the first credited artist only - good for a spoken list, not a claim an album has one artist. */
data class SavedAlbum(val name: String, val artist: String, val uri: String)

/**
 * One row of Spotify's OWN recently-played history - account-wide (everywhere the driver plays),
 * not this app's `music_plays` ledger. [playedAt] is Spotify's ISO-8601 UTC string, passed through.
 */
data class RecentlyPlayedTrack(val name: String, val artist: String, val playedAt: String)

/** One entry from Spotify's own top-artists ranking. */
data class TopArtist(val name: String)

/** One entry from Spotify's own top-tracks ranking. */
data class TopTrack(val name: String, val artist: String)

/**
 * One track in Spotify's up-next queue (`GET /v1/me/player/queue`). A Web API read: App Remote's
 * PlayerApi has no queue-read, only `queue(uri)` to add.
 */
data class QueuedTrack(val name: String, val artist: String)

/** One album/single from `GET /artists/{id}/albums`. No artist field: the request is already scoped to one. */
data class ArtistAlbum(val name: String, val uri: String)

/**
 * Spotify Web API access, using the **PKCE** authorization-code flow.
 *
 * Why this exists at all: [SpotifyController]'s App Remote SDK can only transport
 * (resume/pause/skip) and play a URI it is *given* - it has no free-text search. So
 * "play Midnight City" could not be answered in-app, and `play_music` fell back to
 * an OS search intent that foregrounds the Spotify app over Cruise. This closes
 * that gap: search here for a URI, then hand it to [SpotifyController.playUri], and
 * the driver never leaves the launcher.
 *
 * **PKCE, not client-credentials**: the BYO shape (CLAUDE.md sec 8's 2026-07-21
 * reopen) gives us the driver's own client ID and deliberately no client secret, so
 * the secret-bearing client-credentials flow is unavailable. PKCE needs no secret,
 * which is exactly why it fits - the code verifier plays the secret's role for one
 * exchange only.
 *
 * Reuses [SpotifyController.REDIRECT_URI] and the manifest intent-filter that
 * already exists for it, so the driver has nothing extra to whitelist.
 *
 * Policy caveat inherited from the whole Spotify tier: the BYO-own-dev-app pattern's
 * compliance is GRAY and under review (`.scratch/spotify-byo/policy-read.md`); risk
 * accepted by Kevin 2026-07-21.
 */
object SpotifyWebApi {
    private const val TAG = "SpotifyWebApi"
    private const val AUTH_HOST = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val SEARCH_URL = "https://api.spotify.com/v1/search"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Persisted, not just in memory: the flow leaves the app for a browser, and a
    // head unit under memory pressure can kill us while it's foregrounded. An
    // in-memory-only verifier is then gone on return, the exchange fails with no
    // way to tell why, and CONNECT looks broken. Single-use - cleared the moment
    // it's consumed or the flow is abandoned.
    private const val PREFS = "spotify_auth"
    private const val KEY_VERIFIER = "pending_verifier"
    private const val KEY_SCOPE = "granted_scope"

    private fun authPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun storeVerifier(context: Context, verifier: String?) {
        authPrefs(context).edit().apply {
            if (verifier == null) remove(KEY_VERIFIER) else putString(KEY_VERIFIER, verifier)
        }.apply()
    }

    private fun takeVerifier(context: Context): String? {
        val v = authPrefs(context).getString(KEY_VERIFIER, null)
        storeVerifier(context, null)
        return v
    }

    /**
     * Scope string Spotify reported on the last successful token response, or blank if
     * unknown (a token stored before we recorded it). Blank means "ask and see", never
     * "missing" - see [SpotifyPlaylistLogic.scopeMissing].
     */
    fun grantedScope(context: Context): String =
        authPrefs(context).getString(KEY_SCOPE, "").orEmpty()

    /** True once the driver has authorized and a refresh token is on file. */
    fun isAuthorized(context: Context): Boolean =
        CompanionProfile.spotifyRefreshToken(context).isNotBlank()

    /**
     * Authorized, but the stored grant predates (or was denied) part of [SpotifyScopes.REQUIRED].
     * Distinct from "never authorized": the driver already connected once, so the fix is the
     * one-tap reconnect, not first-time setup. Never true when nothing is authorized.
     */
    fun needsReconnect(context: Context): Boolean =
        isAuthorized(context) && SpotifyScopes.anyMissing(grantedScope(context))

    /**
     * Opens the Spotify consent page in a browser. The redirect comes back to
     * [SpotifyController.REDIRECT_URI], which the manifest routes to MainActivity -
     * see [handleRedirect].
     *
     * Returns false if no client ID is saved or no browser can handle the intent
     * (a real possibility on a stripped AOSP head unit, which is why this is
     * checked rather than assumed).
     */
    fun beginAuthorization(context: Context): Boolean {
        val clientId = CompanionProfile.spotifyClientId(context)
        if (clientId.isBlank()) return false

        val verifier = newCodeVerifier()
        storeVerifier(context, verifier)

        val url = Uri.parse(AUTH_HOST).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SpotifyController.REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challengeFor(verifier))
            // The whole scope set in one ask (see [SpotifyScopes]): playlists for the
            // Cruise row, library/top/recent/queue reads for the voice tools. A driver
            // who authorized before holds a token WITHOUT some of these; that is
            // detected via [grantedScope] / a 403 and answered with a "reconnect"
            // tile that re-runs this very flow, once, for all of them. (Spotify's
            // docs say scope is space-delimited.)
            .appendQueryParameter("scope", SpotifyScopes.ALL)
            // Force the consent page even for an already-approved app, so RE-AUTHORIZE in
            // Settings always shows the (possibly new) scopes instead of silently bouncing back
            // with the old grant. Documented by Spotify for the Authorization Code flow; applied
            // here to the PKCE variant on the same endpoint by inference, not verified on-device.
            .appendQueryParameter("show_dialog", "true")
            .build()

        val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "No browser for Spotify auth: ${e.message}")
            storeVerifier(context, null)
            false
        }
    }

    /**
     * Handles the OAuth redirect. Returns true if this URI was ours AND the code
     * exchange succeeded. Safe to call with any incoming URI.
     */
    suspend fun handleRedirect(context: Context, uri: Uri): Boolean {
        if (!uri.toString().startsWith(SpotifyController.REDIRECT_URI)) return false
        val verifier = takeVerifier(context) ?: return false

        uri.getQueryParameter("error")?.let {
            Log.w(TAG, "Spotify auth denied: $it")
            return false
        }
        val code = uri.getQueryParameter("code") ?: return false
        val clientId = CompanionProfile.spotifyClientId(context)
        if (clientId.isBlank()) return false

        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", SpotifyController.REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        return postForTokens(context, form) == TokenResult.SUCCESS
    }

    /**
     * Why a token could not be produced. "Never authorized", "Spotify refused the refresh" and
     * "we are offline" need three different things from the driver, so the voice tools get them
     * apart; [accessToken] below collapses them for callers that only need a yes/no.
     */
    sealed interface TokenOutcome {
        data class Token(val value: String) : TokenOutcome

        /** No refresh token (or no client ID) on file - the browser grant was never completed. */
        object NeverAuthorized : TokenOutcome

        /** Spotify rejected the refresh (revoked, client mismatch). Tokens have been cleared; re-authorize. */
        object Rejected : TokenOutcome

        /** Could not reach Spotify. Tokens kept - the next attempt with signal will work. */
        object Unreachable : TokenOutcome
    }

    /**
     * A usable access token, refreshing if the stored one has expired - with the reason when
     * there is none. A rejected refresh clears the stored tokens so the UI can offer a clean
     * re-connect; a network blip keeps them.
     */
    suspend fun tokenOutcome(context: Context): TokenOutcome {
        val stored = CompanionProfile.spotifyAccessToken(context)
        if (stored.isNotBlank() && System.currentTimeMillis() < CompanionProfile.spotifyTokenExpiry(context)) {
            return TokenOutcome.Token(stored)
        }
        val refresh = CompanionProfile.spotifyRefreshToken(context)
        if (refresh.isBlank()) return TokenOutcome.NeverAuthorized
        val clientId = CompanionProfile.spotifyClientId(context)
        if (clientId.isBlank()) return TokenOutcome.NeverAuthorized

        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId)
            .build()
        return when (postForTokens(context, form)) {
            TokenResult.SUCCESS ->
                CompanionProfile.spotifyAccessToken(context).ifBlank { null }
                    ?.let { TokenOutcome.Token(it) }
                    // Exchange said success but nothing readable landed (KeyVault could not
                    // encrypt): treat as never-authorized so the UI offers a clean re-connect.
                    ?: TokenOutcome.NeverAuthorized
            // Spotify actually rejected the refresh token (revoked, expired past its
            // grace, client mismatch). Only NOW is a full re-authorization the right
            // answer, so clear and let the UI offer CONNECT again.
            TokenResult.REJECTED -> {
                CompanionProfile.clearSpotifyTokens(context)
                TokenOutcome.Rejected
            }
            // Network blip - a dead zone mid-drive, exactly when this is likely.
            // Keep the tokens; the next play attempt with signal refreshes fine.
            // Nuking them here (the old behavior) forced a full browser re-auth for
            // a transient failure, which is the §9 "degrades gracefully offline" rule
            // getting it backwards.
            TokenResult.TRANSIENT -> TokenOutcome.Unreachable
        }
    }

    /**
     * A usable access token, or null for any reason there is none (never authorized, refresh
     * rejected, offline). Callers that must tell those apart use [tokenOutcome].
     */
    suspend fun accessToken(context: Context): String? =
        (tokenOutcome(context) as? TokenOutcome.Token)?.value

    /**
     * Cover/karaoke/tribute markers, matched on the track name and the artist
     * names. Deliberately narrow: these are the phrases the re-record industry
     * puts in metadata BY CONVENTION so their uploads are findable, which is
     * exactly why they are safe to match on. Broader words that show up in real
     * titles ("live", "remix", "version", "cover") are NOT here - a driver
     * asking for a live cut should get one.
     */
    private val IMPOSTER_MARKERS = listOf(
        "karaoke",
        "made famous by",
        "in the style of",
        "originally performed by",
        "tribute to",
        "tribute band",
        "as made popular by",
        "instrumental version of",
        "backing track",
    )

    private fun looksLikeImposter(track: JSONObject): Boolean {
        val name = track.optString("name").lowercase()
        val artists = track.optJSONArray("artists")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") } }
            ?.joinToString(" ")
            ?.lowercase()
            .orEmpty()
        val haystack = "$name $artists"
        return IMPOSTER_MARKERS.any { it in haystack }
    }

    /**
     * (name, subtitle) for whatever [candidate] actually is, per [type]: the artist for a
     * track/album, the owner's display name for a playlist (blank for some editorial ones), and
     * nothing for an artist. Lets `play_music` name what it picked ("Discovery, Daft Punk")
     * instead of echoing the driver's own words back - a loose query and the top hit can differ,
     * and the driver should hear which one started.
     */
    internal fun pickedDisplayName(candidate: JSONObject, type: String): Pair<String, String?> {
        val name = candidate.optString("name").ifBlank { "Unknown" }
        val subtitle = when (type) {
            "track", "album" -> candidate.optJSONArray("artists")
                ?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
            "playlist" -> candidate.optJSONObject("owner")?.optString("display_name")
                ?.takeIf { it.isNotBlank() }
            else -> null
        }
        return name to subtitle
    }

    /**
     * Outcome of a search. Replaces the old nullable URI, whose null meant "no token" OR
     * "offline" OR "Spotify said no" OR "nothing matched" and was spoken to the driver as "I
     * couldn't find that" - misleading for three of the four, and impossible to debug from outside.
     */
    sealed interface SearchOutcome {
        /** [name]/[subtitle] are Spotify's OWN name for whatever [uri] resolved to. */
        data class Found(val uri: String, val name: String, val subtitle: String?) : SearchOutcome

        /** The browser grant was never completed, or the stored credentials are gone. */
        object NeedsAuthorization : SearchOutcome

        /**
         * Spotify rejected the credentials (401/403, or a refused refresh). [detail] is Spotify's
         * own `error.message`, the only thing that tells an expired token from "this dev app does
         * not list your account" - two 403s that need different fixes.
         */
        data class Unauthorized(val detail: String?) : SearchOutcome

        /** Could not reach Spotify at all - offline, DNS, timeout. */
        object Unreachable : SearchOutcome

        /** Spotify answered, and genuinely had nothing for this query. */
        object NoMatch : SearchOutcome

        /**
         * Spotify answered with hits, but none passed [SpotifyMatch.pick] against the request.
         * [closest] are the top hits as "Title by Artist" so the caller can say what it saw and let
         * the driver choose. Nothing was picked: a refusal to guess, never a popularity fallback.
         */
        data class Rejected(val closest: List<String>) : SearchOutcome

        /** An error this code does not map. [raw] is the untouched body, trimmed. */
        data class Failed(val code: Int, val detail: String?, val raw: String? = null) : SearchOutcome
    }

    /**
     * Spotify's own `{"error":{"status":..,"message":".."}}` message, or a trimmed slice of the raw
     * body when it is not that shape. Never the whole body - an HTML error page from an
     * intercepting proxy would otherwise be pasted into a spoken reply.
     */
    private fun errorDetail(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: body.trim().take(200).takeIf { it.isNotEmpty() }

    /**
     * The exact URL a search requests. [limit] null omits the parameter entirely (Spotify's own
     * default page), which is the self-heal path in [fetchCandidates].
     */
    fun searchUrlFor(query: String, type: String = "track", limit: Int? = SEARCH_LIMIT): String =
        Uri.parse(SEARCH_URL).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("type", type)
            .apply { if (limit != null) appendQueryParameter("limit", limit.toString()) }
            .build()
            .toString()

    /**
     * Results asked for per search. **Ten, not the 20 this app used to send and not the documented
     * 50.** LEGION measured on-device (2026-08-12, a dev-mode BYO app like this one) that no limit
     * and `limit=10` return 200 while 11, 12, 14, 15, 16, 18, 20 and 50 all return `400 Invalid
     * limit`. That is a measurement on LEGION's account, NOT re-measured here: this app's old 20
     * may simply never have been exercised against a dev-mode ceiling. [fetchCandidates] retries
     * once without the parameter on a 400 that names the limit, so a ceiling that moves cannot
     * silently break every search.
     */
    private const val SEARCH_LIMIT = 10

    /** One GET with the bearer token, as (status, body) so the caller can branch and retry. */
    private fun getWithToken(url: String, token: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
    }

    /**
     * Spotify's `?type=` values [search] accepts, matching the four kinds `play_music` promises.
     */
    val SEARCHABLE_TYPES = setOf("track", "artist", "album", "playlist")

    /**
     * Finds the best-matching Spotify URI for [query] of the given [type]
     * ("track"/"artist"/"album"/"playlist" - see [SEARCHABLE_TYPES]).
     *
     * Track and album hits must pass [SpotifyMatch.pick] against the query (and [artist], when
     * known) or the result is [SearchOutcome.NoMatch]/[SearchOutcome.Rejected]. The old pick was
     * the most popular of the page with no check against the query at all, which played 2Pac for
     * a Future request and reported success. Spotify's relevance order is kept; popularity only
     * breaks ties between exact title matches.
     *
     * [looksLikeImposter] is a TRACK problem specifically (karaoke/tribute covers match a loose
     * spoken phrase as hard as the real recording does). Artists and playlists take Spotify's own
     * top relevance hit rather than re-deriving a ranking Spotify already computed.
     */
    suspend fun search(
        context: Context,
        query: String,
        type: String = "track",
        artist: String? = null,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val token = when (val outcome = tokenOutcome(context)) {
            is TokenOutcome.Token -> outcome.value
            TokenOutcome.NeverAuthorized -> return@withContext SearchOutcome.NeedsAuthorization
            TokenOutcome.Rejected -> return@withContext SearchOutcome.Unauthorized(
                "Spotify refused the stored refresh token. It has been cleared.",
            )
            TokenOutcome.Unreachable -> return@withContext SearchOutcome.Unreachable
        }
        try {
            val matched = type == "track" || type == "album"
            val knownArtist = artist?.trim()?.takeIf { it.isNotEmpty() && matched }
            // A known artist gets a fielded query first; if that yields nothing ACCEPTABLE, one
            // retry in plain free text (Spotify's fielded search is strict about spelling, so a
            // censored title like "Fukk a Interview" can miss there and still hit free text).
            val attempts = if (knownArtist != null) {
                listOf(
                    SpotifyMatch.fieldedQuery(type, query, knownArtist),
                    "$query $knownArtist",
                )
            } else {
                listOf(query)
            }
            val closest = mutableListOf<String>()
            for (q in attempts) {
                when (val raw = fetchCandidates(q, type, token)) {
                    is RawSearch.Error -> return@withContext raw.outcome
                    is RawSearch.Items -> {
                        val picked = if (matched) {
                            // Explicit lambda: a callable reference and a lambda in one if/else
                            // do not unify to a single function type.
                            val imposter: (JSONObject) -> Boolean =
                                if (type == "track") { c -> looksLikeImposter(c) } else { _ -> false }
                            SpotifyMatch.pick(raw.items, query, knownArtist, isImposter = imposter)
                        } else {
                            raw.items.firstOrNull()
                        }
                        val uri = picked?.optString("uri")?.takeIf { it.isNotBlank() }
                        if (picked != null && uri != null) {
                            val (name, subtitle) = pickedDisplayName(picked, type)
                            return@withContext SearchOutcome.Found(uri, name, subtitle)
                        }
                        if (matched) closest += SpotifyMatch.describe(raw.items)
                    }
                }
            }
            val named = closest.distinct().take(SpotifyMatch.CLOSEST_COUNT)
            if (named.isEmpty()) SearchOutcome.NoMatch else SearchOutcome.Rejected(named)
        } catch (e: Exception) {
            // A thrown IOException here is a transport failure, never a verdict on the query.
            Log.w(TAG, "Search error: ${e.message}")
            SearchOutcome.Unreachable
        }
    }

    /** One HTTP search: either the usable candidates, or the outcome that ends the search. */
    private sealed interface RawSearch {
        data class Items(val items: List<JSONObject>) : RawSearch
        data class Error(val outcome: SearchOutcome) : RawSearch
    }

    private fun fetchCandidates(q: String, type: String, token: String): RawSearch {
        var (code, body) = getWithToken(searchUrlFor(q, type), token)
        // Self-heal a tightened limit ceiling. SEARCH_LIMIT is a measured value, not a
        // documented one (see its doc), so Spotify lowering it again would silently break every
        // search. Retrying once with the parameter dropped falls back to Spotify's own default
        // page size, which by construction cannot be out of range. Narrow on purpose: only a 400
        // that actually names the limit retries, so this cannot mask an unrelated bad request.
        if (code == 400 && errorDetail(body)?.contains("limit", ignoreCase = true) == true) {
            Log.w(TAG, "limit=$SEARCH_LIMIT rejected; retrying without it")
            val retry = getWithToken(searchUrlFor(q, type, limit = null), token)
            code = retry.first
            body = retry.second
        }
        if (code !in 200..299) {
            val detail = errorDetail(body)
            Log.w(TAG, "Search failed $code: $detail")
            return RawSearch.Error(
                when (code) {
                    401, 403 -> SearchOutcome.Unauthorized(detail)
                    else -> SearchOutcome.Failed(code, detail, body.trim().take(300))
                },
            )
        }
        // Results nest under "<type>s" - "tracks", "albums", "artists", "playlists".
        val items = JSONObject(body).optJSONObject("${type}s")?.optJSONArray("items")
            ?: return RawSearch.Items(emptyList())
        // A playlist search can hand back a null slot for a deleted/private playlist Spotify still
        // indexes; optJSONObject returns null for the literal JSON null as well as an absent slot.
        return RawSearch.Items(
            (0 until items.length())
                .mapNotNull { items.optJSONObject(it) }
                .filter { it.optString("uri").isNotBlank() },
        )
    }

    /** Every way [resolvePlaylist] can end. */
    sealed interface PlaylistResolution {
        /** Matched the driver's OWN playlists - the expected, unremarkable case. */
        data class FromLibrary(val playlist: SpotifyPlaylist) : PlaylistResolution

        /**
         * Nothing of the driver's matched, so this came from Spotify's public catalogue and may
         * not be what they meant - it has to be SAID, not silently substituted. [libraryChecked]
         * is false when their own playlists could not be read at all (offline, or the token lacks
         * the playlist scope), so the spoken note never claims "not among your playlists" about a
         * list nobody looked at.
         */
        data class FromSearch(val uri: String, val name: String?, val libraryChecked: Boolean) : PlaylistResolution

        object NeedsAuthorization : PlaylistResolution
        data class Unauthorized(val detail: String?) : PlaylistResolution
        object Unreachable : PlaylistResolution
        object NoMatch : PlaylistResolution
        data class Failed(val code: Int, val detail: String?) : PlaylistResolution
    }

    /**
     * "Play my Roadtrip playlist": the driver's OWN playlists first, matched fuzzily by
     * [SpotifyPlaylistLogic.bestMatch], and only when none clears the threshold does this fall
     * through to the public-catalogue search - which ranks every playlist on Spotify and is
     * exactly what must not be the primary path for a request that says "my". A failure to READ
     * the library also falls through (a broken `/me/playlists` should not break a public playlist
     * asked for by name), flagged via [PlaylistResolution.FromSearch.libraryChecked].
     */
    suspend fun resolvePlaylist(context: Context, spoken: String): PlaylistResolution {
        var libraryChecked = false
        when (val lib = SpotifyPlaylists.libraryForVoice(context)) {
            is PlaylistFetch.Ok -> {
                libraryChecked = true
                SpotifyPlaylistLogic.bestMatch(lib.playlists, spoken)
                    ?.let { return PlaylistResolution.FromLibrary(it) }
            }
            PlaylistFetch.NeedsReconnect, is PlaylistFetch.Failed -> Unit // fall through to search
        }
        return when (val s = search(context, spoken, "playlist")) {
            is SearchOutcome.Found -> PlaylistResolution.FromSearch(s.uri, s.name, libraryChecked)
            SearchOutcome.NeedsAuthorization -> PlaylistResolution.NeedsAuthorization
            is SearchOutcome.Unauthorized -> PlaylistResolution.Unauthorized(s.detail)
            SearchOutcome.Unreachable -> PlaylistResolution.Unreachable
            SearchOutcome.NoMatch, is SearchOutcome.Rejected -> PlaylistResolution.NoMatch
            is SearchOutcome.Failed -> PlaylistResolution.Failed(s.code, s.detail)
        }
    }

    // --- Library reads (`browse_my_music`, `get_music_queue`) ----------------------------------------

    private const val ALBUMS_URL = "https://api.spotify.com/v1/me/albums"
    private const val RECENTLY_PLAYED_URL = "https://api.spotify.com/v1/me/player/recently-played"
    private const val TOP_URL = "https://api.spotify.com/v1/me/top"
    private const val QUEUE_URL = "https://api.spotify.com/v1/me/player/queue"
    private const val ARTISTS_URL = "https://api.spotify.com/v1/artists"

    /**
     * Page size for the `/v1/me/...` reads. **Unlike [SEARCH_LIMIT] this is NOT a measured
     * ceiling** - nobody has run the one-request-per-value probe against these endpoints. Kept at
     * search's measured 10 on the reasoning that a dev-mode app is the likelier cause of search's
     * cap than anything search-specific; that is inference. If any of these 400 on this value,
     * that is new information and this value should be corrected, not patched around.
     */
    private const val LIBRARY_LIMIT = 10

    /** Albums per "what else does this artist have" answer. */
    private const val ARTIST_ALBUMS_LIMIT = 5

    /**
     * Outcome of a Web API read, generic over the payload because the failure shape (no token /
     * rejected / offline / Spotify error) is identical across every read. [Found] with an EMPTY
     * list is a real, distinct answer - "Spotify said you have zero saved albums" must never
     * collapse into the shape of "the request failed".
     */
    sealed interface LibraryOutcome<out T> {
        data class Found<T>(val items: List<T>) : LibraryOutcome<T>

        /** The browser grant was never completed, or the stored credentials are gone. */
        object NeedsAuthorization : LibraryOutcome<Nothing>

        /** Spotify rejected the credentials, OR the grant predates these scopes - see [needsReconnect]. */
        data class Unauthorized(val detail: String?) : LibraryOutcome<Nothing>

        /** Could not reach Spotify at all. */
        object Unreachable : LibraryOutcome<Nothing>

        /** Spotify answered with an error, or a body this code could not parse. */
        data class Failed(val code: Int, val detail: String?, val raw: String? = null) : LibraryOutcome<Nothing>
    }

    /** `items[].album` -> [SavedAlbum]. Pure and Android-free: a plain JVM unit test. */
    internal fun parseSavedAlbums(json: JSONObject): List<SavedAlbum> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val album = items.optJSONObject(i)?.optJSONObject("album") ?: return@mapNotNull null
            val name = album.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = album.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            SavedAlbum(name = name, artist = artist, uri = album.optString("uri"))
        }
    }

    /** `items[].track` + `items[].played_at` -> [RecentlyPlayedTrack]. */
    internal fun parseRecentlyPlayed(json: JSONObject): List<RecentlyPlayedTrack> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val track = item.optJSONObject("track") ?: return@mapNotNull null
            val name = track.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            RecentlyPlayedTrack(name = name, artist = artist, playedAt = item.optString("played_at"))
        }
    }

    /** `items[]` (artist objects) -> [TopArtist]. */
    internal fun parseTopArtists(json: JSONObject): List<TopArtist> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { TopArtist(it) }
        }
    }

    /** `items[]` (track objects) -> [TopTrack]. */
    internal fun parseTopTracks(json: JSONObject): List<TopTrack> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val track = items.optJSONObject(i) ?: return@mapNotNull null
            val name = track.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            TopTrack(name = name, artist = artist)
        }
    }

    /**
     * `{"queue": [...]}` -> [QueuedTrack], most-imminent first. Unlike recently-played's
     * `items[].track` nesting, `queue`'s entries are bare track objects. A queue whose `queue`
     * key is absent parses as empty, which is correct: Spotify omits nothing, it sends `[]`.
     */
    internal fun parseQueue(json: JSONObject): List<QueuedTrack> {
        val items = json.optJSONArray("queue") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val track = items.optJSONObject(i) ?: return@mapNotNull null
            val name = track.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            QueuedTrack(name = name, artist = artist)
        }
    }

    /**
     * `items[]` (album objects) -> [ArtistAlbum], de-duplicated by name (the endpoint commonly
     * repeats one album per market/edition and a short spoken list must not read a title twice)
     * while keeping Spotify's order (newest release first).
     */
    internal fun parseArtistAlbums(json: JSONObject): List<ArtistAlbum> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length())
            .mapNotNull { i ->
                val album = items.optJSONObject(i) ?: return@mapNotNull null
                val name = album.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val uri = album.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ArtistAlbum(name = name, uri = uri)
            }
            .distinctBy { it.name }
    }

    /**
     * The catalogue id out of a `spotify:artist:...` URI - the Web API's `/artists/{id}/...` paths
     * want the bare id, not the URI App Remote hands back. Null on anything not recognisably a
     * three-part Spotify URI rather than guessing a partial value.
     */
    internal fun spotifyIdFromUri(uri: String): String? {
        val parts = uri.split(":")
        if (parts.size != 3 || parts[0] != "spotify" || parts[2].isBlank()) return null
        return parts[2]
    }

    /**
     * Token-gate, GET, parse - the shape every read below shares. [parse] runs only on a 2xx body;
     * a parse failure (Spotify changing its response shape under us) becomes
     * [LibraryOutcome.Failed] rather than a silently empty [LibraryOutcome.Found], so an empty list
     * only ever means "Spotify said zero".
     */
    private suspend fun <T> libraryGet(
        context: Context,
        url: String,
        parse: (JSONObject) -> List<T>,
    ): LibraryOutcome<T> = withContext(Dispatchers.IO) {
        val token = when (val outcome = tokenOutcome(context)) {
            is TokenOutcome.Token -> outcome.value
            TokenOutcome.NeverAuthorized -> return@withContext LibraryOutcome.NeedsAuthorization
            TokenOutcome.Rejected -> return@withContext LibraryOutcome.Unauthorized(
                "Spotify refused the stored refresh token. It has been cleared.",
            )
            TokenOutcome.Unreachable -> return@withContext LibraryOutcome.Unreachable
        }
        try {
            val (code, body) = getWithToken(url, token)
            if (code !in 200..299) {
                val detail = errorDetail(body)
                Log.w(TAG, "Library read failed $code: $detail")
                return@withContext when (code) {
                    // Ambiguous between "token rejected" and "token valid but minted before these
                    // scopes existed"; callers that care check [needsReconnect] separately rather
                    // than this function guessing from an HTTP status.
                    401, 403 -> LibraryOutcome.Unauthorized(detail)
                    else -> LibraryOutcome.Failed(code, detail, body.trim().take(300))
                }
            }
            val items = try {
                parse(JSONObject(body))
            } catch (e: Exception) {
                Log.w(TAG, "Library parse error: ${e.message}")
                return@withContext LibraryOutcome.Failed(code, "Could not read Spotify's response", body.trim().take(300))
            }
            LibraryOutcome.Found(items)
        } catch (e: Exception) {
            Log.w(TAG, "Library read error: ${e.message}")
            LibraryOutcome.Unreachable
        }
    }

    private fun limitUrl(base: String, limit: Int): String =
        Uri.parse(base).buildUpon().appendQueryParameter("limit", limit.toString()).build().toString()

    /** The driver's saved (liked) albums, newest-saved first. Needs `user-library-read`. */
    suspend fun getSavedAlbums(context: Context, limit: Int = LIBRARY_LIMIT): LibraryOutcome<SavedAlbum> =
        libraryGet(context, limitUrl(ALBUMS_URL, limit), ::parseSavedAlbums)

    /**
     * Spotify's own play history, most recent first, across every device the driver plays on -
     * NOT scoped to this app. Needs `user-read-recently-played`.
     */
    suspend fun getRecentlyPlayed(context: Context, limit: Int = LIBRARY_LIMIT): LibraryOutcome<RecentlyPlayedTrack> =
        libraryGet(context, limitUrl(RECENTLY_PLAYED_URL, limit), ::parseRecentlyPlayed)

    /** Spotify's own top-artists ranking for this account. Needs `user-top-read`. */
    suspend fun getTopArtists(context: Context, limit: Int = LIBRARY_LIMIT): LibraryOutcome<TopArtist> =
        libraryGet(context, limitUrl("$TOP_URL/artists", limit), ::parseTopArtists)

    /** Spotify's own top-tracks ranking for this account. Needs `user-top-read`. */
    suspend fun getTopTracks(context: Context, limit: Int = LIBRARY_LIMIT): LibraryOutcome<TopTrack> =
        libraryGet(context, limitUrl("$TOP_URL/tracks", limit), ::parseTopTracks)

    /**
     * "What's coming up": `GET /v1/me/player/queue`, which needs BOTH `user-read-currently-playing`
     * and `user-read-playback-state`. Truncated to [limit] client-side - the endpoint takes no
     * `limit` parameter and can return its entire queue.
     */
    suspend fun getQueue(context: Context, limit: Int = LIBRARY_LIMIT): LibraryOutcome<QueuedTrack> =
        when (val outcome = libraryGet(context, QUEUE_URL, ::parseQueue)) {
            is LibraryOutcome.Found -> LibraryOutcome.Found(outcome.items.take(limit))
            else -> outcome
        }

    /**
     * "What else does he have": the artist's own albums and singles, newest first. [artistUri]
     * comes from App Remote's own player state, never a guess. `include_groups=album,single`
     * excludes "appears_on" and "compilation", which is not what asking for an artist's albums
     * means. A PUBLIC catalogue endpoint (no `user-...` scope), but whether a dev-mode client id
     * is allowed to call it is NOT verified for this app - a 403 comes back as
     * [LibraryOutcome.Unauthorized] and is spoken, never a silent empty list.
     */
    suspend fun getArtistAlbums(
        context: Context,
        artistUri: String,
        limit: Int = ARTIST_ALBUMS_LIMIT,
    ): LibraryOutcome<ArtistAlbum> {
        val artistId = spotifyIdFromUri(artistUri)
            ?: return LibraryOutcome.Failed(0, "Not a recognizable Spotify artist URI: $artistUri")
        val url = Uri.parse("$ARTISTS_URL/$artistId/albums").buildUpon()
            .appendQueryParameter("include_groups", "album,single")
            // Fetched at LIBRARY_LIMIT BEFORE the market-edition de-dupe and the [limit]
            // truncation, so duplicate editions cannot crowd out genuinely different albums.
            .appendQueryParameter("limit", LIBRARY_LIMIT.toString())
            .build().toString()
        return when (val outcome = libraryGet(context, url, ::parseArtistAlbums)) {
            is LibraryOutcome.Found -> LibraryOutcome.Found(outcome.items.take(limit))
            else -> outcome
        }
    }

    /** Outcome of a token exchange - the caller must treat rejection and a network blip differently. */
    private enum class TokenResult { SUCCESS, REJECTED, TRANSIENT }

    /**
     * POSTs a token request and stores the result on success. Distinguishes a real
     * rejection ([REJECTED], a 4xx from Spotify) from a transient failure
     * ([TRANSIENT], no network / 5xx) so [accessToken] only discards credentials
     * when Spotify actually says they're bad.
     */
    private suspend fun postForTokens(context: Context, form: FormBody): TokenResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(TOKEN_URL).post(form).build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Token request failed ${response.code}")
                    // 4xx = the request/credentials were bad (invalid_grant etc.);
                    // 5xx = Spotify's problem, retryable. Treat only client errors as
                    // a real rejection.
                    return@withContext if (response.code in 400..499) TokenResult.REJECTED else TokenResult.TRANSIENT
                }
                val json = JSONObject(body)
                val access = json.optString("access_token")
                if (access.isBlank()) return@withContext TokenResult.REJECTED
                // Spotify echoes the granted scope on code exchange and on refresh.
                json.optString("scope").takeIf { it.isNotBlank() }?.let {
                    authPrefs(context).edit().putString(KEY_SCOPE, it).apply()
                }
                CompanionProfile.saveSpotifyTokens(
                    context,
                    accessToken = access,
                    // Absent on a refresh response - saveSpotifyTokens keeps the old one.
                    refreshToken = json.optString("refresh_token"),
                    expiresInSec = json.optLong("expires_in", 3600L),
                )
                TokenResult.SUCCESS
            }
        } catch (e: Exception) {
            // A thrown IOException is a network failure, never an auth verdict.
            Log.w(TAG, "Token error: ${e.message}")
            TokenResult.TRANSIENT
        }
    }

    /** 64 random bytes, base64url - comfortably inside PKCE's 43..128 char range. */
    private fun newCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, BASE64_URL)
    }

    private fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, BASE64_URL)
    }

    private const val BASE64_URL = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
}
