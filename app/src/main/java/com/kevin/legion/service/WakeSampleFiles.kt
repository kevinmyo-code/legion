package com.kevin.legion.service

import com.kevin.legion.MidnightEvents
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The file half of [WakeSampleRecorder] (ticket 20): where a finished sample lands and how the live
 * folder is kept to its cap. Called only on the recorder's writer thread.
 *
 * Layout under the app's external files dir `wake-samples/`: `enroll-<stamp>/` per enrollment
 * session, `live/` for every-attempt saving. Each sample is `<stamp>_<label>.wav` plus a `.json`
 * sidecar of the same name.
 */
internal object WakeSampleFiles {
    const val DIR = "wake-samples"
    private const val LIVE_DIR = "live"
    // About 20 MB of utterances at 16 kHz mono 16-bit: old ones go first.
    private const val LIVE_KEEP = 300

    /** Writes [s] under [base]; a take goes to [sessionDir]. Returns the two files written. */
    fun write(base: File, sessionDir: String?, s: WakeSampleCapture.Sample): List<File> {
        val isTake = s.kind == "take"
        val dir = File(base, if (isTake) sessionDir ?: "enroll-unsorted" else LIVE_DIR).apply { mkdirs() }
        val name = stamp() + "_" + s.label.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val wav = File(dir, "$name.wav").apply { writeBytes(WavEncoder.encode(s.pcm, WakeSampleCapture.SAMPLE_RATE)) }
        val json = File(dir, "$name.json").apply { writeText(s.sidecar.toString(2)) }
        MidnightEvents.wakeStage("sample saved: ${dir.name}/$name verdict=${s.sidecar.optString("verdict")}")
        if (!isTake) pruneLive(dir)
        return listOf(wav, json)
    }

    /** WAV count under [base], enrollment and live. */
    fun count(base: File?): Int = base?.walkTopDown()?.count { it.isFile && it.extension == "wav" } ?: 0

    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    private fun pruneLive(dir: File) {
        val wavs = dir.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name } ?: return
        wavs.dropLast(LIVE_KEEP).forEach { w ->
            w.delete()
            File(dir, w.nameWithoutExtension + ".json").delete()
        }
    }
}
