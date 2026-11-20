package com.kevin.legion.carlink.protocol

import com.kevin.legion.carlink.protocol.LinkCrypto.ascii
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** Why a pairing ended without a new pairing. Each one has words on the Car link screen. */
enum class PairingFailure {
    BAD_QR, UNREACHABLE, UNIT_NOT_PROVEN, UNEXPECTED_MESSAGE, CONNECTION_LOST, NO_ANSWER,
    PHONE_STORE_FAILED, CANCELLED_HERE,
    UNIT_BAD_VERSION, UNIT_BAD_MESSAGE, UNIT_TIMEOUT, UNIT_CODE_WRONG, UNIT_CANCELLED, UNIT_STORE_FAILED,
    UNIT_STOPPED,
    /** An abort after `done` opened: the phone went back to its earlier key (3.7). */
    NOT_COMPLETED,
}

/** The phone's pairing stages, section 3.4. */
sealed interface PairingStage {
    object AwaitingAck : PairingStage
    /** Digits on screen; the driver types them on the unit. Not a data class: never print the digits. */
    class DigitsShown(val digits: String) : PairingStage { override fun toString() = "DigitsShown" }
    object AwaitingDone : PairingStage
    /** `done` opened, the new key is current and `stored` went out. */
    object StoredSent : PairingStage
    object Paired : PairingStage
    class Failed(val why: PairingFailure, val hadEarlierPairing: Boolean = false) : PairingStage {
        override fun toString() = "Failed($why)"
    }
}

/** What the runner must do next. */
sealed interface PairingEffect {
    class Send(val json: String) : PairingEffect { override fun toString() = "Send" }
    object Close : PairingEffect
}

/**
 * The phone's side of section 3.4 as a pure state machine: feed it the unit's messages, a disconnect,
 * a timeout or a cancel; it returns what to send and moves [stage]. No socket, no clock, no thread:
 * [PairingRunner] owns those.
 *
 * The man-in-the-middle defence is that [qr]'s `unitPk` (from the QR, never from the wire) goes into
 * the transcript; `kc` proves the unit derived the same keys (section 7).
 */
