package com.kevin.midnightai.ui

import com.kevin.midnightai.media.AppRemoteLink
import com.kevin.midnightai.media.SpotifyScopes
import com.kevin.midnightai.update.UpdateRules
import com.kevin.midnightai.update.UpdateState

/**
 * Status dot on a Settings rail item. [NONE] means "nothing honest to say", which is what a
 * static descriptor gets: a dot only appears when something real was read, so a green dot always
 * means a check actually passed.
 */
internal enum class SettingsDot { OK, WARN, ERROR, NONE }

/** One rail item's live line plus its dot. */
internal data class CategoryStatus(val line: String, val dot: SettingsDot = SettingsDot.NONE)

// Pure functions, kept free of android.* so the plain-JVM unit tests can exercise every branch:
// the screen reads the platform state, these decide what it MEANS.

/**
 * Music rail status, read from the same facts the Spotify leaf's own status block shows
 * ([spotifyStatusLines]) so the two can never disagree about whether Spotify is broken.
 *
 * Not having Spotify at all is not a fault (it is an optional power-user add-on), so that is a
 * neutral line with no dot; only a half-finished or broken setup earns attention.
 */
internal fun spotifyCategoryStatus(
    hasClientId: Boolean,
    webAuthorized: Boolean,
    grantedScope: String,
    playlistCount: Int,
    lastError: String?,
    appRemote: AppRemoteLink,
    appRemoteNotInstalled: Boolean = false,
): CategoryStatus = when {
    !hasClientId -> CategoryStatus("Spotify not set up")
    !webAuthorized -> CategoryStatus("Spotify signed out", SettingsDot.WARN)
    SpotifyScopes.anyMissing(grantedScope) -> CategoryStatus("Spotify missing permissions", SettingsDot.ERROR)
    // No Spotify app on the head unit is a fact about the unit, not something CONNECT fixes, so a
    // permanent WARN would only nag. Neutral, and stable through each resume's reconnect attempt.
    appRemoteNotInstalled && appRemote != AppRemoteLink.CONNECTED ->
        CategoryStatus("Playback: Spotify app not on this unit")
    // Search works but nothing can play: "play X" fails, so this is broken, not green. The fix is
    // App Remote's own consent (CONNECT in the leaf), not the Web API grant.
    appRemote == AppRemoteLink.CONNECTING -> CategoryStatus("Spotify playback connecting")
    appRemote == AppRemoteLink.DISCONNECTED ->
        CategoryStatus("Playback not connected - tap CONNECT", SettingsDot.WARN)
    playlistCount > 0 -> CategoryStatus("Spotify: $playlistCount playlists", SettingsDot.OK)
    lastError != null -> CategoryStatus("Spotify playlists failed to load", SettingsDot.ERROR)
    // Authorized with a complete grant but nothing fetched yet: not broken, not proven either.
    else -> CategoryStatus("Spotify connected, playlists not fetched")
}

/** True when the Music "Spotify" row should be the HOT fix-it button. */
internal fun spotifyNeedsAttention(status: CategoryStatus): Boolean =
    status.dot == SettingsDot.WARN || status.dot == SettingsDot.ERROR

/**
 * System rail status. [missing] is the count of the two grants the Permissions leaf lists
 * (mic+location, media access).
 */
internal fun permissionsCategoryStatus(missing: Int): CategoryStatus = when {
    missing <= 0 -> CategoryStatus("All permissions granted", SettingsDot.OK)
    missing == 1 -> CategoryStatus("1 permission missing", SettingsDot.WARN)
    else -> CategoryStatus("$missing permissions missing", SettingsDot.WARN)
}

/** The rows of Settings > System, top to bottom (HU-1 T7: Updates leads, the rest keep their order). */
internal enum class SystemRow { UPDATES, CLOCK, PERMISSIONS, PREFERENCES, LOGS, DEVELOPER_TOOLS, RESET }

internal fun systemRows(): List<SystemRow> = SystemRow.entries.toList()

/**
 * Where the updater is, as the Updates row's subtitle and the System rail line. [installedCode] is
 * this install's version code. A dot only for a state a check actually produced: Idle and Checking
 * have read nothing yet.
 */
internal fun updateStatusLine(state: UpdateState, installedCode: Long): CategoryStatus = when (state) {
    UpdateState.Idle -> CategoryStatus("Version $installedCode")
    UpdateState.Checking -> CategoryStatus("Checking for updates")
    UpdateState.DebugBuild -> CategoryStatus("Debug build · no updates")
    is UpdateState.UpToDate -> CategoryStatus("Up to date · $installedCode", SettingsDot.OK)
    is UpdateState.Available -> CategoryStatus("Update found · ${state.release.versionCode}")
    is UpdateState.Downloading -> CategoryStatus("Downloading update")
    is UpdateState.Ready -> CategoryStatus("Update ready · tap to install", SettingsDot.WARN)
    is UpdateState.Waiting -> CategoryStatus("Update installs after this chat")
    is UpdateState.Installing -> CategoryStatus("Installing update")
    // A check that could not reach the engine (often just offline at start) is a plain line with
    // no dot, so a start without the hotspot never nags; it still shows on the rail
    // ([systemCategoryStatus]). A refused download, checksum, signer, ABI or install is a real
    // problem with the next APK (recheck L6, 2026-11-20): ACTION NEEDED. The leaf says which.
    is UpdateState.Failed -> if (state.sentence.startsWith(UpdateRules.CHECK_FAILED)) {
        CategoryStatus("Last update check failed")
    } else {
        CategoryStatus("Update refused · open Updates", SettingsDot.WARN)
    }
}

