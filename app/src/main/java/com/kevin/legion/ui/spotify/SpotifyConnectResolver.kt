package com.kevin.legion.ui.spotify

/**
 * Pure UI-state derivation for [com.kevin.legion.ui.SpotifyScreen] - the
 * "Connect Spotify" entry point that makes the whole Spotify tier reachable at
 * all (traced 2026-08-12: before this screen,
 * [com.kevin.legion.ai.CompanionProfile.saveSpotifyClientId],
 * [com.kevin.legion.media.SpotifyController.connect] and
 * [com.kevin.legion.media.SpotifyWebApi.beginAuthorization] ALL had zero
 * callers, so `spotifyClientId` could never become non-blank,
 * `SpotifyController.startConnect` always bailed at its blank-ID guard, and
 * `play_music` answered "Spotify isn't connected - connect your Spotify
 * account in Setup" pointing at a Setup screen that did not exist).
 *
 * Kept Android-free on purpose, same shape as
 * [com.kevin.legion.ui.sync.GoogleGrantResolver]: it takes only the
 * booleans/strings the screen already has in hand, never a `Context`, so this
 * is a plain JVM unit test, no Robolectric.
 *
 * **Two independent grants, not one.** This is the thing the copy has to keep
 * straight, because nothing else in the app has this shape:
 *  - **App Remote** ([com.kevin.legion.media.SpotifyController]) is an
 *    app-to-app binding to the installed Spotify app. It plays a URI it is
 *    given. It needs Spotify installed and logged in with Premium.
 *  - **Web API** ([com.kevin.legion.media.SpotifyWebApi]) is a PKCE browser
 *    grant. It turns a spoken name into a URI. App Remote has no search, which
 *    is the only reason this second grant exists.
 *
 * Both are needed for "play Midnight City". Either one alone is a half-working
 * feature, so [Stage] tracks them separately rather than collapsing to one
 * connected flag, and the screen says which half is missing instead of a
 * generic "not connected".
 */
object SpotifyConnectResolver {

    /**
     * How far through setup the driver is. Ordered: each stage's action is the
     * one thing that gets them to the next.
     */
    enum class Stage {
        /** No client ID saved. Nothing can happen until the driver registers their own Spotify dev app. */
        NEEDS_CLIENT_ID,

        /** Client ID saved, but no Web API refresh token on file - play-by-name cannot resolve a URI yet. */
        NEEDS_AUTHORIZATION,

        /** Client ID saved and Web API authorized. Play-by-name can work; App Remote link state is reported separately. */
        READY,
    }

    /**
     * [hasClientId] false always wins - the client ID is what every other step
     * is keyed to (the PKCE exchange sends it, App Remote's `ConnectionParams`
     * is built from it), so an "authorized" flag with no ID behind it is a
     * state the screen should never offer an action for. It is also reachable:
     * [com.kevin.legion.ai.CompanionProfile.saveSpotifyClientId] clears the
     * tokens when the ID *changes*, but saving a blank ID clears the ID while
     * leaving a refresh token that was minted for the old one.
     */
    fun stage(hasClientId: Boolean, isAuthorized: Boolean): Stage = when {
        !hasClientId -> Stage.NEEDS_CLIENT_ID
        !isAuthorized -> Stage.NEEDS_AUTHORIZATION
        else -> Stage.READY
    }

    /** Verdict on a pasted client ID. Only [BLANK] blocks the save - see [checkClientId]. */
    enum class ClientIdCheck {
        /** Nothing to save. */
        BLANK,

        /** Not the 32-hex-character shape Spotify issues today. Saved anyway, with a caution. */
        UNEXPECTED_FORMAT,

        /** Looks like a Spotify client ID. */
        OK,
    }

    /** Spotify client IDs are 32 hex characters. Not a documented guarantee, which is why a mismatch cautions rather than blocks. */
    private const val CLIENT_ID_LENGTH = 32

