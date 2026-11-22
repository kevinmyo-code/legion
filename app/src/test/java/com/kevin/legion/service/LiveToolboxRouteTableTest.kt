package com.kevin.legion.service

import com.kevin.legion.testutil.MainSource
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Characterization of where every tool name is dispatched (shrink pass, 2026-11-22), written
 * before any branch of [LiveToolbox.dispatch] moves to a toolbox of its own. A move keeps every
 * fact here true: each routed name keeps exactly one `when (name)` branch across the module
 * ([MainSource.LIVE_TOOLBOX]), and each split-out toolbox is asked from [LiveToolbox.dispatch]
 * before its big `when`. A branch lost, doubled, or left in a toolbox nothing calls fails here.
 *
 * It reads the source, so it pins the routing, not the handlers: the handlers keep their own tests.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxRouteTableTest {

    private fun names(arr: JSONArray): Set<String> =
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }.toSet()

    private fun declared(): Set<String> =
        names(LiveToolbox.sourceDeclarations()) +
            names(LiveToolbox.onboardingDeclarations()) +
            LiveToolbox.DISPATCHED.values.flatten()

    /** Every module file but LiveToolbox.kt itself: the toolboxes split out of it. */
    private fun splitFiles(): List<String> = MainSource.LIVE_TOOLBOX.drop(1)

    /** Every branch label of every `when (name)` that routes a tool, across the module. */
    private fun routeLabels(): List<String> {
        val toolbox = MainSource.read(MainSource.LIVE_TOOLBOX.take(1))
        val dispatch = toolbox.indexOf("    suspend fun dispatch(")
        assertTrue("LiveToolbox.dispatch not found", dispatch >= 0)
        val labels = branchLabels(toolbox, toolbox.indexOf("return when (name) {", dispatch)).toMutableList()
        for (file in splitFiles()) {
            val text = MainSource.read(listOf(file))
            var at = text.indexOf("when (name) {")
            assertTrue("$file routes nothing with when (name)", at >= 0)
            while (at >= 0) {
                labels += branchLabels(text, at)
                at = text.indexOf("when (name) {", at + 1)
            }
        }
        return labels
    }

    @Test
    fun `every routed name has exactly one branch across the module`() {
        val doubled = routeLabels().groupingBy { it }.eachCount().filterValues { it != 1 }
        assertEquals("names routed by more than one branch", emptyMap<String, Int>(), doubled)
    }

    @Test
    fun `every declared name not routed in the module is one another door owns`() {
        val routed = routeLabels().toSet()
        assertEquals(ROUTED_ELSEWHERE, declared() - routed)
    }

    @Test
    fun `the module routes only declared names, plus the retired ones it still answers`() {
        val routed = routeLabels().toSet()
        assertEquals(UNDECLARED_ROUTES, routed - declared())
    }

    @Test
    fun `every split-out toolbox is asked from dispatch before the big when`() {
        val toolbox = MainSource.read(MainSource.LIVE_TOOLBOX.take(1))
        val start = toolbox.indexOf("    suspend fun dispatch(")
        val whenAt = toolbox.indexOf("return when (name) {", start)
        val head = toolbox.substring(start, whenAt)
        for (file in splitFiles()) {
            val obj = file.substringAfterLast('/').removeSuffix(".kt")
            assertTrue("$obj is never asked from LiveToolbox.dispatch", head.contains("$obj.dispatch("))
        }
    }

    private companion object {
        /**
         * Declared, and routed outside the module: a sibling toolbox's own `dispatch` (engine
         * meta-tools, books, browser, clock, memory, ...), the voice session itself
         * (`end_conversation`, `look`), or the onboarding screen.
         */
        val ROUTED_ELSEWHERE = setOf(
            "app_update", "aspect_clerk", "books", "bought_log", "browser", "claude_session", "clock",
            "create_aspect", "create_record", "delete_record", "describe_aspect", "email", "end_conversation",
            "finish_intro", "get_energy_estimate", "get_exercise_alternatives", "get_todays_workout",
            "get_weight_trend", "get_workout_programme", "list_aspects", "list_saved_meals", "look", "memory",
            "query_records", "register_car", "set_companion_name", "set_driver", "set_personality",
            "show_vault_item", "suggest_meal", "update_aspect", "update_record", "watch",
        )

        /** Routed in the module but declared nowhere: retired tools that still answer by name. */
        val UNDECLARED_ROUTES = setOf(
            "manage_grocery", "recall_memory", "remember", "show_groceries_modal", "show_list_modal",
        )

        /**
         * The labels of the `when` whose `{` follows [whenAt]: the quoted names before each `->` at
         * the `when`'s own depth. Strings and line comments are skipped when counting depth.
         */
        fun branchLabels(text: String, whenAt: Int): List<String> {
            assertTrue("when (name) not found", whenAt >= 0)
            val labels = mutableListOf<String>()
            var depth = 1
            var i = text.indexOf('{', whenAt) + 1
            var lineStart = true
            while (i < text.length) {
                if (lineStart && depth == 1) {
                    LABEL.find(text.substring(i, lineEnd(text, i)))
                        ?.let { m -> labels += NAME.findAll(m.groupValues[1]).map { it.groupValues[1] } }
                }
                lineStart = false
                when (text[i]) {
                    '"' -> i = skipString(text, i)
                    '\'' -> i += if (text[i + 1] == '\\') 3 else 2
                    '/' -> when {
                        text.startsWith("//", i) -> i = lineEnd(text, i) - 1
                        text.startsWith("/*", i) -> i = text.indexOf("*/", i) + 1
                    }
                    '{', '(' -> depth++
                    '}', ')' -> if (--depth == 0) return labels
                    '\n' -> lineStart = true
                }
                i++
            }
            error("unbalanced when")
        }

        fun lineEnd(text: String, from: Int): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }

        /** Index of the closing quote of the string that opens at [at] (raw strings included). */
        fun skipString(text: String, at: Int): Int {
            if (text.startsWith("\"\"\"", at)) return text.indexOf("\"\"\"", at + 3) + 2
            var i = at + 1
            while (text[i] != '"') {
                if (text[i] == '\\') i++
                i++
            }
            return i
        }

        val LABEL = Regex("""^\s*((?:"[a-z_]+",?\s*)+)->""")
        val NAME = Regex(""""([a-z_]+)"""")
    }
}
