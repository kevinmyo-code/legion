package com.kevin.midnightai.update

/**
 * The updater's decisions, pure so a plain JVM test covers each one (alfred-ota ticket 08, ADR
 * 0072). Copied from the phone's `com.kevin.legion.update.UpdateRules` and changed where a car is
 * not a phone: the phone refuses "the car is connected", and this unit is always in the car, so
 * the gate here is "the car is moving". Every refusal is a sentence that says what did NOT happen
 * (root CLAUDE.md section 7).
 */
object UpdateRules {

    /** `debug` builds keep versionCode 1 (app/build.gradle.kts) and never update themselves. */
    const val DEBUG_VERSION_CODE = 1L

    const val APP = "Midnight AI"

    /** What a release means for the app installed now. */
    sealed interface Offer {
        data object DebugBuild : Offer
        data object UpToDate : Offer
        data class Newer(val release: ReleaseInfo) : Offer
    }

    /** Never a downgrade: only a strictly higher versionCode is an update. */
    fun compare(installedCode: Long, release: ReleaseInfo?): Offer = when {
        installedCode <= DEBUG_VERSION_CODE -> Offer.DebugBuild
        release == null || release.versionCode <= installedCode -> Offer.UpToDate
        else -> Offer.Newer(release)
    }

    /**
     * The download gate. Unlike the phone this defaults to allowing a metered network: the unit's
     * internet is normally the phone's hotspot, which Android reports as metered, so a Wi-Fi-only
     * default would never download at all. The `fast` APK was 16.7 MB on 2026-11-06. Null when it may start.
     */
    fun downloadRefusal(online: Boolean, unmetered: Boolean, allowMetered: Boolean): String? = when {
        !online -> "Not downloaded: this unit is offline."
        !unmetered && !allowMetered ->
            "Not downloaded: updates are set to Wi-Fi only, and this unit is on a metered network " +
                "(the phone's hotspot). Settings, System, Updates can allow it."
        else -> null
    }

    /**
     * The file check before anything is offered for install. Every mismatch refuses, the file is
     * discarded, and the sentence says so. [apk] null means Android could not read the file as an
     * APK at all (also below Android 9, where the signer cannot be read, so nothing installs).
     */
    @Suppress("ReturnCount", "LongParameterList") // one refusal per check, in the order worth saying
    fun verifyRefusal(
        release: ReleaseInfo,
        fileSha256: String,
        apk: ApkFacts?,
        installed: InstalledApp,
        ownPackage: String,
        deviceAbis: List<String>,
    ): String? {
        if (!fileSha256.equals(release.sha256, ignoreCase = true)) {
            return "Not installed: the downloaded file does not match the release's checksum, so it was " +
                "discarded. Nothing changed on this unit."
        }
        if (apk == null) return "Not installed: Android could not read the download as an app, so it was discarded."
        if (apk.packageName != ownPackage) {
            return "Not installed: the download is a different app (${apk.packageName}), so it was discarded."
        }
        if (installed.signerSha256s.isEmpty() || apk.signerSha256s != installed.signerSha256s) {
            return "Not installed: the download is not signed with the same key as the $APP on this unit, " +
                "so it was discarded. Android would refuse it, and so does $APP."
        }
        if (release.signerSha256.lowercase() !in apk.signerSha256s) {
            return "Not installed: the download's signer is not the one the release names, so it was discarded."
        }
        if (apk.versionCode != release.versionCode || apk.versionCode <= installed.versionCode) {
            return "Not installed: the download is version ${apk.versionCode}, not newer than the " +
                "installed ${installed.versionCode}, so it was discarded. $APP never downgrades."
        }
        if (apk.abis.isNotEmpty() && apk.abis.none { it in deviceAbis }) {
            return "Not installed: the download is built for ${apk.abis.sorted().joinToString()}, and this " +
                "unit runs ${deviceAbis.joinToString().ifEmpty { "an unknown ABI" }}, so it was discarded. " +
                "Publish the matching APK."
        }
        return null
    }

    /**
     * The install gate. Never while the car is moving: installing closes the launcher for about a
     * minute. Mid-conversation is not a refusal, it is a wait: the caller holds the tap and
     * installs when the conversation ends. Null when it may install now.
     */
    fun installRefusal(driving: Boolean, mayInstallPackages: Boolean): String? = when {
        driving -> "Not installed: the car is moving. Install it when you are parked."
        !mayInstallPackages ->
            "Not installed: $APP is not allowed to install apps yet. In Settings, System, Updates, tap " +
                "Allow installs and switch it on, then tap Install again."
        else -> null
    }

    /** The Cruise chip (the install prompt) is never shown while the car is moving. */
    fun mayPromptNow(driving: Boolean): Boolean = !driving

    /**
     * Moving: any speed reading above [MOVING_KMH] within the last [DRIVING_HOLD_MS]. The hold
     * covers a red light: stopping for a minute is still driving. No reading at all (no OBD speed,
     * no GPS or beacon fix) is NOT driving, because there is nothing to say so; the prompt then
     * rests on the driver tapping it, which is a parked act.
     */
    fun isDriving(nowMs: Long, lastMovingAtMs: Long): Boolean =
        lastMovingAtMs > 0 && nowMs - lastMovingAtMs in 0..DRIVING_HOLD_MS

    const val MOVING_KMH = 5.0
    const val DRIVING_HOLD_MS = 2 * 60_000L

    /** A tap given mid-conversation installs when it ends, within this long; after that it lapses. */
    const val PENDING_INSTALL_MS = 15 * 60_000L

    /** An install that never reported back (a confirm left open) stops blocking a check after this. */
    const val INSTALL_STALE_MS = 10 * 60_000L

    /** "Midnight AI 1.0+ab12cd3: fixes the double announce" style line for the prompt. */
    fun describe(release: ReleaseInfo): String {
        val notes = release.notes.trim().trimEnd('.')
        return if (notes.isEmpty()) "$APP ${release.versionName}" else "$APP ${release.versionName}: $notes"
    }
}
