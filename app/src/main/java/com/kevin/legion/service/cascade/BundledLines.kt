package com.kevin.legion.service.cascade

import android.content.Context
import com.kevin.legion.service.ListenCue
import org.json.JSONObject

/**
 * The cloud voice's fixed clips shipped in the APK (2026-11-21, voice-local-clips; Kevin: "yes
 * bundle"): the acknowledgements and wait lines in two takes, the goodbyes, the missed line and the
 * voice-lost line, in `assets/fixed_clips/` ([DIR]), rendered by `tools/render_fixed_clips.py` with
 * the phone's own model, voice and request. Before this they came from the cloud voice on first use,
 * so a first install, a cleared cache or a changed voice had no acknowledgement on the first turns
 * (`backchannel_skip reason=no_clip_fits`).
 *
 * Read through [KeptLineAudio] under the kept-line key ([cloudKey]), so a clip is used only for the
 * exact model, voice, rate, line and take it was rendered for, and only when nothing of his own is
 * in memory or on disk under that key. Gemini TTS output only: the laptop voices (CC BY-NC) never
 * ship. `FixedClipsTest` pins every file's sha256 from [MANIFEST].
 */
internal class BundledLines(private val read: (String) -> ByteArray?) {
    /** Kept-line key to its file, from the manifest; empty when it is missing or unreadable. */
    private val files: Map<String, String> by lazy { parse(read(MANIFEST)?.decodeToString()) }

    /** The clip's PCM (24 kHz mono 16-bit) under [key], or null: none shipped for it. */
    operator fun get(key: String): ByteArray? = files[key]?.let { read("$DIR/$it") }?.let(ListenCue::wavPcm)

    companion object {
        const val DIR = "fixed_clips"
        const val MANIFEST = "$DIR/manifest.json"

        /**
         * A cloud clip's kept-line key: model, voice, rate and the exact text, another render apart
         * (`#take1`, [Backchannel.TAKES]). The one format [GeminiTts] and the manifest both use.
         */
        fun cloudKey(model: String, voice: String, rate: Int, text: String, take: Int): String =
            "cloud|$model|$voice|$rate|$text" + if (take > 0) "#take$take" else ""

        /** The manifest's clips by kept-line key; the line goes through [SpokenText.forSpeech] as the voice's does. */
        fun parse(json: String?): Map<String, String> = runCatching {
            val m = JSONObject(json.orEmpty())
            val clips = m.getJSONArray("clips")
            (0 until clips.length()).associate { i ->
                val c = clips.getJSONObject(i)
                val text = SpokenText.forSpeech(c.getString("line"))
                cloudKey(m.getString("model"), m.getString("voice"), m.getInt("rate"), text, c.getInt("take")) to
                    c.getString("file")
            }
        }.getOrDefault(emptyMap())

        @Volatile private var shared: BundledLines? = null

        /** The APK's clips for this process; set once at start ([KeptLineAudio.install]). */
        fun install(context: Context) {
            val assets = context.applicationContext.assets
            shared = BundledLines { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() }
        }

        /** The APK's clip under [key], or null (none, or not installed yet). */
        fun lookup(key: String): ByteArray? = shared?.get(key)
    }
}
