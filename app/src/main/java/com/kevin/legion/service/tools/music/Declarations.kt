@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.music

import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import org.json.JSONObject

/**
 * The music tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        add(fn(
            name = "control_music",
            // Live cost diet 2026-11-10: trimmed to what picks and fills the call; what to say
            // about the outcome rides on the result (LiveSurface.RESULT_RULES["control_music"]).
            description = "Playback of whatever is playing: play, pause, next, previous, restart, " +
                "seek_forward/seek_back (seconds, default 30). Spotify only, on the CURRENT track: " +
                "queue adds a named track next-up (query; 'play X next' is the same, no other " +
                "position exists), like/unlike, follow_artist/unfollow_artist, more_from_artist, " +
                "add_to_playlist (playlistName; his own or collaborative playlists only), " +
                "shuffle/shuffle_on/shuffle_off, repeat_track (this song) versus repeat_context " +
                "(the album or playlist), repeat_off.",
            params = obj(
                "action" to schema("string", "The playback action.",
                    enum = listOf(
                        "play", "pause", "next", "previous", "queue",
                        "like", "unlike", "follow_artist", "unfollow_artist",
                        "shuffle", "shuffle_on", "shuffle_off",
                        "repeat_off", "repeat_track", "repeat_context",
                        "seek_forward", "seek_back", "restart",
                        "more_from_artist", "add_to_playlist",
                    )),
                "query" to schema("string", "queue: what to add, in his words."),
                "seconds" to schema("integer", "seek: seconds to jump."),
                "playlistName" to schema("string", "add_to_playlist: which playlist, in his words."),
            ),
            required = listOf("action"),
        ))

        add(fn(
            name = "control_volume",
            description = "Adjust the phone's music/media volume - an instant on-device action. " +
                "'up'/'down' nudge it, 'set' jumps to a level (0-100), 'mute'/'unmute' silence or " +
                "restore it. This controls the music the user hears, not your own speaking voice.",
            params = obj(
                "action" to schema("string", "The volume action.",
                    enum = listOf("up", "down", "set", "mute", "unmute")),
                "level" to schema("integer", "For 'set': target volume 0-100. Required for 'set'."),
            ),
            required = listOf("action"),
        ))

        add(fn(
            name = "play_music",
            // Live cost diet 2026-11-10: trimmed; the fallback and uri rules ride on the result
            // (LiveSurface.RESULT_RULES["play_music"]).
            description = "Play a named song, artist, album or playlist on Spotify. type: song " +
                "(default), artist, album or playlist; a playlist is looked up in his own first. " +
                "spotifyUri: only one a tool result gave you (control_music browse_my_music " +
                "legion_history rows with replayable true, artist_albums rows); never invent one. " +
                "Play, pause and skip are control_music.",
            params = obj(
                "query" to schema("string",
                    "What to play, his words; required even with spotifyUri. With artist set, " +
                        "the title only."),
                "artist" to schema("string", "The artist whenever you know it, also from earlier."),
                "type" to schema("string", "Defaults to song.",
                    enum = listOf("song", "artist", "album", "playlist")),
                "spotifyUri" to schema("string", "A URI a tool result gave you; played with no search."),
            ),
            required = listOf("query"),
        ))

        add(fn(
            name = "browse_my_music",
            description = "Look up the user's Spotify listening. 'saved_albums' - albums " +
                "they've liked on Spotify. 'recently_played' - Spotify's OWN play history, " +
                "across every device they use Spotify on, not just here - say so if you mention " +
                "it. 'top_artists'/'top_tracks' - Spotify's own top rankings for this account. " +
                "'legion_history' - what LEGION ITSELF has observed playing on THIS device only; " +
                "always say plainly this is LEGION's own count, never Spotify's, and that any " +
                "'favourite' you mention from it is LEGION's own inference from what it happened " +
                "to see - never present it as a number Spotify published. 'artist_albums' - what " +
                "the CURRENTLY PLAYING artist has out, for 'what else does he have' / 'what other " +
                "albums does she have' - taken straight from the current track's artist, no name " +
                "needed from the user; requires something actually playing on Spotify, and if " +
                "nothing is (or it's a non-Spotify source) this fails saying it can't tell who's " +
                "playing rather than guessing. Every result item here carries its own spotifyUri - " +
                "to play one of them, pass that spotifyUri straight to play_music (same as a " +
                "legion_history row), never search for it by name; that resolves within THIS " +
                "artist's own catalogue rather than an open search that could land on someone " +
                "else's album of the same name. Every source needs Spotify connected and approved " +
                "in Setup (same as play_music). Keep the spoken answer short - name a handful of results, don't read " +
                "out the whole list.",
            params = obj(
                "source" to schema("string", "Which listening data to look up.",
                    enum = listOf(
                        "saved_albums", "recently_played", "top_artists", "top_tracks",
                        "legion_history", "artist_albums",
                    )),
                "limit" to schema("integer",
                    "How many results to return. Defaults to 5 - a live voice turn shouldn't " +
                        "read out fifty rows."),
            ),
            required = listOf("source"),
        ))

        add(fn(
            name = "get_music_queue",
            description = "What's coming up next in Spotify's own queue - 'what's playing next', " +
                "'what's coming up'. Read-only; to ADD something to the queue use control_music's " +
                "'queue' action instead. Requires Spotify connected and approved in Setup (same " +
                "as play_music). Keep the spoken answer short.",
            params = obj(
                "limit" to schema("integer",
                    "How many upcoming tracks to return. Defaults to 5."),
            ),
            required = listOf(),
        ))
    }
}
