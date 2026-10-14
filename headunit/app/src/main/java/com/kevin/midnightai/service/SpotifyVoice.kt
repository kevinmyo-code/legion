package com.kevin.midnightai.service

import android.content.Context
import com.kevin.midnightai.media.MixtapePlayer
import com.kevin.midnightai.media.MusicController
import com.kevin.midnightai.media.MusicRouter
import com.kevin.midnightai.media.MusicSource
import com.kevin.midnightai.media.PlayRequests
import com.kevin.midnightai.media.Source
import com.kevin.midnightai.media.SpotifyController
import com.kevin.midnightai.media.SpotifyVoiceLogic
import com.kevin.midnightai.media.SpotifyVoiceLogic.MusicAction
import com.kevin.midnightai.media.SpotifyWebApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The head-unit-Spotify half of the music voice tools, kept out of [LiveToolbox] (already ~2000
 * lines) and wired in by it: `play_music`'s Spotify branch, `control_music`'s Spotify-only
 * actions, and `get_music_queue`. Ported from LEGION's `LiveToolbox` music section and adapted to
 * this app's source model - [MusicSource] says whether the phone, a mixtape or head-unit Spotify
 * owns the speakers, and the Spotify-only actions refuse (in words) unless it is Spotify.
 *
 * Every result sentence follows the same rule as the rest of the toolbox: say what actually
 * happened. A play that was never started does not say "playing"; a search that matched nothing
 * acceptable says nothing was played or queued.
 */
internal object SpotifyVoice {

    private fun result(success: Boolean, message: String?): JSONObject =
        JSONObject().put("success", success).apply { if (message != null) put("message", message) }

    // --- Shared failure wording ---------------------------------------------------------------------
    // One set of sentences for the account-level Spotify failures, so "you haven't authorized
    // Spotify yet" reads identically whichever tool hit it.

    private fun needsAuthorization(): JSONObject = result(
        false,
        "Spotify isn't finished connecting - open Settings, tap CONNECT under the Spotify client " +
            "ID, and approve it in the browser. Then I can do that by name.",
    )

    private fun unauthorized(context: Context, detail: String?, what: String): JSONObject = result(
        false,
        if (SpotifyWebApi.needsReconnect(context)) {
            // The grant is older than the scope this request needs. One reconnect re-asks for the
            // whole set, so say that rather than sending the driver round in circles.
            "Spotify rejected that - your approval predates $what access. Tap Reconnect Spotify on " +
                "the home screen, or CONNECT under the Spotify client ID in Settings, and approve " +
                "once. It only takes a few seconds."
        } else {
            "Spotify rejected the request for $what" + (detail?.let { ": $it" } ?: ".") +
                " If that keeps happening, check this account is allowed on the Spotify developer app."
        },
    )

    private fun unreachable(what: String): JSONObject = result(
        false,
        "I couldn't reach Spotify just now for $what. Worth trying again when you have a better connection.",
    )

    /** Null when the current source is head-unit Spotify; else the refusal result for a Spotify-only action. */
    private fun sourceRefusal(): JSONObject? =
        SpotifyVoiceLogic.sourceBlocker(MusicSource.current.value)?.let { result(false, it) }

    // --- play_music -----------------------------------------------------------------------------------

    private sealed interface Resolution {
        /** [name]/[subtitle] are Spotify's own name for [uri]; [note] is set only when what plays may surprise the driver. */
        data class Found(val uri: String, val name: String?, val subtitle: String?, val note: String? = null) : Resolution
        data class Failed(val toolResult: JSONObject) : Resolution
    }

