package com.kevin.midnightai.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The supersede decision behind a late play_music: a newer choice always wins. */
class PlayRequestsTest {

    private fun token(gen: Long, start: Source) = PlayRequests.Token(gen, start)

    @Test fun latestRequestWithUnchangedSourceProceeds() {
        assertTrue(PlayRequests.shouldProceed(token(5, Source.PHONE), 5, Source.PHONE))
    }

    @Test fun aNewerRequestSupersedes() {
        assertFalse(PlayRequests.shouldProceed(token(5, Source.PHONE), 6, Source.PHONE))
    }

    @Test fun switchingToAnotherSourceSupersedes() {
        assertFalse(PlayRequests.shouldProceed(token(5, Source.PHONE), 5, Source.MIXTAPE))
        assertFalse(PlayRequests.shouldProceed(token(5, Source.SPOTIFY), 5, Source.PHONE))
        assertFalse(PlayRequests.shouldProceed(token(5, Source.MIXTAPE), 5, Source.PHONE))
    }

    @Test fun connectFlippingTheSourceToSpotifyIsNotAChangeOfMind() {
        assertTrue(PlayRequests.shouldProceed(token(5, Source.PHONE), 5, Source.SPOTIFY))
        assertTrue(PlayRequests.shouldProceed(token(5, Source.MIXTAPE), 5, Source.SPOTIFY))
    }

    @Test fun beginAndSupersedeAdvanceTheGeneration() {
        val a = PlayRequests.begin(Source.PHONE)
        val b = PlayRequests.begin(Source.PHONE)
        assertNotEquals(a.generation, b.generation)
        assertFalse(PlayRequests.shouldProceed(a, b.generation, Source.PHONE))
        PlayRequests.supersede()
        val c = PlayRequests.begin(Source.PHONE)
        assertTrue(c.generation > b.generation + 1)
    }
}
