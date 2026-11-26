package com.kevin.midnightai.backend.engine

import android.content.Context
import android.content.SharedPreferences
import com.kevin.midnightai.ai.KeyVault

/**
 * BYO runtime config for the Django engine, this app's third "limb" client (ADR 0044 in the
 * LEGION repo: "one Django server, every limb a client of it" - this app, the phone app, and a
 * planned PWA). Holds the engine's base URL (plain - a URL a driver types in is not a secret) and
 * the device token Django mints at `POST /api/auth/login` (a bearer credential - encrypted at
 * rest via this app's own [KeyVault], the same AES/GCM-in-Keystore mechanism this app already
 * uses for the BYO Gemini key).
 *
 * **No `object` singleton, deliberately** - a plain class taking its [Context] as a constructor
 * parameter, resolved once at the call site, matching [EngineHttp]/[EngineAuth]'s own posture.
 *
 * **[encrypt]/[decrypt] default to [KeyVault]'s real Keystore-backed functions and are injectable
 * constructor params purely so a test can exercise every branch - including the fail-closed one -
 * without a real Android Keystore.** [KeyVault]'s own doc comment already establishes that cannot
 * be done outside a real device.
 *
 * **Fail-closed, not fallback-to-plaintext**: if [encrypt] returns null (a broken Keystore - cheap
 * head units ship flaky keymaster HALs, per [KeyVault]'s own doc comment), [saveSession] stores
 * NOTHING and returns false rather than writing the raw token to disk. A device-scoped credential
 * silently degrading to plaintext is a worse failure than asking the driver to sign in again once
 * storage is healthy.
 *
 * Ported from LEGION's `com.kevin.legion.backend.engine.EngineConfig`, whose class doc gives the
 * identical reasoning against `com.kevin.legion.backend.SupabaseConfig`/`SupabaseSession` - this
 * app has no Supabase client to cross-reference, so those comparisons were dropped rather than
 * pointing at classes that do not exist here.
 */
/** Which sign-in this unit holds: none, a member's (typed here), or the car's own (car link). */
enum class EngineSignIn { NONE, MEMBER, CAR_LINK }

