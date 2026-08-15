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
import okhttp3.OkHttpClient
import okhttp3.Request
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
     */
    const val SCOPES = "user-read-private"

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
        data class Found(val uri: String) : SearchOutcome

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
    fun searchUrlFor(query: String, limit: Int? = SEARCH_LIMIT): String =
        Uri.parse(SEARCH_URL).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("type", "track")
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

    /** One GET with the bearer token, returning (status, body) so the caller can branch and retry. */
    private fun getSearch(url: String, token: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
    }

    suspend fun searchTrack(context: Context, query: String): SearchOutcome = withContext(Dispatchers.IO) {
        val token = when (val outcome = accessToken(context)) {
            is TokenOutcome.Token -> outcome.value
            TokenOutcome.NeverAuthorized -> return@withContext SearchOutcome.NeedsAuthorization
            TokenOutcome.Rejected -> return@withContext SearchOutcome.Unauthorized(
                "Spotify refused the stored refresh token. It has been cleared.",
            )
            TokenOutcome.Unreachable -> return@withContext SearchOutcome.Unreachable
        }
        try {
            var (code, body) = getSearch(searchUrlFor(query), token)
            // Self-heal a tightened limit ceiling. SEARCH_LIMIT is a measured value, not a
            // documented one (see its doc), so Spotify lowering it again would silently
            // break every search exactly as limit=20 did. Retrying once with the parameter
            // dropped falls back to Spotify's own default page size, which by construction
            // can never be out of range. Narrow on purpose: only a 400 that actually names
            // the limit retries, so this cannot mask an unrelated bad request.
            if (code == 400 && errorDetail(body)?.contains("limit", ignoreCase = true) == true) {
                Log.w(TAG, "limit=$SEARCH_LIMIT rejected; retrying without it")
                val retry = getSearch(searchUrlFor(query, limit = null), token)
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
                val items = JSONObject(body).optJSONObject("tracks")?.optJSONArray("items")
                    ?: return@withContext SearchOutcome.NoMatch
                val candidates = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    .filter { it.optString("uri").isNotBlank() }
                if (candidates.isEmpty()) return@withContext SearchOutcome.NoMatch

                val clean = candidates.filterNot { looksLikeImposter(it) }
                // If filtering leaves nothing, the query genuinely WAS for a
                // karaoke/tribute cut - honour it rather than returning nothing.
                val uri = (clean.ifEmpty { candidates })
                    .maxByOrNull { it.optInt("popularity", 0) }
                    ?.optString("uri")
                    ?.takeIf { it.isNotBlank() }
                if (uri != null) SearchOutcome.Found(uri) else SearchOutcome.NoMatch
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
}
