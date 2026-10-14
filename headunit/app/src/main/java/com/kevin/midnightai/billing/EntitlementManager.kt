package com.kevin.midnightai.billing

import android.content.Context
import com.kevin.midnightai.ai.GeminiKeyProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the app's [RuntimeMode].
 *
 * Per CLAUDE.md sec 2 (2026-07-16 pricing rewrite): two tiers, no trial, no
 * subscription, no broker, no network calls of any kind. Mode is purely a
 * local function of whether a Gemini key has been saved
 * ([GeminiKeyProvider.hasKey]).
 *
 * "The base app is never gated by this" is NOT true: per sec 2, **OBD
 * history** is behind the $10 unlock (free sees its last drive in full). That
 * gate is value capture, not a serving cost - the data is local and free to
 * serve - and it is the one deliberate exception to "the natural cost
 * boundary IS the paywall."
 *
 * Call [init] once at app start (alongside [GeminiKeyProvider.init]). Call
 * [refresh] after anything that could change the key (key saved) so mode
 * updates immediately.
 */
object EntitlementManager {
    private val _mode = MutableStateFlow(RuntimeMode.AI_PAUSED)
    val mode: StateFlow<RuntimeMode> = _mode.asStateFlow()

    fun init(context: Context) {
        recomputeFromKeyState()
    }

    /** Re-derive mode after something outside init changed (key saved, purchase completed). */
    fun refresh() {
        recomputeFromKeyState()
    }

    // Every gate is GeminiKeyProvider.hasKey() directly, not mode.value, because
    // hasKey() is synchronous ground truth with no dependency on init() ordering -
    // if the process ever starts service-first (boot) without MainActivity
    // running init(), mode stays at its AI_PAUSED default, and a BYO driver's
    // tools must not silently block on that stale flag.

    /** True if the current key state allows starting a Live voice session. */
    fun canStartVoice(): Boolean = GeminiKeyProvider.hasKey()

    /** True if the current key state allows sub-agent diagnosis/triage/maintenance/music-recommend or narrative gen. */
    fun canUseSubAgent(): Boolean = GeminiKeyProvider.hasKey()

    private fun recomputeFromKeyState() {
        _mode.value = if (GeminiKeyProvider.hasKey()) RuntimeMode.BYO_KEY else RuntimeMode.AI_PAUSED
    }
}
