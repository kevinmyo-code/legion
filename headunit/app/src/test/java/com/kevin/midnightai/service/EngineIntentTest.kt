package com.kevin.midnightai.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ask_engine` intent resolution: the declared intent wins; a missing one falls back to a verb
 * check so "add milk to groceries" is never silently sent read-only. */
class EngineIntentTest {

    @Test
    fun `explicit record always writes`() {
        assertTrue(EngineIntent.wantsWrite("record", "what is on my list"))
        assertTrue(EngineIntent.wantsWrite(" RECORD ", "anything"))
    }

    @Test
    fun `explicit ask never writes even with a write verb`() {
        assertFalse(EngineIntent.wantsWrite("ask", "add milk to groceries"))
    }

    @Test
    fun `missing intent with a write command is a record`() {
        listOf(
            "add milk to groceries",
            "Add eggs to the grocery list",
            "put bread on my shopping list",
            "log 182 pounds",
            "log my lunch, a chicken burrito",
            "tick off milk",
            "check off eggs on groceries",
            "cross off the dentist",
            "create an event for dentist tomorrow at 3",
            "save a note that the garage code changed",
            "remind me to call mom at 5",
            "schedule a haircut friday",
            "can you add milk to groceries",
            "please add coffee",
            "remove eggs from the list",
        ).forEach { assertTrue(it, EngineIntent.wantsWrite(null, it)) }
    }

    @Test
    fun `missing intent with a question is an ask`() {
        listOf(
            "what's on my grocery list",
            "what is on my calendar today",
            "did I log my weight this week",
            "how did I sleep",
            "show me my to-dos",
            "when is the dentist",
            "do I have anything tomorrow",
            "my workout plan",
            "",
        ).forEach { assertFalse(it, EngineIntent.wantsWrite(null, it)) }
    }

    @Test
    fun `review cases - read phrasings with write-looking words stay asks`() {
        listOf(
            "remind me what's on my list",
            "can you check what I logged yesterday",
            "give me an update on my weight",
            "groceries, did I add milk",
        ).forEach { assertFalse(it, EngineIntent.wantsWrite(null, it)) }
        listOf("add milk to groceries", "log 180 pounds")
            .forEach { assertTrue(it, EngineIntent.wantsWrite(null, it)) }
    }

    @Test
    fun `an unknown intent value is treated as missing`() {
        assertTrue(EngineIntent.wantsWrite("write", "add milk"))
        assertFalse(EngineIntent.wantsWrite("", "what is due"))
    }
}
