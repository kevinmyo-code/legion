package com.kevin.midnightai.link

import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject

/**
 * What the phone hands over once the codes match (HU-1 T3; Kevin: "pair once, then the phone hands
 * over the key"). Every field is optional: the phone sends what it has. [toString] never prints a
 * value, so a stray log line or a crash report cannot carry one.
 */
class Handover(
    val engineUrl: String?,
    val engineToken: String?,
    val engineUserId: String?,
    val geminiKey: String?,
    val mapboxToken: String?,
    val wifiSsid: String?,
    val wifiPassphrase: String?,
    val phoneName: String?,
) {
    /** Field names present, for the result line ("engine sign-in, Gemini key"), never values. */
    fun present(): List<String> = buildList {
        if (engineToken != null) add("engine sign-in")
        if (geminiKey != null) add("Gemini key")
        if (mapboxToken != null) add("map token")
        if (wifiSsid != null) add("hotspot")
    }

    override fun toString(): String = "Handover(${present().joinToString()})"

    companion object {
        /** Parses the opened hand-over JSON (spec section 3.5); null if it is not version 1. */
        fun parse(json: JSONObject): Handover? {
            if (json.optInt("v") != 1) return null
            fun s(o: JSONObject?, k: String): String? = o?.optString(k)?.takeIf { it.isNotBlank() }
            val engine = json.optJSONObject("engine")
            val wifi = json.optJSONObject("wifi")
            return Handover(
                engineUrl = s(engine, "url"),
                engineToken = s(engine, "token"),
                engineUserId = s(engine, "user_id"),
                geminiKey = s(json, "gemini_key"),
                mapboxToken = s(json, "mapbox_token"),
                wifiSsid = s(wifi, "ssid"),
                wifiPassphrase = s(wifi, "passphrase"),
                phoneName = s(json, "phone_name")?.take(40),
            )
        }
    }
}

/** Stores a hand-over and the link key (Android: Keystore-wrapped); the tests use a fake. */
fun interface HandoverSink {
    /** Returns null when everything was stored, else a short reason in words. Never throws. */
    fun store(handover: Handover, linkKey: ByteArray): String?
}

/**
 * The head unit's side of one pairing attempt (spec section 3), as a pure state machine: JSON
 * messages in, JSON messages out, clock injected. [PairingServer] moves the bytes; the screen calls
 * [confirm], [mismatch] and [cancel]. One QR = one session = at most one paired phone.
 *
 * Secrets (the QR secret, the derived keys, the hand-over) are held only in memory and wiped when
 * the session ends; nothing here logs.
 */
