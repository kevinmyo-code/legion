package com.kevin.legion.service

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the 2026-08-17 routing defect (`memory/MEMORY.md`, "START HERE: the routing bug").
 *
 * Kevin spoke workout sets. Nothing was written, and he was told it was recorded. Logcat for the
 * 21:42-21:45 window proves `log_workout_set` was NEVER called: the live model routed a
 * workout-logging request to `ask_goals`, a domain whose [LiveToolbox.DISPATCHED] list holds only
 * `list_goals` and `ask_advisor` and structurally cannot write a set. The sub-agent answered in
 * prose anyway, and `agentResult` wrapped that prose as `success: true`.
 *
 * Three things had to change, and this file holds each of them to its claim:
 *
 * 1. **The two descriptions overlapped.** `ask_goals` advertised "domain-advisor coaching across
 *    fitness, planning, the car, or money" - it claimed fitness, which `ask_body` owns - while
 *    `ask_body` buried "workout set" mid-sentence and never used the words a driver actually
 *    says. Territory is asserted below rather than left to review, because the only other place
 *    it surfaces is a live socket.
 * 2. **A mis-routed request could still come back a success.** Every dispatcher grounding now
 *    ends in [LiveToolbox.dispatchBoundaryClause], derived from that domain's own writable tools,
 *    and a domain with none is told in as many words that it cannot record anything.
 * 3. **The mutation gate was off because nothing could tell a write from a read.** The dispatchers
 *    now take an explicit `intent` the model declares, so [LiveToolbox.wantsWrite] reads a stated
 *    fact instead of guessing prose.
 *
 * Prompt text is not a proof, which is why 1 and 2 are the weaker two thirds. 3 is the part that
 * holds mechanically, and its arithmetic is asserted here too.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxDispatchRoutingTest {

    private fun declarationFor(name: String): JSONObject {
        val all: JSONArray = LiveToolbox.declarations()
        for (i in 0 until all.length()) {
            val decl = all.getJSONObject(i)
            if (decl.getString("name") == name) return decl
        }
        throw AssertionError("$name is not declared to the live session at all")
    }

    private fun descriptionOf(name: String) = declarationFor(name).getString("description").lowercase()

    /**
     * The exact confusion that shipped: a workout is body territory, and `ask_goals` must not
     * advertise itself as a place to take one. The word "goal" is fine and expected in its text -
     * what is banned is the training/eating/sleeping vocabulary that made a logging request look
     * like a coaching request.
     */
    @Test
    fun `ask_goals does not claim any of ask_body's territory`() {
        val goals = descriptionOf("ask_goals")
        for (word in listOf("fitness", "workout", "meal", "sleep", "bodyweight", "weigh-in")) {
            // Naming the destination is the one legitimate use of these words here. That
            // destination changed on 2026-08-18: the eight write tools came back out from behind
            // the dispatchers, so a set now goes to log_workout_set BY NAME rather than to
            // ask_body. Anything else is a claim on territory this domain cannot serve.
            assertTrue(
                "ask_goals' description says \"$word\" without handing it to ask_body - this is " +
                    "the exact overlap that routed \"log my sets\" into a domain holding only " +
                    "list_goals and ask_advisor",
                word !in goals || "log_workout_set" in goals,
            )
        }
        assertTrue(
            "ask_goals must say outright that it records nothing - it has no writable tool, and " +
                "the live model has no other way to learn that",
            "records nothing" in goals,
        )
    }

    /**
     * ask_body stopped being a write path on 2026-08-18 (Kevin's call): the five body log tools are
     * declared directly to the live session again, because a mis-routed WRITE is the failure this
     * app has actually suffered twice and no description beat a tool the model could not see.
     *
     * So the assertion inverts. What used to be required - "the ONLY route that can record any of
     * it" - is now the lie. The description must say it records nothing and must name the tools
     * that do, exactly as ask_goals already had to.
     */
    @Test
    fun `ask_body says it records nothing and names the tools that do`() {
        val body = descriptionOf("ask_body")
        assertTrue(
            "ask_body must say outright that it records nothing - it holds no writable tool now, " +
                "and the live model has no other way to learn that",
            "records nothing" in body,
        )
        for (tool in listOf("log_meal", "log_sleep", "log_workout_set", "log_bodyweight")) {
            assertTrue(
                "ask_body must name \"$tool\" as where a record actually goes",
                tool in body,
            )
        }
        // Still findable by the words a driver speaks - the reason it is worded around real speech
        // rather than category names has not changed, only which side of the read/write line it is on.
        for (word in listOf("meal", "sleep", "workouts", "bodyweight")) {
            assertTrue("ask_body's description must name \"$word\"", word in body)
        }
    }

    /**
     * The gate's arithmetic, stated as a fact rather than as prose: `goals` and `mail` hold no
     * writable tool, so a `record`-intent call into either can never report a mutation, so
     * [LiveToolbox.successOrMutationRefusal] must refuse it. This is what makes 2026-08-17's
     * failure impossible rather than merely unlikely.
     */
    @Test
    fun `a record-intent call that wrote nothing is refused, not answered`() {
        val refused = LiveToolbox.successOrMutationRefusal(
            subAgentText = "Logged three sets of squats at 225.",
            mutatingToolsCalled = emptyList(),
            requireMutation = true,
        )
        assertFalse(
            "a write-intent dispatch that touched no mutating tool must NOT come back as success " +
                "carrying the sub-agent's own prose - that prose is precisely how \"it's " +
                "recorded\" got spoken over an empty database",
            refused.getBoolean("success"),
        )

        val answered = LiveToolbox.successOrMutationRefusal(
            subAgentText = "You squatted 225 for three sets last Tuesday.",
            mutatingToolsCalled = emptyList(),
            requireMutation = false,
        )
        assertTrue(
            "a read-intent dispatch must still answer normally - the gate exists to catch a " +
                "claimed write, not to refuse questions",
            answered.getBoolean("success"),
        )
    }

    /**
     * `intent` is optional on purpose: a model that omits it must land on today's behaviour (a
     * read), never on a refusal. Anything but the exact literal reads as a read.
     */
    @Test
    fun `wantsWrite reads only an explicit record intent and defaults to a read`() {
        assertTrue(LiveToolbox.wantsWrite(JSONObject().put("intent", "record")))
        assertFalse(LiveToolbox.wantsWrite(JSONObject().put("intent", "ask")))
        assertFalse("an omitted intent must degrade to a read", LiveToolbox.wantsWrite(JSONObject()))
        assertFalse(LiveToolbox.wantsWrite(JSONObject().put("intent", "")))
        assertFalse(
            "a misspelt value must degrade to a read, never to a refusal",
            LiveToolbox.wantsWrite(JSONObject().put("intent", "Record ")),
        )
    }

    /** Every dispatcher must offer the model the intent field, or the gate above can never fire. */
    @Test
    fun `every dispatcher declares question and the optional intent enum`() {
        for (name in listOf("ask_fleet", "ask_body", "ask_goals", "ask_pantry", "ask_mail")) {
            val decl = declarationFor(name)
            val props = decl.getJSONObject("parameters").getJSONObject("properties")
            assertTrue("$name must take a question", props.has("question"))
            assertTrue("$name must offer the intent signal the mutation gate reads", props.has("intent"))

            val values = props.getJSONObject("intent").getJSONArray("enum")
            assertEquals("intent must be a two-value enum", 2, values.length())
            assertEquals("record", values.getString(0))
            assertEquals("ask", values.getString(1))

            val required = decl.getJSONObject("parameters").getJSONArray("required")
            assertEquals(
                "only question may be required - a required intent would fail the call outright " +
                    "on any model that omits it",
                1,
                required.length(),
            )
            assertEquals("question", required.getString(0))
        }
    }

    /**
     * The clause is derived from the domain's real writable tools, so it cannot drift from
     * [LiveToolbox.DISPATCHED] the way two hand-written prompts drift from each other.
     */
    @Test
    fun `a domain with no writable tool is told it cannot record anything`() {
        // All five joined this group on 2026-08-18. manage_grocery came out from behind ask_pantry
        // after a grocery add kept landing on the persistent list, and Kevin's measured call then
        // pulled the other eight write tools out of fleet and body for the same reason. Every
        // dispatcher is a read path now, so every one of them must say so - which is the mechanical
        // half of the fix: a write that still mis-routes into one is refused in words, not answered
        // around. If a domain ever regains a write tool, this loop is where that shows up.
        for (domain in listOf("fleet", "body", "goals", "mail", "pantry")) {
            val clause = LiveToolbox.dispatchBoundaryClause(domain).lowercase()
            assertTrue(
                "\"$domain\" holds no mutating tool, so its sub-agent must be told it cannot write",
                "no tool that writes anything" in clause,
            )
            assertTrue("\"$domain\" must be told to say nothing was recorded", "nothing was recorded" in clause)
        }
    }
}