class EngineConfig(
    context: Context,
    private val encrypt: (String) -> String? = KeyVault::encrypt,
    private val decrypt: (String) -> String? = KeyVault::decrypt,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The saved engine base URL, or blank if never configured. Reads are cheap and synchronous -
     * no network, no suspend. */
    fun baseUrl(): String = prefs.getString(KEY_BASE_URL, "").orEmpty()

    /**
     * The signed-in device's decrypted token, or null if never signed in, signed out, or the
     * stored blob no longer decrypts (a Keystore key lost across an OS wipe). **Never logged** -
     * no caller in this file or [EngineAuth] writes this value to Log/println; the only place it
     * leaves this class is the `Authorization` header [EngineAuth]/[EngineHttp] attach to a
     * request.
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

    /**
     * The sign-in this unit holds (2026-11-20). CAR_LINK is entered ONLY by a token handed over the
     * car link ([saveSessionStrict] with `carLink`); a member signing in on the unit leaves it.
     */
    fun signIn(): EngineSignIn = when {
        !isSignedIn() -> EngineSignIn.NONE
        isCarLinkSignIn() -> EngineSignIn.CAR_LINK
        else -> EngineSignIn.MEMBER
    }

    /** True when the stored token is the car's own, handed over the link. No decrypt. */
    fun isCarLinkSignIn(): Boolean = prefs.getString(KEY_KIND, null) == KIND_CAR_LINK

    /** The ONE engine vehicle the car's own sign-in may upload OBD samples for (from the hand-over). */
    fun carLinkVehicleId(): String? = prefs.getString(KEY_VEHICLE_ID, null)?.takeIf { it.isNotBlank() }

    /** The member id the car's own sign-in was minted for, in use or in reserve. Plain, a UUID. */
    fun carLinkUserId(): String? =
        (if (isCarLinkSignIn()) userId() else prefs.getString(KEY_RESERVE_USER, null))?.takeIf { it.isNotBlank() }

    /** The engine answered 401 to the car's sign-in: nothing more is sent until a new hand-over. */
    fun carLinkRefused(): Boolean = prefs.getString(KEY_REFUSED, null) != null

    /** A car token from a pairing waits here while a member is signed in (review F1). */
    fun hasCarLinkReserve(): Boolean = prefs.contains(KEY_RESERVE_TOKEN)

    /** The car's own token as stored, active or in reserve (ciphertext), for the car link's provenance. */
    fun carLinkRawToken(): String? =
        if (isCarLinkSignIn()) prefs.getString(KEY_TOKEN, null) else prefs.getString(KEY_RESERVE_TOKEN, null)

    /** Removes the car's own sign-in, active or in reserve; a member sign-in stays. With commit(). */
    fun clearCarLink(): Boolean {
        val editor = prefs.edit().remove(KEY_RESERVE_TOKEN).remove(KEY_RESERVE_USER).remove(KEY_RESERVE_VEHICLE).remove(KEY_RESERVE_BASE)
        if (isCarLinkSignIn()) {
            editor.remove(KEY_TOKEN).remove(KEY_USER_ID).remove(KEY_KIND).remove(KEY_VEHICLE_ID).remove(KEY_REFUSED)
        }
        return editor.commit()
    }

    /** The `Authorization` header this device's token makes, or null when it holds none. */
    fun authorization(): String? = token()?.takeIf { it.isNotBlank() }?.let { "$TOKEN_SCHEME $it" }

    /**
     * The engine refused the car's own sign-in. [sentAuthorization] is the header the refused
     * request carried, and the mark is made only when that is still the token this device holds:
     * the phone replaces the car's sign-in over the link (5.6) while uploads are in flight, and a
     * 401 for the credential it replaced would otherwise stop the car and ask for a re-pair with a
     * perfectly good sign-in on disk. False = nothing was marked.
     */
    @Synchronized
    fun markCarLinkRefused(sentAuthorization: String?): Boolean {
        if (!isCarLinkSignIn()) return false
        if (sentAuthorization == null || sentAuthorization != authorization()) return false
        return prefs.edit().putString(KEY_REFUSED, "1").commit()
    }

    /** Saves [url] only if it passes [isValidBaseUrl]; otherwise saves nothing and returns false -
     * distinguishing "saved" from "rejected" rather than silently leaving prefs half-written. */
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
            // A member signing in on the unit: not the car's own sign-in any more.
            remove(KEY_KIND)
            remove(KEY_VEHICLE_ID)
            remove(KEY_REFUSED)
        }
        return true
    }

    // --- Car link (HU-1 T3, review M7): strict, all-or-nothing writes ---------------------------

    /** The raw stored values (ciphertext for the token), for snapshot and restore. */
    fun snapshot(): Map<String, String?> =
        listOf(
            KEY_BASE_URL, KEY_TOKEN, KEY_USER_ID, KEY_KIND, KEY_VEHICLE_ID, KEY_REFUSED,
            KEY_RESERVE_TOKEN, KEY_RESERVE_USER, KEY_RESERVE_VEHICLE, KEY_RESERVE_BASE,
        ).associateWith { prefs.getString(it, null) }

    /** Writes [snapshot] back exactly (null = removed) with commit(). */
    fun restore(snapshot: Map<String, String?>): Boolean {
        val editor = prefs.edit()
        snapshot.forEach { (k, v) -> if (v == null) editor.remove(k) else editor.putString(k, v) }
        return editor.commit()
    }

    /** The token's ciphertext as stored. */
    fun rawToken(): String? = prefs.getString(KEY_TOKEN, null)

    /**
     * Encrypts and saves the session with commit(); false and nothing written on any failure.
     *
     * Synchronized with [markCarLinkRefused]: a renewed sign-in arriving over the link and a 401
     * for the one it replaces are exactly the pair that race, and this write is what clears
     * [KEY_REFUSED].
     */
    @Synchronized
    fun saveSessionStrict(
        baseUrl: String,
        token: String,
        userId: String,
        carLink: Boolean = false,
        vehicleId: String? = null,
    ): Boolean {
        val encrypted = encrypt(token) ?: return false
        // Review F1 (orchestrator's call): a member signed in on the unit stays in use; the car's
        // token waits in reserve, used only once no member sign-in exists (see clearSession).
        if (carLink && isSignedIn() && !isCarLinkSignIn()) {
            return prefs.edit()
                .putString(KEY_RESERVE_TOKEN, encrypted)
                .putString(KEY_RESERVE_USER, userId)
                .putString(KEY_RESERVE_VEHICLE, vehicleId?.takeIf { it.isNotBlank() })
                .putString(KEY_RESERVE_BASE, baseUrl.trim())
                .commit()
        }
        return prefs.edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_TOKEN, encrypted)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_KIND, if (carLink) KIND_CAR_LINK else null)
            .putString(KEY_VEHICLE_ID, if (carLink) vehicleId?.takeIf { it.isNotBlank() } else null)
            .remove(KEY_REFUSED)
            .commit()
    }

    /** Clears the device token and user id. Does NOT touch [baseUrl] - a driver signing out of one
     * account on this device should not have to re-type where the engine lives. */
    fun clearSession() {
        // A car token waiting in reserve takes over, but only for the engine it was minted for.
        val reserve = prefs.getString(KEY_RESERVE_TOKEN, null)
        if (reserve != null && !isCarLinkSignIn() && prefs.getString(KEY_RESERVE_BASE, null) == baseUrl()) {
            prefs.edit()
                .putString(KEY_TOKEN, reserve)
                .putString(KEY_USER_ID, prefs.getString(KEY_RESERVE_USER, null))
                .putString(KEY_KIND, KIND_CAR_LINK)
                .putString(KEY_VEHICLE_ID, prefs.getString(KEY_RESERVE_VEHICLE, null))
                .remove(KEY_REFUSED)
                .remove(KEY_RESERVE_TOKEN).remove(KEY_RESERVE_USER).remove(KEY_RESERVE_VEHICLE).remove(KEY_RESERVE_BASE)
                .commit()
            return
        }
        write {
            remove(KEY_RESERVE_TOKEN)
            remove(KEY_RESERVE_USER)
            remove(KEY_RESERVE_VEHICLE)
            remove(KEY_RESERVE_BASE)
            remove(KEY_TOKEN)
            remove(KEY_USER_ID)
            remove(KEY_KIND)
            remove(KEY_VEHICLE_ID)
            remove(KEY_REFUSED)
        }
    }

    /** One funnel - every write to this store passes through here, so a future caller cannot add
     * a second `prefs.edit()` call site that forgets `.apply()` or writes a key this class does
     * not otherwise know about. */
    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
    }

    companion object {
        /** DRF's own token scheme, the one [authorization] builds and every request carries. */
        const val TOKEN_SCHEME = "Token"
        private const val PREFS = "engine_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "device_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_KIND = "sign_in_kind"
        private const val KIND_CAR_LINK = "car_link"
        private const val KEY_VEHICLE_ID = "car_link_vehicle_id"
        private const val KEY_REFUSED = "car_link_refused"
        private const val KEY_RESERVE_TOKEN = "car_link_reserve_token"
        private const val KEY_RESERVE_USER = "car_link_reserve_user"
        private const val KEY_RESERVE_VEHICLE = "car_link_reserve_vehicle"
        private const val KEY_RESERVE_BASE = "car_link_reserve_base"

        /**
         * Loose shape check - `http://` or `https://` plus a non-blank host, deliberately loose
         * (no port/path/charset validation): the point is catching a blank or garbage field, not
         * validating URL syntax a real request will reject on its own the moment it is made.
         */
        fun isValidBaseUrl(url: String): Boolean {
            val trimmed = url.trim()
            val withoutScheme = when {
                trimmed.startsWith("http://") -> trimmed.removePrefix("http://")
                trimmed.startsWith("https://") -> trimmed.removePrefix("https://")
                else -> return false
            }
            if (withoutScheme.substringBefore("/").substringBefore(":").isBlank()) return false
            // The host as the HTTP client will read it (a hand-split string can be fooled by
            // user-info such as http://10.0.0.2:80@public.example/), and no user-info at all.
            val parsed = okhttp3.HttpUrl.Companion.run { trimmed.toHttpUrlOrNull() } ?: return false
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
            val host = parsed.host
            // Recheck 2026-11-20: the unit's Android does not block cleartext, so plain http is
            // refused except to a private LAN address (a household engine on the hotspot or at
            // home), where the screen says in words that it is not encrypted.
            return trimmed.startsWith("https://") || isPrivateLanHost(host)
        }

        /** True for an http address the Engine screen must label "not encrypted". */
        fun isUnencrypted(url: String): Boolean = url.trim().startsWith("http://")

        /**
         * RFC 1918 IPv4 literals (10/8, 172.16/12, 192.168/16) and this device's own loopback;
         * any other name never counts.
         */
        private val CANONICAL_OCTET = Regex("0|[1-9][0-9]{0,2}")

        fun isPrivateLanHost(host: String): Boolean {
            if (host == "localhost") return true
            // Review P3: canonical dotted decimal only. "0172" or "01" would read as octal to any
            // resolver with inet_aton rules, i.e. a different (public) address than the one checked.
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { !CANONICAL_OCTET.matches(it) }) return false
            val o = parts.map { it.toInt() }
            if (o.any { it !in 0..255 }) return false
            return o[0] == 10 || o[0] == 127 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
        }
    }
}
