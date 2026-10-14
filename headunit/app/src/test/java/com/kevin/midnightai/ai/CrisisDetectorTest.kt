package com.kevin.midnightai.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [CrisisDetector] (CLAUDE.md sec 9.1 crisis path).
 *
 * The car-idiom cases are the point of this file. The detector is a backstop that
 * drops the character and reads a crisis line, so a false positive is loud and
 * costly - these tests pin down that ordinary car talk never triggers it.
 */
class CrisisDetectorTest {

    // --- fires on unambiguous distress -------------------------------------

    @Test
    fun `detects direct self-harm statements`() {
        assertTrue(CrisisDetector.detect("i want to kill myself"))
        assertTrue(CrisisDetector.detect("I'm going to end my life"))
        assertTrue(CrisisDetector.detect("i just want to die"))
        assertTrue(CrisisDetector.detect("everyone would be better off without me"))
        assertTrue(CrisisDetector.detect("there's no reason to live"))
        assertTrue(CrisisDetector.detect("I've been feeling suicidal"))
        assertTrue(CrisisDetector.detect("i've been hurting myself"))
    }

    @Test
    fun `is case and punctuation insensitive`() {
        assertTrue(CrisisDetector.detect("KILL MYSELF"))
        assertTrue(CrisisDetector.detect("...i want to die."))
        assertTrue(CrisisDetector.detect("i  want   to    die"))
    }

    @Test
    fun `matches with or without apostrophes`() {
        assertTrue(CrisisDetector.detect("i don't want to live"))
        assertTrue(CrisisDetector.detect("i dont want to live"))
        // Curly apostrophe, as transcripts often emit.
        assertTrue(CrisisDetector.detect("i don’t want to live"))
    }

    @Test
    fun `detects inside a longer sentence`() {
        assertTrue(
            CrisisDetector.detect(
                "hey so i know this is weird to say out loud but honestly some days i want to die"
            )
        )
    }

    // --- does NOT fire on car talk -----------------------------------------

    @Test
    fun `ignores kill and die idioms`() {
        assertFalse(CrisisDetector.detect("this traffic is killing me"))
        assertFalse(CrisisDetector.detect("kill the music"))
        assertFalse(CrisisDetector.detect("i'm dying to get home"))
        assertFalse(CrisisDetector.detect("my battery is dead"))
        assertFalse(CrisisDetector.detect("this hill is murder on the transmission"))
    }

    @Test
    fun `ignores suicide doors and other car vocabulary`() {
        assertFalse(CrisisDetector.detect("does it have suicide doors"))
        assertFalse(CrisisDetector.detect("i want to put a suicide knob on the wheel"))
        assertFalse(CrisisDetector.detect("suicide door conversion on the XJ"))
    }

    @Test
    fun `ignores ordinary conversation`() {
        assertFalse(CrisisDetector.detect("what's my coolant temp"))
        assertFalse(CrisisDetector.detect("play the next track"))
        assertFalse(CrisisDetector.detect(""))
        assertFalse(CrisisDetector.detect("   "))
    }

    // --- known limits, pinned so they're a decision not a surprise ----------

    @Test
    fun `known gap - oblique distress is NOT detected here and falls to the prompt rule`() {
        // These are real distress and the detector deliberately misses them: any
        // pattern broad enough to catch them also catches ordinary bad-day talk.
        // The system-prompt rule is what covers these. If this test ever starts
        // failing because someone widened PHRASES, that widening needs to be
        // weighed against the false positives it buys - see CrisisDetector's doc.
        assertFalse(CrisisDetector.detect("i don't see the point anymore"))
        assertFalse(CrisisDetector.detect("i'm so tired of all of this"))
        assertFalse(CrisisDetector.detect("nothing matters"))
    }
}
