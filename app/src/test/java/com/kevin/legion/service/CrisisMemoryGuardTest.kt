package com.kevin.legion.service

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ticket #462 on the phone: once a turn of this conversation tripped the crisis detector, no call
 * that saves a memory runs for the rest of it, and the refusal says nothing was saved.
 *
 * A crisis turn itself already reaches no model on either phone path (voice stops at
 * `CascadeSession.shutdown("crisis")`, typed at `TypedTurnPolicy.Decision.Crisis`), so the hole
 * this closes is the turn AFTER: the typed panel deliberately survives the teardown, so the very
 * next typed message could otherwise save what was just said.
 *
 * Calls are written as the model sends them (folded action tools), since the guard resolves them
 * itself. Invented content only.
 */
@RunWith(RobolectricTestRunner::class) // org.json
class CrisisMemoryGuardTest {

    private fun args(vararg pairs: Pair<String, Any>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v)
        return o
    }

    private fun refusal(name: String, args: JSONObject, crisis: Boolean) =
        ReadThroughWriteGuard.refusal(name, args, readThrough = false, crisis = crisis)

    // ---- refused -------------------------------------------------------------------------------

    @Test
    fun `a remember in a crisis conversation is refused and says nothing was saved`() {
        val out = refusal("memory", args("action" to "remember", "text" to "Sam felt low"), crisis = true)
        assertNotNull("a memory must not be saved after a crisis turn", out)
        assertFalse(out!!.getBoolean("success"))
        assertTrue(out.getBoolean("crisis"))
        assertTrue(out.getString("message").contains("Nothing was saved"))
        assertTrue(out.getString("message").contains("do not offer to save it"))
        // Never his words back at him.
        assertFalse(out.getString("message").contains("Sam felt low"))
    }

    @Test
    fun `the refusal offers no next message, because the gate does not lift next turn`() {
        val out = refusal("memory", args("action" to "remember", "text" to "invented"), crisis = true)
        assertFalse(out!!.getString("message").lowercase().contains("ask again"))
        assertFalse(out.getString("message").lowercase().contains("next message"))
    }

    @Test
    fun `the typed surface is the same guard, so a typed remember is refused too`() {
        // `runTool` is the one tool runner for voice and typed chat alike, and the typed panel is
        // what carries a crisis exchange forward, so this is the case the ticket is really about.
        assertNotNull(refusal(MemoryToolbox.TOOL_NAME, args("action" to "remember", "text" to "x"), crisis = true))
    }

    // ---- still runs ----------------------------------------------------------------------------

    @Test
    fun `a clean conversation saves a memory as before`() {
        assertNull(refusal("memory", args("action" to "remember", "text" to "invented"), crisis = false))
    }

    @Test
    fun `recalling and listing still run in a crisis conversation`() {
        // Reading memories back is not saving this exchange, and the crisis card is the response
        // to the exchange itself; refusing a read would buy nothing.
        assertNull(refusal("memory", args("action" to "recall", "query" to "gym"), crisis = true))
        assertNull(refusal("memory", args("action" to "list"), crisis = true))
    }

    @Test
    fun `forgetting a memory still runs in a crisis conversation`() {
        assertNull(refusal("memory", args("action" to "forget", "id" to "m-invented"), crisis = true))
    }

    @Test
    fun `everything that is not a memory still runs in a crisis conversation`() {
        // The crisis says nothing about the words being untrustworthy, so the outside-text rule's
        // wide refusal is not the right shape here: these must all still work.
        assertNull(refusal("navigate", args("destination" to "invented street"), crisis = true))
        assertNull(refusal("control_music", args("action" to "pause"), crisis = true))
        assertNull(refusal("day", args("action" to "complete_task", "title" to "invented"), crisis = true))
        assertNull(refusal("manage_item", args("action" to "add", "item" to "invented errand"), crisis = true))
        val callArgs = args("action" to "place_call", "target" to "invented", "confirmed" to true)
        assertNull(refusal("phone", callArgs, crisis = true))
    }

    @Test
    fun `a goal plan still runs, and the ticket says why that door is not closed here`() {
        // Refusing the call would refuse the plan he asked for; the constraint's own gate belongs
        // in GoalsToolbox beside the read-through one. Named as owed, not silently widened into.
        assertNull(
            refusal("generate_goal_plan", args("goal_text" to "invented", "new_constraint" to "x"), crisis = true),
        )
    }

    // ---- the panel carries the flag, so it lasts exactly as long as the words --------------

    @Test
    fun `a crisis panel stays flagged through every typed follow-up`() {
        // `typedSent` CONTINUES a panel a voice close ended (it is the text model's history), so
        // the crisis words are still here to be saved and the flag must ride along.
        var panel = ChatTranscript().turnComplete("invented question", "invented answer", typed = false)
        panel = panel.sessionEnded().crisisHappened()
        assertTrue(panel.crisis)
        panel = panel.typedSent("and another thing")
        assertTrue("the words are still in the panel", panel.textHistory().size > 1)
        assertTrue("so the flag is still set", panel.crisis)
        panel = panel.typedReplied("invented reply").typedSent("again")
        assertTrue(panel.crisis)
    }

    @Test
    fun `a panel started afresh is not flagged`() {
        val ended = ChatTranscript()
            .turnComplete("invented", "invented", typed = false)
            .sessionEnded()
            .crisisHappened()
        // The first spoken line of a NEW session replaces the panel, so the words are gone.
        assertFalse(ended.beginIfEnded().crisis)
        // And "new conversation" drops the whole value.
        assertFalse(ChatTranscript().crisis)
    }

    // ---- the wiring the guard cannot see -------------------------------------------------------
    //
    // The controller needs a live Context, a socket and a mic to drive, the same limit
    // `RewakeCarryWiringTest` accepted. These two are source pins, not behaviour, and say so.

    @Test
    fun `the one tool runner hands the guard the panel's crisis flag`() {
        assertTrue(
            "runTool must read ChatTranscript.crisis",
            controller().contains("AssistantChatStore.transcript.value.crisis"),
        )
    }

    @Test
    fun `the crisis branch is the one place that sets the panel flag`() {
        val text = controller()
        assertTrue(text.contains("it.sessionEnded().crisisHappened()"))
        assertTrue(
            "set in one place only",
            Regex("crisisHappened\\(\\)").findAll(text).count() == 1,
        )
    }

    private fun controller() = stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/kevin/legion")
            if (candidate.isDirectory) return candidate
            val here = File(dir, "src/main/java/com/kevin/legion")
            if (here.isDirectory) return here
            dir = dir.parentFile
        }
        error("could not find the phone's source root from ${System.getProperty("user.dir")}")
    }

    private fun stripComments(text: String): String =
        LINE_COMMENT.replace(BLOCK_COMMENT.replace(text) { "\n".repeat(it.value.count { c -> c == '\n' }) }, "")

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("(?<![:\"])//[^\n]*")
    }
}
