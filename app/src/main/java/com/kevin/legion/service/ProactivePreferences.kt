package com.kevin.legion.service

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
     * Whether this device ever WROTE the mute key - i.e. whether there is an existing install to
     * migrate (ticket 04 call 3, `.scratch/proactive-mode/issues/04-categories-storage-and-surface.md`).
     *
     * [isMuted] cannot answer that. It returns the default `false` for a phone that has never seen
     * this app, which is indistinguishable from an existing install that was deliberately left
     * unmuted - and those two must migrate differently: an existing unmuted install keeps Safety,
     * Timing and Fleet on so nothing Kevin hears today goes away, while a fresh install comes up
     * quiet. **The absence of a value is the signal**, so this asks the map, not the getter.
     */
    fun hasStoredValue(context: Context): Boolean = prefs(context).contains(KEY_MUTED)
}