    /**
     * Shape-checks a pasted client ID.
     *
     * **[UNEXPECTED_FORMAT] deliberately does NOT block the save.** Every
     * client ID Spotify issues today is 32 hex characters, so the check catches
     * the realistic mistakes (pasting the client *secret*, a dashboard URL, or
     * a truncated copy) - but the format is Spotify's convention, not a
     * published contract. Hard-rejecting on it would mean a format change on
     * their end locks the driver out of a feature that would otherwise work,
     * with no override. Cautioning costs one line of copy and cannot do that.
     */
    fun checkClientId(raw: String): ClientIdCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ClientIdCheck.BLANK
        val looksRight = trimmed.length == CLIENT_ID_LENGTH &&
            trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        return if (looksRight) ClientIdCheck.OK else ClientIdCheck.UNEXPECTED_FORMAT
    }

    /** One-line state, in words. Colour is reinforcement only (CLAUDE.md §7). */
    fun headline(stage: Stage): String = when (stage) {
        Stage.NEEDS_CLIENT_ID -> "Not set up"
        Stage.NEEDS_AUTHORIZATION -> "Half set up"
        Stage.READY -> "Set up"
    }

    /** What that state actually means for what the assistant can do. */
    fun detail(stage: Stage): String = when (stage) {
        Stage.NEEDS_CLIENT_ID ->
            "Asking for a song by name won't work yet. Play/pause/skip already work on whatever " +
                "you start yourself."
        Stage.NEEDS_AUTHORIZATION ->
            "Client ID saved. One step left: approve access in the browser so a song name can be " +
                "turned into something playable."
        Stage.READY ->
            "Asking for a song by name works, as long as Spotify is installed and logged in with Premium."
    }

    /** The action label for [stage]'s one next step, or null when there is nothing left to do. */
    fun actionLabel(stage: Stage): String? = when (stage) {
        Stage.NEEDS_CLIENT_ID -> null // the SAVE button on the client-ID field is the action here
        Stage.NEEDS_AUTHORIZATION -> "AUTHORIZE"
        Stage.READY -> null
    }

    /** Shown when the Spotify app itself is missing - App Remote is app-to-app, so nothing here can work without it. */
    const val APP_MISSING_MESSAGE: String =
        "The Spotify app isn't installed on this phone. App Remote talks to it directly, so " +
            "nothing here will play until it is."

    /** Shown when Premium is the likely cause. Stated as a requirement, never as a claim about this account - the app cannot see the tier. */
    const val PREMIUM_NOTE: String =
        "Spotify requires Premium for another app to control playback. If you're on Free, " +
            "linking will fail and there's nothing this app can do about it."

    const val SAVED_MESSAGE: String = "Client ID saved."

    const val SAVED_UNEXPECTED_FORMAT_MESSAGE: String =
        "Saved, but that doesn't look like a client ID (they're normally 32 hex characters). " +
            "If authorizing fails, check you copied the Client ID and not the Client Secret."

    const val BLANK_MESSAGE: String = "Paste your client ID first."

    const val AUTHORIZED_MESSAGE: String = "Spotify authorized."

    const val AUTH_DENIED_MESSAGE: String =
        "Spotify didn't authorize. Nothing was saved - you can try again."

    const val NO_BROWSER_MESSAGE: String =
        "Couldn't open a browser to reach Spotify's approval page."

    const val DISCONNECTED_MESSAGE: String =
        "Disconnected. The client ID is kept; the approval is not."

    const val REMOVED_MESSAGE: String =
        "Client ID removed, along with the approval. Play/pause/skip still work on whatever " +
            "you start yourself."

    const val PLAYER_LINK_FAILED_MESSAGE: String =
        "Authorized, but the Spotify app didn't accept the player link. Open Spotify, make sure " +
            "you're logged in with Premium, then tap LINK PLAYER."

    /**
     * The exact Redirect URI the driver must register in their own Spotify
     * dashboard, byte for byte. Not derived here - it is
     * [com.kevin.legion.media.SpotifyController.REDIRECT_URI], threaded in by
     * the screen so this file stays free of the App Remote SDK.
     */
    fun redirectUriInstruction(redirectUri: String): String =
        "Register this exact Redirect URI in your Spotify dashboard, or approval will fail:\n$redirectUri"
}
