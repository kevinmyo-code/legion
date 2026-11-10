package com.kevin.legion.brief

import com.kevin.legion.sitrep.SitrepModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0085: the proposed to-dos line rides the whole brief (the tapped `get_sitrep` and the wake-up
 * brief, which both read [BriefController.forVoice]) and the Brief modal's phone sections, never a
 * filtered brief. A brief with no source wired says nothing about it.
 */
class BriefOpenLoopsTest {

    private class Phone : BriefPhone {
        override suspend fun sections(filter: Set<SitrepModule>?) = listOf(PhoneSection("Calendar", "Nothing today."))

        override suspend fun newslettersEnabled() = false

        override suspend fun newsletters() = ""
    }

    private class Backend : BriefBackend {
        override suspend fun latest(): Result<BriefReply> = Result.failure(IllegalStateException("offline"))

        override suspend fun refresh(): Result<BriefReply.Got> = Result.failure(IllegalStateException("offline"))
    }

    private class Cache : BriefCache {
        override fun read(): CachedBrief? = null

        override fun write(rawServerBody: String, savedAtMs: Long) = Unit
    }

    private val asked = mutableListOf<String>()
    private val lines = object : BriefController.OpenLoopsLines {
        override suspend fun voiceLine(): String {
            asked += "voice"
            return "PROPOSED TO-DOS: \"Call the dentist\"."
        }

        override suspend fun screenLine(): String {
            asked += "screen"
            return "Call the dentist."
        }
    }

    private fun controller(withLoops: Boolean) =
        BriefController({ Backend() }, Cache(), Phone(), openLoops = if (withLoops) lines else null)

    @Test
    fun `the whole spoken brief carries the line, a filtered one never does`() = runBlocking {
        val whole = controller(true).forVoice(null, readMail = false).text
        assertTrue(whole.contains("PROPOSED TO-DOS: \"Call the dentist\"."))
        val filtered = controller(true).forVoice(setOf(SitrepModule.CALENDAR), readMail = false).text
        assertFalse(filtered.contains("PROPOSED"))
        assertEquals(listOf("voice"), asked)
    }

    @Test
    fun `the modal shows it as its own section, and no source means no section`() = runBlocking {
        val sections = controller(true).phoneSections()
        assertEquals(PhoneSection("Proposed to-dos", "Call the dentist."), sections.last())
        assertEquals(1, controller(false).phoneSections().size)
        assertFalse(controller(false).forVoice(null, readMail = false).text.contains("PROPOSED"))
    }
}
