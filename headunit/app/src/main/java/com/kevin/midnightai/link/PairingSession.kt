package com.kevin.midnightai.link

import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject

/**
 * What the phone hands over once the digits are right (spec 3.5). Every field optional. There is
 * NO engine address (review H4): the unit only ever uses its own build-time engine constant.
 * [toString] never prints a value.
 */
class Handover(
    val engineToken: String?,
    val engineUserId: String?,
    val geminiKey: String?,
    val mapboxToken: String?,
    val phoneName: String?,
) {
    fun present(): List<String> = buildList {
        if (engineToken != null) add("engine sign-in")
        if (geminiKey != null) add("Gemini key")
        if (mapboxToken != null) add("map token")
    }

    override fun toString(): String = "Handover(${present().joinToString()})"

    companion object {
        /**
         * Parses the opened hand-over JSON; null if it is not version 1. Unknown fields (a `wifi`
         * object before T8 exists, any `engine` URL) are ignored, never stored.
         */
        fun parse(json: JSONObject): Handover? {
            if (json.optInt("v") != 1) return null
            fun s(k: String): String? = json.optString(k).takeIf { it.isNotBlank() }
            return Handover(
                engineToken = s("engine_token"),
                engineUserId = s("engine_user_id"),
                geminiKey = s("gemini_key"),
                mapboxToken = s("mapbox_token"),
                phoneName = s("phone_name")?.take(40),
            )
        }
    }
}

/**
 * Where a pairing is kept (Android: [AndroidHandoverSink] over [LinkKeyStore]); a fake in tests.
 * Both calls return null on success, else a short reason in words, and never throw.
 */
interface HandoverSink {
    /** Encrypts everything and writes it as the PENDING pairing (spec 3.7). COMMITTED untouched. */
    fun stage(handover: Handover, linkKey: ByteArray): String?

    /** PENDING -> COMMITTED, all or nothing. */
    fun commit(): String?
}

