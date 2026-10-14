package com.kevin.midnightai.billing

import android.content.Context

/**
 * Whether the driver has bought the one-time `unlock_byo` Play Billing
 * product (CLAUDE.md sec 2, 2026-07-16 rewrite: two tiers, no trial, no
 * subscription, no broker). Purchasing it doesn't touch any server - it just
 * means "let this driver into the BYO-key entry screen" (see [BillingManager]);
 * once they save their own key, [com.kevin.midnightai.ai.GeminiKeyProvider]
 * takes over and this flag is no longer consulted for anything.
 */
object UnlockState {
    private const val PREFS = "unlock_state"
    private const val KEY_UNLOCKED = "byo_unlocked"

    fun isUnlocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_UNLOCKED, false)

    fun markUnlocked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_UNLOCKED, true).apply()
    }
}