    /**
     * Turns a spoken name into a Spotify URI via [SpotifyWebApi.search], failing in words that say
     * why. Assumes the caller already confirmed App Remote is connected; checks the Web API grant
     * here because without it there is no way to turn a name into a URI.
     */
    private suspend fun resolveSpotifyUri(
        context: Context,
        query: String,
        type: String,
        artist: String?,
    ): Resolution {
        if (!SpotifyWebApi.isAuthorized(context)) return Resolution.Failed(needsAuthorization())

        return when (val outcome = SpotifyWebApi.search(context, query, SpotifyVoiceLogic.searchType(type), artist)) {
            is SpotifyWebApi.SearchOutcome.Found -> Resolution.Found(outcome.uri, outcome.name, outcome.subtitle)
            SpotifyWebApi.SearchOutcome.NeedsAuthorization -> Resolution.Failed(needsAuthorization())
            is SpotifyWebApi.SearchOutcome.Unauthorized -> Resolution.Failed(unauthorized(context, outcome.detail, "search"))
            SpotifyWebApi.SearchOutcome.Unreachable -> Resolution.Failed(unreachable("that search"))
            SpotifyWebApi.SearchOutcome.NoMatch -> Resolution.Failed(result(
                false,
                "I couldn't find \"$query\"" + byArtist(artist) + " on Spotify, so I didn't play or queue anything.",
            ))
            is SpotifyWebApi.SearchOutcome.Rejected -> Resolution.Failed(result(
                false,
                // Hits existed and none fit the request. Name them so the model can ask which was
                // meant; nothing was started, and the message says so.
                "I couldn't find \"$query\"" + byArtist(artist) + " on Spotify, so I didn't play or queue " +
                    "anything. Closest hits: " + outcome.closest.joinToString("; ") +
                    ". Ask the driver whether one of those is what they meant.",
            ))
            is SpotifyWebApi.SearchOutcome.Failed -> Resolution.Failed(result(
                false,
                "Spotify's search returned an error (${outcome.code})" + (outcome.detail?.let { ": $it" } ?: "."),
            ))
        }
    }

    private fun byArtist(artist: String?): String =
        artist?.takeIf { it.isNotBlank() }?.let { " by $it" } ?: ""

    /**
     * "Play my Roadtrip playlist": the driver's own playlists first, public catalogue only when
     * none matches - and when it falls back, [Resolution.Found.note] says so.
     */
    private suspend fun resolvePlaylist(context: Context, query: String): Resolution {
        if (!SpotifyWebApi.isAuthorized(context)) return Resolution.Failed(needsAuthorization())

        return when (val r = SpotifyWebApi.resolvePlaylist(context, query)) {
            is SpotifyWebApi.PlaylistResolution.FromLibrary -> Resolution.Found(r.playlist.uri, r.playlist.name, null)
            is SpotifyWebApi.PlaylistResolution.FromSearch -> Resolution.Found(
                r.uri, r.name, null,
                note = SpotifyVoiceLogic.playlistFallbackNote(query, r.name, r.libraryChecked),
            )
            SpotifyWebApi.PlaylistResolution.NeedsAuthorization -> Resolution.Failed(needsAuthorization())
            is SpotifyWebApi.PlaylistResolution.Unauthorized -> Resolution.Failed(unauthorized(context, r.detail, "playlist"))
            SpotifyWebApi.PlaylistResolution.Unreachable -> Resolution.Failed(unreachable("that playlist"))
            SpotifyWebApi.PlaylistResolution.NoMatch -> Resolution.Failed(result(
                false,
                "I couldn't find a playlist matching \"$query\" on Spotify, so I didn't play anything.",
            ))
            is SpotifyWebApi.PlaylistResolution.Failed -> Resolution.Failed(result(
                false,
                "Spotify's playlist search returned an error (${r.code})" + (r.detail?.let { ": $it" } ?: "."),
            ))
        }
    }

