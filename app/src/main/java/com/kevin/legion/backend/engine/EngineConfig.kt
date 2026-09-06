package com.kevin.legion.backend.engine

import android.content.Context
import android.content.SharedPreferences
import com.kevin.legion.ai.KeyVault

/**
 * BYO runtime config for the Django engine (ADR 0044, `.scratch/django-engine/research/execution-plan.md`
 * Phase 2/3 - ticket 09 first half: "the phone learns where the engine is and how to sign in to
 * it"). Holds the engine's base URL (plain - a URL a driver types in is not a secret, the same
 * reasoning [com.kevin.legion.backend.SupabaseConfig]'s doc comment already gives for the anon
 * key) and the device token Django mints at `POST /api/auth/login` (a bearer credential -
 * encrypted at rest, the same shape [com.kevin.legion.backend.SupabaseSession] already uses for
 * the Supabase session's refresh token).
 *
 * **No `object` singleton, deliberately** - CLAUDE.md §8's Hilt note for this ticket ("Hilt is
 * coming but this ticket does NOT wait for it - write injectable classes"): this is a plain class
 * taking its [Context] as a constructor parameter, resolved once at the call site, so wiring it
 * through a Hilt module later is a signature-compatible move rather than a rewrite.
 *
 * **[encrypt]/[decrypt] default to [KeyVault]'s real Keystore-backed functions and are injectable
 * constructor params purely so a test can exercise every branch - including the fail-closed one -
 * without a real Android Keystore.** [KeyVault]'s own doc comment already establishes that cannot
 * be done outside a real device, and [com.kevin.legion.backend.SupabaseSession] takes the
 * identical seam for the identical reason.
 *
 * **Fail-closed, not fallback-to-plaintext, matching [com.kevin.legion.backend.SupabaseSession]'s
 * posture exactly**: if [encrypt] returns null (a broken Keystore - cheap devices ship flaky
 * keymaster HALs, per [KeyVault]'s own doc comment), [saveSession] stores NOTHING and returns
 * false rather than writing the raw token to disk. A device-scoped credential silently degrading
 * to plaintext is a worse failure than asking the driver to sign in again once storage is healthy.
 */
class EngineConfig(
    context: Context,
    private val encrypt: (String) -> String? = KeyVault::encrypt,
    private val decrypt: (String) -> String? = KeyVault::decrypt,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The saved engine base URL, or blank if never configured. Reads are cheap and synchronous -
     * no network, no suspend, matching the brief's "reads are cheap and synchronous" instruction. */
    fun baseUrl(): String = prefs.getString(KEY_BASE_URL, "").orEmpty()

    /**
     * The signed-in device's decrypted token, or null if never signed in, signed out, or the
     * stored blob no longer decrypts (a Keystore key lost across an OS wipe - the same shape
     * [com.kevin.legion.backend.SupabaseSession]'s own read path degrades on, traced there rather
     * than re-derived here). **Never logged** - no caller in this file or [EngineAuth] writes this
     * value to Log/println; the only place it leaves this class is the `Authorization` header
     * [EngineAuth] attaches to a request.
     */
    fun token(): String? {
        val blob = prefs.getString(KEY_TOKEN, null) ?: return null
        return decrypt(blob)
    }

    /** The signed-in device's Django user id, or null if never signed in. Plain - a UUID primary
     * key, not a secret. */
    fun userId(): String? = prefs.getString(KEY_USER_ID, null)

    fun isConfigured(): Boolean = isValidBaseUrl(baseUrl())

    fun isSignedIn(): Boolean = !token().isNullOrBlank()

    /** Saves [url] only if it passes [isValidBaseUrl]; otherwise saves nothing and returns false,
     * matching [com.kevin.legion.backend.SupabaseConfig.save]'s "distinguish saved from rejected"
     * contract rather than silently leaving prefs half-written. */
    fun saveBaseUrl(url: String): Boolean {
        val trimmed = url.trim().trimEnd('/')
        if (!isValidBaseUrl(trimmed)) return false
        write { putString(KEY_BASE_URL, trimmed) }
        return true
    }

    /**
     * Encrypts [token] with [encrypt] and saves it alongside [userId]. Returns false and stores
     * NOTHING (not even [userId]) if [encrypt] fails - see the class doc's fail-closed paragraph.
     * [EngineAuth.login] is the one caller; a false return there becomes
     * [LoginResult.Refused] rather than a silent plaintext write.
     */
    fun saveSession(token: String, userId: String): Boolean {
        val encrypted = encrypt(token) ?: return false
        write {
            putString(KEY_TOKEN, encrypted)
            putString(KEY_USER_ID, userId)
        }
        return true
    }

    /** Clears the device token and user id. Does NOT touch [baseUrl] - a driver signing out of one
     * account on this device should not have to re-type where the engine lives. */
    fun clearSession() {
        write {
            remove(KEY_TOKEN)
            remove(KEY_USER_ID)
        }
    }

    /** One funnel (per the brief: "writes go through one funnel") - every write to this store
     * passes through here, so a future caller cannot add a second `prefs.edit()` call site that
     * forgets `.apply()` or writes a key this class does not otherwise know about. */
    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
    }

    companion object {
        private const val PREFS = "engine_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "device_token"
        private const val KEY_USER_ID = "user_id"

        /**
         * Loose shape check - `http://` or `https://` plus a non-blank host, deliberately loose
         * (no port/path/charset validation): the point is catching a blank or garbage field, not
         * validating URL syntax a real request will reject on its own the moment it is made, the
         * same posture [com.kevin.legion.backend.SupabaseConfig.isValidUrl] states for the same
         * reason.
         */
        fun isValidBaseUrl(url: String): Boolean {
            val trimmed = url.trim()
            val withoutScheme = when {
                trimmed.startsWith("http://") -> trimmed.removePrefix("http://")
                trimmed.startsWith("https://") -> trimmed.removePrefix("https://")
                else -> return false
            }
            return withoutScheme.substringBefore("/").isNotBlank()
        }
    }
}
