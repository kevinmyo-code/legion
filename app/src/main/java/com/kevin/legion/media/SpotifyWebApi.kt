package com.kevin.legion.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.kevin.legion.ai.CompanionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

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
/**
 * One saved (liked) album - [SpotifyWebApi.getSavedAlbums]. [artist] is the first credited
 * artist only (Spotify's own `album.artists[0]`) - good enough for a spoken list, not a claim
 * that an album has exactly one artist.
 */
data class SavedAlbum(val name: String, val artist: String, val uri: String)

/**
 * One row of Spotify's OWN recently-played history - [SpotifyWebApi.getRecentlyPlayed]. This is
 * Spotify's account-wide play history (everywhere the driver plays, not just through LEGION) -
 * see `browse_my_music`'s `legion_history` source in `service/LiveToolbox.kt` for the DIFFERENT,
 * LEGION-observed table this is not to be confused with. [playedAt] is Spotify's own ISO-8601
 * UTC timestamp string, passed through unparsed - nothing here needs to do arithmetic on it.
 */
data class RecentlyPlayedTrack(val name: String, val artist: String, val playedAt: String)

/** One entry from Spotify's own top-artists ranking - [SpotifyWebApi.getTopArtists]. */
data class TopArtist(val name: String)

/** One entry from Spotify's own top-tracks ranking - [SpotifyWebApi.getTopTracks]. */
data class TopTrack(val name: String, val artist: String)

/**
 * One track sitting in Spotify's own up-next queue - [SpotifyWebApi.getQueue] (ticket 04,
 * `.scratch/spotify-voice/issues/04-queue.md`). This is a READ of `GET /v1/me/player/queue`, the
 * Web API's own queue, not an App Remote type - App Remote's [com.spotify.android.appremote.api.PlayerApi]
 * has no queue-read method at all, only `queue(uri)` to add one.
 */
data class QueuedTrack(val name: String, val artist: String)

/**
 * One of the driver's own playlists - [SpotifyWebApi.myPlaylists] (ticket 08,
 * `.scratch/spotify-voice/issues/08-playlists-by-name.md`). [id] is the bare playlist ID (the
 * last segment of [uri]), kept separately because the playlist-items write endpoint takes an ID
 * in its path, not a URI.
 *
 * [readable] is the research's hard boundary (`.scratch/spotify-voice/research/01-api-capability-surface.md`):
 * "`GET /playlists/{id}` returns items only for playlists the user owns or collaborates on." A
 * followed EDITORIAL playlist (Discover Weekly) still shows up in `GET /me/playlists` - it is
 * something the driver can PLAY, just not something LEGION can read the contents of or modify.
 * True when the account's own user ID matches the playlist owner, OR the playlist is flagged
 * `collaborative` (a friend added the driver as a collaborator). False for anything else,
 * including a followed editorial playlist and a followed friend's playlist the driver was never
 * made a collaborator on - Spotify's write endpoint would 403 either way, so this is conservative
 * on purpose: a false negative costs one honest "can't do that" sentence, a false positive would
 * cost a confusing 403 the driver has no way to explain to himself.
 */
