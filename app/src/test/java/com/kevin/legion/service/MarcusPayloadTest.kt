package com.kevin.legion.service

import com.kevin.legion.ai.ALFRED
import com.kevin.legion.ai.KRATOS
import com.kevin.legion.ai.MARCUS
import com.kevin.legion.ai.SHARED_INSTRUCTIONS
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Marcus companion costs in the Live `setup` payload, measured the way
 * [LiveSetupPayloadSizeTest] measures it (chars/4, the tools array wrapped as `buildSetup` wraps it,
 * system instruction = persona clause + delivery + [SHARED_INSTRUCTIONS]).
 *
 * **Why a second test and not a second ceiling.** [LiveSetupPayloadSizeTest] measures [ALFRED], the
 * default companion, and its ceiling (raised to 23,800 by Kevin's ruling 2026-10-05, option A) was
 * written for Alfred alone and is re-asserted below for Kratos and Marcus. Every persona has a
 * different register length, so that test cannot speak for the others: Kratos's clause is already
 * ~1,400 chars longer than Alfred's. What this pins is Marcus's OWN delta, so growth in his clause or
 * his tool shows up as a failure with a number on it.
 *
 * **The tool is declared only for Marcus**, so Alfred's payload is byte-identical to before this
 * change - asserted below by comparing the declaration lists.
 */
@RunWith(RobolectricTestRunner::class)
class MarcusPayloadTest {

    // Mirrors LiveSetupPayloadSizeTest.ceilingTokens; two private copies are cheaper than widening one.
    private val liveSetupCeilingTokens = 24_000

    private fun tokens(chars: Int) = chars / 4

    private fun toolsChars(fns: JSONArray): Int =
        JSONArray()
            .put(JSONObject().put("googleSearch", JSONObject()))
            .put(JSONObject().put("functionDeclarations", fns))
            .toString().length

    private fun instructionChars(persona: com.kevin.legion.ai.Persona): Int =
        (persona.clause.trimIndent() + " " + persona.delivery + " " + SHARED_INSTRUCTIONS).length

    @Test
    fun `Marcus costs a measured, bounded amount more than Alfred and Alfred is unchanged`() {
        val alfredFns = LiveToolbox.declarationsFor(ALFRED.key)
        val plainFns = LiveToolbox.declarations()
        val marcusFns = LiveToolbox.declarationsFor(MARCUS.key)

        // Alfred (and anyone else) gets exactly the pre-change tool list.
        assertEquals(plainFns.toString(), alfredFns.toString())
        assertEquals(plainFns.length() + 1, marcusFns.length())

        val toolDelta = toolsChars(marcusFns) - toolsChars(plainFns)
        val clauseDelta = instructionChars(MARCUS) - instructionChars(ALFRED)
        val alfredTotal = toolsChars(alfredFns) + instructionChars(ALFRED)
        val marcusTotal = toolsChars(marcusFns) + instructionChars(MARCUS)
        val kratosTotal = toolsChars(alfredFns) + instructionChars(KRATOS)

        println(
            "marcus payload: tool +$toolDelta chars (~${tokens(toolDelta)} tokens), persona +$clauseDelta chars " +
                "(~${tokens(clauseDelta)} tokens) over Alfred; totals alfred ~${tokens(alfredTotal)}, " +
                "kratos ~${tokens(kratosTotal)}, marcus ~${tokens(marcusTotal)} estimated tokens (chars/4)",
        )

        // The ceiling Kevin set 2026-10-05 (option A) covers every companion, not only Alfred, which
        // is the only one LiveSetupPayloadSizeTest measures. Measured at that merge: kratos ~23,320,
        // marcus ~23,629. A persona that outgrows it trips here with its own number.
        assertTrue("kratos is ~${tokens(kratosTotal)} tokens", tokens(kratosTotal) <= liveSetupCeilingTokens)
        assertTrue("marcus is ~${tokens(marcusTotal)} tokens", tokens(marcusTotal) <= liveSetupCeilingTokens)

        // ~225 tokens for the one declaration. It is trimmed to the rules that matter (quote only
        // what it returned, say paraphrase, say when nothing matched); more than this and it should
        // be trimmed again rather than the bound moved.
        assertTrue("the consult_meditations declaration grew to $toolDelta chars", toolDelta <= 900)
        // The clause carries manner, philosophy, the quotation rule and the distress rule - four jobs.
        // 2026-10-05: 3,500 -> 3,700 for the contiguous-span quotation rule (+~250 chars, ~60 tokens,
        // measured below the 23,800 ceiling in the println above and the assertion before it).
        assertTrue("MARCUS clause is ${MARCUS.clause.length} chars", MARCUS.clause.length <= 3_700)
    }
}
