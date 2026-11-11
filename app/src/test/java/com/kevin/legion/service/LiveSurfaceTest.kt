package com.kevin.legion.service

import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.service.tools.LiveSurface
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Live cost diet (voice cost audit 2026-11-10, F1): every tool the Live setup carried before the
 * diet still has a home - declared directly, as an action of an action tool, or behind a router -
 * and every action resolves to the tool it stands for, so the old dispatch branch runs unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class LiveSurfaceTest {

    private val declared: List<JSONObject> by lazy {
        val a = LiveToolbox.declarations()
        (0 until a.length()).map { a.getJSONObject(it) }
    }
    private val declaredNames by lazy { declared.map { it.getString("name") }.toSet() }
    private val routed by lazy { LiveToolbox.DISPATCHED.values.flatten().toSet() }

    @Test
    fun `every tool the setup carried before the diet has a home`() {
        for (old in BEFORE_DIET) {
            val home = when {
                old in declaredNames -> "direct"
                old in LiveSurface.ACTION_HOME -> {
                    val (tool, action) = LiveSurface.ACTION_HOME.getValue(old)
                    assertTrue("$old's action tool $tool is not declared", tool in declaredNames)
                    "$tool/$action"
                }
                old in routed -> "router ask_" + LiveToolbox.DISPATCHED.entries.first { old in it.value }.key
                else -> null
            }
            assertTrue("$old has no home after the diet: it would be dropped silently", home != null)
            homes.append(old).append(TAB).append(home).append(NEWLINE)
        }
        // The old-tool-to-new-home table for the diet report (build output, never committed).
        java.io.File("build/live-setup").mkdirs()
        java.io.File("build/live-setup/homes.tsv").writeText(homes.toString())
    }

    private val homes = StringBuilder()

    @Test
    fun `no tool is declared directly and folded at once`() {
        for (name in declaredNames) {
            assertFalse("$name is declared AND folded into an action tool", LiveSurface.isFolded(name))
            assertFalse("$name is declared AND behind a router", name in routed)
        }
    }

    @Test
    fun `every action resolves to a declaration the toolbox owns`() {
        val all = LiveToolbox.surfaceCandidatesForTest()
        val owned = (0 until all.length()).map { all.getJSONObject(it).getString("name") }.toSet()
        for (tool in LiveSurface.ACTION_TOOLS) for (a in tool.actions) {
            assertTrue("${tool.name}.${a.value} points at ${a.target}, which no toolbox declares", a.target in owned)
            val r = LiveSurface.resolve(tool.name, JSONObject().put(tool.discriminator, a.value).put("x", 1))
            assertEquals(a.target, r.name)
            assertEquals("other args ride through", 1, r.args.getInt("x"))
            assertFalse("the discriminator is consumed", r.args.has(tool.discriminator) && a.fixed.isEmpty())
        }
    }

    @Test
    fun `fixed args become the target's own action`() {
        val r = LiveSurface.resolve("car", JSONObject().put("action", "rename_vehicle").put("name", "Blue"))
        assertEquals("manage_vehicle", r.name)
        assertEquals("rename", r.args.getString("action"))
        assertEquals("Blue", r.args.getString("name"))
        val t = LiveSurface.resolve("trip", JSONObject().put("action", "avoid").put("avoid", "tolls"))
        assertEquals("change_trip", t.name)
        assertEquals("avoid", t.args.getString("action"))
    }

    @Test
    fun `an extended tool passes its own actions through untouched`() {
        val args = JSONObject().put("action", "add").put("list", "Groceries").put("text", "eggs")
        val r = LiveSurface.resolve("manage_checklist", args)
        assertEquals("manage_checklist", r.name)
        assertEquals("add", r.args.getString("action"))
        assertEquals("get_last_ticked", LiveSurface.resolve("manage_checklist", JSONObject().put("action", "get_last_ticked")).name)
        assertEquals("read_list", LiveSurface.resolve("manage_item", JSONObject().put("action", "read_list")).name)
        assertEquals("get_music_queue", LiveSurface.resolve("control_music", JSONObject().put("action", "get_music_queue")).name)
    }

    @Test
    fun `an old name resolves to itself, so a model that calls one still works`() {
        assertEquals("log_meal", LiveSurface.resolve("log_meal", JSONObject()).name)
    }

    @Test
    fun `an unknown action fails in words through dispatch and does nothing`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val out = LiveToolbox.dispatch(ctx, "body_log", JSONObject().put("action", "log_lunch"))!!
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("Nothing was done"))
        assertTrue(out.getString("message").contains("log_meal"))
    }

    @Test
    fun `an action call dispatches exactly as the old tool did`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // why_did_you_say_that reads the local raise log only: deterministic in a test.
        val viaAction = LiveToolbox.dispatch(ctx, "day", JSONObject().put("action", "why_did_you_say_that"))
        val direct = LiveToolbox.dispatch(ctx, "why_did_you_say_that", JSONObject())
        assertEquals(direct.toString(), viaAction.toString())
    }

    @Test
    fun `each action tool's declaration names every action and requires only the discriminator`() {
        for (tool in LiveSurface.ACTION_TOOLS.filter { it.base == null }) {
            val decl = declared.first { it.getString("name") == tool.name }
            val params = decl.getJSONObject("parameters")
            val enum = params.getJSONObject("properties").getJSONObject(tool.discriminator).getJSONArray("enum")
            assertEquals(tool.actions.map { it.value }, (0 until enum.length()).map { enum.getString(it) })
            assertEquals(1, params.getJSONArray("required").length())
            for (a in tool.actions) assertTrue(decl.getString("description").contains("${a.value}:"))
        }
    }

    /**
     * The 2026-08-17/18 mis-route guards, on the text the live model now actually reads: a spoken
     * workout, meal or grocery add must land on a write, and the read router must say it records
     * nothing (`LiveToolboxDispatchRoutingTest` still pins the routers' own source wording, which
     * now reaches their sub-agents only).
     */
    @Test
    fun `the live text keeps the write-versus-read routing guards`() {
        fun desc(name: String) = declared.first { it.getString("name") == name }.getString("description")
        val ask = desc("ask")
        assertTrue(ask, ask.contains("records NOTHING"))
        assertTrue(ask, ask.contains("body_log"))
        val goalsHint = LiveSurface.ACTION_TOOLS.first { it.name == "ask" }.actions.first { it.value == "ask_goals" }.hint
        for (word in listOf("workout", "meal", "sleep", "weigh")) assertFalse(goalsHint, word in goalsHint)
        assertTrue(desc("body_log").contains("ask_body"))
        assertTrue(desc("car").contains("ask_fleet"))
        val item = desc("manage_item")
        assertTrue(item, item.contains("manage_checklist list=Groceries") && item.contains("grocery"))
        assertTrue(desc("manage_checklist"), desc("manage_checklist").contains("Groceries"))
    }

    @Test
    fun `a moved rule rides on the result instead`() {
        val out = LiveSurface.withRule("stop_voice_note", JSONObject().put("success", true))
        assertTrue(out.getString("rule").contains("not transcribed yet"))
        val untouched = LiveSurface.withRule("navigate", JSONObject().put("success", true))
        assertFalse(untouched.has("rule"))
    }

    @Test
    fun `parcel and flight are instant actions that still mark the turn read-through`() {
        for (name in listOf("track_package", "flight_status")) {
            val r = LiveSurface.resolve("day", JSONObject().put("action", name))
            assertEquals(name, r.name)
            assertTrue(GeminiLiveSession.isEpisodicExcludedTool(r.name))
        }
    }

    @Test
    fun `the surface is about 15 core tools plus helpers`() {
        // Kevin 2026-11-10: "yes trim tools", about 15 always-loaded core tools plus helpers.
        val routers = setOf("ask", "look_up")
        val actionTools = LiveSurface.ACTION_TOOLS.filter { it.base == null }.map { it.name }.toSet()
        val direct = declaredNames - actionTools
        assertTrue("direct tools: $direct", direct.size <= 15)
        assertTrue(routers.all { it in declaredNames })
        assertTrue("declared: ${declaredNames.size}", declaredNames.size <= 24)
    }

    @Test
    fun `a folded read-through tool still marks the turn, because the call is resolved first`() {
        // get_sitrep is read-through; it is now day/get_sitrep. The session resolves before it
        // checks, so the real name is what the exclusion sees.
        val r = LiveSurface.resolve("day", JSONObject().put("action", "get_sitrep"))
        assertTrue(GeminiLiveSession.isEpisodicExcludedTool(r.name))
    }

    companion object {
        private const val TAB = '\t'
        private const val NEWLINE = '\n'

        /** The 93 top-level declarations on be8b7dbf (dev d3991d4f + voice-audit + alfred-email). */
        val BEFORE_DIET = listOf(
            "get_current_time", "clear_codes", "control_music", "control_volume", "end_conversation",
            "navigate", "change_trip", "trip_status", "end_trip", "play_music", "browse_my_music",
            "get_music_queue", "show_app", "set_reminder", "tag_place", "rename_place", "forget_place",
            "show_saved_places", "start_voice_note", "stop_voice_note", "read_voice_note",
            "list_voice_notes", "categorize_transactions", "set_category", "log_pending_transaction",
            "clear_pending_transaction", "import_receipt", "log_workout_set", "log_bodyweight",
            "log_meal", "set_meal_target", "set_budget", "list_budget_categories", "log_sleep",
            "set_sleep_target", "undo_last_log", "set_odometer", "log_service", "log_past_service",
            "set_maintenance_interval", "register_vehicle", "manage_item", "manage_checklist",
            "get_last_ticked", "read_list", "show_agenda_modal", "show_generated_view",
            "log_build_entry", "activate_garage", "get_current_location", "manage_vehicle",
            "track_package", "flight_status", "why_did_you_say_that", "get_sitrep", "answer_call",
            "decline_call", "place_call", "read_calendar", "complete_task", "set_goal", "close_goal",
            "generate_goal_plan", "accept_goal_plan", "accept_proposal", "email",
            "ask_fleet", "ask_body", "ask_goals", "ask_pantry", "ask_mail", "ask_money", "ask_area",
            "ask_engine",
            "list_aspects", "describe_aspect", "query_records", "create_record", "update_record",
            "delete_record", "aspect_clerk", "create_aspect", "update_aspect",
            "bought_log", "books", "browser", "claude_session", "app_update", "memory",
            "show_vault_item", "clock", "watch", "look",
        )
    }
}
