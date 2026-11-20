package com.kevin.midnightai.link

import android.content.Context
import android.content.SharedPreferences
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
 * engine sign-in, once a pairing has carried an engine token. 4 to 6 digits typed on the unit's own
 * pad. Stored only as a salted PBKDF2 hash, and that record is itself Keystore-encrypted ([encrypt];
 * no plaintext fallback: if the Keystore fails, no PIN is set and the screen says so). Never asked
 * while the car moves ([OwnerPinGate]). [MAX_TRIES] wrong entries lock it for [LOCK_MS]. A full reset
 * of the car link wipes the pairing, the engine token and this PIN together
 * ([LinkKeyStore.forget]); nothing secret survives it, which is why a forgotten PIN is fixed that way.
 *
 * Decrypting and hashing run off the main thread.
 */
@OptIn(ExperimentalEncodingApi::class)
class OwnerPin(
    context: Context,
    private val encrypt: (String) -> String? = KeyVault::encrypt,
    private val decrypt: (String) -> String? = KeyVault::decrypt,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val iterations: Int = ITERATIONS,
) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    sealed interface Check {
        data object Ok : Check
        data class Wrong(val triesLeft: Int) : Check
        data class Locked(val untilMs: Long) : Check
        /** A PIN record exists but the Keystore cannot read it: treated as locked, never as open. */
        data object Unreadable : Check
        data object NotSet : Check
    }

    fun isSet(): Boolean = prefs.contains(KEY_RECORD)

    /** Sets (or replaces) the PIN. False, nothing written, for a PIN that is not 4 to 6 digits or a broken Keystore. */
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
        return prefs.edit().putString(KEY_RECORD, enc).putInt(KEY_FAILS, 0).remove(KEY_LOCKED_UNTIL).commit()
    }

    fun check(pin: String): Check {
        val raw = prefs.getString(KEY_RECORD, null) ?: return Check.NotSet
        val now = nowMs()
        var lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        // The unit's clock can come back hours wrong after a power-off: a lock never lasts longer
        // than LOCK_MS from now, whichever way the clock jumped.
        if (lockedUntil - now > LOCK_MS) {
            lockedUntil = now + LOCK_MS
            prefs.edit().putLong(KEY_LOCKED_UNTIL, lockedUntil).commit()
        }
        if (now < lockedUntil) return Check.Locked(lockedUntil)
        val record = decrypt(raw)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Check.Unreadable
        val salt = runCatching { Base64.Default.decode(record.getString("salt")) }.getOrNull() ?: return Check.Unreadable
        val want = runCatching { Base64.Default.decode(record.getString("hash")) }.getOrNull() ?: return Check.Unreadable
        val got = hash(pin, salt, record.optInt("iter", iterations))
        val ok = isValid(pin) && MessageDigest.isEqual(got, want)
        got.fill(0)
        if (ok) {
            prefs.edit().putInt(KEY_FAILS, 0).remove(KEY_LOCKED_UNTIL).commit()
            return Check.Ok
        }
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        return if (fails >= MAX_TRIES) {
            val until = now + LOCK_MS
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, until).commit()
            Check.Locked(until)
        } else {
            prefs.edit().putInt(KEY_FAILS, fails).commit()
            Check.Wrong(MAX_TRIES - fails)
        }
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        const val PREFS = "owner_pin"
        const val MIN_DIGITS = 4
        const val MAX_DIGITS = 6
        const val MAX_TRIES = 5
        const val LOCK_MS = 10 * 60_000L
        private const val ITERATIONS = 20_000
        private const val SALT_BYTES = 16
        private const val KEY_RECORD = "pin_enc"
        private const val KEY_FAILS = "fails"
        private const val KEY_LOCKED_UNTIL = "locked_until"

        fun isValid(pin: String): Boolean = pin.length in MIN_DIGITS..MAX_DIGITS && pin.all { it in '0'..'9' }

        private fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        /** Words for a check result, for the pad. */
        fun words(check: Check, nowMs: Long): String = when (check) {
            Check.Ok -> "PIN accepted."
            is Check.Wrong -> "That PIN did not match. Tries left: ${check.triesLeft}."
            is Check.Locked -> "Too many wrong PINs. Try again in ${((check.untilMs - nowMs).coerceAtLeast(0) + 59_999) / 60_000} min."
            Check.Unreadable -> "This unit cannot read its owner PIN right now. Try again, or reset the car link."
            Check.NotSet -> "No owner PIN is set."
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
