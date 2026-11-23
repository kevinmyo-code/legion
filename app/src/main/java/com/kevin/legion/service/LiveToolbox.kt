package com.kevin.legion.service

import com.kevin.legion.service.tools.DECLARATION_ORDER
import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.service.tools.declarationsByName
import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import android.content.Context
import android.content.Intent
import com.kevin.legion.MidnightEvents
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.bodycoach.BodyCoachText
import com.kevin.legion.ai.AgentTool
import com.kevin.legion.ai.AssistantIdentity
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineMcpClient
import com.kevin.legion.advisor.AdvisorAgent
import com.kevin.legion.advisor.HarnessPrompt
import com.kevin.legion.advisor.Priming
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.displayDescription
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.excludedOwnAccountMovementsSentence
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ledger.sameCard
import com.kevin.legion.ledger.uncategorizedExcludedSentence
import com.kevin.legion.pantry.PantryController
import com.kevin.legion.location.LocationController
import com.kevin.legion.util.documentDate
import com.kevin.legion.util.relativeAge
import com.kevin.legion.util.shortDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kevin.legion.location.ReminderController
import com.kevin.legion.backend.EventsAppointmentWriter
import com.kevin.legion.calendar.TaskDoneMatcher
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.backend.EventKind
import com.kevin.legion.calendar.DueReader
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.nextAppointmentId
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.PhoneCards
import com.kevin.legion.assistant.cards.ToolCards
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.gmail.GmailAuth
import com.kevin.legion.gmail.GmailClient
import com.kevin.legion.gmail.GmailToolLogic
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.service.tools.day.DateCarry
import com.kevin.legion.service.tools.day.EventRemoval
import com.kevin.legion.service.tools.day.ListRouting
import com.kevin.legion.service.tools.day.RelativeDay
import com.kevin.legion.checklists.ChecklistItemMatch
import com.kevin.legion.checklists.checklistScheduleLabel
import com.kevin.legion.checklists.matchChecklistItem
import com.kevin.legion.checklists.measureValueDisplay
import com.kevin.legion.checklists.TickHistoryController
import com.kevin.legion.checklists.TickMatch
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.MeasureDirection
import com.kevin.legion.notes.NotesController
import com.kevin.legion.ui.notes.buildInboxRows
import com.kevin.legion.ui.notes.ScheduleIntentResolver
import com.kevin.legion.notes.ItemMatch
import com.kevin.legion.notes.RepeatEnd
import com.kevin.legion.notes.RepeatRule
import com.kevin.legion.notes.formatWeekdays
import com.kevin.legion.notes.parseWeekdays
import com.kevin.legion.vehicle.DiagnosticAgent
import com.kevin.legion.vehicle.DtcDescriptions
import com.kevin.legion.vehicle.DtcLookup
import com.kevin.legion.vehicle.GarageController
import com.kevin.legion.vehicle.MaintenanceAgent
import com.kevin.legion.vehicle.SymptomAgent
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VehicleMatch
import com.kevin.legion.vehicle.VehicleResolver
import com.kevin.legion.vehicle.VehicleSpecController
import org.json.JSONArray
import org.json.JSONObject
import com.kevin.legion.data.local.ProactiveRaiseRow
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.sitrep.SitrepBuilder
import com.kevin.legion.sitrep.SitrepModule
import com.kevin.legion.voice.VoiceNoteController
import com.kevin.legion.voice.VoiceNoteStartResult

/**
 * The function tools the Live session exposes to Gemini, plus their dispatch.
 *
 * In the old text pipeline, the driver's transcript was routed through a chain
 * of local handlers (vehicle data, music, place tagging, "remember ...") before
 * ever reaching the cloud. With Gemini Live the audio goes straight to the
 * model, so those behaviors are preserved as function tools Gemini calls: the
 * model decides intent and supplies structured arguments, and we run the same
 * underlying actions and hand back a result.
 *
 * Data tools (live OBD values) return structured data for Gemini to phrase in
 * character. Action tools reuse the existing natural-language handlers via a
 * synthesized canonical phrase, so their tested regex/side-effect logic isn't
 * duplicated. The UI-scoped `show_saved_places` is dispatched by the caller
 * (it owns the screen), not here - [dispatch] returns null for it.
 */
object LiveToolbox {


    /**
     * The full, un-filtered declaration set - every tool this file knows how to build, dispatched
     * ones included. [declarations] (the one actually sent to the Live setup message) filters this
     * down; [agentToolsFor] slices it back out per domain for a dispatcher's own sub-agent. Kept
     * `private` - nothing outside this file should ever advertise the unfiltered set to a model.
     * (`internal` since 2026-10-27 only so `TextChatDeclarationSizeTest` can measure it against
     * [declarations]; no production caller outside this file sends it.)
     */
    internal fun allDeclarations(): JSONArray {
        // Architecture review 2026-11-10, item 8: the declarations live in
        // service/tools/<domain>/Declarations.kt now, emitted here in their pre-split order.
        val byName = declarationsByName()
        check(byName.keys == DECLARATION_ORDER.toSet()) { "DECLARATION_ORDER and the domain files disagree" }
        val fns = JSONArray()
        for (name in DECLARATION_ORDER) fns.put(byName.getValue(name))

        // fat-loss ticket 10: the body coach's three READ tools, hidden behind ask_body (DISPATCHED).
        val bodyCoachDecls = BodyCoachToolbox.declarations()
        for (i in 0 until bodyCoachDecls.length()) fns.put(bodyCoachDecls.getJSONObject(i))

        return fns
    }

    /**
     * Every dispatched tool name, grouped under the short domain key its dispatcher (`ask_fleet`/
     * `ask_body`/`ask_goals`/`ask_pantry`/`ask_mail`) hands to [agentToolsFor]. Single source of
     * truth for BOTH [declarations] (which must hide every name here from the live session) and
     * [agentToolsFor] (which hands each domain's own names to its sub-agent), so the two can never
     * disagree about who owns a tool.
     *
     * A misspelt entry here FAILS OPEN, not closed: the filter in [declarations] matches nothing,
     * so the real tool stays live-declared and simply never migrates behind its dispatcher, and
     * the sub-agent is handed a name [dispatch] does not know. The cost is a silently unshrunk
     * token block, not a lost capability - which is exactly why `every DISPATCHED name resolves to
     * a real declaration` is asserted in `LiveToolboxDeclarationSetTest` rather than left to
     * review.
     *
     * Gemini Live's `setup` message re-bills its whole tool-declaration block on every turn - there
     * is no mid-session tool update, no `toolConfig` on Live, and no context caching for Live
     * models - and Google's own guidance caps the active set at 10-20 tools. This moved five whole
     * domains (~25 tools) behind dispatchers that route to a Flash REST sub-agent instead, the same
     * shape [diagnoseCodes]/[triageSymptom]/[askMaintenance]/[checkColdStart] already used before
     * this ticket generalised it.
     *
     * Everything NOT listed here stays a live declaration on purpose, each for its own reason:
     * `clear_codes` (confirm/REFUSED protocol, unverified on a real car), `manage_vehicle`/
     * `register_vehicle`/`set_odometer`/`set_maintenance_interval` (identity/config the maintenance
     * math reads back), `activate_garage` (acts on the physical world), `set_goal`/`close_goal`/
     * `accept_proposal` (lifecycle plus an explicit-consent protocol), `set_meal_target`/
     * `set_sleep_target` (config the meters read), `import_receipt`/
     * `show_saved_places`/`show_agenda_modal` (UI-scoped -
     * [dispatch] returns null for these, and a sub-agent has no screen to hand them to, so
     * dispatching one from inside an investigate loop would be a silent no-op; `show_list_modal`
     * used to be a third example here but is RETIRED, one-today ticket 10 slice C, 2026-09-05 - its
     * own `dispatch` branch returns a named failure message now, never null), and every
     * money/notes/media/place/core tool.
     *
     * Internal, not private, so `LiveNonBlockingReadToolsTest` reads the REAL domain lists when it
     * proves [NON_BLOCKING_READ_TOOLS] holds no dispatcher that can reach a write (ADR 0065).
     */
    internal val DISPATCHED: Map<String, List<String>> = mapOf(
        // WRITES CAME BACK OUT, 2026-08-18 (Kevin's call, measured). Every tool below is a READ.
        // The eight write tools this map used to hide - log_service, log_past_service,
        // log_build_entry, log_meal, log_sleep, log_bodyweight, log_workout_set,
        // create_workout_plan - are declared directly to the live session again.
        //
        // Why writes specifically, and not the whole split: both mis-routes this app has actually
        // suffered were writes. Spoken workout sets reached `ask_goals`, which cannot log a set,
        // and the driver was told it was recorded (2026-08-17). "Add tums to the groceries list"
        // reached `manage_item` and landed on the persistent list (2026-08-18). A mis-routed READ
        // costs a slightly worse answer; a mis-routed WRITE either lands in the wrong store or gets
        // claimed and never happens, and no amount of description tuning beat a tool the model
        // could not see. Measured cost of bringing the eight back: about 1,374 tokens of the
        // roughly 5,700 the split saves, leaving most of the saving intact - the reads, which are
        // the bulk of the block and the half that was never implicated, stay hidden.
        //
        // Consequence, and it is deliberate: `fleet` and `body` now hold no mutating tool, so
        // [dispatchBoundaryClause] tells both sub-agents they can record NOTHING, and a write that
        // still mis-routes into one is refused out loud instead of answered around.
        "fleet" to listOf(
            "read_vehicle_sensor", "get_vehicle_data", "get_codes", "diagnose_codes",
            "get_code_history", "check_readiness", "check_cold_start", "get_mpg", "get_specs",
            "lookup_vin", "check_recalls", "list_vehicles", "get_next_service", "ask_maintenance",
            "triage_symptom", "list_build_history", "get_trend",
            // fat-loss ticket 16 (G4): the CAR's OBD health snapshot. It sat under "body" because of
            // its name, so "is the car okay" routed to the health specialist, which had no car.
            "get_health",
            // Architecture review 2026-11-10 item 7: the build sheet's spend total, a fleet read.
            "get_spend",
        ),
        "body" to listOf(
            "list_recent_meals", "get_meal_gap", "list_recent_sleep", "get_sleep_gap",
            "list_recent_workouts", "get_workout_gap",
            // fat-loss ticket 10: reads only (BodyCoachToolbox). The writes ride ask_engine.
            "get_energy_estimate", "get_weight_trend", "list_saved_meals",
            // workouts, 2026-11-06: today's workout from the engine's programme, a read.
            "get_todays_workout",
            // workout reads, 2026-11-06: the whole programme, Day A and B, and the sessions ahead.
            "get_workout_programme",
            // body coach 2, 2026-11-06: what to eat from what's left; alternatives to an exercise.
            "suggest_meal", "get_exercise_alternatives",
        ),
        "goals" to listOf("list_goals", "ask_advisor"),
        // manage_grocery came BACK OUT of this list on 2026-08-18 and was declared directly to the
        // live session again. Kevin said "I need to buy tums, add it to groceries list" and it
        // landed on the persistent list; sharpening manage_item's and ask_pantry's descriptions did
        // not fix it on a retry. The model cannot route to a tool it cannot see, and no wording on
        // the two tools it COULD see was going to beat a tool literally named for the list the
        // driver named. That costs one declaration's tokens on every session, which is exactly what
        // hiding it was buying - the trade was re-made deliberately, in favour of the write landing
        // in the right place. Consequence, and it is the point: `pantry` now holds no mutating tool
        // at all, so dispatchBoundaryClause tells its sub-agent in as many words that it can record
        // NOTHING, and a grocery add mis-routed to ask_pantry is refused out loud instead of being
        // answered around.
        //
        // **RETIRED one-today ticket 10 slice B, 2026-09-05.** manage_grocery itself is gone -
        // "add tums to the groceries list" now routes to manage_checklist (list=Groceries), which
        // is also declared directly (not hidden behind `pantry`), for the identical reason. `pantry`
        // still holds no mutating tool - list_recent_groceries/get_grocery_spend below read pantry
        // RECEIPTS, never the trip, and never did.
        "pantry" to listOf("list_recent_groceries", "get_grocery_spend"),
        "mail" to listOf("search_mail", "read_mail"),
        // Architecture review 2026-11-10 item 7 (Kevin, Q3: the payload is a cost/quality line,
        // fewer top-level tools, one extra hop accepted on moved READS). Reads only, so the
        // 2026-08-18 "writes stay direct" ruling above still holds: logging or clearing a pending
        // transaction, categorising and budgets stay declared to the live session, and so does
        // list_budget_categories, which set_category and set_budget tell the model to call first.
        "money" to listOf(
            "get_balance", "get_monthly_spend", "list_recent_transactions", "list_pending_transactions",
        ),
        "area" to listOf("area_info", "get_reported_crime_history"),
    )

    /**
     * The parameters every dispatcher tool below takes.
     *
     * `question` is the plain-English request routed to that domain's own sub-agent, which then
     * pulls whichever of the domain's real tools its reasoning needs via [agentToolsFor].
     *
     * `intent` (2026-08-18) is the explicit write-intent signal [agentResult]'s `requireMutation`
     * doc comment said this file was missing. [dispatch] previously saw nothing but free prose, so
     * it could not tell "log my three sets of squats" from "what did I lift last week" without
     * guessing, and the gate therefore shipped switched off. It is not a guess when the MODEL
     * declares it - the same shape `accept_proposal` already uses in requiring an explicit id
     * rather than trusting prose. Deliberately NOT in `required`: an omission degrades to exactly
     * today's behaviour (treated as a read, gate off) rather than failing the call, so a model
     * that ignores the field is never worse off than before this existed.
     */
    private val DISPATCHER_PARAMS: JSONObject
        get() = obj(
            "question" to schema(
                "string",
                "The user's question or instruction, in their own words.",
            ),
            "intent" to schema(
                "string",
                "\"record\" when the user is asking for something to be written down, logged, " +
                    "saved, or changed. \"ask\" when they only want information back. Set this " +
                    "honestly: a \"record\" call that writes nothing is reported to the user as " +
                    "a failure rather than answered around.",
                enum = listOf("record", "ask"),
            ),
        )

    /**
     * Function declarations to advertise in the Live setup message: [allDeclarations] minus every
     * name [DISPATCHED] claims, plus the five dispatcher tools that stand in for them. This is the
     * ~13,300-token, 79-tool block the setup message used to carry in full on every turn - Live
     * re-bills the whole thing every turn, so trimming it is the entire point of this split (see
     * [DISPATCHED]'s doc comment for why).
     */
    fun declarations(): JSONArray {
        // Live cost diet (voice cost audit 2026-11-10, F1): every candidate below, then
        // LiveSurface folds writes and instant reads into action tools. See LiveSurface.
        val candidates = surfaceCandidates()
        val all = allDeclarations()
        return LiveSurface.apply(
            (0 until candidates.length()).map { candidates.getJSONObject(it) },
            (0 until all.length()).map { all.getJSONObject(it) },
        )
    }

