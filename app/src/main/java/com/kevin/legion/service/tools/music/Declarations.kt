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
            description = "Music playback: 'play', 'pause', 'next', 'previous' " +
                "work with whatever's playing on the phone. 'queue' adds a NAMED track (pass " +
                "'query') to Spotify's own up-next queue via search - requires Spotify connected " +
                "in Setup, same as play_music. 'Play X next' and 'add X to the queue' are the " +
                "SAME thing here: Spotify has no way to insert at a specific position, only " +
                "next-up, so don't imply otherwise. To hear what's already queued, use " +
                "get_music_queue instead - this tool only adds, it doesn't read. " +
                "'like'/'unlike' act on the CURRENTLY PLAYING track only, never an album or the " +
                "whole queue. 'follow_artist'/'unfollow_artist' act on the current track's " +
                "artist. All four require Spotify connected in Setup and something actually " +
                "playing - if you say 'like this' twice, the second call comes back telling you " +
                "it was already liked, which is a real distinct answer, not a failure. " +
                "'shuffle' toggles shuffle to whatever it currently isn't; 'shuffle_on'/" +
                "'shuffle_off' set it explicitly - the spoken confirmation always says the state " +
                "that RESULTED, read back from Spotify, not just what was asked for. " +
                "'repeat_track' repeats ONLY the current track ('repeat this'); " +
                "'repeat_context' repeats the whole album/playlist ('repeat the album') - these " +
                "are genuinely different requests, don't collapse them. 'repeat_off' turns " +
                "repeat off entirely. 'seek_forward'/'seek_back' jump within the current track " +
                "- pass 'seconds' if the user named a duration, otherwise it defaults to 30. " +
                "Seeking forward past the end of the track moves on to the next song - say so " +
                "plainly if it happens, don't just report a jump. " +
                "'restart' jumps back to the start of the current track. 'more_from_artist' " +
                "('more from this artist', 'keep going with this artist') plays the CURRENT " +
                "track's artist directly - no search, no query needed, it's exact catalogue " +
                "navigation, not a suggestion. Requires something actually playing on Spotify; " +
                "if nothing is, or it's a non-Spotify source, say plainly you can't tell who's " +
                "playing rather than guessing an artist from something heard earlier. " +
                "'add_to_playlist' adds the CURRENTLY PLAYING track (same subject as 'like') to a " +
                "playlist the user names - pass 'playlistName'. Only matches playlists the user " +
                "owns or a friend made them a " +
                "collaborator on - it does NOT search the public catalogue for a playlist to add " +
                "to, and if the name matches a playlist that's followed but not owned or " +
                "collaborative (an editorial playlist like Discover Weekly), this fails and says " +
                "so - never report it as done and never claim there's no such playlist.",
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
                "query" to schema("string",
                    "Required for 'queue': what to add, in the user's own words, e.g. " +
                        "'Plastic Love by Mariya Takeuchi'."),
                "seconds" to schema("integer",
                    "For 'seek_forward'/'seek_back' only: how many seconds to jump. Defaults to 30."),
                "playlistName" to schema("string",
                    "Required for 'add_to_playlist': which playlist to add the current track to, " +
                        "in the user's own words, e.g. 'Roadtrip' or 'my summer playlist'."),
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
            description = "Play something specific by name - a song, artist, album, or playlist - " +
                "directly in-app via Spotify (requires the user to have connected their own Spotify " +
                "account in Setup). Use when the user names what they want to hear, e.g. 'play " +
                "Plastic Love' or 'play some city pop' (song, the default), 'play the album Discovery' " +
                "(album), or 'play my Roadtrip playlist' (playlist). Set 'type' to match what the " +
                "user actually asked for - defaults to song if they didn't say. For type 'playlist', " +
                "this checks the user's OWN playlists (including ones friends made him a " +
                "collaborator on) FIRST, and only falls back to Spotify's public catalogue if " +
                "nothing there matches - when it falls back, say so and name what actually played, " +
                "since it may not be what the user meant. Once something's playing, control_music " +
                "handles play/pause/skip. To replay something the user names from LEGION's own " +
                "history (browse_my_music's legion_history source, e.g. 'play that thing from " +
                "Tuesday'), OR to play a NAMED album from the current artist's own catalogue " +
                "(browse_my_music's artist_albums source, e.g. 'play his album Discovery'), pass " +
                "its spotifyUri exactly as returned - this skips search entirely and plays that " +
                "exact track/album, resolved within the artist's own catalogue rather than an open " +
                "search that could land on someone else's album of the same name. Only pass a " +
                "spotifyUri you actually received from a tool result; never invent one, and never " +
                "pass one for a legion_history row whose replayable field was false - say plainly " +
                "you can't replay that one instead.",
            params = obj(
                "query" to schema("string",
                    "What to play, in the user's own words, e.g. 'Plastic Love by Mariya Takeuchi' " +
                        "or 'Discovery by Daft Punk'. Still required even when spotifyUri is set - " +
                        "used to build the spoken confirmation."),
                "artist" to schema("string",
                    "The artist, whenever you know it - including from earlier in the " +
                        "conversation ('play that song' after discussing an album). When you " +
                        "pass it, put ONLY the title in 'query'. Used for songs and albums; " +
                        "ignored for artist and playlist requests."),
                "type" to schema("string",
                    "What kind of thing 'query' names. Defaults to 'song' if omitted.",
                    enum = listOf("song", "artist", "album", "playlist")),
                "spotifyUri" to schema("string",
                    "A concrete Spotify URI already known from a prior tool result - only " +
                        "legion_history rows with replayable true, and artist_albums rows, carry " +
                        "one. When set, this is played directly with no search."),
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
