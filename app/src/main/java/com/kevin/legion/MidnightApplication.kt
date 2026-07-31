package com.kevin.legion

import android.app.Application
import com.kevin.legion.ai.CompanionProfile

/**
 * Application subclass registered in the manifest via android:name=".MidnightApplication".
 *
 * Firebase (Crashlytics) is NOT wired up yet - no `google-services.json`, no
 * Firebase dependency (see README.md). [MidnightEvents] logs via `Log.d` until a
 * fresh Firebase project exists for this app. GenerationMeter (billing-tier image
 * quota tracking) was retired with the rest of billing/ in the 2026-07-31 pivot.
 */
class MidnightApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        MidnightEvents.setBuildContext(
            buildType = if (BuildConfig.DEBUG) "debug" else "release",
            deviceModel = android.os.Build.MODEL ?: "unknown",
            isEmulator = isProbablyEmulator(),
        )
        MidnightEvents.setCompanionName(CompanionProfile.name(this).ifBlank { "unset" })
        MidnightEvents.setHasGeminiKey(CompanionProfile.hasGeminiKey(this))
        MidnightEvents.setGlEsVersion(rawGlEsVersionHex())
    }

    /** Raw `reqGlEsVersion` as hex (e.g. "0x30000" for ES 3.0). */
    private fun rawGlEsVersionHex(): String {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val version = am?.deviceConfigurationInfo?.reqGlEsVersion ?: return "unknown"
        return "0x%08x".format(version)
    }

    /**
     * Cheap emulator heuristic. Standard fingerprint/model markers; false
     * negatives are fine (an unrecognized emulator just shows up as a device).
     */
    private fun isProbablyEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT ?: ""
        val model = android.os.Build.MODEL ?: ""
        val product = android.os.Build.PRODUCT ?: ""
        return fp.startsWith("generic") || fp.contains("emulator") ||
            model.contains("Emulator") || model.contains("Android SDK built for") ||
            product.contains("sdk_gphone") || product == "google_sdk"
    }
}