data class SpotifyPlaylist(val name: String, val uri: String, val id: String, val readable: Boolean)

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
    private const val KEY_GRANTED_SCOPE = "granted_scope"

    /**
     * The scopes the authorize request asks for.
     *
     * **This used to be nothing at all, and that was the bug** (found on-device
     * 2026-08-12: `/v1/search` answered `403 Insufficient client scope`). The
     * previous code omitted the `scope` parameter deliberately, reasoning that
     * search needs no particular scope and that a narrower consent screen is
     * better. The first half is true of a *client-credentials* token; it is not
     * true of a **user** token, which is what the PKCE flow mints. A user token
     * carrying an empty scope set is refused by search outright.
     *
     * `user-read-private` is the narrowest scope that fixes it, and it is needed
     * a second time over: `market=from_token` on the search call is defined in
     * terms of the token's own account country, which Spotify will only disclose
     * under this scope. It grants no access to playlists, listening history,
     * playback control, or anything else - the original instinct to ask for as
     * little as possible is right, this is just the actual floor rather than zero.
     *
     * **Widened 2026-08-18** (`.scratch/drive-test-2026-08-18/issues/05-reading-kevins-spotify-library.md`,
     * Kevin: "can we look up our favorite or recent albums?") to add `user-library-read`
     * (saved albums), `user-read-recently-played`, and `user-top-read` (top artists/tracks) -
     * the reads [browse_my_music][com.kevin.legion.service.LiveToolbox] needs.
     *
     * **Widened again 2026-08-19** (`.scratch/spotify-voice/issues/01-scopes-and-one-reapproval.md`),
     * to every scope the whole spotify-voice map will ever need, taken in this ONE edit rather than
     * once per ticket - see that ticket's settled decision 2 (re-auth happens at a desk, never
     * discovered in the car) and its rule that a later ticket discovering a missing scope is a
     * defect in THIS one. Added: `user-modify-playback-state` (every `/me/player` WRITE - play,
     * pause, skip, seek, shuffle, repeat), `user-read-playback-state` (device/player reads, and
     * required alongside `user-read-currently-playing` for `GET /me/player/queue`),
     * `user-read-currently-playing` (the currently-playing read), `user-library-modify`
     * (like/unlike and follow-as-save-to-library, since `PUT/DELETE /me/following` is deprecated
     * in favour of the unified `/me/library` family), `playlist-read-private` and
     * `playlist-read-collaborative` (reading his own and friend-shared playlists),
     * `playlist-modify-private` and `playlist-modify-public` (add-to-playlist; playlist CREATION
     * by voice is deliberately out of scope per the map), and `app-remote-control` (the App Remote
     * SDK connection itself - see `media/SpotifyController.kt`).
     *
     * **This change is destructive to every existing grant, on purpose.** [isAuthorized]'s scope
     * equality check below compares the GRANTED scope string against this constant verbatim, so
     * changing it - even by strictly adding scopes a prior grant obviously didn't have - makes
     * every driver's stored refresh token read as unauthorized starting the next time this runs.
     * That is the documented, intended behavior of that check (see its own doc comment), not a
     * regression: a refresh token only ever mints access tokens scoped to what it was ISSUED
     * with, so a stale grant silently continuing to "work" while quietly lacking the new scopes
     * is the actual bug this equality check exists to prevent (found the hard way 2026-08-12).
     * The cost is that every driver must re-approve once in the browser after this ships - see
     * [hasStaleGrant] and `ui/SpotifyScreen.kt`'s re-authorization copy, which exists specifically
     * so that re-approval reads as "you're due for a refresh" rather than "connect for the first
     * time" or, worse, a silent `play_music` failure discovered mid-drive.
     */
    const val SCOPES = "user-read-private user-library-read user-read-recently-played user-top-read " +
        "user-modify-playback-state user-read-playback-state user-read-currently-playing " +
        "user-library-modify playlist-read-private playlist-read-collaborative " +
        "playlist-modify-private playlist-modify-public app-remote-control"

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
     * True once the driver has authorized and a refresh token **granted under the
     * current [SCOPES]** is on file.
     *
     * The scope check is not belt-and-braces, it is load-bearing (2026-08-12). A
     * refresh token remembers the scopes it was minted with, so a grant obtained
     * before [SCOPES] existed keeps minting scope-less access tokens indefinitely
     * - every search failing `403 Insufficient client scope`, with the setup
     * screen cheerfully reporting "Set up" because a refresh token was present.
     * Comparing against the recorded grant makes that stale grant read as
     * unauthorized, which is what puts the AUTHORIZE button back in front of the
     * driver. Same shape as [CompanionProfile.saveSpotifyClientId] discarding
     * tokens when the client ID changes, for the same underlying reason: a
     * credential is only valid for the thing it was issued against.
     */
    fun isAuthorized(context: Context): Boolean =
        CompanionProfile.spotifyRefreshToken(context).isNotBlank() &&
            authPrefs(context).getString(KEY_GRANTED_SCOPE, null) == SCOPES

    /**
     * True when a refresh token IS on file but was minted under a DIFFERENT scope string than
     * the current [SCOPES] - i.e. [isAuthorized] is false not because the driver never connected
     * at all, but because [SCOPES] grew out from under a grant that used to be valid. Lets the
     * setup screen say "your Spotify connection needs re-approving" instead of the misleading
     * "not set up" it would otherwise show for a driver who has, in fact, connected before.
     * Never true for a driver who has genuinely never authorized (no refresh token exists yet,
     * so there is nothing to call stale).
     */
    fun hasStaleGrant(context: Context): Boolean {
        if (CompanionProfile.spotifyRefreshToken(context).isBlank()) return false
        val granted = authPrefs(context).getString(KEY_GRANTED_SCOPE, null) ?: return false
        return granted != SCOPES
    }

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
            // See SCOPES: omitting this entirely is what produced "Insufficient
            // client scope" on every search.
            .appendQueryParameter("scope", SCOPES)
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
        val ok = postForTokens(context, form) == TokenResult.SUCCESS
        // Record what this grant was issued under, so a later SCOPES change
        // invalidates it rather than silently producing tokens search will refuse.
        // Written only on success - a failed exchange must not look authorized.
        if (ok) authPrefs(context).edit().putString(KEY_GRANTED_SCOPE, SCOPES).apply()
        return ok
    }

    /**
     * Why a token could not be produced. Separated 2026-08-12 from the single
     * nullable String this used to return: "never authorized", "Spotify refused
     * the refresh", and "we are offline" need three different things from the
     * driver, and collapsing them meant [searchTrack] could not tell them apart
     * either. Same fix, same reasoning, as
     * [com.kevin.legion.ui.sync.GoogleGrantResolver.diagnose] (2026-08-03).
     */
    sealed interface TokenOutcome {
        data class Token(val value: String) : TokenOutcome

        /** No refresh token on file - the browser grant was never completed. */
        object NeverAuthorized : TokenOutcome

        /** Spotify rejected the refresh (revoked, client mismatch). Tokens have been cleared; re-authorize. */
        object Rejected : TokenOutcome

        /** Could not reach Spotify. Tokens kept - the next attempt with signal will work. */
        object Unreachable : TokenOutcome
    }

    /**
     * A usable access token, refreshing if the stored one has expired.
     */
    suspend fun accessToken(context: Context): TokenOutcome {
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
                CompanionProfile.spotifyAccessToken(context)
                    .ifBlank { null }
                    ?.let { TokenOutcome.Token(it) }
                    // Exchange reported success but nothing readable landed - only
                    // reachable if KeyVault.encrypt failed (there is no plaintext
                    // fallback slot for tokens, unlike the client ID). Treat as
                    // never-authorized so the UI offers a clean re-connect.
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
     * Best matching track's Spotify URI for [query], or null. Null covers "no
     * token", "no network", and "nothing matched" alike - every one of them means
     * the same thing to the caller: fall back to the intent path.
     *
     * Asks for a page of results and picks by popularity rather than taking
     * Spotify's first hit. Relevance ranking on a loose spoken phrase
     * ("mask off by future") routinely puts karaoke, tribute, and "made famous
     * by" re-records above the real track - they match the words harder because
     * the words are ALL they have. Popularity separates them cleanly: the
     * original is a chart single, the karaoke cut is not. [looksLikeImposter]
     * then drops the obvious ones outright, so a query whose real track happens
     * to be obscure still doesn't land on a cover.
     */
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
     * Outcome of a track search. Replaces the nullable String (2026-08-12): that
     * null meant "no token" OR "offline" OR "Spotify said no" OR "nothing
     * matched", and `play_music` reported every one of them to the driver as
     * "I couldn't find that on Spotify" - which is actively misleading for
     * three of the four, and left no way to tell from the outside which had
     * happened. Found in the field the day the setup screen shipped.
     */
    sealed interface SearchOutcome {
        /**
         * [name] is Spotify's own title for the matched item, carried through (ticket 08) so a
         * caller that fell through from a driver's own playlist library to this public-catalogue
         * search can name what it actually found - the search result is often NOT what the driver
         * meant by their words, and that has to be said, not silently substituted. Null only for
         * older call sites that never populated it; [search] always sets it now.
         */
        data class Found(val uri: String, val name: String? = null) : SearchOutcome

        /** The browser grant was never completed, or the stored credentials are gone. */
        object NeedsAuthorization : SearchOutcome

        /**
         * Spotify rejected the credentials outright (401/403, or a refused refresh).
         * [detail] is Spotify's own `error.message`, which is the only thing that
         * distinguishes an expired token from "this app is in Development Mode and
         * your account is not on its allowlist" - two 403s that need completely
         * different fixes.
         */
        data class Unauthorized(val detail: String?) : SearchOutcome

        /** Could not reach Spotify at all - offline, DNS, timeout. */
        object Unreachable : SearchOutcome

        /** Spotify answered, and genuinely had nothing for this query. */
        object NoMatch : SearchOutcome

        /**
         * Spotify answered with an error this code does not map. [code] is the HTTP
         * status, [detail] its parsed `error.message`, and [raw] the untouched body -
         * carried because the parsed message alone proved to be misleading in the
         * field (a `400 Invalid limit` against a request whose limit was plainly
         * valid), and on this device logcat cannot be used to see the rest.
         */
        data class Failed(val code: Int, val detail: String?, val raw: String? = null) : SearchOutcome
    }

    /**
     * Spotify's own `{"error":{"status":..,"message":".."}}` message, or a trimmed
     * slice of the raw body when it is not that shape. Never the whole body - an
     * HTML error page from an intercepting proxy would otherwise be pasted into a
     * spoken reply.
     */
    private fun errorDetail(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: body.trim().take(200).takeIf { it.isNotEmpty() }

    /**
     * The exact URL [searchTrack] requests, exposed so the setup screen's search
     * test can display it. Split out 2026-08-12 while chasing a `400 Invalid
     * limit` that the parameters, read on their own, could not explain - on this
     * device app logs never reach logcat, so the only way to see the request as
     * actually sent is to put it on screen.
     */
    fun searchUrlFor(query: String, type: String = "track", limit: Int? = SEARCH_LIMIT): String =
        Uri.parse(SEARCH_URL).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("type", type)
            .apply { if (limit != null) appendQueryParameter("limit", limit.toString()) }
            .build()
            .toString()

    /**
     * Results asked for per search. **Ten, not the documented maximum of fifty.**
     *
     * Measured on-device 2026-08-12 against this account's own token, one request
     * per value: no limit and `limit=10` both return 200; 11, 12, 14, 15, 16, 18,
     * 20 and 50 every one return `400 Invalid limit`. The same URLs return 401
     * (not 400) when sent without a token, and a hand-built URL fails identically
     * to the `Uri.Builder` one, so this is neither an encoding artifact nor a
     * malformed request - Spotify is enforcing a ceiling of 10 that its own
     * documented 1..50 range does not mention.
     *
     * Most likely the reduced quota applied to apps still in Development mode
     * (this is a BYO dev app by design - CLAUDE.md §2 - so it will stay there).
     * That is the best available explanation, NOT a verified one; what is
     * verified is the measurement above.
     *
     * Costs the search a little discrimination: [looksLikeImposter] and the
     * popularity ranking pick from ten candidates rather than twenty, so an
     * obscure original buried under many karaoke cuts is likelier to be missed.
     * Ten is still comfortably enough for the ranking to do its job, and the
     * alternative is every search returning 400.
     */
    private const val SEARCH_LIMIT = 10

    /**
     * One GET with the bearer token, returning (status, body) so the caller can branch and
     * retry. Named for what it does, not just for search: [search] AND every `/v1/me/...`
     * library read below ([getSavedAlbums], [getRecentlyPlayed], [getTopArtists],
     * [getTopTracks]) share this one call site.
     */
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
     * One POST with the bearer token and a JSON body, same (status, body) shape as
     * [getWithToken] - the playlist-items write ([addTrackToPlaylist]) is the only caller today.
     */
    private fun postWithToken(url: String, token: String, jsonBody: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
    }

    /**
     * Spotify's `?type=` values [search] accepts. Deliberately narrow to the four
     * `play_music` actually promises ("a song, artist, album, or playlist" - the honesty bug
     * closed 2026-08-18, `.scratch/drive-test-2026-08-18/issues/05-reading-kevins-spotify-library.md`):
     * the description used to advertise all four while the search underneath was hardcoded
     * `track`, so an album request silently returned one song off it.
     */
    val SEARCHABLE_TYPES = setOf("track", "artist", "album", "playlist")

    /** Convenience wrapper for the common case - unchanged call shape for existing callers. */
    suspend fun searchTrack(context: Context, query: String): SearchOutcome = search(context, query, "track")

    /**
     * Finds the best-matching Spotify URI for [query] of the given [type]
     * ("track"/"artist"/"album"/"playlist" - see [SEARCHABLE_TYPES]). Null covers "no token",
     * "no network", and "nothing matched" alike - every one of them means the same thing to the
     * caller: fall back to the intent path.
     *
     * The popularity-based disambiguation and [looksLikeImposter] filtering below are a TRACK
     * problem specifically - karaoke/tribute covers exist because a loose spoken phrase gives
     * them exactly as much to match on as the real recording. Albums, artists and playlists
     * don't have an equivalent impostor-flooding problem in practice, so for those types this
     * just takes Spotify's own top relevance hit rather than re-deriving a ranking Spotify
     * already computed.
     */
    suspend fun search(context: Context, query: String, type: String = "track"): SearchOutcome = withContext(Dispatchers.IO) {
        val token = when (val outcome = accessToken(context)) {
            is TokenOutcome.Token -> outcome.value
            TokenOutcome.NeverAuthorized -> return@withContext SearchOutcome.NeedsAuthorization
            TokenOutcome.Rejected -> return@withContext SearchOutcome.Unauthorized(
                "Spotify refused the stored refresh token. It has been cleared.",
            )
            TokenOutcome.Unreachable -> return@withContext SearchOutcome.Unreachable
        }
        try {
            var (code, body) = getWithToken(searchUrlFor(query, type), token)
            // Self-heal a tightened limit ceiling. SEARCH_LIMIT is a measured value, not a
            // documented one (see its doc), so Spotify lowering it again would silently
            // break every search exactly as limit=20 did. Retrying once with the parameter
            // dropped falls back to Spotify's own default page size, which by construction
            // can never be out of range. Narrow on purpose: only a 400 that actually names
            // the limit retries, so this cannot mask an unrelated bad request.
            if (code == 400 && errorDetail(body)?.contains("limit", ignoreCase = true) == true) {
                Log.w(TAG, "limit=$SEARCH_LIMIT rejected; retrying without it")
                val retry = getWithToken(searchUrlFor(query, type, limit = null), token)
                code = retry.first
                body = retry.second
            }
            if (code !in 200..299) {
                val detail = errorDetail(body)
                Log.w(TAG, "Search failed $code: $detail")
                return@withContext when (code) {
                    401, 403 -> SearchOutcome.Unauthorized(detail)
                    else -> SearchOutcome.Failed(code, detail, body.trim().take(300))
                }
            }
            run {
                // Spotify's search response nests results under "<type>s" - "tracks", "albums",
                // "artists", "playlists" - keyed by the SAME type string the request used.
                val items = JSONObject(body).optJSONObject("${type}s")?.optJSONArray("items")
                    ?: return@withContext SearchOutcome.NoMatch
                val candidates = (0 until items.length())
                    // A playlist search can hand back a null slot in "items" for a
                    // deleted/private playlist Spotify still indexes - optJSONObject already
                    // returns null for that case, which mapNotNull drops, but the array can
                    // also contain the literal JSON null token rather than an absent slot, so
                    // this filters both shapes rather than assuming one.
                    .mapNotNull { items.optJSONObject(it) }
                    .filter { it.optString("uri").isNotBlank() }
                if (candidates.isEmpty()) return@withContext SearchOutcome.NoMatch

                val chosen = if (type == "track") {
                    val clean = candidates.filterNot { looksLikeImposter(it) }
                    // If filtering leaves nothing, the query genuinely WAS for a
                    // karaoke/tribute cut - honour it rather than returning nothing.
                    (clean.ifEmpty { candidates }).maxByOrNull { it.optInt("popularity", 0) }
                } else {
                    // Non-track types: trust Spotify's own relevance ranking (first result).
                    candidates.first()
                }
                val uri = chosen?.optString("uri")?.takeIf { it.isNotBlank() }
                if (uri != null) {
                    SearchOutcome.Found(uri, chosen.optString("name").takeIf { it.isNotBlank() })
                } else {
                    SearchOutcome.NoMatch
                }
            }
        } catch (e: Exception) {
            // A thrown IOException here is a transport failure, never a verdict on
            // the query - the old code reported it as "not found".
            Log.w(TAG, "Search error: ${e.message}")
            SearchOutcome.Unreachable
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

    // --- Library reads (`browse_my_music`, ticket 05, 2026-08-18) -----------------------------

    private const val ALBUMS_URL = "https://api.spotify.com/v1/me/albums"
    private const val RECENTLY_PLAYED_URL = "https://api.spotify.com/v1/me/player/recently-played"
    private const val TOP_URL = "https://api.spotify.com/v1/me/top"

    /**
     * Page size for the four `/v1/me/...` reads below. **Unlike [SEARCH_LIMIT], this is NOT a
     * measured ceiling** - nobody has run the same one-request-per-value probe against these
     * endpoints that found search's real 10-item cap on 2026-08-12. Kept at the same
     * conservative value on the reasoning that a Development-mode app (this one, by design -
     * CLAUDE.md §2) is the more likely explanation for that cap than something search-specific,
     * but that is inference, not measurement - if any of these four also 400 on this value, that
     * is new information and this comment (and the value) should be corrected the same way
     * [SEARCH_LIMIT]'s was, not just patched around.
     */
    private const val LIBRARY_LIMIT = 10

    /**
     * Outcome of a `/v1/me/...` library read. Deliberately generic over [T] rather than one
     * sealed type per endpoint - the failure shape (no token / rejected / offline / Spotify
     * error) is identical across all four reads, only the payload differs. [Found] with an EMPTY
     * list is a real, distinct outcome from every failure case below it - "Spotify answered and
     * you have zero saved albums" must never collapse into the same shape as "the request
     * failed", which is exactly the tool-description trap CLAUDE.md's `browse_my_music` honesty
     * requirement (ticket 05, part C.9) exists to avoid at the tool layer.
     */
    sealed interface LibraryOutcome<out T> {
        data class Found<T>(val items: List<T>) : LibraryOutcome<T>

        /** The browser grant was never completed, or the stored credentials are gone. */
        object NeedsAuthorization : LibraryOutcome<Nothing>

        /** Spotify rejected the credentials outright, OR the grant on file predates these scopes - see [hasStaleGrant]. */
        data class Unauthorized(val detail: String?) : LibraryOutcome<Nothing>

        /** Could not reach Spotify at all. */
        object Unreachable : LibraryOutcome<Nothing>

        /** Spotify answered with an error, or answered with a body this code could not parse. */
        data class Failed(val code: Int, val detail: String?, val raw: String? = null) : LibraryOutcome<Nothing>
    }

    /** `items[].album` -> [SavedAlbum]. Pure and Android-free so it is a plain JVM unit test, no Robolectric. */
    internal fun parseSavedAlbums(json: JSONObject): List<SavedAlbum> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val album = items.optJSONObject(i)?.optJSONObject("album") ?: return@mapNotNull null
            val name = album.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = album.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            val uri = album.optString("uri")
            SavedAlbum(name = name, artist = artist, uri = uri)
        }
    }

    /** `items[].track` + `items[].played_at` -> [RecentlyPlayedTrack]. Pure, unit-testable. */
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

    /** `items[]` (artist objects) -> [TopArtist]. Pure, unit-testable. */
    internal fun parseTopArtists(json: JSONObject): List<TopArtist> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { TopArtist(it) }
        }
    }

    /** `items[]` (track objects) -> [TopTrack]. Pure, unit-testable. */
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
     * Shared token-gate-then-GET-then-parse shape for the four reads below. [parse] runs only on
     * a 2xx body; a parse failure (Spotify changing its response shape under us) becomes
     * [LibraryOutcome.Failed] rather than a silently empty [LibraryOutcome.Found] - an empty list
     * must only ever mean "Spotify said zero", never "something went wrong reading the answer".
     */
    private suspend fun <T> libraryGet(
        context: Context,
        url: String,
        parse: (JSONObject) -> List<T>,
    ): LibraryOutcome<T> = withContext(Dispatchers.IO) {
        val token = when (val outcome = accessToken(context)) {
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
                    // A 401/403 here is ambiguous between "token genuinely rejected" and "token
                    // is valid but was minted before these scopes existed" - callers that care
                    // about the distinction (browse_my_music) check hasStaleGrant separately
                    // rather than this function trying to infer it from an HTTP status alone.
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

    /** The driver's saved (liked) albums, newest-saved first (Spotify's own default order). */
    suspend fun getSavedAlbums(context: Context, limit: Int = LIBRARY_LIMIT): LibraryOutcome<SavedAlbum> {
        val url = Uri.parse(ALBUMS_URL).buildUpon()
            .appendQueryParameter("limit", limit.toString())
            .build().toString()
        return libraryGet(context, url, ::parseSavedAlbums)
    }

    /**
     * Spotify's own play history, most recent first, across every device the driver has played
     * on - NOT scoped to LEGION. Requires `user-read-recently-played`.
     */
    suspend fun getRecentlyPlayed(context: Context, limit: Int = LIBRARY_LIMIT): LibraryOutcome<RecentlyPlayedTrack> {
        val url = Uri.parse(RECENTLY_PLAYED_URL).buildUpon()
            .appendQueryParameter("limit", limit.toString())
            .build().toString()
        return libraryGet(context, url, ::parseRecentlyPlayed)
    }

    /** Spotify's own top-artists ranking for this account. Requires `user-top-read`. */
    suspend fun getTopArtists(context: Context, limit: Int = LIBRARY_LIMIT): LibraryOutcome<TopArtist> {
        val url = Uri.parse("$TOP_URL/artists").buildUpon()
            .appendQueryParameter("limit", limit.toString())
            .build().toString()
        return libraryGet(context, url, ::parseTopArtists)
    }

    /** Spotify's own top-tracks ranking for this account. Requires `user-top-read`. */
    suspend fun getTopTracks(context: Context, limit: Int = LIBRARY_LIMIT): LibraryOutcome<TopTrack> {
        val url = Uri.parse("$TOP_URL/tracks").buildUpon()
            .appendQueryParameter("limit", limit.toString())
            .build().toString()
        return libraryGet(context, url, ::parseTopTracks)
    }

    // --- Queue read (ticket 04, 2026-08-19) ---------------------------------------------------

    private const val QUEUE_URL = "https://api.spotify.com/v1/me/player/queue"

    /**
     * `{"queue": [...]}` -> [QueuedTrack] list, most-imminent first (Spotify's own order). Pure,
     * unit-testable, same shape as [parseRecentlyPlayed] - unlike that endpoint's `items[].track`
     * nesting, `queue`'s own entries are bare track objects.
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
     * "What's coming up" (ticket 04 scope item 3) - `GET /v1/me/player/queue`, needing BOTH
     * `user-read-currently-playing` and `user-read-playback-state` (both already in [SCOPES]).
     * Truncated to [limit] client-side: Spotify's own endpoint takes no `limit` parameter and
     * can return its entire queue.
     */
    suspend fun getQueue(context: Context, limit: Int = LIBRARY_LIMIT): LibraryOutcome<QueuedTrack> =
        when (val outcome = libraryGet(context, QUEUE_URL, ::parseQueue)) {
            is LibraryOutcome.Found -> LibraryOutcome.Found(outcome.items.take(limit))
            else -> outcome
        }

    // --- Playlists by name (ticket 08, .scratch/spotify-voice/issues/08-playlists-by-name.md) --

    private const val PLAYLISTS_URL = "https://api.spotify.com/v1/me/playlists"
    private const val ME_URL = "https://api.spotify.com/v1/me"
    private const val PLAYLIST_ITEMS_BASE = "https://api.spotify.com/v1/playlists"

    private const val KEY_USER_ID = "spotify_user_id"
    private const val KEY_PLAYLIST_CACHE = "playlist_cache"
    private const val KEY_PLAYLIST_CACHE_AT = "playlist_cache_at"

    /**
     * How long [myPlaylists] trusts its own cache before hitting `/me/playlists` again. Ticket
     * 08's scope item 1 asks for "a schedule that does not cost a call per utterance" without
     * naming one - this is the judgment call, written down rather than left implicit.
     *
     * 15 minutes: long enough that a drive full of "play my X playlist" / "add this to Y"
     * requests costs at most one refresh, short enough that a playlist a friend shares mid-drive
     * ("shared with me" is explicitly in scope, per the ticket's header) shows up within the same
     * sitting rather than needing an app restart. A stale cache is never silently wrong in a way
     * that matters: [bestPlaylistMatch] against a cache missing a brand-new playlist just falls
     * through to [resolvePlaylist]'s search step, same as it would for a genuinely unknown name.
     */
    private const val PLAYLIST_CACHE_TTL_MS = 15 * 60 * 1000L

    /** Same page-size reasoning as [LIBRARY_LIMIT] - unmeasured for this endpoint, kept aligned with the measured [SEARCH_LIMIT] ceiling this dev-mode app is the likeliest explanation for. Paginated below via `next`, so this only bounds the round trips, not the total playlists returned. */
    private const val PLAYLISTS_PAGE_LIMIT = 10

    /** Bounds pagination so a corrupted or infinite `next` chain cannot spin forever - 20 pages at [PLAYLISTS_PAGE_LIMIT] is 200 playlists, comfortably past any real personal library. */
    private const val MAX_PLAYLIST_PAGES = 20

    /** `items[]` (playlist objects) -> [SpotifyPlaylist], `readable` computed against [userId]. Pure, unit-testable. */
    internal fun parsePlaylistsPage(json: JSONObject, userId: String?): List<SpotifyPlaylist> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ownerId = item.optJSONObject("owner")?.optString("id")
            val collaborative = item.optBoolean("collaborative", false)
            val readable = collaborative || (userId != null && userId == ownerId)
            SpotifyPlaylist(name = name, uri = uri, id = id, readable = readable)
        }
    }

    /**
     * Serializes [playlists] to a compact JSON array string for the on-device cache. Kept as
     * plain JSON (not Room) - this is a disposable, TTL-bound read cache of data Spotify owns,
     * not a fact LEGION asserts, so it does not belong in [com.kevin.legion.data.local.CarDatabase].
     */
    private fun serializePlaylists(playlists: List<SpotifyPlaylist>): String {
        val arr = JSONArray()
        playlists.forEach { p ->
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("uri", p.uri)
                    .put("id", p.id)
                    .put("readable", p.readable),
            )
        }
        return arr.toString()
    }

    /** Inverse of [serializePlaylists]. Returns null (not empty) on anything unparseable, so a corrupt cache is treated as absent rather than as "zero playlists". */
    private fun deserializePlaylists(raw: String): List<SpotifyPlaylist>? = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SpotifyPlaylist(
                name = o.getString("name"),
                uri = o.getString("uri"),
                id = o.getString("id"),
                readable = o.getBoolean("readable"),
            )
        }
    }.getOrNull()

    /**
     * The account's own Spotify user ID, used to decide [SpotifyPlaylist.readable]. Cached
     * indefinitely once known - a Spotify user ID does not change for the life of an account, and
     * this read is on the hot path of every playlist cache refresh, so re-fetching it on a
     * schedule would be pure waste. Best-effort: any failure returns null, and [parsePlaylistsPage]
     * treats a null [userId] as "cannot prove ownership", which only ever makes a playlist read
     * as LESS permissive (falls back to the `collaborative` flag alone) - never more.
     */
    private suspend fun cachedUserId(context: Context, token: String): String? {
        authPrefs(context).getString(KEY_USER_ID, null)?.let { return it }
        return try {
            val (code, body) = getWithToken(ME_URL, token)
            if (code !in 200..299) return null
            JSONObject(body).optString("id").takeIf { it.isNotBlank() }
                ?.also { authPrefs(context).edit().putString(KEY_USER_ID, it).apply() }
        } catch (e: Exception) {
            Log.w(TAG, "GET /me threw: ${e.message}")
            null
        }
    }

    /** On-disk cache read, or null when absent, corrupt, or past [PLAYLIST_CACHE_TTL_MS]. */
    private fun freshCachedPlaylists(context: Context): List<SpotifyPlaylist>? {
        val at = authPrefs(context).getLong(KEY_PLAYLIST_CACHE_AT, 0L)
        if (System.currentTimeMillis() - at > PLAYLIST_CACHE_TTL_MS) return null
        val raw = authPrefs(context).getString(KEY_PLAYLIST_CACHE, null) ?: return null
        return deserializePlaylists(raw)
    }

    /**
     * Fetches every page of `/me/playlists` following Spotify's own `next` links, bounded by
     * [MAX_PLAYLIST_PAGES]. Failure shape matches [libraryGet]'s - the two live in different
     * functions only because this one must keep looping across pages rather than making one call.
     */
    private suspend fun fetchAllPlaylists(context: Context, token: String, userId: String?): LibraryOutcome<SpotifyPlaylist> {
        val out = mutableListOf<SpotifyPlaylist>()
        var url: String? = Uri.parse(PLAYLISTS_URL).buildUpon()
            .appendQueryParameter("limit", PLAYLISTS_PAGE_LIMIT.toString())
            .build().toString()
        var pages = 0
        while (url != null && pages < MAX_PLAYLIST_PAGES) {
            val (code, body) = try {
                getWithToken(url, token)
            } catch (e: Exception) {
                Log.w(TAG, "Playlists read error: ${e.message}")
                return LibraryOutcome.Unreachable
            }
            if (code !in 200..299) {
                val detail = errorDetail(body)
                Log.w(TAG, "Playlists read failed $code: $detail")
                return when (code) {
                    401, 403 -> LibraryOutcome.Unauthorized(detail)
                    else -> LibraryOutcome.Failed(code, detail, body.trim().take(300))
                }
            }
            val page = try {
                JSONObject(body)
            } catch (e: Exception) {
                Log.w(TAG, "Playlists parse error: ${e.message}")
                return LibraryOutcome.Failed(code, "Could not read Spotify's response", body.trim().take(300))
            }
            out += parsePlaylistsPage(page, userId)
            url = page.optString("next").takeIf { it.isNotBlank() && it != "null" }
            pages++
        }
        return LibraryOutcome.Found(out)
    }

    /**
     * The driver's own playlists, plus ones friends have made him a collaborator on (map ticket 08's
     * header scope: "his own playlists, plus playlists friends have shared with him"). Backed by a
     * TTL cache ([PLAYLIST_CACHE_TTL_MS]) so a normal drive full of play/add requests costs at
     * most one `/me/playlists` round trip, never one per utterance. [forceRefresh] bypasses the
     * cache - nothing in this map calls it with true yet, kept for a future "refresh my
     * playlists" tool without needing a signature change.
     */
    suspend fun myPlaylists(context: Context, forceRefresh: Boolean = false): LibraryOutcome<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            freshCachedPlaylists(context)?.let { return@withContext LibraryOutcome.Found(it) }
        }
        val token = when (val outcome = accessToken(context)) {
            is TokenOutcome.Token -> outcome.value
            TokenOutcome.NeverAuthorized -> return@withContext LibraryOutcome.NeedsAuthorization
            TokenOutcome.Rejected -> return@withContext LibraryOutcome.Unauthorized(
                "Spotify refused the stored refresh token. It has been cleared.",
            )
            TokenOutcome.Unreachable -> return@withContext LibraryOutcome.Unreachable
        }
        val userId = cachedUserId(context, token)
        val result = fetchAllPlaylists(context, token, userId)
        if (result is LibraryOutcome.Found) {
            authPrefs(context).edit()
                .putString(KEY_PLAYLIST_CACHE, serializePlaylists(result.items))
                .putLong(KEY_PLAYLIST_CACHE_AT, System.currentTimeMillis())
                .apply()
        }
        result
    }

    /**
     * Case/whitespace/punctuation-insensitive normalization shared by [playlistMatchScore] - a
     * driver's spoken name and Spotify's stored title routinely differ only in casing or a
     * possessive apostrophe ("kev's roadtrip" vs "Kev's Roadtrip"), and neither is a real
     * mismatch.
     */
    internal fun normalizePlaylistName(s: String): String =
        s.lowercase().trim().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    /** Classic edit-distance DP, iterative (no recursion depth risk on a long playlist name). */
    internal fun levenshtein(a: String, b: String): Int {
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
     * 0.0 (nothing alike) to 1.0 (identical after normalization). Three tiers, cheapest and most
     * confident first: exact normalized match, then substring containment (a driver saying just
     * "roadtrip" for a playlist actually named "Summer Roadtrip 2024"), then a normalized edit
     * distance ratio for everything else - typos, mis-heard words, near-misses. Pure and
     * `internal` so this is a plain JVM unit test rather than something only checkable by driving.
     */
    internal fun playlistMatchScore(spoken: String, candidate: String): Double {
        val ns = normalizePlaylistName(spoken)
        val nc = normalizePlaylistName(candidate)
        if (ns.isEmpty() || nc.isEmpty()) return 0.0
        if (ns == nc) return 1.0
        if (nc.contains(ns) || ns.contains(nc)) return 0.9
        val distance = levenshtein(ns, nc)
        val maxLen = maxOf(ns.length, nc.length)
        return 1.0 - distance.toDouble() / maxLen
    }

    /**
     * Threshold [bestPlaylistMatch] requires before trusting a fuzzy hit over falling through to
     * public search. Set high enough that two genuinely different short playlist names ("Gym" vs
     * "Chill") don't collide, low enough that a couple of typos or a dropped word still lands.
     */
    internal const val PLAYLIST_MATCH_THRESHOLD = 0.6

    /**
     * The best-scoring playlist in [playlists] for [spoken], or null when nothing clears
     * [PLAYLIST_MATCH_THRESHOLD] - null means "fall through to search", not "no playlists exist".
     * Pure, `internal`, no network - the whole reason [myPlaylists] exists as a separate cached
     * read is so this can run against a plain in-memory list with zero Web API cost per utterance.
     */
    internal fun bestPlaylistMatch(playlists: List<SpotifyPlaylist>, spoken: String): SpotifyPlaylist? {
        if (playlists.isEmpty() || spoken.isBlank()) return null
        val best = playlists.maxByOrNull { playlistMatchScore(spoken, it.name) } ?: return null
        return if (playlistMatchScore(spoken, best.name) >= PLAYLIST_MATCH_THRESHOLD) best else null
    }

    /** A library-only playlist lookup - see [findMyPlaylist]. Never falls through to public search; a caller that wants that is [resolvePlaylist]. */
    sealed interface PlaylistLookup {
        data class Found(val playlist: SpotifyPlaylist) : PlaylistLookup
        object NeedsAuthorization : PlaylistLookup
        data class Unauthorized(val detail: String?) : PlaylistLookup
        object Unreachable : PlaylistLookup
        /** No cached playlist scored above [PLAYLIST_MATCH_THRESHOLD]. Distinct from every failure above - Spotify answered fine, nothing just matched. */
        object NoMatch : PlaylistLookup
        data class Failed(val code: Int, val detail: String?) : PlaylistLookup
    }

    /**
     * Matches [spoken] against [myPlaylists] ONLY - no public-search fallback. This is what
     * "add this to <playlist>" (ticket 08 scope item 3) resolves the target playlist through:
     * adding a track to a public playlist found via search would almost always 403 anyway (the
     * driver doesn't own or collaborate on it), and even on the rare case where it wouldn't, that
     * was never what "add this to Roadtrip" meant. [resolvePlaylist] below is the play-time
     * version that DOES fall through to search.
     */
    suspend fun findMyPlaylist(context: Context, spoken: String): PlaylistLookup =
        when (val lib = myPlaylists(context)) {
            is LibraryOutcome.Found -> bestPlaylistMatch(lib.items, spoken)?.let { PlaylistLookup.Found(it) }
                ?: PlaylistLookup.NoMatch
            LibraryOutcome.NeedsAuthorization -> PlaylistLookup.NeedsAuthorization
            is LibraryOutcome.Unauthorized -> PlaylistLookup.Unauthorized(lib.detail)
            LibraryOutcome.Unreachable -> PlaylistLookup.Unreachable
            is LibraryOutcome.Failed -> PlaylistLookup.Failed(lib.code, lib.detail)
        }

    /** Every way [resolvePlaylist] can end. [FromSearch] is the fallback case that must be spoken about, per ticket 08 scope item 2 ("say which one it used when the answer might surprise him"). */
    sealed interface PlaylistResolution {
        /** Matched the driver's OWN cached library ([myPlaylists]) - the expected, unremarkable case. */
        data class FromLibrary(val playlist: SpotifyPlaylist) : PlaylistResolution
        /** Nothing in the driver's own library matched; this came from the public catalogue via `/v1/search`. [name] is Spotify's own title for it, when known. */
        data class FromSearch(val uri: String, val name: String?) : PlaylistResolution
        object NeedsAuthorization : PlaylistResolution
        data class Unauthorized(val detail: String?) : PlaylistResolution
        object Unreachable : PlaylistResolution
        object NoMatch : PlaylistResolution
        data class Failed(val code: Int, val detail: String?) : PlaylistResolution
    }

    /**
     * "Play my Roadtrip playlist" (ticket 08 scope items 1-2): [findMyPlaylist] first - the
     * driver's own cache, matched with no Web API cost - and only when that comes back
     * [PlaylistLookup.NoMatch] does this fall through to `/v1/search`, which ranks the whole
     * public catalogue and is exactly what this ticket exists to stop being the primary path. A
     * library-read FAILURE (not merely "no match") still falls through too - the theory being a
     * broken `/me/playlists` call should not also break a public playlist the driver asked for by
     * name, and search is an independent code path that may well still work.
     */
    suspend fun resolvePlaylist(context: Context, spoken: String): PlaylistResolution {
        when (val lookup = findMyPlaylist(context, spoken)) {
            is PlaylistLookup.Found -> return PlaylistResolution.FromLibrary(lookup.playlist)
            PlaylistLookup.NeedsAuthorization -> return PlaylistResolution.NeedsAuthorization
            is PlaylistLookup.Unauthorized -> return PlaylistResolution.Unauthorized(lookup.detail)
            PlaylistLookup.Unreachable -> return PlaylistResolution.Unreachable
            PlaylistLookup.NoMatch -> Unit // fall through to search below
            is PlaylistLookup.Failed -> Unit // fall through to search below - see doc above
        }
        return when (val s = search(context, spoken, "playlist")) {
            is SearchOutcome.Found -> PlaylistResolution.FromSearch(s.uri, s.name)
            SearchOutcome.NeedsAuthorization -> PlaylistResolution.NeedsAuthorization
            is SearchOutcome.Unauthorized -> PlaylistResolution.Unauthorized(s.detail)
            SearchOutcome.Unreachable -> PlaylistResolution.Unreachable
            SearchOutcome.NoMatch -> PlaylistResolution.NoMatch
            is SearchOutcome.Failed -> PlaylistResolution.Failed(s.code, s.detail)
        }
    }

    /** Every way [addTrackToPlaylist] can end. */
    sealed interface PlaylistWriteOutcome {
        /** `POST .../items` was awaited and came back successful (201). */
        object Added : PlaylistWriteOutcome
        object NeedsAuthorization : PlaylistWriteOutcome
        data class Unauthorized(val detail: String?) : PlaylistWriteOutcome
        object Unreachable : PlaylistWriteOutcome
        /**
         * Spotify's own write refusal (403) - the research's ownership/collaborator boundary,
         * confirmed live rather than only inferred from the cached [SpotifyPlaylist.readable]
         * flag. A caller should normally never reach this (it should have checked `readable`
         * first and said so), but a stale cache entry can still surface it, and it must be named
         * for what it is - never reported as "couldn't find that playlist".
         */
        object NotYours : PlaylistWriteOutcome
        data class Failed(val code: Int, val detail: String?) : PlaylistWriteOutcome
    }

    /**
     * `POST /v1/playlists/{id}/items` (ticket 08 scope item 3) - note the migration named in the
     * research: this used to be `POST /v1/playlists/{id}/tracks` with a `tracks` response field;
     * both are now `items`. [playlistId] is [SpotifyPlaylist.id] (bare ID), never a full URI.
     * Creating a NEW playlist by voice is deliberately out of scope (map decision 9) - this only
     * ever appends to one that already exists.
     */
    suspend fun addTrackToPlaylist(context: Context, playlistId: String, trackUri: String): PlaylistWriteOutcome =
        withContext(Dispatchers.IO) {
            val token = when (val outcome = accessToken(context)) {
                is TokenOutcome.Token -> outcome.value
                TokenOutcome.NeverAuthorized -> return@withContext PlaylistWriteOutcome.NeedsAuthorization
                TokenOutcome.Rejected -> return@withContext PlaylistWriteOutcome.Unauthorized(
                    "Spotify refused the stored refresh token. It has been cleared.",
                )
                TokenOutcome.Unreachable -> return@withContext PlaylistWriteOutcome.Unreachable
            }
            val body = JSONObject().put("uris", JSONArray().put(trackUri)).toString()
            try {
                val (code, respBody) = postWithToken("$PLAYLIST_ITEMS_BASE/$playlistId/items", token, body)
                if (code in 200..299) return@withContext PlaylistWriteOutcome.Added
                val detail = errorDetail(respBody)
                Log.w(TAG, "Playlist add failed $code: $detail")
                when (code) {
                    401 -> PlaylistWriteOutcome.Unauthorized(detail)
                    // 403 here is Spotify's own ownership/collaborator refusal, not an auth
                    // problem - see PlaylistWriteOutcome.NotYours's own doc.
                    403 -> PlaylistWriteOutcome.NotYours
                    else -> PlaylistWriteOutcome.Failed(code, detail)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Playlist add error: ${e.message}")
                PlaylistWriteOutcome.Unreachable
            }
        }
}
