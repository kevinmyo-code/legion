package com.kevin.legion.service

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import org.json.JSONObject

/**
 * The short scripted line the phone plays when a tap or the wake word opens the mic (voice cost
 * audit 2026-11-10, row F2; Kevin: "drop the spoken greeting and wake reply, can we seed a few
 * scripted lines instead?").
 *
 * Before this, every cold tap sent GREETING_PROMPT and every wake word WAKE_ACK_PROMPT: a full
 * billed model generation (setup re-billed) before Kevin said a word, and the mic stayed shut
 * until the model had finished speaking. Now the phone plays a pre-rendered clip in Alfred's Puck
 * voice and the model is not asked for anything.
 *
 * **One list, one place:** `assets/listen_cues/lines.json`. `tools/render_listen_cues.py` renders
 * each line to `assets/listen_cues/<id>.wav` (24 kHz mono PCM16, the Live output format, so it
 * plays through the session's own track unchanged) with a pluggable renderer: Gemini TTS in Puck
 * today, a local voice when the cascade lands. A line with no clip, or clips in a voice other than
 * the runtime one (lines.json `voice`), plays [earconPcm] instead, so the build works with no
 * clips at all and swapping voices needs no code change.
 *
 * **Never heard as Kevin's speech.** The clip goes through [GeminiLiveSession.playListenCue], the
 * same queue and AudioTrack as Alfred's replies, and the mic opens only after that track has
 * drained plus [com.kevin.legion.service.turn.MicReopen]'s settle (700 ms on Bluetooth), with the
 * capture pre-roll flushed. Nothing captured during the clip is ever sent.
 *
 * Phase 2 seam (Kevin 2026-11-10, local fast path): picking and loading here need no socket. A
 * local recognizer can play the same cue through its own player and claim the utterance before
 * any Live session exists; see `LiveSessionController.openListening`.
 */
object ListenCue {
    const val ASSET_DIR = "listen_cues"
    const val LINES_ASSET = "$ASSET_DIR/lines.json"
    const val SAMPLE_RATE = 24_000

    /** One opening line. [window] is null for an any-time line. */
    data class Line(val id: String, val text: String, val window: Window? = null)

    /** A phone-local time window, both ends inclusive to the minute; may not wrap midnight. */
    data class Window(val start: LocalTime, val end: LocalTime) {
        val key: String get() = "$start-$end"
        fun contains(t: LocalTime): Boolean {
            val m = t.withSecond(0).withNano(0)
            return !m.isBefore(start) && !m.isAfter(end)
        }

        companion object {
            fun parse(spec: String): Window {
                val (a, b) = spec.split("-").map { LocalTime.parse(it.trim()) }
                return Window(a, b)
            }
        }
    }

    /** What the picker remembers between opens. [usedWindows] holds `yyyy-MM-dd|window` keys. */
    data class State(val lastId: String? = null, val usedWindows: Set<String> = emptySet())

    data class Pick(val line: Line, val state: State)

    /** What to play: a clip's PCM, or the earcon's. [label] goes on the LIVE_AUDIT line. */
    class Cue(val pcm: ByteArray, val label: String)

    /**
     * The voice the bundled clips are in: lines.json's `voice` (default Puck). The clips play only
     * when it matches the runtime voice, so re-rendering them in another voice (a local TTS when the
     * cascade lands) is a data change: new WAVs plus this one field, no code.
     */
    fun parseVoice(json: String): String = JSONObject(json).optString("voice").ifBlank { "Puck" }

