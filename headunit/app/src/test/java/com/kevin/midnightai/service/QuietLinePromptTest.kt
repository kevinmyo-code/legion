package com.kevin.midnightai.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietLinePromptTest {

    @Test fun `with memories they are quoted and nothing else may be claimed`() {
        val p = QuietLinePrompt.build(listOf("(March 3, 2026) likes the XJ's cold starts", "(May 1, 2026) hates highway merges"))
        assertTrue(p.contains("\"(March 3, 2026) likes the XJ's cold starts\""))
        assertTrue(p.contains("\"(May 1, 2026) hates highway merges\""))
        assertTrue(p.contains("Do not claim to remember anything else"))
    }

    @Test fun `with no memories the subject is forbidden in words`() {
        val p = QuietLinePrompt.build(emptyList())
        assertTrue(p.contains("do not claim to remember anything"))
        assertFalse(p.contains("something you remember"))
    }

    @Test fun `blank memories count as none and the list is capped`() {
        assertTrue(QuietLinePrompt.build(listOf("  ", "")).contains("no stored memory"))
        val p = QuietLinePrompt.build(listOf("one", "two", "three"))
        assertFalse(p.contains("three"))
    }

    @Test fun `the prompt never refers to the driver's absence or engagement`() {
        for (p in listOf(QuietLinePrompt.build(emptyList()), QuietLinePrompt.build(listOf("a fact")))) {
            assertFalse(p.contains("missed"))
            assertFalse(p.contains("haven't"))
            assertFalse(p.contains("since we last"))
        }
    }
}
