package com.kevin.legion.service.tools.music

/**
 * Every listening source `browse_my_music` can read, as ONE list.
 *
 * It is one list because the two halves drifted apart and the user paid for it
 * (`music-suggestions-1124` P7): the merged `control_music` declaration advertised "his playlists,
 * liked songs or recent plays" while the `source` enum carried neither a playlists nor a liked
 * songs value, so the model picked the nearest value it had, got saved albums, and then asserted a
 * fact about playlists it had never looked at. The declaration's enum is now
 * [WIRE_VALUES] and the dispatch is a `when` over [BrowseSource], which the compiler keeps
 * exhaustive - the same implement-then-declare ordering
 * [com.kevin.legion.service.MusicControlToolbox.MusicAction] already documents for the action enum.
 *
 * So: adding a value here fails the build until its branch exists, and the declaration cannot
 * promise a source the dispatch does not serve.
 */
internal enum class BrowseSource(val wire: String) {
    SAVED_ALBUMS("saved_albums"),
    RECENTLY_PLAYED("recently_played"),
    TOP_ARTISTS("top_artists"),
    TOP_TRACKS("top_tracks"),
    LEGION_HISTORY("legion_history"),
    ARTIST_ALBUMS("artist_albums"),
    PLAYLISTS("playlists"),
    LIKED_SONGS("liked_songs"),
    ;

    companion object {
        /** The `source` enum the declaration carries, in this order. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        /** Parses the wire string the model sent. Unrecognised text is null, never a throw. */
        fun fromWire(value: String): BrowseSource? = entries.firstOrNull { it.wire == value }
    }
}
