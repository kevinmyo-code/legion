package com.kevin.midnightai.link

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.kevin.midnightai.ai.KeyVault
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject

/**
 * The owner PIN (Kevin, 2026-11-20). It guards Forget, Pair again, and showing or replacing the
 * engine sign-in (and revealing a typed Gemini key), once a pairing has carried an engine token.
 * Exactly [DIGITS] digits typed on the unit's own pad. Stored only as a salted PBKDF2 hash, and that
 * record is itself Keystore-encrypted ([encrypt]; no plaintext fallback: if the Keystore fails, no
 * PIN is set and the screen says so). Never asked while the car moves ([OwnerPinGate]).
 *
 * Lockout (review round 5964b74c, B2): every [MAX_TRIES] wrong entries lock it; wrong entries count
 * across locks (only the right PIN starts the count again) and each lock doubles, from [LOCK_MS] up
 * to [MAX_LOCK_MS]. The lock runs on the MONOTONIC clock ([elapsedMs]); the wall clock is never read,
 * so a date set forward, or the unit's clock being corrected after a power-off, cannot end it. After
 * a reboot ([bootId] changed, the monotonic clock restarted) the lock starts again in full.
 *
 * A full reset of the car link wipes the pairing, the engine token and this PIN together
 * ([LinkKeyStore.forget]); nothing secret survives it, which is why a forgotten PIN is fixed that way.
 * Decrypting and hashing run off the main thread; [isSet] is a plain prefs lookup and is not.
 */
