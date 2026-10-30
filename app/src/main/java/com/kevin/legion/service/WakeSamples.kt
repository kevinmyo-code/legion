package com.kevin.legion.service

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the wake pipeline did, as data rather than a log string (ticket 20). The detector and the
 * engine emit these; [WakeSampleCapture] files them beside the audio they happened in, so an
 * offline replay can be checked against what the phone actually decided.
 */
sealed interface WakeSignal {
    data object VadOn : WakeSignal
    data object VadOff : WakeSignal

    /** Stage 1 fired. sherpa-onnx 1.13.8 exposes no score, only the tokens and their times. */
    data class KwsHit(val keyword: String, val tokens: List<String>, val timestamps: List<Float>) : WakeSignal

    /** Stage 2's verdict and exactly what Vosk heard in the ring. */
    data class Confirm(val text: String, val raw: String, val accepted: Boolean) : WakeSignal

    /** The engine would have opened a conversation but enrollment held it back. */
    data object Suppressed : WakeSignal

    /** The engine opened a conversation. */
    data object Opened : WakeSignal
}

/** 16-bit mono PCM to a WAV file's bytes. Pure, so the header is a JVM test. */
object WavEncoder {
    private const val HEADER_BYTES = 44
    private const val FMT_CHUNK_BYTES = 16
    private const val PCM_FORMAT: Short = 1
    private const val BITS: Short = 16
    private const val BYTES_PER_SAMPLE = 2
    private const val MONO: Short = 1

    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = pcm.size * BYTES_PER_SAMPLE
        val b = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(HEADER_BYTES - 8 + dataBytes)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(FMT_CHUNK_BYTES)
        b.putShort(PCM_FORMAT)
        b.putShort(MONO)
        b.putInt(sampleRate)
        b.putInt(sampleRate * BYTES_PER_SAMPLE)
        b.putShort(BYTES_PER_SAMPLE.toShort())
        b.putShort(BITS)
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes)
        for (s in pcm) b.putShort(s)
        return b.array()
    }
}

/**
 * The pure half of the wake sample recorder (ticket 20, Kevin 2026-10-30: "train it to be better
 * for me"). Fed every captured chunk and every [WakeSignal]; hands back finished [Sample]s for the
 * Android side ([WakeSampleRecorder]) to write. No files, no threads, no Android.
 *
 * Two kinds of sample:
 *  - **Live segments** (only while [liveOn]): one per VAD utterance, from [PREROLL_SECONDS] before
 *    the VAD flipped on to when it flipped off, capped at [MAX_SEGMENT_SECONDS]. What the phone
 *    heard and decided on an ordinary day.
 *  - **Takes** (enrollment): continuous audio for a labelled window, whatever the VAD did. A take
 *    keeps the VAD out of the recording so the offline eval can sweep the VAD too; a segment-only
 *    recording would bake today's VAD threshold into every result.
 *
 * Offsets in the sidecar are samples from the start of that sample's own audio.
 */
class WakeSampleCapture(private val sampleRate: Int = SAMPLE_RATE) {

    /** One finished recording: the PCM and its sidecar. [kind] is "live" or "take". */
    class Sample(val kind: String, val label: String, val pcm: ShortArray, val sidecar: JSONObject)

    private class Open(val label: String, val startSample: Long, val maxSamples: Int, val meta: Map<String, String>) {
        var pcm = ShortArray(0)
        var n = 0
        val events = JSONArray()

        fun append(buf: ShortArray, count: Int) {
            val take = minOf(count, maxSamples - n)
            if (take <= 0) return
            if (n + take > pcm.size) pcm = pcm.copyOf(maxOf(n + take, pcm.size * 2))
            System.arraycopy(buf, 0, pcm, n, take)
            n += take
        }

        val full get() = n >= maxSamples
    }

    @Volatile var liveOn: Boolean = false

    private val preroll = ShortArray((sampleRate * PREROLL_SECONDS).toInt())
    private var written = 0L
    private var segment: Open? = null
    private var take: Open? = null

    /** True while a take is open. */
    val takeOpen: Boolean @Synchronized get() = take != null

    @Synchronized
    fun onAudio(buf: ShortArray, n: Int): List<Sample> {
        val out = mutableListOf<Sample>()
        segment?.append(buf, n)
        take?.let { t ->
            t.append(buf, n)
            if (t.full) closeTake()?.let(out::add)
        }
        segment?.let { s -> if (s.full) closeSegment(reason = "max_length")?.let(out::add) }
        for (i in 0 until n) preroll[((written + i) % preroll.size).toInt()] = buf[i]
        written += n
        return out
    }

