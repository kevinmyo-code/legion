package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.SpotifyController
import com.kevin.legion.media.ClosestHit
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.media.VolumeController
import com.kevin.legion.service.LiveToolbox.result
import org.json.JSONObject

/**
 * The music tools' dispatch, split out of [LiveToolbox] (shrink pass, 2026-11-22) along the
 * [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch] runs its guards, then asks this object,
 * which answers the names it owns and returns null for every other. The declarations stay in
 * `service/tools/`; only the routing and the handlers live here. Spotify and the phone's media
 * session are reached through [SpotifyController] and [SpotifyWebApi], never directly.
 */
object MusicToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "play_music" -> playMusic(
            context,
            args.optString("query"),
            args.optString("type", "song"),
            args.optString("spotifyUri", "").ifBlank { null },
            args.optString("artist", "").ifBlank { null },
        )
        "control_volume" -> controlVolume(context, args)
        else -> null
    }

    /**
     * The result of turning a spoken name into a Spotify URI - [Found] or a fully-built failure
     * [JSONObject] ready to return as-is. Kept as its own tiny sealed type rather than a nullable
     * String so a caller cannot forget to check which one it got.
     */
    private sealed interface SpotifyUriResolution {
        /**
         * [name]/[subtitle] (ticket 07) are Spotify's OWN name for whatever [uri] resolved to, so
         * `play_music` can say what it actually picked ("Discovery, Daft Punk") instead of echoing
         * the driver's own words back. Null on the paths where nothing was searched for.
         *
         * [note] (ticket 08) is set only by [resolvePlaylistForPlay], when the URI came from the
         * public search fallback rather than the driver's own library - the one case where what
         * starts may not be what he meant, and it has to be said rather than silently substituted.
         */
        data class Found(
            val uri: String,
            val name: String? = null,
            val subtitle: String? = null,
            val note: String? = null,
        ) : SpotifyUriResolution
        data class Failed(val toolResult: JSONObject) : SpotifyUriResolution
    }

    /**
     * The shared "turn what the driver said into a Spotify URI" sequence: `ensureConnected` (a
     * silent reconnect attempt), confirm the Web API grant is authorized, then
     * [SpotifyWebApi.search]. Extracted (ticket 04, `.scratch/spotify-voice/issues/04-queue.md`
     * scope item 1: "`queue`, taking a `query` resolved through the same search path `play_music`
     * uses") so [playMusic] and `control_music`'s `queue` action fail with the IDENTICAL words
     * for the IDENTICAL reasons - they are the same resolve step with two different things done
     * to the result.
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // one resolution cascade; a split scatters it
    private suspend fun resolveSpotifyUri(
        context: Context,
        query: String,
        type: String,
        artist: String? = null,
    ): SpotifyUriResolution {
        // Tool-facing vocabulary is "song" (matches how a driver actually talks); Spotify's own
        // API calls that "track" - translated at the boundary so nothing upstream of this line
        // needs to know Spotify's word for it.
        val spotifyType = if (type == "song") "track" else type

        // ensureConnected, not isConnected: App Remote drops on its own (Spotify
        // killed/backgrounded), so this always tries a silent reconnect first.
        // Only attempts a reconnect when a client ID is actually saved, so a
        // driver who never set Spotify up pays nothing here.
        if (!SpotifyController.ensureConnected(context)) {
            return SpotifyUriResolution.Failed(result(
                success = false,
                message = "Spotify isn't connected - connect your Spotify account in Setup, or pick " +
                    "something on your phone yourself and I'll control play/pause/skip from here.",
            ))
        }

        // Not authorized = no Web API = no way to turn a name into a URI. Say so
        // plainly; this is the one failure the driver can fix themselves.
        // hasStaleGrant distinguishes "never connected" from "connected once, but a newly
        // added scope invalidated that grant" (ticket 05, 2026-08-18's SCOPES widening for
        // browse_my_music) - the two need different words, since the second driver has already
        // done this once and "isn't finished connecting" would read as if nothing was saved.
        if (!SpotifyWebApi.isAuthorized(context)) {
            return SpotifyUriResolution.Failed(
                if (SpotifyWebApi.hasStaleGrant(context)) {
                    result(
                        success = false,
                        message = "Spotify needs re-approving - I picked up a couple of new permissions " +
                            "and your old approval doesn't cover them. Open Setup, Spotify, and tap " +
                            "AUTHORIZE again. Takes a few seconds.",
                    )
                } else {
                    result(
                        success = false,
                        message = "Spotify isn't finished connecting - open Setup, tap CONNECT under the " +
                            "Spotify client ID, and approve it in the browser. Then I can play by name.",
                    )
                },
            )
        }

        // Each outcome gets its own answer (2026-08-12). These used to be one nullable
        // String reported as "I couldn't find that", so an expired grant, a dead
        // connection and a genuinely unknown song were indistinguishable to the driver
        // AND to anyone debugging it - the exact collapse GoogleGrantResolver.diagnose
        // was written to undo on the Drive side.
        return when (val outcome = SpotifyWebApi.search(context, query, spotifyType, artist)) {
            is SpotifyWebApi.SearchOutcome.Found -> SpotifyUriResolution.Found(outcome.uri, outcome.name, outcome.subtitle)
            SpotifyWebApi.SearchOutcome.NeedsAuthorization -> SpotifyUriResolution.Failed(result(
                success = false,
                message = "Spotify hasn't been authorized on this device yet - open Setup, " +
                    "Spotify, and tap AUTHORIZE.",
            ))
            is SpotifyWebApi.SearchOutcome.Unauthorized -> SpotifyUriResolution.Failed(result(
                success = false,
                // Spotify's own words are carried through rather than paraphrased: a 403
                // saying "the user may not be registered" is a dashboard problem that
                // re-authorizing will never fix, and telling the driver to tap AUTHORIZE
                // again would send them in circles.
                message = "Spotify rejected the request" +
                    (outcome.detail?.let { ": $it" } ?: ".") +
                    " Run the search test in Setup, Spotify for the details.",
            ))
            SpotifyWebApi.SearchOutcome.Unreachable -> SpotifyUriResolution.Failed(result(
                success = false,
                message = "Could not reach Spotify. Try again on a better " +
                    "connection.",
            ))
            SpotifyWebApi.SearchOutcome.NoMatch -> SpotifyUriResolution.Failed(result(
                success = false,
                // Names what was searched and says nothing was started: the search now rejects
                // hits that do not match the request, so NoMatch can mean "Spotify had
                // something, but not this" (CLAUDE.md §7 outcome-verb rule). Shared with the
                // queue action, and whatever was already playing keeps playing, so it claims
                // neither "nothing is playing" nor a play-specific verb.
                message = "I couldn't find \"$query\"" +
                    (artist?.takeIf { it.isNotBlank() }?.let { " by $it" } ?: "") +
                    " on Spotify, so I didn't play or queue anything.",
            ))
            is SpotifyWebApi.SearchOutcome.Rejected -> SpotifyUriResolution.Failed(
                result(
                    success = false,
                    // Hits existed and none fit the request. Name them so the model can ask the user
                    // which one was meant; nothing was started, and the message says so.
                    message = "I couldn't find \"$query\"" +
                        (artist?.takeIf { it.isNotBlank() }?.let { " by $it" } ?: "") +
                        " on Spotify, so I didn't play or queue anything. Closest hits: " +
                        outcome.closest.joinToString("; ") { it.label } +
                        ". Ask the user whether one of those is what they meant.",
                ).put("closestHits", closestHitsJson(outcome.closest)),
            )
            is SpotifyWebApi.SearchOutcome.Failed -> SpotifyUriResolution.Failed(result(
                success = false,
                message = "Spotify's search returned an error (${outcome.code})" +
                    (outcome.detail?.let { ": $it" } ?: "."),
            ))
        }
    }

    /**
     * The near-misses a rejection named, as playable rows (music-suggestions-1124 P3).
     *
     * The result's own instruction is to ask the user which of these they meant, and until this
     * existed there was no way to honour a yes: the model had a name and no URI, so its only route
     * was to search again for a title it would have to reproduce in the catalogue's own spelling.
     * Each row carries `spotifyUri` exactly as every other playable row does, so "yes, that one"
     * is one `play_music` call with the URI. Result-side only, so it costs no declaration tokens.
     */
    internal fun closestHitsJson(hits: List<ClosestHit>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        hits.forEach { h ->
            arr.put(
                JSONObject()
                    .put("name", h.name)
                    .put("artist", h.artist ?: JSONObject.NULL)
                    // A hit Spotify indexed without a playable URI can be NAMED but not played,
                    // the same shape legion_history's replayable-false rows already have.
                    .put("spotifyUri", h.uri ?: JSONObject.NULL)
                    .put("playable", h.uri != null),
            )
        }
        return arr
    }

    // --- Shared Spotify failure wording (ticket 08) --------------------------------------------
    // Extracted so the playlist paths below ([resolvePlaylistForPlay], [controlMusicAddToPlaylist])
    // say the IDENTICAL words for the IDENTICAL Spotify-account-level failures as
    // [resolveSpotifyUri] above does for song/artist/album - a driver hearing two different
    // sentences for "you haven't authorized Spotify yet" depending on WHICH thing they asked for
    // would read as two different problems.

    internal fun spotifyNeedsAuthorizationResult(): JSONObject = result(
        success = false,
        message = "Spotify hasn't been authorized on this device yet - open Setup, Spotify, and tap AUTHORIZE.",
    )

    internal fun spotifyUnauthorizedResult(detail: String?): JSONObject = result(
        success = false,
        message = "Spotify rejected the request" +
            (detail?.let { ": $it" } ?: ".") +
            " Run the search test in Setup, Spotify for the details.",
    )

    internal fun spotifyUnreachableResult(): JSONObject = result(
        success = false,
        message = "Could not reach Spotify. Try again on a better connection.",
    )

    /**
     * `play_music`'s playlist path (ticket 08 scope items 1-2): [SpotifyWebApi.resolvePlaylist]
     * first tries the driver's OWN cached library, and only falls through to public
     * `/v1/search` when nothing there matched. When it fell through, [playlistFallbackNote]
     * carries the honesty line - "say which one it used when the answer might surprise him" -
     * for [playMusic] to fold into its spoken confirmation; null on every other outcome.
     */
    private suspend fun resolvePlaylistForPlay(context: Context, query: String): SpotifyUriResolution {
        return when (val resolution = SpotifyWebApi.resolvePlaylist(context, query)) {
            is SpotifyWebApi.PlaylistResolution.FromLibrary -> SpotifyUriResolution.Found(resolution.playlist.uri)
            is SpotifyWebApi.PlaylistResolution.FromSearch -> SpotifyUriResolution.Found(
                uri = resolution.uri,
                // A distinct URI from the public catalogue, not the driver's own library -
                // this note is [playMusic]'s honesty line for scope item 2 ("say which one it
                // used when the answer might surprise him"), carried on the resolution itself
                // rather than through shared state (tool dispatch is concurrent across sessions).
                note = "Couldn't find \"$query\" among your own playlists, so I played the " +
                    "closest match in Spotify's public catalogue" +
                    (resolution.name?.let { " - \"$it\"" } ?: "") + ".",
            )
            SpotifyWebApi.PlaylistResolution.NeedsAuthorization -> SpotifyUriResolution.Failed(spotifyNeedsAuthorizationResult())
            is SpotifyWebApi.PlaylistResolution.Unauthorized -> SpotifyUriResolution.Failed(spotifyUnauthorizedResult(resolution.detail))
            SpotifyWebApi.PlaylistResolution.Unreachable -> SpotifyUriResolution.Failed(spotifyUnreachableResult())
            SpotifyWebApi.PlaylistResolution.NoMatch -> SpotifyUriResolution.Failed(result(
                success = false,
                message = "Spotify has nothing matching \"$query\".",
            ))
            is SpotifyWebApi.PlaylistResolution.Failed -> SpotifyUriResolution.Failed(result(
                success = false,
                message = "Spotify's playlist search returned an error (${resolution.code})" +
                    (resolution.detail?.let { ": $it" } ?: "."),
            ))
        }
    }

    /**
     * Plays a specific track/artist by name, in-app, via Spotify App Remote +
     * Web API search. This is the only "play something specific" path left -
     * the old OS-level play-from-search-intent fallback (opening a separate
     * music app full-screen, with a floating companion badge over it) was
     * retired with the rest of the car-launcher UI in the 2026-07-31 pivot.
     * If Spotify isn't connected, this fails with an actionable message rather
     * than falling back to launching another app.
     */
    @Suppress("ReturnCount") // guard clauses, one worded exit each
    private suspend fun playMusic(
        context: Context,
        query: String,
        type: String = "song",
        knownUri: String? = null,
        artist: String? = null,
    ): JSONObject {
        if (query.isBlank()) return result(success = false, message = "What should I play?")

        // knownUri (ticket 09, scope item 2) is the "play that thing from Tuesday" path: a caller
        // that already holds a concrete URI - today only a `legion_history` row with `replayable`
        // true - skips name-to-URI resolution entirely. Searching anyway would risk landing on a
        // DIFFERENT track than the one the driver actually pointed at. [query] is still carried
        // for the spoken confirmation, so the message names what was asked for either way.
        var fallbackNote: String? = null
        // pickedLabel (ticket 07) is what search actually resolved to ("Discovery, Daft Punk"),
        // not the driver's own words - a loose spoken query and the top hit can genuinely differ,
        // and he should hear which one started, once, briefly. Null on the knownUri replay path
        // (the query already names the legion_history row about to play) and on the playlist path,
        // which carries its own fallback note instead.
        val (uri, pickedLabel) = if (!knownUri.isNullOrBlank()) {
            knownUri to null
        } else if (type == "playlist") {
            // Ticket 08: playlists route through the driver's OWN library cache first, falling
            // through to public search only when nothing there matched - see
            // [resolvePlaylistForPlay]'s own doc for why this cannot share [resolveSpotifyUri],
            // which only ever calls Spotify's public search.
            when (val resolved = resolvePlaylistForPlay(context, query)) {
                is SpotifyUriResolution.Found -> {
                    fallbackNote = resolved.note
                    resolved.uri to null
                }
                is SpotifyUriResolution.Failed -> return resolved.toolResult
            }
        } else {
            when (val resolved = resolveSpotifyUri(context, query, type, artist)) {
                is SpotifyUriResolution.Found -> resolved.uri to
                    SpotifyController.pickedLabel(resolved.name, resolved.subtitle)
                is SpotifyUriResolution.Failed -> return resolved.toolResult
            }
        }

        // playUri (ticket 02, map decision 4) is the one play path: isInstalled ->
        // ensureConnected -> connectSwitchToLocalDevice -> play(uri), and it awaits App Remote's
        // real result, so a Started outcome genuinely means playback started - not just that the
        // call didn't throw. SpotifyController.message/succeeded are the pure outcome -> spoken
        // mapping (same shape as the navigation tools' result mapping), so every one of the four distinct
        // connect failures the research called for gets its own line here, never one generic one.
        val outcome = SpotifyController.playUri(context, uri, pickedLabel = pickedLabel)
        if (SpotifyController.succeeded(outcome)) {
            // Attributes the track NowPlayingController is about to observe to LEGION rather
            // than the driver having started it themselves - see its own doc comment.
            NowPlayingController.markLegionInitiatedPlay()
        }
        val succeeded = SpotifyController.succeeded(outcome)
        // fallbackNote only ever exists on a successful playlist resolution - append it so the
        // driver hears WHICH playlist actually played when it wasn't one of his own (ticket 08
        // scope item 2). Never appended on failure: a failed play has nothing to caveat further.
        val message = SpotifyController.message(outcome, query) + (fallbackNote?.takeIf { succeeded }?.let { " $it" } ?: "")
        return result(success = succeeded, message = message)
    }

    /**
     * Adjusts the head unit's media volume on-device ([VolumeController]) - no
     * cloud needed once the intent is known. Reports the resulting level so Zero
     * can confirm where it landed.
     */
    private fun controlVolume(context: Context, args: JSONObject): JSONObject {
        val pct = when (val action = args.optString("action")) {
            "up" -> VolumeController.raise(context)
            "down" -> VolumeController.lower(context)
            "set" -> {
                val level = args.optInt("level", -1)
                if (level < 0) return result(success = false, message = "What level should I set it to?")
                VolumeController.setPercent(context, level)
            }
            "mute" -> VolumeController.mute(context, true)
            "unmute" -> VolumeController.mute(context, false)
            else -> return result(success = false, message = "Unknown volume action: $action")
        }
        return result(success = true, message = null).put("volumePercent", pct)
    }

    /**
     * `control_music`'s `queue` action (ticket 04, `.scratch/spotify-voice/issues/04-queue.md`):
     * resolves [query] through [resolveSpotifyUri] - the identical path [playMusic] uses - then
     * [SpotifyController.queueUri]. "Play X next" and "add X to the queue" are the same operation
     * to Spotify (there is no insert-at-position), which is why they share one action and one
     * spoken line ([SpotifyController.message] for [SpotifyController.QueueOutcome] says so).
     */
    internal suspend fun controlMusicQueue(context: Context, query: String): JSONObject {
        if (query.isBlank()) return result(success = false, message = "What should I queue?")

        val (uri, pickedLabel) = when (val resolved = resolveSpotifyUri(context, query, "song")) {
            is SpotifyUriResolution.Found -> resolved.uri to
                SpotifyController.pickedLabel(resolved.name, resolved.subtitle)
            is SpotifyUriResolution.Failed -> return resolved.toolResult
        }

        val outcome = SpotifyController.queueUri(context, uri)
        // Names the track Spotify resolved, not the user's own words: the queue line used to echo
        // the query ("Queued \"Ugly by The Gazette\"") whatever had actually been found, the same
        // wrong-song blind spot play_music had (voice audit finding 4, S18). Falls back to the
        // query only when the resolution carried no name.
        return result(
            success = SpotifyController.succeeded(outcome),
            message = SpotifyController.message(outcome, pickedLabel ?: query),
        )
    }
}
