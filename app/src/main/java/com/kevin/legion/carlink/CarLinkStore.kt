package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.LinkCrypto
import com.kevin.legion.carlink.protocol.LinkKeyPersistence

/** Encrypts and decrypts with a key that never leaves the device. Null = the Keystore failed. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray?
    fun open(blob: ByteArray): ByteArray?
}

/** One prefs file written all at once: a null value removes the key. False = nothing changed. */
interface RecordFile {
    fun read(): Map<String, String>
    fun writeAll(values: Map<String, String?>): Boolean
}

/** What [CarLinkStore.load] found. Not data classes: they hold keys. */
sealed interface StoredLink {
    object NotPaired : StoredLink
    class Paired(val current: ByteArray, val reserve: ByteArray?) : StoredLink {
        override fun toString() = "Paired"
    }
    /** A record exists but its key cannot be read: send nothing, ask to pair again (3.7). */
    object Broken : StoredLink
}

/**
 * The phone's car link record, `car_link.xml`: the current link key, the reserve key (3.7), the unit
 * token's engine id for revocation (5.4) and two switches. Excluded from backup and device transfer.
 *
 * Every secret is sealed with the Android Keystore before anything is written; if any value cannot
 * be sealed nothing is written at all, and there is no plaintext fallback. Reads are tried three
 * times, and a read failure never deletes anything. Each key is registered with the log redactor
 * ([redact]) when it is read and when it is written.
 */
class CarLinkStore(
    private val box: SecretBox,
    private val file: RecordFile,
    private val redact: (String) -> Unit,
) : LinkKeyPersistence {

    fun load(): StoredLink {
        val values = file.read()
        val curBlob = values[CURRENT]
        val current = curBlob?.let(::openWithRetry)
        // An unreadable reserve is not "broken": the current key rules, the reserve only serves a revert.
        val reserve = current?.let { values[RESERVE]?.let(::openWithRetry) }
        current?.let(::register)
        reserve?.let(::register)
        return when {
            current != null -> StoredLink.Paired(current, reserve)
            curBlob != null || values.containsKey(RESERVE) -> StoredLink.Broken
            else -> StoredLink.NotPaired
        }
    }

    override fun write(current: ByteArray?, reserve: ByteArray?): Boolean {
        val cur = current?.let(::sealed)
        val res = reserve?.let(::sealed)
        // All or nothing: a value the Keystore could not seal means nothing is written.
        if ((current != null && cur == null) || (reserve != null && res == null)) return false
        current?.let(::register)
        reserve?.let(::register)
        val clearing = current == null
        return file.writeAll(
            buildMap {
                put(CURRENT, cur)
                put(RESERVE, res)
                if (clearing) {
                    put(UNIT_DEVICE_ID, null)
                    put(SEND_LOCATION, null)
                    put(CAR_GEMINI, null)
                }
            },
        )
    }

    fun revokeOnlyPending(): Boolean = false

    fun writeRevokeOnly(key: ByteArray, sinceMs: Long): Boolean = false

    /**
     * The car's OWN Gemini key (5.3, rev 5): a separate, restricted key pasted for the unit, sealed like
     * the link keys. Never the phone's key, never prefilled, never logged. Null when not set.
     */
    fun carGeminiKey(): String? {
        val blob = file.read()[CAR_GEMINI] ?: return null
        val plain = openWithRetry(blob) ?: return null
        return String(plain, Charsets.UTF_8).also { plain.fill(0); redact(it) }
    }

    /** Blank clears it. False = not saved (the Keystore could not seal it; nothing was written). */
    fun setCarGeminiKey(key: String?): Boolean {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isEmpty()) return file.writeAll(mapOf(CAR_GEMINI to null))
        redact(trimmed)
        val sealed = sealed(trimmed.toByteArray(Charsets.UTF_8)) ?: return false
        return file.writeAll(mapOf(CAR_GEMINI to sealed))
    }

    fun pairingNetwork(): PairingNetwork? = null

    fun setPairingNetwork(n: PairingNetwork?): Boolean = false

    fun unitDeviceId(): String? = file.read()[UNIT_DEVICE_ID]

    fun setUnitDeviceId(id: String?): Boolean = file.writeAll(mapOf(UNIT_DEVICE_ID to id))

    fun sendLocation(): Boolean = file.read()[SEND_LOCATION] == "1"

    fun setSendLocation(on: Boolean): Boolean = file.writeAll(mapOf(SEND_LOCATION to if (on) "1" else null))

    private fun sealed(plain: ByteArray): String? = box.seal(plain)?.let(LinkCrypto::b64)

    private fun openWithRetry(blob: String): ByteArray? {
        val bytes = LinkCrypto.unb64(blob) ?: return null
        return (1..READ_TRIES).firstNotNullOfOrNull { box.open(bytes) }
    }

    private fun register(key: ByteArray) {
        redact(LinkCrypto.b64(key))
        redact(key.joinToString("") { "%02x".format(it) })
    }

    companion object {
        const val FILE = "car_link"
        const val READ_TRIES = 3
        private const val CURRENT = "link_current"
        private const val RESERVE = "link_reserve"
        private const val UNIT_DEVICE_ID = "unit_device_id"
        private const val SEND_LOCATION = "send_location"
        private const val CAR_GEMINI = "car_gemini_key"
    }
}
