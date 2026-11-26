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
 * A minted unit sign-in the unit has not yet said it stored. Not a data class: it holds a token.
 *
 * [replaces] is the credential it was minted to replace, which is the pairing it belongs to: a
 * record whose [replaces] is no longer the car's unit id was left behind by a pairing that is over,
 * and handing it to the unit now would bind the car to the pairing before it (RED review round 3).
 */
class PendingUnitToken(val id: String, val token: String, val vehicleId: String?, val replaces: String) {
    override fun toString() = "PendingUnitToken($id)"
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
// One record file: link keys, the revoke-only record, the car's key, the pairing network, the switches.
@Suppress("TooManyFunctions")
class CarLinkStore(
    private val box: SecretBox,
    private val file: RecordFile,
    private val redact: (String) -> Unit,
) : LinkKeyPersistence {

    fun load(): StoredLink {
        val values = file.read()
        // The retired "Car's own Gemini key" field (5.3 rev 8) and its earlier switch: removed once.
        if (RETIRED.any(values::containsKey)) file.writeAll(RETIRED.associateWith { null })
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
        if (!sealedOk(current, cur) || !sealedOk(reserve, res)) return false
        current?.let(::register)
        reserve?.let(::register)
        val clearing = current == null
        // 5.6: a pending renewal belongs to the link key it was minted under, so any write that
        // leaves a DIFFERENT key current ends it: a new pairing, a revert, or a stored key this
        // device can no longer read. Deciding it here is what keeps the record from outliving its
        // pairing however the keys were written (RED review round 3: "Pair again" left it behind
        // and the next pass handed the new unit the old pairing's token).
        val endsRenewal = clearing || !sameCurrentKey(current)
        return file.writeAll(
            buildMap {
                put(CURRENT, cur)
                put(RESERVE, res)
                if (endsRenewal) {
                    put(PENDING_ID, null)
                    put(PENDING_TOKEN, null)
                    put(PENDING_VEHICLE, null)
                    put(PENDING_FOR, null)
                }
                if (clearing) {
                    put(UNIT_DEVICE_ID, null)
                    put(SEND_LOCATION, null)
                    put(REVOKE_ONLY, null)
                    put(REVOKE_SINCE, null)
                    put(PAIRING_NET, null)
                }
            },
        )
    }

    /** True only when [current] is byte for byte the key already stored. Unreadable = not the same. */
    private fun sameCurrentKey(current: ByteArray?): Boolean {
        if (current == null) return false
        val stored = file.read()[CURRENT] ?: return false
        return openWithRetry(stored)?.contentEquals(current) == true
    }

    /** A REVOKE-only record exists (5.4, rev 5): a forgotten pairing whose unit was not yet told. */
    fun revokeOnlyPending(): Boolean = file.read().containsKey(REVOKE_ONLY)

    /** When the revoke-only record was made (wall clock, for its 90-day expiry), or null. */
    fun revokeOnlySince(): Long? = file.read()[REVOKE_SINCE]?.toLongOrNull()

    /** The forgotten pairing's link key, used for nothing but one REVOKE. Null if none or unreadable. */
    fun revokeOnlyKey(): ByteArray? = file.read()[REVOKE_ONLY]?.let(::openWithRetry)?.also(::register)

    /**
     * Forget while the unit is out of range: in one write, every pairing value goes and only the
     * sealed link key stays, marked forgotten, with the time. False = nothing changed.
     */
    fun writeRevokeOnly(key: ByteArray, sinceMs: Long): Boolean {
        val blob = sealed(key) ?: return false
        register(key)
        return file.writeAll(
            mapOf(
                CURRENT to null, RESERVE to null, UNIT_DEVICE_ID to null, SEND_LOCATION to null,
                REVOKE_ONLY to blob, REVOKE_SINCE to sinceMs.toString(),
                PENDING_ID to null, PENDING_TOKEN to null, PENDING_VEHICLE to null, PENDING_FOR to null,
            ),
        )
    }

    fun clearRevokeOnly(): Boolean = file.writeAll(mapOf(REVOKE_ONLY to null, REVOKE_SINCE to null))

    /** The network the phone paired on (4.3, rev 5). Kept by a revoke-only record, cleared by Forget. */
    fun pairingNetwork(): PairingNetwork? = PairingNetwork.decode(file.read()[PAIRING_NET])

    fun setPairingNetwork(n: PairingNetwork?): Boolean = file.writeAll(mapOf(PAIRING_NET to n?.encode()))

    fun unitDeviceId(): String? = file.read()[UNIT_DEVICE_ID]

    /**
     * A replacement engine sign-in for the paired unit, minted and waiting for the unit to say it
     * stored it (5.6). It outlives the process on purpose: the unit may hold a token whose TOKEN_OK
     * was lost, so the same one is handed over again at the next contact rather than a new one
     * minted and this one abandoned live on the engine.
     */
    fun pendingUnitToken(): PendingUnitToken? {
        val values = file.read()
        val id = values[PENDING_ID]
        val replaces = values[PENDING_FOR]
        val token = values[PENDING_TOKEN]?.let(::openText)
        // A record with no pairing to belong to is never handed over, so it is not a record.
        if (id == null || token == null || replaces == null) return null
        return PendingUnitToken(id, token, values[PENDING_VEHICLE], replaces)
    }

    /** Which credential is waiting for the unit, without opening its token. Null = none. */
    fun pendingUnitId(): String? = file.read()[PENDING_ID]

    /** Writes or (null) clears it. False = nothing was written, so nothing changed. */
    fun setPendingUnitToken(pending: PendingUnitToken?): Boolean {
        val sealed = pending?.let { sealedText(it.token) ?: return false }
        return file.writeAll(
            mapOf(
                PENDING_ID to pending?.id,
                PENDING_TOKEN to sealed,
                PENDING_VEHICLE to pending?.vehicleId,
                PENDING_FOR to pending?.replaces,
            ),
        )
    }

    fun setUnitDeviceId(id: String?): Boolean = file.writeAll(mapOf(UNIT_DEVICE_ID to id))

    fun sendLocation(): Boolean = file.read()[SEND_LOCATION] == "1"

    fun setSendLocation(on: Boolean): Boolean = file.writeAll(mapOf(SEND_LOCATION to if (on) "1" else null))

    private fun sealedOk(plain: ByteArray?, sealed: String?) = plain == null || sealed != null

    private fun sealedText(text: String): String? =
        box.seal(text.toByteArray(Charsets.US_ASCII))?.let(LinkCrypto::b64)?.also { redact(text) }

    private fun openText(blob: String): String? =
        openWithRetry(blob)?.let { String(it, Charsets.US_ASCII) }?.also(redact)

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
        private const val REVOKE_ONLY = "revoke_only_key"
        private const val REVOKE_SINCE = "revoke_only_since"
        private const val PAIRING_NET = "pairing_network"
        private const val PENDING_ID = "pending_unit_id"
        private const val PENDING_TOKEN = "pending_unit_token"
        private const val PENDING_VEHICLE = "pending_unit_vehicle"
        private const val PENDING_FOR = "pending_unit_replaces"
        private val RETIRED = listOf("car_gemini_key", "hand_over_gemini")
    }
}