    /**
     * `play_music` when App Remote is connected: resolve, silence the outgoing source, play in
     * place, and flip [MusicSource] to SPOTIFY only once playback genuinely started. Search runs
     * BEFORE anything is paused - it is the network-dependent half, and silencing the car only to
     * then play nothing leaves it quiet for no reason.
     *
     * [knownUri] (a URI the model got from a tool result, e.g. `browse_my_music`'s artist_albums)
     * skips resolution entirely: searching anyway could land on a different album of the same
     * name than the one the driver pointed at.
     *
     * [sourceBeforeConnect] is [MusicSource] as it stood before the caller's `ensureConnected`,
     * which flips it to SPOTIFY on connect and would otherwise hide a still-playing phone/mixtape.
     */
    suspend fun playOnSpotify(
        context: Context,
        query: String,
        type: String,
        artist: String?,
        knownUri: String?,
        sourceBeforeConnect: Source = MusicSource.current.value,
        token: PlayRequests.Token? = null,
    ): JSONObject {
        // [token] (play_music only): a newer choice made while this ran wins - see [PlayRequests].
        val stillWanted = { token == null || PlayRequests.shouldProceed(token) }
        var note: String? = null
        val (uri, label) = if (!knownUri.isNullOrBlank()) {
            knownUri to null
        } else {
            val resolved = if (type == "playlist") resolvePlaylist(context, query)
            else resolveSpotifyUri(context, query, type, artist)
            when (resolved) {
                is Resolution.Found -> {
                    note = resolved.note
                    resolved.uri to SpotifyController.pickedLabel(resolved.name, resolved.subtitle)
                }
                is Resolution.Failed -> return resolved.toolResult
            }
        }

        val outcome = playResolvedUri(context, uri, label, sourceBeforeConnect, stillWanted)
        val ok = SpotifyController.succeeded(outcome)
        // The fallback note rides only on a success: a failed play has nothing to caveat further.
        return result(ok, SpotifyController.message(outcome, query) + (note?.takeIf { ok }?.let { " $it" } ?: ""))
    }

    /**
     * The one "play this Spotify URI now" sequence, shared by `play_music` and Cruise's pinned
     * playlist tiles so the two cannot drift: bail if superseded, quiet the outgoing player,
     * [SpotifyController.playUriOutcome] (connect, switch to this device, optional shuffle,
     * play), re-check [stillWanted] after the play landed, and only then claim the speakers
     * (MusicSource -> SPOTIFY).
     *
     * [sourceBeforeConnect] is the source captured BEFORE any connect (connecting flips it to
     * SPOTIFY and would hide a still-playing phone/mixtape). [stillWanted] is the caller's
     * [PlayRequests] check.
     */
    suspend fun playResolvedUri(
        context: Context,
        uri: String,
        pickedLabel: String?,
        sourceBeforeConnect: Source,
        stillWanted: () -> Boolean,
        shuffle: Boolean = false,
        // VOICE by default: a voice play never opens the Spotify app (see uiLaunchRefusal).
        caller: com.kevin.midnightai.media.SpotifyConnectLogic.PlayCaller =
            com.kevin.midnightai.media.SpotifyConnectLogic.PlayCaller.VOICE,
    ): SpotifyController.PlayOutcome {
        // Search/connect can be slow; if the driver moved on meanwhile, do not silence what they chose.
        if (!stillWanted()) return SpotifyController.PlayOutcome.Superseded

        // Quiet the outgoing player so two sources do not talk over each other. Main thread: the
        // media framework needs a Looper, and this may run on a background dispatcher.
        when (sourceBeforeConnect) {
            Source.PHONE -> withContext(Dispatchers.Main) { MusicController.pausePhone(context) }
            Source.MIXTAPE -> withContext(Dispatchers.Main) { MixtapePlayer.pause() }
            Source.SPOTIFY -> Unit
        }

        val outcome = SpotifyController.playUriOutcome(
            context, uri, pickedLabel = pickedLabel, stillWanted = stillWanted, shuffle = shuffle, caller = caller,
        )
        val ok = SpotifyController.succeeded(outcome)
        if (ok && !stillWanted()) {
            // Superseded while play() was in flight. Claim nothing, and if the speakers moved to
            // another source, stop Spotify talking over it. A newer Spotify play replaces this one
            // on its own, so Spotify is left alone in that case.
            if (MusicSource.current.value != Source.SPOTIFY) MusicRouter.pauseSpotify(context)
            return SpotifyController.PlayOutcome.Superseded
        }
        if (ok) MusicSource.set(Source.SPOTIFY)
        return outcome
    }

    // --- control_music: the Spotify-only actions --------------------------------------------------