@OptIn(ExperimentalEncodingApi::class)
class PairingSession(
    private val keyPair: KeyPair,
    private val secret: ByteArray,
    private val sink: HandoverSink,
    private val now: () -> Long,
) {
    val unitPk: ByteArray = PairingCrypto.encode(keyPair.public as ECPublicKey)
    private val startedAt = now()
    private val qrKey = PairingCrypto.qrKey(secret)
    private var badMacs = 0
    private var keys: PairingCrypto.Keys? = null
    private var pending: Handover? = null
    private var confirmedAt: Long? = null
    private var codeShownAt = 0L

    sealed interface Stage {
        data object WaitingForPhone : Stage
        data class CompareCodes(val code: String, val unitConfirmed: Boolean) : Stage
        data class Paired(val phoneName: String?, val received: List<String>) : Stage
        data class Failed(val reason: Reason) : Stage
    }

    enum class Reason(val words: String) {
        QR_EXPIRED("The code timed out. Show a new one."),
        CODE_TIMEOUT("Nobody confirmed the numbers in time. Start again."),
        WRONG_QR("A phone tried to pair without this screen's code. Start again."),
        BAD_VERSION("The phone speaks a different pairing version. Update both apps."),
        BAD_MESSAGE("The phone sent something this unit cannot read. Start again."),
        MISMATCH("The numbers did not match, so nothing was paired."),
        CANCELLED("Pairing cancelled. Nothing was changed."),
        PHONE_CANCELLED("The phone stopped pairing. Nothing was changed."),
        STORE_FAILED("Paired, but this unit could not store the keys safely, so nothing was kept."),
    }

    var stage: Stage = Stage.WaitingForPhone
        private set

    /** The QR's text (spec 3.1). [ip] and [port] are where [PairingServer] listens. */
    fun qrText(ip: String, port: Int): String =
        "mnai-pair:1:${Base64.UrlSafe.encode(unitPk)}:${Base64.UrlSafe.encode(secret)}:$ip:$port"

    /** One message from the phone; returns the messages to send back (possibly none). */
    fun onMessage(msg: JSONObject): List<JSONObject> {
        tick()?.let { return listOf(it) }
        if (stage is Stage.Paired || stage is Stage.Failed) return emptyList()
        return when (msg.optString("t")) {
            "hello" -> onHello(msg)
            "handover" -> onHandover(msg)
            "abort" -> listOfNotNull(fail(Reason.PHONE_CANCELLED, tellPhone = false))
            else -> listOfNotNull(fail(Reason.BAD_MESSAGE))
        }
    }

    private fun onHello(msg: JSONObject): List<JSONObject> {
        if (stage != Stage.WaitingForPhone) return listOfNotNull(fail(Reason.BAD_MESSAGE))
        if (msg.optInt("v") != 1) return listOfNotNull(fail(Reason.BAD_VERSION))
        val phonePk = b64(msg.optString("pk")) ?: return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val mac = b64(msg.optString("mac")) ?: return listOfNotNull(fail(Reason.BAD_MESSAGE))
        if (!LinkCrypto.constantTimeEquals(mac, PairingCrypto.helloMac(qrKey, unitPk, phonePk))) {
            // Somebody on the LAN without the QR. Three tries, then this QR is dead.
            badMacs++
            return if (badMacs >= MAX_BAD_MACS) listOfNotNull(fail(Reason.WRONG_QR))
            else listOf(abort("bad_mac"))
        }
        val peer = PairingCrypto.decode(phonePk, keyPair.public as ECPublicKey)
            ?: return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val shared = PairingCrypto.sharedSecret(keyPair.private, peer)
        val transcript = PairingCrypto.transcript(unitPk, phonePk)
        keys = PairingCrypto.derive(shared, secret, transcript)
        shared.fill(0)
        codeShownAt = now()
        stage = Stage.CompareCodes(keys!!.checkCode, unitConfirmed = false)
        return listOf(
            JSONObject().put("t", "ack").put("v", 1)
                .put("mac", Base64.Default.encode(PairingCrypto.ackMac(qrKey, transcript))),
        )
    }

    private fun onHandover(msg: JSONObject): List<JSONObject> {
        val k = keys
        if (stage !is Stage.CompareCodes || k == null || pending != null) return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val sealed = b64(msg.optString("c")) ?: return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val plain = LinkCrypto.open(k.handover, LinkCrypto.nonce(HAND, 1), HANDOVER_AAD, sealed)
            ?: return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val handover = runCatching { Handover.parse(JSONObject(String(plain, Charsets.UTF_8))) }.getOrNull()
        plain.fill(0)
        if (handover == null) return listOfNotNull(fail(Reason.BAD_VERSION))
        pending = handover
        return if (confirmedAt != null) finish() else emptyList()
    }

    /** The driver tapped "the numbers match". */
    fun confirm(): List<JSONObject> {
        tick()?.let { return listOf(it) }
        val s = stage as? Stage.CompareCodes ?: return emptyList()
        confirmedAt = now()
        stage = s.copy(unitConfirmed = true)
        return if (pending != null) finish() else emptyList()
    }

    /** The driver tapped "the numbers differ". */
    fun mismatch(): List<JSONObject> = listOfNotNull(fail(Reason.MISMATCH))

    fun cancel(): List<JSONObject> =
        if (stage is Stage.Paired || stage is Stage.Failed) emptyList() else listOfNotNull(fail(Reason.CANCELLED))

    /** Expiry check; returns an abort to send when the session just timed out. */
    fun tick(): JSONObject? = when (val s = stage) {
        Stage.WaitingForPhone -> if (now() - startedAt > QR_LIFETIME_MS) fail(Reason.QR_EXPIRED) else null
        is Stage.CompareCodes -> if (now() - codeShownAt > CODE_LIFETIME_MS) fail(Reason.CODE_TIMEOUT) else null
        else -> null
    }

    private fun finish(): List<JSONObject> {
        val k = keys!!
        val h = pending!!
        val error = runCatching { sink.store(h, k.link) }.getOrElse { "store threw" }
        if (error != null) return listOfNotNull(fail(Reason.STORE_FAILED))
        val done = LinkCrypto.seal(
            k.handover, LinkCrypto.nonce(DONE, 1), DONE_AAD,
            JSONObject().put("ok", true).toString().toByteArray(Charsets.UTF_8),
        )
        stage = Stage.Paired(h.phoneName, h.present())
        wipe()
        return listOf(JSONObject().put("t", "done").put("c", Base64.Default.encode(done)))
    }

    private fun fail(reason: Reason, tellPhone: Boolean = true): JSONObject? {
        stage = Stage.Failed(reason)
        wipe()
        return if (tellPhone) abort(reason.name.lowercase()) else null
    }

    private fun abort(why: String) = JSONObject().put("t", "abort").put("why", why)

    private fun wipe() {
        keys?.wipe()
        keys = null
        pending = null
        secret.fill(0)
        qrKey.fill(0)
    }

    private fun b64(s: String): ByteArray? = runCatching { Base64.Default.decode(s) }.getOrNull()

    override fun toString(): String = "PairingSession(stage=${stage::class.simpleName})"

    companion object {
        const val QR_LIFETIME_MS = 5 * 60_000L
        const val CODE_LIFETIME_MS = 2 * 60_000L
        const val MAX_BAD_MACS = 3
        val HAND = "HAND".toByteArray(Charsets.US_ASCII)
        val DONE = "DONE".toByteArray(Charsets.US_ASCII)
        val HANDOVER_AAD = "MNAI-PAIR handover v1".toByteArray(Charsets.US_ASCII)
        val DONE_AAD = "MNAI-PAIR done v1".toByteArray(Charsets.US_ASCII)
    }
}
