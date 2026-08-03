package com.kevin.legion.ai

import android.content.Context

/**
 * The DEVICE-LOCAL choice of which named [com.kevin.legion.data.local.CompanionProfileEntity]
 * is active (multi-companion, Kevin 2026-08-02).
 *
 * **This must NEVER sync, and is deliberately stored in its own SharedPreferences
 * file rather than folded into [CompanionProfile]'s.** Kevin's phone and his
 * wife's phone share one Google account and therefore the same ROSTER of
 * profile rows (via [com.kevin.legion.sync.SyncEngine]'s `companion_profiles`
 * registry entry), but each device picks its own ACTIVE one - Kevin wants an
 * Alfred-style assistant, his wife wants a different one, on the same account.
 * If this key rode along with anything that syncs, the two phones would fight
 * over which companion is "on" every time either one edited a profile, which
 * defeats the entire point of the feature. Keeping it in its own prefs file
 * (rather than a key inside [CompanionProfile]'s `companion_profile` prefs
 * file) makes the exclusion structural, not just a comment someone could miss
 * when a future sync pass starts snapshotting arbitrary SharedPreferences.
 *
 * Reads/writes are a plain profileId string; resolving that id to the actual
 * profile row, and pushing its fields into [CompanionProfile]'s legacy flat
 * keys so every existing reader keeps working, is [CompanionProfileStore]'s
 * job, not this object's.
 */
object ActiveCompanionProfile {
    private const val PREFS = "active_companion_profile"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The active profile's id on THIS device, or null if none has been chosen or migrated yet. */
    fun activeProfileId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_PROFILE_ID, null)

    /**
     * Switches the active profile on THIS device only. Callers must follow this
     * with [CompanionProfileStore.materializeActive] so [CompanionProfile]'s
     * flat keys actually reflect the switch - this setter is intentionally
     * dumb storage, it does not materialise anything itself.
     */
    fun setActiveProfileId(context: Context, profileId: String) {
        prefs(context).edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
    }
}
