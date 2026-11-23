package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.media.MusicController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.SpotifyController
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.service.MusicToolbox.controlMusicQueue
import com.kevin.legion.service.MusicToolbox.spotifyNeedsAuthorizationResult
import com.kevin.legion.service.MusicToolbox.spotifyUnauthorizedResult
import com.kevin.legion.service.MusicToolbox.spotifyUnreachableResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * `control_music` (transport, shuffle, repeat, seek, library actions, add to playlist, queue), split
 * out of [LiveToolbox] (shrink pass, 2026-11-22) along the [EngineToolbox.dispatch] pattern:
 * [LiveToolbox.dispatch] runs its guards, then asks this object, which answers the names it owns
 * and returns null for every other. Playing and the volume are [MusicToolbox]; library reads are
 * [MusicLibraryToolbox]. The declaration stays in `service/tools/`.
 */
object MusicControlToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "control_music" -> controlMusic(context, args)
        else -> null
    }

    /**
     * The action-dispatch shape `control_music` grows into (ticket 03,
     * `.scratch/spotify-voice/issues/03-tool-surface.md`). The map's ten-plus new music
     * capabilities (queue, like/unlike, follow/unfollow, shuffle, repeat, seek) fold into this
     * ONE enum as additional entries rather than spawning sibling tool declarations - map
     * decision "fold not spawn" (settled with Kevin 2026-08-19): a bloated tool surface makes
     * the live model choose worse (the `log_workout_set` routing bug this repo already paid
     * for), and music is the one domain where a second `ask_music` dispatcher round trip IS
     * the complaint.
     *
     * **This enum is always allowed to run AHEAD of the JSON schema `enum` in
     * [declarations]'s `control_music` entry - the schema only widens the moment the case
     * below it is actually wired to real Spotify/MediaSession behaviour, in the SAME commit.**
     * ADR 0031 and this ticket name the same failure from two directions: a declared action
     * that does nothing is a lying tool description. So every entry lands implement-then-declare,
     * never the reverse, and the compiler enforces the ordering incidentally - [controlMusic]'s
     * `when` below is exhaustive over exactly the entries that exist, so adding one without
     * also adding its branch fails the build rather than shipping a silent no-op action.
     */
    internal enum class MusicAction(val wireValue: String) {
        PLAY("play"),
        PAUSE("pause"),
        NEXT("next"),
        PREVIOUS("previous"),
        QUEUE("queue"),
        LIKE("like"),
        UNLIKE("unlike"),
        FOLLOW_ARTIST("follow_artist"),
        UNFOLLOW_ARTIST("unfollow_artist"),
        SHUFFLE("shuffle"),
        SHUFFLE_ON("shuffle_on"),
        SHUFFLE_OFF("shuffle_off"),
        REPEAT_OFF("repeat_off"),
        REPEAT_TRACK("repeat_track"),
        REPEAT_CONTEXT("repeat_context"),
        SEEK_FORWARD("seek_forward"),
        SEEK_BACK("seek_back"),
        RESTART("restart"),
        /** Ticket 08 scope item 3: adds the CURRENTLY PLAYING track to a named playlist. */
        ADD_TO_PLAYLIST("add_to_playlist"),
        // ticket 13 (.scratch/spotify-voice/issues/13-more-from-this-artist.md): catalogue
        // navigation off the CURRENT artist, not a search - see controlMusicMoreFromArtist.
        MORE_FROM_ARTIST("more_from_artist"),
        ;

        companion object {
            /** Parses the wire string the live model sent. Unrecognized text is null, never a throw. */
            internal fun fromWire(value: String): MusicAction? = entries.firstOrNull { it.wireValue == value }
        }
    }

    /**
     * Routes a parsed [MusicAction] to its implementation. Transport (play/pause/next/previous)
     * over TWO independent backends.
     *
     * [MusicController] (Android's media-session framework) is preferred because it
     * drives whatever is actually playing - the phone-BT relay, Spotify, anything
     * that publishes a session. But `MediaSessionManager.getActiveSessions` requires
     * the one-time notification-access grant, and without it every command silently
     * no-ops: the SecurityException is swallowed into an empty session list
     * ([MusicController.activeSessions]), so the transport call never happens and the
     * tool just reports failure.
     *
     * Found on device 2026-08-16 (Kevin): "ai can play what i ask but i couldn't get
     * it to pause or skip". `play_music` goes to [SpotifyController.playUri] over App
     * Remote, which needs NO notification grant, so starting a track worked while
     * every transport command failed - and the grant is per-device special access,
     * so the A25 migration dropped it. The two paths had no reason to agree and
     * nothing said why.
     *
     * So App Remote is now the fallback: it drives Spotify only, but it needs no
     * grant at all. Ordering keeps the granted case byte-identical to before, and
     * only adds a second attempt where the first currently fails.
     *
     * When neither can act, [musicFailureMessage] says which of the two reasons it
     * was, in words, rather than one generic line. There is no third silent no-op.
     *
     * The media framework expects to run on a thread with a Looper; Live tool
     * dispatch runs on a worker, so every MusicController call is marshalled to
     * [Dispatchers.Main] (a bare worker thread threw "Can't create handler ...",
     * crashing the app on next/play). App Remote has no such requirement.
     */
    private suspend fun controlMusic(context: Context, args: JSONObject): JSONObject {
        val raw = args.optString("action")
        val action = MusicAction.fromWire(raw)
            ?: return result(success = false, message = "Unknown music action: $raw")

        return when (action) {
            MusicAction.PLAY, MusicAction.PAUSE, MusicAction.NEXT, MusicAction.PREVIOUS ->
                controlMusicTransport(context, action)
            MusicAction.QUEUE -> controlMusicQueue(context, args.optString("query"))
            MusicAction.LIKE -> controlMusicLibrary(context, SpotifyController.LibraryAction.LIKE)
            MusicAction.UNLIKE -> controlMusicLibrary(context, SpotifyController.LibraryAction.UNLIKE)
            MusicAction.FOLLOW_ARTIST -> controlMusicLibrary(context, SpotifyController.LibraryAction.FOLLOW_ARTIST)
            MusicAction.UNFOLLOW_ARTIST -> controlMusicLibrary(context, SpotifyController.LibraryAction.UNFOLLOW_ARTIST)
            MusicAction.SHUFFLE -> controlMusicShuffle(context, turnOn = null)
            MusicAction.SHUFFLE_ON -> controlMusicShuffle(context, turnOn = true)
            MusicAction.SHUFFLE_OFF -> controlMusicShuffle(context, turnOn = false)
            MusicAction.REPEAT_OFF, MusicAction.REPEAT_TRACK, MusicAction.REPEAT_CONTEXT ->
                controlMusicRepeat(context, action)
            MusicAction.SEEK_FORWARD -> controlMusicSeek(context, forward = true, seconds = args.optInt("seconds", DEFAULT_SEEK_SECONDS))
            MusicAction.SEEK_BACK -> controlMusicSeek(context, forward = false, seconds = args.optInt("seconds", DEFAULT_SEEK_SECONDS))
            MusicAction.RESTART -> controlMusicRestart(context)
            MusicAction.ADD_TO_PLAYLIST -> controlMusicAddToPlaylist(context, args.optString("playlistName"))
            MusicAction.MORE_FROM_ARTIST -> controlMusicMoreFromArtist(context)
        }
    }

    /**
     * "More from this artist" (ticket 13, `.scratch/spotify-voice/issues/13-more-from-this-artist.md`
     * scope item 1): no search at all - `SpotifyController.currentArtist` reads the artist straight
     * off App Remote's own pushed player state (the same truth ticket 07's now-playing uses), and
     * this plays that artist's own Spotify context (`spotify:artist:...`) through the SAME
     * [SpotifyController.playUri] path every other play in this file uses - App Remote's `play()`
     * documented as accepting an artist URI directly, no different from a track/album/playlist one.
     * When nothing is playing (or a non-Spotify session is), [SpotifyController.currentArtist] is
     * null and this fails honestly rather than ever guessing an artist from something seen earlier
     * (ticket 13 scope item 5).
     */
    private suspend fun controlMusicMoreFromArtist(context: Context): JSONObject {
        val artist = SpotifyController.currentArtist() ?: return result(
            success = false,
            message = "I can't tell who's playing right now, so I don't know whose music to keep " +
                "going with - play something on Spotify first, then ask again.",
        )
        val outcome = SpotifyController.playUri(context, artist.uri, pickedLabel = artist.name)
        if (SpotifyController.succeeded(outcome)) {
            NowPlayingController.markLegionInitiatedPlay()
        }
        return result(success = SpotifyController.succeeded(outcome), message = SpotifyController.message(outcome, artist.name))
    }

    /** "Back 30 seconds" with nothing said defaults to 30 (ticket 06 scope item 3). */
    private const val DEFAULT_SEEK_SECONDS = 30

    /**
     * `shuffle`/`shuffle_on`/`shuffle_off` (ticket 06,
     * `.scratch/spotify-voice/issues/06-shuffle-repeat-seek.md`). [turnOn] null means the bare
     * "shuffle" action (toggle); non-null means the explicit on/off request. Either way the
     * spoken line is built from [SpotifyController.TransportWriteResult] - the state that
     * RESULTED, read back from Spotify after the write, never the state that was asked for.
     */
    private suspend fun controlMusicShuffle(context: Context, turnOn: Boolean?): JSONObject {
        val state = if (turnOn == null) SpotifyController.toggleShuffle(context) else SpotifyController.setShuffle(context, turnOn)
        return if (state != null) result(success = true, message = SpotifyController.shuffleMessage(state))
        else result(success = false, message = SpotifyController.transportWriteFailureMessage())
    }

    /**
     * `repeat_off`/`repeat_track`/`repeat_context` - "repeat this" is the track, "repeat the
     * album" is the context, and the two are genuinely different requests (ticket 06 scope item
     * 2), never collapsed into one "repeat" action.
     */
    private suspend fun controlMusicRepeat(context: Context, action: MusicAction): JSONObject {
        val state = when (action) {
            MusicAction.REPEAT_OFF -> SpotifyController.setRepeatOff(context)
            MusicAction.REPEAT_TRACK -> SpotifyController.setRepeatTrack(context)
            MusicAction.REPEAT_CONTEXT -> SpotifyController.setRepeatContext(context)
            else -> error("controlMusicRepeat called with a non-repeat action: $action")
        }
        return if (state != null) result(success = true, message = SpotifyController.repeatMessage(state))
        else result(success = false, message = SpotifyController.transportWriteFailureMessage())
    }

    /**
     * `restart` - jumps back to the start of the current track.
     */
    private suspend fun controlMusicRestart(context: Context): JSONObject {
        val state = SpotifyController.restart(context)
        return if (state != null) result(success = true, message = "Restarted the track.")
        else result(success = false, message = SpotifyController.transportWriteFailureMessage())
    }

    /**
     * `add_to_playlist` (ticket 08 scope item 3): adds the CURRENTLY PLAYING track - same subject
     * as `like` - to a playlist named by the driver. Deliberately **library-only**: resolves the
     * target through [SpotifyWebApi.findMyPlaylist], never [SpotifyWebApi.resolvePlaylist]'s
     * public-search fallback, because adding to a playlist the driver doesn't own or collaborate
     * on would almost always 403 anyway and was never what "add this to Roadtrip" meant (see
     * [SpotifyWebApi.findMyPlaylist]'s own doc). A playlist that DOES match but isn't
     * [SpotifyWebApi.SpotifyPlaylist.readable] - the research's ownership/collaborator boundary,
     * hit hardest by a followed EDITORIAL playlist like Discover Weekly - is named as unreadable
     * in words before any write is attempted (ticket 08 scope item 4), never silently skipped and
     * never reported as "no such playlist".
     */
    private suspend fun controlMusicAddToPlaylist(context: Context, playlistName: String): JSONObject {
        if (playlistName.isBlank()) return result(success = false, message = "Which playlist should I add this to?")

        val track = when (val t = SpotifyController.currentTrack(context)) {
            is SpotifyController.CurrentTrackOutcome.Found -> t
            SpotifyController.CurrentTrackOutcome.NothingPlaying -> return result(
                success = false,
                message = "Nothing's playing right now, so there's nothing to add.",
            )
            SpotifyController.CurrentTrackOutcome.NotConnected -> return result(
                success = false,
                message = "Spotify isn't connected - connect your Spotify account in Setup, or pick " +
                    "something on your phone yourself and I'll control play/pause/skip from here.",
            )
        }

        val playlist = when (val lookup = SpotifyWebApi.findMyPlaylist(context, playlistName)) {
            is SpotifyWebApi.PlaylistLookup.Found -> lookup.playlist
            SpotifyWebApi.PlaylistLookup.NeedsAuthorization -> return spotifyNeedsAuthorizationResult()
            is SpotifyWebApi.PlaylistLookup.Unauthorized -> return spotifyUnauthorizedResult(lookup.detail)
            SpotifyWebApi.PlaylistLookup.Unreachable -> return spotifyUnreachableResult()
            SpotifyWebApi.PlaylistLookup.NoMatch -> return result(
                success = false,
                message = "\"$playlistName\" doesn't match any playlist you own or a friend has " +
                    "shared with you - I can only add to playlists in your own library.",
            )
            is SpotifyWebApi.PlaylistLookup.Failed -> return result(
                success = false,
                message = "Spotify's playlist lookup returned an error (${lookup.code})" +
                    (lookup.detail?.let { ": $it" } ?: "."),
            )
        }

        // The honesty boundary this ticket exists for: said in words BEFORE attempting the
        // write, never left to surface as a bare 403.
        if (!playlist.readable) {
            return result(
                success = false,
                message = "I can't add to \"${playlist.name}\" - you don't own it or collaborate on " +
                    "it, so Spotify won't allow changes to it. This is Spotify's own rule, the same " +
                    "reason a playlist like Discover Weekly can't be edited by anyone but Spotify.",
            )
        }

        return when (val outcome = SpotifyWebApi.addTrackToPlaylist(context, playlist.id, track.uri)) {
            SpotifyWebApi.PlaylistWriteOutcome.Added -> result(
                success = true,
                message = "Added \"${track.name}\" to \"${playlist.name}\".",
            )
            // Belt-and-braces: the cache said readable, Spotify disagreed. Same honest wording as
            // the proactive check above rather than a generic error.
            SpotifyWebApi.PlaylistWriteOutcome.NotYours -> result(
                success = false,
                message = "Spotify wouldn't let me add to \"${playlist.name}\" - you don't own or " +
                    "collaborate on it.",
            )
            SpotifyWebApi.PlaylistWriteOutcome.NeedsAuthorization -> spotifyNeedsAuthorizationResult()
            is SpotifyWebApi.PlaylistWriteOutcome.Unauthorized -> spotifyUnauthorizedResult(outcome.detail)
            SpotifyWebApi.PlaylistWriteOutcome.Unreachable -> spotifyUnreachableResult()
            is SpotifyWebApi.PlaylistWriteOutcome.Failed -> result(
                success = false,
                message = "Spotify wouldn't add that (${outcome.code})" + (outcome.detail?.let { ": $it" } ?: "."),
            )
        }
    }

    /**
     * `seek_forward`/`seek_back` - relative seek, default [DEFAULT_SEEK_SECONDS]. **Seeking past
     * the end of a track starts the next one** (Spotify's own documented behaviour, ticket 06
     * scope item 3) - [SpotifyController.SeekOutcome.TrackChanged] is spoken as exactly that,
     * never as "jumped forward N seconds" when the track actually changed underneath the driver.
     */
    private suspend fun controlMusicSeek(context: Context, forward: Boolean, seconds: Int): JSONObject {
        val secs = seconds.coerceAtLeast(1)
        val deltaMs = secs * 1000L * if (forward) 1L else -1L
        return when (val outcome = SpotifyController.seekRelative(context, deltaMs)) {
            is SpotifyController.SeekOutcome.Landed ->
                result(success = true, message = "Jumped ${if (forward) "forward" else "back"} $secs seconds.")
            SpotifyController.SeekOutcome.TrackChanged ->
                result(success = true, message = "That went past the end of the track, so Spotify moved on to the next song.")
            SpotifyController.SeekOutcome.NotConnected ->
                result(success = false, message = SpotifyController.transportWriteFailureMessage())
            SpotifyController.SeekOutcome.SeekRejected ->
                result(success = false, message = "Spotify wouldn't seek there.")
        }
    }

    /**
     * `like`/`unlike`/`follow_artist`/`unfollow_artist` (ticket 05,
     * `.scratch/spotify-voice/issues/05-library-writes.md`): all four route through
     * [SpotifyController]'s shared `libraryWrite` sequence, which reads `getLibraryState` BEFORE
     * writing so "already liked" and "liked it" are told apart rather than guessed.
     */
    private suspend fun controlMusicLibrary(context: Context, action: SpotifyController.LibraryAction): JSONObject {
        val outcome = when (action) {
            SpotifyController.LibraryAction.LIKE -> SpotifyController.like(context)
            SpotifyController.LibraryAction.UNLIKE -> SpotifyController.unlike(context)
            SpotifyController.LibraryAction.FOLLOW_ARTIST -> SpotifyController.followArtist(context)
            SpotifyController.LibraryAction.UNFOLLOW_ARTIST -> SpotifyController.unfollowArtist(context)
        }
        return result(success = SpotifyController.succeeded(outcome), message = SpotifyController.message(outcome, action))
    }

    /**
     * Handles ONLY [MusicAction.PLAY]/[PAUSE]/[NEXT]/[PREVIOUS] - [controlMusic]'s own `when` is
     * what actually restricts callers to those four. The `else` branches below exist purely to
     * satisfy exhaustiveness against the FULL [MusicAction] enum (which keeps growing as later
     * tickets land); reaching one would mean [controlMusic]'s routing itself is broken, so they
     * throw rather than silently doing nothing.
     *
     * `internal` (not private) - command-center ticket 08's drift-debt half:
     * `ui/media/MediaTransport.kt`'s `run` used to restate this exact two-backend ordering (its
     * own doc named the reason: this function used to be `private`, "file-scoped in Kotlin even
     * within the same module"). It now calls this SAME function, so the ordering can never drift
     * out of step between the voice tool and the hands path again.
     */
    internal suspend fun controlMusicTransport(context: Context, action: MusicAction): JSONObject {
        if (NowPlayingController.hasAccess(context)) {
            val ok = withContext(Dispatchers.Main) {
                when (action) {
                    MusicAction.PLAY     -> MusicController.play(context)
                    MusicAction.PAUSE    -> MusicController.pause(context)
                    MusicAction.NEXT     -> MusicController.next(context)
                    MusicAction.PREVIOUS -> MusicController.previous(context)
                    else -> error("controlMusicTransport called with non-transport action: $action")
                }
            }
            if (ok) return result(success = true, message = null)
        }

        // Only attempt this against a live remote. [SpotifyController.withPlayer]
        // reports success the instant the call is DISPATCHED rather than when it
        // lands (unlike playUri, which awaits the real CallResult), so gating on
        // isConnected is what keeps a `true` here from becoming a claim the app
        // cannot support.
        if (SpotifyController.isConnected) {
            val ok = when (action) {
                MusicAction.PLAY     -> SpotifyController.play()
                MusicAction.PAUSE    -> SpotifyController.pause()
                MusicAction.NEXT     -> SpotifyController.next()
                MusicAction.PREVIOUS -> SpotifyController.previous()
                else -> error("controlMusicTransport called with non-transport action: $action")
            }
            if (ok) return result(success = true, message = null)
        }

        return result(success = false, message = musicFailureMessage(context))
    }

    /**
     * Names the actual reason transport failed. The missing-grant case used to be
     * indistinguishable from "nothing is playing", which is why the defect above
     * survived: the app knew exactly why it could not act and said nothing.
     */
    internal fun musicFailureMessage(context: Context): String =
        if (!NowPlayingController.hasAccess(context)) {
            "I can't reach the transport controls. Android requires notification access " +
                "to control media, and Legion doesn't have it - it's under Settings, Apps, " +
                "Special app access, Notification access. Until then I can start a track on " +
                "Spotify, but I can't pause or skip anything else."
        } else {
            "Could not reach the music player. Check that something is playing."
        }
}