    /**
     * Routes one Spotify-only [MusicAction]. Refuses first unless the head-unit-Spotify source owns
     * the speakers; everything after that goes through [SpotifyController], which reconnects App
     * Remote if it dropped and confirms the result from Spotify rather than from the request.
     */
    suspend fun controlSpotify(context: Context, action: MusicAction, args: JSONObject): JSONObject {
        sourceRefusal()?.let { return it }
        return when (action) {
            MusicAction.QUEUE -> queue(context, args.optString("query"))
            MusicAction.LIKE -> library(context, SpotifyController.LibraryAction.LIKE)
            MusicAction.UNLIKE -> library(context, SpotifyController.LibraryAction.UNLIKE)
            MusicAction.FOLLOW_ARTIST -> library(context, SpotifyController.LibraryAction.FOLLOW_ARTIST)
            MusicAction.UNFOLLOW_ARTIST -> library(context, SpotifyController.LibraryAction.UNFOLLOW_ARTIST)
            MusicAction.SHUFFLE -> shuffle(context, turnOn = null)
            MusicAction.SHUFFLE_ON -> shuffle(context, turnOn = true)
            MusicAction.SHUFFLE_OFF -> shuffle(context, turnOn = false)
            MusicAction.REPEAT_OFF, MusicAction.REPEAT_TRACK, MusicAction.REPEAT_CONTEXT -> setRepeat(context, action)
            MusicAction.SEEK_FORWARD -> seek(context, forward = true, args.optInt("seconds", SpotifyVoiceLogic.DEFAULT_SEEK_SECONDS))
            MusicAction.SEEK_BACK -> seek(context, forward = false, args.optInt("seconds", SpotifyVoiceLogic.DEFAULT_SEEK_SECONDS))
            MusicAction.RESTART -> restart(context)
            MusicAction.MORE_FROM_ARTIST -> moreFromArtist(context)
            MusicAction.PLAY, MusicAction.PAUSE, MusicAction.NEXT, MusicAction.PREVIOUS ->
                error("transport actions route through MusicRouter, not SpotifyVoice: $action")
        }
    }

    /**
     * `queue`: resolves the name through the SAME search `play_music` uses, then adds it to
     * Spotify's up-next. "Play X next" and "add X to the queue" are one operation here - Spotify
     * has no insert-at-position, and the spoken line says so. Names the track Spotify resolved,
     * not the driver's words.
     */
    private suspend fun queue(context: Context, query: String): JSONObject {
        if (query.isBlank()) return result(false, "What should I queue?")
        val resolved = resolveSpotifyUri(context, query, "song", artist = null)
        val (uri, label) = when (resolved) {
            is Resolution.Found -> resolved.uri to SpotifyController.pickedLabel(resolved.name, resolved.subtitle)
            is Resolution.Failed -> return resolved.toolResult
        }
        val outcome = SpotifyController.queueUri(context, uri)
        return result(SpotifyController.succeeded(outcome), SpotifyController.message(outcome, label ?: query))
    }

    private suspend fun library(context: Context, action: SpotifyController.LibraryAction): JSONObject {
        val outcome = when (action) {
            SpotifyController.LibraryAction.LIKE -> SpotifyController.like(context)
            SpotifyController.LibraryAction.UNLIKE -> SpotifyController.unlike(context)
            SpotifyController.LibraryAction.FOLLOW_ARTIST -> SpotifyController.followArtist(context)
            SpotifyController.LibraryAction.UNFOLLOW_ARTIST -> SpotifyController.unfollowArtist(context)
        }
        return result(SpotifyController.succeeded(outcome), SpotifyController.message(outcome, action))
    }

    private suspend fun shuffle(context: Context, turnOn: Boolean?): JSONObject {
        val state = if (turnOn == null) SpotifyController.toggleShuffle(context) else SpotifyController.setShuffle(context, turnOn)
        return if (state != null) result(true, SpotifyController.shuffleMessage(state))
        else result(false, SpotifyController.notConnectedMessage())
    }

