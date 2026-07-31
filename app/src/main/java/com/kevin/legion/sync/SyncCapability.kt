package com.kevin.legion.sync

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.kevin.legion.ai.CompanionProfile

/**
 * Whether cross-device BYO-cloud sync (S1) can run on this install right now.
 * A hard gate checked before any sync work, so the feature is fully opt-in and
 * degrades to today's 100%-on-device behaviour with zero regression when
 * unavailable.
 *
 * Two conditions:
 *  - Google Play Services present (the Identity Authorization API and Drive both
 *    need it; some cheap AOSP head units lack a working Play Services - this is
 *    the head-unit auth risk called out in decisions.md 2026-07-14), and
 *  - the driver has connected their own Google Drive ([CompanionProfile.isSyncEnabled]).
 */
object SyncCapability {
    /** True if this device has a usable Google Play Services install. */
    fun playServicesAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** True only when Play Services is present AND the driver has opted in / connected Drive. */
    fun syncAvailable(context: Context): Boolean =
        playServicesAvailable(context) && CompanionProfile.isSyncEnabled(context)
}
