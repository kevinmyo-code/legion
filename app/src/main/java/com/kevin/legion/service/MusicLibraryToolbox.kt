package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MusicPlayHistoryEntry
import com.kevin.legion.media.SpotifyController
import com.kevin.legion.media.SpotifyPlaylist
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.service.tools.music.BrowseSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * The music library reads (`browse_my_music`, `get_music_queue`), split out of [LiveToolbox]
 * (shrink pass, 2026-11-22) along the [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch]
 * runs its guards, then asks this object, which answers the names it owns and returns null for
 * every other. Playing music is [MusicToolbox]; the declarations stay in `service/tools/`.
 */
object MusicLibraryToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "browse_my_music" -> browseMyMusic(context, args)
        "get_music_queue" -> getMusicQueue(context, args)
        else -> null
    }

    /**
     * `get_music_queue` (ticket 04 scope item 3): "what's coming up" - Spotify's OWN up-next
     * queue via [SpotifyWebApi.getQueue]. This is the map's one net-new tool declaration across
     * tickets 03-06 (ticket 03 scope item 4): a READ belongs as its own tool rather than folded
     * into the WRITE-shaped `control_music` action enum, and no existing declaration's shape
     * fits "list what's coming up" without stretching its own promise.
     */
    private suspend fun getMusicQueue(context: Context, args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 5).coerceIn(1, 10)
        return libraryOutcomeToJson(context, SpotifyWebApi.getQueue(context, limit), "the upcoming queue") { t ->
            JSONObject().put("name", t.name).put("artist", t.artist)
        }
    }

    /**
     * `browse_my_music` (ticket 05, 2026-08-18): reads one of Spotify's own library endpoints,
     * or LEGION's own observed-listening table. Routing only - the actual honesty work (never
     * silently reading empty as "nothing there", always naming a stale-grant failure for what it
     * is) lives in [libraryOutcomeToJson] and [browseLegionHistory] below, since both non-Spotify
     * and Spotify sources have to make the same promise for different reasons.
     */
    private suspend fun browseMyMusic(context: Context, args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 5).coerceIn(1, 10)
        val raw = args.optString("source")
        val source = BrowseSource.fromWire(raw)
            ?: return result(success = false, message = "Unknown source: $raw")
        return when (source) {
            BrowseSource.SAVED_ALBUMS -> libraryOutcomeToJson(
                context, SpotifyWebApi.getSavedAlbums(context, limit), "saved albums",
            ) { a -> JSONObject().put("name", a.name).put("artist", a.artist) }
            // Deliberately ignores `limit`: answering "which playlists do I have" from a page of
            // five is how a library read turned into a claim about what is NOT in the library, and
            // a NAMED lookup served off the first five rows can still miss the one he asked for
            // (music-suggestions-1124 P7). Playlist names are short; the whole cached list is
            // cheaper than a wrong answer.
            BrowseSource.PLAYLISTS -> browsePlaylists(context, args.optString("playlistName").ifBlank { null })
            BrowseSource.LIKED_SONGS -> libraryOutcomeToJson(
                context, SpotifyWebApi.getLikedSongs(context, limit), "liked songs",
            ) { t -> JSONObject().put("name", t.name).put("artist", t.artist).put("spotifyUri", t.uri) }
            BrowseSource.RECENTLY_PLAYED -> {
                val json = libraryOutcomeToJson(context, SpotifyWebApi.getRecentlyPlayed(context, limit), "recently played") { t ->
                    JSONObject().put("name", t.name).put("artist", t.artist)
                        .put("playedAt", com.kevin.legion.util.LocalClock.stamp(t.playedAt))
                }
                // Honesty requirement (ticket 05 part C.9): this is Spotify's OWN history,
                // everywhere the driver uses Spotify - not scoped to LEGION or to this device.
                // Said on every successful answer, not just when asked, so the model can't
                // forget to pass it along.
                if (json.optBoolean("success")) {
                    json.put(
                        "note",
                        "This is Spotify's own play history across every device the user uses " +
                            "Spotify on, not just through LEGION.",
                    )
                } else json
            }
            BrowseSource.TOP_ARTISTS -> libraryOutcomeToJson(
                context, SpotifyWebApi.getTopArtists(context, limit), "top artists",
            ) { a -> JSONObject().put("name", a.name) }
            BrowseSource.TOP_TRACKS -> libraryOutcomeToJson(
                context, SpotifyWebApi.getTopTracks(context, limit), "top tracks",
            ) { t -> JSONObject().put("name", t.name).put("artist", t.artist) }
            BrowseSource.LEGION_HISTORY -> browseLegionHistory(context, limit)
            BrowseSource.ARTIST_ALBUMS -> browseArtistAlbums(context, limit)
        }
    }

    /**
     * `playlists` source of [browseMyMusic] (music-suggestions-1124 P7): the capability
     * ([SpotifyWebApi.myPlaylists]) already existed and served the by-name play and add-to-playlist
     * paths, but nothing could LIST playlists, so "find the playlist called X" and "what playlists
     * do I have" had no read to land on and Alfred answered them off the saved-albums page.
     *
     * Every row, never a page - see the call site's comment. [spoken], when the model passed one,
     * also runs the same fuzzy match the by-name play path uses, and the closest name rides on the
     * result as `match`; a miss says so in words beside the full list, because "no playlist by
     * that name" and "here is everything you have" are different answers and he is owed both.
     *
     * One read: the match runs against the rows this call already has, rather than through
     * [SpotifyWebApi.findMyPlaylist], which would read the cache a second time.
     */
    private suspend fun browsePlaylists(context: Context, spoken: String?): JSONObject {
        val outcome = SpotifyWebApi.myPlaylists(context)
        val json = libraryOutcomeToJson(context, outcome, "playlists", ::playlistRow)
        if (outcome !is SpotifyWebApi.LibraryOutcome.Found || spoken == null) return json
        return withPlaylistMatch(json, outcome.items, spoken)
    }

    /**
     * The successful shape of every library read: the rows, how many are IN THIS PAGE (`count`),
     * and - only when Spotify's own response stated one - how many exist (`total`) with whether
     * the page fell short of it (`truncated`).
     *
     * `count` on its own could not tell a five-row page from a five-row library, so the page size
     * was spoken as the library size (music-suggestions-1124 P8). A source whose response states
     * no total emits NEITHER field, because `truncated: false` would stand in for "that is all of
     * them" on exactly the reads that cannot know it.
     *
     * An empty row list is a REAL, distinct answer ("Spotify said zero"), never a failure shape
     * and never phrased as if nothing was checked. Pure and `internal`: a plain JVM test.
     *
     * A total SMALLER than the page in hand is dropped, not published. Spotify's cursor-paged
     * bodies carry a `total` that does not count what the cursor returned, so a 0 beside five rows
     * would reach the model as `total: 0, truncated: false` - a stated library figure, and a claim
     * that this is all of it, both contradicted by the rows underneath. With no publishable total
     * the result states none, which the rule already tells the model to read as "I cannot say how
     * many he has".
     */
    internal fun pageResult(rows: List<JSONObject>, total: Int?, sourceLabel: String): JSONObject {
        val items = JSONArray()
        rows.forEach { items.put(it) }
        val message = if (rows.isEmpty()) "Spotify has no $sourceLabel to show right now." else null
        return result(success = true, message = message)
            .put("items", items)
            .put("count", rows.size)
            .apply {
                total?.takeIf { it >= rows.size }?.let {
                    put("total", it)
                    put("truncated", rows.size < it)
                }
            }
    }

    /** One playlist row: named, playable by URI, and honest about whether he can add to it. */
    internal fun playlistRow(p: SpotifyPlaylist): JSONObject =
        JSONObject().put("name", p.name).put("spotifyUri", p.uri).put("ownOrCollaborative", p.readable)

    /**
     * Adds the closest name to [spoken] to a successful playlists result, or a note saying nothing
     * scored close enough. Pure and `internal` so the exact wording is a plain JVM test rather
     * than something only a real turn could check.
     */
    internal fun withPlaylistMatch(
        json: JSONObject,
        playlists: List<SpotifyPlaylist>,
        spoken: String,
    ): JSONObject {
        val matched = SpotifyWebApi.bestPlaylistMatch(playlists, spoken)
        return if (matched != null) {
            json.put("match", matched.name)
        } else {
            json.put(
                "matchNote",
                "No playlist of his scored close enough to \"$spoken\" to call it a match. Say that, " +
                    "and that the names in items are what he does have - never that he has no playlists.",
            )
        }
    }

    /**
     * Turns a [SpotifyWebApi.LibraryOutcome] into the tool-result shape, with every failure
     * named for what it actually is - never a bare "couldn't get that". [sourceLabel] is plain
     * English ("saved albums", "top tracks", ...) folded into the message so the driver hears
     * WHICH request failed, not a generic Spotify error.
     *
     * The [SpotifyWebApi.LibraryOutcome.Unauthorized] branch specifically calls out
     * [SpotifyWebApi.hasStaleGrant] - this is the exact trap ticket 05 was filed to catch: the
     * SCOPES widening that made `browse_my_music` possible at all invalidates every existing
     * grant (see [SpotifyWebApi.SCOPES]'s own doc), so the very first time most drivers call
     * this tool it is expected to fail on a stale grant, and the message has to say that in
     * words rather than read as a mysterious rejection.
     */
    private fun <T> libraryOutcomeToJson(
        context: Context,
        outcome: SpotifyWebApi.LibraryOutcome<T>,
        sourceLabel: String,
        toJson: (T) -> JSONObject,
    ): JSONObject = when (outcome) {
        is SpotifyWebApi.LibraryOutcome.Found ->
            pageResult(outcome.items.map(toJson), outcome.total, sourceLabel)
        SpotifyWebApi.LibraryOutcome.NeedsAuthorization -> result(
            success = false,
            message = "Spotify hasn't been authorized on this device yet - open Setup, Spotify, " +
                "and tap AUTHORIZE. Then I can look up $sourceLabel.",
        )
        is SpotifyWebApi.LibraryOutcome.Unauthorized -> {
            // See this function's own doc: distinguishing "never connected" from "connected,
            // but the grant is now stale" is the whole point of this branch.
            val stale = SpotifyWebApi.hasStaleGrant(context)
            result(
                success = false,
                message = if (stale) {
                    "Spotify rejected that - your approval predates $sourceLabel access. Open " +
                        "Setup, Spotify, and tap AUTHORIZE again to re-approve; it only takes a " +
                        "few seconds."
                } else {
                    "Spotify rejected the request for $sourceLabel" +
                        (outcome.detail?.let { ": $it" } ?: ".") +
                        " Run the search test in Setup, Spotify for the details."
                },
            )
        }
        SpotifyWebApi.LibraryOutcome.Unreachable -> result(
            success = false,
            message = "Could not reach Spotify for $sourceLabel. Try again " +
                "on a better connection.",
        )
        is SpotifyWebApi.LibraryOutcome.Failed -> result(
            success = false,
            message = "Spotify's $sourceLabel request returned an error (${outcome.code})" +
                (outcome.detail?.let { ": $it" } ?: "."),
        )
    }

    /**
     * `artist_albums` source of [browseMyMusic] (ticket 13, scope item 2): "what else does he
     * have" - the CURRENT track's artist, read via [SpotifyController.currentArtist] (never a
     * guess - see its own doc), then [SpotifyWebApi.getArtistAlbums]. Every returned item carries
     * its own `spotifyUri` (ticket 13 scope item 3): `play_music` can be handed one directly to
     * play "his album X" resolved within THIS artist's own catalogue, never through open search.
     */
    private suspend fun browseArtistAlbums(context: Context, limit: Int): JSONObject {
        val artist = SpotifyController.currentArtist() ?: return result(
            success = false,
            message = "I can't tell who's playing right now, so I don't know whose albums to " +
                "look up - play something on Spotify first, then ask again.",
        )
        val outcome = SpotifyWebApi.getArtistAlbums(context, artist.uri, limit)
        return libraryOutcomeToJson(context, outcome, "${artist.name}'s albums") { a ->
            JSONObject().put("name", a.name).put("spotifyUri", a.uri)
        }
    }

    /**
     * `legion_history` source of [browseMyMusic] - LEGION's OWN observed-listening log
     * ([com.kevin.legion.data.local.MusicPlayHistoryEntry]), never Spotify's. Every path out of
     * this function says so in words, including the empty-table case: "nothing recorded yet" is
     * a different fact from "you have no music", and collapsing them is exactly the false-empty
     * failure mode CLAUDE.md §4 rule 5's data-anchoring thesis exists to prevent at the data
     * layer, carried here to the tool layer.
     */
    private suspend fun browseLegionHistory(context: Context, limit: Int): JSONObject {
        val dao = CarDatabase.getDatabase(context).musicPlayHistoryDao()
        val recent = dao.getRecent(limit)
        if (recent.isEmpty()) {
            return result(
                success = true,
                message = "LEGION hasn't observed anything playing yet on this device - this " +
                    "fills in as music is actually played while LEGION is running, it isn't " +
                    "Spotify's own history.",
            ).put("items", JSONArray())
        }

        val items = JSONArray()
        recent.forEach { e ->
            items.put(
                JSONObject()
                    .put("title", e.title)
                    .put("artist", e.artist)
                    .put("startedByLegion", e.startedByLegion)
                    // Ticket 09 (`.scratch/spotify-voice/issues/09-history-uri.md`): a row logged
                    // before App Remote's player state was wired in (or one observed off a
                    // non-Spotify session) carries no URI. `replayable` is handed over pre-computed
                    // rather than left for the model to derive from a JSON null, so there is no
                    // path where a null spotifyUri quietly reads as "sure, play it" - see
                    // play_music's spotifyUri param and this tool's own description for how the
                    // model is told to act on it.
                    .put("spotifyUri", e.spotifyUri ?: JSONObject.NULL)
                    .put("replayable", e.spotifyUri != null),
            )
        }

        // The "favourite" inference, labelled as LEGION's own the moment it's computed, not left
        // to the model to caveat later - see this function's own doc and MusicPlayHistoryDao's.
        val top = dao.getMostPlayed(1).firstOrNull()
        val favouriteNote = top?.let {
            "By LEGION's own count on this device (not a Spotify figure), the track it's seen " +
                "played most is \"${it.title}\" by ${it.artist}, ${it.playCount} time(s)."
        }

        return result(
            success = true,
            message = "This is LEGION's OWN observed-listening log on this device, not Spotify's " +
                "history." + (favouriteNote?.let { " $it" } ?: "") + (replayabilityNote(recent)?.let { " $it" } ?: ""),
        ).put("items", items)
    }

    /**
     * ADR 0031's outcome-asserting-vocabulary clause applied to a READ path (ticket 09, scope
     * item 4): a null-URI row can be NAMED but never REPLAYED, and that has to be said in words
     * to the model reading this result, not left for it to infer from a JSON `null`. Returns null
     * (no note appended) only when every row in [recent] already carries a URI, so the note never
     * shows up as noise on a history that has nothing to caveat.
     *
     * Pure and `internal` so the exact wording is a plain JVM unit test rather than something
     * only checkable by reading a live tool result off a real turn.
     */
    internal fun replayabilityNote(recent: List<MusicPlayHistoryEntry>): String? {
        if (recent.none { it.spotifyUri == null }) return null
        return "Rows with spotifyUri null were never observed with a resolvable Spotify URI - " +
            "you can tell the user what they were, but say plainly you can't play them again " +
            "from here. Never search for a title to invent a URI for one; a guess that plays the " +
            "wrong song is worse than an honest no."
    }
}
