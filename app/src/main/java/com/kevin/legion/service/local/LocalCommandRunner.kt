package com.kevin.legion.service.local

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.ui.MainActivity
import java.util.Locale
import org.json.JSONObject

/**
 * Carries out a [LocalAction] on the phone and returns the words to say (phase 2). A tool runs
 * through `LiveToolbox.dispatch`, the same code Alfred's call runs (ADR 0035), and the tool's own
 * result message is what is said, so a refusal ("Nothing was set") is said as plainly as a success.
 * A screen opens through the app's deep link. Nothing here asks a model anything.
 */
object LocalCommandRunner {

    suspend fun run(context: Context, action: LocalAction): String = when (action) {
        is LocalAction.Tool -> {
            val out = LiveToolbox.dispatch(context, action.name, JSONObject(action.args))
            spoken(out)
        }
        is LocalAction.Open -> {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_ROUTE, action.route)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            "Opening ${action.screen}."
        }
        LocalAction.Dismiss -> ""
    }

    /** The tool's own words: its message, else "Done." on success, else that nothing happened. */
    internal fun spoken(out: JSONObject?): String {
        val message = out?.optString("message").orEmpty().trim()
        return when {
            message.isNotEmpty() -> message
            out?.optBoolean("success", false) == true -> "Done."
            else -> "That didn't work, so nothing was changed."
        }
    }
}

/**
 * On-device text to speech for a local command's short reply (no Live socket, no model). Lazily
 * made once; a reply asked for before the engine is ready waits for it.
 */
object LocalSpeaker {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pending: String? = null

    @Synchronized
    fun say(context: Context, text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (engine != null && ready) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "local-command")
            return
        }
        pending = text
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
        }
    }

    @Synchronized
    private fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        val engine = tts ?: return
        if (ready) {
            engine.language = Locale.US
            pending?.let { engine.speak(it, TextToSpeech.QUEUE_FLUSH, null, "local-command") }
        }
        pending = null
    }
}
