package com.kevin.legion.service.tools.music

import com.kevin.legion.service.tools.LiveSurface
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merged `control_music` declaration may not promise a listening source the dispatch has no
 * branch for (`music-suggestions-1124` P7).
 *
 * The bug this pins: the action hint said "his playlists, liked songs or recent plays (source)"
 * while the `source` enum carried `saved_albums, recently_played, top_artists, top_tracks,
 * legion_history, artist_albums` - and the merge drops each target's own description, so the hint
 * and the enum were the whole of what the model saw. It picked the nearest value it had, read
 * saved albums, and then stated a fact about playlists it had never looked at.
 *
 * Plain JVM: `org.json` is a test dependency and the merge is pure JSON assembly.
 */
class BrowseSourceDeclarationTest {

    private fun mergedControlMusic(): JSONObject {
        val decls = Declarations.declarations().associateBy { it.getString("name") }
        val tool = LiveSurface.ACTION_TOOLS.first { it.name == "control_music" }
        return LiveSurface.declaration(tool, decls)
    }

    private fun sourceEnum(decl: JSONObject): List<String> {
        val src = decl.getJSONObject("parameters").getJSONObject("properties").getJSONObject("source")
        val enum = src.getJSONArray("enum")
        return (0 until enum.length()).map { enum.getString(it) }
    }

    @Test
    fun `the source enum the model sees carries playlists and liked songs`() {
        val sources = sourceEnum(mergedControlMusic())
        assertTrue("the hint promises playlists; the enum must offer them", "playlists" in sources)
        assertTrue("the hint promises liked songs; the enum must offer them", "liked_songs" in sources)
    }

    @Test
    fun `the declared enum is exactly the dispatch's own source list`() {
        // One list, so a value cannot be declared without its branch: BrowseSource drives both the
        // enum here and the `when` in MusicLibraryToolbox, which the compiler keeps exhaustive.
        assertEquals(BrowseSource.WIRE_VALUES, sourceEnum(mergedControlMusic()))
        for (wire in sourceEnum(mergedControlMusic())) {
            assertNotNull("$wire is declared but parses to no source", BrowseSource.fromWire(wire))
        }
    }

    @Test
    fun `an unknown source still parses to null rather than throwing`() {
        assertEquals(null, BrowseSource.fromWire("mixtapes"))
        assertEquals(null, BrowseSource.fromWire(""))
    }

    @Test
    fun `the browse result carries the page-versus-library rule`() {
        // Billed on the result, not in the declaration: the rule only matters once a count exists.
        val rule = LiveSurface.RESULT_RULES.getValue("browse_my_music")
        assertTrue("the rule must name `total` as the only library figure", rule.contains("total"))
        assertTrue("and `count` as the page", rule.contains("count"))
        assertEquals(rule, LiveSurface.withRule("browse_my_music", JSONObject().put("success", true)).getString("rule"))
    }

    @Test
    fun `a named playlist lookup has a parameter to arrive in`() {
        // browse_my_music is only reachable through control_music, so the name rides on the
        // merged tool's own playlistName, which must therefore not be scoped to add_to_playlist.
        val props = mergedControlMusic().getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.has("playlistName"))
        assertTrue(
            "playlistName's description must not read as add_to_playlist's alone",
            !props.getJSONObject("playlistName").optString("description").contains("add_to_playlist"),
        )
    }
}
