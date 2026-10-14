package com.kevin.midnightai.service

import com.kevin.midnightai.media.SpotifyVoiceLogic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The music tools' advertised schema must match what the code can actually do - a declared action
 * with no implementation is a lying tool description - and the whole declarations payload must stay
 * a sane size, because it rides inside the Live setup message on every session.
 */
class LiveToolboxMusicDeclarationsTest {

    private fun decl(name: String): JSONObject {
        val all: JSONArray = LiveToolbox.declarations()
        return (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.getString("name") == name }
            ?: error("no declaration named $name")
    }

    private fun enumOf(fn: JSONObject, param: String): List<String> {
        val arr = fn.getJSONObject("parameters").getJSONObject("properties").getJSONObject(param).getJSONArray("enum")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Test fun `control_music offers exactly the actions the code implements`() {
        val offered = enumOf(decl("control_music"), "action")
        assertEquals(SpotifyVoiceLogic.WIRE_VALUES, offered)
        offered.forEach { assertNotNull("$it has no MusicAction", SpotifyVoiceLogic.MusicAction.fromWire(it)) }
    }

    @Test fun `play_music offers the four kinds and a spotifyUri escape hatch`() {
        val play = decl("play_music")
        assertEquals(listOf("song", "artist", "album", "playlist"), enumOf(play, "type"))
        val props = play.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.has("artist"))
        assertTrue(props.has("spotifyUri"))
        assertEquals("query", play.getJSONObject("parameters").getJSONArray("required").getString(0))
    }

    @Test fun `browse_my_music offers the five sources and no legion_history`() {
        val offered = enumOf(decl("browse_my_music"), "source")
        assertEquals(listOf("saved_albums", "recently_played", "top_artists", "top_tracks", "artist_albums"), offered)
        // music_plays has no uri/replayable column, so a replayable history source would be a lie.
        assertTrue("legion_history" !in offered)
    }

    @Test fun `get_music_queue is declared with no required arguments`() {
        assertEquals(0, decl("get_music_queue").getJSONObject("parameters").getJSONArray("required").length())
    }

    @Test fun `every tool name is declared once`() {
        val all = LiveToolbox.declarations()
        val names = (0 until all.length()).map { all.getJSONObject(it).getString("name") }
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun `the declarations payload stays under the setup ceiling`() {
        val size = LiveToolbox.declarations().toString().length
        // Not a Live API limit we have measured: a tripwire so the payload cannot grow unnoticed.
        // LEGION raised its own ceiling when its tool set grew; this is the equivalent guard here.
        println("LiveToolbox.declarations() JSON length: $size chars")
        assertTrue("declarations grew to $size chars", size < 60_000)
    }
}