    private suspend fun setRepeat(context: Context, action: MusicAction): JSONObject {
        val state = when (action) {
            MusicAction.REPEAT_OFF -> SpotifyController.setRepeatOff(context)
            MusicAction.REPEAT_TRACK -> SpotifyController.setRepeatTrack(context)
            MusicAction.REPEAT_CONTEXT -> SpotifyController.setRepeatContext(context)
            else -> error("setRepeat called with a non-repeat action: $action")
        }
        return if (state != null) result(true, SpotifyController.repeatMessage(state))
        else result(false, SpotifyController.notConnectedMessage())
    }

    private suspend fun restart(context: Context): JSONObject {
        val state = SpotifyController.restart(context)
        return if (state != null) result(true, "Back to the start of the track.")
        else result(false, SpotifyController.notConnectedMessage())
    }

    /**
     * Seeking past the end of a track starts the next one (Spotify's documented behaviour), so
     * [SpotifyController.SeekOutcome.TrackChanged] is spoken as exactly that, never as "jumped
     * forward N seconds" when the track changed underneath the driver.
     */
    private suspend fun seek(context: Context, forward: Boolean, seconds: Int): JSONObject =
        when (SpotifyController.seekRelative(context, SpotifyVoiceLogic.seekDeltaMs(forward, seconds))) {
            is SpotifyController.SeekOutcome.Landed -> result(true, SpotifyVoiceLogic.seekMessage(forward, seconds))
            SpotifyController.SeekOutcome.TrackChanged ->
                result(true, "That went past the end of the track, so Spotify moved on to the next song.")
            SpotifyController.SeekOutcome.NotConnected -> result(false, SpotifyController.notConnectedMessage())
            SpotifyController.SeekOutcome.SeekRejected -> result(false, "Spotify wouldn't seek there.")
        }

    /**
     * "More from this artist": no search at all - the artist comes straight from App Remote's
     * player state, and the artist's own context (`spotify:artist:...`) plays through the same
     * outcome-reporting path as everything else. Fails honestly when nothing is playing rather
     * than guessing an artist from something heard earlier.
     */
    private suspend fun moreFromArtist(context: Context): JSONObject {
        val artist = SpotifyController.currentArtistNow(context) ?: return result(
            false,
            "I can't tell who's playing right now, so I don't know whose music to keep going with - " +
                "play something on Spotify first, then ask again.",
        )
        val outcome = SpotifyController.playUriOutcome(context, artist.uri, pickedLabel = artist.name)
        return result(SpotifyController.succeeded(outcome), SpotifyController.message(outcome, artist.name))
    }

    // --- get_music_queue -------------------------------------------------------------------------------

    /** What's coming up in Spotify's own queue. Source-gated: the queue read is about head-unit Spotify. */
    suspend fun getQueue(context: Context, args: JSONObject): JSONObject {
        sourceRefusal()?.let { return it }
        val limit = SpotifyVoiceLogic.clampLimit(args.optInt("limit", 0))
        return libraryOutcomeToJson(context, SpotifyWebApi.getQueue(context, limit), "the upcoming queue") { t ->
            JSONObject().put("name", t.name).put("artist", t.artist)
        }
    }

    // --- browse_my_music -------------------------------------------------------------------------------

    /** The `source` values `browse_my_music` offers. There is deliberately no on-device history source - see [browse]. */
    val BROWSE_SOURCES = listOf("saved_albums", "recently_played", "top_artists", "top_tracks", "artist_albums")

