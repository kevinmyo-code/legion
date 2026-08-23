package com.kevin.legion.engine

import android.content.Context
import android.provider.Settings

/**
 * The scope key [com.kevin.legion.data.local.WidgetInstance.deviceId] stores (aspect-engine ticket
 * 08 answer point 2: "layouts are per-device"). `Settings.Secure.ANDROID_ID` is the right primitive
 * for this specifically because widget layout is explicitly NOT sync'd - unlike a Drive-BYO identity
 * (CLAUDE.md §2's "clone-and-run"), nothing here needs to survive a reinstall or match across
 * Kevin's two phones; a value that is stable for the life of one app install on one device is
 * exactly the granularity a home-screen arrangement is supposed to have. `ANDROID_ID` also asks for
 * no permission and needs no Play Services dependency, keeping this consistent with CLAUDE.md §7's
 * "no backend, ever" posture - this never leaves the device, and nothing here transmits it anywhere.
 */
object DeviceId {
    /** Never blank in practice on a real device - `ANDROID_ID` is populated at first boot - but a
     * blank/null read (an emulator misconfiguration, a future OS restriction) falls back to a fixed
     * literal rather than crashing the pager: a shared fallback device id is still a WORSE outcome
     * than a crash-free single-device experience, never a silent data-loss one, since layouts are
     * per-device and unsynced by design (this class's own doc) - the worst case is two profiles on
     * the same broken device sharing one arrangement, not data going anywhere it shouldn't. */
    fun current(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return if (id.isNullOrBlank()) "unknown-device" else id
    }
}
