package com.kevin.legion.backend

import android.content.Context

/**
 * BYO runtime config for the backend-erp cutover (`.scratch/backend-erp/issues/05-migration-path.md`,
 * Phase 1). Holds the Supabase project URL and its anon key - **install-scoped, same posture as
 * [com.kevin.legion.ai.CompanionProfile]'s flags**, plain [android.content.SharedPreferences],
 * never [com.kevin.legion.ai.KeyVault].
 *
 * **Why the anon key does not need KeyVault, deliberately, so nobody "fixes" it later.** Supabase
 * designs the anon key to ship inside a client - it is the public half of the pair, restricted
 * entirely by the RLS policies in `supabase/migrations/20260825000100_household_and_rls.sql`. The
 * SECRET that must never be lost or leaked in plaintext is the SESSION (refresh token) a real
 * sign-in mints, and that is [SupabaseSession]'s job, not this class's. Storing the anon key in
 * KeyVault would spend Keystore-failure risk (see [SupabaseSession]'s fail-closed doc comment) on
 * a value that was never secret to begin with.
 *
 * **Never read from [com.kevin.legion.BuildConfig].** CLAUDE.md §2 (clone-and-run) and ADR 0038:
 * a distributed APK must not carry Kevin's own Supabase project. A stranger who clones the repo
 * and sideloads gets a blank config screen, exactly like the Gemini key.
 */
object SupabaseConfig {
    private const val PREFS = "supabase_config"
    private const val KEY_URL = "url"
    private const val KEY_ANON_KEY = "anon_key"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Loose shape check for a Supabase project URL - `https://<ref>.supabase.co`. Deliberately
     * loose (no ref-length/charset check): the point is to catch a pasted anon key or a stray
     * whitespace-only field, not to validate Supabase's own ref format, which is Supabase's to
     * change.
     */
    fun isValidUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) return false
        val host = trimmed.removePrefix("https://").substringBefore("/")
        return host.endsWith(".supabase.co") && host.length > ".supabase.co".length
    }

    /** An anon key is a long opaque JWT; the only cheap check available is "not blank". */
    fun isValidAnonKey(key: String): Boolean = key.trim().isNotBlank()

    /** The saved project URL, or blank if never configured. */
    fun url(context: Context): String = prefs(context).getString(KEY_URL, "").orEmpty()

    /** The saved anon key, or blank if never configured. */
    fun anonKey(context: Context): String = prefs(context).getString(KEY_ANON_KEY, "").orEmpty()

    /**
     * True once both a plausible URL and a non-blank anon key are saved. Callers ([SupabaseClientProvider],
     * the settings screen) use this to decide whether to attempt a client at all, rather than
     * building one against blank strings and failing later inside a network call.
     */
    fun isConfigured(context: Context): Boolean =
        isValidUrl(url(context)) && isValidAnonKey(anonKey(context))

    /**
     * Saves [url] and [anonKey] only if BOTH pass their shape check; otherwise saves nothing and
     * returns false, so a caller can distinguish "saved" from "rejected" instead of the value
     * silently sitting in prefs half-configured.
     */
    fun save(context: Context, url: String, anonKey: String): Boolean {
        val trimmedUrl = url.trim()
        val trimmedKey = anonKey.trim()
        if (!isValidUrl(trimmedUrl) || !isValidAnonKey(trimmedKey)) return false
        prefs(context).edit()
            .putString(KEY_URL, trimmedUrl)
            .putString(KEY_ANON_KEY, trimmedKey)
            .apply()
        return true
    }

    /** Clears the saved project config. Does NOT touch [SupabaseSession] - a caller that means to
     * fully sign out and reconfigure must clear both explicitly. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_URL).remove(KEY_ANON_KEY).apply()
    }
}
