package com.kevin.legion.ui.media

import com.kevin.legion.media.SpotifyWebApi

/**
 * Pure mapping from a [SpotifyWebApi.SearchOutcome] to what [MediaScreen]'s search-and-play
 * panel shows - the IDENTICAL outcomes `play_music` reports through
 * [com.kevin.legion.service.LiveToolbox]'s `resolveSpotifyUri`, in the same words. That
 * function is `private` to `LiveToolbox.kt` (file-scoped in Kotlin, not reachable even from
 * elsewhere in this module), so this is a second CALL SITE over the same
 * [SpotifyWebApi.search] - ADR 0035's "both paths call the same controller, never a second
 * implementation of it" - not a second implementation of the resolution itself. Wording is
 * carried over deliberately: a driver who hears one sentence from the assistant and reads a
 * different one on screen for the identical failure would read it as two different problems.
 *
 * [SpotifyWebApi.SearchOutcome] carries no Android dependency, so this stays a plain JVM unit
 * test target, same shape as every other resolver under `ui/media` and `ui/spotify`.
 */
object MediaSearchResolver {

    /** One playable row: what actually resolved, per [SpotifyWebApi.SearchOutcome.Found]'s own doc. */
    data class Row(val uri: String, val title: String, val subtitle: String?)

    fun rowFor(outcome: SpotifyWebApi.SearchOutcome.Found): Row =
        Row(uri = outcome.uri, title = outcome.name, subtitle = outcome.subtitle)

    /** The honest line for every non-[SpotifyWebApi.SearchOutcome.Found] outcome; null only for Found. */
    fun failureMessage(outcome: SpotifyWebApi.SearchOutcome, query: String): String? = when (outcome) {
        is SpotifyWebApi.SearchOutcome.Found -> null
        SpotifyWebApi.SearchOutcome.NeedsAuthorization ->
            "Spotify hasn't been authorized on this device yet - open Setup, Spotify, and tap AUTHORIZE."
        is SpotifyWebApi.SearchOutcome.Unauthorized ->
            "Spotify rejected the request" + (outcome.detail?.let { ": $it" } ?: ".") +
                " Run the search test in Setup, Spotify for the details."
        SpotifyWebApi.SearchOutcome.Unreachable ->
            "Couldn't reach Spotify just now. Worth trying again when you have a better connection."
        SpotifyWebApi.SearchOutcome.NoMatch ->
            "Spotify has nothing matching \"$query\"."
        is SpotifyWebApi.SearchOutcome.Failed ->
            "Spotify's search returned an error (${outcome.code})" + (outcome.detail?.let { ": $it" } ?: ".")
    }
}
