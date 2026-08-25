package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.ai.KeyVault
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Thrown by [SupabaseSession.saveSession] when [KeyVault] encryption fails. The auth-kt plugin
 * calls `sessionManager.saveSession(...)` inline, in the same suspend call as `signInWith`/
 * `refreshCurrentSession` (traced in `AuthImpl.importSession`, no coroutine hop, no try/catch
 * around the call), so this exception genuinely propagates out to whatever caller triggered the
 * sign-in or refresh - it is not swallowed by the plugin, which is what makes it a usable signal
 * rather than a log line nobody reads.
 */
class SessionPersistFailedException(message: String) : Exception(message)

/**
 * Thrown by [SupabaseSession.loadSession] when there is nothing usable in storage - never saved,
 * or a corrupt/undecryptable blob. **auth-kt 3.6.0 (the version this module is pinned to - see
 * `gradle/libs.versions.toml`'s `supabaseKt` comment for why) has no dedicated
 * `NoSessionFoundException`**; its own [io.github.jan.supabase.auth.MemorySessionManager] just
 * throws a bare `IllegalStateException("No session stored")` from [SessionManager.loadSession],
 * and [SessionManager.loadSessionOrNull]'s default implementation catches plain `Exception`. This
 * mirrors that exact shape rather than inventing a library-shaped type.
 */
class NoSupabaseSessionException : IllegalStateException("No session found in storage")

/**
 * Session storage for the household's Supabase account, backing [io.github.jan.supabase.auth.Auth]'s
 * [SessionManager] plugin point.
 *
 * **This is the one deliberate departure from every other BYO secret slot in this codebase**
 * (ticket 02 ruling 5). [com.kevin.legion.ai.CompanionProfile.saveGeminiKey] and its Shelly/
 * Spotify siblings all fall back to PLAINTEXT storage when [KeyVault.encrypt] fails - that
 * fallback exists because cheap head-unit keymaster HALs were genuinely flaky, and a bricked key
 * entry was judged worse than a plaintext one sitting on-device. Phone-only killed the reason
 * (CLAUDE.md §2's "phone-only" pivot) and this session is not like those other secrets in the
 * first place: a Gemini key limits blast radius to one Google account's quota, a leaked Supabase
 * refresh token is a standing credential to **every row of the household's ERP** - fleet,
 * ledger, pantry, dates, everything Phase 4 onward migrates. **If [KeyVault.encrypt] returns
 * null, [saveSession] stores NOTHING and throws [SessionPersistFailedException]** rather than
 * writing the session in the clear. The cost is real and is stated plainly rather than hidden: a
 * device with a broken Keystore cannot keep a Supabase session across a process restart, and will
 * have to sign in again every time. That is the correct trade for a credential this wide.
 *
 * [encrypt]/[decrypt] are injectable (default to [KeyVault]'s real functions) purely so a test can
 * simulate a Keystore failure without trying to break the real Android Keystore, which
 * [KeyVault]'s own doc comment already establishes cannot be done outside a real device.
 *
 * The stored blob is the [UserSession] itself, JSON-encoded via its own `@Serializable` shape
 * (kotlinx-serialization, not a hand-rolled subset of its fields) and then handed to [KeyVault] -
 * reusing [UserSession.serializer()] rather than reconstructing [io.github.jan.supabase.auth.user.UserInfo]'s
 * many optional fields by hand.
 */
class SupabaseSession(
    private val context: Context,
    private val encrypt: (String) -> String? = KeyVault::encrypt,
    private val decrypt: (String) -> String? = KeyVault::decrypt,
) : SessionManager {

    companion object {
        private const val PREFS = "supabase_session"
        private const val KEY_SESSION_ENC = "session_enc"
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * True after the most recent [saveSession] call actually persisted, false if it failed
     * closed. Defaults true (no save attempted yet is not a failure). Read this after a sign-in
     * completes to tell the driver, in words, whether the session will survive a restart -
     * [SupabaseAuth.signIn] surfaces it as [SignInResult.SucceededButNotPersisted].
     */
    @Volatile
    var lastSaveSucceeded: Boolean = true
        private set

    private fun prefs() =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun saveSession(session: UserSession) {
        val blob = json.encodeToString(UserSession.serializer(), session)
        val enc = encrypt(blob)
        if (enc == null) {
            lastSaveSucceeded = false
            throw SessionPersistFailedException(
                "Keystore encryption failed - the session was not written to storage. " +
                    "Sign-in against Supabase succeeded, but this device will need to sign in " +
                    "again after a restart."
            )
        }
        prefs().edit().putString(KEY_SESSION_ENC, enc).apply()
        lastSaveSucceeded = true
    }

    override suspend fun loadSession(): UserSession {
        val enc = prefs().getString(KEY_SESSION_ENC, null) ?: throw NoSupabaseSessionException()
        val blob = decrypt(enc) ?: throw NoSupabaseSessionException()
        return try {
            json.decodeFromString(UserSession.serializer(), blob)
        } catch (e: SerializationException) {
            // A corrupt or format-mismatched blob is the same "nothing usable in storage" case
            // as never having saved one.
            throw NoSupabaseSessionException()
        }
    }

    override suspend fun deleteSession() {
        prefs().edit().remove(KEY_SESSION_ENC).apply()
        lastSaveSucceeded = true
    }
}
