package com.kevin.legion.service.cascade

import com.kevin.legion.service.WakePhrases
import com.kevin.legion.service.local.LocalAction
import com.kevin.legion.service.local.LocalCommand
import com.kevin.legion.service.local.LocalCommandPlan
import com.kevin.legion.service.local.MediaAction
import com.kevin.legion.service.local.VolumeAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One heard word changes nothing without his yes (2026-11-19, eval case `lone_word_write`).
 * Robolectric only for the toolbox's statics and `org.json`. Invented words only.
 */
@RunWith(RobolectricTestRunner::class)
class LoneWordWriteGuardTest {

    private fun args(vararg kv: Pair<String, String>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }
    private val switch = "car" to args("action" to "switch_vehicle", "vehicle" to "five")
    private val add = "manage_checklist" to args("action" to "add", "list" to "Groceries", "text" to "milk")
    private val read = "day" to args("action" to "read_calendar", "from" to "today", "to" to "today")

    private fun held(call: Pair<String, JSONObject>, heard: String, asked: Boolean = false) =
        LoneWordWriteGuard.refusal(call.first, call.second, heard, "Alfred", asked)

    @Test
    fun `a lone word's write is held for a yes, and nothing runs`() {
        val r = held(switch, "Five.")!!
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getBoolean("needs_yes"))
        assertTrue(r.getString("message").startsWith("Nothing was done"))
        assertTrue(held(add, "Milk.") != null)
        // His companion's name and a lead-in do not make it more than one word.
        for (h in listOf("Alfred, five.", "Five, Alfred.", "Hey five", "ok, okay five", "five!", "  five  ")) {
            assertTrue(h, held(switch, h) != null)
        }
    }

    @Test
    fun `a lone word that only reads runs, and so does any write said in more than one word`() {
        assertNull(held(read, "Tomorrow."))
        for (h in listOf("milk please", "add milk", "switch to five", "don't forget milk")) assertNull(h, held(add, h))
    }

    @Test
    fun `one word answers what a tool asked him in the turn before`() {
        assertNull(held(switch, "Yes.", asked = true))
        assertNull(held(add, "Groceries.", asked = true))
    }

    @Test
    fun `what counts as a tool asking him, from tool data only`() {
        fun asks(name: String, a: JSONObject, r: String) = LoneWordWriteGuard.asks(name, a, JSONObject(r))
        assertTrue("the guard's own hold", asks(switch.first, switch.second, held(switch, "Five.").toString()))
        assertTrue("its hold carries the one question field", held(switch, "Five.")!!.getBoolean(LoneWordWriteGuard.NEEDS_ANSWER))
        assertTrue("a tool's question (the adapter's which-car)", asks("car", args("action" to "switch_vehicle"), """{"success":true,"needs_answer":true,"message":"Which car is this?"}"""))
        // Review F6: a plain failure asks nothing, so a failed read never opens a one-word write.
        assertFalse("a clarifying refusal with no ask field", asks("car", args("action" to "switch_vehicle"), """{"success":false,"message":"Which car?"}"""))
        assertFalse("a read that failed offline", asks(read.first, read.second, """{"success":false,"message":"No network."}"""))
        assertFalse("an outside-read refusal", asks(add.first, add.second, """{"success":false,"read_through":true,"message":"Nothing was done."}"""))
        assertTrue("a draft waiting to send", asks("email", args("action" to "draft"), """{"success":true,"draft_id":"d1","sent":false}"""))
        assertTrue("an explicit ask", asks("browser", args("action" to "click"), """{"success":true,"needs_confirmation":true}"""))
        assertTrue("the first step of a two-step write", asks("clear_codes", args(), """{"success":true,"message":"Read this warning back."}"""))
        assertFalse("a write that went through", asks(add.first, add.second, """{"success":true}"""))
        assertFalse("a read", asks(read.first, read.second, """{"success":true,"events":[]}"""))
    }

    @Test
    fun `a sentence in a script without spaces is never one word, and never held (review F7)`() {
        // Invented phrases: a Mandarin, a Japanese, a Thai and a Burmese sentence, each with no spaces.
        for (h in listOf("把牛奶加到购物清单", "牛乳を買い物リストに追加して", "เพิ่มนมในรายการ", "နို့ကိုစာရင်းထဲထည့်ပါ", "牛奶")) {
            assertFalse(h, LoneWordWriteGuard.isLoneWord(h, "Alfred"))
            assertNull(h, held(add, h))
        }
        // A script written with spaces still counts words: one Korean word is one word.
        assertTrue(LoneWordWriteGuard.isLoneWord("우유", "Alfred"))
        assertFalse(LoneWordWriteGuard.isLoneWord("우유 추가해 줘", "Alfred"))
    }

    @Test
    fun `the stop words never reach the guard, they end the turn before the model`() {
        for (p in WakePhrases.STOP_PHRASES + WakePhrases.HARD_STOPS) {
            assertTrue(p, WakePhrases.isSleepPhrase(p, "Alfred"))
        }
    }

    @Test
    fun `the simple commands are not writes, so the guard never touches them`() {
        val one = listOf(
            LocalCommand.Media(MediaAction.PAUSE), LocalCommand.Media(MediaAction.RESUME),
            LocalCommand.Media(MediaAction.NEXT), LocalCommand.Media(MediaAction.PREVIOUS),
            LocalCommand.Volume(VolumeAction.UP, null), LocalCommand.Volume(VolumeAction.DOWN, null),
            LocalCommand.Volume(VolumeAction.MUTE, null), LocalCommand.Volume(VolumeAction.UNMUTE, null),
            LocalCommand.Volume(VolumeAction.SET, 5), LocalCommand.Timer(60), LocalCommand.Alarm(7, 0),
        )
        for (c in one) {
            val t = LocalCommandPlan.of(c) as LocalAction.Tool
            val a = JSONObject(t.args)
            assertFalse("$c", CascadeRules.isWrite(t.name, a))
            assertNull("$c", LoneWordWriteGuard.refusal(t.name, a, "pause", "Alfred", previousAsked = false))
        }
        // The one simple command that writes names its list: never one word.
        val clear = LocalCommandPlan.of(LocalCommand.ClearTicked("groceries")) as LocalAction.Tool
        assertTrue(CascadeRules.isWrite(clear.name, JSONObject(clear.args)))
        assertFalse(LoneWordWriteGuard.isLoneWord("clear ticked groceries", "Alfred"))
    }
}
