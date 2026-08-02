package com.kevin.legion

import android.app.Application
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ledger.LedgerFolderPreferences
import com.kevin.legion.service.ProactivePreferences

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

        // Process-wide caches that are seeded from disk exactly once.
        //
        // These used to be seeded in AriaForegroundService.onCreate, which was
        // fine while that service started on its own. Ticket 07 made the
        // assistant an explicit user toggle that is OFF by default, so on a
        // normal launch that service never runs - and every one of these
        // silently stayed empty while its backing value sat on disk. Verified
        // on the A17K 2026-08-02: the ledger spend gate reported "no Gemini
        // key" for a key that was saved and present, and the connected
        // statements folder was forgotten on every process start.
        //
        // Application.onCreate is the correct home precisely because it does
        // not depend on any feature being switched on. Each init is a cheap
        // SharedPreferences read. AriaForegroundService still calls the first
        // two, which is harmless - they are idempotent - and is left alone so
        // the assistant path does not depend on this ordering.
        GeminiKeyProvider.init(this)
        ProactivePreferences.init(this)
        LedgerFolderPreferences.init(this)

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
