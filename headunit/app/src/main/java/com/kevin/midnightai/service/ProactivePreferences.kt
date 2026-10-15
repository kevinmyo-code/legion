package com.kevin.midnightai.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the driver has muted proactive chatter (idle chatter, arrival/
 * reminder proactives, health/recall alerts - everything [ProactiveBus]
 * routes). Plain app-global SharedPreferences.
 *
 * Driver-initiated speech (tap avatar, PTT, the DTC sheet's "ASK" button)
 * is untouched - this only silences what the companion says unprompted,
 * same as muting turn-by-turn voice in Google/Waze without muting your own
 * voice search.
 */
object ProactivePreferences {
    private const val PREFS = "proactive_preferences"
    private const val KEY_MUTED = "muted"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** Call once, early (e.g. Application/Activity init), to seed [muted] from disk. */
    fun init(context: Context) {
        _muted.value = prefs(context).getBoolean(KEY_MUTED, false)
    }

    fun isMuted(context: Context): Boolean = prefs(context).getBoolean(KEY_MUTED, false)

    fun setMuted(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUTED, value).apply()
        _muted.value = value
    }

    fun toggle(context: Context) = setMuted(context, !isMuted(context))

    /**
     * The tool result for the `set_proactive_mute` voice tool (2026-10-15, root CLAUDE.md section 7
     * compulsion test clause (d): an unsolicited raise must be silenceable forever in one
     * instruction). [requested] is what the driver asked for; [actual] is [isMuted] READ BACK after
     * the write, so the sentence reports the observed state and not the request. Returns
     * (success, message). Pure so the wording is a JVM test.
     */
    internal fun voiceResult(requested: Boolean, actual: Boolean): Pair<Boolean, String> = when {
        actual != requested ->
            false to "I couldn't change that: unprompted remarks are still ${if (actual) "muted" else "on"}."
        actual ->
            true to "Done: no more unprompted remarks from me - no idle chatter, reminders or alerts " +
                "- until you ask me to start again. Anything you ask for still works."
        else ->
            true to "Done: unprompted remarks are back on."
    }
}
