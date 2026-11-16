package com.kevin.legion.service.speaker

import org.json.JSONObject
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the voiceprint from the wake enrolment takes the phone already keeps (ticket 20's
 * `wake-samples/enroll-<stamp>/`, one WAV and one JSON sidecar per take). No new recording step:
 * the twenty prompted takes are the study's enrolment set, and the study built its profile the
 * same way.
 *
 * A take counts only when it is a prompted wake take (label `pos_...`, never the `neg_...` ambient
 * minute, which the study found held other voices) AND the engine confirmed the phrase in it: an
 * `open_suppressed` or `opened` event with its sample offset. That event, not the sidecar's
 * verdict string, is the key: takes saved before the verdict fix carry a stale verdict but the
 * right events. A take with no confirmed hit has no point to end its window at, so it is skipped.
 */
object SpeakerEnrolment {
    /** Stages that mean the engine would have opened on this take. */
    val HIT_STAGES = setOf("open_suppressed", "opened")

    private const val POSITIVE_PREFIX = "pos_"

    /** The sample offset of the first confirmed hit in [sidecar], or null for none. */
    @Suppress("ReturnCount") // first match wins
    fun hitSample(sidecar: JSONObject): Int? {
        val events = sidecar.optJSONArray("events") ?: return null
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            if (e.optString("stage") in HIT_STAGES && e.has("atSample")) return e.optLong("atSample").toInt()
        }
        return null
    }

    fun eligible(sidecar: JSONObject): Boolean =
        sidecar.optString("kind") == "take" &&
            sidecar.optString("label").startsWith(POSITIVE_PREFIX) &&
            hitSample(sidecar) != null

    /** The scored window of one take, or null when the take does not count. */
    @Suppress("ReturnCount") // two refusals, then the window
    fun window(sidecar: JSONObject, pcm: ShortArray): ShortArray? {
        if (!eligible(sidecar)) return null
        val hit = hitSample(sidecar) ?: return null
        return SpeakerWindow.pick(pcm, hit).takeIf { it.isNotEmpty() }
    }

    sealed interface Built {
        class Ready(val voiceprint: FloatArray, val takes: Int) : Built
        data class TooFew(val usable: Int) : Built
        data object Failed : Built
    }

    /** The mean of the L2-normalised embeddings of [windows]; needs [SpeakerPolicy.MIN_ENROL_TAKES]. */
    fun build(windows: List<ShortArray>, embed: (ShortArray) -> FloatArray): Built {
        if (windows.size < SpeakerPolicy.MIN_ENROL_TAKES) return Built.TooFew(windows.size)
        val c = SpeakerMath.centroid(windows.map(embed)) ?: return Built.Failed
        return Built.Ready(c, windows.size)
    }
}

/**
 * The voiceprint file: a magic, the dimension, how many takes it came from, when it was built, then
 * the floats. It is the only thing derived from his voice that is kept, it lives in the app's
 * private files dir (`speaker/`), is excluded from backups, and is never logged or uploaded.
 */
object VoiceprintCodec {
    class Voiceprint(val vector: FloatArray, val takes: Int, val builtAtMs: Long)

    private const val MAGIC = 0x4C565031 // "LVP1"
    private const val INT_BYTES = 4
    private const val LONG_BYTES = 8
    private const val FLOAT_BYTES = 4
    private const val HEADER = INT_BYTES * 3 + LONG_BYTES
    private const val MAX_DIM = 4096

    fun encode(v: Voiceprint): ByteArray {
        val b = ByteBuffer.allocate(HEADER + v.vector.size * FLOAT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC).putInt(v.vector.size).putInt(v.takes).putLong(v.builtAtMs)
        for (x in v.vector) b.putFloat(x)
        return b.array()
    }

    /** Null for anything that is not a whole, sane voiceprint. */
    fun decode(bytes: ByteArray): Voiceprint? = try {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != MAGIC) {
            null
        } else {
            val dim = b.int
            val takes = b.int
            val at = b.long
            if (dim !in 1..MAX_DIM || bytes.size != HEADER + dim * FLOAT_BYTES) {
                null
            } else {
                val v = FloatArray(dim) { b.float }
                if (v.any { it.isNaN() }) null else Voiceprint(v, takes, at)
            }
        }
    } catch (_: BufferUnderflowException) {
        null
    }
}

/** 16-bit mono 16 kHz PCM out of a WAV file's bytes (the files [com.kevin.legion.service.WavEncoder] writes). */
object WavReader {
    private const val RIFF_HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val ID_BYTES = 4
    private const val WAVE_AT = 8
    private const val CHANNELS_AT = 2
    private const val RATE_AT = 4
    private const val BITS_AT = 14
    private const val SAMPLE_BYTES = 2
    private const val PCM_FORMAT = 1
    private const val BITS = 16
    private const val FMT_BYTES = 16

    /** Null for anything but 16-bit mono PCM at [sampleRate]. */
    @Suppress("ReturnCount") // a RIFF walk: refusals, then the data chunk
    fun pcm16(bytes: ByteArray, sampleRate: Int = SpeakerMath.SAMPLE_RATE): ShortArray? {
        if (bytes.size < RIFF_HEADER || String(bytes, 0, ID_BYTES, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, WAVE_AT, ID_BYTES, Charsets.US_ASCII) != "WAVE"
        ) {
            return null
        }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = RIFF_HEADER
        var fmtOk = false
        while (at + CHUNK_HEADER <= bytes.size) {
            val id = String(bytes, at, ID_BYTES, Charsets.US_ASCII)
            val size = b.getInt(at + ID_BYTES)
            val body = at + CHUNK_HEADER
            if (size < 0 || body + size > bytes.size) return null
            when (id) {
                "fmt " -> fmtOk = size >= FMT_BYTES &&
                    b.getShort(body).toInt() == PCM_FORMAT &&
                    b.getShort(body + CHANNELS_AT).toInt() == 1 &&
                    b.getInt(body + RATE_AT) == sampleRate &&
                    b.getShort(body + BITS_AT).toInt() == BITS
                "data" -> return if (fmtOk) {
                    ShortArray(size / SAMPLE_BYTES) { b.getShort(body + it * SAMPLE_BYTES) }
                } else {
                    null
                }
            }
            at = body + size + (size and 1)
        }
        return null
    }
}
