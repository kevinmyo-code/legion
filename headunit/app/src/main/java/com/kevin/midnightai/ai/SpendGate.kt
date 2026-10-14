package com.kevin.midnightai.ai

import android.content.Context
import java.security.MessageDigest

/**
 * The courtesy lock on the build sheet's *money* layer (per-entry costs + the
 * grand total). NOT real security - it's a playful, social gate so a nosy
 * passenger can't get the companion to read out how much has been spent. The
 * build *history* (what was done, when) stays open; only dollar figures are gated.
 *
 * How it works: the owner sets a spoken/typed passphrase (stored only as a
 * SHA-256 hash in [CompanionProfile], never plaintext). Giving the correct phrase
 * unlocks the money layer for [UNLOCK_TTL_MS], after which it re-locks on its own.
 * There is no speaker recognition - the passphrase IS the gate. If no passphrase
 * has been set, the gate is OFF (money is open); the owner opts in by setting one.
 *
 * Note this is one in-memory unlock flag for the whole app; the parked-mode app
 * view and the voice path both consult [isUnlocked], so the lock can't be
 * bypassed by simply opening the app.
 */
object SpendGate {
    private const val UNLOCK_TTL_MS = 5 * 60 * 1000L

    @Volatile private var unlockedUntil = 0L

    /** True once the owner has opted into the lock by setting a passphrase. */
    fun hasPassphrase(context: Context): Boolean =
        CompanionProfile.spendPassphraseHash(context).isNotBlank()

    /**
     * Whether the money layer is currently readable: open if no passphrase is set,
     * otherwise only within the unlock window after a correct passphrase.
     */
    fun isUnlocked(context: Context): Boolean =
        !hasPassphrase(context) || System.currentTimeMillis() < unlockedUntil

    /** Sets (or changes) the passphrase and unlocks for the current window. */
    fun setPassphrase(context: Context, phrase: String) {
        CompanionProfile.saveSpendPassphraseHash(context, hash(phrase))
        unlockedUntil = System.currentTimeMillis() + UNLOCK_TTL_MS
    }

    /** Returns true and unlocks if [phrase] matches the set passphrase. */
    fun unlock(context: Context, phrase: String): Boolean {
        val stored = CompanionProfile.spendPassphraseHash(context)
        val ok = stored.isNotBlank() && hash(phrase) == stored
        if (ok) unlockedUntil = System.currentTimeMillis() + UNLOCK_TTL_MS
        return ok
    }

    /** Re-locks immediately (e.g. owner says "lock it back up"). */
    fun lock() { unlockedUntil = 0L }

    // Normalize (trim + lowercase) so a spoken phrase matches regardless of
    // casing/incidental spacing, then SHA-256 to a hex string.
    private fun hash(phrase: String): String {
        val normalized = phrase.trim().lowercase().replace(Regex("\\s+"), " ")
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
