package com.kevin.legion.carlink.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The head unit branch's known-answer vectors (`carlink/vectors.json`, copied byte for byte), read as
 * raw bytes from the test classpath. All values in it are made up.
 */
object CarLinkVectors {
    val raw: ByteArray by lazy {
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("carlink/vectors.json")) { "vectors.json missing" }
            .use { it.readBytes() }
    }

    private val root: JsonObject by lazy { Json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonObject }
    val pairing: JsonObject get() = root["pairing"]!!.jsonObject
    val mnai2: JsonObject get() = root["mnai2"]!!.jsonObject

    fun JsonObject.hex(key: String): ByteArray = unhex(this[key]!!.jsonPrimitive.content)
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** The pairing QR the unit would show for the vectors' key and secret, at an invented LAN address. */
    fun qrText(host: String = "10.0.0.1", port: Int = 17300): String =
        "mnai-pair:1:${LinkCrypto.b64url(pairing.hex("unit_pk"))}:${LinkCrypto.b64url(pairing.hex("secret"))}:$host:$port"

    fun phoneKey(): PhoneKeyPair = P256.fromScalar(pairing.str("phone_private_scalar"), pairing.hex("phone_pk"))

    /** The body the vectors sealed (3.5 field order, blank fields omitted). */
    fun vectorHandover() = HandoverBody(engineToken = "test-token", engineUserId = "u-1", phoneName = "Test phone")

    fun sealedJson(type: String, hexKey: String) = PairingWire.sealedMessage(type, pairing.hex(hexKey))
}
