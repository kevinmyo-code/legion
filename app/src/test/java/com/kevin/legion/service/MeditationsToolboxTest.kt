package com.kevin.legion.service

import com.kevin.legion.ai.CrisisDetector
import com.kevin.legion.ai.MARCUS
import com.kevin.legion.meditations.Meditations
import com.kevin.legion.meditations.MeditationsFixture
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `consult_meditations`: what the model gets back, where it is declared, and that the bundled asset
 * is really packaged (the other meditations tests read the file from the source tree).
 *
 * Robolectric because `org.json` is stubbed in a plain JVM test, and for the asset check.
 */
@RunWith(RobolectricTestRunner::class)
class MeditationsToolboxTest {

    private val index = MeditationsFixture.search

    private fun names(arr: JSONArray) = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }

    @Test
    fun `the tool is declared only while Marcus is the active companion`() {
        assertTrue(MeditationsToolbox.TOOL_NAME in names(LiveToolbox.declarationsFor(MARCUS.key)))
        for (other in listOf(null, "", "alfred", "dorothy", "kratos")) {
            assertFalse(
                "persona \"$other\" must not carry the Meditations tool",
                MeditationsToolbox.TOOL_NAME in names(LiveToolbox.declarationsFor(other)),
            )
        }
    }

    @Test
    fun `the declared name is the dispatched name and the persona key is Marcus's`() {
        assertEquals(listOf(MeditationsToolbox.TOOL_NAME), names(MeditationsToolbox.declarations()))
        assertEquals(MARCUS.key, MeditationsToolbox.PERSONA_KEY)
        assertTrue(
            "the persona must tell the model the real tool name",
            MARCUS.clause.contains(MeditationsToolbox.TOOL_NAME),
        )
    }

    @Test
    fun `a topic returns cited passages that are verbatim`() {
        val r = MeditationsToolbox.consult(index, "what others think")
        assertTrue(r.getBoolean("success"))
        assertTrue(r.getBoolean("found"))
        val passages = r.getJSONArray("passages")
        assertTrue(passages.length() in 1..3)
        for (i in 0 until passages.length()) {
            val p = passages.getJSONObject(i)
            assertTrue(p.getString("cite").matches(Regex("""Book [IVX]+, \d+""")))
            val source = MeditationsFixture.passages.first { it.cite == p.getString("cite") }
            assertTrue(source.text.contains(p.getString("text")))
        }
        assertTrue(r.getString("note").contains("Quote only these words"))
    }

    @Test
    fun `no match says so in words and invites no quotation`() {
        val r = MeditationsToolbox.consult(index, "pizza recipe")
        assertTrue(r.getBoolean("success"))
        assertFalse(r.getBoolean("found"))
        assertFalse(r.has("passages"))
        assertTrue(r.getString("message").contains("Nothing in the Meditations matched"))
        assertTrue(r.getString("message").contains("do not quote"))
    }

    @Test
    fun `a blank query is a failure that says nothing was looked up`() {
        val r = MeditationsToolbox.consult(index, "  ")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message").contains("nothing was looked up"))
    }

    @Test
    fun `a reference fetches that exact section`() {
        val r = MeditationsToolbox.consult(index, "Book XII, 36")
        val p = r.getJSONArray("passages")
        assertEquals(1, p.length())
        assertEquals("Book XII, 36", p.getJSONObject(0).getString("cite"))
        assertTrue(p.getJSONObject(0).getString("text").startsWith("Man, thou hast been a citizen"))
    }

    /**
     * Safety stays above character. The Meditations hold passages about leaving life; a distressed
     * turn must not be answered with one. The tool refuses on the same detector that guards the
     * transcript, and tells the model to stop being the character.
     */
    @Test
    fun `a distressed query is refused and sends the model to the safety path`() {
        for (q in listOf("I want to die", "I am going to kill myself", "I have nothing to live for")) {
            assertTrue("test premise: CrisisDetector must match \"$q\"", CrisisDetector.detect(q))
            val r = MeditationsToolbox.consult(index, q)
            assertFalse("\"$q\"", r.getBoolean("success"))
            assertFalse(r.has("passages"))
            assertTrue(r.getString("message").contains("stop speaking as the Emperor"))
        }
    }

    @Test
    fun `dispatch ignores other tools and the bundled asset is packaged`() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(MeditationsToolbox.dispatch(context, "get_current_time", org.json.JSONObject()))
        val r = MeditationsToolbox.dispatch(
            context, MeditationsToolbox.TOOL_NAME, org.json.JSONObject().put("query", "the morning"),
        )
        assertNotNull(r)
        assertTrue(r!!.getBoolean("found"))
        assertEquals(487, Meditations.search(context).passages.size)
    }
}
