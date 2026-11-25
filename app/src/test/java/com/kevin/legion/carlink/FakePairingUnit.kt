package com.kevin.legion.carlink

import com.kevin.legion.carlink.protocol.Frames
import com.kevin.legion.carlink.protocol.LinkCrypto
import com.kevin.legion.carlink.protocol.P256
import com.kevin.legion.carlink.protocol.PairingConnection
import com.kevin.legion.carlink.protocol.PairingKeys
import com.kevin.legion.carlink.protocol.PairingWire
import com.kevin.legion.carlink.protocol.Received
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

/**
 * A head unit that pairs for real (3.4), so a whole pairing can be driven through
 * [CarLinkController]: it derives the session keys from the phone's own `hello`, which is why the
 * phone may keep generating a fresh key, and it opens and keeps every hand-over body it is sent.
 *
 * Its answers never suspend: `ack`, then `code_ok`, then `done` are ready the moment the phone's
 * loop asks for them. That is the point - it leaves the ordering entirely to the phone, so a mint
 * the phone did not wait for is visible as a hand-over frame that went out too early.
 *
 * Every value in it is made up. [com.kevin.legion.carlink.protocol.FakeUnit] is the MNAI2 feed's
 * counterpart; this one is the pairing conversation's.
 */
class FakePairingUnit(private val host: String = "10.0.0.1", private val port: Int = 17300) : PairingConnection {
    private val unitPair = P256.generate()
    private val unitPk = unitPair.publicBytes
    private val secret = ByteArray(SECRET_LEN) { 7 }
    private val reader = Frames.FrameReader()
    @Volatile private var keys: PairingKeys? = null
    @Volatile private var step = 0

    /** Every hand-over body this unit opened, as the JSON the phone sealed. */
    val handovers: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var closed = false
        private set

    /** The QR this unit would show (3.1), for [CarLinkController.pair]. */
    fun qrText(): String = listOf(
        "mnai-pair", "1", LinkCrypto.b64url(unitPk), LinkCrypto.b64url(secret), host, port.toString(),
    ).joinToString(":")

    override suspend fun send(frame: ByteArray) {
        for (text in reader.feed(frame)) onFrame(text)
    }

    override suspend fun receive(timeoutMs: Long): Received {
        val k = keys ?: return Received.Closed
        return when (step++) {
            0 -> Received.Message("""{"t":"ack","v":1,"kc":"${LinkCrypto.b64(k.expectedKc())}"}""")
            1 -> Received.Message(sealed(k, PairingWire.CODE_OK, "CDOK", "MNAI-PAIR code ok v1"))
            2 -> Received.Message(sealed(k, PairingWire.DONE, "DONE", "MNAI-PAIR done v1"))
            else -> Received.Closed
        }
    }

    override fun close() {
        closed = true
    }

    private fun onFrame(text: String) {
        val obj = Json.parseToJsonElement(text).jsonObject
        when (obj["t"]?.jsonPrimitive?.content) {
            "hello" -> keys = derive(LinkCrypto.unb64(obj["pk"]!!.jsonPrimitive.content)!!)
            "handover" -> handovers += openHandover(obj["c"]!!.jsonPrimitive.content)
        }
    }

    private fun derive(phonePk: ByteArray): PairingKeys {
        val z = P256.agree(unitPair, P256.parsePublic(phonePk)!!)
        return try {
            PairingKeys.derive(secret, unitPk, phonePk, z)
        } finally {
            z.fill(0)
        }
    }

    private fun openHandover(c: String): String {
        val plain = LinkCrypto.open(
            requireNotNull(keys).handKey, LinkCrypto.nonce("HAND", 1),
            LinkCrypto.ascii("MNAI-PAIR handover v1"), requireNotNull(LinkCrypto.unb64(c)),
        )
        return String(requireNotNull(plain) { "the hand-over did not open under the hand key" }, Charsets.UTF_8)
    }

    private fun sealed(k: PairingKeys, type: String, dir: String, label: String): String = PairingWire.sealedMessage(
        type,
        LinkCrypto.seal(k.handKey, LinkCrypto.nonce(dir, 1), LinkCrypto.ascii(label), OK_JSON.toByteArray()),
    )

    private companion object {
        const val SECRET_LEN = 16
        const val OK_JSON = """{"ok":true}"""
    }
}
