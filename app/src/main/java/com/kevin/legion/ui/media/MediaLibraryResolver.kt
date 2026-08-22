package com.kevin.legion.ui.media

import com.kevin.legion.media.SpotifyWebApi

/**
 * Pure mapping from a [SpotifyWebApi.LibraryOutcome] to what [MediaScreen]'s library-browse
 * panel shows, over the SAME four endpoints `browse_my_music` reads
 * ([com.kevin.legion.service.LiveToolbox]'s `browseMyMusic`/`libraryOutcomeToJson`, both
 * `private` to `LiveToolbox.kt` and so a second call site over the shared
 * [SpotifyWebApi] endpoints, never a second implementation of them - same ADR 0035 posture
 * [MediaSearchResolver] documents). Wording matches `libraryOutcomeToJson`'s deliberately, for
 * the same reason: one story, one set of words, whether it is heard or read.
 *
 * [SpotifyWebApi.LibraryOutcome] carries no Android dependency, so this stays a plain JVM unit
 * test target. [stale] is threaded in by the caller (from [SpotifyWebApi.hasStaleGrant], which
 * DOES touch `SharedPreferences`) rather than read here, keeping this function itself pure.
 */
object MediaLibraryResolver {

    /**
     * The honest line for every outcome. Null only for a [SpotifyWebApi.LibraryOutcome.Found]
     * with at least one item - a caller renders the list itself in that one case. An EMPTY
     * [SpotifyWebApi.LibraryOutcome.Found] gets its own line ("Spotify has no X to show right
     * now") rather than silently rendering nothing: CLAUDE.md §4 rule 5's "unreadable and empty
     * are different sentences" carried from ingestion to a browse read.
     */
    fun <T> message(outcome: SpotifyWebApi.LibraryOutcome<T>, sourceLabel: String, stale: Boolean): String? = when (outcome) {
        is SpotifyWebApi.LibraryOutcome.Found ->
            if (outcome.items.isEmpty()) "Spotify has no $sourceLabel to show right now." else null
        SpotifyWebApi.LibraryOutcome.NeedsAuthorization ->
            "Spotify hasn't been authorized on this device yet - open Setup, Spotify, and tap " +
                "AUTHORIZE. Then you can look up $sourceLabel."
        is SpotifyWebApi.LibraryOutcome.Unauthorized ->
            if (stale) {
                "Spotify rejected that - your approval predates $sourceLabel access. Open Setup, " +
                    "Spotify, and tap AUTHORIZE again to re-approve; it only takes a few seconds."
            } else {
                "Spotify rejected the request for $sourceLabel" +
                    (outcome.detail?.let { ": $it" } ?: ".") +
                    " Run the search test in Setup, Spotify for the details."
            }
        SpotifyWebApi.LibraryOutcome.Unreachable ->
            "Couldn't reach Spotify just now for $sourceLabel. Worth trying again when you have " +
                "a better connection."
        is SpotifyWebApi.LibraryOutcome.Failed ->
            "Spotify's $sourceLabel request returned an error (${outcome.code})" +
                (outcome.detail?.let { ": $it" } ?: ".")
    }
}