    /**
     * `browse_my_music`: reads one of the driver's own Spotify library endpoints. Routing only - the
     * honesty work (an empty answer is "Spotify said zero", a stale grant is named for what it is)
     * lives in [libraryOutcomeToJson].
     *
     * LEGION also has a `legion_history` source for its own observed-play ledger, with a replay
     * URI per row. It is NOT ported: this app's `music_plays` table stores no Spotify URI and no
     * replayable flag, so it cannot answer "play that thing from Tuesday", and a history read
     * already exists as `get_music_taste`. Adding the URI would be a Room schema change.
     *
     * Every account-level source works whichever player owns the speakers - they read the head
     * unit's Spotify account, not the playback session. `artist_albums` is the exception: it keys
     * off the artist App Remote says is playing, so it is source-gated.
     */
    suspend fun browse(context: Context, args: JSONObject): JSONObject {
        val limit = SpotifyVoiceLogic.clampLimit(args.optInt("limit", 0))
        return when (val source = args.optString("source")) {
            "saved_albums" -> libraryOutcomeToJson(context, SpotifyWebApi.getSavedAlbums(context, limit), "saved albums") { a ->
                JSONObject().put("name", a.name).put("artist", a.artist).put("spotifyUri", a.uri)
            }
            "recently_played" -> {
                val json = libraryOutcomeToJson(context, SpotifyWebApi.getRecentlyPlayed(context, limit), "recently played") { t ->
                    JSONObject().put("name", t.name).put("artist", t.artist).put("playedAt", t.playedAt)
                }
                // Said on every successful answer so the model cannot forget: this is Spotify's
                // OWN history across every device, not what played through this head unit.
                if (json.optBoolean("success")) {
                    json.put("note", "This is Spotify's own play history across every device the driver uses Spotify on, not just this head unit.")
                } else json
            }
            "top_artists" -> libraryOutcomeToJson(context, SpotifyWebApi.getTopArtists(context, limit), "top artists") { a ->
                JSONObject().put("name", a.name)
            }
            "top_tracks" -> libraryOutcomeToJson(context, SpotifyWebApi.getTopTracks(context, limit), "top tracks") { t ->
                JSONObject().put("name", t.name).put("artist", t.artist)
            }
            "artist_albums" -> artistAlbums(context, limit)
            else -> result(false, "Unknown source: $source")
        }
    }

    /**
     * "What else does he have": the CURRENT artist (read from App Remote's own player state, never
     * guessed) and their albums. Every row carries its `spotifyUri` so `play_music` can play "his
     * album Discovery" inside THIS artist's catalogue instead of an open search that could land on
     * someone else's album of the same name.
     */
    private suspend fun artistAlbums(context: Context, limit: Int): JSONObject {
        sourceRefusal()?.let { return it }
        val artist = SpotifyController.currentArtistNow(context) ?: return result(
            false,
            "I can't tell who's playing right now, so I don't know whose albums to look up - play " +
                "something on Spotify first, then ask again.",
        )
        return libraryOutcomeToJson(context, SpotifyWebApi.getArtistAlbums(context, artist.uri, limit), "${artist.name}'s albums") { a ->
            JSONObject().put("name", a.name).put("spotifyUri", a.uri)
        }
    }

    /**
     * Turns a [SpotifyWebApi.LibraryOutcome] into the tool-result shape with every failure named
     * for what it is, never a bare "couldn't get that". [sourceLabel] ("saved albums", "the
     * upcoming queue") is folded into the message so the driver hears WHICH request failed. An
     * EMPTY [SpotifyWebApi.LibraryOutcome.Found] is "Spotify said zero" - a real answer, never
     * phrased as a failure and never as if nothing was checked.
     */
    fun <T> libraryOutcomeToJson(
        context: Context,
        outcome: SpotifyWebApi.LibraryOutcome<T>,
        sourceLabel: String,
        toJson: (T) -> JSONObject,
    ): JSONObject = when (outcome) {
        is SpotifyWebApi.LibraryOutcome.Found -> {
            val items = JSONArray()
            outcome.items.forEach { items.put(toJson(it)) }
            val message = if (outcome.items.isEmpty()) "Spotify has no $sourceLabel to show right now." else null
            result(true, message).put("items", items).put("count", outcome.items.size)
        }
        SpotifyWebApi.LibraryOutcome.NeedsAuthorization -> result(
            false,
            "Spotify isn't finished connecting - open Settings, tap CONNECT under the Spotify client ID, " +
                "and approve it in the browser. Then I can look up $sourceLabel.",
        )
        is SpotifyWebApi.LibraryOutcome.Unauthorized -> unauthorized(context, outcome.detail, sourceLabel)
        SpotifyWebApi.LibraryOutcome.Unreachable -> unreachable(sourceLabel)
        is SpotifyWebApi.LibraryOutcome.Failed -> result(
            false,
            "Spotify's $sourceLabel request returned an error (${outcome.code})" +
                (outcome.detail?.let { ": $it" } ?: "."),
        )
    }
}
