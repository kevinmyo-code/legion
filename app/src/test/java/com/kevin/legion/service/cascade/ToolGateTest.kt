package com.kevin.legion.service.cascade

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #453: the per-turn tool set. Invented phrasings only. The failure that matters is a turn that
 * needs a personal tool and gets the chat set, so [needsTools] is the long list. Robolectric only
 * for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class ToolGateTest {

    private fun set(heard: String, sticky: Boolean = false, companion: String? = null) =
        ToolGate.choose(heard, sticky, companion).set

    /** Every one of these must reach the full set: a personal read, a write, or a device action. */
    private val needsTools = listOf(
        // calendar
        "what's on my calendar", "what is on the calendar tomorrow", "am I free Thursday afternoon",
        "when is my next appointment", "do I have anything this weekend", "what's on tonight",
        "put dinner with Sam on Friday at seven", "move the dentist to next week", "cancel the meeting",
        // memory
        "remember that the gate code is 4411", "remind me to call the vet", "what did I tell you about the trip",
        "do you remember where I parked", "forget that", "note that the wifi password changed",
        // records and money
        "what did I spend on groceries", "how much is in checking", "log forty dollars at the hardware store",
        "did I pay the rent", "what's my balance", "how much did the oil change cost", "add milk to the list",
        "tick off eggs", "what's on the checklist", "who do I owe",
        // body, pantry, car
        "how many calories were in lunch", "what's my weight trend", "when is the next oil change",
        "any recalls on the truck", "what's in the fridge", "start a workout",
        // device
        "play something by the band", "skip this song", "call Sam", "who's calling", "what's playing",
        "navigate to the airport", "set a timer for ten minutes", "turn the volume down",
        "text mom I'm running late", "read my email", "open the browser",
        // here and now
        "what's the weather", "is it going to rain", "what time is it", "what day is it",
        "what's the latest news", "what's the score of the game", "is the pharmacy nearby",
        "what is this", "what does it say",
        // review of ba49fc72: third person, weekdays, named things, mail-backed, car by name
        "what's on friday", "what's happening on saturday", "is there anything on monday", "what about friday",
        "when's the dentist", "when is soccer practice", "when is garbage pickup", "when does the lease end",
        "when is mia's flight", "who is mia", "what's mia's number", "how old is mia", "who's picking up the kids",
        "what's the gate code", "where are the keys", "what's the confirmation number",
        "how much was dinner last night", "how much did the plumber cost", "when does netflix renew",
        "is the civic ok", "how many miles has the civic done", "is there a check engine code", "what's the vin",
        "where is the ups package", "what's the status of united 1234", "when does flight aa 100 land",
        "why did you say that", "stop saying that", "is the neighborhood safe",
        // general knowledge is no longer chat: the open set never closes
        "who won the world cup in 2018", "what's the capital of France", "why is the sky blue",
        "tell me a joke", "that's funny", "ok", "thanks, what's on friday", "hi, remind me to call the vet",
        // bare or ambiguous: no chat shape, so full
        "yes", "okay", "sure do it", "no", "and tomorrow", "the second one", "do that again", "send it",
        "", "   ", "um",
        // long
        "tell me about the history of the roman empire and then ".repeat(5),
    )

    /** The closed allowlist: pleasantries and closers, whole utterance. */
    private val chat = listOf(
        "hi", "hello there", "hey", "good morning", "good night", "thanks", "thank you so much",
        "thanks a lot", "ok thanks", "okay, thank you", "how are you", "how are you doing today",
        "how's it going", "what's up", "bye", "goodbye", "see you later", "that's all", "never mind",
        "stand down",
    )

    @Test
    fun `every turn that may need a tool gets the full set`() {
        val wrong = needsTools.filter { set(it) != ToolGate.Set.FULL }
        assertEquals("these were sent the chat set: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun `pleasantries and closers get the chat set`() {
        val wrong = chat.filter { set(it) != ToolGate.Set.CHAT }
        assertEquals("these were sent the full set: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun `once a turn went out full every later turn stays full`() {
        chat.forEach { assertEquals(it, ToolGate.Set.FULL, set(it, sticky = true)) }
        assertEquals("sticky", ToolGate.choose("hi", sticky = true).why)
    }

    @Test
    fun `the companion's name in front does not hide the shape`() {
        assertEquals(ToolGate.Set.CHAT, set("hey Jarvis, how are you", companion = "Jarvis"))
        assertEquals(ToolGate.Set.CHAT, set("Jarvis, thanks", companion = "Jarvis"))
        assertEquals(ToolGate.Set.FULL, set("hey Jarvis, what's on my calendar", companion = "Jarvis"))
        // Unknown name: the words after it are not guessed at.
        assertEquals(ToolGate.Set.FULL, set("hey Jarvis, how are you", companion = null))
    }

    @Test
    fun `punctuation case and curly apostrophes do not change the verdict`() {
        assertEquals(ToolGate.Set.CHAT, set("HOW’S IT GOING?!"))
        assertEquals(ToolGate.Set.FULL, set("What’s on my calendar??"))
    }

    @Test
    fun `a pleasantry with anything else in it is full, and the reason is logged`() {
        assertEquals(ToolGate.Set.FULL, set("how are you, and remind me about the vet"))
        assertEquals(ToolGate.Set.FULL, set("thanks, now what's on tomorrow"))
        assertEquals("pleasantry", ToolGate.choose("thanks", false).why)
        assertEquals("not_a_pleasantry", ToolGate.choose("yes", false).why)
        assertEquals("empty", ToolGate.choose("  ", false).why)
    }

    @Test
    fun `the chat cut keeps end_conversation and nothing personal`() {
        val decls = JSONArray()
            .put(JSONObject().put("name", "end_conversation"))
            .put(JSONObject().put("name", "remember"))
            .put(JSONObject().put("name", "day"))
            .put(JSONObject().put("name", "records"))
        val full = CascadePrefix("model-x", "Invented instruction.", decls)
        val cut = ToolGate.chatPrefix(full)
        val declared = cut.functionDeclarations
        val names = (0 until declared.length()).map { declared.getJSONObject(it).getString("name") }
        assertEquals(listOf("end_conversation"), names)
        assertEquals(full.systemInstruction, cut.systemInstruction)
        assertEquals(true, cut.search)
        assertFalse("the chat prefix never has a cache", cut.cacheable)
        assertEquals(true, full.cacheable)
        assertFalse("a different key, so it can never pick up the full set's cache", cut.key == full.key)
        assertEquals("the full prefix is not changed", 4, full.functionDeclarations.length())
        assertEquals(full, ToolGate.prefixFor(full, ToolGate.Verdict(ToolGate.Set.FULL, "x")))
        assertEquals(cut.key, ToolGate.prefixFor(full, ToolGate.Verdict(ToolGate.Set.CHAT, "x")).key)
    }

    @Test
    fun `the chat set is stable so its cache is reused`() {
        val decls = JSONArray().put(JSONObject().put("name", "end_conversation")).put(JSONObject().put("name", "a"))
        val a = ToolGate.chatPrefix(CascadePrefix("m", "i", decls)).key
        val b = ToolGate.chatPrefix(CascadePrefix("m", "i", decls)).key
        assertEquals(a, b)
    }
}
