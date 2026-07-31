package com.kevin.legion.location

import android.content.Context

/**
 * Optional manual address of the beacon phone, for when auto-discovery cannot
 * work or does not.
 *
 * **Why this exists (2026-07-25).** The original link assumed the beacon phone WAS
 * the hotspot's access point, which made discovery free: the head unit's default
 * gateway is the AP by definition. That assumption broke on Kevin's actual rig -
 * the hotspot comes from an iPhone (the eSIM lives there) which cannot run this
 * app, and the beacon is an Oppo A17K with no cellular data joined to that same
 * hotspot. Beacon and head unit are now peer clients; the gateway is a device that
 * will never answer.
 *
 * [BeaconClient] therefore probes several addresses. Broadcast covers the peer case
 * automatically, but broadcast is exactly the traffic an access point is free to
 * drop - client isolation is a real setting on real hotspots, and iOS Personal
 * Hotspot's behaviour here is not something we can verify from code. This is the
 * escape hatch when it does: read the beacon phone's IP off its own Setup screen,
 * type it here, and discovery is skipped entirely.
 *
 * Blank (the default) means auto-discover. Per-device, never synced, same as
 * [com.kevin.legion.service.DeviceRole].
 */
object BeaconPreferences {
    private const val PREFS = "beacon_prefs"
    private const val KEY_PEER = "manual_peer"

    /** The driver's typed beacon address, or null for auto-discovery. */
    fun manualPeer(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PEER, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setManualPeer(context: Context, address: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cleaned = address?.trim().orEmpty()
        if (cleaned.isEmpty()) prefs.edit().remove(KEY_PEER).apply()
        else prefs.edit().putString(KEY_PEER, cleaned).apply()
    }
}
