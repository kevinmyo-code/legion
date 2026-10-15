package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckResumeLogicTest {

    @Test fun `unchanged speakers get the resume nudge`() {
        assertTrue(DuckResumeLogic.shouldNudge(true, Source.PHONE, Source.PHONE, 4L, 4L))
        assertNull(DuckResumeLogic.skipReason(true, Source.PHONE, Source.PHONE, 4L, 4L))
    }

    @Test fun `nothing playing before the turn means no nudge`() {
        assertFalse(DuckResumeLogic.shouldNudge(false, Source.PHONE, Source.PHONE, 4L, 4L))
        assertNotNull(DuckResumeLogic.skipReason(false, Source.PHONE, Source.PHONE, 4L, 4L))
    }

    @Test fun `a source switch during the turn cancels the nudge`() {
        // Phone was playing when the turn started; a tool moved the speakers to Spotify.
        assertFalse(DuckResumeLogic.shouldNudge(true, Source.PHONE, Source.SPOTIFY, 4L, 4L))
        assertEquals("source changed PHONE -> SPOTIFY", DuckResumeLogic.skipReason(true, Source.PHONE, Source.SPOTIFY, 4L, 4L))
    }

    @Test fun `a newer play request during the turn cancels the nudge`() {
        assertFalse(DuckResumeLogic.shouldNudge(true, Source.SPOTIFY, Source.SPOTIFY, 4L, 5L))
        assertNotNull(DuckResumeLogic.skipReason(true, Source.SPOTIFY, Source.SPOTIFY, 4L, 5L))
    }
}
