package com.kevin.midnightai

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.kevin.midnightai.ai.CompanionProfile

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
        // First, so every MidnightEvents breadcrumb from here on lands in the on-disk drive log
        // (diag/DriveLog) that is uploaded after each drive. Self-guarded: never throws.
        try {
            com.kevin.midnightai.diag.DriveLog.init(this)
        } catch (e: Exception) {
            android.util.Log.w("MidnightApp", "drive log failed to start: ${e.message}")
        }
        // Captured before StartupTrace/Crashlytics exist, then recorded as spans once they do.
        val tTop = android.os.SystemClock.elapsedRealtime()

        // Before anything reads the companion name (including the Crashlytics key
        // below): installs seeded with the retired "Zero" default become Uncle Moose
        // (one-shot; see the function's doc).
        try {
            CompanionProfile.migrateRetiredDefaultName(this)
        } catch (e: Exception) {
            android.util.Log.w("MidnightApp", "name migration failed: ${e.message}")
        }

        val tFirebaseStart = android.os.SystemClock.elapsedRealtime()
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
        } catch (e: Exception) {
            android.util.Log.w("MidnightApp", "Firebase init failed — crashlytics offline: ${e.message}")
        }
        val tFirebaseEnd = android.os.SystemClock.elapsedRealtime()

        // The app's own clock and zone (2026-10-13 car report: the head unit's clock/zone reset
        // themselves). Before any screen or service reads "now"; after Firebase so its
        // Crashlytics keys land. Network sync runs off-thread.
        try {
            com.kevin.midnightai.time.TrustedClock.init(this)
        } catch (e: Exception) {
            android.util.Log.w("MidnightApp", "trusted clock failed to start: ${e.message}")
        }

        // Before any screen: the home-screen PhoneLinkChip reads its StateFlow
        // and must show live state without the manager screen ever opening.
        // Deliberately AFTER Firebase init (white-screen hunt): this does Bluetooth binder
        // work on the main thread, and a crash in it used to happen before Crashlytics
        // existed, so it would have been invisible on a head unit with no ADB.
        // Startup forensics (see StartupTrace): process-level context first, then the stall
        // watchdog, so everything below it is covered by both.
        StartupTrace.processStarted()
        StartupTrace.startWatchdog()
        // app_pre_firebase = super.onCreate + the retired-name migration (SharedPreferences reads);
        // firebase_init = initializeApp + Crashlytics keys incl. the Keystore-backed hasGeminiKey
        // and the GL ES read. Both were unmeasured because StartupTrace could not exist yet.
        StartupTrace.recordSpan("app_pre_firebase", tFirebaseStart - tTop)
        StartupTrace.recordSpan("firebase_init", tFirebaseEnd - tFirebaseStart)
        // NOTE: since PhoneLinkManager.init moved its work to a worker, this only measures the
        // HandlerThread start + post; the real work shows up as phonelink_refresh_ms_max.
        StartupTrace.span("phonelink_init_dispatch") { com.kevin.midnightai.bluetooth.PhoneLinkManager.init(this) }
        // Off-thread one-shot: deletes the Vosk model that the removed wake word left in filesDir.
        com.kevin.midnightai.util.LegacyCleanup.runAsync(this)
        // Re-applies hardware/steering volume-key changes through the app's working
        // AudioManager path (2026-10-13 field report; see VolumeSync).
        try {
            com.kevin.midnightai.media.VolumeSync.start(this)
        } catch (e: Exception) {
            android.util.Log.w("MidnightApp", "volume sync failed to start: ${e.message}")
        }
        StartupTrace.mark("app_create_end")
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
