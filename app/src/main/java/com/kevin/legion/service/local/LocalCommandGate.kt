package com.kevin.legion.service.local

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Decides whether a held first turn is a simple command the phone can do itself (phase 2).
 * Returns the command, or null for "hand it to Alfred". Never throws on purpose; a throw is a miss
 * in [com.kevin.legion.service.turn.HeldTurnSink] anyway.
 */
fun interface LocalCommandGate {
    fun decide(pcm16k: ByteArray): LocalCommand?
}

/**
 * The on-device gate: Vosk (already in the APK for the wake word) over the held PCM, restricted
 * to the command vocabulary plus `[unk]` so free speech comes out as unknown words, then
 * [LocalCommandMatcher] with its confidence cut-off. Uses the model the wake engine already copied
 * out of assets (`files/vosk-model`); if it is not there yet, every turn goes to Alfred.
 *
 * Logs one LIVE_LOCAL line per decision: hit with the command and confidence, or miss with the
 * reason, and the milliseconds it took. Never the words heard.
 */
class VoskLocalCommandGate(
    private val context: Context,
    companionName: String,
) : LocalCommandGate {

    private val matcher = LocalCommandMatcher(companionName)
    private val grammar: String = JSONArray(
        (LocalCommandMatcher.VOCABULARY + LocalCommandMatcher.tokens(companionName) + SPOKEN_FORMS)
            .filter { w -> w.any(Char::isLetter) }.toSortedSet().toList() + listOf(LocalCommandMatcher.UNKNOWN),
    ).toString()

    @Suppress("ReturnCount") // no model, recogniser failed, or the matcher's verdict
    override fun decide(pcm16k: ByteArray): LocalCommand? {
        val t0 = System.currentTimeMillis()
        val model = model(context) ?: return null.also { log("miss model-not-ready") }
        val utterance = try {
            Recognizer(model, SAMPLE_RATE, grammar).use { rec ->
                rec.setWords(true)
                rec.acceptWaveForm(pcm16k, pcm16k.size)
                parse(rec.finalResult)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            log("miss recogniser ${e.javaClass.simpleName}")
            return null
        }
        val ms = System.currentTimeMillis() - t0
        return when (val r = matcher.match(utterance)) {
            is LocalMatch.Hit -> r.command.also { log("hit $it conf=${"%.2f".format(r.confidence)} ms=$ms") }
            is LocalMatch.Miss -> null.also { log("miss ${r.reason} ms=$ms") }
        }
    }

    private fun log(line: String) = Log.i(TAG, line)

    companion object {
        private const val TAG = "LIVE_LOCAL"
        private const val SAMPLE_RATE = 16_000f
        private const val MODEL_DIR = "vosk-model"

        /** How the recogniser spells words the matcher reads without their apostrophe. */
        private val SPOKEN_FORMS = setOf("that's", "that'll", "o'clock", "it's")

        @Volatile private var loaded: Model? = null

        @Synchronized
        @Suppress("ReturnCount") // loaded, not copied yet, or load now
        private fun model(context: Context): Model? {
            loaded?.let { return it }
            val dir = File(context.filesDir, MODEL_DIR)
            if (!File(dir, "am/final.mdl").exists()) return null
            return runCatching { Model(dir.absolutePath) }.getOrNull()?.also { loaded = it }
        }

        /** Vosk's final JSON to an [Utterance]: the text and each word's confidence. */
        internal fun parse(json: String): Utterance {
            val o = JSONObject(json)
            val words = o.optJSONArray("result")
            val conf = words?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optDouble("conf", 0.0).toFloat() }
            }
            return Utterance(o.optString("text"), conf)
        }
    }
}