/**
 * The head unit's side of one pairing (spec 3, revision 2) as a pure state machine. Unauthenticated
 * input goes through [preAuth] and can only close its own connection; only the connection whose
 * hello carried a valid QR MAC ([preAuth] returned [PreAuth.Claimed]) feeds [onMessage]. The
 * driver TYPES the phone's six digits ([enterCode]); the unit never shows them. The clock is
 * monotonic and injected. Secrets live only in memory and are wiped when the session ends.
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
    private var keys: PairingCrypto.Keys? = null
    private var pending: Handover? = null
    private var codeOk = false
    private var wrongCodes = 0
    private var stageStartedAt = startedAt

    sealed interface Stage {
        data object WaitingForPhone : Stage
        /** The phone shows six digits; the driver types them here. No digits in this object. */
        data class EnterCode(val triesLeft: Int) : Stage
        /** Digits right; waiting for the phone's hand-over. */
        data object Receiving : Stage
        /** Stored as PENDING, `done` sent; waiting for the phone's `stored`. */
        data object AwaitingPhone : Stage
        data class Paired(val phoneName: String?, val received: List<String>, val confirmed: Boolean) : Stage
        data class Failed(val reason: Reason) : Stage
    }

    enum class Reason(val words: String) {
        TIMEOUT("Nothing happened in time, so nothing was paired. Start again."),
        BAD_VERSION("The phone speaks a different pairing version. Update both apps."),
        BAD_MESSAGE("The phone sent something this unit cannot read. Start again."),
        CODE_WRONG("Three wrong numbers, so nothing was paired. Start again."),
        CANCELLED("Pairing cancelled. Nothing was changed."),
        PHONE_CANCELLED("The phone stopped pairing. Nothing was changed."),
        STORE_FAILED("This unit could not store the keys safely, so nothing was kept. The old pairing, if any, is unchanged."),
    }

    var stage: Stage = Stage.WaitingForPhone
        private set

    fun qrText(ip: String, port: Int): String =
        "mnai-pair:${PairingCrypto.QR_VERSION}:${Base64.UrlSafe.encode(unitPk)}:${Base64.UrlSafe.encode(secret)}:$ip:$port"

    /** What to do with an unauthenticated connection's message. */
    sealed interface PreAuth {
        /** Not a valid hello: close THIS connection; the session is unchanged. */
        data object Close : PreAuth
        /** A valid hello: this connection now owns the session; send [reply]. */
        data class Claimed(val reply: JSONObject) : PreAuth
        /** A hello with a valid MAC that still cannot go on (version, key): the session ends. */
        data class Fail(val reply: JSONObject) : PreAuth
    }

    fun preAuth(msg: JSONObject): PreAuth {
        if (tick() != null || stage != Stage.WaitingForPhone) return PreAuth.Close
        if (msg.optString("t") != "hello") return PreAuth.Close
        val phonePk = b64(msg.optString("pk")) ?: return PreAuth.Close
        val mac = b64(msg.optString("mac")) ?: return PreAuth.Close
        if (!LinkCrypto.constantTimeEquals(mac, PairingCrypto.helloMac(qrKey, unitPk, phonePk))) return PreAuth.Close
        // From here the peer has seen the QR: its failures may end the session.
        if (msg.optInt("v") != PairingCrypto.HELLO_VERSION.toInt()) return PreAuth.Fail(fail(Reason.BAD_VERSION)!!)
        val peer = PairingCrypto.decode(phonePk, keyPair.public as ECPublicKey)
            ?: return PreAuth.Fail(fail(Reason.BAD_MESSAGE)!!)
        val shared = PairingCrypto.sharedSecret(keyPair.private, peer)
        val transcript = PairingCrypto.transcript(unitPk, phonePk)
        val k = PairingCrypto.derive(shared, secret, transcript)
        shared.fill(0)
        keys = k
        enter(Stage.EnterCode(MAX_CODE_TRIES))
        return PreAuth.Claimed(
            JSONObject().put("t", "ack").put("v", 1)
                .put("kc", Base64.Default.encode(PairingCrypto.unitConfirm(k.confirm, transcript))),
        )
    }

    /** A message from the claiming connection. Returns what to send back. */
    fun onMessage(msg: JSONObject): List<JSONObject> {
        tick()?.let { return listOf(it) }
        if (stage is Stage.Paired || stage is Stage.Failed) return emptyList()
        return when (msg.optString("t")) {
            "handover" -> onHandover(msg)
            "stored" -> onStored(msg)
            "abort" -> listOfNotNull(fail(Reason.PHONE_CANCELLED, tellPhone = false))
            else -> listOfNotNull(fail(Reason.BAD_MESSAGE))
        }
    }

    /** The claiming connection closed. Before `done` that ends the session; after, the unit waits (spec 3.7). */
    fun onDisconnect(): List<JSONObject> {
        return when (stage) {
            Stage.AwaitingPhone -> {
                stage = Stage.Paired(pending?.phoneName, pending?.present().orEmpty(), confirmed = false)
                wipe()
                emptyList()
            }
            is Stage.EnterCode, Stage.Receiving -> listOfNotNull(fail(Reason.PHONE_CANCELLED, tellPhone = false))
            else -> emptyList()
        }
    }

    sealed interface CodeResult {
        data class Correct(val send: JSONObject) : CodeResult
        data class Wrong(val triesLeft: Int) : CodeResult
        data class Dead(val send: JSONObject?) : CodeResult
        data object NotNow : CodeResult
    }

    /** The driver typed [digits] on the unit's pad. */
    fun enterCode(digits: String): CodeResult {
        tick()?.let { return CodeResult.Dead(it) }
        val s = stage as? Stage.EnterCode ?: return CodeResult.NotNow
        val k = keys ?: return CodeResult.NotNow
        val ok = digits.length == 6 && LinkCrypto.constantTimeEquals(digits.toByteArray(), k.checkCode.toByteArray())
        if (!ok) {
            wrongCodes++
            if (wrongCodes >= MAX_CODE_TRIES) return CodeResult.Dead(fail(Reason.CODE_WRONG))
            stage = s.copy(triesLeft = MAX_CODE_TRIES - wrongCodes)
            return CodeResult.Wrong(MAX_CODE_TRIES - wrongCodes)
        }
        codeOk = true
        enter(Stage.Receiving)
        val sealed = LinkCrypto.seal(k.handover, LinkCrypto.nonce(CDOK, 1), CODE_OK_AAD, OK_BODY)
        return CodeResult.Correct(JSONObject().put("t", "code_ok").put("c", Base64.Default.encode(sealed)))
    }

    private fun onHandover(msg: JSONObject): List<JSONObject> {
        val k = keys
        if (stage != Stage.Receiving || k == null || !codeOk) return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val sealed = b64(msg.optString("c")) ?: return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val plain = LinkCrypto.open(k.handover, LinkCrypto.nonce(HAND, 1), HANDOVER_AAD, sealed)
            ?: return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val json = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()
        plain.fill(0)
        if (json == null) return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val handover = Handover.parse(json) ?: return listOfNotNull(fail(Reason.BAD_VERSION))
        val error = runCatching { sink.stage(handover, k.link) }.getOrElse { "store threw" }
        if (error != null) return listOfNotNull(fail(Reason.STORE_FAILED))
        pending = handover
        enter(Stage.AwaitingPhone)
        val done = LinkCrypto.seal(k.handover, LinkCrypto.nonce(DONE, 1), DONE_AAD, OK_BODY)
        return listOf(JSONObject().put("t", "done").put("c", Base64.Default.encode(done)))
    }

    private fun onStored(msg: JSONObject): List<JSONObject> {
        val k = keys
        if (stage != Stage.AwaitingPhone || k == null) return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val sealed = b64(msg.optString("c")) ?: return listOfNotNull(fail(Reason.BAD_MESSAGE))
        LinkCrypto.open(k.handover, LinkCrypto.nonce(STOR, 1), STORED_AAD, sealed)
            ?: return listOfNotNull(fail(Reason.BAD_MESSAGE))
        val h = pending
        // A commit that fails leaves PENDING in place; the phone's next datagram retries it.
        val committed = runCatching { sink.commit() }.getOrElse { "commit threw" } == null
        stage = Stage.Paired(h?.phoneName, h?.present().orEmpty(), confirmed = committed)
        wipe()
        return emptyList()
    }

    fun cancel(): List<JSONObject> = when (stage) {
        is Stage.Paired, is Stage.Failed -> emptyList()
        else -> listOfNotNull(fail(Reason.CANCELLED))
    }

    /** Expiry check (monotonic clock); returns an abort to send when the session just timed out. */
    fun tick(): JSONObject? {
        val limit = when (stage) {
            Stage.WaitingForPhone -> QR_LIFETIME_MS
            is Stage.EnterCode, Stage.Receiving, Stage.AwaitingPhone -> STAGE_LIFETIME_MS
            else -> return null
        }
        if (now() - stageStartedAt <= limit) return null
        // A pairing already stored as PENDING stays pending (spec 3.7); only the session ends.
        if (stage == Stage.AwaitingPhone) {
            onDisconnect()
            return null
        }
        return fail(Reason.TIMEOUT)
    }

    private fun enter(next: Stage) {
        stage = next
        stageStartedAt = now()
    }

    private fun fail(reason: Reason, tellPhone: Boolean = true): JSONObject? {
        stage = Stage.Failed(reason)
        wipe()
        return if (tellPhone) JSONObject().put("t", "abort").put("why", reason.name.lowercase()) else null
    }

    private fun wipe() {
        keys?.wipe()
        keys = null
        secret.fill(0)
        qrKey.fill(0)
    }

    private fun b64(s: String): ByteArray? = runCatching { Base64.Default.decode(s) }.getOrNull()

    override fun toString(): String = "PairingSession(stage=${stage::class.simpleName})"

    companion object {
        const val QR_LIFETIME_MS = 2 * 60_000L
        const val STAGE_LIFETIME_MS = 2 * 60_000L
        const val MAX_CODE_TRIES = 3
        private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
        val CDOK = ascii("CDOK")
        val HAND = ascii("HAND")
        val DONE = ascii("DONE")
        val STOR = ascii("STOR")
        val CODE_OK_AAD = ascii("MNAI-PAIR code ok v1")
        val HANDOVER_AAD = ascii("MNAI-PAIR handover v1")
        val DONE_AAD = ascii("MNAI-PAIR done v1")
        val STORED_AAD = ascii("MNAI-PAIR stored v1")
        private val OK_BODY = ascii("{\"ok\":true}")
    }
}
