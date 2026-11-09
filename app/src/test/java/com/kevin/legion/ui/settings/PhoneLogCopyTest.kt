package com.kevin.legion.ui.settings

import com.kevin.legion.service.phonelog.PhoneLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Developer row's copy (2026-11-09): what is sent, what is removed first, no assistant name. */
class PhoneLogCopyTest {
    private val all = listOf(
        PhoneLogCopy.HEADER, PhoneLogCopy.TITLE, PhoneLogCopy.DETAIL, PhoneLogCopy.OFF_DETAIL,
        PhoneLogCopy.SEND_NOW, PhoneLogCopy.SENDING,
    )

    @Test fun `says what is sent, what is removed first, and the limits`() {
        val s = PhoneLogCopy.DETAIL
        assertTrue(s.contains("crashes"))
        assertTrue(s.contains("engine"))
        for (removed in listOf("Passwords", "keys", "email addresses", "phone numbers", "mail")) assertTrue(removed, s.contains(removed))
        assertTrue(s.contains("${PhoneLog.DAILY_CAP_BYTES / (1024 * 1024)} MB a day"))
        assertTrue(s.contains("30 days"))
    }

    @Test fun `never names the assistant and carries no em dash`() {
        for (text in all) {
            for (name in listOf("Alfred", "Aria", "Jarvis", "JARVIS", "Gemini")) assertFalse(text, text.contains(name))
            assertFalse(text, text.contains('—'))
        }
    }
}
