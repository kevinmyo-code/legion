package com.kevin.legion.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests for [SpotifyMatch]: the 2026-10-01 wrong-song cases and the rules behind them. */
class SpotifyMatchTest {

    private fun track(name: String, artist: String, uri: String, popularity: Int = 50, vararg more: String) =
        JSONObject()
            .put("name", name)
            .put("uri", uri)
            .put("popularity", popularity)
            .put("artists", JSONArray((listOf(artist) + more).map { JSONObject().put("name", it) }))

    private val wrongHits = listOf(
        track("Keep Ya Head Up", "2Pac", "spotify:track:1", 90),
        track("Alabama Haint", "Penny & Sparrow", "spotify:track:2", 40),
        track("Breathe", "Fabolous", "spotify:track:3", 70),
    )
    private val real = track("FUKK A INTERVIEW", "Future", "spotify:track:real", 60)

    private val queries = listOf(
        "Fukk a Interview" to "Future", // explicit artist param
        "Interview by Future" to null,
        "F*kk An Interview by Future" to null,
    )

    @Test
    fun `unrelated hits are rejected for all three real queries`() {
        for ((q, a) in queries) assertNull("$q / $a", SpotifyMatch.pick(wrongHits, q, a))
    }

    @Test
    fun `a real FUKK A INTERVIEW by Future is accepted for all three spellings, even among wrong hits`() {
        for ((q, a) in queries) {
            val picked = SpotifyMatch.pick(wrongHits + real, q, a)
            assertEquals("$q / $a", "spotify:track:real", picked?.optString("uri"))
        }
    }

    @Test
    fun `censored spellings compare equal but near words do not`() {
        for (w in listOf("fukk", "f*kk", "f**k", "fuck", "F*ck")) assertTrue(w, SpotifyMatch.tokensMatch(SpotifyMatch.normalize("fuck"), SpotifyMatch.normalize(w)))
        assertFalse(SpotifyMatch.tokensMatch("bike", "bake"))
        assertFalse(SpotifyMatch.tokensMatch("fuck", "fork"))
    }

    @Test
    fun `X by Y splits on the last by`() {
        assertEquals(SpotifyMatch.Wanted("Stand by Me", "Ben E. King"), SpotifyMatch.splitByArtist("Stand by Me by Ben E. King"))
        assertNull(SpotifyMatch.splitByArtist("Mask Off"))
        assertNull(SpotifyMatch.splitByArtist("by Future"))
    }

    @Test
    fun `a title containing by still matches when read whole`() {
        val c = track("Stand By Me", "Ben E. King", "spotify:track:sbm")
        assertEquals("spotify:track:sbm", SpotifyMatch.pick(listOf(c), "Stand by Me", null)?.optString("uri"))
    }

    @Test
    fun `feat, parenthetical and dash suffixes are stripped from candidate titles`() {
        assertEquals("mask off", SpotifyMatch.normalizeCandidateTitle("Mask Off (feat. Kendrick)"))
        assertEquals("mask off", SpotifyMatch.normalizeCandidateTitle("Mask Off - Remastered 2019"))
        assertEquals("mask off", SpotifyMatch.normalizeCandidateTitle("Mask Off feat. Someone"))
        assertEquals("mask off", SpotifyMatch.normalizeCandidateTitle("Mask Off [Live]"))
        assertTrue(SpotifyMatch.titleMatches("Mask Off - Remastered", "mask off"))
    }

    @Test
    fun `title thresholds`() {
        assertTrue(SpotifyMatch.titleMatches("Mask Off", "mask off future")) // contained, 2 tokens
        assertFalse(SpotifyMatch.titleMatches("Keep Ya Head Up", "up")) // lone word, no artist
        assertTrue(SpotifyMatch.titleMatches("Keep Ya Head Up", "up", artistConfirmed = true))
        assertTrue(SpotifyMatch.titleMatches("Fukk a Interview", "f*kk an interview")) // 2 of 3 tokens
        assertFalse(SpotifyMatch.titleMatches("Alabama Haint", "interview"))
    }

