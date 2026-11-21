@file:Suppress("MaxLineLength")

package com.kevin.legion.greeting

import com.kevin.legion.ai.ALFRED
import com.kevin.legion.service.cascade.AckLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The greeting calls him by no name (2026-11-20: a model-written hello on wake used an invented first
 * name). The names below are made up for the test; none is his.
 */
class GreetingNamesTest {
    @Test
    fun `a name ending the line is caught`() {
        listOf(
            "What's on your mind, Marcus?", "Ready when you are, Tobias.", "Go on then, Felix!",
            "There you are, Dr. Okafor.", "What now, Captain?", "Long time no see, Sunshine.",
            "Well, look who it is, Rupert. What's up?", "Morning, Theo", "Let's go, Mr. Pemberton!",
        ).forEach { assertTrue(it, GreetingNames.addresses(it)) }
    }

    @Test
    fun `a name opening the line, inside it or after a hello is caught`() {
        listOf(
            "Marcus, what's the plan?", "Hey Felix, what do you need?", "Hi, Tobias. Go ahead.",
            "Well, Rupert, here we are.", "Hello there, Mister Brandt.", "Evening Theo, what's up?",
            "\"Felix, go on.\"", "Yo Marcus.",
        ).forEach { assertTrue(it, GreetingNames.addresses(it)) }
    }

    @Test
    fun `ordinary hellos are left alone`() {
        listOf(
            "Well, look who it is.", "Go on then.", "Evening shift again?", "Oh, it's you. What's up?",
            "Ready when you are.", "Alright, I'm all ears.", "I'm here, what do you need?",
            "Hey, what's on your mind?", "Right, here we are. What's first?", "So, what's the plan?",
            "Okay, go ahead.", "Hello again.", "Hey there.", "Honestly, I was hoping you'd say hi.",
            "Hm, the floor is yours.", "Listening, as ever.", "Here, all yours.",
        ).forEach { assertFalse(it, GreetingNames.addresses(it)) }
    }

    @Test
    fun `every scripted line and every first-ever hello passes`() {
        (AckLines.DEFAULT + ALFRED.greetings).forEach { assertFalse(it, GreetingNames.addresses(it)) }
    }

    @Test
    fun `his real name is allowed when one is given, and only that one`() {
        assertFalse(GreetingNames.addresses("What's on your mind, Sam?", allowed = "Sam"))
        assertFalse(GreetingNames.addresses("Hey sam, go ahead.", allowed = " Sam "))
        assertTrue(GreetingNames.addresses("What's on your mind, Marcus?", allowed = "Sam"))
        assertTrue(GreetingNames.addresses("What's on your mind, Sam?"))
        assertTrue(GreetingNames.addresses("What's on your mind, Sam?", allowed = " "))
    }

    @Test
    fun `the rule names no one and forbids made-up names, titles and nicknames`() {
        listOf("no name at all", "nickname", "title", "character name", "make up", "overrides")
            .forEach { assertTrue(it, GreetingNames.RULE.contains(it)) }
        assertFalse(GreetingNames.RULE.contains(ALFRED.defaultName))
        assertEquals(1, GreetingNames.RULE.lines().size)
    }
}
