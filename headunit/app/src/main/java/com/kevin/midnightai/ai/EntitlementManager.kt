package com.kevin.midnightai.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the app's [RuntimeMode]: purely a local function of whether a Gemini
 * key has been saved ([GeminiKeyProvider.hasKey]). No purchase, tier, trial or network call is
 * involved; the commercial model is dead (root ADR 0004). This file lived in `billing/` until
 * 2026-10-14, when the Play Billing unlock, `UnlockState` and the OBD-history paywall were
 * deleted; what is left is only the key check the AI gates read.
 *
 * Call [init] once at app start (alongside [GeminiKeyProvider.init]). Call [refresh] after
 * anything that could change the key (key saved) so mode updates immediately.
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
