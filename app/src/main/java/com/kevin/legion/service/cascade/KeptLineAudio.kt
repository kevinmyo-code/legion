package com.kevin.legion.service.cascade

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The scripted lines' audio ([ScriptedLines.KEEP]: the opening acknowledgements and the tool
 * filler), kept ON DISK as well as in memory (2026-11-15). Kept only for the process, the first
 * "Yes?" after every app start was a full TTS round trip (laptop ~0.75 s, cloud more) on the very
 * line whose job is to be instant; kept on disk it is instant from the second time ever.
 *
 * Both voices use it ([LaptopVoiceConnection], [GeminiTts]) through the `kept` map they already
 * took. The key is the voice's own: engine, model, voice, sample rate and the exact text, so a new
 * voice or a reworded line never plays old audio. Bounded by [maxFiles] and [maxBytes], the least
 * recently played file going first. A disk that cannot be read or written costs only the speed-up.
 */
internal class KeptLineAudio(
    private val dir: () -> File?,
    private val maxFiles: Int = MAX_FILES,
    private val maxBytes: Long = MAX_BYTES,
    private val mem: MutableMap<String, ByteArray> = ConcurrentHashMap(),
) : MutableMap<String, ByteArray> by mem {

    override fun get(key: String): ByteArray? = mem[key] ?: fromDisk(key)?.also { mem[key] = it }

    override fun put(key: String, value: ByteArray): ByteArray? {
        val before = mem.put(key, value)
        toDisk(key, value)
        return before
    }

    private fun fileOf(key: String): File? = dir()?.let { File(it, name(key)) }

    private fun fromDisk(key: String): ByteArray? = runCatching {
        val f = fileOf(key)?.takeIf { it.isFile } ?: return null
        f.setLastModified(System.currentTimeMillis())
        f.readBytes().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun toDisk(key: String, value: ByteArray) {
        if (value.isEmpty() || value.size > maxBytes) return
        runCatching {
            val d = dir() ?: return
            d.mkdirs()
            val f = File(d, name(key))
            val tmp = File(d, f.name + ".tmp")
            tmp.writeBytes(value)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            prune(d)
        }
    }

    /** Oldest-played first, until both bounds hold. */
    private fun prune(d: File) {
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.sortedBy { it.lastModified() }
            ?: return
        var count = files.size
        var bytes = files.sumOf { it.length() }
        for (f in files) {
            if (count <= maxFiles && bytes <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                count--
                bytes -= len
            }
        }
    }

    companion object {
        /**
         * About 65 lines per voice since 2026-11-17 (8 acks, 5 fillers, ~44 tool lines, the missed
         * line, the check-in closers): two voices' worth, so a voice switch does not evict the other.
         */
        const val MAX_FILES = 160

        /** About 200 s of 24 kHz PCM16: every kept line in two voices, at ~1.3 s a line. */
        const val MAX_BYTES = 10L * 1024 * 1024
        private const val SUFFIX = ".pcm"

        fun name(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS) + SUFFIX
        }

        private const val HASH_CHARS = 32

        @Volatile private var dir: File? = null

        /** Where the process's kept lines live; set once at start ([CascadeWarmup.install]). */
        fun install(context: Context) {
            dir = File(context.applicationContext.cacheDir, "scripted-tts")
        }

        /** The process's one store, shared by both voices (their keys never collide: each names its engine). */
        val SHARED: MutableMap<String, ByteArray> = KeptLineAudio({ dir })
    }
}
