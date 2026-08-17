package com.kevin.legion.service

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Guards WHICH declaration set each tool lands in - the failure this file exists for shipped, on
 * 2026-08-13, and no other test could see it.
 *
 * `set_goal`, `list_goals`, `close_goal`, `ask_advisor` and `accept_proposal` were all appended
 * inside [LiveToolbox.onboardingDeclarations] instead of [LiveToolbox.declarations] - the natural
 * mistake when you add a `fns.put(...)` block at the end of a 4,000-line file and the last one you
 * scrolled past happened to belong to the other function. Everything else about them was correct:
 * the handlers worked, [LiveToolbox.dispatch] routed them, and **thirteen unit tests passed**,
 * because every one of those tests called `dispatch` directly and never asked whether the live
 * session is ever TOLD the tool exists.
 *
 * On the device the effect was total and silent: Kevin asked what his goals were, and the
 * assistant - holding no `list_goals` declaration in a normal session - answered "I do not seem to
 * have any recorded goals for you, sir" while the row sat in Room. Onboarding, meanwhile, was
 * advertising five tools it has no dispatch path for.
 *
 * A tool is only real when it is BOTH dispatchable and reachable from the session that needs it -
 * EITHER declared directly, or, after the 2026-08-17 dispatcher split (see [LiveToolbox.DISPATCHED]'s
 * doc comment), by way of its domain's `ask_*` tool and [LiveToolbox.agentToolsFor]. That split is
 * also why this class now runs under Robolectric: [LiveToolbox.agentToolsFor] takes a real
 * [android.content.Context] to close over (never invoked while just listing tool names, but the
 * type still has to be satisfiable).
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxDeclarationSetTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun names(arr: JSONArray): Set<String> =
        (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }.toSet()

    /**
     * Every name [LiveToolbox.DISPATCHED] claims must vanish from the live declaration set, and
     * every one of the five dispatchers must appear in its place. This is the direct regression
     * guard for the whole ticket: the live `setup` message must never carry both a dispatched
     * tool's own declaration AND its dispatcher - that would spend tokens on the same capability
     * twice, and defeats trimming the block at all.
     */
    @Test
    fun `declarations hides every dispatched name and carries exactly the five dispatchers`() {
        val live = names(LiveToolbox.declarations())
        val allDispatchedNames = DISPATCHED_FOR_TEST.values.flatten().toSet()

        for (name in allDispatchedNames) {
            assertFalse(
                "$name is behind a dispatcher now and must NOT be declared directly to the live " +
                    "session - the setup message would be billing for it twice",
                name in live,
            )
        }
        for (dispatcher in listOf("ask_fleet", "ask_body", "ask_goals", "ask_pantry", "ask_mail")) {
            assertTrue("$dispatcher must be declared to the live session", dispatcher in live)
        }
    }

    /**
     * Every name [LiveToolbox.DISPATCHED] claims must resolve to a real declaration somewhere in
     * the full (un-filtered) set - this catches a typo'd name silently vanishing from BOTH the
     * live block and its dispatcher's own sub-agent at once, which nothing else here would see
     * (declarations() would just look "correctly" smaller, and agentToolsFor would just silently
     * return one fewer tool).
     */
    @Test
    fun `every DISPATCHED name resolves to a real declaration`() {
        for ((domain, toolNames) in DISPATCHED_FOR_TEST) {
            val resolved = LiveToolbox.agentToolsFor(domain, context).map { it.name }.toSet()
            for (name in toolNames) {
                assertTrue(
                    "\"$name\" is listed under domain \"$domain\" in DISPATCHED but agentToolsFor " +
                        "did not resolve it to a real declaration - likely a typo'd name, which " +
                        "would silently vanish from both the live block and the sub-agent",
                    name in resolved,
                )
            }
        }
    }

    /**
     * [LiveToolbox.agentToolsFor] must return a non-empty list for each of the five domains, and
     * every tool it hands back must actually belong to that domain (never another domain's tool
     * leaking in, and never a UI-scoped tool - `import_receipt`/`import_statement`/
     * `show_saved_places` all return null from [LiveToolbox.dispatch] because they need an
     * Activity a sub-agent doesn't have, so dispatching one from inside an investigate loop would
     * be a silent no-op).
     */
    @Test
    fun `agentToolsFor returns the right, non-empty tool set per domain`() {
        for ((domain, expectedNames) in DISPATCHED_FOR_TEST) {
            val tools = LiveToolbox.agentToolsFor(domain, context)
            assertTrue("agentToolsFor(\"$domain\", ...) must not be empty", tools.isNotEmpty())
            for (tool in tools) {
                assertTrue(
                    "agentToolsFor(\"$domain\", ...) returned \"${tool.name}\", which is not in " +
                        "that domain's own DISPATCHED list",
                    tool.name in expectedNames,
                )
            }
        }
        val uiScoped = setOf("import_receipt", "import_statement", "show_saved_places")
        for ((_, toolNames) in DISPATCHED_FOR_TEST) {
            for (name in uiScoped) {
                assertFalse(
                    "$name is UI-scoped (dispatch() returns null for it) and must never be in a " +
                        "DISPATCHED domain list - a sub-agent has no screen to hand it to",
                    name in toolNames,
                )
            }
        }
    }

    /** ask_mail's whole point is that mail never reaches the live socket by its real name again. */
    @Test
    fun `EPISODIC_EXCLUDED_TOOLS carries ask_mail alongside the original two names`() {
        assertTrue("ask_mail" in LiveToolbox.EPISODIC_EXCLUDED_TOOLS)
        assertTrue("search_mail" in LiveToolbox.EPISODIC_EXCLUDED_TOOLS)
        assertTrue("read_mail" in LiveToolbox.EPISODIC_EXCLUDED_TOOLS)
    }

    /**
     * Every tool the driver can reach mid-drive must be in the MAIN set - EITHER declared
     * directly, or reachable through its domain's dispatcher (2026-08-17, the ask_fleet/ask_body/
     * ask_goals/ask_pantry/ask_mail split - [LiveToolbox.DISPATCHED]'s doc comment). `set_goal`,
     * `close_goal`, and `accept_proposal` stayed direct declarations (lifecycle/consent tools);
     * `list_goals` and `ask_advisor` moved behind `ask_goals` and are proven reachable via
     * [LiveToolbox.agentToolsFor] instead of [LiveToolbox.declarations] directly.
     */
    @Test
    fun `goal and advisor tools are declared to the live session, directly or via ask_goals`() {
        val live = names(LiveToolbox.declarations())
        for (tool in listOf("set_goal", "close_goal", "accept_proposal")) {
            assertTrue(
                "$tool is dispatchable but NOT declared in declarations() - the live model will " +
                    "never know it exists, which is exactly how 'I do not seem to have any " +
                    "recorded goals' happened with the goal sitting in the database",
                tool in live,
            )
        }
        assertTrue("ask_goals must be declared so the live model can route goal/advisor questions to it", "ask_goals" in live)

        val goalAgentToolNames = LiveToolbox.agentToolsFor("goals", context).map { it.name }.toSet()
        for (tool in listOf("list_goals", "ask_advisor")) {
            assertTrue(
                "$tool moved behind ask_goals but is not in agentToolsFor(\"goals\", ...) - the " +
                    "dispatcher's own sub-agent would never know it exists either",
                tool in goalAgentToolNames,
            )
            assertTrue(
                "$tool moved behind ask_goals and must no longer be declared directly to the live session",
                tool !in live,
            )
        }
    }

    /**
     * `clear_codes` (`.scratch/hands-and-senses/issues/01-clear-dtc.md`, D10) is the exact shape
     * this file exists to catch: a destructive, confirm-gated tool that would be silently
     * dispatchable-but-invisible if it ever landed in the wrong array.
     */
    @Test
    fun `clear_codes is declared to the live session, not to onboarding`() {
        assertTrue("clear_codes must be declared() so the live model can call it", "clear_codes" in names(LiveToolbox.declarations()))
        assertTrue(
            "clear_codes must NOT be in onboardingDeclarations() - onboarding has no OBD write path",
            "clear_codes" !in names(LiveToolbox.onboardingDeclarations()),
        )
    }

    /**
     * Onboarding replaces the normal toolset rather than extending it, and its dispatch lives in
     * the onboarding screen, not [LiveToolbox.dispatch]. A tool that leaks in here is advertised
     * to a model that has no way to run it.
     */
    @Test
    fun `onboarding declares only its own five capture tools`() {
        val expected = setOf(
            "set_companion_name", "set_personality", "set_driver", "register_car", "finish_intro",
        )
        assertEquals(
            "onboardingDeclarations() must hold only the first-run capture tools - anything else " +
                "is either unreachable there or missing from the main set",
            expected,
            names(LiveToolbox.onboardingDeclarations()),
        )
    }

    /** No tool should be advertised twice, in both sets, under two different dispatch regimes. */
    @Test
    fun `the two declaration sets do not overlap`() {
        val overlap = names(LiveToolbox.declarations()) intersect names(LiveToolbox.onboardingDeclarations())
        assertTrue("a tool declared in both sets has two dispatch regimes: $overlap", overlap.isEmpty())
    }

    companion object {
        // A test-local mirror of LiveToolbox.DISPATCHED's grouping (that map itself is private -
        // deliberately, nothing outside LiveToolbox should ever hand-build a dispatched tool list).
        // Kept in exact sync with the plan's split by hand; `every DISPATCHED name resolves to a
        // real declaration` above is what actually proves each of these names is real, not this
        // list's mere existence.
        private val DISPATCHED_FOR_TEST: Map<String, List<String>> = mapOf(
            "fleet" to listOf(
                "read_vehicle_sensor", "get_vehicle_data", "get_codes", "diagnose_codes",
                "get_code_history", "check_readiness", "check_cold_start", "get_mpg", "get_specs",
                "lookup_vin", "check_recalls", "list_vehicles", "get_next_service", "ask_maintenance",
                "triage_symptom", "list_build_history", "get_trend", "log_service", "log_past_service",
                "log_build_entry",
            ),
            "body" to listOf(
                "list_recent_meals", "get_meal_gap", "list_recent_sleep", "get_sleep_gap",
                "list_recent_workouts", "get_workout_gap", "get_health", "log_meal", "log_sleep",
                "log_bodyweight", "log_workout_set", "create_workout_plan",
            ),
            "goals" to listOf("list_goals", "ask_advisor"),
            "pantry" to listOf("list_recent_groceries", "get_grocery_spend", "manage_grocery"),
            "mail" to listOf("search_mail", "read_mail"),
        )
    }
}