class PairingClient(
    private val qr: QrPayload,
    private val phoneKey: PhoneKeyPair,
    private val ring: LinkKeyRing,
    private val handover: () -> HandoverBody,
) {
    var stage: PairingStage = PairingStage.AwaitingAck
        private set

    private val keys: PairingKeys = run {
        val z = P256.agree(phoneKey, qr.unitKey)
        try {
            PairingKeys.derive(qr.secret, qr.unitPk, phoneKey.publicBytes, z)
        } finally {
            z.fill(0)
        }
    }

    /** Message 1, sent as soon as the connection opens (and again on each retried connection). */
    fun hello(): String = PairingWire.hello(phoneKey.publicBytes, keys.helloMac(qr.unitPk, phoneKey.publicBytes))

    fun onMessage(text: String): List<PairingEffect> {
        val msg = PairingWire.read(text)
        if (msg is UnitMessage.Abort) return onAbort(msg.why)
        return when (stage) {
            PairingStage.AwaitingAck -> onAck(msg)
            is PairingStage.DigitsShown -> onCodeOk(msg)
            PairingStage.AwaitingDone -> onDone(msg)
            // Nothing else is expected after `stored`; the unit closes. Not a failure: the key is held.
            PairingStage.StoredSent -> finish()
            else -> emptyList()
        }
    }

    private fun onAck(msg: UnitMessage): List<PairingEffect> {
        if (msg !is UnitMessage.Ack) return abort("bad_message", PairingFailure.UNEXPECTED_MESSAGE)
        if (msg.v != 1) return abort("bad_version", PairingFailure.UNEXPECTED_MESSAGE)
        val kc = msg.kc
        if (kc == null || !LinkCrypto.sameBytes(kc, keys.expectedKc())) {
            return abort("bad_message", PairingFailure.UNIT_NOT_PROVEN)
        }
        stage = PairingStage.DigitsShown(keys.check)
        return emptyList()
    }

    private fun onCodeOk(msg: UnitMessage): List<PairingEffect> {
        // A second `ack` is an abort (3.4), as is anything but a `code_ok` that opens.
        if (msg !is UnitMessage.Sealed || msg.type != PairingWire.CODE_OK) {
            return abort("bad_message", PairingFailure.UNEXPECTED_MESSAGE)
        }
        if (!opensOk(msg.c, "CDOK", "MNAI-PAIR code ok v1")) return abort("bad_message", PairingFailure.UNEXPECTED_MESSAGE)
        val body = handover().toJson().toByteArray(Charsets.UTF_8)
        val sealed = LinkCrypto.seal(keys.handKey, LinkCrypto.nonce("HAND", 1), ascii("MNAI-PAIR handover v1"), body)
        body.fill(0)
        ring.beginHandover()
        stage = PairingStage.AwaitingDone
        return listOf(PairingEffect.Send(PairingWire.sealedMessage("handover", sealed)))
    }

    private fun onDone(msg: UnitMessage): List<PairingEffect> {
        if (msg !is UnitMessage.Sealed || msg.type != PairingWire.DONE ||
            !opensOk(msg.c, "DONE", "MNAI-PAIR done v1")
        ) {
            ring.handoverFailed()
            return abort("bad_message", PairingFailure.UNEXPECTED_MESSAGE)
        }
        // Persist new-current plus old-in-reserve BEFORE `stored` (3.7); if that fails, stay on the old key.
        if (!ring.commit(keys.linkKey)) {
            ring.handoverFailed()
            return abort("store_failed", PairingFailure.PHONE_STORE_FAILED)
        }
        stage = PairingStage.StoredSent
        val ok = OK_JSON.toByteArray(Charsets.UTF_8)
        val stored = LinkCrypto.seal(keys.handKey, LinkCrypto.nonce("STOR", 1), ascii("MNAI-PAIR stored v1"), ok)
        return listOf(PairingEffect.Send(PairingWire.sealedMessage("stored", stored)))
    }

    private fun onAbort(why: String?): List<PairingEffect> {
        val failure = when (why) {
            "bad_version" -> PairingFailure.UNIT_BAD_VERSION
            "bad_message" -> PairingFailure.UNIT_BAD_MESSAGE
            "timeout" -> PairingFailure.UNIT_TIMEOUT
            "code_wrong" -> PairingFailure.UNIT_CODE_WRONG
            "cancelled" -> PairingFailure.UNIT_CANCELLED
            "store_failed" -> PairingFailure.UNIT_STORE_FAILED
            else -> PairingFailure.UNIT_STOPPED
        }
        return when (stage) {
            PairingStage.AwaitingDone -> { ring.handoverFailed(); fail(failure) }
            // After `done` opened: the unit dropped PENDING (3.6). Back to the earlier key (3.7).
            PairingStage.StoredSent -> revertAndFail()
            PairingStage.Paired, is PairingStage.Failed -> emptyList()
            else -> fail(failure)
        }
    }

    /** The connection closed (or failed) without a message. */
    fun onDisconnected(): List<PairingEffect> = when (stage) {
        PairingStage.StoredSent -> finish()
        PairingStage.AwaitingDone -> { ring.handoverFailed(); fail(PairingFailure.CONNECTION_LOST) }
        PairingStage.Paired, is PairingStage.Failed -> emptyList()
        else -> fail(PairingFailure.CONNECTION_LOST)
    }

    /** The stage's wait ran out (the runner's clock; the document gives the phone no timeouts). */
    fun onTimeout(): List<PairingEffect> = when (stage) {
        PairingStage.StoredSent -> finish()
        PairingStage.AwaitingDone -> { ring.handoverFailed(); abort("timeout", PairingFailure.NO_ANSWER) }
        PairingStage.Paired, is PairingStage.Failed -> emptyList()
        else -> abort("timeout", PairingFailure.NO_ANSWER)
    }

    /**
     * The person cancelled. Before `stored` it sends `abort cancelled`. After `stored` it only closes:
     * the unit may already have committed, so an abort then could strand the two on different keys.
     */
    fun cancel(): List<PairingEffect> = when (stage) {
        PairingStage.StoredSent -> finish()
        PairingStage.AwaitingDone -> { ring.handoverFailed(); abort("cancelled", PairingFailure.CANCELLED_HERE) }
        PairingStage.Paired, is PairingStage.Failed -> emptyList()
        else -> abort("cancelled", PairingFailure.CANCELLED_HERE)
    }

    /** Every connect try failed before an `ack` (3.2: three retries). */
    fun giveUp(): List<PairingEffect> =
        if (stage == PairingStage.AwaitingAck) fail(PairingFailure.UNREACHABLE) else emptyList()

    private fun revertAndFail(): List<PairingEffect> {
        val earlier = ring.reserve != null
        ring.revert()
        stage = PairingStage.Failed(PairingFailure.NOT_COMPLETED, hadEarlierPairing = earlier)
        keys.wipe()
        return listOf(PairingEffect.Close)
    }

    private fun finish(): List<PairingEffect> {
        stage = PairingStage.Paired
        keys.wipe()
        return listOf(PairingEffect.Close)
    }

    private fun fail(why: PairingFailure): List<PairingEffect> {
        stage = PairingStage.Failed(why)
        keys.wipe()
        return listOf(PairingEffect.Close)
    }

    private fun abort(why: String, failure: PairingFailure): List<PairingEffect> =
        listOf(PairingEffect.Send(PairingWire.abort(why))) + fail(failure)

    private fun opensOk(c: ByteArray?, dir: String, label: String): Boolean {
        c ?: return false
        val plain = LinkCrypto.open(keys.handKey, LinkCrypto.nonce(dir, 1), ascii(label), c) ?: return false
        return try {
            val obj = Json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject
            (obj["ok"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            plain.fill(0)
        }
    }

    companion object {
        const val OK_JSON = """{"ok":true}"""
    }
}
