package com.kevin.legion.service.cascade

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A write that went through and was not heard is owed until its line plays (2026-11-19), and the
 * session has one door that says it: [CascadeSession]'s newSpeaker, plus [sayOwed] before each
 * close. The session needs a live Context to construct, so its wiring is read from source, as
 * `CascadeWiringTest` does. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class OwedWritesTest {

    private fun fact(item: String, ok: Boolean = true, result: String = """{"item":"$item","list":"Groceries"}""") =
        ScriptedLines.writeFact("manage_item", "add", JSONObject(result), ok)

    @Test
    fun `a write whose line played is not owed, one whose line did not play is`() {
        val milk = fact("milk")
        val eggs = fact("eggs")
        val owed = OwedWrites()
        owed.owe(listOf(milk, eggs), played = listOf(milk), cutOff = false)
        val due = owed.due()!!
        assertEquals("Done: added eggs to Groceries.", due.line)
        // Not paid until it plays: asked again, it is still due.
        assertEquals(due.line, owed.due()!!.line)
        owed.paid(due.facts)
        assertNull(owed.due())
        assertTrue(owed.isEmpty())
    }

    @Test
    fun `two identical writes are two debts, and owing the same fact twice counts it once`() {
        val a = fact("milk")
        val b = fact("milk")
        val owed = OwedWrites()
        owed.owe(listOf(a, b), emptyList(), cutOff = false)
        owed.owe(listOf(a, b), emptyList(), cutOff = false)
        assertEquals("Done: added milk to Groceries; added milk to Groceries.", owed.due()!!.line)
        owed.paid(listOf(a))
        assertEquals("Done: added milk to Groceries.", owed.due()!!.line)
    }

    @Test
    fun `a failed write is owed only when the turn was cut off, nothing unchanged ever is`() {
        val failed = fact("essay", ok = false, result = """{"title":"essay"}""")
        val same = fact("x", result = """{"changed":false,"item":"x"}""")
        val owed = OwedWrites()
        owed.owe(listOf(failed, same), emptyList(), cutOff = false)
        assertNull(owed.due())
        owed.owe(listOf(failed, same), emptyList(), cutOff = true)
        assertEquals("Essay didn't go through.", owed.due()!!.line)
    }

    @Test
    fun `a debt paid while a new one arrives keeps the new one`() {
        val milk = fact("milk")
        val eggs = fact("eggs")
        val owed = OwedWrites()
        owed.owe(listOf(milk), emptyList(), cutOff = true)
        val due = owed.due()!!
        owed.owe(listOf(eggs), emptyList(), cutOff = true)
        owed.paid(due.facts)
        assertEquals("Done: added eggs to Groceries.", owed.due()!!.line)
    }

    // --- the session's wiring, from source ------------------------------------------------------

    private val session by lazy { File(root(), "service/cascade/CascadeSession.kt").readText() }

    @Test
    fun `every spoken thing starts with what is owed, paid only when its line played`() {
        val speaker = bodyOf(session, "newSpeaker")
        assertTrue(speaker.contains("owed.due()?.let { d -> it.line(d.line) { owed.paid(d.facts) } }"))
        val listener = bodyOf(session, "listenerFor")
        assertTrue(listener.contains("reply.line(text) { synchronized(record) { record.played.addAll(facts) } }"))
        assertTrue("the session's loop says writes", session.contains("writeFact = ScriptedLines::factOf"))
    }

    @Test
    fun `every turn's end owes what was not heard, a cut-off turn its failures too`() {
        val answer = bodyOf(session, "answerTurn")
        assertTrue(answer.contains("owe(record, cutOff = outcome != ReplySpeaker.Outcome.Played)"))
        assertTrue(answer.contains("owe(record, cutOff = true)"))
        assertTrue("a failure after a said write is said", answer.contains("result.codeLines > 0"))
        assertFalse("one owed list, nothing else", session.contains("unheardWrites"))
    }

    @Test
    fun `no close plays its tone before what is owed was said, except a voice that failed`() {
        // Every function that plays the closing tone either says what is owed first or has already
        // spoken through newSpeaker (which says it). The hand-over is the voice failing: the accepted
        // known limit (the line is on the panel and in the record).
        val exempt = setOf("handOver")
        val names = Regex("""(?m)^\s*(?:private |internal )?(?:suspend )?fun (\w+)\(""").findAll(session).map { it.groupValues[1] }.toSet()
        var checked = 0
        for (name in names) {
            val body = bodyOf(session, name)
            val tone = body.indexOf("host.closingTone()")
            if (tone < 0 || name in exempt) continue
            val before = body.substring(0, tone)
            assertTrue("$name plays the tone before saying what is owed", before.contains("sayOwed()") || before.contains("newSpeaker("))
            checked++
        }
        assertTrue("found the closes ($checked)", checked >= 6)
        assertTrue(bodyOf(session, "endByStop").contains("endByVoice()"))
    }

    private fun bodyOf(src: String, fn: String): String {
        val start = Regex("""fun $fn\(""").find(src)?.range?.first ?: error("no fun $fn")
        val open = src.indexOf('{', src.indexOf(')', start))
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(start, i + 1)
            }
        }
        error("unbalanced $fn")
    }

    private fun root(): File =
        listOf("src/main/java/com/kevin/legion", "app/src/main/java/com/kevin/legion").map { File(it) }.first { it.isDirectory }
}