@OptIn(ExperimentalEncodingApi::class)
class OwnerPin(
    context: Context,
    private val encrypt: (String) -> String? = KeyVault::encrypt,
    private val decrypt: (String) -> String? = KeyVault::decrypt,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val bootId: () -> String = { bootCount(context) },
    private val iterations: Int = ITERATIONS,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    sealed interface Check {
        data object Ok : Check
        data class Wrong(val triesLeft: Int) : Check
        /** Locked for [remainingMs] more on the monotonic clock. */
        data class Locked(val remainingMs: Long) : Check
        /** A PIN record exists but the Keystore cannot read it: treated as locked, never as open. */
        data object Unreadable : Check
        data object NotSet : Check
    }

    fun isSet(): Boolean = prefs.contains(KEY_RECORD)

    /** Sets (or replaces) the PIN. False, nothing written, for a PIN that is not 6 digits or a broken Keystore. */
    fun set(pin: String): Boolean {
        if (!isValid(pin)) return false
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt, iterations)
        val record = JSONObject()
            .put("v", 1)
            .put("salt", Base64.Default.encode(salt))
            .put("hash", Base64.Default.encode(hash))
            .put("iter", iterations)
        hash.fill(0)
        val enc = encrypt(record.toString()) ?: return false
        return prefs.edit().putString(KEY_RECORD, enc).putInt(KEY_FAILS, 0).clearLock().commit()
    }

    /** Remaining lock on the monotonic clock, 0 when not locked. A new boot re-locks in full. */
    private fun remainingLock(): Long {
        val duration = prefs.getLong(KEY_LOCK_MS, 0L)
        if (duration <= 0L) return 0L
        val now = elapsedMs()
        if (prefs.getString(KEY_LOCK_BOOT, null) != bootId()) {
            prefs.edit().putLong(KEY_LOCK_UNTIL, now + duration).putString(KEY_LOCK_BOOT, bootId()).commit()
            return duration
        }
        val remaining = (prefs.getLong(KEY_LOCK_UNTIL, 0L) - now).coerceAtMost(duration)
        if (remaining <= 0L) {
            // Served: forget it, so a later reboot does not lock again (the wrong-entry count stays).
            prefs.edit().clearLock().commit()
            return 0L
        }
        return remaining
    }

    fun check(pin: String): Check {
        val raw = prefs.getString(KEY_RECORD, null) ?: return Check.NotSet
        remainingLock().takeIf { it > 0L }?.let { return Check.Locked(it) }
        val record = decrypt(raw)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Check.Unreadable
        val salt = runCatching { Base64.Default.decode(record.getString("salt")) }.getOrNull() ?: return Check.Unreadable
        val want = runCatching { Base64.Default.decode(record.getString("hash")) }.getOrNull() ?: return Check.Unreadable
        val got = hash(pin, salt, record.optInt("iter", iterations))
        val ok = MessageDigest.isEqual(got, want)
        got.fill(0)
        if (ok) {
            prefs.edit().putInt(KEY_FAILS, 0).clearLock().commit()
            return Check.Ok
        }
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        if (fails % MAX_TRIES != 0) {
            prefs.edit().putInt(KEY_FAILS, fails).commit()
            return Check.Wrong(MAX_TRIES - fails % MAX_TRIES)
        }
        val lock = lockFor(fails / MAX_TRIES)
        prefs.edit()
            .putInt(KEY_FAILS, fails)
            .putLong(KEY_LOCK_MS, lock)
            .putLong(KEY_LOCK_UNTIL, elapsedMs() + lock)
            .putString(KEY_LOCK_BOOT, bootId())
            .commit()
        return Check.Locked(lock)
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private fun SharedPreferences.Editor.clearLock(): SharedPreferences.Editor =
        remove(KEY_LOCK_MS).remove(KEY_LOCK_UNTIL).remove(KEY_LOCK_BOOT)

    companion object {
        const val PREFS = "owner_pin"
        const val DIGITS = 6
        const val MIN_DIGITS = DIGITS
        const val MAX_DIGITS = DIGITS
        const val MAX_TRIES = 5
        const val LOCK_MS = 10 * 60_000L
        const val MAX_LOCK_MS = 24 * 60 * 60_000L
        private const val ITERATIONS = 20_000
        private const val SALT_BYTES = 16
        private const val KEY_RECORD = "pin_enc"
        private const val KEY_FAILS = "fails"
        private const val KEY_LOCK_MS = "lock_ms"
        private const val KEY_LOCK_UNTIL = "lock_until_elapsed"
        private const val KEY_LOCK_BOOT = "lock_boot"

        fun isValid(pin: String): Boolean = pin.length == DIGITS && pin.all { it in '0'..'9' }

        /** The n-th lock (1-based): 10 min, 20, 40, ... never above [MAX_LOCK_MS]. */
        fun lockFor(n: Int): Long {
            var lock = LOCK_MS
            repeat((n - 1).coerceAtLeast(0)) {
                lock = (lock * 2).coerceAtMost(MAX_LOCK_MS)
            }
            return lock
        }

        /** The boot count (API 24+), so a reboot is told apart from an app restart. */
        private fun bootCount(context: Context): String =
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }
                .getOrDefault("unknown")

        private fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        /** Words for a check result, for the pad. */
        fun words(check: Check): String = when (check) {
            Check.Ok -> "PIN accepted."
            is Check.Wrong -> "That PIN did not match. Tries left before a lock: ${check.triesLeft}."
            is Check.Locked -> "Too many wrong PINs. Try again in ${minutesText(check.remainingMs)}."
            Check.Unreadable -> "This unit cannot read its owner PIN right now. Try again, or reset the car link."
            Check.NotSet -> "No owner PIN is set."
        }

        private fun minutesText(ms: Long): String {
            val minutes = (ms + 59_999) / 60_000
            return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
        }
    }
}

/** When an action the PIN guards may go ahead (pure; the screens ask it). */
object OwnerPinGate {
    enum class Gate { ALLOWED, NEEDS_PIN, PARK_FIRST }

    /** No PIN set: allowed (hold-to-confirm still applies). PIN set: never asked while moving. */
    fun gate(pinSet: Boolean, driving: Boolean): Gate = when {
        !pinSet -> Gate.ALLOWED
        driving -> Gate.PARK_FIRST
        else -> Gate.NEEDS_PIN
    }

    const val PARK_FIRST = "Park to do this: the owner PIN is never asked while the car moves."
}
