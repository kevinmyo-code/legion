package com.kevin.legion.service

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * "Save my turns" (Kevin, 2026-11-15): keeps the audio of his own voice turns on the phone, for
 * speaker-recognition training on his laptop later. **Off unless he turns it on.**
 *
 * - Local files only, beside the wake samples: the app's external files dir `voice-turns/`, a
 *   sibling of `wake-samples/` so the wake screen's own "delete all" never takes these and this
 *   one never takes those. Both are excluded from device backup (`res/xml/*backup*`), so nothing
 *   here is uploaded by Android either. Pulled to the laptop by hand (adb), like the wake samples.
 * - Audio only: no transcript, no sidecar, nothing of what was said. Never logged: the log says a
 *   count or a failure, never a file name or a word.
 * - Capped by [VoiceTurnFiles.MAX_FILES], [VoiceTurnFiles.MAX_BYTES] and [VoiceTurnFiles.MAX_AGE_MS],
 *   the oldest going first. "Delete all" is on the wake tuning screen.
 *
 * Fed by the cascade conversation ([com.kevin.legion.service.cascade.CascadeSession]): what the
 * phone sent as his turn, the same audio the speech-to-text heard (16 kHz mono PCM16).
 */
object VoiceTurnRecorder {
    private const val TAG = "VoiceTurnRecorder"
    private const val PREFS = "voice_turns"
    private const val KEY_ON = "save_turns"

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-turns") }

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON, on).apply()
    }

    private fun base(context: Context): File? = context.applicationContext.getExternalFilesDir(VoiceTurnFiles.DIR)

    /** Keeps one turn's audio ([pcm]: 16 kHz mono PCM16, little-endian), off the caller's thread. */
    fun keep(context: Context, pcm: ByteArray) {
        if (!enabled(context)) return
        val app = context.applicationContext
        writer.execute {
            runCatching {
                val dir = base(app) ?: return@runCatching
                VoiceTurnFiles.write(dir, pcm, System.currentTimeMillis())
            }.onFailure { Log.w(TAG, "turn not kept: ${it.javaClass.simpleName}") }
        }
    }

    /** Every kept turn, deleted. */
    fun deleteAll(context: Context) {
        val app = context.applicationContext
        writer.execute { runCatching { base(app)?.let(VoiceTurnFiles::deleteAll) } }
    }

    /** How many turns are kept. Reads the filesystem; call off the main thread. */
    fun count(context: Context): Int = VoiceTurnFiles.count(base(context))
}

/** The file half of [VoiceTurnRecorder]: pure over a directory, so the caps are a JVM test. */
internal object VoiceTurnFiles {
    const val DIR = "voice-turns"
    const val SAMPLE_RATE = 16_000

    /** About 500 turns of a few seconds each. */
    const val MAX_FILES = 500

    /** About 100 minutes of 16 kHz PCM16. */
    const val MAX_BYTES = 200L * 1024 * 1024

    /** Thirty days: enough to collect a training set, short enough that nothing piles up for good. */
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** A turn shorter than this is a cough or a false start: not worth keeping. */
    const val MIN_MS = 400L

    private const val BYTES_PER_SAMPLE = 2
    private const val MS_PER_SECOND = 1000L

    /** Writes [pcm] as a WAV under [dir] and prunes; null when it was too short to keep. */
    fun write(dir: File, pcm: ByteArray, now: Long): File? {
        if (durationMs(pcm.size) < MIN_MS) return null
        dir.mkdirs()
        val f = File(dir, stamp(now) + ".wav")
        val shorts = ShortArray(pcm.size / BYTES_PER_SAMPLE)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        f.writeBytes(WavEncoder.encode(shorts, SAMPLE_RATE))
        prune(dir, now)
        return f
    }

    /** Drops files past [MAX_AGE_MS], then the oldest until both [MAX_FILES] and [MAX_BYTES] hold. */
    fun prune(dir: File, now: Long, maxFiles: Int = MAX_FILES, maxBytes: Long = MAX_BYTES) {
        val wavs = dir.listFiles { f -> f.isFile && f.extension == "wav" }?.sortedBy { it.lastModified() } ?: return
        val kept = wavs.filter { f ->
            val old = now - f.lastModified() > MAX_AGE_MS
            if (old) f.delete()
            !old
        }.toMutableList()
        var bytes = kept.sumOf { it.length() }
        while (kept.isNotEmpty() && (kept.size > maxFiles || bytes > maxBytes)) {
            val f = kept.removeAt(0)
            bytes -= f.length()
            f.delete()
        }
    }

    fun count(dir: File?): Int = dir?.listFiles { f -> f.isFile && f.extension == "wav" }?.size ?: 0

    fun deleteAll(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun durationMs(bytes: Int): Long = bytes / BYTES_PER_SAMPLE * MS_PER_SECOND / SAMPLE_RATE

    private fun stamp(now: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now))
}