    @Test
    fun `artist must match when given`() {
        val c = track("Mask Off", "Someone Else", "spotify:track:x")
        assertNull(SpotifyMatch.pick(listOf(c), "Mask Off", "Future"))
        val f = track("Mask Off", "Someone Else", "spotify:track:y", 50, "Future")
        assertEquals("spotify:track:y", SpotifyMatch.pick(listOf(f), "Mask Off", "future")?.optString("uri"))
    }

    @Test
    fun `artist match is whole-word, never a substring`() {
        assertFalse(SpotifyMatch.artistMatches(listOf("Yeat"), "Ye"))
        assertTrue(SpotifyMatch.artistMatches(listOf("Ye"), "ye"))
        assertTrue(SpotifyMatch.artistMatches(listOf("Mariya Takeuchi"), "Takeuchi"))
        assertTrue(SpotifyMatch.artistMatches(listOf("Tyler, The Creator"), "Tyler the Creator"))
    }

    @Test
    fun `fielded query is built for track and album`() {
        assertEquals("track:\"Mask Off\" artist:\"Future\"", SpotifyMatch.fieldedQuery("track", "Mask Off", "Future"))
        assertEquals("album:\"Discovery\" artist:\"Daft Punk\"", SpotifyMatch.fieldedQuery("album", "Discovery", "Daft Punk"))
        assertEquals("track:\"a b\" artist:\"c\"", SpotifyMatch.fieldedQuery("track", "a \"b\"", "c"))
    }

    @Test
    fun `an explicit artist trims a trailing by artist left in the title`() {
        assertEquals(listOf(SpotifyMatch.Wanted("Mask Off", "Future")), SpotifyMatch.readings("Mask Off by Future", "Future"))
    }

    @Test
    fun `relevance order is kept over popularity unless the first accepted is an exact title match`() {
        val first = track("Mask Off (Live)", "Future", "spotify:track:first", 10)
        val second = track("Mask Off Remix Extended Edit", "Future", "spotify:track:second", 99)
        // first is not exact? "Mask Off (Live)" normalises to "mask off" which IS exact; use a non-exact first.
        val nonExact = track("Mask Off Part Two", "Future", "spotify:track:first", 10)
        assertEquals("spotify:track:first", SpotifyMatch.pick(listOf(nonExact, second), "Mask Off", "Future")?.optString("uri"))
        // exact tie: popularity decides among exact matches
        val exactLow = track("Mask Off", "Future", "spotify:track:low", 10)
        val exactHigh = track("Mask Off", "Future", "spotify:track:high", 80)
        assertEquals("spotify:track:high", SpotifyMatch.pick(listOf(exactLow, exactHigh), "Mask Off", "Future")?.optString("uri"))
        assertEquals("spotify:track:low", SpotifyMatch.pick(listOf(exactLow, track("Mask Off", "Future", "spotify:track:t", 10)), "Mask Off", "Future")?.optString("uri"))
        assertEquals("spotify:track:first", SpotifyMatch.pick(listOf(first), "Mask Off", "Future")?.optString("uri"))
    }

    @Test
    fun `imposters are filtered, but honoured when they are all there is`() {
        val karaoke = track("Mask Off", "Karaoke Hits", "spotify:track:k", 5)
        val orig = track("Mask Off", "Future", "spotify:track:o", 5)
        val isImp = { c: JSONObject -> c.optJSONArray("artists")!!.getJSONObject(0).optString("name").contains("Karaoke") }
        assertEquals("spotify:track:o", SpotifyMatch.pick(listOf(karaoke, orig), "Mask Off", null, isImp)?.optString("uri"))
        assertEquals("spotify:track:k", SpotifyMatch.pick(listOf(karaoke), "Mask Off", null, isImp)?.optString("uri"))
    }
}