    @Synchronized
    fun onSignal(signal: WakeSignal, meta: Map<String, String> = emptyMap()): List<Sample> {
        val out = mutableListOf<Sample>()
        if (signal == WakeSignal.VadOn && liveOn && segment == null) {
            val lead = prerollTail()
            segment = Open("live", written - lead.size, sampleRate * MAX_SEGMENT_SECONDS, meta).also {
                it.append(lead, lead.size)
            }
        }
        segment?.let { record(it, signal) }
        take?.let { record(it, signal) }
        if (signal == WakeSignal.VadOff) closeSegment(reason = "vad_off")?.let(out::add)
        return out
    }

    /** Opens a labelled take of at most [maxMs]; an open take is closed and returned first. */
    @Synchronized
    fun beginTake(label: String, maxMs: Long, meta: Map<String, String>): List<Sample> {
        val out = listOfNotNull(closeTake())
        val max = (sampleRate * maxMs / MS_PER_SECOND).toInt()
        take = Open(label, written, max, meta)
        return out
    }

    @Synchronized
    fun endTake(): Sample? = closeTake()

    /** Capture is going away (a hit tore the detector down, or the mic was preempted). */
    @Synchronized
    fun flush(): List<Sample> = listOfNotNull(closeSegment(reason = "capture_released"), closeTake())

    private fun record(o: Open, signal: WakeSignal) {
        val e = JSONObject().put("atSample", (written - o.startSample).coerceAtLeast(0))
        when (signal) {
            WakeSignal.VadOn -> e.put("stage", "vad_on")
            WakeSignal.VadOff -> e.put("stage", "vad_off")
            WakeSignal.Suppressed -> e.put("stage", "open_suppressed")
            WakeSignal.Opened -> e.put("stage", "opened")
            is WakeSignal.KwsHit -> e.put("stage", "kws_hit").put("keyword", signal.keyword)
                .put("tokens", JSONArray(signal.tokens))
                .put("timestamps", JSONArray(signal.timestamps.map { it.toDouble() }))
            is WakeSignal.Confirm -> e.put("stage", "confirm").put("accepted", signal.accepted)
                .put("text", signal.text).put("raw", signal.raw)
        }
        o.events.put(e)
    }

    private fun closeSegment(reason: String): Sample? {
        val s = segment ?: return null
        segment = null
        return finish("live", s, reason)
    }

    private fun closeTake(): Sample? {
        val t = take ?: return null
        take = null
        return finish("take", t, if (t.full) "max_length" else "ended")
    }

    private fun finish(kind: String, o: Open, reason: String): Sample {
        val pcm = o.pcm.copyOf(o.n)
        return Sample(kind, o.label, pcm, sidecar(kind, o, pcm, reason))
    }

    private fun sidecar(kind: String, o: Open, pcm: ShortArray, reason: String): JSONObject {
        val events = o.events
        var kwsHit = false
        var confirmText: String? = null
        var accepted: Boolean? = null
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            when (e.getString("stage")) {
                "kws_hit" -> kwsHit = true
                "confirm" -> {
                    confirmText = e.getString("text")
                    accepted = e.getBoolean("accepted")
                }
            }
        }
        return JSONObject().apply {
            put("kind", kind)
            put("label", o.label)
            put("sampleRate", sampleRate)
            put("samples", pcm.size)
            put("durationMs", pcm.size * MS_PER_SECOND / sampleRate)
            put("peak", pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0)
            put("closedBy", reason)
            put("kwsHit", kwsHit)
            put("confirmText", confirmText ?: JSONObject.NULL)
            put("confirmAccepted", accepted ?: JSONObject.NULL)
            put("verdict", verdict(kwsHit, accepted))
            o.meta.forEach { (k, v) -> put(k, v) }
            put("events", events)
        }
    }

    private fun prerollTail(): ShortArray {
        val have = minOf(written, preroll.size.toLong()).toInt()
        val start = written - have
        return ShortArray(have) { preroll[((start + it) % preroll.size).toInt()] }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val PREROLL_SECONDS = 1.0
        const val MAX_SEGMENT_SECONDS = 20
        private const val MS_PER_SECOND = 1000L

        /** One word for what the pipeline did with this audio. */
        fun verdict(kwsHit: Boolean, accepted: Boolean?): String = when {
            accepted == true -> "opened"
            kwsHit && accepted == false -> "confirm_rejected"
            kwsHit -> "kws_hit"
            else -> "kws_miss"
        }
    }
}
