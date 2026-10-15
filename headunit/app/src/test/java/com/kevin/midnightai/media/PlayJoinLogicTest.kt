package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayJoinLogicTest {

    @Test fun `case, spacing and punctuation do not make a different request`() {
        assertEquals(
            PlayJoinLogic.key("Mask Off", "song", "Future", null),
            PlayJoinLogic.key("  mask   off! ", "song", "future", null),
        )
    }

    @Test fun `a different artist, type or uri is a different request`() {
        val base = PlayJoinLogic.key("Mask Off", "song", "Future", null)
        assertNotEquals(base, PlayJoinLogic.key("Mask Off", "song", "Someone Else", null))
        assertNotEquals(base, PlayJoinLogic.key("Mask Off", "album", "Future", null))
        assertNotEquals(base, PlayJoinLogic.key("Mask Off", "song", "Future", "spotify:album:abc"))
    }

    @Test fun `a missing artist and a blank artist are the same request`() {
        assertEquals(PlayJoinLogic.key("Chill", "playlist", null, null), PlayJoinLogic.key("Chill", "playlist", "  ", null))
    }
}
