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

    /** True once the driver has authorized and a refresh token is on file. */
    fun isAuthorized(context: Context): Boolean =
        CompanionProfile.spotifyRefreshToken(context).isNotBlank()

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
            // Search needs a valid user token but no particular scope. Ask for
            // nothing extra - the narrower the consent screen, the better. The
            // parameter is OMITTED rather than sent empty (2026-07-29): Spotify
            // documents absent scope as "publicly available information only",
            // and a literal `scope=` is not that documented case.
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
     * A usable access token, refreshing if the stored one has expired. Null when the
     * driver has never authorized or the refresh failed (in which case the stored
     * tokens are cleared so the UI can offer a clean re-connect).
     */
    suspend fun accessToken(context: Context): String? {
        val stored = CompanionProfile.spotifyAccessToken(context)
        if (stored.isNotBlank() && System.currentTimeMillis() < CompanionProfile.spotifyTokenExpiry(context)) {
            return stored
        }
        val refresh = CompanionProfile.spotifyRefreshToken(context)
        if (refresh.isBlank()) return null
        val clientId = CompanionProfile.spotifyClientId(context)
        if (clientId.isBlank()) return null

        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId)
            .build()
        return when (postForTokens(context, form)) {
            TokenResult.SUCCESS -> CompanionProfile.spotifyAccessToken(context).ifBlank { null }
            // Spotify actually rejected the refresh token (revoked, expired past its
            // grace, client mismatch). Only NOW is a full re-authorization the right
            // answer, so clear and let the UI offer CONNECT again.
            TokenResult.REJECTED -> {
                CompanionProfile.clearSpotifyTokens(context)
                null
            }
            // Network blip - a dead zone mid-drive, exactly when this is likely.
            // Keep the tokens; the next play attempt with signal refreshes fine.
            // Nuking them here (the old behavior) forced a full browser re-auth for
            // a transient failure, which is the §9 "degrades gracefully offline" rule
            // getting it backwards.
            TokenResult.TRANSIENT -> null
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

    suspend fun searchTrackUri(context: Context, query: String): String? = withContext(Dispatchers.IO) {
        val token = accessToken(context) ?: return@withContext null
        val url = Uri.parse(SEARCH_URL).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("type", "track")
            .appendQueryParameter("limit", "20")
            .build()
        try {
            val request = Request.Builder()
                .url(url.toString())
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Search failed ${response.code}")
                    return@withContext null
                }
                val items = JSONObject(body).optJSONObject("tracks")?.optJSONArray("items")
                    ?: return@withContext null
                val candidates = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    .filter { it.optString("uri").isNotBlank() }
                if (candidates.isEmpty()) return@withContext null

                val clean = candidates.filterNot { looksLikeImposter(it) }
                // If filtering leaves nothing, the query genuinely WAS for a
                // karaoke/tribute cut - honour it rather than returning nothing.
                (clean.ifEmpty { candidates })
                    .maxByOrNull { it.optInt("popularity", 0) }
                    ?.optString("uri")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Search error: ${e.message}")
            null
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
