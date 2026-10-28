package com.kevin.legion.service.turn

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Loads the vectors written by `tools/turn/make_turn_golden.py`. */
internal object TurnGolden {
    private fun bytes(name: String): ByteArray =
        requireNotNull(TurnGolden::class.java.getResourceAsStream("/turn/$name")) { "missing /turn/$name" }
            .use { it.readBytes() }

    fun audio(name: String): FloatArray {
        val b = ByteBuffer.wrap(bytes("golden_$name.pcm")).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(b.remaining()) { b.get(it) / 32768f }
    }

    fun features(name: String): FloatArray {
        val b = ByteBuffer.wrap(bytes("golden_$name.f32")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()) { b.get(it) }
    }

    val meta: JSONObject by lazy { JSONObject(String(bytes("golden.json"))) }
}
