package com.kevin.legion.service.cascade

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The body of `POST /api/agents/turn/stream` (engine `agents/voice/streaming.py`, `FrameReader`):
 * frames of `<type: 1 byte><length: 4 bytes, big-endian><payload>`, sent while he speaks.
 *
 * - `M` metadata JSON first (`conversation_id`, `client`, `contract_version`);
 * - `A` 16 kHz PCM16 mono audio, never empty, at most [MAX_FRAME_BYTES] a frame;
 * - `E` the phone's own end of turn, `{"bytes": <audio total>}`: a total that does not match what
 *   arrived is the engine's `audio_gap`, and the clip is sent again on the batch route.
 *
 * The client ends the body right after `E` (the engine's gunicorn sees `E` only once the body ends).
 */
internal object EngineFrames {
    /** ASCII `M`, `A`, `E`. */
    const val META: Byte = 0x4D
    const val AUDIO: Byte = 0x41
    const val END: Byte = 0x45

    /** The engine's `MAX_FRAME_BYTES` (64 KiB); a longer buffer goes as several audio frames. */
    const val MAX_FRAME_BYTES = 64 * 1024
    private const val HEADER_BYTES = 5

    fun frame(type: Byte, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES + length).order(ByteOrder.BIG_ENDIAN)
            .put(type)
            .putInt(length)
            .put(payload, offset, length)
            .array()

    fun meta(json: String): ByteArray = frame(META, json.toByteArray(Charsets.UTF_8))

    /** [length] bytes of [pcm] as audio frames (none for an empty slice: the engine refuses one). */
    fun audio(pcm: ByteArray, offset: Int, length: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var at = offset
        val end = offset + length
        while (at < end) {
            val n = minOf(MAX_FRAME_BYTES, end - at)
            out.add(frame(AUDIO, pcm, at, n))
            at += n
        }
        return out
    }

    fun end(totalAudioBytes: Long): ByteArray =
        frame(END, "{\"bytes\":$totalAudioBytes}".toByteArray(Charsets.UTF_8))
}
