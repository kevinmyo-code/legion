package com.kevin.legion.games.race

import com.kevin.legion.backend.engine.engineRefusalSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The phone's parsers against bodies shaped exactly as `server/games/views.py` emits them
 * (`MatchSerializer`, `PlayerSerializer`, `InviteResponseSerializer`, `OpponentSerializer`; DRF
 * renders datetimes as ISO 8601 with a `Z`). The first version of the backend guessed field names
 * and read the invite's match id from the top level, so a successful invite was never followed.
 * If the engine renames a field, this fails the build instead of a race failing on a phone.
 */
class DjangoGamesBackendContractTest {
    private val player = { id: String, name: String, score: Int ->
        """{"user_id": "$id", "name": "$name", "score": $score, "lines": 3, "level": 2,
            "topped_out": false, "finished": false, "updated_at": "2026-10-19T12:00:05.123456Z"}"""
    }

    /** A `MatchSerializer` body for the invitee's view of an invited match. */
    private fun match(status: String, startAt: String?, endsAt: String?, result: String?, detail: String) = """
        {"id": "5d1c6c3a-0a54-4c40-9a3b-0c1b7e9f2a11", "game": "tetris", "status": "$status",
         "seed": 2891336453, "role": "inviter", "created_at": "2026-10-19T12:00:00.000001Z",
         "expires_at": "2026-10-19T12:02:00.000001Z",
         "start_at": ${startAt?.let { "\"$it\"" }}, "ends_at": ${endsAt?.let { "\"$it\"" }},
         "server_now": "2026-10-19T12:00:10.500000Z",
         "you": ${player("11111111-1111-1111-1111-111111111111", "Kevin", 120)},
         "opponent": ${player("22222222-2222-2222-2222-222222222222", "Mia", 80)},
         "result": ${result?.let { "\"$it\"" }}, "detail": "$detail"}
    """.trimIndent()

    @Test
    fun `invite unwraps the match and carries the engine's notification sentence`() {
        val sentence = "Invited Mia. No notification was sent: they have no device signed up for them. " +
            "The invite still shows in Mia's Games for 2 minutes."
        val body = """{"match": ${match("invited", null, null, null, "Waiting for Mia to accept.")},
            "push": "no_device", "detail": "$sentence"}"""
        val sent = parseInvite(body)
        assertEquals("5d1c6c3a-0a54-4c40-9a3b-0c1b7e9f2a11", sent.matchId)
        assertEquals(sentence, sent.note)
    }

    @Test
    fun `an invite reply with the match at the top level is refused, not guessed at`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseInvite(match("invited", null, null, null, "x"))
        }
    }

    @Test
    fun `an invited match`() {
        val m = parseMatch(match("invited", null, null, null, "Waiting for Mia to accept."))
        assertEquals(MatchStatus.INVITED, m.status)
        assertEquals(2891336453L, m.seed)
        assertEquals("Mia", m.opponentName)
        assertNull(m.startAtMs)
        assertNull(m.endsAtMs)
        assertNull(m.result)
        assertEquals(120, m.me.score)
        assertEquals(80, m.opponent.score)
        assertEquals(1_792_411_210_500L, m.serverNowMs)
    }

    @Test
    fun `a playing match has its start and end, and an ended one its result`() {
        val playing = parseMatch(
            match("playing", "2026-10-19T12:00:15.000000Z", "2026-10-19T12:03:15.000000Z", null, "Racing Mia."),
        )
        assertEquals(MatchStatus.PLAYING, playing.status)
        assertEquals(180_000L, playing.endsAtMs!! - playing.startAtMs!!)
        val ended = parseMatch(
            match("ended", "2026-10-19T12:00:15Z", "2026-10-19T12:03:15Z", "won", "You won, 120 to 80."),
        )
        assertEquals(RaceResult.WON, ended.result)
        assertEquals(MatchStatus.ENDED, ended.status)
    }

    @Test
    fun `an unknown status is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseMatch(match("paused", null, null, null, "x"))
        }
    }

    @Test
    fun `pending is a bare list of matches and the inviter is the opponent block`() {
        val body = "[" + match("invited", null, null, null, "Mia invited you to a Tetris race.") + "]"
        assertEquals(
            listOf(PendingInvite("5d1c6c3a-0a54-4c40-9a3b-0c1b7e9f2a11", "Mia")),
            parsePending(body),
        )
        assertEquals(emptyList<PendingInvite>(), parsePending("[]"))
    }

    @Test
    fun `opponents are user_id and name`() {
        val body = """[{"user_id": "22222222-2222-2222-2222-222222222222", "name": "Mia"}]"""
        assertEquals(listOf(Opponent("22222222-2222-2222-2222-222222222222", "Mia")), parseOpponents(body))
    }

    @Test
    fun `a refusal body is shown as the engine's own sentence`() {
        val body = """{"detail": "Nothing was accepted. Mia cancelled the invite. Nothing was played."}"""
        assertEquals(
            "Nothing was accepted. Mia cancelled the invite. Nothing was played.",
            engineRefusalSentence(body),
        )
    }
}
