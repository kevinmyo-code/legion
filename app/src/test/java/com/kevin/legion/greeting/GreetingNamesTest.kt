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
    fun `the rule with no name forbids every name, and names no one`() {
        val rule = GreetingNames.rule(null)
        listOf("no name at all", "nickname", "title", "character name", "make up", "overrides")
            .forEach { assertTrue(it, rule.contains(it)) }
        assertFalse(rule.contains(ALFRED.defaultName))
        assertEquals(1, rule.lines().size)
        assertEquals(rule, GreetingNames.rule("  "))
    }

    @Test
    fun `the lowercase forms of address the persona forbids are caught`() {
        listOf(
            "Ready when you are, sir.", "Go ahead, boss.", "What's next, chief?", "Well, old boy, what now?",
            "All ears, my friend.", "Hey boss, what's up?", "Sir, the floor is yours.", "Good to see you, madam.",
            "Fire away, old chap.", "Morning, sunshine.", "Hello, stranger.", "What now, mate?",
        ).forEach { assertTrue(it, GreetingNames.addresses(it)) }
    }

    @Test
    fun `a title before a name is caught, his own name included`() {
        listOf(
            "Good evening, Master Bruce.", "Master Bruce, what now?", "Ready, Lord Grantham?",
            "Miss Avery, all yours.", "Sir Lancelot, go on.",
        ).forEach { assertTrue(it, GreetingNames.addresses(it)) }
        assertTrue(GreetingNames.addresses("Evening, Master Sam.", allowed = "Sam"))
        assertTrue(GreetingNames.addresses("Morning, Mr. Sam.", allowed = "Sam"))
    }

    @Test
    fun `a name with no comma, or before a dash or ellipsis, is caught`() {
        listOf(
            "so Felix what now?", "Well Marcus, what's the plan?", "Okay Tobias what's first?", "What now Felix?",
            "Go on then Marcus.", "Felix \u2014 what's the plan?", "Marcus... what now?", "Well, Rupert \u2014 all yours.",
            "So Felix... go ahead.", "Felix - what now?",
        ).forEach { assertTrue(it, GreetingNames.addresses(it)) }
    }

    @Test
    fun `ordinary words in those spots are left alone`() {
        listOf(
            "Back on it Monday?", "So what's the plan?", "Well \u2014 here we are.", "Hm... go on.",
            "What's up now?", "Ready for Thursday?", "A master plan, then?", "With a friend tonight?",
            "Right... where were we?", "Now what?",
        ).forEach { assertFalse(it, GreetingNames.addresses(it)) }
    }

    @Test
    fun `with his name known, only his passes`() {
        listOf("Morning, Sam.", "Sam, what's first?", "so Sam what now?", "Sam... go on.", "Hey Sam.")
            .forEach { assertFalse(it, GreetingNames.addresses(it, allowed = "Sam")) }
        listOf("Morning, Marcus.", "Morning, sir.", "so Felix what now?", "Hey boss.")
            .forEach { assertTrue(it, GreetingNames.addresses(it, allowed = "Sam")) }
    }

    @Test
    fun `the rule with his name allows that one only, now and then`() {
        val rule = GreetingNames.rule(" Sam ")
        listOf("no name other than Sam", "now and then", "not in every greeting", "invented name", "nickname", "title")
            .forEach { assertTrue(it, rule.contains(it)) }
        assertEquals(1, rule.lines().size)
        assertTrue(GreetingNames.mentions("Morning, sam.", "Sam"))
        assertFalse(GreetingNames.mentions("Samples, anyone?", "Sam"))
        assertFalse(GreetingNames.mentions("Morning, Sam.", null))
    }
}
