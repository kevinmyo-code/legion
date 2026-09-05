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
     * leaking in, and never a UI-scoped tool - `import_receipt`/
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
        // "show_groceries_modal" removed from this set (one-today ticket 10 slice B, 2026-09-05) -
        // it is no longer UI-scoped (dispatch() no longer returns null for it, see the retirement
        // test below) because it is retired outright, not because it moved behind a dispatcher.
        val uiScoped = setOf(
            "import_receipt", "show_saved_places",
            "show_agenda_modal", "show_list_modal",
        )
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

    /**
     * The grocery mis-route, 2026-08-18. Kevin said "I need to buy tums, add it to groceries list"
     * and it landed on the persistent list instead of the shopping trip.
     *
     * Nothing was broken in the sense of throwing: `manage_item` did exactly what it was asked. The
     * defect was in what the live model was TOLD. `manage_item` described itself as "the app's only
     * list ... never ask which list or mention lists in the plural", which is a flat claim that no
     * other list exists, and `manage_grocery` (this test's original grocery tool, since retired -
     * see below) was hidden behind `ask_pantry` so the model could not see it to know better.
     *
     * Same shape as the 2026-08-17 `ask_goals` mis-route (see that tool's own description): a tool
     * over-claiming its domain while the tool that actually owns the request is invisible. This test
     * is the tripwire for the copy, since no behavioural test can see a routing decision the model
     * makes before any of our code runs.
     *
     * **UPDATED one-today ticket 10 slice B, 2026-09-05: `manage_grocery` retired, `manage_checklist`
     * is the replacement destination** ("everything is a checklist now" - a shopping list is a
     * checklist named "Groceries"). The regression this test guards against is unchanged in shape:
     * `manage_item` must still name the real grocery destination and must still disclaim being the
     * app's only list, and `ask_pantry` must still hand the shopping list off by name rather than
     * re-claiming it.
     */
    @Test
    fun `the list tools tell the model where groceries actually go`() {
        val live = LiveToolbox.declarations()
        fun descriptionOf(name: String): String =
            (0 until live.length()).map { live.getJSONObject(it) }
                .first { it.getString("name") == name }
                .getString("description")

        // manage_grocery is gone - manage_checklist is declared directly instead, so the model can
        // still SEE the real grocery destination (the original fix's load-bearing half).
        assertFalse("manage_grocery must no longer be declared - it was retired", "manage_grocery" in names(live))
        assertTrue(
            "manage_checklist must be declared directly to the live session - a model cannot route " +
                "to a tool it cannot see, which is what the description-only fix failed to beat",
            "manage_checklist" in names(live),
        )

        val manageItem = descriptionOf("manage_item")
        assertTrue(
            "manage_item must name manage_checklist as the grocery destination, not claim to be " +
                "the only list there is",
            manageItem.contains("manage_checklist"),
        )
        assertTrue(
            "manage_item must say out loud that shopping is not its job",
            manageItem.contains("grocery", ignoreCase = true) &&
                manageItem.contains("shopping", ignoreCase = true),
        )
        assertFalse(
            "manage_item must not tell the model it is the app's ONLY list - it is the only " +
                "PERSISTENT one, and the claim is what sent tums to the wrong place",
            manageItem.contains("the app's only list"),
        )

        // ask_pantry went back to read-only when manage_grocery came out from behind it, and stays
        // read-only now that manage_checklist owns groceries instead. If it ever claims the
        // shopping list again there are two doors to the same place and the mis-route can return.
        val askPantry = descriptionOf("ask_pantry")
        assertTrue(
            "ask_pantry must say it records nothing now that it holds no mutating tool",
            askPantry.contains("Read-only", ignoreCase = true),
        )
        assertTrue(
            "ask_pantry must hand the shopping list to manage_checklist by name",
            askPantry.contains("manage_checklist"),
        )
    }

    /**
     * `manage_grocery`/`show_groceries_modal` retirement (one-today ticket 10 slice B, 2026-09-05):
     * neither is declared to the live session anymore, and a model that still calls either by name
     * (a cached old session, say) gets an explicit retirement message rather than the generic
     * "Unknown tool" [dispatch] would otherwise fall through to - §7's outcome-verb rule applied to
     * a retired capability.
     */
    @Test
    fun `manage_grocery and show_groceries_modal are retired, not silently unknown`() = kotlinx.coroutines.runBlocking {
        val live = names(LiveToolbox.declarations())
        assertFalse("manage_grocery must not be declared", "manage_grocery" in live)
        assertFalse("show_groceries_modal must not be declared", "show_groceries_modal" in live)

        for (name in listOf("manage_grocery", "show_groceries_modal")) {
            val result = LiveToolbox.dispatch(context, name, org.json.JSONObject())
            assertTrue("$name must return a non-null result even though it is retired", result != null)
            assertFalse("$name's retirement result must say it failed, not claim success", result!!.getBoolean("success"))
            assertTrue(
                "$name's retirement message must name manage_checklist as the replacement",
                result.getString("message").contains("manage_checklist"),
            )
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

    /**
     * The voice-called-modal tools (ADR 0040): each must be declared to the live session with no
     * required params, and [LiveToolbox.dispatch] must return null for both (the UI-scoped
     * contract - [LiveSessionController] owns showing the modal, same shape as
     * `show_saved_places`/`import_receipt`). Used to be three - `show_groceries_modal` retired
     * (one-today ticket 10 slice B, 2026-09-05), see the retirement test above.
     */
    @Test
    fun `the voice-modal tools are declared with no required params`() {
        val live = LiveToolbox.declarations()
        for (name in listOf("show_agenda_modal", "show_list_modal")) {
            val decl = (0 until live.length()).map { live.getJSONObject(it) }
                .first { it.getString("name") == name }
            assertEquals(
                "$name must take no parameters - it names a target, not content",
                0,
                decl.getJSONObject("parameters").optJSONArray("required")?.length() ?: 0,
            )
        }
    }

    @Test
    fun `dispatch returns null for both voice-modal tools`() = kotlinx.coroutines.runBlocking {
        for (name in listOf("show_agenda_modal", "show_list_modal")) {
            val result = LiveToolbox.dispatch(context, name, org.json.JSONObject())
            org.junit.Assert.assertNull(
                "$name is UI-scoped and must return null from dispatch() - the caller owns the screen",
                result,
            )
        }
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
                "triage_symptom", "list_build_history", "get_trend",
            ),
            "body" to listOf(
                "list_recent_meals", "get_meal_gap", "list_recent_sleep", "get_sleep_gap",
                "list_recent_workouts", "get_workout_gap", "get_health",
            ),
            "goals" to listOf("list_goals", "ask_advisor"),
            "pantry" to listOf("list_recent_groceries", "get_grocery_spend"),
            "mail" to listOf("search_mail", "read_mail"),
        )
    }
}
