package com.kevin.legion.update

/**
 * The updater's decisions, pure so a plain JVM test covers each one (alfred-ota ticket 05, ADR
 * 0072). Every refusal is a sentence that says what did NOT happen (CLAUDE.md section 7).
 */
object UpdateRules {

    /** `debug` builds keep versionCode 1 (app/build.gradle.kts) and never update themselves. */
    const val DEBUG_VERSION_CODE = 1L

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
     * The download gate. The APK is 180-220 MB, so Wi-Fi (an unmetered network) only, unless the
     * user switched "Allow mobile data" on. Null when the download may start.
     */
    fun downloadRefusal(online: Boolean, unmetered: Boolean, allowMobileData: Boolean): String? = when {
        !online -> "Not downloaded: the phone is offline."
        !unmetered && !allowMobileData ->
            "Not downloaded: the update downloads on Wi-Fi only, and the phone is on mobile data. " +
                "Settings, Updates can allow mobile data."
        else -> null
    }

    /**
     * The file check before anything is offered for install. Every mismatch refuses, the file is
     * discarded, and the sentence says so. [apk] null means Android could not read the file as an
     * APK at all.
     */
    @Suppress("ReturnCount") // one refusal per check, in the order they are worth saying
    fun verifyRefusal(
        release: ReleaseInfo,
        fileSha256: String,
        apk: ApkFacts?,
        installed: InstalledApp,
        ownPackage: String,
    ): String? {
        if (!fileSha256.equals(release.sha256, ignoreCase = true)) {
            return "Not installed: the downloaded file does not match the release's checksum, so it was " +
                "discarded. Nothing changed on the phone."
        }
        if (apk == null) return "Not installed: Android could not read the download as an app, so it was discarded."
        if (apk.packageName != ownPackage) {
            return "Not installed: the download is a different app (${apk.packageName}), so it was discarded."
        }
        if (installed.signerSha256s.isEmpty() || apk.signerSha256s != installed.signerSha256s) {
            return "Not installed: the download is not signed with the same key as the LEGION on this phone, " +
                "so it was discarded. Android would refuse it, and so does LEGION."
        }
        if (release.signerSha256.lowercase() !in apk.signerSha256s) {
            return "Not installed: the download's signer is not the one the release names, so it was discarded."
        }
        if (apk.versionCode != release.versionCode || apk.versionCode <= installed.versionCode) {
            return "Not installed: the download is version ${apk.versionCode}, not newer than the " +
                "installed ${installed.versionCode}, so it was discarded. LEGION never downgrades."
        }
        return null
    }

    /**
     * The install gate (map decision 3). Never in Matrix mode or with the car connected: those
     * refuse. Mid-conversation is not a refusal, it is a wait: the caller holds the yes and
     * installs when the conversation ends. Null when it may install now.
     */
    fun installRefusal(matrixOn: Boolean, carConnected: Boolean, mayInstallPackages: Boolean): String? = when {
        matrixOn -> "Not installed: Matrix mode is on. Install it after you leave Matrix mode."
        carConnected -> "Not installed: the car is connected. Install it when you are out of the car."
        !mayInstallPackages ->
            "Not installed: LEGION is not allowed to install apps yet. In Settings, Updates, tap " +
                "Allow installs and switch it on, then ask again."
        else -> null
    }

    /** The spoken ask waits for a natural pause: none of these. */
    fun mayAskNow(conversationBusy: Boolean, matrixOn: Boolean, carConnected: Boolean): Boolean =
        !conversationBusy && !matrixOn && !carConnected

    /**
     * A voice yes counts only when the update was offered to the user moments ago (the tool's own
     * ask, or the spoken announcement), and not in the same breath: at least [MIN_OFFER_AGE_MS]
     * after it, the time it takes to hear the question and answer it. Code, not only a prompt
     * rule, so the model can neither install on a yes to something else nor ask and install in one
     * turn with no answer between.
     */
    fun offerStillOpen(offeredAtMs: Long?, nowMs: Long, windowMs: Long = OFFER_WINDOW_MS): Boolean =
        offeredAtMs != null && nowMs - offeredAtMs in MIN_OFFER_AGE_MS..windowMs

    const val OFFER_WINDOW_MS = 3 * 60_000L
    const val MIN_OFFER_AGE_MS = 2_000L

    /** A yes given mid-conversation installs when it ends, within this long; after that it lapses. */
    const val PENDING_INSTALL_MS = 15 * 60_000L

    /** "LEGION 2026-11-05-ab12cd3: fixes the double announce" style line for the ask. */
    fun describe(release: ReleaseInfo): String {
        val notes = release.notes.trim().trimEnd('.')
        return if (notes.isEmpty()) "LEGION ${release.versionName}" else "LEGION ${release.versionName}: $notes"
    }
}
