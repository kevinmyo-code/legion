package com.kevin.legion

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.kevin.legion.ai.CompanionProfile

/**
 * Application subclass registered in the manifest via android:name=".MidnightApplication".
 *
 * Firebase is initialized here rather than lazily because Crashlytics must be
 * live before any Activity or Service starts - otherwise a crash during service
 * startup is lost. The [FirebaseApp.initializeApp] call is idempotent; calling it
 * more than once is safe.
 */
class MidnightApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Lives here, NOT in MainActivity.onCreate: AriaForegroundService can
        // start (and dispatch a voice-triggered restyle_avatar/restyle_background
        // through LiveToolbox) in a process that never created MainActivity at
        // all - the service outlives the Activity by design (CLAUDE.md sec 4.4).
        // GenerationMeter.recordImage() silently no-ops when appContext is null
        // (`prefs(appContext ?: return)`), so if init only ran from MainActivity
        // a service-only process start would undercount every image generated in
        // it forever, with no error and no signal that anything was wrong - the
        // exact "reports succeeded but part of the work didn't happen" pattern
        // this meter exists to avoid in the first place. Application.onCreate
        // runs for EVERY process start, service-only included, so this is the
        // one place that's guaranteed to run before any generation can fire.
        // Do not move this back to MainActivity - init() is idempotent, so
        // calling it again there would be harmless but pointless.
        com.kevin.legion.billing.GenerationMeter.init(this)

        try {
            FirebaseApp.initializeApp(this)
            // NOTE: no App Check / Play Integrity here. That only existed for the
            // entitlement broker's Cloud Functions callables, which were removed
            // (CLAUDE.md sec 2/8, 2026-07-16 rewrite: two tiers, no broker). Firebase
            // is initialized here solely for Crashlytics.
            // Crash collection is ALWAYS on (2026-07-19). It used to be
            // `!BuildConfig.DEBUG` to keep dev-machine noise out of the dashboard -
            // but every field build sideloaded onto the head unit up to that point
            // WAS a debug build, so field telemetry was completely off on the real
            // car. That single line is why the 2026-07-16 and 2026-07-19 drives both
            // produced ZERO retrievable Crashlytics data for the B9/B10/B12 mic bug
            // despite instrumentation existing for it. Dev noise is now handled by
            // FILTERING, not suppression: the custom keys below (build_type /
            // device_model / is_emulator) let the dashboard separate head-unit
            // events from emulator/dev-machine runs.
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = true
            // Tag every crash report with the active setup so triage doesn't need
            // to guess. Self-guarded (MidnightEvents.safe), so a not-ready Firebase
            // here can't take down app startup.
            MidnightEvents.setBuildContext(
                buildType = if (BuildConfig.DEBUG) "debug" else "release",
                deviceModel = android.os.Build.MODEL ?: "unknown",
                isEmulator = isProbablyEmulator(),
            )
            MidnightEvents.setCompanionName(CompanionProfile.name(this).ifBlank { "unset" })
            MidnightEvents.setHasGeminiKey(CompanionProfile.hasGeminiKey(this))
            MidnightEvents.setGlEsVersion(rawGlEsVersionHex())
        } catch (e: Exception) {
            android.util.Log.w("MidnightApp", "Firebase init failed — crashlytics offline: ${e.message}")
        }
    }

    /**
     * Raw `reqGlEsVersion` as hex (e.g. "0x30000" for ES 3.0), the same field
     * `NavCapability.supportsEmbeddedNav` gates on. Surfaced via Crashlytics
     * custom key because ADB is blocked on the head unit (CLAUDE.md sec 14).
     */
    private fun rawGlEsVersionHex(): String {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val version = am?.deviceConfigurationInfo?.reqGlEsVersion ?: return "unknown"
        return "0x%08x".format(version)
    }

    /**
     * Cheap emulator heuristic for the `is_emulator` Crashlytics key. Standard
     * fingerprint/model markers; false negatives are fine (an unrecognized
     * emulator just shows up as a device and gets filtered by model instead).
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