    fun parseLines(json: String): List<Line> {
        val arr = JSONObject(json).getJSONArray("lines")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Line(
                id = o.getString("id"),
                text = o.getString("text"),
                window = o.optString("window").takeIf { it.isNotBlank() }?.let(Window::parse),
            )
        }
    }

    /**
     * Kevin's rules (alfred-opening-lines-1110): a time-of-day line plays at most once per window
     * per day, on the first open inside that window; otherwise a random any-time line; never the
     * same line twice in a row.
     */
    fun pick(lines: List<Line>, now: LocalDateTime, state: State, random: Random = Random.Default): Pick {
        val today = now.toLocalDate().toString()
        val usedToday = state.usedWindows.filter { it.startsWith("$today|") }.toSet()
        val windowed = lines.filter { l ->
            val w = l.window ?: return@filter false
            w.contains(now.toLocalTime()) && "$today|${w.key}" !in usedToday && l.id != state.lastId
        }
        if (windowed.isNotEmpty()) {
            val line = windowed[random.nextInt(windowed.size)]
            return Pick(line, State(line.id, usedToday + "$today|${line.window!!.key}"))
        }
        val anyTime = lines.filter { it.window == null && it.id != state.lastId }
            .ifEmpty { lines.filter { it.window == null } }
            .ifEmpty { lines }
        val line = anyTime[random.nextInt(anyTime.size)]
        return Pick(line, State(line.id, usedToday))
    }

    /**
     * The PCM samples of a WAV clip, or null unless it is 24 kHz mono 16-bit PCM (anything else
     * would play at the wrong speed on the session's track).
     */
    // The WAV header is byte offsets and sizes by definition (RIFF, fmt, data); naming each one
    // would read worse than the format it parses.
    @Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
    fun wavPcm(wav: ByteArray): ByteArray? {
        if (wav.size < 12 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wav, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) return null
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var formatOk = false
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > wav.size) {
                // A streamed WAV may carry a bogus data size; take the rest of the file.
                return if (id == "data" && formatOk) wav.copyOfRange(body, wav.size) else null
            }
            when (id) {
                "fmt " -> formatOk = size >= 16 &&
                    b.getShort(body).toInt() == 1 && // PCM
                    b.getShort(body + 2).toInt() == 1 && // mono
                    b.getInt(body + 4) == SAMPLE_RATE &&
                    b.getShort(body + 14).toInt() == 16
                "data" -> return if (formatOk) wav.copyOfRange(body, body + size) else null
            }
            pos = body + size + (size and 1)
        }
        return null
    }

    /**
     * Two soft rising tones, about 0.25 s, 24 kHz mono PCM16: the fallback when a line has no clip
     * or the voice is not Puck. Raised-cosine edges so it never clicks.
     */
    @Suppress("MagicNumber") // tone frequencies, durations and the edge length are the design
    fun earconPcm(): ByteArray {
        val tones = listOf(660.0 to 0.09, 0.0 to 0.03, 990.0 to 0.11)
        val samples = tones.sumOf { (it.second * SAMPLE_RATE).toInt() }
        val out = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for ((freq, secs) in tones) {
            val n = (secs * SAMPLE_RATE).toInt()
            val edge = (0.015 * SAMPLE_RATE).toInt()
            for (i in 0 until n) {
                val env = min(1.0, min(i, n - 1 - i).toDouble() / edge).let { 0.5 - 0.5 * kotlin.math.cos(PI * it) }
                val v = if (freq == 0.0) 0.0 else sin(2 * PI * freq * i / SAMPLE_RATE) * env * EARCON_GAIN
                out.putShort((v * Short.MAX_VALUE).toInt().toShort())
            }
        }
        return out.array()
    }

    /** A clip longer than this plays the earcon instead: an opening line is a few words. */
    const val MAX_CLIP_MS = 2_500L

    /** How long [pcm] (24 kHz mono PCM16) plays, in milliseconds. */
    fun durationMs(pcm: ByteArray): Long = pcm.size / 2 * 1_000L / SAMPLE_RATE

    /** 100 ms of 24 kHz mono PCM16: the size of a reply chunk, see [chunks]. */
    const val CHUNK_BYTES = 4_800

    /**
     * [pcm] cut into [CHUNK_BYTES] pieces for the session's playback queue. One big chunk would
     * break the mic gate: the track counts frames as played only after a whole chunk's write
     * returns, so a clip longer than the track's buffer would look drained while still playing.
     * Small chunks drain the way a model reply does.
     */
    fun chunks(pcm: ByteArray, size: Int = CHUNK_BYTES): List<ByteArray> =
        (pcm.indices step size).map { pcm.copyOfRange(it, minOf(it + size, pcm.size)) }

    private const val EARCON_GAIN = 0.25
    private const val PREFS = "listen_cue"
    private const val KEY_LAST = "last_id"
    private const val KEY_WINDOWS = "used_windows"

    private class Bundle(val voice: String, val lines: List<Line>)

    @Volatile private var cached: Bundle? = null

    private fun bundle(context: Context): Bundle = cached ?: runCatching {
        val json = context.assets.open(LINES_ASSET).use { it.readBytes().decodeToString() }
        Bundle(parseVoice(json), parseLines(json))
    }.getOrDefault(Bundle("Puck", emptyList())).also { cached = it }

    /**
     * The next cue for an open: a picked line's clip, else the earcon. [voice] is the runtime voice;
     * clips in any other voice ([parseVoice]) give the earcon rather than a second speaker.
     */
    @Suppress("ReturnCount") // one exit per fallback: no lines, wrong voice, clip or earcon
    fun next(context: Context, voice: String?, now: LocalDateTime = LocalDateTime.now()): Cue {
        val bundle = bundle(context)
        val all = bundle.lines
        if (all.isEmpty()) return Cue(earconPcm(), "earcon")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = State(prefs.getString(KEY_LAST, null), prefs.getStringSet(KEY_WINDOWS, emptySet()).orEmpty())
        val pick = pick(all, now, state)
        prefs.edit().putString(KEY_LAST, pick.state.lastId).putStringSet(KEY_WINDOWS, pick.state.usedWindows).apply()
        if (!voice.equals(bundle.voice, ignoreCase = true)) return Cue(earconPcm(), "earcon:voice")
        val pcm = runCatching {
            context.assets.open("$ASSET_DIR/${pick.line.id}.wav").use { wavPcm(it.readBytes()) }
        }.getOrNull()
        return if (pcm != null && pcm.isNotEmpty() && durationMs(pcm) <= MAX_CLIP_MS) {
            Cue(pcm, pick.line.id)
        } else {
            Cue(earconPcm(), "earcon:${pick.line.id}")
        }
    }
}
