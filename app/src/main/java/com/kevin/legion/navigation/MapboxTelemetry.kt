package com.kevin.legion.navigation

import android.util.Log
import com.mapbox.common.TelemetryUtils

/**
 * The Mapbox SDK's own telemetry switch (mapbox-nav ticket 11, research 01 section 6): the Navigation
 * and Maps SDKs report anonymous usage to Mapbox unless collection is turned off. This is the SDK's
 * own opt-out (`TelemetryUtils.setEventsCollectionState`), not an app-side flag, so the state read
 * back is the SDK's, and the SDK keeps it between launches (reasoned from its API; not observed).
 *
 * The calls are native. Where the library cannot load (a JVM test, an unsupported ABI) [isOn] is
 * null and the Setup row says so in words rather than showing a switch that does nothing.
 */
object MapboxTelemetry {
    private const val TAG = "MapboxTelemetry"

    /** Whether the SDK is sharing usage data now; null when the SDK's native library is unavailable. */
    fun isOn(): Boolean? = try {
        TelemetryUtils.getEventsCollectionState()
    } catch (e: LinkageError) {
        Log.w(TAG, "telemetry state unreadable: ${e.message}")
        null
    }

    /** Asks the SDK to share (or stop sharing) usage data. The caller re-reads [isOn] for the truth. */
    fun set(on: Boolean) {
        try {
            TelemetryUtils.setEventsCollectionState(on) { /* the state is read back with isOn() */ }
        } catch (e: LinkageError) {
            Log.w(TAG, "telemetry state not set: ${e.message}")
        }
    }
}