/**
 * The System rail line: a missing permission outranks everything (the app half-works without it);
 * otherwise the update line once the updater has read something, else the permissions line.
 */
internal fun systemCategoryStatus(
    permissions: CategoryStatus,
    update: CategoryStatus,
    updateFailed: Boolean = false,
): CategoryStatus = when {
    permissions.dot == SettingsDot.WARN || permissions.dot == SettingsDot.ERROR -> permissions
    update.dot != SettingsDot.NONE -> update
    // A failed check has no dot but must still be seen: the rail says it instead of "All granted".
    updateFailed -> update
    else -> permissions
}

/**
 * Engine rail status. [verifiedEmail] is non-null only once the engine itself confirmed the
 * token (a network read); [refusal] is the engine saying the token is dead. A stored token alone
 * is reported as signed in WITHOUT a green dot, because a token can be revoked server-side.
 */
internal fun engineCategoryStatus(
    configured: Boolean,
    signedIn: Boolean,
    verifiedEmail: String?,
    refusal: Boolean,
): CategoryStatus = when {
    !configured -> CategoryStatus("No engine address")
    !signedIn -> CategoryStatus("Not signed in", SettingsDot.WARN)
    refusal -> CategoryStatus("Sign-in no longer valid", SettingsDot.ERROR)
    verifiedEmail != null -> CategoryStatus("Signed in as $verifiedEmail", SettingsDot.OK)
    else -> CategoryStatus("Signed in")
}

/** Phones rail status: the phone carrying audio right now, else a neutral descriptor. */
internal fun phonesCategoryStatus(activePhoneName: String?, pairedCount: Int): CategoryStatus = when {
    activePhoneName != null -> CategoryStatus("Playing from $activePhoneName", SettingsDot.OK)
    pairedCount > 0 -> CategoryStatus("$pairedCount paired, none playing")
    else -> CategoryStatus("Bluetooth phones")
}

/** What the Music "Spotify" row's button does. */
internal enum class SpotifyRowAction { QUIET_OPEN, HOT_OPEN, HOT_REAUTHORIZE }

/**
 * The Spotify row's button. Broken (warn or error dot) makes it HOT. It goes straight to the consent
 * flow only when consent is the fix: a client ID is saved AND the grant is absent or stale. A
 * playlist-fetch failure on a complete grant is broken but not a consent problem, so it just opens
 * the leaf, as does any broken state with no client ID to authorize with. Playback (App Remote) not
 * connected on a complete Web grant is the same: HOT, opening the leaf where CONNECT lives.
 */
internal fun spotifyRowAction(
    status: CategoryStatus?,
    hasClientId: Boolean,
    authorized: Boolean,
    needsReconnect: Boolean,
): SpotifyRowAction {
    if (status == null || !spotifyNeedsAttention(status)) return SpotifyRowAction.QUIET_OPEN
    return if (hasClientId && (!authorized || needsReconnect)) SpotifyRowAction.HOT_REAUTHORIZE
    else SpotifyRowAction.HOT_OPEN
}

/** The chip shown on a row for [status]: words, not just colour. Null when there is nothing honest to say. */
internal fun settingsChip(status: CategoryStatus?): SettingsChip? = when (status?.dot) {
    SettingsDot.OK -> SettingsChip("OK", SettingsDot.OK)
    SettingsDot.WARN -> SettingsChip("ACTION NEEDED", SettingsDot.WARN)
    SettingsDot.ERROR -> SettingsChip("ACTION NEEDED", SettingsDot.ERROR)
    else -> null
}

/** The Keystore-backed Spotify reads, taken together off the main thread. */
internal data class SpotifyFacts(
    val hasClientId: Boolean,
    val authorized: Boolean,
    val needsReconnect: Boolean,
    val grantedScope: String,
    /** App Remote (playback), from [com.kevin.midnightai.media.SpotifyController.effectiveLink]. */
    val appRemote: AppRemoteLink,
    /** The last App Remote failure was "Spotify app not installed" ([com.kevin.midnightai.media.SpotifyController.lastFailureIsNotInstalled]). */
    val appRemoteNotInstalled: Boolean = false,
)

/** Music rail status. [facts] null means the off-main read has not landed: neutral, no dot. */
internal fun spotifyStatusFor(facts: SpotifyFacts?, playlistCount: Int, lastError: String?): CategoryStatus =
    if (facts == null) {
        CategoryStatus("Spotify and music apps")
    } else {
        spotifyCategoryStatus(
            facts.hasClientId, facts.authorized, facts.grantedScope, playlistCount, lastError,
            facts.appRemote, facts.appRemoteNotInstalled,
        )
    }
