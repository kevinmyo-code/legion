package com.kevin.legion.ui.sync

/**
 * Pure UI-state derivation for Google grant status/failure copy. Started life as
 * `DriveConnectResolver`, Drive-only, backing [com.kevin.legion.ui.DriveSyncScreen] - the
 * "Connect Google Drive" screen's state derivation (traced 2026-08-03:
 * [com.kevin.legion.ai.CompanionProfile.setSyncEnabled] had zero callers and nothing ever
 * launched the consent [android.app.PendingIntent] [com.kevin.legion.sync.DriveAuth.authorize]
 * returns, so `sync/` had never executed). Generalised 2026-08-13 (ticket
 * `.scratch/google-account-integration/issues/12-google-grant-plumbing.md`) to a per-[Grant]
 * resolver shared by [com.kevin.legion.ui.DriveSyncScreen] (still the one screen that actually
 * performs a Drive connect/disconnect round trip) and the new `GoogleAccessScreen` (the GOOGLE
 * row's read status across all three grants, ticket 06's Answer §2) - one resolver, three
 * grants, not three resolvers drifting apart (ticket 06 Answer §5).
 *
 * Kept Android-free on purpose, same shape as
 * [com.kevin.legion.ui.ledger.LedgerEmptyStateResolver] and
 * [com.kevin.legion.ui.assistant.AssistantStripResolver]: it takes only the
 * booleans/strings/status-codes the caller already has in hand, never
 * [com.kevin.legion.sync.DriveAuth.Outcome] or [com.kevin.legion.sync.DriveAuth.TokenResult]
 * themselves (whose consent arms carry real Android/GMS types - a [android.app.PendingIntent]
 * that only exists on-device), so this stays a plain JVM unit test target, no Robolectric.
 */
object GoogleGrantResolver {

    /**
     * Which Google grant a message is about, so copy names the right service instead of
     * hardcoding Drive - the one thing this class always did before there was more than one
     * grant to talk about.
     */
    enum class Grant(val label: String) {
        DRIVE("Google Drive"),
        GMAIL("Gmail"),
    }

    /** What the screen should offer, given the two signals only the screen combines. */
    enum class Availability {
        /** No Play Services on this device - sync structurally cannot run here. No connect action offered. */
        UNAVAILABLE,
        /** Play Services present, Drive has not been connected yet. */
        DISCONNECTED,
        /** Play Services present, Drive is connected. */
        CONNECTED,
    }

    /**
     * [playServicesAvailable] false always wins - a device with no working Play
     * Services install cannot run the Identity Authorization API or Drive at
     * all ([com.kevin.legion.sync.SyncCapability]'s own doc comment), so
     * offering a CONNECT button that can never work would be worse than
     * saying so plainly.
     */
    fun availability(playServicesAvailable: Boolean, syncEnabled: Boolean): Availability = when {
        !playServicesAvailable -> Availability.UNAVAILABLE
        syncEnabled -> Availability.CONNECTED
        else -> Availability.DISCONNECTED
    }

    /** Whether Play Services being unavailable should also be stated as the reason [grant]
     * can't run, not just silently omitted. */
    fun unavailableMessage(grant: Grant): String =
        "Google Play Services isn't available on this device. ${grant.label} can't run here."

    /** Fixed copy for a successful connect/consent round trip. Shared so the wording lives in one place. */
    const val CONNECTED_MESSAGE = "Connected to Google Drive."

    /** Fixed copy for a plain user cancel - not an error, nothing to diagnose. */
    const val CANCELLED_MESSAGE = "Drive wasn't connected. Nothing was turned on."

    /**
     * Category of a connect/consent failure - both for picking plain-language copy
     * and for whether the screen should draw the message in the quarantine color.
     */
    enum class FailureCategory {
        /** This build's package + signing SHA-1 aren't registered for Google access with Google. A setup problem; retrying will not fix it on its own. */
        CONFIG,
        /** Looks like a plain connectivity failure (offline, DNS, timeout) that never reached Play Services. Retrying once back online might work. */
        NETWORK,
        /**
         * The grant lapsed (7-day Testing-status expiry, ticket 01) or was revoked in the
         * driver's own Google account - [com.kevin.legion.sync.DriveAuth.Outcome.NeedsConsent] /
         * [com.kevin.legion.sync.DriveAuth.TokenResult.NeedsConsent], not a Play Services error
         * at all. This is the case that never existed before ticket 12: a lapsed grant used to
         * collapse into [NETWORK] (or a plain null with no category at all), reading as a
         * transient blip instead of what it is - see [needsReauthorisingMessage].
         */
        NEEDS_CONSENT,
        /** Neither of the above. Falls back to showing the underlying message so a failure is never silently swallowed, even unmapped ones. */
        UNKNOWN,
    }

