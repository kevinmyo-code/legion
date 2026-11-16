package com.kevin.legion.service.testturn

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * The microphone of a test conversation: continuous silence, with a queued clip played in its
 * place in real time (100 ms chunks, as the real mic delivers them), then silence again so the
 * phone's own end of turn fires. The real mic is never opened, so nothing said in the room reaches
 * a test conversation. A clip queued while a reply plays exercises barge-in.
 */
class TestMic {
    private val clips = Channel<ByteArray>(capacity = MAX_QUEUED)

    @Volatile var feeding = false
        private set

    private val waiting = java.util.concurrent.atomic.AtomicInteger(0)

    /** A clip is queued or still being fed. */
    val pending: Boolean get() = waiting.get() > 0

    /** False when [MAX_QUEUED] clips already wait. */
    fun queue(pcm: ByteArray): Boolean = clips.trySend(pcm).isSuccess.also { if (it) waiting.incrementAndGet() }

    suspend fun play(onBuffer: (ByteArray, Int) -> Unit) {
        val silence = ByteArray(CHUNK_BYTES)
        val buf = ByteArray(CHUNK_BYTES)
        var next = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            val clip = clips.tryReceive().getOrNull()
            if (clip == null) {
                onBuffer(silence, CHUNK_BYTES)
                next = pace(next)
                continue
            }
            feeding = true
            try {
                var at = 0
                while (at < clip.size && coroutineContext.isActive) {
                    val n = minOf(CHUNK_BYTES, clip.size - at)
                    System.arraycopy(clip, at, buf, 0, n)
                    if (n < CHUNK_BYTES) buf.fill(0, n, CHUNK_BYTES)
                    onBuffer(buf, CHUNK_BYTES)
                    at += n
                    next = pace(next)
                }
            } finally {
                feeding = false
                waiting.decrementAndGet()
            }
        }
    }

    private suspend fun pace(next: Long): Long {
        val due = next + CHUNK_MS
        val wait = due - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        // Fell far behind (the process was paused): restart the clock rather than burst.
        return if (wait < -CHUNK_MS * BEHIND_CHUNKS) System.currentTimeMillis() else due
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK_MS = 100L
        const val CHUNK_BYTES = 3_200
        private const val MAX_QUEUED = 4
        private const val BEHIND_CHUNKS = 5

        /**
         * The PCM16 samples of a 16 kHz mono 16-bit WAV, or null for anything else (the CLI
         * converts before pushing). Walks the chunks, so a LIST chunk before `data` is fine.
         */
        // One exit per malformed header; the numbers are RIFF offsets.
        @Suppress("ReturnCount", "MagicNumber", "CyclomaticComplexMethod")
        fun parseWav(bytes: ByteArray): ByteArray? {
            if (bytes.size < 44) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") {
                return null
            }
            var pos = 12
            var fmtOk = false
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = b.getInt(pos + 4)
                val body = pos + 8
                if (size < 0 || body + size > bytes.size) {
                    // A streamed WAV may leave the data size unset: take the rest.
                    return if (id == "data" && fmtOk) bytes.copyOfRange(body, bytes.size) else null
                }
                when (id) {
                    "fmt " -> {
                        val format = b.getShort(body).toInt()
                        val channels = b.getShort(body + 2).toInt()
                        val rate = b.getInt(body + 4)
                        val bits = b.getShort(body + 14).toInt()
                        fmtOk = format == 1 && channels == 1 && rate == RATE && bits == 16
                        if (!fmtOk) return null
                    }
                    "data" -> return if (fmtOk) bytes.copyOfRange(body, body + size) else null
                }
                pos = body + size + (size and 1)
            }
            return null
        }
    }
}