    /**
     * Every declaration as written in its own file, before [LiveSurface] folds them: the
     * candidates plus the router-hidden reads. Tests that pin a tool's own wording read it here;
     * for a folded tool that wording now reaches a router's sub-agent or nobody, and the live
     * model sees the action tool's hint instead (`LiveSurfaceTest` pins those).
     */
    internal fun sourceDeclarations(): JSONArray {
        val out = JSONArray()
        val seen = mutableSetOf<String>()
        for (arr in listOf(surfaceCandidates(), allDeclarations())) {
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                if (seen.add(d.getString("name"))) out.put(d)
            }
        }
        return out
    }

    /**
     * Every tool name the live model can reach: declared, an action of a declared action tool, or
     * behind a router.
     */
    internal fun reachableNamesForTest(): Set<String> {
        val live = declarations()
        val declared = (0 until live.length()).map { live.getJSONObject(it).getString("name") }.toSet()
        val folded = LiveSurface.ACTION_TOOLS.filter { it.name in declared }
            .flatMap { t -> t.actions.map { it.target } }
        return declared + folded + DISPATCHED.values.flatten()
    }

    /**
     * Everything that could be top-level, in order: [allDeclarations] minus [DISPATCHED], the
     * routers, and every sibling toolbox's declarations. [declarations] then applies
     * [LiveSurface].
     */
    // The pre-diet declarations() body, renamed (live cost diet): its LongMethod was baselined
    // under the old name.
    @Suppress("LongMethod")
    private fun surfaceCandidates(): JSONArray {
        val dispatchedNames = DISPATCHED.values.flatten().toSet()
        val all = allDeclarations()
        val fns = JSONArray()
        for (i in 0 until all.length()) {
            val decl = all.getJSONObject(i)
            if (decl.getString("name") !in dispatchedNames) fns.put(decl)
        }

        fns.put(fn(
            name = "ask_fleet",
            description = "READING anything about the cars: live sensor readings, trouble codes, " +
                "mileage, specs, recalls, service history, maintenance schedules, or the build " +
                "log. Read-only - it records NOTHING. Recording work that was done is the car " +
                "tool (log_service, log_past_service, log_build_entry), never here. Ask in the " +
                "user's words, naming only a car they named; raw results come back to relay. How the car is doing " +
                "or whether it is connected is answered only from this, never from memory.",
            params = DISPATCHER_PARAMS,
            required = listOf("question"),
        ))
        fns.put(fn(
            name = "ask_body",
            // fat-loss ticket 16 (G6): calorie, protein and maintenance questions route HERE (the
            // energy estimate is behind it), and the body-coach writes go to ask_engine. Reworded
            // token-neutral against LiveSetupPayloadSizeTest (-2 chars with the edits below).
            description = "READING BACK the user's own body and training: meals, sleep, workouts " +
                "and the programme (Day A/B, sessions), weight trend, waist, and estimates of " +
                "maintenance, calorie target and protein. Read-only - it records NOTHING. Anything the user just " +
                "ate, slept, lifted or weighed is a RECORD for body_log - log_meal, " +
                "log_sleep, log_workout_set, log_bodyweight - never here. Recording a " +
                "body profile, waist or saved meal, or starting, swapping or finishing a workout, " +
                "is ask_engine. Ask a " +
                "plain-English question; raw results come back to relay.",
            params = DISPATCHER_PARAMS,
            required = listOf("question"),
        ))
        fns.put(fn(
            name = "ask_goals",
            description = "The user's long-term goals and a domain advisor's coaching, and " +
                "nothing else: listing the goals currently set, or asking an advisor to reason " +
                "about progress. Records NOTHING: a workout, meal, sleep or weigh-in goes to " +
                "body_log (log_workout_set, log_meal, log_sleep, log_bodyweight). Workouts, sessions, the " +
                "programme, calories or protein go to " +
                "ask_body; groceries to manage_checklist; setting or closing a goal is " +
                "the goal tool. Ask a plain-English question; raw results come back to relay.",
            params = DISPATCHER_PARAMS,
            required = listOf("question"),
        ))
        fns.put(fn(
            name = "ask_pantry",
            description = "Grocery HISTORY and spending: items logged from past receipts with " +
                "their estimated macros. A month's grocery spend is ask_money. Read-only - it " +
                "records nothing and cannot touch the shopping list. Adding to, ticking off, or " +
                "reading back the CURRENT shopping list is manage_checklist (list=Groceries), " +
                "not this. Ask a plain-English question; raw results come back to relay.",
            params = DISPATCHER_PARAMS,
            required = listOf("question"),
        ))
        fns.put(fn(
            name = "ask_mail",
            description = "Anything about Kevin's Gmail: searching for messages or reading one in " +
                "full. Read-only. Ask a plain-English question; raw results come back to " +
                "relay.",
            params = DISPATCHER_PARAMS,
            required = listOf("question"),
        ))
        // Architecture review 2026-11-10 item 7: money and area READS behind routers.
        fns.put(fn(
            name = "ask_money",
            description = "READING money: account balances, budget left, spending this or another month " +
                "in total or by category (groceries too), recent transactions, and pending ones the user reported. " +
                "Read-only - logging or clearing a pending transaction, categorising and budgets " +
                "are the money tool. Ask a plain-English question; raw results come back to relay.",
            params = DISPATCHER_PARAMS,
            required = listOf("question"),
        ))
        fns.put(fn(
            name = "ask_area",
            description = "What official sources report around the user's current location: severe " +
                "weather alerts, earthquakes, wildfires, federal disaster declarations, and " +
                "reported crime history (which never answers 'is it safe'). Needs a GPS fix. " +
                "Read-only. Ask a plain-English question; raw results come back to relay.",
            params = DISPATCHER_PARAMS,
            required = listOf("question"),
        ))

        // engine-mcp ticket 11 (ruling 04, option C): the ONE declaration through which the
        // household engine's own tools (`/mcp`) reach the live session. Constant cost however many
        // tools the engine grows; see [AskEngine].
        fns.put(fn(
            name = "ask_engine",
            description = AskEngine.DESCRIPTION,
            params = obj(
                "question" to schema("string", "The user's question or instruction, in their own words."),
                "intent" to schema("string", AskEngine.INTENT_DESCRIPTION, enum = listOf("record", "ask")),
            ),
            required = listOf("question"),
        ))

        // The nine aspect-engine meta-tools (ticket 17, `EngineToolbox`) - declared directly,
        // never hidden behind DISPATCHED, since ticket 06's answer is that this surface is meant
        // to be seen by the live session itself, not routed through a domain dispatcher.
        val engineDecls = EngineToolbox.declarations()
        for (i in 0 until engineDecls.length()) fns.put(engineDecls.getJSONObject(i))

        // purchase-log ticket 08: the household bought log, one action-dispatched declaration.
        val purchaseDecls = PurchaseToolbox.declarations()
        for (i in 0 until purchaseDecls.length()) fns.put(purchaseDecls.getJSONObject(i))

        // books ticket 02: open a book in LEGION's own reader, one action-dispatched declaration.
        val booksDecls = BooksToolbox.declarations()
        for (i in 0 until booksDecls.length()) fns.put(booksDecls.getJSONObject(i))

        // voice-browser ticket 01 (ADR 0071): LEGION's own browser, one action-dispatched declaration.
        val browserDecls = BrowserToolbox.declarations()
        for (i in 0 until browserDecls.length()) fns.put(browserDecls.getJSONObject(i))
        // claude-session ticket 05: Kevin's Claude session on his laptops, one action-dispatched declaration.
        val claudeDecls = ClaudeSessionToolbox.declarations()
        for (i in 0 until claudeDecls.length()) fns.put(claudeDecls.getJSONObject(i))
        // alfred-ota ticket 06 (ADR 0072): LEGION's own updates, one action-dispatched declaration.
        val updateDecls = AppUpdateToolbox.declarations()
        for (i in 0 until updateDecls.length()) fns.put(updateDecls.getJSONObject(i))
        // memory-and-vault ticket 09 (ADR 0075): memory on the engine, one action-dispatched declaration.
        val memoryDecls = MemoryToolbox.declarations()
        for (i in 0 until memoryDecls.length()) fns.put(memoryDecls.getJSONObject(i))
        // memory-and-vault ticket 12 (ADR 0076): opens the ID vault screen; never carries a value.
        val vaultDecls = VaultToolbox.declarations()
        for (i in 0 until vaultDecls.length()) fns.put(vaultDecls.getJSONObject(i))
        // alfred-clock ticket 06 (ADR 0079): alarms, timers, stopwatch and bedtime, one action-dispatched declaration.
        val clockDecls = ClockToolbox.declarations()
        for (i in 0 until clockDecls.length()) fns.put(clockDecls.getJSONObject(i))
        // watches (ADR 0083): standing watches, one action-dispatched declaration.
        fns.put(WatchesToolbox.declarations().getJSONObject(0))
        // ADR 0084: "look at this", the phone camera into this session. The trust rules ride on
        // the result (LookRules.RULES), not here: this is re-billed on every reconnect.
        fns.put(fn(
            name = "look",
            description = com.kevin.legion.look.LookRules.TOOL_DESCRIPTION,
            params = obj(),
            required = listOf(),
        ))

        return fns
    }

    /**
     * Tools that steer the Live voice session itself and mean nothing to a typed turn on the text
     * model: `end_conversation` arms a hang-up after the spoken reply. It has no session to act on
     * in typed chat.
     */
    val VOICE_SESSION_TOOLS = setOf("end_conversation", "look")

    /**
     * What typed chat's text model is offered (2026-10-27): exactly the voice set
     * ([declarations]) minus [VOICE_SESSION_TOOLS]. The trimmed set, not [allDeclarations] with
     * the hidden reads exposed. Measured by `TextChatDeclarationSizeTest` (chars/4, Alfred): this
     * set 91 tools, ~20,842 tokens; the full alternative 117 tools, ~24,685 tokens, about 3,800
     * more (remeasured 2026-10-27 after the persona removal and the merge). Live re-bills its block every turn, but REST re-bills it on every POST, and a typed turn
     * that calls tools makes two to seven POSTs, so the saving that justified [DISPATCHED] for voice
     * applies here at least as strongly. Every write is declared directly in both sets (DISPATCHED
     * hides reads only), so the full set would buy one hop fewer on reads, not new capability. And
     * one tool surface for both modes is one surface to keep honest.
     */
    fun textDeclarations(): JSONArray {
        val all = declarations()
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val decl = all.getJSONObject(i)
            if (decl.getString("name") !in VOICE_SESSION_TOOLS) out.put(decl)
        }
        return out
    }

    /**
     * Whether this call asks for a WRITE: a tool in [MUTATING_TOOLS], or a dispatcher (`ask_*`,
     * `ask_engine`) the model called with `intent = "record"`. The typed chat engine tracks these
     * so a write is reported done only when its own result came back `success: true` that turn.
     */
    fun isMutatingCall(name: String, args: JSONObject): Boolean {
        // 2026-11-16: an unconfirmed clear_codes never sends a clear (it asks, or it reads when his
        // words only read: clearNotAsked), so a successful one is never a write done.
        if (name == "clear_codes" && !args.optBoolean("confirmed", false)) return false
        val dispatcher = name == "ask_engine" || name in DISPATCHED.keys.map { "ask_$it" }
        return name in MUTATING_TOOLS || (dispatcher && wantsWrite(args))
    }

    /**
     * Builds the [AgentTool] list a dispatcher's own [SubAgent.investigate] loop gets for
     * [domain] - the real declarations [DISPATCHED] hides from the live session, handed to a
     * sub-agent instead so it can pull exactly the ones its reasoning needs. Name/description/
     * params/required come straight off the same [allDeclarations] JSON the live model used to
     * see before this ticket, so the sub-agent reasons from the SAME descriptions, not a
     * re-authored summary that could drift from what [dispatch] actually does. Each tool's
     * [AgentTool.run] just replays the call through [dispatch] - the same function the live
     * session itself called directly before this ticket, so a dispatched tool's behavior is
     * unchanged, only who calls it and how the result gets back to the driver.
     */
    fun agentToolsFor(domain: String, context: Context): List<AgentTool> {
        val names = DISPATCHED[domain] ?: return emptyList()
        val byName = allDeclarations().let { all ->
            (0 until all.length()).map { all.getJSONObject(it) }.associateBy { it.getString("name") }
        }
        return names.mapNotNull { name ->
            val decl = byName[name] ?: return@mapNotNull null
            val params = decl.getJSONObject("parameters")
            val properties = params.optJSONObject("properties") ?: JSONObject()
            val requiredArr = params.optJSONArray("required") ?: JSONArray()
            val required = (0 until requiredArr.length()).map { requiredArr.getString(it) }
            AgentTool(
                name = name,
                description = decl.getString("description"),
                params = properties,
                required = required,
                timeoutMs = agentToolTimeoutMs(name),
                run = { args -> dispatch(context, name, args)?.toString() ?: "{}" },
            )
        }
    }

    /**
     * [tools] with `vehicle` filled in as [vehicleId] on any call that takes a `vehicle` argument
     * and was not given one (2026-11-03): the question named a car, so a tool the router called
     * without one must not fall back to another car. Unchanged when [vehicleId] is null.
     */
    internal fun withDefaultVehicle(tools: List<AgentTool>, vehicleId: String?): List<AgentTool> {
        if (vehicleId == null) return tools
        return tools.map { t ->
            if (!t.params.has("vehicle")) {
                t
            } else {
                AgentTool(t.name, t.description, t.params, t.required, t.timeoutMs) { args ->
                    if (args.optString("vehicle").isBlank()) args.put("vehicle", vehicleId)
                    t.run(args)
                }
            }
        }
    }

    /**
     * Per-tool leash inside a dispatcher's sub-agent (2026-10-27). A plain read gets
     * [AGENT_TOOL_TIMEOUT_MS]. A specialist in [SPECIALIST_TOOLS] runs its own Gemini call or loop
     * (investigate's 30 s budget, or one askTyped POST) and was cut at 8 s, so a slow specialist
     * always came back "timed out". It gets [SPECIALIST_TOOL_TIMEOUT_MS], above its own budget.
     *
     * The chain must nest, outermost last, or the outer leash fires first and the inner one is
     * dead code: [SPECIALIST_TOOL_TIMEOUT_MS] < [DISPATCH_ROUTE_BUDGET_MS] (route's whole budget:
     * its one POST plus the tools) < LiveSessionController.SUB_AGENT_TOOL_TIMEOUT_MS (45 s).
     * LiveToolboxAgentToolTimeoutTest pins the order.
     */
    internal fun agentToolTimeoutMs(name: String): Long =
        if (name in SPECIALIST_TOOLS || name in SLOW_READ_TOOLS) SPECIALIST_TOOL_TIMEOUT_MS else AGENT_TOOL_TIMEOUT_MS

    /**
     * Reads that talk to the car for longer than a plain PID (2026-11-15): `get_codes` with
     * `all_modules` waits up to [com.kevin.legion.vehicle.diag.DiagVoice.SCAN_WAIT_MS] for a module
     * scan. The engine-only read still answers in about a second, so the longer leash costs it nothing.
     */
    internal val SLOW_READ_TOOLS = setOf("get_codes")

    internal const val AGENT_TOOL_TIMEOUT_MS = 8_000L
    internal const val SPECIALIST_TOOL_TIMEOUT_MS = 32_000L
    internal const val DISPATCH_ROUTE_BUDGET_MS = 42_000L
    internal val SPECIALIST_TOOLS = setOf(
        "diagnose_codes", "triage_symptom", "ask_maintenance", "check_cold_start", "ask_advisor",
    )

    /**
     * [domain]'s own tool names, narrowed to the ones [MUTATING_TOOLS] says actually write - the
     * `mutatingToolNames` argument every `ask_<domain>` handler hands its [SubAgent.investigate]
     * call, so the loop's own [AgentResult.Success.mutatingToolsCalled] account is accurate for
     * THAT domain specifically (a domain like "goals" or "mail" whose [DISPATCHED] list contains
     * no mutating name at all correctly always gets back an empty list - there is nothing in its
     * tool set that could ever populate one, which is itself information: neither domain can write
     * anything through its dispatcher today, only through the separate live-declared lifecycle
     * tools [DISPATCHED]'s own doc comment names).
     */
    private fun mutatingToolsFor(domain: String): Set<String> =
        (DISPATCHED[domain] ?: emptyList()).toSet().intersect(MUTATING_TOOLS)

    /**
     * The clause every dispatcher grounding below ends with, derived from [domain]'s OWN
     * [mutatingToolsFor] set so it can never drift from what the sub-agent was actually handed.
     *
     * This is the structural half of the 2026-08-17 routing defect (`memory/MEMORY.md`, "START
     * HERE"). Kevin spoke workout sets; logcat proves the live model routed the request to
     * `ask_goals`, whose domain holds only `list_goals`/`ask_advisor` and cannot write a set under
     * any circumstances. The sub-agent answered in prose anyway, [agentResult] wrapped that prose
     * as `success: true`, and the driver was told it was recorded while nothing reached Room.
     * Sharpening the two tool descriptions makes that routing less likely; this clause makes the
     * LIE impossible even when routing still goes wrong, which is the part that has to hold.
     *
     * A domain with no writable tool at all (`goals`, `mail`) is told so in as many words and told
     * to refuse rather than answer around a request to record something. Every other domain is
     * told not to claim a write it did not perform. Neither replaces the mechanical gate - a
     * `record`-intent call that touches no mutating tool is refused by [successOrMutationRefusal]
     * regardless of what the sub-agent says - it just makes the prose match the refusal.
     */
    internal fun dispatchBoundaryClause(domain: String): String =
        if (mutatingToolsFor(domain).isEmpty()) {
            " You have NO tool that writes anything - you cannot log, record, save, or change any " +
                "data at all. If the user asked for something to be recorded, that request " +
                "reached the wrong specialist: say plainly that nothing was recorded and that you " +
                "cannot record it. Never say or imply something was logged, saved, recorded, or " +
                "updated."
        } else {
            " Never say or imply something was logged, saved, recorded, or updated unless you " +
                "actually called the tool that does it and it reported success. If the request " +
                "needs something none of your tools can do, say plainly that you did not do it " +
                "rather than answering around it."
        }

    // --- Dispatcher grounding clauses -------------------------------------
    //
    // Same shape as DiagnosticAgent/SymptomAgent/MaintenanceAgent's own `system(context)`
    // functions: AssistantIdentity's compressed sub-agent clause, plus what this particular
    // investigate loop is for and how to use the tools it's handed. Kept short - this text is
    // billed on every dispatcher call, not just once like the live setup block.
    //
    // Every one of them ends with Priming.dispatchClause, which is the empty string for a domain
    // that reads no doctrine (goals/pantry/mail today - see Priming.topicForDispatchDomain for why
    // each of those is a decision and not a hole) and the driver's own playbook plus the
    // basis-honesty clause for one that does. Appended to all five rather than only to the two
    // that are primed today so re-routing a domain is a one-line change in Priming, not a hunt
    // through this file - and so the token cost of a playbook is visible in exactly one place.
    // A primed domain is materially more expensive: the playbook rides EVERY model call in the
    // bounded investigate loop (up to four), not once per dispatch.

    private fun fleetDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are reasoning about the user's cars using the tools you're given: live sensor " +
        "readings, trouble codes, mileage, specs, recalls, service history, and maintenance " +
        "scheduling and logging. Pull only what would change your answer, then answer in plain " +
        "spoken text, no markdown." +
        dispatchBoundaryClause("fleet") +
        Priming.dispatchClause(context, "fleet")

    /**
     * What the fleet router must know before its ONE round (ticket 04 run 2, A25, 2026-10-28).
     * "Are there any trouble codes on the Jeep?" reached `ask_fleet`, and the router called only
     * `list_vehicles`: every live codes tool (`get_codes`, `diagnose_codes`,
     * `get_vehicle_data(trouble_codes)`) says it "cannot answer for any other car", and the router
     * had no way to know which car the dongle was in, or whether it was in any. [SubAgent.route]
     * gives it one POST, so the discovery call spent the round and the result held no codes.
     *
     * No fleet tool takes an id: the stored-record tools already take `vehicle` by name and resolve
     * it through [withResolvedVehicle], which is why "next service on the Jeep" worked in one
     * round. So this hands the router names, not ids: each car under the string that resolves
     * (the nickname, matched exactly by [VehicleResolver]'s first tier, else the year-make-model
     * spec, matched by its contains tier), which one is active, and where the dongle is. Appended
     * to the router's system instruction, not the question, so [SubAgent.frameRouteResults] still
     * echoes only the user's words. Billed on the router's REST call, not the Live setup payload.
     *
     * Pure so a JVM test pins it; the caller does the three reads `list_vehicles` does.
     */
    @Suppress("CyclomaticComplexMethod")
    internal fun fleetRouteContext(
        vehicles: List<Vehicle>,
        activeId: String?,
        connectedId: String?,
        namedId: String? = null,
    ): String {
        if (vehicles.isEmpty()) {
            return " No car is on file yet, so there is nothing to pass as `vehicle`."
        }
        fun key(v: Vehicle) = v.name.trim().ifBlank { VehicleController.identitySpec(v) }.ifBlank { v.obdMac }
        fun entry(v: Vehicle): String {
            val k = key(v)
            val spec = VehicleController.identitySpec(v)
            return if (spec.isBlank() || spec.equals(k, ignoreCase = true)) "\"$k\"" else "\"$k\" ($spec)"
        }
        val active = vehicles.firstOrNull { it.obdMac == activeId }
        val connected = connectedId?.let { id -> vehicles.firstOrNull { it.obdMac == id } }
        val dongle = when {
            connectedId == null ->
                "The OBD dongle is not connected to any car, so the live tools can read nothing right now."
            connected == null ->
                "The OBD dongle is plugged into a car that is not on file; the live tools read only that car."
            else ->
                "The OBD dongle is plugged into \"${key(connected)}\"; the live tools read only that car."
        }
        val named = namedId?.let { id -> vehicles.firstOrNull { it.obdMac == id } }
        val about = when {
            named != null -> " The question names \"${key(named)}\": pass that as `vehicle`."
            active != null -> " Currently driving: \"${key(active)}\"."
            vehicles.size > 1 && connectedId == null ->
                " No car is connected or picked; if the question names none, the tools ask which."
            else -> ""
        }
        return " Cars on file: ${vehicles.joinToString(", ") { entry(it) }}." + about +
            " $dongle For a car the dongle is not in, pass its quoted name as `vehicle` to the " +
            "stored-record tools (code history, service, specs, recalls, build log, trends) in this " +
            "same round. Every car is listed here, so call list_vehicles only when the question is " +
            "about the cars themselves."
    }

    /**
     * Body coach 2 (Kevin's rulings, 2026-11-06, `.scratch/body-coach-2/` ticket 12): coach-style
     * tone, what to eat from the engine's options, supplements, eating out and off days. Also in
     * [com.kevin.legion.advisor.playbooks.BioPlaybook]. A presence test guards it; obedience is
     * human-reviewed (CLAUDE.md section 7).
     */
    internal const val BODY_COACH_RULES =
        "Coach style: short, with the reason in one clause; give the full why only when asked for " +
            "more detail. What to eat is suggest_meal, and you suggest only from its options. Days " +
            "are flexible: never nudge about a missed or due workout. Supplements: only protein " +
            "powder and creatine, never fat burners. Eating out: suggest what to order with " +
            "estimated calories and protein, said as estimates. An off day gets one neutral factual " +
            "line, never guilt and never a word about the lapse itself. A sore joint: no diagnosis; " +
            "a swap through the plan, and a physio if it persists."

    private fun bodyDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are reasoning about the user's meals, sleep, workouts, and bodyweight using the " +
        "tools you're given. Calorie and macro figures are LLM estimates, never measured - always " +
        "phrase them as estimates, never as fact. Pull only what would change your answer, then " +
        "answer in plain spoken text, no markdown. " +
        // workout reads, 2026-11-06: a today-only read made the answer "there is no Day B".
        "Any question about the training programme beyond today - another day such as Day B, " +
        "the next workout, how many sessions a week, all the sessions - is get_workout_programme. " +
        "Never say a day or a session does not exist because a today-only read did not list it. " +
        BODY_COACH_RULES +
        // Phone audit 2026-11-19: a meal question was answered on a clock six hours out.
        " " + com.kevin.legion.util.LocalClock.turnLine() +
        dispatchBoundaryClause("body") +
        Priming.dispatchClause(context, "body")

    private fun goalsDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are reasoning about the user's long-term goals and, when asked, handing off to a " +
        "domain advisor for grounded coaching or planning advice using the tools you're given. " +
        "Pull only what would change your answer, then answer in plain spoken text, no markdown." +
        dispatchBoundaryClause("goals") +
        Priming.dispatchClause(context, "goals")

    private fun pantryDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are reasoning about the user's groceries using the tools you're given: recently " +
        "logged items and their estimated macros, total spend by currency, and the current " +
        "shopping trip list. Macro/calorie figures are estimates, never measured - phrase them " +
        "that way. Pull only what would change your answer, then answer in plain spoken text, no " +
        "markdown." +
        dispatchBoundaryClause("pantry") +
        Priming.dispatchClause(context, "pantry")

    // Architecture review 2026-11-10 item 7. The tools these two hand their sub-agent keep their
    // own full descriptions (the excluded-movements and uncategorised rules on get_monthly_spend,
    // the "never answers is it safe" rule on crime history), so the rules travel with the tool.
    /** The money specialist's own instruction, for its offline eval (`MoneySpecialistEvalCasesTest`). */
    internal fun moneyGroundingForTest(context: Context): String = moneyDispatchGrounding(context)

    private fun moneyDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are reading the user's money using the tools you're given: balances, budget left, " +
        "a month's spend in total or by category, recent transactions and pending ones. Budget " +
        "left (only when he names a budget or says spend or this month), one category or the top " +
        "categories is get_monthly_spend " +
        "with its view; a bare \"what's left\" is net cash, get_balance. Every amount carries its own " +
        "currency; keep it, never convert. Keep every exclusion and unverified note a tool " +
        "returns. Pull only what would change your answer, then answer in plain spoken text, no " +
        "markdown. " + com.kevin.legion.util.LocalClock.turnLine() +
        dispatchBoundaryClause("money") +
        Priming.dispatchClause(context, "money")

    private fun areaDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are checking official sources around the user's current location using the tools " +
        "you're given. Name each result's source as it names it. With no GPS fix, say the " +
        "location is unknown. Pull only what would change your answer, then answer in plain " +
        "spoken text, no markdown." +
        dispatchBoundaryClause("area") +
        Priming.dispatchClause(context, "area")

    private fun mailDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are searching and reading the user's Gmail using the tools you're given. " +
        "Read-only - you cannot send, reply to, or delete mail. Pull only what would change your " +
        "answer, then answer in plain spoken text, no markdown." +
        // 2026-11-05: "today" and "new" need the phone's day; see GmailToolLogic.routeDateClause.
        GmailToolLogic.routeDateClause(java.time.Instant.now(), java.time.ZoneId.systemDefault()) +
        dispatchBoundaryClause("mail") +
        Priming.dispatchClause(context, "mail")

    /**
     * Runs a tool call. Returns a JSON response to hand back to Gemini, or null
     * if the tool is UI-scoped (`show_saved_places`) and must be handled by the
     * caller that owns the screen.
     *
     * [touchedReadThroughToolThisTurn] (ticket 21, google-account-integration) is what the
     * "remember" branch below checks via [rememberBlockedByReadThroughTool] before writing
     * anything to permanent memory. Defaulted to `false` so every existing call site - the one
     * production caller that doesn't care ([LiveSessionController] passes the real value only
     * for its own dispatch call) and every test in this module that constructs its own args and
     * has nothing to do with mail - keeps compiling and behaving exactly as before. Only
     * [LiveSessionController.handleToolCall] ever passes `true`.
     */
    suspend fun dispatch(
        context: Context,
        name: String,
        args: JSONObject,
        touchedReadThroughToolThisTurn: Boolean = false,
        // What the user said (or typed) in the turn that called this tool - null when the caller
        // has no turn to read it from. clear_codes' yes-check and the date fix read it.
        userText: String? = null,
    ): JSONObject? {
        // Live cost diet (2026-11-10): an action tool's call runs as the tool it stands for. The
        // voice and typed paths resolve before they get here; this keeps any other caller honest.
        LiveSurface.resolve(name, args).let { r ->
            if (r.name != name) return dispatch(context, r.name, r.args, touchedReadThroughToolThisTurn, userText)
        }
        LiveSurface.unknownAction(name, args)?.let { return it }
        // A calendar-entry remove question waits only for the remove that answers it (EventRemoval).
        if (name != "manage_item" || !isRemove(args)) EventRemoval.reset()
        // His own day word wins over a date one day off it, for the calendar and task tools, on the
        // date the voice turn was given (RelativeDay.corrected; 2026-11-17 evening: "and tomorrow?"
        // read today+2); a dated write that names no day goes on the conversation's day
        // (DateCarry.carried, 2026-11-22). Only a voice turn carries TurnDates.
        val turnDates = kotlin.coroutines.coroutineContext[RelativeDay.TurnDates]
        turnDates?.let { DateCarry.resolved(name, args, userText, it) }?.let { (fixed, note) ->
            return dispatch(context, name, fixed, touchedReadThroughToolThisTurn, userText)?.put("date_note", note)
        }
        // The adapter's car is unknown or only recognised: a live read still runs (its readings are held,
        // not filed), and its result carries the ask or the "looks like" once a connection (AdapterBinding).
        // The fleet specialist's answer carries it too: "how's the car doing" goes there, and the
        // specialist's own live reads run inside this mark, so only the answer he hears is noted. Only
        // a voice turn (it carries TurnDates) gets it: a background read never uses up the ask (A9).
        val carNoted = name in CATEGORY_A_TOOLS && name != "clear_codes" || name == "ask_fleet"
        val heard = turnDates != null
        if (carNoted && heard &&
            kotlin.coroutines.coroutineContext[com.kevin.legion.vehicle.AdapterBinding.Annotating] == null
        ) {
            return kotlinx.coroutines.withContext(com.kevin.legion.vehicle.AdapterBinding.Annotating()) {
                dispatch(context, name, args, touchedReadThroughToolThisTurn, userText)
            }?.let { com.kevin.legion.vehicle.AdapterBinding.annotate(context, it) }
        }
        MidnightEvents.toolDispatched(name)
        // Category A guard (ticket 01 §0): if a live-hardware tool got handed a
        // `vehicle` argument anyway (the model shouldn't, since these declare no
        // such parameter, but nothing enforces that at the wire level) and it
        // doesn't name the car the dongle is actually in, refuse before ever
        // touching the connected car's real readings. Category B tools are
        // exempt - they resolve their own `vehicle` argument below.
        com.kevin.legion.vehicle.AdapterBinding.unnamedCarWriteRefusal(name, args.optString("vehicle"))
            ?.let { return result(false, it) }
        if (name in CATEGORY_A_TOOLS) {
            refuseIfNotConnectedCar(context, args)?.let { return it }
        }
        // The nine aspect-engine meta-tools (ticket 17) live in their own file; try them first so
        // this already-7,000-line `when` never has to grow their branches too.
        EngineToolbox.dispatch(context, name, args)?.let { return it }
        PurchaseToolbox.dispatch(context, name, args)?.let { return it }
        BooksToolbox.dispatch(context, name, args)?.let { return it }
        BrowserToolbox.dispatch(context, name, args)?.let { return it }
        ClaudeSessionToolbox.dispatch(context, name, args, touchedReadThroughToolThisTurn, userText)?.let { return it }
        AppUpdateToolbox.dispatch(context, name, args)?.let { return it }
        MemoryToolbox.dispatch(context, name, args, touchedReadThroughToolThisTurn)?.let { return it }
        VaultToolbox.dispatch(context, name, args)?.let { return it }
        ClockToolbox.dispatch(context, name, args)?.let { return it }
        WatchesToolbox.dispatch(context, name, args, touchedReadThroughToolThisTurn)?.let { return it }
        // ADR 0086: draft, open and send email; send is gated on the user's own words (userText).
        EmailToolbox.dispatch(context, name, args, userText)?.let { return it }
        // ADR 0085: a manage_item add/remove naming a proposed to-do answers it; null otherwise.
        OpenLoopsToolbox.dispatch(context, name, args, touchedReadThroughToolThisTurn)?.let { return it }
        BodyCoachToolbox.dispatch(context, name, args)?.let { return it }
        PlacesToolbox.dispatch(context, name, args)?.let { return it }
        BodyLogToolbox.dispatch(context, name, args, userText)?.let { return it }
        BodyReadToolbox.dispatch(context, name, args)?.let { return it }
        VehicleBuildToolbox.dispatch(context, name, args)?.let { return it }
        VehicleManageToolbox.dispatch(context, name, args)?.let { return it }
        VehicleRecordsToolbox.dispatch(context, name, args)?.let { return it }
        VehicleServiceToolbox.dispatch(context, name, args)?.let { return it }
        VehicleHealthToolbox.dispatch(context, name, args)?.let { return it }
        VehicleSensorToolbox.dispatch(context, name, args)?.let { return it }
        VehicleCodesToolbox.dispatch(context, name, args, userText)?.let { return it }
        AdvisorToolbox.dispatch(context, name, args)?.let { return it }
        GoalsToolbox.dispatch(context, name, args, touchedReadThroughToolThisTurn)?.let { return it }
        MusicControlToolbox.dispatch(context, name, args)?.let { return it }
        MusicLibraryToolbox.dispatch(context, name, args)?.let { return it }
        MusicToolbox.dispatch(context, name, args)?.let { return it }
        return when (name) {
            "get_current_time" -> getCurrentTime()
            // Router mode (2026-10-27): each dispatcher makes ONE sub-agent POST that picks
            // tools, runs them, and hands their raw results to the live model to phrase
            // (SubAgent.route). No second POST composes prose; that round was most of the wait.
            // Safe because every DISPATCHED domain is read-only by construction.
            //
            // The five dispatchers (see DISPATCHED's doc comment): each hands a bounded
            // sub-agent only the real tools its own domain needs (agentToolsFor), so a
            // dispatched tool's underlying behavior - including the CATEGORY_A_TOOLS guard above,
            // which still runs because these AgentTools call back into this same dispatch() - is
            // completely unchanged from before this ticket. Only who calls it, and how the result
            // gets back to the driver, changed.
            // The model's own declared write-intent (DISPATCHER_PARAMS' `intent`), not a guess
            // made from the question prose - see agentResult's requireMutation doc comment for
            // why this file refused to guess it. Absent or unrecognised reads as a plain read,
            // which is exactly the pre-2026-08-18 behaviour.
            "ask_fleet" -> fleetByCode(context, args) ?: fleetNotOnFile(context, args) ?: agentResult(
                "Could not reach the fleet specialist. Try again shortly.",
                requireMutation = wantsWrite(args),
                lookupName = "fleet lookup",
            ) {
                probedRoute(name) { probe ->
                    // The router gets one round, so it is told which cars exist and where the
                    // dongle is before it picks (ticket 04 run 2): see fleetRouteContext.
                    // 2026-11-03: the car the question names wins, then pickDefault's order, and the
                    // named car rides into every tool that takes `vehicle` even if the router omits it.
                    val roster = com.kevin.legion.vehicle.FleetEngineStore.getAll(context)
                    val question = args.optString("question")
                    val named = VehicleResolver.mentionedIn(question, roster)
                    val pick = VehicleResolver.pickDefault(
                        roster,
                        connectedId = ActiveVehicle.connectedCarId(context),
                        selectedId = ActiveVehicle.selected(context),
                        lastConnectedId = ActiveVehicle.lastConnected(context),
                    )
                    val fleetLine = fleetRouteContext(
                        roster,
                        (pick as? VehicleMatch.Resolved)?.vehicle?.obdMac,
                        ActiveVehicle.connectedCarId(context),
                        namedId = named?.obdMac,
                    )
                    SubAgent(systemInstruction = fleetDispatchGrounding(context) + fleetLine).route(
                        question = question,
                        tools = withDefaultVehicle(agentToolsFor("fleet", context), named?.obdMac),
                        budgetMs = DISPATCH_ROUTE_BUDGET_MS,
                        mutatingToolNames = mutatingToolsFor("fleet"),
                        probe = probe,
                    )
                }
            }
            "ask_body" -> askBody(context, name, args)
            // Coach voice fixes (2026-11-06): "how about my workouts?" reached ask_goals and came
            // back with an old goal. A training question is the body route's, whatever was called.
            "ask_goals" -> if (CoachRouting.isWorkoutQuestion(args.optString("question"))) {
                askBody(context, "ask_body", args)
            } else {
                agentResult(
                    "Could not reach the goals specialist. Try again shortly.",
                    requireMutation = wantsWrite(args),
                    lookupName = "goals lookup",
                ) {
                    probedRoute(name) { probe ->
                        SubAgent(systemInstruction = goalsDispatchGrounding(context)).route(
                            question = args.optString("question"),
                            tools = agentToolsFor("goals", context),
                            budgetMs = DISPATCH_ROUTE_BUDGET_MS,
                            mutatingToolNames = mutatingToolsFor("goals"),
                            probe = probe,
                        )
                    }
                }
            }
            "ask_pantry" -> agentResult(
                "Could not reach the grocery specialist. Try again shortly.",
                requireMutation = wantsWrite(args),
                lookupName = "grocery lookup",
            ) {
                probedRoute(name) { probe ->
                    SubAgent(systemInstruction = pantryDispatchGrounding(context)).route(
                        question = args.optString("question"),
                        tools = agentToolsFor("pantry", context),
                        budgetMs = DISPATCH_ROUTE_BUDGET_MS,
                        mutatingToolNames = mutatingToolsFor("pantry"),
                        probe = probe,
                    )
                }
            }
            "ask_mail" -> agentResult(
                "Could not reach your mail. Try again shortly.",
                requireMutation = wantsWrite(args),
                lookupName = "mail lookup",
            ) {
                probedRoute(name) { probe ->
                    SubAgent(systemInstruction = mailDispatchGrounding(context)).route(
                        question = args.optString("question"),
                        tools = agentToolsFor("mail", context),
                        budgetMs = DISPATCH_ROUTE_BUDGET_MS,
                        mutatingToolNames = mutatingToolsFor("mail"),
                        probe = probe,
                    )
                }
            }
            "ask_money" -> agentResult(
                "Could not reach the money specialist. Try again shortly.",
                requireMutation = wantsWrite(args),
                lookupName = "money lookup",
            ) {
                probedRoute(name) { probe ->
                    SubAgent(systemInstruction = moneyDispatchGrounding(context)).route(
                        question = args.optString("question"),
                        tools = agentToolsFor("money", context),
                        budgetMs = DISPATCH_ROUTE_BUDGET_MS,
                        mutatingToolNames = mutatingToolsFor("money"),
                        probe = probe,
                    )
                }
            }
            "ask_area" -> agentResult(
                "Could not reach the area specialist. Try again shortly.",
                requireMutation = wantsWrite(args),
                lookupName = "area lookup",
            ) {
                probedRoute(name) { probe ->
                    SubAgent(systemInstruction = areaDispatchGrounding(context)).route(
                        question = args.optString("question"),
                        tools = agentToolsFor("area", context),
                        budgetMs = DISPATCH_ROUTE_BUDGET_MS,
                        mutatingToolNames = mutatingToolsFor("area"),
                        probe = probe,
                    )
                }
            }
            "ask_engine" -> askEngine(context, args, touchedReadThroughToolThisTurn)
            "show_app" -> showApp(context)
            // set_reminder/tag_place/forget_place/register_vehicle: success DERIVED from the
            // controller's own outcome, never hardcoded. All four used to pass `success = true`
            // over a `String` that was sometimes a refusal ("I need both a place and what to
            // remind you about", "I don't have a GPS lock yet", "I don't have a saved place called
            // X", "I need a valid year, make, and model") - the same shape the `remember` blank
            // guard had, found on the A25 2026-09-07 and swept the same day.
            "set_reminder" ->
                ReminderController.add(context, args.optString("place"), args.optString("text"))
                    .let { result(it.success, it.message) }
            "start_voice_note" -> startVoiceNote(context, args)
            "stop_voice_note" -> stopVoiceNote(context)
            "read_voice_note" -> readVoiceNote(context, args)
            "list_voice_notes" -> listVoiceNotes(context, args)
            // Ticket 21 (google-account-integration, "close the remember leak"): refuse in words
            // rather than silently stripping or quietly recording provenance - the mail
            // read-through rule (CLAUDE.md §7, ticket 07) is written as absolute ("mail is read,
            // used, dropped"), and a refusal is the only implementation consistent with that. See
            // rememberBlockedByReadThroughTool's doc for why this checks a pre-reduced boolean
            // rather than re-testing tool names here.
            // Retired by memory-and-vault ticket 09 (the `memory` tool, MemoryToolbox) - named here,
            // never left to the generic "Unknown tool" branch, so a model that still calls it by
            // name is told in words what replaced it and that nothing happened. Its read twin,
            // recall_memory, kept the same shim until Kevin ruled it deleted (shrink pass,
            // 2026-11-22): declared nowhere, it gets the plain unknown-tool answer.
            "remember" -> result(
                success = false,
                message = "Nothing was saved: remember is now the memory tool with action=remember.",
            )
            // Absorbed the retired add_car_task/complete_car_task/remove_car_task/list_car_tasks
            // (ticket 10) - car items are now just items on the list named "Car".
            // remove reaches calendar entries too, asked first (2026-11-22, EventRemoval).
            // schedule moves one, and an add right after one may be a correction to it (EventMove).
            "manage_item" -> nameIt(
                removeCalendarEntry(context, args, userText) ?: moveCalendarEntry(context, args, userText)
                    ?: manageItem(context, args),
                args,
            )
            "read_list" -> readList(context)
            // "manage_grocery" retired one-today ticket 10 slice B, 2026-09-05 - named explicitly
            // (never falling through to the generic "Unknown tool" branch below) so a model that
            // still calls it by name is told in words what replaced it, §7's outcome-verb rule
            // applied to a retired capability rather than an unsuccessful one.
            "manage_grocery" -> result(
                false,
                "manage_grocery was retired - the shopping list is a checklist named \"Groceries\" " +
                    "now, managed through manage_checklist.",
            )
            "manage_checklist" -> nameIt(manageChecklist(context, args), args)
            "get_last_ticked" -> getLastTicked(context, args.optString("item"))
            "categorize_transactions" -> categorizeTransactions(context)
            "set_category" -> setCategory(context, args)
            "get_balance" -> getLedgerBalance(context, args.optString("account"))
            "list_recent_transactions" -> listRecentTransactions(context, args.optInt("count", 10))
            "log_pending_transaction" -> logPendingTransaction(context, args)
            "list_pending_transactions" -> listPendingTransactions(context)
            "clear_pending_transaction" -> clearPendingTransaction(context, args.optString("description"))
            "list_recent_groceries" -> listRecentGroceries(context, args.optInt("count", 10))
            "get_grocery_spend" -> getGrocerySpend(context)
            "set_budget" -> setBudget(context, args)
            "list_budget_categories" -> listBudgetCategories(context)
            "get_monthly_spend" -> com.kevin.legion.service.tools.money.BudgetTool.viewOf(args)
                ?.let { com.kevin.legion.service.tools.money.BudgetTool.answer(context, it, args) }
                ?: getMonthlySpend(context, args.optString("month"))
            "search_mail" -> searchMail(context, args)
            "read_mail" -> readMail(context, args)
            "track_package" -> trackPackage(context)
            "flight_status" -> flightStatus(context)
            "read_calendar" -> readCalendar(context, args)
            "complete_task" -> nameIt(
                completeTask(context, args, onNoMatch = {
                    tickReminderOrTodo(context, args.optString("title"), args.optBoolean("done", true))
                }),
                JSONObject().put("action", "tick"),
            )
            "why_did_you_say_that" -> whyDidYouSayThatTool(context, args)
            "get_sitrep" -> getSitrepTool(context, args)
            "answer_call" -> answerCallTool(context, args)
            "decline_call" -> declineCallTool(context)
            "place_call" -> placeCallTool(context, args)
            // Session-scoped tools the owning controller handles (it has the live
            // session / capture controller / activity), so dispatch returns null:
            "show_saved_places" -> null // caller launches the saved-places screen and replies
            "import_receipt" -> null // caller launches the receipt-import screen and replies
            "show_agenda_modal" -> null // caller shows the voice-called modal and replies
            // "show_list_modal" retired one-today ticket 10 slice C, 2026-09-05 - named explicitly
            // for the same reason "manage_grocery" (above) and "show_groceries_modal" (below) are:
            // a retired UI-scoped tool must still say so in words rather than falling through to
            // "Unknown tool". The persistent list itself is gone (`ui/NotesScreen.kt`) - a reminder
            // lives on the calendar day view now, a plain to-do is a checklist line.
            "show_list_modal" -> result(
                false,
                "show_list_modal was retired - the persistent list is gone. A reminder can be " +
                    "edited from the calendar day view now, or read back with show_agenda_modal; a " +
                    "plain to-do is a checklist line, managed through manage_checklist.",
            )
            // "show_groceries_modal" retired one-today ticket 10 slice B, 2026-09-05 - named
            // explicitly for the same reason "manage_grocery" is above: a retired UI-scoped tool
            // must still say so in words rather than falling through to "Unknown tool".
            "show_groceries_modal" -> result(
                false,
                "show_groceries_modal was retired - the shopping list is a checklist named " +
                    "\"Groceries\" now, managed through manage_checklist.",
            )
            "show_generated_view" -> null // caller validates the spec, runs it, and replies
            else -> result(success = false, message = "Unknown tool: $name")
        }
    }

    /**
     * Every category A (live-hardware) tool name (ticket 01 §0) - the set
     * [dispatch] guards with [refuseIfNotConnectedCar] before running.
     */
    private val CATEGORY_A_TOOLS = setOf(
        "get_vehicle_data", "get_health", "check_readiness", "get_codes",
        "diagnose_codes", "triage_symptom", "check_cold_start", "clear_codes",
    )

    /**
     * Every tool name whose [dispatch] branch WRITES - inserts, updates, or deletes a Room row, or
     * (`activate_garage`) actuates a real physical device. Named explicitly rather than inferred
     * from a prefix at call time (2026-08-17, defect trace: `ai/SubAgent.kt`'s investigate loop
     * could report success on an `ask_<domain>` dispatch that never actually wrote anything - see
     * [agentResult]'s `requireMutation` doc comment). An explicit set survives a future tool being
     * named `save_x` or `note_y` that a `log_*`/`set_*` prefix check would miss, and is the one
     * place a reviewer can check "is this tool a write" without reading its whole branch.
     *
     * CATEGORY_A_TOOLS above answers a different question (does this tool touch the connected
     * car's LIVE hardware) - `clear_codes` is in both sets for two independent reasons, and several
     * tools below are in this one only.
     *
     * `ask_fleet`/`ask_body`/`ask_goals`/`ask_pantry`/`ask_mail` themselves are deliberately NOT
     * listed - a dispatcher is a router, not a write; whether ONE PARTICULAR call into it wrote
     * anything is exactly what [agentToolsFor]'s `mutatingToolNames` argument (built by intersecting
     * this set with a domain's own [DISPATCHED] list) tells [SubAgent.investigate] to track.
     * `show_saved_places`/`import_receipt` are UI-scoped hand-offs - [dispatch]
     * itself writes nothing for them, the screen it launches does, downstream of this file entirely.
     */
    /** Whether [name] writes (see [MUTATING_TOOLS]): a cancelled call's outcome must then be told. */
    internal fun isMutatingTool(name: String): Boolean = name in MUTATING_TOOLS

    private val MUTATING_TOOLS = setOf(
        "clear_codes", "set_reminder", "tag_place", "rename_place", "forget_place", "set_odometer", "log_service",
        "log_past_service", "set_maintenance_interval", "register_vehicle",
        // memory-and-vault ticket 09: `memory` replaced `remember`; remember and forget write.
        "memory",
        // "manage_grocery" removed (one-today ticket 10 slice B, 2026-09-05) - its dispatch branch
        // no longer writes anything, only returns the retirement message.
        "manage_item", "manage_checklist", "log_build_entry", "activate_garage",
        "categorize_transactions", "set_category", "log_pending_transaction",
        "clear_pending_transaction", "log_workout_set", "log_bodyweight",
        "log_meal", "set_meal_target", "set_budget", "log_sleep", "set_sleep_target",
        "undo_last_log", "manage_vehicle", "set_goal", "close_goal", "accept_proposal",
        // goal-plans ticket 03: generate_goal_plan writes NOTHING about the plan itself (it is a
        // proposal - see its own declaration), but DOES write a member memory (2026-11-09; a
        // CompanionMemory row before) when new_constraint is given, the same reason "remember" is
        // in this set above.
        // accept_goal_plan is a real write - the workout piece of an accepted plan.
        "generate_goal_plan", "accept_goal_plan",
        // voice-notes ticket 04: start/stop each write a `voice_notes` row (insert, then update on
        // stop) through VoiceNoteController - see that object's own class doc.
        "start_voice_note", "stop_voice_note",
        // 2026-10-03: writes Event.done through EventsAppointmentWriter.setDone.
        "complete_task",
        // purchase-log ticket 08: action=log writes a bought entry on the engine.
        "bought_log",
        // claude-session ticket 05: send/answer/assign/send_file create or change a task on the engine.
        "claude_session",
        // alfred-ota ticket 06: action=install hands the APK to PackageInstaller.
        "app_update",
        // ADR 0086: draft writes a Gmail draft, send sends it.
        "email",
    )

    /**
     * Live declarations stamped `behavior: NON_BLOCKING` (ADR 0065, async-lookups ticket 01/02,
     * Kevin 2026-10-28): the model keeps talking while these run, and the result comes back with
     * `scheduling: WHEN_IDLE`. Every other declaration stays BLOCKING
     * ([GeminiLiveSession.withToolBehavior]). Empty on the wire when the Settings switch is off.
     *
     * **Only a read may be here.** The 2026-09-27 all-blocking ruling exists because a write could
     * be called "done" before its result existed. A read has no outcome to misreport; its risk is a
     * fact spoken early, and `LOOKUP_HOLD_CLAUSE` in `ai/AriaBrain.kt` closes that in the prompt.
     *
     * **What counts as a write (Kevin, 2026-10-28, ADR 0065):** a user-visible record change the
     * assistant could claim as an outcome. Internal bookkeeping is not one. A dispatcher may be here
     * when nothing behind it makes such a change; [isMutatingTool] must still be false for it.
     *
     * Ticket 01 offered nine names, traced 2026-10-28:
     * - `ask_fleet`: IN. `lookup_vin` behind it refreshes the car's cached spec row
     *   (`VehicleSpecController.refreshFromVin`), which is bookkeeping by that ruling.
     * - `ask_goals`: IN. `ask_advisor` behind it logs an `advisor_advice` row (`AdvisorAgent.ask`),
     *   also bookkeeping. A proposal it returns is only applied by `accept_proposal`, which blocks.
     * - `ask_body`, `ask_pantry`: IN. Plain reads.
     * - `ask_advisor`, `diagnose_codes`, `triage_symptom`, `ask_maintenance`, `check_cold_start`:
     *   behind `ask_goals`/`ask_fleet` ([DISPATCHED]), so they never reach the Live socket and a
     *   stamp on them would do nothing. They run non-blocking as part of their dispatcher.
     *
     * Never: `ask_engine` and `aspect_clerk` (can write), `ask_mail` (the read-through rule keys off
     * the turn a mail tool ran in). `LiveNonBlockingReadToolsTest` pins all of this.
     */
    val NON_BLOCKING_READ_TOOLS: Set<String> = setOf("ask_fleet", "ask_body", "ask_goals", "ask_pantry")

    /**
     * Every read-through tool (ticket 07's rule, ticket 15's build). **Since memory-and-vault ticket
     * 10 the phone writes no EpisodicTurn and distils no CompanionMemory**; this set now drives the
     * `remember` gate ([rememberBlockedByReadThroughTool], the `memory` tool's remember action) and
     * the LIVE_AUDIT / conversation-record redaction, and the engine's consolidation drops any turn
     * that called a read-through tool on its side. The original text: every tool whose result must
     * never reach [com.kevin.legion.data.local.EpisodicTurn]/[com.kevin.legion.data.local.CompanionMemory]. [com.kevin.legion.service.GeminiLiveSession] checks a functionCall's name against
     * this set the moment it arrives off the socket - before dispatch even runs - and skips
     * persisting that whole turn's transcript if it matches, since a mail tool's answer can BE
     * the driver's or Alfred's spoken text for the turn, not just a side value. Single source of
     * truth so a third mail-shaped tool later doesn't need a second place taught about it.
     */
    // "ask_mail" (2026-08-17, dispatcher split): the live session only ever sees "ask_mail" off
    // the socket now - "search_mail"/"read_mail" moved behind it (DISPATCHED's doc comment) and
    // are no longer declared to the live model at all. GeminiLiveSession.isEpisodicExcludedTool
    // matches the functionCall NAME as it arrives off the socket, before dispatch runs, so without
    // "ask_mail" here the read-through exclusion this set exists for would silently stop firing -
    // the two original names stay too, since dispatch still runs them internally inside ask_mail's
    // own investigate loop (agentToolsFor("mail", ...)), and a future direct caller of either
    // should still be caught.
    // `get_sitrep` joined 2026-08-21, and it is the first NON-mail tool here. It belongs because a
    // sitrep's NEWS module fetches Gmail bodies and hands back an LLM summary of them: the summary
    // IS mail content, one derivation removed. Ticket 08 call 4 rejected "store the summary, drop
    // the bodies" explicitly, so letting the spoken sitrep land in `episodic_turns` - and from
    // there into CompanionMemory via the consolidation pass - would have stored exactly what that
    // call refused.
    //
    // The cost is accepted and was already accepted by that ticket: a sitrep turn is not
    // remembered, so "what did yesterday's sitrep say" cannot be answered. A sitrep is a snapshot
    // of now, not a feed with a history.
    //
    // **Excluded unconditionally, not only when NEWS ran.** A conditional exclusion would depend on
    // which modules were enabled at the moment of the call, which is exactly the kind of "safe most
    // of the time" gate this set exists to avoid.
    // "track_package"/"flight_status" (ticket 25) read Gmail directly - see [mailExtraction]'s own
    // doc comment - and are declared straight to the live session, never behind `ask_mail`, so
    // unlike "search_mail"/"read_mail" (excluded only because a future direct caller might still
    // reach them despite living behind a dispatcher today) these two are excluded because the LIVE
    // model itself calls them by name every time. CLAUDE.md's read-through rule (§7, "the guarantee
    // is that it was never stored, not that something remembered to exclude it") is exactly why
    // this line, not a habit in `mailExtraction` itself, is what has to be right.
    val EPISODIC_EXCLUDED_TOOLS = setOf(
        "search_mail", "read_mail", "ask_mail", "get_sitrep", "track_package", "flight_status", "browser",
        // ADR 0086: a recipient lookup reads recent mail, and the read-back carries the draft.
        "email",
    )

    /**
     * Ticket 21 (google-account-integration, "close the remember leak"): the gate `remember`'s
     * dispatch branch applies before writing anything to permanent memory. The episodic exclusion
     * above already keeps a mail-touched turn out of [com.kevin.legion.data.local.EpisodicTurn]/
     * [com.kevin.legion.data.local.CompanionMemory] - this closes the second, independent hole
     * ticket 21 found: nothing stopped `remember` writing a
     * [com.kevin.legion.data.local.MemoryEntry] row in that SAME turn, so a driver saying "remember
     * that" right after Alfred read an email put mail content straight into permanent memory, with
     * no provenance, because the turn that would have recorded where it came from was the one
     * thing correctly dropped.
     *
     * A one-line wrapper, not inlined into `dispatch`'s "remember" case, so the decision is its own
     * named, plain-JVM unit test target - same reasoning as
     * [com.kevin.legion.service.GeminiLiveSession.isEpisodicExcludedTool].
     *
     * Takes [touchedExcludedTool] as an already-reduced boolean, not a tool name or a set of names
     * called this turn, because that IS the production shape: [com.kevin.legion.service.GeminiLiveSession]
     * already reduces "did any tool this turn match [EPISODIC_EXCLUDED_TOOLS]" down to one boolean
     * (`mailToolCalledThisTurn`, exposed read-only via `readThroughToolTouchedThisTurn()`) the
     * moment a matching functionCall arrives off the socket, via [com.kevin.legion.service.GeminiLiveSession.isEpisodicExcludedTool].
     * That is the one place the actual SET MEMBERSHIP test against [EPISODIC_EXCLUDED_TOOLS] runs
     * - its own doc comment explains why *that* function takes the set as an injectable parameter
     * (a test proves the membership test generalises to whatever joins the set later without a
     * code change) rather than re-testing membership a second, parallel time here, which could
     * quietly drift from what production actually decides.
     */
    internal fun rememberBlockedByReadThroughTool(touchedExcludedTool: Boolean): Boolean =
        touchedExcludedTool

    /**
     * The worded refusal `remember` returns when [rememberBlockedByReadThroughTool] fires
     * (ticket 21). Says plainly that mail is never kept, that this is deliberate rather than a
     * bug, and what the driver can do instead - the register the ticket asked for, no jargon, no
     * rule numbers, matching the old remember acknowledgements for tone rather than reading like a
     * system error.
     */
    // ADR 0071: a web page read in the same turn trips the same gate, so the words name both.
    internal const val REMEMBER_MAIL_REFUSAL = "I don't keep anything from mail, web pages or the camera - that's on " +
        "purpose, not a slip. Tell me the fact yourself, in your own words, and I'll remember that."

    /**
     * `generate_goal_plan`'s `constraintNotSaved` when the same gate fires for its `new_constraint`
     * (2026-11-09): a constraint is a member memory, so mail or web text in the turn refuses it.
     */
    internal const val GOAL_CONSTRAINT_READ_THROUGH_REFUSAL = "Constraint not saved: this turn read mail, " +
        "the camera or a web page. Ask the user to confirm it in their own words next turn."

    /**
     * Category A guard (ticket 01 §0): these tools read whichever car the OBD
     * dongle is physically plugged into RIGHT NOW - there is no "the second
     * car's live coolant temp" while the dongle sits in the first one. None of
     * these declare a `vehicle` parameter, but nothing stops the model from
     * supplying one anyway. If it names a car other than the one actually
     * connected, this refuses IN WORDS naming the connected car, rather than
     * silently answering with the connected car's real reading under the
     * wrong label - that mislabeled answer is the exact "confidently wrong"
     * failure this ticket exists to remove. Returns null (proceed normally)
     * when the argument is blank/omitted, or when it resolves to the actually
     * connected car.
     */
    private suspend fun refuseIfNotConnectedCar(context: Context, args: JSONObject): JSONObject? {
        val requested = args.optString("vehicle").trim()
        if (requested.isBlank()) return null
        // The car the dongle is in, not its MAC (2026-11-09 audit, see ActiveVehicle.connectedCarId).
        val connectedId = ActiveVehicle.connectedCarId(context)
        if (connectedId == null) {
            // Nothing connected at all - each of these tools already returns
            // its own "OBD adapter isn't connected" message; a second,
            // contradictory refusal here would just be confusing.
            return null
        }
        // The adapter's car is unknown (held under a pending id): never a dead end, the refusal says how
        // to name it (2026-11-17).
        if (com.kevin.legion.vehicle.DongleCar.isPending(connectedId)) {
            return JSONObject().put("success", false)
                .put("error", com.kevin.legion.vehicle.AdapterBinding.UNKNOWN_CAR_ERROR)
                .put("message", com.kevin.legion.vehicle.AdapterBinding.unknownCarRefusal(requested))
        }
        val connectedVehicle = VehicleController.vehicleFor(context, connectedId)
        // Ticket 04's label rule: the one rule, every surface - see VehicleController.label's own
        // doc. This used to hand-roll the same name-then-spec-then-placeholder precedence
        // VehicleResolver.displayName also hand-rolled, with its own "this car" filter and its own
        // last-resort literal - now both just call the one function.
        val connectedLabel = VehicleController.label(connectedVehicle)

        val match = VehicleResolver.resolveVehicle(context, requested)
        val isConnectedCar = match is VehicleMatch.Resolved && match.vehicle.obdMac == connectedId
        if (isConnectedCar) return null

        return JSONObject()
            .put("success", false)
            .put("error", "not_connected_to_that_car")
            .put(
                "message",
                "The OBD adapter's plugged into the $connectedLabel right now, not that one - I can " +
                    "only read live data for the car it's actually in. If he says the adapter is in the " +
                    "$requested now, that is car adapter_in vehicle=$requested.",
            )
    }

    /**
     * Category B resolution (ticket 01 §0/§3): resolves the tool's optional
     * `vehicle` argument and either runs [onResolved] against the matched
     * [Vehicle], or returns the mapped error JSON straight away. Blank/omitted
     * means the active car (existing behaviour, unchanged) - this NEVER
     * touches [ActiveVehicle]; resolving "the other car" must not switch which
     * one is actually active (verification gate 3).
     */

    // --- Gmail (ticket 15, google-account-integration) -------------------
    //
    // Read-through only (CLAUDE.md §7 proposed amendment, ticket 07's Answer): a message is
    // read, used in the answer, and dropped. Nothing here writes to Room - no entity, no DAO,
    // no table - that is a design constraint on this file, not a runtime check, and it is why
    // neither function below takes a CarDatabase. GeminiLiveSession.captureEpisodicTurn is what
    // additionally excludes a mail tool's OWN turn from the episodic log (ticket 07 point 2's
    // sharp part) - see that function's doc comment for the mechanism; nothing here needs to
    // know about it.

    /**
     * `search_mail`. A blank/omitted `query` is the app's own fixed briefing
     * ([GmailToolLogic.BRIEFING_QUERY]) at [GmailToolLogic.BRIEFING_CAP]; a supplied `query`
     * passes straight to Gmail's `q` UNCHANGED (ticket 05: the model is good at that syntax, the
     * app second-guessing it would be a worse parser wrapped around a better one), capped at
     * [GmailToolLogic.SEARCH_CAP]. The query that actually ran is always in the returned
     * payload's `query` field, so Alfred always has it to say - the guardrail ticket 05 built
     * this domain around, because a bad translation into Gmail syntax has to be visible, never a
     * confident wrong answer.
     */
    private suspend fun searchMail(context: Context, args: JSONObject): JSONObject {
        val plan = GmailToolLogic.plan(
            query = args.optString("query").takeIf { args.has("query") },
            limit = if (args.has("limit")) args.optInt("limit") else null,
        )
        return when (val tokenResult = GmailAuth.tokenOrReason(context)) {
            is GmailAuth.TokenResult.Token -> withContext(Dispatchers.IO) {
                when (val page = GmailClient(tokenResult.accessToken).search(plan.query, plan.cap)) {
                    is GmailClient.FetchResult.Ok -> searchMailResult(plan, page.value)
                    is GmailClient.FetchResult.Failed ->
                        mailFailure(GmailToolLogic.causeForFailure(page.networkFailure))
                }
            }
            is GmailAuth.TokenResult.NeedsConsent ->
                mailFailure(GmailToolLogic.causeForNeedsConsent(CompanionProfile.isGmailEnabled(context)))
            is GmailAuth.TokenResult.Failed ->
                mailFailure(GmailToolLogic.causeForFailure(GmailAuth.looksLikeNetworkFailure(tokenResult.error)))
        }
    }

    /**
     * Builds `search_mail`'s success payload. The briefing arm adds `total_unread`/`over_cap`
     * from Gmail's own `resultSizeEstimate` (ticket 05: "over the cap he says the total and
     * reads the first ten") - a plain search has no such overflow wording, it is a lookup
     * capped at 5, not a survey. An empty result says so in the `message` field rather than
     * returning a bare empty list for the model to improvise a sentence around.
     */
    private fun searchMailResult(plan: GmailToolLogic.Plan, page: GmailClient.SearchPage): JSONObject {
        val arr = JSONArray()
        for (m in page.messages) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("from", m.from)
                    .put("subject", m.subject)
                    .put("date", GmailToolLogic.relativeMailDate(m.timestampMs))
                    .put("snippet", m.snippet)
            )
        }
        val o = JSONObject()
            .put("success", true)
            .put("query", plan.query)
            .put("count", arr.length())
            .put("messages", arr)
        if (plan.isBriefing) {
            o.put("total_unread", page.totalEstimate)
            o.put("over_cap", page.totalEstimate > page.messages.size)
        }
        if (arr.length() == 0) {
            o.put(
                "message",
                if (plan.isBriefing) "Nothing unread in the last two days." else "No results for that search.",
            )
        }
        return o
    }

    /**
     * `read_mail`. Only ever called with an id `search_mail` just returned - the full plain-text
     * body goes to Gemini for this one call and is never written anywhere (ticket 07's read-
     * through rule).
     */
    private suspend fun readMail(context: Context, args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        if (id.isBlank()) return result(false, "I need a message id from a search first.")
        return when (val tokenResult = GmailAuth.tokenOrReason(context)) {
            is GmailAuth.TokenResult.Token -> withContext(Dispatchers.IO) {
                when (val fetched = GmailClient(tokenResult.accessToken).fetchFull(id)) {
                    is GmailClient.FetchResult.Ok -> JSONObject()
                        .put("success", true)
                        .put("id", fetched.value.id)
                        .put("from", fetched.value.from)
                        .put("subject", fetched.value.subject)
                        .put("date", GmailToolLogic.relativeMailDate(fetched.value.timestampMs))
                        .put("body", fetched.value.body)
                    is GmailClient.FetchResult.Failed ->
                        mailFailure(GmailToolLogic.causeForFailure(fetched.networkFailure))
                }
            }
            is GmailAuth.TokenResult.NeedsConsent ->
                mailFailure(GmailToolLogic.causeForNeedsConsent(CompanionProfile.isGmailEnabled(context)))
            is GmailAuth.TokenResult.Failed ->
                mailFailure(GmailToolLogic.causeForFailure(GmailAuth.looksLikeNetworkFailure(tokenResult.error)))
        }
    }

    /** One of ticket 10's four failure messages, never a collapsed generic one. */
    private fun mailFailure(cause: GmailToolLogic.Cause): JSONObject =
        JSONObject().put("success", false).put("message", GmailToolLogic.message(cause))

    /**
     * The four failure buckets `ui/world`'s package/flight cards render (command-center ticket
     * 08) - a coarser grouping than [GmailToolLogic.Cause]'s four, because a card doesn't need to
     * tell "never granted" from "grant lapsed" apart the way the spoken message does; it needs to
     * tell "you can't get in" from "nothing was there" from "the network dropped" from "we got the
     * mail but couldn't read it". `internal` (not private) so [MailCardFailure] and the JSON key
     * it's tagged under ([MAIL_CARD_FAILURE_KEY]) are visible to `ui/world` without duplicating the
     * enum there - one classification, read by both the failure copy and the card's `when`.
     */
    internal enum class MailCardFailure { NO_PERMISSION, NO_MATCH, UNREACHABLE, EXTRACTION_FAILED }

    /** The JSON key [MailCardFailure] rides under on a failed [trackPackage]/[flightStatus] result.
     * Absent entirely on success - a card must never read this key without first checking `success`. */
    internal const val MAIL_CARD_FAILURE_KEY = "failure_kind"

    /** [GmailToolLogic.Cause] collapsed to the two buckets a card actually distinguishes: no
     * permission (never granted or lapsed - both are "you can't get in" to a person looking at a
     * card, unlike the spoken message which does tell them apart) and unreachable (a real network
     * or API failure once permission is not the problem). */
    private fun mailCardFailureFor(cause: GmailToolLogic.Cause): MailCardFailure = when (cause) {
        GmailToolLogic.Cause.NEEDS_CONSENT, GmailToolLogic.Cause.NEVER_GRANTED -> MailCardFailure.NO_PERMISSION
        GmailToolLogic.Cause.NO_NETWORK, GmailToolLogic.Cause.API_ERROR -> MailCardFailure.UNREACHABLE
    }

    /** [mailFailure] plus the [MailCardFailure] tag a card needs - every [mailExtraction] failure
     * branch that originates from a Gmail-side [GmailToolLogic.Cause] goes through this instead of
     * the bare [mailFailure], so the tag can never drift out of sync with the spoken cause. */
    private fun mailCardFailure(cause: GmailToolLogic.Cause): JSONObject =
        mailFailure(cause).put(MAIL_CARD_FAILURE_KEY, mailCardFailureFor(cause).name)

    // --- Inbox intelligence: packages and flights (ticket 25, hands-and-senses map) ------
    //
    // Two read-through answers over the SAME mail search_mail/read_mail already reach (ticket 18's
    // resolution table, `.scratch/hands-and-senses/issues/18-inbox-intelligence.md`). Deliberately
    // NOT routed through `ask_mail`'s generic dispatcher: that dispatcher hands its sub-agent a
    // free-form question and trusts IT to compose the whole spoken answer, which is exactly the
    // wrong shape here - §4 rule 5 (no printed total to reconcile against, so it's an estimate,
    // never a fact) and ticket 18's rule 2 (naming the source mail is MANDATORY, not a nicety) both
    // have to hold even when the model drifts off-prompt. [mailExtraction] instead composes the
    // answer in two halves that never trust the model with either: [buildMailSourceLine] and
    // [ESTIMATE_SUFFIX] are built in plain Kotlin from fields [GmailClient] already parses, and a
    // one-shot, search-DISABLED [SubAgent] only ever supplies the middle sentence - what the one
    // email it was handed actually says. `useSearch = false` is load-bearing, not a default left
    // alone: ticket 18 rule 1 is "mail only", and the default `SubAgent` constructor leaves
    // `google_search` grounding ON, which would let the model answer from the live web instead of
    // the email it was asked to read.

    /** Package/order mail search terms - deliberately broad (an OR of the words a shipping
     * notification actually uses) since the model never writes this query; unlike `search_mail`,
     * nothing here is user-facing Gmail syntax a driver could get wrong. */
    private const val PACKAGE_MAIL_QUERY =
        "(tracking OR shipped OR \"has shipped\" OR \"out for delivery\" OR delivery OR " +
            "shipment OR package OR order) newer_than:30d"

    /** Travel-confirmation mail search terms - a year's window, since a booked trip is often
     * confirmed months ahead of the date being asked about. */
    private const val FLIGHT_MAIL_QUERY =
        "(flight OR itinerary OR \"boarding pass\" OR \"confirmation number\" OR reservation OR " +
            "check-in OR e-ticket) newer_than:365d"

    /** Exact literal `track_package` returns when the search ran fine and simply found nothing -
     * `internal` (not private), same reasoning as [buildMailSourceLine], so a plain-JVM test can
     * pin the wording and its distinctness from [FLIGHT_NO_MATCH_MESSAGE] and every
     * [GmailToolLogic.Cause] message without a real Gmail account or network call. */
    internal const val PACKAGE_NO_MATCH_MESSAGE =
        "I didn't find any shipping or tracking email in your inbox recently."

    /** `flight_status`'s equivalent of [PACKAGE_NO_MATCH_MESSAGE]. */
    internal const val FLIGHT_NO_MATCH_MESSAGE =
        "I didn't find any flight or travel confirmation email in your inbox."

    /** The extraction-side failure [mailExtraction] hands [agentResult] - a Gemini-reachability
     * problem AFTER the mail itself was already found and read, distinct in wording from both
     * [PACKAGE_NO_MATCH_MESSAGE]/[FLIGHT_NO_MATCH_MESSAGE] (no matching mail at all) and every
     * [GmailToolLogic.Cause] message (a Gmail-side problem, before any mail was ever found). */
    internal const val MAIL_EXTRACTION_FAILED_MESSAGE =
        "The mail was found but could not be read. Try again shortly."

    /** `track_package`. See this section's header comment for why the answer is composed the way
     * it is; this function only supplies the domain-specific query and extraction prompt.
     * `internal` (not private) - command-center ticket 08's package card calls this SAME function
     * on demand rather than restating the search-and-extract sequence, so the tool dispatch below
     * and the card can never drift into two different answers for the same question. */
    internal suspend fun trackPackage(context: Context): JSONObject = mailExtraction(
        context = context,
        query = PACKAGE_MAIL_QUERY,
        noMatchMessage = PACKAGE_NO_MATCH_MESSAGE,
        extractionPrompt = "You are reading ONE shipping or order-confirmation email on behalf " +
            "of a voice assistant. In one or two plain sentences, state the carrier if it is " +
            "named, the tracking number if one is given, and the most recent delivery status or " +
            "estimate the email itself states - a ship date, an \"out for delivery\" notice, or " +
            "an expected delivery window. State ONLY what THIS email says. If it does not mention " +
            "something (no tracking number, no delivery date), say plainly that the email does " +
            "not give one - never guess, never invent a figure, never describe this as a live or " +
            "current status. Plain text, no markdown.",
    )

    /** `flight_status`. See this section's header comment; the calendar-boundary instruction lives
     * in the tool's own declared description, not here - a rule the live model never reads cannot
     * steer it toward `read_calendar` first. `internal` for the same reason as [trackPackage] -
     * the flight card calls this directly, never a second search-and-extract. */
    internal suspend fun flightStatus(context: Context): JSONObject = mailExtraction(
        context = context,
        query = FLIGHT_MAIL_QUERY,
        noMatchMessage = FLIGHT_NO_MATCH_MESSAGE,
        extractionPrompt = "You are reading ONE travel-confirmation email on behalf of a voice " +
            "assistant. In one or two plain sentences, state the airline or hotel, the flight " +
            "number or confirmation number if one is given, and the date and time the email " +
            "itself states. State ONLY what THIS email says. If it does not mention something, " +
            "say plainly that the email does not give one - never guess, never invent a figure, " +
            "never describe this as a confirmed or current schedule. Plain text, no markdown.",
    )

    /**
     * Shared shape behind [trackPackage]/[flightStatus] (ticket 25): find the single most recent
     * mail matching [query], read its full body, and hand JUST that body to a one-shot,
     * search-disabled [SubAgent] with [extractionPrompt]. Reuses [agentResult] for the extraction
     * call's own typed offline/rate-limit/bad-key/overloaded mapping - the exact same phrasing
     * every other specialist in this file already uses for a Gemini-side failure, so this domain
     * doesn't invent a fifth wording for the same four outcomes.
     *
     * Three DIFFERENT sentences on the way in, on purpose (CLAUDE.md's "a failure result must say
     * in words what did NOT happen" - `search_mail`/`read_mail` never mention a lack of match
     * being a *different* thing from a lack of permission, but this domain has to, since a driver
     * asking "where's my package" cannot tell from silence which one happened): a Gmail
     * auth/permission problem ([mailFailure] via [GmailToolLogic]'s own four causes), no matching
     * mail at all ([noMatchMessage], the one case that is not an error - the inbox was read fine
     * and simply has nothing), and a Gemini-side failure reading the one message found
     * ([agentResult]'s own generic phrasing).
     *
     * **Command-center ticket 08's card fields.** A successful result additionally carries
     * `source_line`/`answer`/`estimate_suffix` as their OWN keys, alongside the already-composed
     * `message` string the voice tool speaks - the card renders the three parts on separate lines
     * ("shows the answer, the source mail line... and the estimate label", per the ticket) rather
     * than re-splitting the spoken sentence back apart by punctuation. A failed result carries
     * [MAIL_CARD_FAILURE_KEY] so the card can pick one of its four failure states without
     * re-deriving [GmailToolLogic.Cause] from the message text.
     */
    internal suspend fun mailExtraction(
        context: Context,
        query: String,
        noMatchMessage: String,
        extractionPrompt: String,
    ): JSONObject {
        val token = when (val tokenResult = GmailAuth.tokenOrReason(context)) {
            is GmailAuth.TokenResult.Token -> tokenResult.accessToken
            is GmailAuth.TokenResult.NeedsConsent ->
                return mailCardFailure(GmailToolLogic.causeForNeedsConsent(CompanionProfile.isGmailEnabled(context)))
            is GmailAuth.TokenResult.Failed ->
                return mailCardFailure(GmailToolLogic.causeForFailure(GmailAuth.looksLikeNetworkFailure(tokenResult.error)))
        }
        return withContext(Dispatchers.IO) {
            val client = GmailClient(token)
            val hit = when (val page = client.search(query, GmailToolLogic.SEARCH_CAP)) {
                is GmailClient.FetchResult.Ok -> page.value.messages.firstOrNull()
                    ?: return@withContext result(false, noMatchMessage)
                        .put(MAIL_CARD_FAILURE_KEY, MailCardFailure.NO_MATCH.name)
                is GmailClient.FetchResult.Failed ->
                    return@withContext mailCardFailure(GmailToolLogic.causeForFailure(page.networkFailure))
            }
            val body = when (val fetched = client.fetchFull(hit.id)) {
                is GmailClient.FetchResult.Ok -> fetched.value
                is GmailClient.FetchResult.Failed ->
                    return@withContext mailCardFailure(GmailToolLogic.causeForFailure(fetched.networkFailure))
            }
            val sourceLine = buildMailSourceLine(
                subject = body.subject,
                from = body.from,
                relativeDate = GmailToolLogic.relativeMailDate(body.timestampMs),
            )
            val extracted = agentResult(MAIL_EXTRACTION_FAILED_MESSAGE) {
                SubAgent(systemInstruction = extractionPrompt, useSearch = false).askTyped(
                    context = body.body,
                    question = "Summarize what this email says, per your instructions.",
                )
            }
            if (!extracted.optBoolean("success")) {
                // Every agentResult failure branch here happens strictly AFTER the mail itself was
                // found and read (rate-limited/offline/bad-key/overloaded/generic-failed all reach
                // this same `if`) - from the card's point of view that is one bucket, EXTRACTION_FAILED,
                // whichever of agentResult's own five wordings the driver would have heard spoken.
                extracted.put(MAIL_CARD_FAILURE_KEY, MailCardFailure.EXTRACTION_FAILED.name)
            } else {
                val answer = extracted.optString("message")
                result(true, "$sourceLine $answer $ESTIMATE_SUFFIX")
                    .put("source_line", sourceLine)
                    .put("answer", answer)
                    .put("estimate_suffix", ESTIMATE_SUFFIX)
            }
        }
    }

    /**
     * Ticket 18's rule 2, made structural rather than trusted to the model: the mandatory "Your X
     * email from Y (Z) says:" clause, built entirely from fields [GmailClient] already parses off
     * the message itself. `internal` (not private) so a plain-JVM test can pin its exact wording
     * without a network call or a Robolectric context.
     */
    internal fun buildMailSourceLine(subject: String, from: String, relativeDate: String): String {
        val subjectPart = subject.trim().ifBlank { "an" }
        val senderPart = from.trim().ifBlank { "an unknown sender" }
        return "Your \"$subjectPart\" email from $senderPart ($relativeDate) says:"
    }

    /**
     * Ticket 18's rule 3, verbatim and unconditional (CLAUDE.md §4 rule 5): every mail-extracted
     * answer here is an estimate of what the mail SAYS, never a fact, because nothing in this
     * domain reconciles against a printed total the way ledger/pantry do - there is no gate to
     * fall back on, only this sentence. A fixed literal, not model output, for the same reason
     * [buildMailSourceLine] is: it must survive even when the model's own phrasing drifts.
     */
    internal const val ESTIMATE_SUFFIX =
        "That's an estimate from reading the email text, not a live status - the calendar or the " +
            "carrier or airline itself may say something different by now."

    /** `from`/`to` (yyyy-MM-dd) into a `[startMs, endMs]` window in [RelativeDay.clock]'s zone - midnight
     * of `from` through the START of the day AFTER `to`. Moved inline here from the retired
     * `calendar/CalendarReadToolLogic.kt` (one-today ticket 01). Null on a malformed date or `to`
     * before `from`. */
    private fun parseCalendarWindow(from: String, to: String): Pair<Long, Long>? {
        // `today`/`tonight`/`tomorrow`/`yesterday` resolve here, on the local date (RelativeDay,
        // 2026-11-16). No `from` is today and no `to` is `from`: a day left out is
        // never one the model worked out.
        val zone = RelativeDay.clock().zone
        val today = RelativeDay.today()
        val fromDate = if (from.isBlank()) today else RelativeDay.parse(from, today)
        val toDate = fromDate?.let { if (to.isBlank()) it else RelativeDay.parse(to, today) }
        if (fromDate == null || toDate == null || toDate.isBefore(fromDate)) return null
        val startMs = fromDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = toDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return startMs to endMs
    }

    private const val CALENDAR_INVALID_WINDOW_MESSAGE =
        "I need a valid date range to read your calendar - from and to as yyyy-MM-dd or today, " +
            "tonight, tomorrow or yesterday, with to on or after from."

    /**
     * `complete_task` (2026-10-03). Resolves with [TaskDoneMatcher], writes with
     * [com.kevin.legion.backend.EventsAppointmentWriter.setDone] per row - the writer the calendar
     * tick uses, so voice and hands cannot drift. Window: an explicit `date` is that one day (no
     * `to` arg: it cost ~50 tokens past LiveSetupPayloadSizeTest's ceiling); otherwise anything due
     * up to 7 days out that is overdue-and-open (unbounded past) or, for an untick or an
     * already-done row, dated within the last 30 days. Success is reported per row, only for rows
     * setDone returned from.
     */
    @Suppress("CyclomaticComplexMethod") // one dispatch; a split scatters the decision
    private suspend fun completeTask(
        context: Context,
        args: JSONObject,
        // What to try when no task matches (2026-11-15): another list's line by the same words.
        onNoMatch: (suspend () -> JSONObject?)? = null,
    ): JSONObject {
        val target = args.optBoolean("done", true)
        val dateArg = args.optString("date").trim()
        val now = System.currentTimeMillis()
        var lo: Long? = null
        var hi = now + TASK_WINDOW_AHEAD_MS
        if (dateArg.isNotBlank()) {
            val window = parseCalendarWindow(dateArg, dateArg)
                ?: return result(false, "Nothing was changed. $CALENDAR_INVALID_WINDOW_MESSAGE")
            lo = window.first
            hi = window.second
        }
        val dao = CarDatabase.getDatabase(context).eventDao()
        val all = dao.getActiveByKind(EventKind.TASK)
        val scoped = all.filter { e ->
            val at = e.startsAt
            when {
                at == null || at > hi -> false
                lo != null -> at >= lo
                // Default window: open overdue work is always in; settled work only if recent.
                else -> (!e.done && target) || at >= now - TASK_WINDOW_BEHIND_MS
            }
        }
        val dateOf: (Event) -> String? = { e ->
            e.startsAt?.let { if (e.allDay) documentDate(it) else shortDate(it) }
        }
        val resolution = TaskDoneMatcher.resolve(
            scoped, all, args.optString("title"), target, args.optBoolean("all_matches", false),
        )
        return when (resolution) {
            TaskDoneMatcher.Resolution.EmptyQuery -> result(false, TaskDoneMatcher.EMPTY_QUERY_TEXT)
            is TaskDoneMatcher.Resolution.NoMatch ->
                onNoMatch?.invoke() ?: result(false, TaskDoneMatcher.noMatchText(resolution.nearest, dateOf))
            is TaskDoneMatcher.Resolution.Ambiguous ->
                result(false, TaskDoneMatcher.ambiguousText(resolution.candidates, dateOf))
            is TaskDoneMatcher.Resolution.AlreadyThere ->
                result(false, TaskDoneMatcher.alreadyText(target, resolution.matches, dateOf))
            is TaskDoneMatcher.Resolution.Apply -> commitTaskDone(context, resolution, target, dateOf)
        }
    }

    private const val TASK_WINDOW_AHEAD_MS = 7L * 24 * 60 * 60 * 1000
    private val RELATIVE_WORDS = setOf("today", "tonight", "tomorrow", "yesterday")
    private const val TASK_WINDOW_BEHIND_MS = 30L * 24 * 60 * 60 * 1000

    /** The write half of [completeTask]: one [EventsAppointmentWriter.setDone] per row, each
     * guarded, and the outbox read afterwards to tell which commits are only queued. */
    private suspend fun commitTaskDone(
        context: Context,
        r: TaskDoneMatcher.Resolution.Apply,
        target: Boolean,
        dateOf: (Event) -> String?,
    ): JSONObject {
        val dao = CarDatabase.getDatabase(context).eventDao()
        val committed = mutableListOf<Event>()
        val failed = mutableListOf<Event>()
        for (row in r.toChange) {
            try {
                // Re-read: the row may have changed or been deleted since the list was read.
                val fresh = dao.getById(row.id)
                if (fresh == null || fresh.deleted || fresh.kind != EventKind.TASK) {
                    failed += row
                } else {
                    committed += EventsAppointmentWriter.setDone(context, fresh, target)
                }
            } catch (e: Exception) {
                android.util.Log.w("LiveToolbox", "complete_task setDone failed for ${row.id}: ${e.message}")
                failed += row
            }
        }
        val pendingIds = runCatching {
            CarDatabase.getDatabase(context).outboxDao()
                .pendingForTable(OutboxTarget.EVENTS, Int.MAX_VALUE)
                .map { it.localId }.toSet()
        }.getOrDefault(emptySet())
        val queued = committed.map { it.id }.filter { it in pendingIds }.toSet()
        return result(
            committed.isNotEmpty(),
            TaskDoneMatcher.resultText(target, committed, failed, queued, r.alreadyThere, dateOf),
        ).apply {
            // What the phone's done line names (2026-11-19): the task, and which way it went.
            put("done", target)
            committed.singleOrNull()?.let { put("title", it.title) }
        }
    }

    /**
     * `read_calendar` (ticket 19). **One-today ticket 01, "cut Google entirely" (2026-09-01):**
     * repointed off the retired `calendar/CalendarProvider.kt`'s live `CalendarContract` read onto
     * [com.kevin.legion.data.local.EventDao.activeByKindInWindow] directly - the same local `events`
     * table `ui/TodayScreen.kt`/`ui/notes/InboxScreen.kt` now read. No permission check any more:
     * the local table has none to refuse.
     *
     * **No `done` field in the result (one-today ticket 08, "events are not todos", 2026-09-01).**
     * Ticket 02 ("ticking an appointment") had put one here, briefly true while every calendar-table
     * row was tickable; ticket 08 reversed that, and this tool only ever reads
     * [com.kevin.legion.backend.EventKind.EVENT] rows, which are never completable (Kevin: "i dont
     * mark an event done, it just passes"). Reporting `done: false` on every single row would not be
     * false, but it invites the model to read completability into a row that has none - the tool's
     * own description already promises only "title, start, end, and whether it is all-day", so the
     * field was undocumented as well as now-meaningless. Dropped rather than hardcoded to `false`.
     *
     * The `LEGION::v1` structured-metadata block (ticket 19's whole reason for existing -
     * `service_records.syncId`-style course/deadline fields a class syllabus states) is no longer
     * parsed from a live `DESCRIPTION` string at read time - it is parsed once, at import, into
     * [Event.structuredMeta] (a JSON object string). This function just decodes that column
     * straight into the `meta` field below - same shape the model always saw, sourced differently.
     */
    private suspend fun readCalendar(context: Context, args: JSONObject): JSONObject {
        val zone = RelativeDay.clock().zone
        val window = parseCalendarWindow(args.optString("from"), args.optString("to"))
            ?: return result(false, CALENDAR_INVALID_WINDOW_MESSAGE)
        return readCalendarWindow(context, zone, window, args.optString("city"))
    }

    /** [readCalendar] once its window is settled. */
    @Suppress("CyclomaticComplexMethod") // one dispatch; a split scatters the decision
    private suspend fun readCalendarWindow(
        context: Context,
        zone: java.time.ZoneId,
        window: Pair<Long, Long>,
        city: String,
    ): JSONObject {
        val (startMs, endMs) = window

        // [activeByKindInLocalWindow], not the raw DAO query - an all-day row's `startsAt` is UTC
        // midnight of its date, not a device-zone instant, so a plain window compare can speak the
        // wrong day aloud (found 2026-09-01, "the due dates seem to be advanced by 1 day"; see that
        // function's own doc comment).
        // **BOTH kinds, and reading only EventKind.EVENT is the bug this fixes (2026-09-11).**
        // Kevin: *"the AI doesnt see anything thats due on sunday for sch. i asked it and it didnt
        // know."* Seven assignments were due that Sunday. Every one of them is `kind = task`, this
        // query asked for `kind = event`, and so the model was handed an empty window and said so -
        // which reads exactly like "you have nothing due", the worst way for this to fail.
        //
        // 145 of the 314 rows on the real device are tasks (counted on the A25, 2026-09-11). Nothing
        // in the toolbox returned a single one of them: `read_list` is reminders, `manage_checklist`
        // is checklists, and `NotesController.openAppointments` is TASK-filtered but exists to MATCH
        // a spoken title for `manage_item`, not to read a day. **Assignments were unreachable by
        // voice entirely**, which is also an ADR 0035 gap - a capability with no hands path is
        // unfinished, and this one had no path at all.
        //
        // The dates themselves were always right and that is worth recording, because the first
        // suspicion was a timezone fault: these rows carry `allDay = 0` and a true instant
        // (`2026-09-14T04:59Z` = Sunday 23:59 in Houston), so [activeByKindInLocalWindow] buckets
        // them onto the correct local day already. Canvas writes an 11:59pm local deadline as
        // `04:59Z the next day`; the storage handles it. Only the `kind` filter was wrong.
        // One read for the tool and the voice agenda block (ADR 0093, 2026-11-16): the block is what
        // this tool returns for today.
        val nowMs = RelativeDay.clock().millis()
        val read = com.kevin.legion.calendar.CalendarWindowRead.read(context, startMs, endMs, zone, nowMs)
        val events = read.events
        // A live card: it re-reads this window when the calendar changes while it is up (LiveCards).
        ToolCards.publish(LiveCardReads.calendarCard(events, startMs, endMs))
        // 2026-11-15: "Quiet on the calendar for today" over two open assignments. Everything due,
        // from BOTH places a due date lives (calendar tasks and dated to-dos, overdue too), is
        // summed up in Kotlin ahead of the rows ([DueReader]), and the
        // rows put open tasks first, so the answer is the first thing in the result.
        val due = read.due
        val arr = JSONArray()
        for (event in openTasksFirst(events, zone, nowMs)) {
            val eventStart = event.startsAt ?: continue
            val eventEnd = event.endsAt ?: eventStart
            arr.put(
                JSONObject()
                    .put("title", event.title)
                    .put(
                        "start",
                        if (event.allDay) com.kevin.legion.util.documentDate(eventStart)
                        else "${com.kevin.legion.util.shortDate(eventStart)} ${com.kevin.legion.util.clockTime(eventStart)}",
                    )
                    .put(
                        "end",
                        if (event.allDay) com.kevin.legion.util.documentDate(eventEnd)
                        else "${com.kevin.legion.util.shortDate(eventEnd)} ${com.kevin.legion.util.clockTime(eventEnd)}",
                    )
                    .put("all_day", event.allDay)
                    // An event PASSES; a task gets DONE (one-today ticket 08). Without this the
                    // model cannot tell "your 09:30 class" from "a quiz due tonight", and would
                    // have to guess from the title - so it is told, and told whether the task is
                    // already finished rather than being left to imply it.
                    .put("kind", event.kind)
                    .also { o -> if (event.kind == EventKind.TASK) o.put("done", event.done) }
                    .also { o ->
                        // event.structuredMeta is already a JSON object string (parsed once, at
                        // import, from the source description's `LEGION::v1` block) - decode it
                        // straight through rather than re-parsing any raw text here.
                        val meta = event.structuredMeta
                        if (!meta.isNullOrBlank()) {
                            runCatching { JSONObject(meta) }.getOrNull()?.let { o.put("meta", it) }
                        }
                        if (!event.location.isNullOrBlank()) o.put("location", event.location)
                    },
            )
        }
        val o = calendarResult(due, events, arr)
        // The window said back as local days, so a reply about "tomorrow" stands on a date it was given.
        val fromDay = java.time.Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val toDay = java.time.Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()
        val saidWindow = RelativeDay.label(fromDay) + if (fromDay == toDay) "" else " to ${RelativeDay.label(toDay)}"
        o.put("window", saidWindow)
        // How that window stands to today, in words: a bare date beside today's is easy for the model
        // to misread, so a read of another day is never answered as today.
        o.put("window_is", RelativeDay.windowIs(fromDay, toDay, RelativeDay.today()))
        // Suggestions (2026-10-09) ride in their OWN list, never in `events`: they are things the
        // user could do, not plans, and `count` and the empty-window sentence stay about plans.
        com.kevin.legion.calendar.EventSuggestions.attachInWindow(context, o, startMs, endMs, zone, city)
        return o
    }

    /**
     * `read_calendar`'s result: the `due` block first, then the rows. With no rows it says what
     * was looked at, and "nothing due" only when the `due` block is empty and could be read.
     * ("Nothing on the calendar" once invited the model to answer a question about DEADLINES with a
     * sentence about appointments, which is how a window that excluded every task still sounded
     * authoritative.)
     */
    private fun calendarResult(
        due: com.kevin.legion.calendar.DueSummary,
        events: List<com.kevin.legion.data.local.Event>,
        rows: JSONArray,
    ): JSONObject {
        val o = JSONObject().put("success", true)
            .put("due", DueReader.toJson(due))
            .put("events_count", events.count { it.kind == EventKind.EVENT })
            .put("count", rows.length())
            .put("events", rows)
        if (rows.length() == 0) {
            o.put(
                "message",
                if (due.open.isEmpty() && due.unreadable.isEmpty()) {
                    "Nothing on the calendar and nothing due in that window."
                } else {
                    "Nothing on the calendar in that window; what is due is under due."
                },
            )
        }
        return o
    }

    /**
     * Open tasks first (by time), then everything else (by time): the rows the model reads first.
     * "Open" is [DueReader.taskInput]'s, at [nowMs], so the rows and the `due` block agree.
     */
    private fun openTasksFirst(
        events: List<com.kevin.legion.data.local.Event>,
        zone: java.time.ZoneId,
        nowMs: Long,
    ) = events.sortedWith(
        compareBy<com.kevin.legion.data.local.Event> { DueReader.taskInput(it, zone, nowMs)?.done != false }
            .thenBy { it.startsAt ?: Long.MAX_VALUE },
    )

    internal suspend fun withResolvedVehicle(
        context: Context,
        args: JSONObject,
        onResolved: suspend (Vehicle) -> JSONObject,
    ): JSONObject = when (val match = VehicleResolver.resolveVehicle(context, args.optString("vehicle").takeIf { it.isNotBlank() })) {
        // Every stored-record answer names the car it is about (2026-11-03), so a reply about the
        // wrong car is audible instead of passing as an answer about the right one.
        is VehicleMatch.Resolved -> onResolved(match.vehicle).also { out ->
            if (!out.has("vehicle")) out.put("vehicle", VehicleController.label(match.vehicle))
        }
        is VehicleMatch.NoneChosen -> noneChosenResult(match)
        is VehicleMatch.Unknown -> JSONObject()
            .put("success", false)
            .put("error", "unknown_vehicle")
            .put("requested", match.requested)
            .put("knownVehicles", JSONArray(match.known))
            .put(
                "message",
                if (match.known.isEmpty()) "I don't have any cars on file yet - \"${match.requested}\" isn't one I know."
                else "I don't have a car matching \"${match.requested}\" - you've got ${match.known.joinToString(", ")}. Which one did you mean?",
            )
        is VehicleMatch.Ambiguous -> JSONObject()
            .put("success", false)
            .put("error", "ambiguous_vehicle")
            .put("requested", match.requested)
            .put("candidates", JSONArray(match.candidates))
            .put("message", "\"${match.requested}\" matches more than one car - ${match.candidates.joinToString(", ")}. Which one did you mean?")
    }

    /**
     * Rides on every non-empty `memory` recall result (was `recall_memory` until memory-and-vault
     * ticket 09; [MemoryToolbox] puts it on) (LIVE_AUDIT, A25, 2026-10-30): with six
     * memories about his wife in front of it, the model offered generic gift ideas and called her
     * "their". The description is read once at setup; this is read beside the memories it is
     * about. Names no assistant and no honorific: the register's "never sir, never Mr" lives in
     * `ai/Personas.kt` and this must not fight it.
     *
     * 2026-11-05 (Kevin: Alfred adds details the memory does not hold): "say only what a memory
     * states". No occurrence in the 11-01..11-05 phone logs (both recalls there were answered
     * honestly), so this is the minimal guard; the scoring in `ai/AriaBrain.kt` is memory v2's.
     */
    internal const val RECALL_USE_NOTE =
        "These are things the user told you before. Build the answer on them: name the people, " +
            "places and details as the user does, use the pronouns the memories use for a person " +
            "(never 'they' for someone they call she or he), and make suggestions specific to what " +
            "the memories say rather than generic. When you say what you remember, say only what a " +
            "memory states: add no detail, date, place or reason it does not hold. If none of them " +
            "bears on the question, say so in a clause and ask."

    /**
     * Latest known balance for one account, or all known accounts if none
     * named. Reads [LedgerController.accountBalances] - the SAME
     * `AccountBalance` data [com.kevin.legion.ui.ledger.BalancesSection]
     * renders - rather than a raw [LedgerController.latestBalanceCents] read,
     * for two reasons found in review:
     *
     * 1. `latestBalanceCents` only ever returns a printed balance, so an
     *    account carrying UNRECONCILED card-CSV rows (ticket 12) would have
     *    reported a stale figure here that visibly disagreed with what the UI
     *    shows for the same account - two surfaces answering the same
     *    question differently is worse than either being wrong alone.
     * 2. The old loop only wrote a JSON key when `cents != null`, so an
     *    account with no printed balance at all (Bank of America's card
     *    layout, or a card CSV-only account before its PDF ever lands)
     *    vanished from the response instead of reading as "not stated".
     *
     * CLAUDE.md §4 rule 7 requires every surface rendering an UNRECONCILED
     * figure to say so in words - this is the voice surface's half of that;
     * `AccountBalanceRow`'s "includes pending transactions..." stamp is the
     * UI's half. `verified=false` plus a `note` field on the affected
     * account(s) is that words-not-colour requirement translated into JSON,
     * since a voice tool has no colour to fall back on regardless.
     *
     * **Voice-logged pending transactions add a THIRD figure on top of the
     * two ticket 12 already tracked** (a printed balance, and mid-cycle
     * card-CSV provisional activity): `pending_delta_cents`/`pending_count`,
     * the driver's own spoken reports. `posted_balance_cents` is `balanceCents`
     * alone (still possibly null - a format that never prints one, unchanged),
     * `available_balance_cents` sums all three when a posted figure exists,
     * matching [com.kevin.legion.ledger.AccountBalance]'s own doc comment for
     * how the available figure is defined. `verified` is false whenever
     * EITHER `provisional_delta_cents` or `pending_delta_cents` is nonzero -
     * two independently unconfirmed sources, either one is enough to make the
     * headline figure not-yet-final.
     */
    /**
     * Dispatches `categorize_transactions`: applies every stored rule first (idempotent, cheap -
     * see [LedgerController.applyCategoryRules]'s doc comment), then batches the AI guess for
     * whatever is STILL uncategorised, one call per distinct merchant key
     * ([LedgerController.uncategorizedMerchants], never per row - the cost guard ticket 07 D17
     * requires). A merchant list of zero after the rule sweep is a real, ordinary success (nothing
     * left to guess), not a failure. [UncategorizedMerchants.transfersSkipped] is spoken out loud
     * whenever nonzero, never folded silently into "nothing to categorize".
     */
    private suspend fun categorizeTransactions(context: Context): JSONObject {
        val rulesApplied = LedgerController.applyCategoryRules(context)
        val pool = LedgerController.uncategorizedMerchants(context)
        // Said out loud rather than silently dropped (CLAUDE.md §4 rule 6's principle) - a transfer
        // row (moving Kevin's own money between his own accounts) is excluded from the guesser
        // entirely, see LedgerController.uncategorizedMerchants's own doc comment.
        val transferNote = if (pool.transfersSkipped > 0) {
            " ${pool.transfersSkipped} transfer-shaped transaction(s) skipped - moving your own money isn't a category."
        } else ""
        if (pool.keys.isEmpty()) {
            return result(
                true,
                (
                    if (rulesApplied > 0) "$rulesApplied transaction(s) categorized from rules already on file - nothing left to guess."
                    else "Nothing to categorize - everything's already sorted."
                    ) + transferNote,
            )
        }
        val guessed = LedgerController.applyCategoryGuesses(context, pool.keys)
        return result(
            true,
            "Guessed a category for ${guessed.merchantsCategorized} merchant(s), " +
                "${guessed.rowsCategorized} transaction(s) total" +
                (if (rulesApplied > 0) " ($rulesApplied more categorized from rules already on file)." else ".") +
                transferNote +
                " These are guesses, not confirmed - say \"[merchant] is [category]\" to confirm or correct one.",
        )
    }

    /**
     * Dispatches `set_category`: validates [args]'s `category` against the fixed list
     * (D14, "fixed list enforced at the boundary") before ever writing anything, then hands off to
     * [LedgerController.setCategory] to confirm-or-correct every transaction from that merchant.
     */
    private suspend fun setCategory(context: Context, args: JSONObject): JSONObject {
        val merchant = args.optString("merchant").trim()
        if (merchant.isBlank()) return result(false, "Which merchant should I set a category for?")

        // Audit fix 2026-08-07: `action = "clear"` is the undo path for a rule
        // this tool installed. A rule was previously unremovable through the
        // app at all - see LedgerController.setCategory's comment for why that
        // was dangerous. Deliberately a PARAMETER, not a second tool
        // registration: every tool is prompt tokens on every live session, on
        // the driver's own key, and the notes map's budget rule (net-neutral or
        // better) applies to corrections too.
        if (args.optString("action").trim().equals("clear", ignoreCase = true)) {
            val removed = LedgerController.clearCategoryRules(context, merchant)
            return if (removed > 0) {
                result(
                    true,
                    "Removed $removed category rule(s) for \"$merchant\", so it won't govern future " +
                        "imports. Transactions already categorized keep their category - say the " +
                        "correct category for them if you want those changed too.",
                )
            } else {
                result(false, "There's no category rule on file for \"$merchant\".")
            }
        }

        val spoken = args.optString("category").trim()
        if (spoken.isBlank()) return result(false, "Which category should that be?")

        val known = CarDatabase.getDatabase(context).categoryDao().allNames()
        val category = known.firstOrNull { it.equals(spoken, ignoreCase = true) }
            ?: return result(
                false,
                "There's no \"$spoken\" category. The ones that exist are: ${known.joinToString(", ")}.",
            )

        val outcome = LedgerController.setCategory(context, merchant, category)
        return when {
            // A refusal, NOT "nothing matched" - the two must not be conflated
            // or the driver is told the wrong thing about why nothing happened.
            outcome.keyTooShort -> result(
                false,
                "\"$merchant\" is too short a name to categorize safely - it would match anything " +
                    "containing those letters and I'd re-file transactions you never named. Give me " +
                    "at least ${LedgerController.MIN_MERCHANT_KEY_LENGTH} characters of the merchant.",
            )
            // 2026-08-13 fix: "CHECKCARD"/"CHKCARD"/"PURCHASE" are the bank's own transaction-type
            // words, not merchants - they prefix nearly every card line on a BofA statement, so a
            // rule on one of them alone would re-file almost every card purchase, exactly what
            // happened with the CHECKCARD rule this fix removes (see MIGRATION_17_18).
            outcome.isNoiseKey -> result(
                false,
                "\"$merchant\" is a transaction type the bank prints on the line, not a merchant name - " +
                    "it would match nearly every card purchase. Say the actual merchant.",
            )
            outcome.rowsTouched > 0 -> result(
                true,
                buildString {
                    append("$merchant is now categorized as $category - ")
                    append("${outcome.rowsTouched} transaction(s) updated")
                    // Say the blast radius out loud whenever one spoken name
                    // reached more than one merchant. This is the only signal a
                    // driver gets that a short or garbled key went wide, and the
                    // update rewrites history unconditionally (D19).
                    if (outcome.merchantsTouched > 1) {
                        append(" across ${outcome.merchantsTouched} different descriptions")
                    }
                    append(", confirmed. Future imports matching \"$merchant\" will use it too - ")
                    append("say \"clear the category rule for $merchant\" to undo that.")
                    // backend-etl ticket 14 option 2: whether the choice reached the server. Said
                    // only when something did NOT go (queued, or a row with no server copy).
                    outcome.server.sentence()?.let { append(" "); append(it) }
                },
            )
            else -> result(false, "I don't see any transactions from \"$merchant\" on file.")
        }
    }

    // One exit each: no ledger with a net cash figure, no ledger, the balances (ADR 0094 added the first).
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun getLedgerBalance(context: Context, account: String): JSONObject {
        val allBalances = LedgerController.accountBalances(context)
        // account is free-text from the driver's speech, not a stored key, so
        // this matches loosely: an exact accountId, a substring (a driver
        // says "BofA", not "BOFA ****4471"), or the same physical card by
        // last-4 (sameCard) so "the 7823 card" resolves whether the balance
        // came from the card's own PDF or its bare-filename CSV entry.
        val matched = allBalances.filter { balance ->
            account.isBlank() || balance.accountId.contains(account, ignoreCase = true) ||
                sameCard(balance.accountId, account)
        }
        // ADR 0094 (Kevin, 2026-11-16): asked how much he has, the answer LEADS with net cash and
        // its as-of time. The engine's figure through the one FinanceController HOME and the Money
        // screen read (ADR 0035), its saved copy offline; never summed here. Only when no account
        // was named: "the 7823 card" asks about that card.
        val cash = if (account.isBlank()) com.kevin.legion.finance.FinanceController.forApp(context).netCash() else null
        val voice = cash?.let {
            com.kevin.legion.finance.NetCashVoice.voice(it, java.time.Instant.now(), java.time.ZoneId.systemDefault())
        }
        // Stage 7 money gate: when the engine was read, the answer is ONLY ready strings made by code
        // (no raw cents to add up, no clock to turn into day words). The ledger's own per-account
        // figures stay for a named account and for when the engine could not be read.
        if (voice != null && voice.figures.isNotEmpty()) return netCashResult(voice)
        val cashLead = voice?.sayFirst
        if (matched.isEmpty()) {
            return result(success = false, message = "No ledger accounts on file yet - import a statement first.")
        }
        // Fetched once, filtered per account below by the same suffix+currency
        // match pendingDeltaCents itself uses - cheaper than one DB round trip
        // per matched account for a list that is typically small.
        val allPending = LedgerController.pendingTransactions(context)
        val balances = JSONObject()
        val spoken = mutableListOf<String>()
        voice?.speech?.takeIf { it.isNotBlank() }?.let { spoken += it }
        for (b in matched) {
            val entry = JSONObject()
            val postedBalanceCents = b.balanceCents
            // Read from the shared property, never re-summed here - see
            // AccountBalance.availableCents's doc comment for why (the
            // 2026-08-07 incident this ticket 30 fix closes a second instance of).
            val availableCents = b.availableCents
            val hasAnyFigure = postedBalanceCents != null || b.isProvisional || b.hasPendingRows
            val pendingCount = allPending.count { sameCard(it.accountId, b.accountId) && it.currency == b.currency }
            // JSONObject silently OMITS a key whose value is Kotlin null
            // rather than writing one - JSONObject.NULL is the explicit way
            // to say "asked, and the answer is not stated" rather than
            // leaving the account out of the response entirely (the same
            // silent-drop bug this whole fix is closing, one layer down).
            // "currency" (2026-08-07 currency audit): the one addition every
            // figure in this response needed. Without it, a bare 316.89 (or
            // any other account's own currency) read as whatever currency the
            // persona's own manner of speaking implied - see LiveToolbox's
            // file-level currency-audit note near getSpend for the traced
            // hazard this closes.
            entry.put("currency", b.currency.name)
            entry.put("posted_balance_cents", postedBalanceCents ?: JSONObject.NULL)
            entry.put("provisional_delta_cents", b.provisionalDeltaCents)
            entry.put("pending_delta_cents", b.pendingDeltaCents)
            entry.put("pending_count", pendingCount)
            entry.put("available_balance_cents", if (hasAnyFigure) availableCents else JSONObject.NULL)
            // Legacy "balance" field, dollars - kept so an older transcript/log
            // reading this response doesn't silently lose the figure it used
            // to read; new callers should prefer available_balance_cents.
            entry.put("balance", if (hasAnyFigure) availableCents / 100.0 else JSONObject.NULL)
            entry.put("verified", !b.isProvisional && !b.hasPendingRows)
            // The figure to say, ready (stage 7): the model says this string, never its own sum.
            val say = com.kevin.legion.finance.FinanceText.exact(availableCents, b.currency.name)
            entry.put("say", if (hasAnyFigure) say else JSONObject.NULL)
            val heard = if (hasAnyFigure) availableCents else null
            spoken += com.kevin.legion.finance.NetCashVoice.ledgerSpeech(b.accountId, heard, b.currency.name)
            if (b.isProvisional || b.hasPendingRows) {
                val notes = mutableListOf<String>()
                if (b.isProvisional) {
                    notes += if (postedBalanceCents != null) {
                        "includes pending card transactions not yet confirmed by a statement"
                    } else {
                        "no statement on file for this card yet - this figure is pending transactions only, unverified"
                    }
                }
                if (b.hasPendingRows) {
                    notes += "includes $pendingCount transaction(s) you logged as pending, not yet confirmed by the bank"
                }
                entry.put("note", notes.joinToString(" "))
            }
            balances.put(b.accountId, entry)
        }
        val answer = JSONObject().put("success", true)
        // First key, so the model reads it first; the per-account figures below leave out the
        // bank's pending charges, which net cash counts.
        cashLead?.let { answer.put("say_first", it) }
        // Said by the phone itself, word for word (VerbatimSpeech): no amount is the model's.
        answer.put(com.kevin.legion.ai.VerbatimSpeech.KEY, spoken.joinToString(" "))
        answer.put(com.kevin.legion.ai.VerbatimSpeech.FROM, "get_balance")
        answer.put("rule", com.kevin.legion.finance.NetCashVoice.RULE)
        return answer.put("balances", balances)
    }

    /** `get_balance` with no account named and the engine read: net cash as ready strings only. */
    private fun netCashResult(v: com.kevin.legion.finance.NetCashVoice.Voice): JSONObject {
        val figures = JSONArray()
        v.figures.forEach { figures.put(JSONObject().put("what", it.what).put("say", it.say)) }
        val out = JSONObject().put("success", true)
            .put(com.kevin.legion.ai.VerbatimSpeech.KEY, v.speech)
            .put(com.kevin.legion.ai.VerbatimSpeech.FROM, "get_balance")
            .put("say_first", v.sayFirst)
        v.asOf?.let { out.put("as_of", it) }
        v.stale?.let { out.put("stale", it) }
        return out.put("figures", figures).put("rule", com.kevin.legion.finance.NetCashVoice.RULE)
    }

    /**
     * Dispatches `log_pending_transaction`. Account resolution and cents conversion are pure
     * ([com.kevin.legion.ledger.resolveAccountForPending]/[com.kevin.legion.ledger.pendingAmountCents],
     * `ledger/LedgerPendingLog.kt`, unit-tested without Context) - this function is the thin
     * Context/JSONObject wrapper that reads [args], calls them, and writes the row.
     */
    private suspend fun logPendingTransaction(context: Context, args: JSONObject): JSONObject {
        val description = args.optString("description").trim()
        if (description.isBlank()) return result(false, "What was the pending charge for?")

        val direction = args.optString("direction", "debit")
        val amount = args.optDouble("amount", Double.NaN)
        val amountCents = com.kevin.legion.ledger.pendingAmountCents(amount, direction)
            ?: return result(
                false,
                "That amount doesn't sound right - I need a positive number, in dollars, for how much this was.",
            )

        val balances = LedgerController.accountBalances(context)
        val resolution = com.kevin.legion.ledger.resolveAccountForPending(balances, args.optString("account"))
        val resolved = when (resolution) {
            is com.kevin.legion.ledger.PendingAccountResolution.NoMatch ->
                return result(false, "No ledger accounts on file yet - import a statement first.")
            is com.kevin.legion.ledger.PendingAccountResolution.Ambiguous -> return result(
                false,
                "Which account? I see: ${resolution.candidates.joinToString(", ") { it.accountId }}.",
            )
            is com.kevin.legion.ledger.PendingAccountResolution.Resolved -> resolution.account
        }

        val dateRaw = args.optString("date").trim()
        val txnDate = if (dateRaw.isBlank()) {
            // WHICH day "today" is comes from the DEVICE's clock; how that date is then STORED
            // stays UTC-midnight, matching every parser (see util.documentDate). Reading the
            // calendar date in UTC would stamp tomorrow's date all evening west of UTC - the same
            // mismatch workouts.weekStartEpoch documents.
            java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            try {
                java.time.LocalDate.parse(dateRaw, PENDING_DATE_FORMAT)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (e: Exception) {
                return result(false, "I couldn't read that date - try month/day/year, like 08/07/2026.")
            }
        }

        LedgerController.logPendingTransaction(
            context = context,
            accountId = resolved.accountId,
            currency = resolved.currency,
            description = description,
            amountCents = amountCents,
            txnDate = txnDate,
        )
        // Same "no anchor at all" case get_balance's own figure branches on:
        // a balance-less account (no printed running balance, e.g. Bank of
        // America's card layout) can still report a pending-only figure, just
        // never as if it were a stated balance. resolved.availableCents is the
        // shared property read (ticket 30) - this adds only the just-logged
        // amount on top, never re-summing the base terms itself.
        val newAvailable = resolved.availableCents + amountCents
        val figure = " New available balance for ${resolved.accountId}: ${formatMoney(newAvailable, resolved.currency)}."
        return result(
            true,
            "Logged: ${formatMoney(amountCents, resolved.currency)} pending on ${resolved.accountId}." +
                "$figure This is your own report, not yet confirmed by the bank.",
        )
    }

    /** `MM/dd/yyyy` - matches [com.kevin.legion.ledger.parsers.BofaCsvStatementParser]'s own convention. */
    internal val PENDING_DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy")

    /**
     * Dispatches `list_pending_transactions`. Every row carries `verified = false` unconditionally
     * - there is no other kind of row [LedgerController.pendingTransactions] can return, unlike
     * [listRecentTransactions] where verified varies row to row.
     */
    private suspend fun listPendingTransactions(context: Context): JSONObject {
        val rows = LedgerController.pendingTransactions(context)
        val aliases = LedgerController.merchantAliases(context)
        val arr = JSONArray()
        var totalCents = 0L
        for (r in rows) {
            arr.put(
                JSONObject()
                    .put("date", documentDate(r.txnDate))
                    // Merchant aliases (2026-10-07): `merchant` is the person's name for it; `description`
                    // stays the raw text because clear_pending_transaction matches against it.
                    .put("merchant", displayDescription(r.description, aliases))
                    .put("description", r.description)
                    .put("amount", r.amountCents / 100.0)
                    .put("currency", r.currency.name)
                    .put("account", r.accountId)
                    .put("verified", false),
            )
            totalCents += r.amountCents
        }
        // "total" is a mixed-currency sum only when every row shares one currency (matching
        // the ledger's own "never combine currencies" posture) - each row's own "currency" key
        // above is what a mixed-currency caller must actually read.
        val currencies = rows.map { it.currency }.distinct()
        val totalObj = JSONObject().put("success", true).put("count", rows.size).put("pending", arr)
        if (currencies.size <= 1) {
            totalObj.put("total", totalCents / 100.0)
            totalObj.put("currency", currencies.singleOrNull()?.name ?: JSONObject.NULL)
        } else {
            totalObj.put("total", JSONObject.NULL)
            totalObj.put("currency_note", "pending rows span more than one currency - never sum them, read each row's own currency")
        }
        return totalObj
    }

    /**
     * Dispatches `clear_pending_transaction`. Matching is pure
     * ([com.kevin.legion.ledger.matchPendingByDescription], `ledger/LedgerPendingLog.kt`) - this
     * function fetches the current pending rows, matches, and if exactly one hit, deletes via
     * [LedgerController.clearPendingTransaction], which itself can only ever remove a pending row
     * (the DAO-level `AND pendingLoggedAt IS NOT NULL` guard).
     */
    private suspend fun clearPendingTransaction(context: Context, description: String): JSONObject {
        val query = description.trim()
        if (query.isBlank()) return result(false, "Which pending transaction should I remove?")

        val pending = LedgerController.pendingTransactions(context)
        return when (val match = com.kevin.legion.ledger.matchPendingByDescription(pending, query)) {
            is com.kevin.legion.ledger.PendingClearMatch.NoMatch ->
                result(false, "I don't see a pending transaction matching \"$query\".")
            is com.kevin.legion.ledger.PendingClearMatch.Ambiguous -> result(
                false,
                "Which one? " + match.candidates.joinToString(", ") { "${it.description} (${formatMoney(it.amountCents, it.currency)})" },
            )
            is com.kevin.legion.ledger.PendingClearMatch.Resolved -> {
                val removed = LedgerController.clearPendingTransaction(context, match.row.id)
                result(
                    removed,
                    if (removed) "Removed the pending charge: ${match.row.description}." else "Couldn't remove that - it may already be gone.",
                )
            }
        }
    }

    /**
     * Recent ledger transactions, raw (no categorization yet - see the
     * tool's own description). Each row carries `verified` +, for
     * [IngestMethod.UNRECONCILED] rows, a `note` - CLAUDE.md §4 rule 7's
     * "labelled in every surface that renders them" applied to the voice
     * surface, matching [com.kevin.legion.ui.ledger.LedgerTransactionRow]'s
     * "pending, not verified" inline stamp rather than inventing new wording
     * for the same claim.
     */
    private suspend fun listRecentTransactions(context: Context, count: Int): JSONObject {
        val transactions = LedgerController.recentTransactions(context, count.coerceIn(1, 100))
        val aliases = LedgerController.merchantAliases(context)
        val arr = JSONArray()
        for (t in transactions) {
            val o = JSONObject()
                .put("date", documentDate(t.txnDate))
                // Merchant aliases (2026-10-07): `merchant` is the person's name for it; `description`
                // stays the bank's raw text, unchanged, so nothing keyed on it breaks.
                .put("merchant", displayDescription(t.description, aliases))
                .put("description", t.description)
                .put("amount", t.amountCents / 100.0)
                .put("currency", t.currency.name)
                .put("account", t.accountId)
                .put("verified", t.ingestMethod != IngestMethod.UNRECONCILED)
            if (t.ingestMethod == IngestMethod.UNRECONCILED) {
                o.put("note", "pending, not verified")
            }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", transactions.size).put("transactions", arr)
    }

    /**
     * Recent grocery line items - macro fields are estimates, see the tool's own description.
     * Each row carries its own receipt's `currency` (2026-08-07 currency audit) - a bare dollar
     * figure here was invisible to a persona-primed model, which read it in whatever currency
     * its own manner of speaking implied (CLAUDE.md §4, the same hazard `get_balance` closes).
     */
    private suspend fun listRecentGroceries(context: Context, count: Int): JSONObject {
        val items = PantryController.recentLineItemsWithCurrency(context, count.coerceIn(1, 100))
        val arr = JSONArray()
        for (row in items) {
            val i = row.item
            val o = JSONObject()
                .put("name", i.name)
                .put("quantity", i.quantity)
                .put("totalPrice", i.totalPriceCents / 100.0)
                .put("currency", row.currency.name)
            i.caloriesKcal?.let { o.put("estimatedCaloriesKcal", it) }
            i.proteinG?.let { o.put("estimatedProteinG", it) }
            i.carbsG?.let { o.put("estimatedCarbsG", it) }
            i.fatG?.let { o.put("estimatedFatG", it) }
            arr.put(o)
        }
        com.kevin.legion.assistant.cards.DomainToolCards.groceries(items)
        return JSONObject().put("success", true).put("count", items.size).put("items", arr)
    }

    /**
     * Total logged grocery spend, PER CURRENCY, never combined into one bare figure (2026-08-07
     * currency audit) - see [com.kevin.legion.pantry.PantryController.totalSpendCentsByCurrency]'s
     * doc comment for the SGD/USD-mixing bug this closes. `total`/`currency` are kept ONLY when
     * exactly one currency is on file, so an older caller reading those two legacy keys gets a
     * real answer in the common single-currency case rather than a silently wrong combined sum;
     * `byCurrency` is the field every new caller should read.
     *
     * **AMENDED 2026-08-26 (CLAUDE.md section 4 rule 7 condition 3, ticket 08).** `unverifiedNote`
     * is present ONLY when at least one currency's total includes a receipt that couldn't be
     * fully re-checked ([com.kevin.legion.data.local.PantryCurrencyTotal.hasUnreconciled]) - the
     * model is expected to relay this in words when it speaks the total, the same way it already
     * must for an "estimate" macro figure. Never a silent flag the model can drop.
     */
    private suspend fun getGrocerySpend(context: Context): JSONObject {
        val totals = PantryController.totalSpendCentsByCurrency(context)
        val byCurrency = JSONObject()
        for (t in totals) byCurrency.put(t.currency.name, t.totalCents / 100.0)
        val o = JSONObject().put("success", true).put("byCurrency", byCurrency)
        if (totals.size == 1) {
            o.put("total", totals[0].totalCents / 100.0)
            o.put("currency", totals[0].currency.name)
        }
        val unverifiedCurrencies = totals.filter { it.hasUnreconciled }.map { it.currency.name }
        if (unverifiedCurrencies.isNotEmpty()) {
            o.put(
                "unverifiedNote",
                "At least one receipt behind the ${unverifiedCurrencies.joinToString(" and ")} " +
                    "total couldn't be fully verified against its own numbers - mention that the " +
                    "figure isn't confirmed accurate when you report it.",
            )
        }
        return o
    }

    /**
     * D27: today's macro gap - or "not logged", NEVER a zero-actual gap, per
     * [com.kevin.legion.meals.DailyMealGap]'s sealed shape making the mistake structurally
     * impossible rather than merely avoided here.
     */
    /**
     * Sets one category's monthly budget, spoken.
     *
     * **The category must already exist.** Ticket 07 D14 made the category set
     * FIXED precisely so "groceries" means the same thing in March and April -
     * so an unrecognised name is refused and the real list is handed back,
     * rather than quietly minting a category nobody can spend against. A budget
     * against a category no transaction can ever match would read as permanently
     * unspent, which is worse than a refusal.
     *
     * Dollars in, `Long` cents stored (CLAUDE.md §4 rule 3). The rounding is the
     * only place a spoken amount becomes money, so it happens once, here.
     */
    private suspend fun setBudget(context: Context, args: JSONObject): JSONObject {
        val spoken = args.optString("category").trim()
        if (spoken.isBlank()) return result(false, "Which category should that budget apply to?")

        val known = CarDatabase.getDatabase(context).categoryDao().allNames()
        val category = known.firstOrNull { it.equals(spoken, ignoreCase = true) }
            ?: return result(
                false,
                "There's no \"$spoken\" category. The ones that exist are: ${known.joinToString(", ")}.",
            )

        val dollars = args.optDouble("amount", Double.NaN)
        if (dollars.isNaN() || dollars <= 0) {
            return result(false, "How much should the $category budget be?")
        }
        val cents = Math.round(dollars * 100.0)

        LedgerController.setBudget(
            context = context,
            entity = LedgerEntity.US,
            category = category,
            // "This month" is the driver's month, not UTC's - on the last evening of a month west
            // of UTC those differ, and the budget would land on the wrong one.
            month = java.time.YearMonth.now(java.time.ZoneId.systemDefault()),
            amountCents = cents,
        )
        return result(true, "Budget set: ${formatMoney(cents, LedgerEntity.US.currency)} a month for $category, from this month on.")
    }

    /** The fixed category set (ticket 07 D14), so the model can name one correctly before setting a budget. */
    private suspend fun listBudgetCategories(context: Context): JSONObject {
        val names = CarDatabase.getDatabase(context).categoryDao().allNames()
        return JSONObject().put("categories", org.json.JSONArray(names))
    }

    /**
     * `get_monthly_spend` - reads the SAME [LedgerController.budgetVsActual] call the Money
     * screen's `ui.ledger.BudgetSection` renders, so the voice path can never quietly disagree with
     * what the screen shows for the identical month. `Long` cents throughout (CLAUDE.md §4 rule 3).
     *
     * **The own-account-movements disclosure (2026-08-13) is not optional trim here.** CLAUDE.md §4
     * rule 7 requires every figure that excluded something to say so in words; `note` carries the
     * EXACT sentence [excludedOwnAccountMovementsSentence] builds for the screen (the same shared
     * function, not a second wording of the same claim), and `excluded_own_account_movements_count`/
     * `_cents` are handed separately too so a caller that wants to compose its own phrasing still has
     * the numbers rather than only a pre-baked sentence - see this tool's own description for why
     * the model is told to always say both.
     */
    private suspend fun getMonthlySpend(context: Context, monthArg: String = ""): JSONObject {
        // "This month" is the driver's month, not UTC's - matches setBudget's own reasoning above.
        val (month, monthNote) = SpendMonth.resolve(monthArg, YearMonth.now(java.time.ZoneId.systemDefault()))
        val budget = LedgerController.budgetVsActual(context, LedgerEntity.US, month)
        ToolCards.publish(PhoneCards.monthlySpend(budget, month))
        val totalCents = budget.spentCents
        val unverified = budget.uncategorized.hasProvisionalRows ||
            budget.lines.any { it.hasProvisionalRows || it.hasPendingCategoryGuesses } ||
            !budget.isComplete
        val excluded = budget.excludedOwnAccountMovements
        // Two separate exclusions, two separate sentences, both handed over (2026-08-15): own-account
        // movements were never spend at all, while uncategorised rows ARE spend the driver has not
        // classified yet - the model must be able to say which is which rather than reading one
        // merged caveat.
        val uncategorizedNote = uncategorizedExcludedSentence(budget.uncategorized, budget.entity.currency)
        return JSONObject()
            .put("success", true)
            .put("currency", budget.entity.currency.name)
            .put("month", month.toString())
            .put("month_words", SpendMonth.words(month))
            .put("other_months", SpendMonth.otherMonths(month))
            .apply { monthNote?.let { put("month_note", it) } }
            .put("total_spent_cents", totalCents)
            .put("verified", !unverified)
            .put("excluded_own_account_movements_count", excluded.count)
            .put("excluded_own_account_movements_cents", excluded.totalCents)
            .put("uncategorized_cents", budget.uncategorized.spentCents)
            .put("uncategorized_note", uncategorizedNote)
            .put("note", excludedOwnAccountMovementsSentence(excluded, budget.entity.currency))
            // 2026-09-29: rows filed under a not-spending category (Transfers). A third exclusion,
            // stated in its own sentence for the same reason as the two above.
            .put("excluded_not_spending_count", budget.notSpendingExcluded.count)
            .put("excluded_not_spending_cents", budget.notSpendingExcluded.totalCents)
            .put(
                "not_spending_note",
                com.kevin.legion.ledger.notSpendingExcludedSentence(budget.notSpendingExcluded, budget.entity.currency),
            )
            // 2026-09-29 (Kevin, "b"): a Housing charge in the last 3 days of a month counts in the
            // next month. The shared sentences, one per moved row; empty when nothing moved.
            .put(
                "early_housing_notes",
                org.json.JSONArray(
                    com.kevin.legion.ledger.earlyChargeSentences(
                        budget.earlyChargesMoved, month, budget.entity.currency,
                    ),
                ),
            )
    }

    /**
     * `why_did_you_say_that`. Reads the most recent [ProactiveRaiseRow] and hands back its stored
     * rule and reason.
     *
     * **Returns `success = false` with a plain answer when there is nothing**, rather than an empty
     * success. An empty success invites the model to fill the gap, which is the exact failure the
     * whole raise contract exists to stop - the same reason `OpenerCalendarBriefing` says "nothing
     * is scheduled" in words instead of handing back an empty list.
     */
    private suspend fun whyDidYouSayThatTool(context: Context, args: JSONObject): JSONObject {
        // ADR 0082: "stop the morning brief" switches that brief off, the same switch as its row in
        // Settings, Proactive speech (ADR 0035). Says in words when nothing matched.
        val stop = args.optString("stop").trim()
        val row = if (stop.isEmpty()) CarDatabase.getDatabase(context).proactiveRaiseDao().mostRecent() else null
        val delivery = when (row?.delivery) {
            ProactiveRaiseRow.DELIVERY_SPOKEN -> "said aloud"
            ProactiveRaiseRow.DELIVERY_RUNG -> "rung as an emergency alarm"
            else -> "posted as a notification"
        }
        return when {
            stop.isNotEmpty() ->
                com.kevin.legion.spokenbrief.SpokenBriefs.stop(context, stop).let { result(it.ok, it.message) }
            row == null -> result(false, "You have not raised anything on your own recently.")
            else -> result(
                true,
                "Rule \"${row.ruleId}\" (${row.category}) fired and was $delivery. The fact behind " +
                    "it: ${row.reason}. Say the rule and the fact plainly; do not justify it.",
            )
        }
    }

    /**
     * `get_sitrep`. Parses the optional `modules` filter and hands off entirely to
     * [com.kevin.legion.brief.BriefController.forVoice], the same controller the Brief modal's
     * ViewModel calls (ai-upgrade ticket 02, ADR 0035). This function does no formatting of its own.
     * `success` is false when no brief from the engine, fresh or saved, was read, and the message
     * then says so in words before giving the phone's own calendar and weather.
     */
    private suspend fun getSitrepTool(context: Context, args: JSONObject): JSONObject {
        val modules = parseSitrepModules(args.optString("modules").takeIf { args.has("modules") })
        val voice = com.kevin.legion.brief.BriefController.of(context).forVoice(modules)
        return result(voice.success, voice.text)
    }

    /** A blank/omitted `modules` argument means "every enabled module" - [SitrepBuilder.build]'s
     * own `null` sentinel - never an empty set, which would instead mean "the model explicitly
     * asked for nothing." An unrecognised token (a typo, a module name that doesn't exist) is
     * silently dropped from the requested set rather than failing the whole call - the same
     * "narrows, never widens" contract [SitrepBuilder.build] documents for this filter. `internal`
     * for direct unit testing. */
    internal fun parseSitrepModules(raw: String?): Set<SitrepModule>? {
        if (raw.isNullOrBlank()) return null
        val requested = raw.split(",").mapNotNull { SitrepModule.fromKey(it.trim().lowercase()) }.toSet()
        return requested
    }

    /**
     * `answer_call` / `decline_call`.
     *
     * **The `success` flag is the load-bearing part.** `TelecomManager.acceptRingingCall()` returns
     * `void`, so [CallActions] confirms by watching the phone's own call state instead of assuming;
     * only an observed RINGING -> OFFHOOK transition reports success here. CLAUDE.md §7 lets the
     * assistant say "answered" only after a tool result that came back successful, so anything less
     * than an observed connection must come back false - otherwise the lie is simply moved one
     * layer up into what it says out loud.
     */
    private suspend fun answerCallTool(context: Context, args: JSONObject): JSONObject {
        val outcome = CallActions.answer(context, speaker = args.optBoolean("speaker", false))
        // A refused SPEAKER does not make a connected call a failure - the call is answered either
        // way, and describe() states the route separately. Reporting success=false here would make
        // the assistant say it failed to answer a call it had just answered.
        return result(outcome is CallActions.Outcome.Answered, CallActions.describe(outcome))
    }

    private suspend fun declineCallTool(context: Context): JSONObject {
        val outcome = CallActions.reject(context)
        return result(outcome is CallActions.Outcome.Rejected, CallActions.describe(outcome))
    }

    /**
     * `place_call`. Thin Context/JSONObject wrapper around [PlaceCallAction.dispatchVoiceCall],
     * which owns the whole confirm-gate + resolution + emergency-refusal logic and is
     * unit-tested directly with injected fakes (see `PlaceCallActionTest`) - no Context needed
     * there, matching the split [GarageController.dispatchVoiceActivate] already established.
     *
     * `success` is true only for [PlaceCallAction.VoiceCallResult.success] - the confirm-prompt
     * turn itself, every failure sentence, and the emergency refusal all come back false, same
     * convention `activate_garage`/`clear_codes` already use for a destructive real-world tool.
     */
    private suspend fun placeCallTool(context: Context, args: JSONObject): JSONObject {
        val contact = args.optString("contact").takeIf { args.has("contact") }
        val number = args.optString("number").takeIf { args.has("number") }
        val confirmed = args.optBoolean("confirmed", false)
        val r = PlaceCallAction.dispatchVoiceCall(
            contactQuery = contact,
            numberQuery = number,
            confirmed = confirmed,
            hasCallPermission = PlaceCallAction.hasCallPermission(context),
            hasContactsPermission = CallerId.hasContactsPermission(context),
            lookupContacts = { query -> PlaceCallAction.lookupContacts(context, query) },
            isEmergencyNumber = { num -> PlaceCallAction.isEmergencyNumberOnDevice(context, num) },
            dial = { num -> PlaceCallAction.dial(context, num) },
        )
        return result(r.success, r.message)
    }

    // ---------------------------------------------------------------- notes / lists / calendar
    //
    // Dispatches `manage_list` / `manage_item` / `read_list` (ticket 05/10/11). Matching itself
    // is pure (`notes/NotesLogic.kt`) - these functions are the thin Context/JSONObject wrapper,
    // same split `logPendingTransaction`/`resolveAccountForPending` already uses.

    private val NOTE_DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val NOTE_TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    /** Human-readable date for a spoken confirmation - "Aug 13, 2026". Deliberately its own
     * formatter rather than `util/Dates.kt`'s `documentDate` (ticket 14): `documentDate` reads an
     * epoch-millis instant back in a FIXED UTC zone, which is correct for a date printed on a
     * statement or receipt (see that function's own doc comment) but wrong here - the local
     * [java.time.LocalDate] this formats has no zone attached to round-trip, it is exactly what the
     * driver said, so it is formatted directly rather than composed into millis and reinterpreted. */
    private val NOTE_DISPLAY_DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")

    /** How long a voice-created appointment with a specific time (not all-day), on LEGION's own
     * calendar, lasts when the driver never states a duration - ticket 14's `manage_item` doesn't expose an
     * end-time argument, matching CLAUDE.md's tool-budget posture (this ticket's own instruction:
     * "prefer extending the existing notes tools... every tool is prompt tokens"). An hour is the
     * same default the AOSP Calendar app's own "new event" flow uses. */
    private const val CALENDAR_EVENT_DEFAULT_DURATION_MS = 60L * 60L * 1000L

    /**
     * `yyyy-MM-dd` -> **device-zone** midnight epoch ms. Null if blank/unparseable.
     *
     * **Was UTC midnight until the 2026-08-07 audit, and that was a real bug**,
     * not a convention choice. A reminder's date is not a date printed on a
     * document (`LedgerTransaction.txnDate`'s UTC convention, correct there) -
     * it is part of a real future instant that an alarm fires at, and
     * `ui/notes/ListDetailScreen` has always written it in the device zone.
     * The voice path disagreeing with the hand path meant the same spoken
     * "tomorrow at 6am" landed on a different instant depending on which
     * surface you used.
     *
     * Also used for skip dates and repeat end dates, which must stay in the
     * SAME zone as [com.kevin.legion.notes.Recurrence]'s day-maths or a skip
     * silently fails to match the occurrence it was meant to remove.
     */
    private fun parseNoteDate(raw: String): Long? =
        parseNoteLocalDate(raw)?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    /** The parsed calendar date itself, for callers that must compose it with a time-of-day before choosing a zone. */
    private fun parseNoteLocalDate(raw: String): java.time.LocalDate? {
        val s = raw.trim()
        if (s.isBlank()) return null
        // The words resolve on the local date too (RelativeDay, 2026-11-16).
        return if (s.lowercase() in RELATIVE_WORDS) {
            RelativeDay.parse(s, RelativeDay.today())
        } else {
            runCatching { java.time.LocalDate.parse(s, NOTE_DATE_FORMAT) }.getOrNull()
        }
    }

    /** `HH:mm` as a local wall-clock time. Null if blank/unparseable. */
    private fun parseNoteLocalTime(raw: String): java.time.LocalTime? {
        val s = raw.trim()
        if (s.isBlank()) return null
        return runCatching { java.time.LocalTime.parse(s, NOTE_TIME_FORMAT) }.getOrNull()
    }

    /**
     * **The** list. There is one (2026-08-11: "dissolve the car list. merge everything into one
     * list model"), so resolving which list a command means is no longer a question that can be
     * asked, let alone got wrong.
     *
     * The four helpers this replaces (`resolveNotesList`, `resolveOrCreateNotesList`,
     * `withMatchedList`, `manageList`) existed to fuzzy-match a spoken list name against every list
     * the driver had. That matcher is precisely what filed an F150 recall appointment onto "Car",
     * where nothing surfaced it. Deleting the structure deletes the failure mode with it.
     */
    private suspend fun theList(context: Context): ItemList = NotesController.theList(context)

    /**
     * `manage_item remove` for a calendar entry, before the reminder path: asked first, removed on his
     * yes in a later turn ([userText], EventRemoval). Null when no calendar entry is involved; `date`
     * narrows which day.
     */
    private suspend fun removeCalendarEntry(context: Context, args: JSONObject, userText: String?): JSONObject? {
        val item = args.optString("item").trim()
        val dateArg = args.optString("date").trim()
        val day = parseNoteLocalDate(dateArg)
        // A date that does not read leaves the calendar alone: the reminder path runs as before.
        val unreadable = dateArg.isNotEmpty() && day == null
        if (!isRemove(args) || item.isBlank() || unreadable) return null
        val reminders = NotesController.openItemsForList(context, theList(context).id)
        return EventRemoval.remove(context, item, day, userText, reminders)
    }

    private fun isRemove(args: JSONObject) = args.optString("action").trim().lowercase() == "remove"

    /**
     * `manage_item schedule` for a calendar entry (moved, EventMove), or an `add` that corrects the
     * entry just added or moved. Null when neither applies, or a date or time does not read: the
     * reminder and add paths run as before and word their own refusals.
     */
    private suspend fun moveCalendarEntry(context: Context, args: JSONObject, userText: String?): JSONObject? {
        val item = args.optString("item").trim()
        val dateArg = args.optString("date").trim()
        val timeArg = args.optString("time").trim()
        val date = parseNoteLocalDate(dateArg)
        val time = parseNoteLocalTime(timeArg)
        val unreadable = dateArg.isNotEmpty() && date == null || timeArg.isNotEmpty() && time == null
        val action = args.optString("action").trim().lowercase()
        // Only an add that could be an appointment corrects one: a reminder add never moves an entry.
        val appointmentAdd = args.optString("kind").trim().lowercase() in setOf("", "appointment")
        return when {
            unreadable -> null
            action == "add" -> if (appointmentAdd) {
                com.kevin.legion.service.tools.day.EventMove.followUp(context, item, date, time, userText)
            } else {
                null
            }
            action != "schedule" || item.isBlank() -> null
            else -> com.kevin.legion.service.tools.day.EventMove.move(
                context, item, date, time, NotesController.openItemsForList(context, theList(context).id), userText,
            )
        }
    }

    /** Dispatches `manage_item`: add / tick / untick / remove / schedule / skip one item. */
    private suspend fun manageItem(context: Context, args: JSONObject): JSONObject {
        val action = args.optString("action").trim().lowercase()
        val itemArg = args.optString("item").trim()
        val list = theList(context)

        if (action == "add") {
            if (itemArg.isBlank()) return result(false, "Nothing was added: add what?").put("needs_answer", true)

            // **`add` honours `date`/`time`** (bug, 2026-08-11). It previously called addItem with
            // the text alone and dropped both arguments on the floor while still replying "Added
            // ...", so "add the F150 recall appointment on the 22nd" produced a dateless item and a
            // confirmation that it had worked. The date only landed if the model happened to make a
            // SECOND `schedule` call, which it often did not - and nothing anywhere reported the
            // difference. An accepted argument that is silently discarded is worse than one that is
            // refused: the refusal is at least visible.
            val dateArg = args.optString("date").trim()
            val timeArg = args.optString("time").trim()

            if (timeArg.isNotBlank() && dateArg.isBlank()) {
                return result(false, "What date is that time on? I need a date to set a reminder.")
            }
            val date = if (dateArg.isBlank()) null else parseNoteLocalDate(dateArg)
                ?: return result(false, "I couldn't read \"$dateArg\" as a date - try yyyy-MM-dd.")
            val time = if (timeArg.isBlank()) null else parseNoteLocalTime(timeArg)
                ?: return result(false, "I couldn't read \"$timeArg\" as a time - try 24-hour HH:mm.")

            // Ticket 14: the appointment-versus-reminder call lives ONE place -
            // ScheduleIntentResolver - never inferred ad hoc here. The model's `kind` argument is
            // its only input; anything other than exactly "appointment" (including omitted/blank/
            // unrecognized) resolves to Reminder, ticket 04's "when genuinely ambiguous, default to
            // reminder" applied.
            val kindArg = args.optString("kind").trim().ifBlank { null }
            val kind = ScheduleIntentResolver.resolve(kindArg)
            // 2026-11-15 voice eval: "add buy stamps to my to-do list" became an undated reminder. A
            // to-do with no date, time or repeat lives on the Todo checklist (ListRouting), so it goes
            // there whichever tool the model picked, and the result says where it went.
            val repeatArg = args.optString("repeat_kind").trim().lowercase()
            if (date == null && kind !is ScheduleIntentResolver.Kind.Appointment && repeatArg in setOf("", "none")) {
                return addToTodo(context, itemArg)
            }

            if (kind is ScheduleIntentResolver.Kind.Appointment) {
                // A calendar event needs SOMETHING to put on the calendar: a row with no
                // `startsAt` is skipped by every calendar read, so a dateless appointment would
                // be saved and then invisible. Asking "when" is cheaper and more honest than
                // inventing a date.
                if (date == null) {
                    return result(false, "What date is that for? I need one to put it on your calendar.")
                }
                return addAppointment(context, list, itemArg, date, time, timeArg)
            }

            // Same device-zone composition [scheduleItem] uses - see its comment for why this is
            // atZone(systemDefault) over a LocalDateTime rather than date-millis plus time-millis.
            val startsAt = date?.let {
                java.time.LocalDateTime.of(it, time ?: java.time.LocalTime.MIDNIGHT)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            val added = NotesController.addItemDue(context, list.id, itemArg, startsAt, allDay = time == null)

            // The reply states the date back (and, per ticket 14, says which STORE it landed in),
            // so a date that did NOT take is visible in the answer rather than only on a screen the
            // driver may not open for a week.
            val whenPhrase = date?.let {
                if (time == null) it.format(NOTE_DISPLAY_DATE_FORMAT) else "${it.format(NOTE_DISPLAY_DATE_FORMAT)} at $timeArg"
            }
            return result(
                true,
                ScheduleIntentResolver.confirmationPhrase(ScheduleIntentResolver.Kind.Reminder, added.text, whenPhrase),
            ).put("item", added.text) // what the phone's done line names (2026-11-19)
        }

        if (itemArg.isBlank()) return result(false, "Which item?")
        return when (val match = NotesController.findItem(context, list.id, itemArg)) {
            is ItemMatch.NoMatch -> {
                // One-today ticket 02, "ticking an appointment": tick/untick only, and only after a
                // reminder match genuinely came back empty - a title that matches a reminder is
                // never shadowed by a calendar-table row of the same name. schedule/skip stay
                // reminder-only; remove reached calendar entries above (EventRemoval, asked first).
                // One-today ticket 08 narrowed [tickOrUntickAppointment] itself to match
                // only a task, never an event - see [NotesController.openAppointments]'s own doc
                // comment - so this branch now genuinely finds nothing for a class/event title,
                // which is correct.
                when (action) {
                    "tick", "untick" -> tickOrUntickAppointment(context, itemArg, action)
                    "schedule" -> scheduleTodoLine(context, list, itemArg, args)
                        ?: result(false, "I don't see \"$itemArg\" on your list - add it?")
                    "remove" -> removeTodoLine(context, itemArg)
                        ?: result(false, EventRemoval.nothingFound(itemArg, parseNoteLocalDate(args.optString("date"))))
                    else -> result(false, "I don't see \"$itemArg\" on your list - add it?")
                }
            }
            is ItemMatch.Ambiguous -> result(
                false, "Nothing was changed. Which one? " + match.candidates.joinToString(", ") { it.text },
            )
            is ItemMatch.Resolved -> dispatchItemAction(context, action, list, match.item, args)
        }
    }

    /** `manage_item`'s tick/untick fallback once a reminder match came back empty - one-today
     * ticket 02, narrowed by ticket 08 ("events are not todos") to an [EventKind.TASK] match only.
     * **CORRECTED 2026-10-09:** this used to say it matched [NotesController.openAppointments] and
     * wrote through [NotesController.tickAppointment]/[untickAppointment] directly. It now hands the
     * phrase to [completeTask], the same code `complete_task` runs, so the two voice paths cannot
     * disagree about what matched or what the result says. */
    private suspend fun tickOrUntickAppointment(context: Context, itemArg: String, action: String): JSONObject =
        // Delegates to completeTask (2026-10-09, voice audit finding 6). This branch used to fuzzy-match
        // with NotesController.findAppointment and say 'Marked "X" attended.' for homework; in S46 a
        // quiz that was already done fell out of the open-task pool and the fuzzy matcher answered
        // 'Which one?' with eight OTHER quizzes. completeTask matches on every word, counts a done
        // task as a match (so 'already done' is reported as that), words the result as task done /
        // not done, and its failures all begin 'Nothing was changed' with the closest real titles.
        // 2026-11-15: and when no task matches either, a Todo checklist line ("tick off printer
        // ink"), so a tick sent to the reminder tool ends on whichever list holds the thing.
        completeTask(
            context,
            JSONObject().put("title", itemArg).put("done", action == "tick"),
            onNoMatch = { tickTodoLine(context, itemArg, action == "tick") },
        ).also { r ->
            if (r.optBoolean("success") && r.optString("message").startsWith("Marked")) noteRoute(r, TASK_NOT_REMINDER)
        }

    /**
     * `manage_item`'s `add` action once [ScheduleIntentResolver] has decided the item is an
     * [ScheduleIntentResolver.Kind.Appointment] (ticket 14). **One-today ticket 01, "cut Google
     * entirely" (2026-09-01): writes straight to the local `events` table now** - the retired
     * `calendar/CalendarProvider.kt`'s live Google Calendar insert is gone, and there is no fallback
     * branch left to word, because a local Room write has nothing external to fail on the way
     * `WRITE_CALENDAR` being refused or no Google account being synced once did. [Event.id] is
     * explicitly allocated from [Event.APPOINTMENT_ID_BASE]'s disjoint range via
     * [com.kevin.legion.data.local.nextAppointmentId] - the SAME allocator the retired Google import
     * used, so a voice-created appointment's id can never collide with a reminder's (see that
     * function's own doc comment).
     *
     * [date]/[time] are already-parsed local values; [timeArg] is the raw `HH:mm` string purely so
     * the confirmation sentence echoes back exactly what the driver said rather than a reformatted
     * version of it (matching [scheduleItem]'s own reply style elsewhere in this file).
     *
     * **Events-outbox ticket: the write itself now goes through
     * [com.kevin.legion.backend.EventsAppointmentWriter], not straight to [EventDao].** This
     * function's own job shrinks to "resolve dates, phrase the confirmation" - the local write, the
     * server push attempt, and enqueueing a durable retry on a push failure all live in that
     * object now (see its own class doc for why [Event.serverId] on a fresh row is null rather than
     * the client-minted placeholder UUID this function used to mint here directly).
     */
    private suspend fun addAppointment(
        context: Context,
        list: ItemList,
        itemArg: String,
        date: java.time.LocalDate,
        time: java.time.LocalTime?,
        timeArg: String,
    ): JSONObject {
        val allDay = time == null
        val whenPhrase = if (allDay) date.format(NOTE_DISPLAY_DATE_FORMAT) else "${date.format(NOTE_DISPLAY_DATE_FORMAT)} at $timeArg"

        // Android's own all-day convention (Calendar Provider guide, research doc §5), preserved
        // here even though the write is local now: UTC midnight of the calendar date, DTEND one day
        // later - matching the exact encoding every already-imported appointment row uses, so
        // `ui/notes/CalendarAgendaResolver.kt`'s `agendaDayStart` UTC-branch keeps working for a
        // voice-created row exactly as it does for a Google-imported one.
        val (startMs, endMs) = if (allDay) {
            val start = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            start to start + java.time.Duration.ofDays(1).toMillis()
        } else {
            val start = java.time.LocalDateTime.of(date, time)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            start to start + CALENDAR_EVENT_DEFAULT_DURATION_MS
        }

        return try {
            val added = com.kevin.legion.backend.EventsAppointmentWriter.addEvent(
                context = context,
                title = itemArg,
                startsAtMs = startMs,
                endsAtMs = endMs,
                allDay = allDay,
                source = DatesAspectSeeder.SOURCE_LEGION,
                kind = EventKind.EVENT,
            )
            com.kevin.legion.service.tools.day.EventMove.noteTouched(added.id)
            // The day it landed on takes the screen unless a card already shows it (LiveCards). The
            // row is saved by now: a failed card read must never turn this into "couldn't save".
            val zone = RelativeDay.clock().zone
            LiveCardReads.showDayAfterWrite(
                context,
                date.atStartOfDay(zone).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            )
            // `date` ahead of the words: the day it landed on, kept inside the audit line's cut so the
            // voice eval can check a carried day (DateCarry.carried) on the outcome, not the request.
            JSONObject().put("success", true).put("date", date.toString())
                // What the phone's done line names (2026-11-19), the same key the reminder branch
                // puts there. Without it CascadeRules finds no title and says "Done: added it."
                // After `date`, so the day stays inside the audit line's cut.
                .put("item", itemArg)
                .put(
                    "message",
                    ScheduleIntentResolver.confirmationPhrase(
                        ScheduleIntentResolver.Kind.Appointment, itemArg, whenPhrase,
                    ),
                )
        } catch (e: Exception) {
            // A genuine Room failure (disk full, etc) - worded, never a silent "added" - same "no
            // false success" contract every write in this file holds (CLAUDE.md §7). A push
            // failure to the server is NOT this branch - EventsAppointmentWriter.addEvent already
            // wrote the local row and enqueued a retry before returning normally in that case; only
            // the LOCAL write throwing reaches here.
            result(false, "I couldn't save that appointment - try again?")
        }
    }

    private suspend fun dispatchItemAction(
        context: Context, action: String, list: ItemList, item: com.kevin.legion.data.local.ListItem, args: JSONObject,
    ): JSONObject = when (action) {
        "tick" -> {
            // A recurring item cannot be ticked (ticket 04) - NotesController.tick refuses and
            // writes nothing rather than silently no-op'ing.
            if (NotesController.tick(context, item)) {
                result(true, "Checked off \"${item.text}\" on ${list.name}.")
            } else {
                result(
                    false,
                    "\"${item.text}\" repeats, so it can't be ticked off - skip today's occurrence " +
                        "instead, or change the repeat.",
                )
            }
        }
        // Senior review, 2026-08-24 (should-fix 3): "untick"/"remove" used to speak success
        // unconditionally, ignoring whether the engine write actually landed - a §7 outcome-verb
        // violation. NotesController.untick/removeItem now return a real Boolean; both branches
        // check it before ever claiming an outcome verb happened.
        "untick" -> {
            if (NotesController.untick(context, item)) {
                result(true, "Unticked \"${item.text}\" on ${list.name}.")
            } else {
                result(false, "Something went wrong unticking \"${item.text}\" - nothing changed.")
            }
        }
        // No confirmation on removing one item (ticket 05) - unlike deleting a whole list.
        "remove" -> {
            if (NotesController.removeItem(context, item)) {
                result(true, "Took \"${item.text}\" off ${list.name}.")
            } else {
                result(false, "Something went wrong removing \"${item.text}\" - it's still there.")
            }
        }
        "schedule" -> scheduleItem(context, list, item, args)
        "skip" -> {
            val date = parseNoteDate(args.optString("date"))
                ?: return result(false, "Which date should I skip? Give it as yyyy-MM-dd.")
            NotesController.skipOccurrence(context, item, date)
            result(true, "Skipping \"${item.text}\" on ${documentDate(date)}.")
        }
        else -> result(false, "I don't know how to do that with a list item.")
    }

    /**
     * Handles `manage_item`'s `schedule` action - a time and/or a repeat and/or an exact-alarm
     * request in one call, since setting up a recurring event ("gym every Monday at six") or an
     * exact one ("wake me up at exactly 6am") naturally needs more than one of these fields at
     * once. Setting a time always clears any place trigger first ([NotesController.setTime]'s own
     * contract) - "at most one trigger" (charting decision 4).
     *
     * Each `NotesController` call below RETURNS the item as it now stands and that return value,
     * not the original `item` parameter, feeds the next call - `NotesController.setTime`/
     * `setRepeat`/`setExact`'s own doc comments are explicit that chaining on the stale, pre-edit
     * item silently loses whichever field the previous call in this chain just set (ticket 03/04's
     * scheduling maths reads `startsAt`/`repeatKind`/`exact` straight off whatever `ListItem` it's
     * handed). **Senior review, 2026-08-24 (should-fix 3):** each of those three now returns
     * `ListItem?`, null on a failed engine write - this function bails out with a worded
     * `result(false, ...)` the instant one of them returns null, rather than silently chaining a
     * stale `current` forward and speaking success anyway (the §7 outcome-verb violation).
     */
    private suspend fun scheduleItem(
        context: Context, list: ItemList, item: com.kevin.legion.data.local.ListItem, args: JSONObject,
    ): JSONObject {
        var current = item

        val dateRaw = args.optString("date")
        if (dateRaw.isNotBlank()) {
            val date = parseNoteLocalDate(dateRaw)
                ?: return result(false, "I couldn't read that date - try yyyy-MM-dd.")
            val timeRaw = args.optString("time")
            val time = if (timeRaw.isNotBlank()) {
                parseNoteLocalTime(timeRaw)
                    ?: return result(false, "I couldn't read that time - try 24-hour HH:mm.")
            } else null

            // Compose date + time as one LOCAL date-time and resolve it in the
            // device zone (2026-08-07 audit fix). This used to add a UTC-midnight
            // date to a millis-since-midnight offset, which made every
            // voice-scheduled reminder fire off by the device's whole UTC offset:
            // in America/Chicago, "remind me at 6am" armed 06:00Z, i.e. 1am local,
            // five hours early, every time, with a success-shaped confirmation and
            // nothing anywhere to indicate it was wrong.
            //
            // atZone(ZoneId), not a fixed offset, so a spring-forward gap resolves
            // to the first valid instant rather than a time that does not exist.
            val zone = java.time.ZoneId.systemDefault()
            val startsAt = java.time.LocalDateTime
                .of(date, time ?: java.time.LocalTime.MIDNIGHT)
                .atZone(zone).toInstant().toEpochMilli()
            current = NotesController.setTime(context, current, startsAt, null, time == null)
                ?: return result(false, "Something went wrong setting that time on \"${current.text}\" - nothing changed.")
        }

        val repeatKindRaw = args.optString("repeat_kind").trim().lowercase()
        if (repeatKindRaw.isNotBlank()) {
            if (repeatKindRaw == "none") {
                current = NotesController.setRepeat(context, current, null, RepeatEnd.Never)
                    ?: return result(false, "Something went wrong clearing the repeat on \"${current.text}\" - nothing changed.")
            } else {
                val every = args.optInt("repeat_every", 1).coerceAtLeast(1)
                val rule = when (repeatKindRaw) {
                    "daily" -> RepeatRule.Daily(every)
                    "weekly" -> {
                        val days = parseWeekdays(args.optString("repeat_days"))
                        if (days.isNullOrEmpty()) return result(false, "Which day(s) of the week should that repeat on?")
                        RepeatRule.Weekly(every, days)
                    }
                    "monthly_on_date" -> {
                        val day = args.optInt("repeat_day", -1)
                        if (day !in 1..31) return result(false, "Which day of the month should that repeat on?")
                        RepeatRule.MonthlyOnDate(every, day)
                    }
                    "yearly" -> {
                        val month = args.optInt("repeat_month", -1)
                        val day = args.optInt("repeat_day", -1)
                        if (month !in 1..12 || day !in 1..31) return result(false, "Which month and day should that repeat on each year?")
                        RepeatRule.Yearly(month, day)
                    }
                    else -> return result(false, "I don't recognize that kind of repeat.")
                }
                val end = when (args.optString("repeat_end_kind").trim().lowercase()) {
                    "on_date" -> RepeatEnd.OnDate(
                        parseNoteDate(args.optString("repeat_end_date")) ?: return result(false, "When should that repeat stop?")
                    )
                    "after_count" -> {
                        val count = args.optInt("repeat_end_count", -1)
                        if (count < 1) return result(false, "How many times should that repeat?")
                        RepeatEnd.AfterCount(count)
                    }
                    else -> RepeatEnd.Never
                }
                current = NotesController.setRepeat(context, current, rule, end)
                    ?: return result(false, "Something went wrong setting that repeat on \"${current.text}\" - nothing changed.")
            }
        }

        // Exact alarms (ticket 03) - only ever requested explicitly, never implied. A downgrade
        // (permission refused) is said in the reply, not just persisted, per ticket 03's "downgrade
        // to inexact and say so in words - never fail quietly".
        var downgradeNotice = ""
        if (args.has("exact")) {
            val wantsExact = args.optBoolean("exact")
            current = NotesController.setExact(context, current, wantsExact)
                ?: return result(false, "Something went wrong setting the exact-alarm flag on \"${current.text}\" - nothing changed.")
            if (wantsExact && current.exactDowngraded) {
                downgradeNotice = " I can't get exact-alarm permission right now, so it'll fire " +
                    "approximately on time instead - you can grant it in Settings."
            }
        }

        return result(true, "Scheduled \"${current.text}\" on ${list.name}.$downgradeNotice")
    }

    // `manageGrocery` (dispatched `manage_grocery`: add / tick / untick / remove / read / suggest /
    // finish, addressing GroceryController's own short-lived trip table) DELETED one-today ticket
    // 10 slice B, 2026-09-05 - "everything is a checklist now". `dispatch`'s own "manage_grocery"
    // branch above returns a retirement message in words rather than calling this; manage_checklist
    // (list=Groceries) is the replacement, and its own dispatch fn (manageChecklist, just below)
    // covers the same add/tick/untick/remove/read shape through ChecklistController instead of
    // GroceryController. "suggest" and destructive "finish"-with-confirm have no checklist
    // equivalent yet - see GroceryStaple's own class doc for why staples suggestions do not survive
    // this retirement, and the ticket's own "what is knowingly lost" section for "finish"'s trip
    // teardown.

    /** Case-insensitive, trimmed match on [Checklist.name] - deliberately NOT fuzzy (unlike
     * [matchChecklistItem] for a line within a list): the brief calls for exact-modulo-case-and-
     * whitespace addressing of the list itself, so "bio" and "Bio " resolve the same checklist but
     * a near-miss name is reported unknown rather than silently guessed at. Archived checklists are
     * excluded, matching [ChecklistController.allChecklists]'s own default. */
    private suspend fun resolveChecklistByName(context: Context, name: String): Checklist? =
        // "Groceries" is the household's BUILT-IN list (2026-10-05): resolved by its system key,
        // never by name, so an old hand-made copy of the name can never be written to instead.
        if (isGroceriesName(name)) {
            ChecklistController.builtInGroceries(context)
        } else {
            ChecklistController.allChecklists(context).let { all ->
                all.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
                    // "to-do", "to do list", "my to-dos": the Todo checklist (ListRouting, 2026-11-15).
                    ?: if (ListRouting.isTodoName(name)) todoChecklist(context) else null
            }
        }

    private fun isGroceriesName(name: String) = name.trim().equals("Groceries", ignoreCase = true)

    /** Whole items that point at something without naming it: never a checklist line on their own. */
    private val BARE_REFERENCES = setOf(
        "it", "that", "this", "them", "those", "these", "something", "that one", "this one", "it all", "all of it",
    )

    private fun isBareReference(text: String): Boolean =
        text.lowercase().replace(Regex("[^\\p{L}' ]"), " ").trim().replace(Regex("\\s+"), " ") in BARE_REFERENCES

    /** `today`/`yesterday` (the only two the tool declares) -> a local epoch day, matching
     * [ChecklistController.tick]/[ChecklistController.untick]'s own `day` parameter. Anything
     * else (blank, or a value the model was not offered) defaults to today rather than failing the
     * whole call over an unrecognised word here - the tool schema is a closed enum, so this branch
     * only ever sees a value outside it if a caller bypasses the declared schema entirely. */
    private fun resolveChecklistDay(spec: String): Int = when (spec.trim().lowercase()) {
        "yesterday" -> ChecklistController.today() - 1
        else -> ChecklistController.today()
    }

    /**
     * Dispatches `manage_checklist`: create / add / tick / untick / remove / read / lists on a
     * user-authored named checklist (one-today ticket 09's voice slice). Follows manageItem/
     * manageGrocery's own shape immediately above - one `action` enum, one dispatch branch - and
     * calls straight into [ChecklistController], never re-implementing any of its rules (§7
     * "not a second implementation").
     */
    private suspend fun manageChecklist(context: Context, args: JSONObject): JSONObject {
        val action = args.optString("action").trim().lowercase()
        val listArg = args.optString("list").trim()

        if (action == "lists") {
            val lists = ChecklistController.allChecklists(context)
            if (lists.isEmpty()) {
                return result(true, "No checklists yet - say 'create a checklist called bio' to start one.")
            }
            val arr = JSONArray()
            for (cl in lists) {
                arr.put(JSONObject().put("name", cl.name).put("schedule", checklistScheduleLabel(cl)))
            }
            return JSONObject()
                .put("success", true)
                .put("count", lists.size)
                .put("lists", arr)
        }

        if (listArg.isBlank()) return result(false, "Which checklist?")

        if (action == "create") {
            if (isGroceriesName(listArg)) {
                // Never creates one: the household's engine makes the single built-in list.
                return if (resolveChecklistByName(context, listArg) != null) {
                    result(
                        false,
                        "Groceries is the household's built-in list, so it already exists - " +
                            "add to it instead. Nothing was created.",
                    )
                } else {
                    result(
                        false,
                        "${ChecklistController.GROCERIES_NOT_SYNCED} Nothing was created; try again in a moment.",
                    )
                }
            }
            val existing = resolveChecklistByName(context, listArg)
            if (existing != null) {
                return result(
                    false,
                    "There's already a checklist called \"${existing.name}\" - pick a different name or add to that one.",
                )
            }
            val scheduleArg = args.optString("schedule", "none").trim().lowercase()
            val scheduleKind: String?
            val scheduleEvery: Int?
            val scheduleDaysOfWeek: String?
            val scheduleWords: String
            when (scheduleArg) {
                "daily" -> {
                    scheduleKind = "DAILY"; scheduleEvery = 1; scheduleDaysOfWeek = null
                    scheduleWords = "daily"
                }
                "weekly" -> {
                    val daysRaw = args.optString("days").trim()
                    val days = parseWeekdays(daysRaw)
                    if (daysRaw.isBlank() || days.isNullOrEmpty()) {
                        return result(false, "A weekly checklist needs which days - say something like 'Monday, Wednesday, Friday'.")
                    }
                    scheduleKind = "WEEKLY"; scheduleEvery = 1; scheduleDaysOfWeek = formatWeekdays(days)
                    scheduleWords = "weekly, on ${scheduleDaysOfWeek.replace(",", ", ")}"
                }
                else -> {
                    scheduleKind = null; scheduleEvery = null; scheduleDaysOfWeek = null
                    scheduleWords = "with no schedule - it's done once you tick anything on it"
                }
            }
            ChecklistController.createChecklist(
                context,
                name = listArg,
                scheduleKind = scheduleKind,
                scheduleEvery = scheduleEvery,
                scheduleDaysOfWeek = scheduleDaysOfWeek,
            )
            // `list`: what the phone's done line names ("created the Camping list"), never the message.
            return result(true, "Created the \"$listArg\" checklist, $scheduleWords.").put("list", listArg)
        }

        // Every action from here on addresses an EXISTING checklist by name. The Todo checklist is
        // made on its first add (the to-do list must exist to be written to; ListRouting).
        if (action == "add" && ListRouting.isTodoName(listArg) && resolveChecklistByName(context, listArg) == null) {
            todoChecklistOrNew(context)
        }
        val checklist = resolveChecklistByName(context, listArg)
            ?: return result(
                false,
                if (isGroceriesName(listArg)) {
                    "${ChecklistController.GROCERIES_NOT_SYNCED} Nothing was changed; try again in a moment."
                } else {
                    "No checklist named \"$listArg\" - say 'lists' to hear them."
                },
            )

        when (action) {
            "add" -> {
                // `item` accepted as an alias for `text` (2026-09-06). Observed on the phone from
                // `conversation_audit`: the model sent `item` (which IS the right parameter for
                // tick/untick/remove) on an `add`, was refused with "What should I add to
                // "Groceries"?", called `lists` to re-orient, then retried with `text` - three
                // calls and about three seconds for an unambiguous request. The meaning of an
                // `add` carrying only `item` is not in doubt, so it is taken; the tool description
                // says so in one clause, because an alias the description hides is a rule the
                // model can only find by failing.
                val itemAlias = args.optString("item").trim()
                val textArg = args.optString("text").trim().ifBlank { itemAlias }
                if (textArg.isBlank()) {
                    // Says the parameter, not just the question - CLAUDE.md section 7 wants a
                    // failure that states what did NOT happen AND what would fix it. The "you
                    // sent" clause appears only when the key really was present (empty, since a
                    // non-empty one would have been taken above), never as a guess about what the
                    // caller did.
                    val sentEmptyItem = if (args.has("item")) " (you sent \"item\", and it was empty)" else ""
                    return result(
                        false,
                        "add needs the line in \"text\"$sentEmptyItem; nothing was added to " +
                            "\"${checklist.name}\".",
                    )
                }
                // Phone audit 2026-11-19: a line literally named "it" was added. A closed word list,
                // matched on the whole item only, never a reading of what he meant.
                if (isBareReference(textArg)) {
                    return result(false, "Nothing was added: \"$textArg\" names nothing. What should I add?")
                        .put("needs_answer", true)
                }
                val unit = args.optString("unit").trim().ifBlank { null }
                val target = if (args.has("target") && !args.isNull("target")) args.optDouble("target") else null
                val direction = when (args.optString("direction").trim().lowercase()) {
                    "at_least" -> MeasureDirection.AT_LEAST.name
                    "at_most" -> MeasureDirection.AT_MOST.name
                    else -> null
                }
                val sortOrder = ChecklistController.itemsFor(context, checklist.id).size
                val added = ChecklistController.addItem(
                    context,
                    checklistId = checklist.id,
                    text = textArg,
                    sortOrder = sortOrder,
                    measureUnit = unit,
                    measureTarget = target,
                    measureDirection = direction,
                )
                // `item` and `list`: what the phone's done line names (2026-11-19), never the message.
                return result(true, "Added \"${added.text}\" to \"${checklist.name}\".")
                    .put("item", added.text).put("list", checklist.name)
            }

            "read" -> {
                return when (val loaded = ChecklistController.itemsWithTickState(context, checklist.id)) {
                    is ChecklistController.ChecklistItemsResult.Failed ->
                        result(false, "Could not read \"${checklist.name}\". Try again shortly.")
                    is ChecklistController.ChecklistItemsResult.Loaded -> {
                        val lines = loaded.items.map { ChecklistLine(it.item.text, it.ticked) }
                        ToolCards.publish(LiveCardReads.checklistCard(checklist.id, checklist.name, lines))
                        if (loaded.items.isEmpty()) {
                            return result(true, "\"${checklist.name}\" has no items yet.").also {
                                if (ListRouting.isTodoName(checklist.name)) withReminders(context, it)
                            }
                        }
                        val arr = JSONArray()
                        for (state in loaded.items) {
                            val line = JSONObject()
                                .put("text", state.item.text)
                                .put("ticked", state.ticked)
                            if (state.item.measureUnit != null) {
                                // Same phrasing the checklist screen itself renders
                                // (checklists/ChecklistDayViewLogic.kt) - "8,400 / 10,000 steps" -
                                // so the number spoken and the number shown never drift apart
                                // (ADR 0035: one implementation, not a second one for voice).
                                line.put(
                                    "measured",
                                    if (state.value != null) measureValueDisplay(state.item, state.value)
                                    else "not recorded yet, in ${state.item.measureUnit}",
                                )
                            }
                            arr.put(line)
                        }
                        JSONObject()
                            .put("success", true)
                            .put("list", checklist.name)
                            .put("count", loaded.items.size)
                            .put("items", arr)
                            .also { if (ListRouting.isTodoName(checklist.name)) withReminders(context, it) }
                    }
                }
            }

            // 2026-11-10 (Kevin, 74 ticked grocery lines): 'ticked' lists and counts them,
            // 'clear_ticked' removes them all in one call - both through ChecklistController.
            "ticked" -> return tickedLines(context, checklist)
            "clear_ticked" -> return when (val outcome = ChecklistController.clearTicked(context, checklist.id)) {
                is ChecklistController.ClearTickedOutcome.Refused -> result(false, outcome.message)
                is ChecklistController.ClearTickedOutcome.Cleared ->
                    if (outcome.items.isEmpty()) {
                        result(true, "Nothing on \"${checklist.name}\" is ticked, so nothing was removed.")
                    } else {
                        result(true, "Removed ${outcome.items.size} ticked items from \"${checklist.name}\".")
                            .put("removed_count", outcome.items.size)
                    }
            }

            "tick", "untick", "remove" -> {
                val itemArg = args.optString("item").trim()
                if (itemArg.isBlank()) return result(false, "Which item on \"${checklist.name}\"?")
                val items = ChecklistController.itemsFor(context, checklist.id)
                return when (val match = matchChecklistItem(itemArg, items)) {
                    is ChecklistItemMatch.NoMatch -> {
                        val notHere = result(false, "I don't see \"$itemArg\" on \"${checklist.name}\" - add it?")
                        // 2026-11-15: "tick off the homework" sent to the to-do list. On Todo, a calendar
                        // task by those words is completed instead, and the result says so; any other
                        // list only names the tool (Groceries and routines never tick a task).
                        if (action == "remove") {
                            notHere
                        } else if (!ListRouting.isTodoName(checklist.name)) {
                            noteRoute(notHere, "If it is a calendar task (an assignment), use day complete_task.")
                            notHere
                        } else {
                            completeTask(
                                context,
                                JSONObject().put("title", itemArg).put("done", action == "tick"),
                                onNoMatch = { notHere },
                            ).also { if (it.optBoolean("success")) noteRoute(it, TASK_NOT_CHECKLIST) }
                        }
                    }
                    is ChecklistItemMatch.Ambiguous -> result(
                        false,
                        "Which one on \"${checklist.name}\"? " + match.candidates.joinToString(", ") { it.text },
                    )
                    is ChecklistItemMatch.Resolved -> when (action) {
                        "tick" -> {
                            val day = resolveChecklistDay(args.optString("day"))
                            val value = if (args.has("value") && !args.isNull("value")) args.optDouble("value") else null
                            when (val outcome = ChecklistController.tick(context, match.item.id, day = day, value = value)) {
                                // Verbatim - the controller's own message already says what did NOT
                                // happen (§7's outcome-verb rule: nothing was recorded, and this
                                // must not paraphrase that into something that reads like success).
                                is ChecklistController.TickOutcome.Refused -> result(false, outcome.message)
                                ChecklistController.TickOutcome.Ticked ->
                                    result(true, "Ticked \"${match.item.text}\" on \"${checklist.name}\".")
                                        .put("item", match.item.text).put("list", checklist.name)
                            }
                        }
                        "untick" -> {
                            val day = resolveChecklistDay(args.optString("day"))
                            ChecklistController.untick(context, match.item.id, day = day)
                            result(true, "Unticked \"${match.item.text}\" on \"${checklist.name}\".")
                        }
                        "remove" -> {
                            ChecklistController.deleteItem(context, match.item.id)
                            result(true, "Removed \"${match.item.text}\" from \"${checklist.name}\".")
                        }
                        else -> result(false, "I don't know how to do that with a checklist.")
                    }
                }
            }

            else -> return result(false, "I don't know how to do that with a checklist.")
        }
    }

    /** `manage_checklist` action=ticked: the ticked lines of [checklist] and how many there are. */
    private suspend fun tickedLines(context: Context, checklist: Checklist): JSONObject =
        when (val loaded = ChecklistController.itemsWithTickState(context, checklist.id)) {
            is ChecklistController.ChecklistItemsResult.Failed ->
                result(false, "Could not read \"${checklist.name}\". Try again shortly.")
            is ChecklistController.ChecklistItemsResult.Loaded -> {
                val ticked = loaded.items.filter { it.ticked }
                JSONObject()
                    .put("success", true)
                    .put("list", checklist.name)
                    .put("ticked_count", ticked.size)
                    .put("open_count", loaded.items.size - ticked.size)
                    .put("ticked", JSONArray(ticked.map { it.item.text }))
            }
        }

    /**
     * `get_last_ticked` - ticket 04 of web-calendar-and-lists ("when did I last buy toothpaste").
     * Reads [TickHistoryController.lastTicked], never [PantryController] - a tick carries no price
     * and nothing reconciled it (CLAUDE.md §4 rule 5), so this can never answer "how much" or
     * "what did I spend", only "when was this line last tapped".
     *
     * **The wording is the whole point of this tool** (ticket 04's own table). Every string here
     * says "ticked", never "bought". An empty result says "no record of ticking", never "never
     * bought" - an absent tick is an absent RECORD, not an absent purchase (§1's
     * empty-versus-unreadable distinction, applied to a tick). [LiveToolboxLastTickedWordingTest]
     * pins this by grepping the tool's own description for a purchase claim; nothing greps the
     * spoken strings this function returns, so their wording is reviewed here by hand instead.
     */
    private suspend fun getLastTicked(context: Context, item: String): JSONObject {
        val query = item.trim()
        if (query.isBlank()) return result(false, "Which item?")

        val matches = TickHistoryController.lastTicked(context, query)
        return if (matches.isEmpty()) {
            result(
                true,
                "I have no record of \"$query\" being ticked off any list - that's just an " +
                    "absent record, not proof of anything about whether you got it.",
            )
        } else {
            lastTickedFound(query, matches)
        }
    }

    /** The found half of [getLastTicked] - split out so that function has one guard clause and one
     * branch, not three returns, per detekt's [ReturnCount]. */
    private fun lastTickedFound(query: String, matches: List<TickMatch>): JSONObject {
        val latest = matches.first()
        val history = JSONArray()
        for (m in matches.take(10)) {
            history.put(
                JSONObject()
                    .put("checklist", m.checklistName)
                    .put("tickedAtMs", m.tickedAt)
                    .put("date", shortDate(m.tickedAt)),
            )
        }
        return JSONObject()
            .put("success", true)
            .put("item", query)
            .put("lastTickedAtMs", latest.tickedAt)
            .put("lastTickedDate", shortDate(latest.tickedAt))
            .put("lastTickedChecklist", latest.checklistName)
            .put("history", history)
            .put(
                "message",
                "You ticked \"$query\" off \"${latest.checklistName}\" on ${shortDate(latest.tickedAt)} " +
                    "(${relativeAge(latest.tickedAt)}) - that's the last time it was ticked, not a " +
                    "purchase record.",
            )
    }

    /**
     * Dispatches `read_list`: the one list's items, soonest due date first.
     *
     * Ordering is [com.kevin.legion.ui.notes.buildInboxRows]' - the SAME resolver the screen uses,
     * so what the assistant reads out and what the driver sees are one order, not two that drift.
     * Every dated item carries its date into the JSON; a read-back that omitted the date is how a
     * dateless item passed for a scheduled one for as long as it did.
     */
    private suspend fun readList(context: Context): JSONObject {
        val items = NotesController.allItems(context)
        val rows = buildInboxRows(items, System.currentTimeMillis())
        ToolCards.publish(LiveCardReads.remindersCard(rows.map { ChecklistLine(it.text, it.done) }))
        val arr = JSONArray()
        for (row in rows) {
            val o = JSONObject()
                .put("text", row.text)
                .put("done", row.done)
                // Stated in words for BOTH states. Omitting the key for an undated item would let
                // the model read a missing field as "I just wasn't told", and fill the gap.
                .put("due", row.dateLabel ?: "no date set")
            if (row.overdue) o.put("overdue", true)
            row.placeLabel?.let { o.put("place", it) }
            if (row.recurring) o.put("repeats", true)
            arr.put(o)
        }
        val out = JSONObject()
            .put("success", true)
            .put("count", rows.size)
            .put("open_count", rows.count { !it.done })
            .put("items", arr)
        // "What's on my to-do list" is ambiguous (ListRouting): the Todo checklist rides along.
        openTodoLines(context)?.takeIf { it.isNotEmpty() }?.let { out.put(ListRouting.TODO_KEY, JSONArray(it)) }
        return out
    }

    /**
     * Delegates to the diagnostics specialist ([DiagnosticAgent]). Codes the
     * driver named explicitly win; otherwise we read whatever is on the port now
     * and hand both the codes and the question to the worker, then return its
     * spoken-friendly text for Zero to read out. This is an extra round-trip
     * mid-conversation, so Zero typically says "let me check..." first.
     */
    /**
     * Fast, offline read of the stored codes themselves - no causal reasoning,
     * no sub-agent call. Names come from the SAME two-tier lookup
     * [DtcDescriptions] gives [com.kevin.legion.ui.DtcSheet] (bundled seed
     * dictionary, then this install's disk cache of previously agent-answered
     * codes) - but unlike [diagnoseCodes], a code missing from BOTH never
     * escalates to the diagnostics sub-agent here. That escalation is what
     * makes diagnose_codes slow; skipping it is the whole point of this tool
     * existing separately (`.scratch/glance-cards/issues/01-*`). An unknown
     * code never blocks this tool on a Gemini call: since 2026-11-03 it is
     * looked up in the BACKGROUND ([com.kevin.legion.vehicle.DtcLookup]) and
     * the glance card is redrawn with the AI-labelled answer when it lands.
     */
    /**
     * Dispatches `get_current_time` (Kevin, 2026-08-07: "ai is telling me its 413 am in katy
     * texas. its 11:13 PM").
     *
     * **The model has no clock.** The system instruction stated only `Today's date is ...` and
     * never a time of day, so every "what time is it" answer was invention - and it landed on
     * 04:13, which is UTC, exactly five hours off America/Chicago. Handing it a real reading is
     * the CLAUDE.md §7 "pull-based tools, not pre-injected context" answer, and it is also the
     * only one that stays correct in a long conversation: a time baked into the prompt is stale
     * the moment it is uploaded, whereas this is read at the moment it is asked.
     *
     * The zone's name is returned alongside the time, not just the offset, so the model can say
     * "central time" rather than reciting a number, and so a wrong DEVICE timezone (the one thing
     * this tool cannot fix) is visible in the answer rather than silently baked into it.
     *
     * **The DISPLAY name, never the IANA id (2026-08-20).** This returned `America/Chicago`, and
     * Kevin - who lives in Houston - started hearing about Chicago. A zone id is a database key
     * that happens to contain a city, and handing one to a model that speaks is handing it a place
     * name to mention. "Central Daylight Time" serves this doc's own stated purpose better anyway:
     * it is what the doc says it wants the model to say, and it names no city to get wrong.
     */
    private fun getCurrentTime(): JSONObject {
        val now = RelativeDay.now()
        val zone = now.zone
        val stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"))
        val zoneName = zone.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        // The day as a date too, with tomorrow's (2026-11-15: no date arithmetic left to the model).
        val tomorrow = now.toLocalDate().plusDays(1)
        val tomorrowDay = tomorrow.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
        return result(
            true,
            "$stamp ($zoneName, the phone's own clock). Today is ${now.toLocalDate()}; " +
                "tomorrow is $tomorrowDay $tomorrow.",
        )
    }

    /**
     * Gates, then delegates to a specialist and maps its [AgentResult] to the
     * tool-response JSON, phrasing each failure kind in character and recording
     * key health for the Setup screen. [call] is only invoked (spending a real
     * Gemini call) once a key is actually saved - no tiers anymore (the
     * commercial model was retired 2026-07-31), every install is BYO-key only.
     *
     * [requireMutation] (2026-08-17): when true, a [AgentResult.Success] whose
     * [AgentResult.Success.mutatingToolsCalled] came back empty is reported as a FAILURE, never
     * the sub-agent's own prose - see [successOrMutationRefusal] (pulled out as a pure function so
     * this is unit-testable without a real Gemini call, same shape as [LiveSessionController]'s
     * `shouldRestoreAfterToolCall`).
     *
     * **Switched on 2026-08-18 for the five `ask_<domain>` dispatches, via [wantsWrite].** It
     * landed defaulting to `false` everywhere because [dispatch] then saw nothing but free prose,
     * and no reliable, non-guessing reading of that prose separates "log my three sets of squats"
     * (a write) from "what did I lift last week" (a read) - both are legitimate questions the SAME
     * dispatcher answers, so turning it on unconditionally would have refused every read that
     * happened not to need a mutating tool, and refused EVERY call to a domain with no mutating
     * tool at all (goals/mail). That doc comment named its own way out: "a dispatcher schema later
     * extended with an explicit intent argument the model itself declares." That argument now
     * exists ([DISPATCHER_PARAMS]' `intent`), so nothing here is guessing - the model states it,
     * and an absent or unrecognised value reads as a read, i.e. exactly the old behaviour.
     *
     * This is what makes 2026-08-17's reported failure mechanically impossible rather than merely
     * less likely: a `record`-intent request routed to `ask_goals` finds a domain whose
     * [mutatingToolsFor] set is empty, so `mutatingToolsCalled` comes back empty by construction,
     * so the driver is told it was NOT written down instead of being told it was recorded.
     *
     * Every other [agentResult] caller in this file still defaults to `false`; none of them is
     * domain-shaped for a write.
     */
    /**
     * Phone audit 2026-11-10: asked about a car the household does not own (another make), the
     * fleet lookup read the household's own car instead, and the answer named that car and read its
     * codes. A question that names only a make no car on file has no answer in these records: say
     * so, name the cars there are, and point the answer at general knowledge or a search.
     */
    /**
     * The two fleet questions answered by code, never by the fleet helper model (2026-11-16, stage 7
     * fleet eval):
     * - a request to clear or erase codes: ask_fleet only reads (its tool list holds no write), so it
     *   is refused in words that name the car tool's clear_codes, whatever `intent` the model set;
     * - a module scan ("scan every module", "codes in all modules"): run here through
     *   [com.kevin.legion.vehicle.diag.DiagVoice.scan], whose `say` is built by code
     *   ([com.kevin.legion.vehicle.diag.ScanSay]). A scan only reads, so a `record` intent the model set on it is ignored rather than refused (the eval saw
     *   "can't write" spoken for a scan).
     * Null for every other question, which goes to the helper as before.
     */
    private suspend fun fleetByCode(context: Context, args: JSONObject): JSONObject? {
        val question = args.optString("question")
        return when {
            isFleetClearRequest(question) -> result(false, FLEET_NO_CLEAR)
            com.kevin.legion.vehicle.diag.ScanSay.isModuleScanQuestion(question) ->
                com.kevin.legion.vehicle.diag.DiagVoice.scan(context)
            else -> null
        }
    }

    /**
     * A request to clear, not a question about past clears: "clear the codes" is one, "when did I last
     * clear the codes" and "did the clear take" are code-history reads and stay with the helper.
     */
    internal fun isFleetClearRequest(question: String): Boolean =
        com.kevin.legion.vehicle.ClearCodesVoiceGate.asksToClear(question) && !CLEAR_HISTORY.containsMatchIn(question)

    private val CLEAR_HISTORY =
        Regex("\\b(when|last|history|ever|did|was|were|been|cleared|how long)\\b", RegexOption.IGNORE_CASE)

    internal const val FLEET_NO_CLEAR = "Nothing was cleared: ask_fleet only reads. Clearing trouble codes is the " +
        "car tool's clear_codes, which asks him first and clears only after his plain yes."

    private suspend fun fleetNotOnFile(context: Context, args: JSONObject): JSONObject? {
        val roster = com.kevin.legion.vehicle.FleetEngineStore.getAll(context)
        val make = VehicleResolver.makeNotOnFile(args.optString("question"), roster) ?: return null
        val cars = roster.joinToString { com.kevin.legion.vehicle.VehicleController.label(it) }
        return result(
            false,
            "No ${make.replaceFirstChar { it.uppercase() }} is on file (the cars on file: $cars), so the car " +
                "records have nothing on it and nothing was read. Answer from general knowledge or a search, " +
                "say that is where it came from, and never answer it with another car's records.",
        )
    }

    internal suspend fun agentResult(
        failMessage: String,
        requireMutation: Boolean = false,
        lookupName: String = "lookup",
        call: suspend () -> AgentResult,
    ): JSONObject {
        if (!GeminiKeyProvider.hasKey()) {
            return result(
                false,
                "No Gemini key is set, so this cannot run. One can be added in Setup.",
            )
        }
        return when (val r = call()) {
            is AgentResult.Success -> {
                KeyHealth.noteOk()
                successOrMutationRefusal(r.text, r.mutatingToolsCalled, requireMutation).also { out ->
                    // A routed balance read: the phone speaks its words itself (VerbatimSpeech).
                    if (r.verbatim != null && r.verbatimFrom != null && out.optBoolean("success")) {
                        out.put(com.kevin.legion.ai.VerbatimSpeech.KEY, r.verbatim)
                        out.put(com.kevin.legion.ai.VerbatimSpeech.FROM, r.verbatimFrom)
                    }
                }
            }
            AgentResult.RateLimited -> {
                KeyHealth.noteRateLimited()
                result(false, "The Gemini key hit its rate limit. Try again in a minute.")
            }
            AgentResult.KeyInvalid -> {
                KeyHealth.noteInvalid()
                result(false, "The Gemini key was rejected. It can be checked in Setup.")
            }
            AgentResult.Offline ->
                result(false, "No network connection. Try again once online.")
            AgentResult.Failed, AgentResult.Overloaded -> result(false, failMessage)
            is AgentResult.UnknownTool -> result(false, unknownToolMessage(r, lookupName))
        }
    }

    /**
     * What [agentResult] says when the specialist WAS reached but asked only for tools that do not
     * exist (2026-11-03: `check_recall` was spoken as "Could not reach the fleet specialist").
     * Says what happened and that nothing was checked. Pure and internal so a test pins it.
     */
    internal fun unknownToolMessage(r: AgentResult.UnknownTool, lookupName: String = "lookup"): String =
        "The $lookupName asked for a tool that doesn't exist (${r.requested.distinct().joinToString(", ")}), " +
            "so nothing was checked. Ask again and it will try the real tools."

    /**
     * Runs one `ask_<domain>` dispatcher's [SubAgent.route] with a fresh [LookupProbe] named [tool],
     * then writes its `route_ms=` LIVE_LATENCY line (async-lookups ticket 03). A line per call, the
     * failures included, so a capture can say which stage a slow or failed lookup spent its time in.
     */
    /**
     * `ask_body`'s route, also run for a training question that reached `ask_goals`
     * ([CoachRouting.isWorkoutQuestion]). Its answer carries [BodyCoachText.COACH_SAY] beside the
     * figures ([withCoachNote]).
     */
    private suspend fun askBody(context: Context, name: String, args: JSONObject): JSONObject =
        agentResult(
            "Could not reach the health specialist. Try again shortly.",
            requireMutation = wantsWrite(args),
            lookupName = "health lookup",
        ) {
            withCoachNote(
                probedRoute(name) { probe ->
                    SubAgent(systemInstruction = bodyDispatchGrounding(context)).route(
                        question = args.optString("question"),
                        tools = agentToolsFor("body", context),
                        budgetMs = DISPATCH_ROUTE_BUDGET_MS,
                        mutatingToolNames = mutatingToolsFor("body"),
                        probe = probe,
                    )
                },
            )
        }

    /** A body answer with the coach's way of saying it appended (coach voice fixes, 2026-11-06). */
    internal fun withCoachNote(r: AgentResult): AgentResult =
        if (r is AgentResult.Success) r.copy(text = r.text + "\n" + BodyCoachText.COACH_SAY) else r

    private suspend fun probedRoute(
        tool: String,
        run: suspend (com.kevin.legion.ai.LookupProbe) -> AgentResult,
    ): AgentResult {
        val probe = com.kevin.legion.ai.LookupProbe(tool)
        return run(probe).also { LiveLatency.log(LiveLatency.TAG, LiveLatency.routeLine(probe, it)) }
    }

    /** One client for the process so its short `tools/list` cache survives between utterances;
     * it reads the engine address and token live from [EngineConfig] on every call. */
    private var engineMcpClient: EngineMcpClient? = null

    /**
     * `ask_engine` (engine-mcp ticket 11). Same Gemini-key guard and key-health bookkeeping as
     * [agentResult]; everything about WHAT may cross to the engine lives in [AskEngine].
     */
    private suspend fun askEngine(
        context: Context,
        args: JSONObject,
        touchedReadThroughTool: Boolean,
    ): JSONObject {
        if (!GeminiKeyProvider.hasKey()) {
            return result(
                false,
                "No Gemini key is set, so this cannot run. One can be added in Setup. Nothing was read or written.",
            )
        }
        val client = engineMcpClient ?: EngineConfig(context.applicationContext).let { config ->
            EngineMcpClient(EngineHttp(config), config::baseUrl).also { engineMcpClient = it }
        }
        // Debug-selectable (2026-10-27): Flash-Lite by default, 3.8 Flash for a latency comparison.
        // Logged per call so a LIVE_LATENCY capture says which model served it: the probe's model,
        // which is the fallback's when one fired (async-lookups ticket 03), not the one asked for.
        val model = DebugSettings.askEngineModel(context)
        // Coach voice fixes (2026-11-06): a lock by voice wrote today's target on the engine while
        // the phone kept answering "what's left" from its own copy. Pull once after such a write.
        val movedTheTarget = java.util.concurrent.atomic.AtomicBoolean(false)
        return AskEngine(
            client = client,
            runAgent = { instruction, question, tools ->
                val startedAt = System.currentTimeMillis()
                val probe = com.kevin.legion.ai.LookupProbe("ask_engine")
                SubAgent(systemInstruction = instruction, useSearch = false, model = model).investigate(
                    context = "",
                    question = question,
                    tools = tools,
                    maxModelCalls = 4,
                    budgetMs = 40_000,
                    probe = probe,
                ).also { r ->
                    LiveLatency.log(
                        LiveLatency.TAG,
                        "ask_engine_ms=${System.currentTimeMillis() - startedAt} model=${probe.model} " +
                            "result=${r::class.simpleName}",
                    )
                    LiveLatency.log(LiveLatency.TAG, LiveLatency.investigateLine(probe, r))
                }
            },
            identityClause = { AssistantIdentity.shortClause(context) },
            nowText = { java.time.OffsetDateTime.now().toString() },
            // Round 5 (conditions): the live fix for a point-taking engine tool. Location only, never
            // a guessed one: no fix is a blank, and the engine then falls back to a saved place.
            whereText = { com.kevin.legion.conditions.ConditionsWords.fix(LocationController.state.value) },
            onAgentResult = { r ->
                when (r) {
                    is AgentResult.Success -> KeyHealth.noteOk()
                    AgentResult.RateLimited -> KeyHealth.noteRateLimited()
                    AgentResult.KeyInvalid -> KeyHealth.noteInvalid()
                    else -> Unit
                }
            },
            onToolResult = { answer ->
                ToolCards.engineResult(answer)
                if (CoachRouting.movesTheMealTarget(answer)) movedTheTarget.set(true)
            },
        ).ask(args.optString("question"), wantsWrite(args), touchedReadThroughTool)
            .also { if (movedTheTarget.get()) runCatching { com.kevin.legion.backend.BodySync.pullNow(context) } }
            .toJson()
    }

    /** "the Jeep Cherokee", or the label as-is when it already starts with an article. */
    internal fun theCar(label: String): String =
        if (label.startsWith("a ") || label.startsWith("an ")) label else "the $label"

    /** [withResolvedVehicle]'s answer when no car was named and several are on file. Internal for a test. */
    internal fun noneChosenResult(match: VehicleMatch.NoneChosen): JSONObject = JSONObject()
        .put("success", false)
        .put("error", "which_vehicle")
        .put("candidates", JSONArray(match.candidates))
        .put(
            "message",
            "No car was named and none is connected or picked, so nothing was looked up. On file: " +
                "${match.candidates.joinToString(", ")}. Which one?",
        )

    // --------------------------------------------------------------- voice notes (ticket 04)

    /** Both branches route through [VoiceNoteController.start] - the same call
     * `ui/voicenotes/VoiceNotesScreen.kt`'s record button makes (ADR 0035). */
    private suspend fun startVoiceNote(context: Context, args: JSONObject): JSONObject {
        val kind = args.optString("kind", VoiceNoteKind.SOLO).lowercase().let {
            if (it == "meeting") VoiceNoteKind.MEETING else VoiceNoteKind.SOLO
        }
        val title = args.optString("title", "").ifBlank { null }
        return when (val started = VoiceNoteController.start(context, kind, title)) {
            is VoiceNoteStartResult.Started ->
                result(success = true, message = "Recording started.")
            is VoiceNoteStartResult.Refused ->
                result(success = false, message = started.reason)
        }
    }

    /**
     * The outcome-verb rule, load-bearing here (ticket 04's own words): [VoiceNoteController.stop]
     * returns before transcription has even started, so this NEVER says the note is ready,
     * summarized, or readable yet - only that it saved and is being transcribed. Same call
     * `ui/voicenotes/VoiceNotesScreen.kt`'s stop button makes.
     */
    private suspend fun stopVoiceNote(context: Context): JSONObject =
        when (VoiceNoteController.stop(context)) {
            is VoiceNoteController.StopOutcome.Saved ->
                result(success = true, message = "Recording saved and being transcribed now - ask " +
                    "me to read it back in a bit.")
            VoiceNoteController.StopOutcome.NothingRecording ->
                result(success = false, message = "There's no voice note recording right now.")
        }

    /** [VoiceNoteController.findByTitleOrLatest] is the same lookup the hands-path detail screen's
     * "open by title" search would use, though the screen more often just taps a row. Wording here
     * always says whether the summary is model-derived, and whether the note is incomplete
     * (interrupted, or still transcribing) - CLAUDE.md §4 rule 5 / ticket 04's derived-in-words
     * rule, applied to speech. */
    private suspend fun readVoiceNote(context: Context, args: JSONObject): JSONObject {
        val titleQuery = args.optString("title", "").ifBlank { null }
        val note = VoiceNoteController.findByTitleOrLatest(context, titleQuery)
            ?: return result(
                success = false,
                message = if (titleQuery != null) "I couldn't find a voice note called \"$titleQuery\"."
                else "There are no voice notes yet.",
            )
        val label = note.title ?: "Untitled recording"
        val summary = note.summary
        val sb = StringBuilder()
        sb.append("\"$label\": ")
        if (summary == null) {
            sb.append("this one hasn't been transcribed yet - the audio is saved, try again shortly.")
        } else {
            sb.append("(AI-generated summary, not a verbatim account) ")
            sb.append(summary)
        }
        if (note.interrupted) {
            sb.append(" This recording was interrupted before it finished, so it may be incomplete.")
        }
        return result(success = true, message = sb.toString())
    }

    /** Read-only list, same rows [VoiceNoteController.listNotes] hands the hands-path screen. */
    private suspend fun listVoiceNotes(context: Context, args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 10).coerceIn(1, 50)
        val kindFilter = args.optString("kind", "").ifBlank { null }?.lowercase()?.let {
            if (it == "meeting") VoiceNoteKind.MEETING else VoiceNoteKind.SOLO
        }
        val notes = VoiceNoteController.listNotes(context, limit = 200)
            .let { if (kindFilter != null) it.filter { n -> n.kind == kindFilter } else it }
            .take(limit)
        if (notes.isEmpty()) return result(success = true, message = "No voice notes yet.")
        val lines = notes.map { note ->
            val label = note.title ?: "Untitled recording"
            val kindWord = if (note.kind == VoiceNoteKind.MEETING) "meeting" else "solo"
            val status = when {
                note.interrupted -> "interrupted"
                note.summary == null -> "still transcribing"
                else -> "ready"
            }
            "$label ($kindWord, $status)"
        }
        return result(success = true, message = lines.joinToString("; "))
    }

    /** Brings our own app to the foreground on request. */
    private fun showApp(context: Context): JSONObject {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) }
            ?: return result(success = false, message = "I couldn't bring the app up.")
        return try {
            context.startActivity(intent)
            result(success = true, message = null)
        } catch (e: Exception) {
            result(success = false, message = "I couldn't bring the app up.")
        }
    }

    /**
     * Function declarations for the conversational first-run onboarding (see
     * [com.kevin.legion.ai.buildOnboardingInstruction]). These are advertised ONLY
     * during onboarding, in place of the normal toolset - the companion has no
     * persona or car data yet, so the only tools it needs are the ones that
     * capture the answers it's collecting. Dispatch lives in the hosting screen
     * ([com.kevin.legion.ui.ConversationalOnboardingScreen]), not [dispatch], since
     * the captured values drive that screen's state and the step transition.
     */
    fun onboardingDeclarations(): JSONArray {
        val fns = JSONArray()

        fns.put(fn(
            name = "set_companion_name",
            description = "Save the name the user chose for you (the companion). Call this as soon " +
                "as they tell you what to call you.",
            params = obj("name" to schema("string", "The name the user wants to call you.")),
            required = listOf("name"),
        ))

        fns.put(fn(
            name = "set_personality",
            description = "Save the personality you've shaped together. Call this once you have a feel " +
                "for who you are.",
            params = obj(
                "description" to schema("string", "A short second-person persona summary written as an " +
                    "instruction to yourself, e.g. 'You are warm and easygoing, quick with a dry joke, " +
                    "and you treat the user like an old friend.'"),
                "look" to schema("string", "A brief visual descriptor of how you'd appear as a " +
                    "character, used to draw your face, e.g. 'a laid-back, warm-eyed retro mascot'."),
            ),
            required = listOf("description"),
        ))

        fns.put(fn(
            name = "set_driver",
            description = "Save who the user is. Call this after they tell you their name and " +
                "anything they want you to know about them.",
            params = obj(
                "name" to schema("string", "The user's name."),
                "about" to schema("string", "Optional: anything the user wants you to know about them."),
            ),
            required = listOf("name"),
        ))

        fns.put(fn(
            name = "register_car",
            description = "Save the car the user owns. Call this once they tell you what they drive. " +
                "Leave fields blank if they don't know or skip them.",
            params = obj(
                "year" to schema("integer", "The car's model year, e.g. 2003."),
                "make" to schema("string", "The make, e.g. BMW, Jeep, Honda."),
                "model" to schema("string", "The model, e.g. 330i, Cherokee, Civic."),
                "trim" to schema("string", "Optional trim, e.g. ZHP, Type R."),
            ),
            required = listOf("make", "model"),
        ))

        fns.put(fn(
            name = "finish_intro",
            description = "End the spoken setup and move on to the visual face + voice steps. Call this " +
                "last, once you have at least a name and a personality.",
            params = obj(),
            required = listOf(),
        ))

        return fns
    }


    internal fun result(success: Boolean, message: String?): JSONObject =
        JSONObject().put("success", success).apply { if (message != null) put("message", message) }

    /** A car write named no item: nothing is written, and the result asks him which ([needs_answer]). */
    internal fun noItem(question: String): JSONObject =
        result(false, "Nothing was logged: no item was named. $question").put("needs_answer", true)

    // ---- Lists a tool call may have reached by the wrong door (ListRouting, 2026-11-15) ----

    private const val TASK_NOT_REMINDER = "It was a calendar task, not a reminder."
    private const val TASK_NOT_CHECKLIST = "It was a calendar task, not a checklist line."
    private const val TODO_LINE = "a to-do, not a reminder or a calendar task"

    private val CHANGE_ACTIONS = setOf("add", "tick", "untick", "remove", "schedule")

    /**
     * A successful change says, as its `rule`, to name the item (2026-11-16 voice eval: "add buy
     * stamps to my to-do list" and "mark the quiz done" were answered "Added." and "Done.", which
     * tell him nothing about WHAT changed). Reads and failures are untouched.
     */
    private fun nameIt(r: JSONObject, args: JSONObject): JSONObject {
        if (r.optBoolean("success") && args.optString("action") in CHANGE_ACTIONS && !r.has("rule")) {
            r.put("rule", ListRouting.NAME_IT)
        }
        return r
    }

    /** [r]'s message with [note] after it: what kind of row was changed, said in words. */
    private fun noteRoute(r: JSONObject, note: String) {
        r.put("message", (r.optString("message") + " " + note).trim())
    }

    /** The Todo checklist, found and made as `notes/ReminderChecklistMigration` does (archived included). */
    private suspend fun todoChecklist(context: Context): Checklist? =
        ChecklistController.allChecklists(context, includeArchived = true)
            .firstOrNull { ListRouting.isTodoName(it.name) }

    private suspend fun todoChecklistOrNew(context: Context): Checklist =
        todoChecklist(context)
            ?: ChecklistController.createChecklist(context, name = ListRouting.TODO, scheduleKind = null)

    /** The Todo checklist's lines not ticked today; null when there is none or it cannot be read. */
    @Suppress("ReturnCount") // guard clauses: no list, no single match
    private suspend fun openTodoLines(context: Context): List<String>? {
        val todo = todoChecklist(context) ?: return null
        val loaded = ChecklistController.itemsWithTickState(context, todo.id)
            as? ChecklistController.ChecklistItemsResult.Loaded ?: return null
        return loaded.items.filter { !it.ticked }.map { it.item.text }
    }

    /** A Todo read's [ListRouting.REMINDERS_KEY]: open reminders, each with when it is due. */
    private suspend fun withReminders(context: Context, out: JSONObject) {
        val rows = runCatching { buildInboxRows(NotesController.allItems(context), System.currentTimeMillis()) }
            .getOrNull()?.filter { !it.done } ?: return
        if (rows.isEmpty()) return
        out.put(
            ListRouting.REMINDERS_KEY,
            JSONArray(rows.map { r -> JSONObject().put("text", r.text).put("due", r.dateLabel ?: "no date set") }),
        )
        out.put("rule", ListRouting.TODO_READ_NOTE)
    }

    /** An undated `manage_item` add: a line on the Todo checklist (made if missing). */
    private suspend fun addToTodo(context: Context, text: String): JSONObject {
        val todo = todoChecklistOrNew(context)
        val order = ChecklistController.itemsFor(context, todo.id).size
        val added = ChecklistController.addItem(context, checklistId = todo.id, text = text, sortOrder = order)
        return result(
            true,
            "Added \"${added.text}\" to his to-do list (the ${todo.name} checklist): no date or repeat, so " +
                "it is a to-do, not a reminder.",
        ).put("item", added.text).put("list", todo.name)
    }

    /** A tick for a Todo line by [phrase]; null when no single line there matches it. */
    @Suppress("ReturnCount") // guard clauses: no list, no single match
    private suspend fun tickTodoLine(context: Context, phrase: String, tick: Boolean): JSONObject? {
        val todo = todoChecklist(context) ?: return null
        val match = matchChecklistItem(phrase, ChecklistController.itemsFor(context, todo.id))
        val line = (match as? ChecklistItemMatch.Resolved)?.item ?: return null
        if (!tick) {
            ChecklistController.untick(context, line.id)
            return result(true, "Unticked \"${line.text}\" on the ${todo.name} checklist ($TODO_LINE).")
        }
        return when (val o = ChecklistController.tick(context, line.id)) {
            is ChecklistController.TickOutcome.Refused -> result(false, o.message)
            ChecklistController.TickOutcome.Ticked ->
                result(true, "Ticked \"${line.text}\" on the ${todo.name} checklist ($TODO_LINE).")
                    .put("item", line.text).put("list", todo.name)
        }
    }

    /** `manage_item remove` for a Todo line (an undated add lands there); null when none matches. */
    @Suppress("ReturnCount") // guard clauses: no list, no single match
    private suspend fun removeTodoLine(context: Context, phrase: String): JSONObject? {
        val todo = todoChecklist(context) ?: return null
        val match = matchChecklistItem(phrase, ChecklistController.itemsFor(context, todo.id))
        val line = (match as? ChecklistItemMatch.Resolved)?.item ?: return null
        ChecklistController.deleteItem(context, line.id)
        return result(true, "Removed \"${line.text}\" from the ${todo.name} checklist ($TODO_LINE).")
    }

    /** `complete_task` matched no task: a reminder by those words, then a Todo line; null when neither. */
    @Suppress("ReturnCount") // guard clauses: no list, no single match
    private suspend fun tickReminderOrTodo(context: Context, phrase: String, done: Boolean): JSONObject? {
        if (phrase.isBlank()) return null
        val list = theList(context)
        val reminder = (NotesController.findItem(context, list.id, phrase) as? ItemMatch.Resolved)?.item
            ?: return tickTodoLine(context, phrase, done)
        return dispatchItemAction(context, if (done) "tick" else "untick", list, reminder, JSONObject())
            .also { if (it.optBoolean("success")) noteRoute(it, "It was a reminder, not a calendar task.") }
    }

    /**
     * `manage_item schedule` for a Todo line (added undated, then given a date or a repeat): it
     * becomes a reminder with that schedule and leaves the checklist. Null when no Todo line matches.
     * If the schedule fails, the new reminder is taken back and the line stays where it was.
     */
    @Suppress("ReturnCount") // guard clauses: no list, no single match
    private suspend fun scheduleTodoLine(
        context: Context,
        list: ItemList,
        phrase: String,
        args: JSONObject,
    ): JSONObject? {
        val todo = todoChecklist(context) ?: return null
        val match = matchChecklistItem(phrase, ChecklistController.itemsFor(context, todo.id))
        val line = (match as? ChecklistItemMatch.Resolved)?.item ?: return null
        val reminder = NotesController.addItem(context, list.id, line.text)
        val r = dispatchItemAction(context, "schedule", list, reminder, args)
        if (r.optBoolean("success")) {
            ChecklistController.deleteItem(context, line.id)
            noteRoute(r, "It moved from the ${todo.name} checklist to his reminders.")
        } else {
            NotesController.removeItem(context, reminder)
        }
        return r
    }

    /**
     * Pure decision extracted out of [agentResult] so it's unit-testable without a real Gemini
     * call: when [requireMutation] is false, or [mutatingToolsCalled] is non-empty, the sub-agent's
     * own [subAgentText] is what the driver hears, success. When [requireMutation] is true and
     * NOTHING mutating ran, [subAgentText] is discarded entirely - never spoken, since it is
     * exactly the shape of text that caused this fix ("logged it" over nothing written) - and a
     * plain, generic "not recorded" refusal takes its place instead. `internal` (not private) so
     * [LiveToolboxMutationGateTest] can exercise the three cases directly.
     */
    /**
     * Whether an `ask_<domain>` call declared itself a WRITE, read off [DISPATCHER_PARAMS]' own
     * `intent` argument rather than inferred from the question prose.
     *
     * Anything other than the exact string `record` - absent, empty, misspelt, or a model that
     * simply ignores the optional field - reads as a plain read. That default is the whole reason
     * this is safe to switch on: the worst case is the pre-2026-08-18 behaviour, never a refused
     * legitimate question.
     */
    internal fun wantsWrite(args: JSONObject): Boolean = args.optString("intent") == "record"

    internal fun successOrMutationRefusal(
        subAgentText: String,
        mutatingToolsCalled: List<String>,
        requireMutation: Boolean,
    ): JSONObject = if (requireMutation && mutatingToolsCalled.isEmpty()) {
        result(false, "That didn't get written down - nothing changed. Try again and I'll log it properly.")
    } else {
        // `changed`: a write the sub-agent made, so the phone may say it (2026-11-19); a router's
        // answer without it (a read, a scan) is never confirmed aloud.
        result(true, subAgentText).apply { if (requireMutation) put("changed", true) }
    }

    // --- JSON schema helpers --------------------------------------------

    // JSON schema helpers: service/tools/ToolSchema.kt (fn, obj, schema).
}

/** A number argument, or null when the model left it out (`optInt` would say 0). fat-loss 15. */
internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() }?.let { Math.round(it).toInt() } else null

/** A number argument, or null when the model left it out (`optDouble` would say NaN). fat-loss 15. */
internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