    /** A failure category paired with the exact line the screen shows for it. */
    data class ConnectFailure(val category: FailureCategory, val message: String)

    // Raw values behind com.google.android.gms.common.api.CommonStatusCodes.{DEVELOPER_ERROR,NETWORK_ERROR}.
    // Kept as literal Ints, not an import, so this file stays a plain JVM unit-test target with
    // zero GMS/Android dependency - com.kevin.legion.sync.DriveAuth is the one place that actually
    // unwraps an ApiException and hands [diagnose] the bare code number via [DriveAuth.statusCodeOf].
    private const val STATUS_DEVELOPER_ERROR = 10
    private const val STATUS_NETWORK_ERROR = 7

    /**
     * The line shown for [FailureCategory.NEEDS_CONSENT]: a grant that existed and lapsed or
     * was revoked, distinct from a driver who has simply never connected [grant] yet (that case
     * is [Availability.DISCONNECTED], not a failure at all). This is the fix ticket 06 point 4
     * demanded and ticket 01 proved live: `DriveAuth.accessTokenOrNull()` collapsed exactly this
     * outcome to `null`, and `SyncEngine` swallowed a null by design as "cannot sync right now" -
     * so a lapsed or revoked grant read identically to never having connected at all.
     */
    fun needsReauthorisingMessage(grant: Grant): String =
        "${grant.label} needs re-authorising. It's in Setup, under Google."

    /**
     * Turns the diagnostic signals [com.kevin.legion.sync.DriveAuth] pulled off a
     * connect/consent failure into a specific, actionable [ConnectFailure]. This is
     * the fix for the 2026-08-03 defect: every failure used to collapse into the same
     * "wasn't connected" line, so a DEVELOPER_ERROR (this build's package name and
     * signing certificate not registered with Google) read identically to a plain
     * cancel, and there was no way for Kevin to tell which one he was looking at.
     *
     * [grant] names which service the message is about. [statusCode] is
     * [com.google.android.gms.common.api.ApiException.getStatusCode] when the failure
     * came from Play Services (via [com.kevin.legion.sync.DriveAuth.statusCodeOf]), null
     * otherwise. [isNetworkException] flags a plain `java.io.IOException` (offline, DNS,
     * timeout) that never reached Play Services at all (via
     * [com.kevin.legion.sync.DriveAuth.looksLikeNetworkFailure]). [fallbackMessage] is the
     * raw [Throwable.message], shown verbatim under [FailureCategory.UNKNOWN] so an
     * unmapped failure is still legible instead of swallowed.
     *
     * A lapsed/revoked grant does NOT come through here - [com.kevin.legion.sync.DriveAuth.TokenResult.NeedsConsent]
     * has no status code and no exception to diagnose, it is a distinct outcome. Callers
     * that see it use [needsReauthorisingMessage] directly, never this function.
     */
    fun diagnose(grant: Grant, statusCode: Int?, isNetworkException: Boolean, fallbackMessage: String?): ConnectFailure = when {
        statusCode == STATUS_DEVELOPER_ERROR -> ConnectFailure(
            FailureCategory.CONFIG,
            "This build's package name and signing certificate aren't registered for Google " +
                "access yet. That's a setup problem with this build, not something retrying " +
                "will fix. This build is not registered with Google - Drive sync and Gmail " +
                "will not work in a copy of this app built from source; you would need to " +
                "register your own OAuth client. Calendar is unaffected and keeps working, " +
                "since it uses no OAuth at all.",
        )
        statusCode == STATUS_NETWORK_ERROR || isNetworkException -> ConnectFailure(
            FailureCategory.NETWORK,
            "Couldn't reach Google. Check your connection and try again.",
        )
        else -> ConnectFailure(
            FailureCategory.UNKNOWN,
            "Couldn't connect" + (fallbackMessage?.let { ": $it" } ?: "."),
        )
    }

    // grant is unused in the NETWORK/UNKNOWN branches above on purpose: those two lines were
    // already generic before ticket 12 (neither ever named Drive), so "every existing behaviour
    // preserved" means leaving their wording exactly alone rather than inventing a per-grant
    // variant nobody asked for. It IS load-bearing for CONFIG (which used to say "Google Drive
    // access" and now has to be accurate for a Gmail failure too) and for [needsReauthorisingMessage].
}
