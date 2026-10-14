package com.kevin.midnightai.media

/**
 * The pure decisions behind the Spotify voice tools (`control_music`'s extra actions,
 * `browse_my_music`, `get_music_queue`, `play_music`): action parsing, the "is this even the right
 * source" gate, and the spoken lines that depend only on plain data. No Android, no SDK, so all of
 * it is a plain JVM unit test; the I/O lives in `service/SpotifyVoice.kt`.
 */
object SpotifyVoiceLogic {

    /**
     * The `control_music` actions. The first four are the long-standing transport and keep
     * routing through [MusicRouter] for PHONE / MIXTAPE / SPOTIFY exactly as before. Every other
     * entry is [spotifyOnly]: it acts on head-unit Spotify through App Remote and has no meaning
     * for a phone Bluetooth stream or a mixtape, so it is refused (in words) off that source.
     */
    enum class MusicAction(val wire: String, val spotifyOnly: Boolean = true) {
        PLAY("play", spotifyOnly = false),
        PAUSE("pause", spotifyOnly = false),
        NEXT("next", spotifyOnly = false),
        PREVIOUS("previous", spotifyOnly = false),
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
        MORE_FROM_ARTIST("more_from_artist");

        companion object {
            fun fromWire(value: String): MusicAction? = entries.firstOrNull { it.wire == value }
        }
    }

    /** Wire values for the tool schema's `enum`, in declaration order. */
    val WIRE_VALUES: List<String> = MusicAction.entries.map { it.wire }

    /**
     * The sentence for refusing a Spotify-only action, or null when the current [source] is
     * head-unit Spotify and the action may proceed. Phone-Bluetooth and mixtape playback have no
     * App Remote session to act on, and acting on the wrong player (liking the head unit's last
     * track while the phone plays something else) would be worse than refusing.
     */
    fun sourceBlocker(source: Source): String? = when (source) {
        Source.SPOTIFY -> null
        Source.PHONE ->
            "That only works with Spotify on the head unit, and the music is coming from your phone " +
                "right now. Say \"switch to the head unit\" first if you want it there."
        Source.MIXTAPE ->
            "That only works with Spotify on the head unit, and a mixtape is playing right now. " +
                "Stop the tape or say \"switch to the head unit\" first."
    }

    const val DEFAULT_SEEK_SECONDS = 30
    private const val MAX_SEEK_SECONDS = 600

    /** Signed millisecond delta for a seek, with the spoken-friendly seconds clamped to 1..600. */
    fun seekDeltaMs(forward: Boolean, seconds: Int): Long {
        val secs = seconds.coerceIn(1, MAX_SEEK_SECONDS)
        return secs * 1000L * if (forward) 1L else -1L
    }

    /** The seconds actually used for [seekDeltaMs] - what the spoken line must report. */
    fun seekSeconds(seconds: Int): Int = seconds.coerceIn(1, MAX_SEEK_SECONDS)

    /** How many rows a list tool returns: the caller's ask, held to 1..10 so a voice turn never reads fifty. */
    fun clampLimit(requested: Int, default: Int = 5): Int =
        (if (requested <= 0) default else requested).coerceIn(1, 10)

    /** Normalises the tool's `type` argument to the tool-facing vocabulary; anything unknown is a song. */
    fun normalizeType(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "artist", "album", "playlist" -> raw.trim().lowercase()
        else -> "song"
    }

    /** Spotify's word for a tool-facing type ("song" is "track" to the search endpoint). */
    fun searchType(toolType: String): String = if (toolType == "song") "track" else toolType

    /**
     * The honesty line when "play my X playlist" fell through to the public catalogue, so what
     * started may not be what the driver meant. [libraryChecked] false means their own list was
     * never read (offline, or a token without the playlist scope): the line must not claim it
     * was searched.
     */
    fun playlistFallbackNote(query: String, pickedName: String?, libraryChecked: Boolean): String {
        val named = pickedName?.takeIf { it.isNotBlank() }?.let { " - \"$it\"" } ?: ""
        return if (libraryChecked) {
            "Couldn't find \"$query\" among your own playlists, so I played the closest match in " +
                "Spotify's public catalogue$named."
        } else {
            "I couldn't read your own playlists just now, so I played the closest match in " +
                "Spotify's public catalogue$named - it may not be yours."
        }
    }

    /** "Jumped forward 30 seconds." - reports the seconds that were actually used. */
    fun seekMessage(forward: Boolean, seconds: Int): String =
        "Jumped ${if (forward) "forward" else "back"} ${seekSeconds(seconds)} seconds."
}
