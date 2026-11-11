package com.kevin.legion.service.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Live tool surface after the cost diet (voice cost audit 2026-11-10, row F1; Kevin: "lets cut
 * the toollist").
 *
 * Live re-bills the whole setup (system instruction plus every function declaration) as text
 * input on every model generation, and the declarations were about 20k of its 23k tokens. This
 * file is the one place that says where every old top-level tool now lives:
 *
 * - **direct**: still declared on its own (the tools used most, or where a second's delay hurts).
 * - **action tool**: a WRITE (or an instant read) folded into one declaration with an `action`
 *   enum, as `manage_item` already was. The value names the old tool, so every result, rule and
 *   habit that names `log_meal` or `tag_place` still points the model at the right call. A call
 *   resolves to the old tool ([resolve]) before anything else sees it, so the old dispatch branch,
 *   its confirm gates, its read-through mark, its write accounting and its timeout all run
 *   unchanged. No second model is involved.
 * - **router**: a READ behind an `ask_<domain>` dispatcher ([com.kevin.legion.service.LiveToolbox.DISPATCHED]),
 *   answered by the phone's own sub-agent (one extra Flash REST hop, about a second).
 *
 * Nothing is removed. [ACTION_TOOLS] and `DISPATCHED` are the switches: moving a tool between an
 * action tool, a router and direct is an edit to one list here or there, and
 * `LiveSurfaceTest` fails on any old tool left without a home.
 *
 * Long per-tool caveats that only matter once the tool has run moved onto its RESULT
 * ([RESULT_RULES], billed per call, not per generation); what the model needs to choose and fill
 * the call stays in the action's hint.
 */
internal object LiveSurface {

    /** One value of an action tool's discriminator. [fixed] args are added on resolve. */
    data class Action(
        val value: String,
        val target: String,
        val hint: String,
        val fixed: Map<String, String> = emptyMap(),
    )

    /**
     * A declaration that stands for several old tools. With [base] it EXTENDS an existing direct
     * tool: [actions] are extra values on that tool's own [discriminator] enum, and any other value
     * passes through to [base] unchanged.
     */
    data class ActionTool(
        val name: String,
        val summary: String,
        val actions: List<Action>,
        val discriminator: String = "action",
        val base: String? = null,
        /** Short descriptions kept on union params whose meaning is not obvious from the name. */
        val paramNotes: Map<String, String> = emptyMap(),
    )

    /** A call after resolution: the old tool's name and its own arguments. */
    data class Resolved(val name: String, val args: JSONObject)

    private const val YES = "confirmed=true only after his plain yes to the read-back"

    private val ROUTER_NOTES = mapOf(
        "question" to "His question, in his words.",
        "intent" to "record if he asked for something written or changed, ask to only read. A record " +
            "call that writes nothing is reported as a failure.",
    )

    val ACTION_TOOLS: List<ActionTool> = listOf(
        ActionTool(
            name = "car",
            summary = "Changes to the cars. Readings, codes, specs, recalls and service history are ask_fleet.",
            actions = listOf(
                Action("log_service", "log_service", "work just done (service, cost)"),
                Action(
                    "log_past_service", "log_past_service",
                    "when an item was last done, from memory (mileage, miles_ago or date; never_done only " +
                        "if never done); he does not know means do not call",
                ),
                Action("set_odometer", "set_odometer", "current mileage (miles)"),
                Action("set_maintenance_interval", "set_maintenance_interval", "how often an item is due"),
                Action(
                    "log_build_entry", "log_build_entry",
                    "a mod, part, repair or purchase for the car (title, type, cost only if stated)",
                ),
                Action(
                    "register_vehicle", "register_vehicle",
                    "THIS car is year make model; overwrites the active car",
                ),
                Action("add_vehicle", "manage_vehicle", "another car he owns", mapOf("action" to "add")),
                Action("correct_vehicle", "manage_vehicle", "fix a saved car's details", mapOf("action" to "correct")),
                Action("rename_vehicle", "manage_vehicle", "nickname a car (name)", mapOf("action" to "rename")),
                Action("switch_vehicle", "manage_vehicle", "make a car the selected one", mapOf("action" to "switch")),
                Action("archive_vehicle", "manage_vehicle", "sold or gone", mapOf("action" to "archive")),
                Action("unarchive_vehicle", "manage_vehicle", "bring one back", mapOf("action" to "unarchive")),
                Action(
                    "clear_codes", "clear_codes",
                    "erase trouble codes, only when asked to clear; first call returns the warning to " +
                        "recite, $YES",
                ),
                Action(
                    "activate_garage", "activate_garage",
                    "pulse the garage door or gate; say triggering, never opening or closing; $YES",
                ),
            ),
            paramNotes = mapOf("vehicle" to "Which car; omit for the one being driven."),
        ),
        ActionTool(
            name = "body_log",
            summary = "Record the body: what he just ate, slept, lifted or weighed, and its daily targets. " +
                "Reading any of it back is ask_body.",
            actions = listOf(
                Action("log_meal", "log_meal", "a meal in his words (description); macros are estimates"),
                Action("log_workout_set", "log_workout_set", "sets just done (exercise, sets, reps, weight)"),
                Action("log_bodyweight", "log_bodyweight", "today's weight; not a waist"),
                Action("log_sleep", "log_sleep", "a night's sleep (duration_hours, quality 1-5)"),
                Action("set_meal_target", "set_meal_target", "daily calories and protein_g"),
                Action("set_sleep_target", "set_sleep_target", "nightly hours"),
                Action("undo_last_log", "undo_last_log", "take back the last body log"),
            ),
        ),
        ActionTool(
            name = "money",
            summary = "Money writes and the household bought log. Balances, spending and transactions " +
                "are look_up ask_money.",
            actions = listOf(
                Action(
                    "log_pending_transaction", "log_pending_transaction",
                    "a charge or credit not on a statement yet (description, amount, direction, account)",
                ),
                Action("clear_pending_transaction", "clear_pending_transaction", "remove a pending one he logged"),
                Action("set_category", "set_category", "confirm or correct a merchant's category"),
                Action("categorize_transactions", "categorize_transactions", "guess categories for uncategorised ones"),
                Action("set_budget", "set_budget", "monthly budget for a category (amount)"),
                Action("list_budget_categories", "list_budget_categories", "the exact category names"),
                // The household bought log (was its own declaration).
                Action(
                    "log_purchase", "bought_log", "record a purchase (item; date, store, price_cents if said)",
                    mapOf("action" to "log"),
                ),
                Action("last_bought", "bought_log", "when did we last buy item", mapOf("action" to "last")),
                Action("recent_bought", "bought_log", "the newest purchases", mapOf("action" to "recent")),
            ),
        ),
        ActionTool(
            name = "place",
            summary = "Saved places and where he is.",
            actions = listOf(
                Action(
                    "get_current_location", "get_current_location",
                    "where he is now, heading, sun and saved places nearby",
                ),
                Action("tag_place", "tag_place", "save a place (label; address, or here)"),
                Action("rename_place", "rename_place", "rename one (from, to)"),
                Action("forget_place", "forget_place", "delete one (label); confirmed only after his yes"),
                Action("show_saved_places", "show_saved_places", "show the saved places screen"),
                Action("set_reminder", "set_reminder", "remind him of text when he reaches a saved place"),
            ),
        ),
        ActionTool(
            name = "trip",
            summary = "The running navigation trip; start one with navigate.",
            actions = listOf(
                Action("trip_status", "trip_status", "ETA, distance, next turn (ask)"),
                Action("end_trip", "end_trip", "stop navigating"),
                Action(
                    "add_stop", "change_trip",
                    "add a stop (place); several matches: read the top one, then choice",
                    mapOf("action" to "add_stop"),
                ),
                Action("remove_stop", "change_trip", "remove a stop (place)", mapOf("action" to "remove_stop")),
                Action("avoid", "change_trip", "avoid tolls, highways, ferries or none", mapOf("action" to "avoid")),
                Action(
                    "take_alternative", "change_trip", "switch route (route, 1 is in use)",
                    mapOf("action" to "take_alternative"),
                ),
                Action("mute", "change_trip", "mute spoken turn cues, never you", mapOf("action" to "mute")),
                Action("unmute", "change_trip", "unmute turn cues", mapOf("action" to "unmute")),
                Action("overview", "change_trip", "show the whole route", mapOf("action" to "overview")),
                Action("recenter", "change_trip", "follow the car again", mapOf("action" to "recenter")),
            ),
        ),
        ActionTool(
            name = "voice_note",
            summary = "Voice notes he records.",
            actions = listOf(
                Action("start_voice_note", "start_voice_note", "start recording (kind solo or meeting, title)"),
                Action("stop_voice_note", "stop_voice_note", "stop; saved, not yet transcribed"),
                Action("read_voice_note", "read_voice_note", "read a note's summary (title, else latest)"),
                Action("list_voice_notes", "list_voice_notes", "list recent notes"),
            ),
        ),
        ActionTool(
            name = "day",
            summary = "His day, the time, and standing alerts.",
            actions = listOf(
                Action(
                    "read_calendar", "read_calendar",
                    "events and due tasks over a date range (from, to, YYYY-MM-DD)",
                ),
                Action("complete_task", "complete_task", "mark a calendar task (assignment) done or not (title, done)"),
                Action(
                    "get_sitrep", "get_sitrep",
                    "the daily brief, also for 'what's the news' (modules=news); say it as given",
                ),
                Action(
                    "why_did_you_say_that", "why_did_you_say_that",
                    "why you spoke unasked; with stop, turn that line off for good",
                ),
                // Parcel and flight read the newest shipping or travel mail: instant, no router.
                Action("track_package", "track_package", "where his package is, from the shipping mail"),
                Action(
                    "flight_status", "flight_status",
                    "flight or hotel from the travel mail; only when read_calendar has nothing for the trip",
                ),
                Action(
                    "get_current_time", "get_current_time",
                    "the date and time now; call whenever the answer depends on them, never guess",
                ),
                // Standing watches (was its own declaration, ADR 0083).
                Action("watch_list", "watch", "his standing alerts, with ids", mapOf("action" to "list")),
                Action(
                    "watch_set", "watch",
                    "alert him later: kind charge_over, deposit_over, balance_under, canvas_due, " +
                        "grade_posted, service_due or mail_reply; text the merchant, payer, account, " +
                        "course or companies; dollars",
                    mapOf("action" to "set"),
                ),
                Action("watch_pause", "watch", "pause one (text is its id)", mapOf("action" to "pause")),
                Action("watch_resume", "watch", "resume one (text is its id)", mapOf("action" to "resume")),
                Action("watch_remove", "watch", "remove one (text is its id)", mapOf("action" to "remove")),
            ),
        ),
        ActionTool(
            name = "goal",
            summary = "Long-term goals and plans. Listing goals or coaching is ask_goals.",
            actions = listOf(
                Action("set_goal", "set_goal", "set or revise a goal (aspect, statement; a number is optional)"),
                Action("close_goal", "close_goal", "achieved or abandoned (aspect, statement words)"),
                Action(
                    "generate_goal_plan", "generate_goal_plan",
                    "a rough plan for a body goal (goal_text in full; new_constraint for a lasting limit)",
                ),
                Action("accept_goal_plan", "accept_goal_plan", "apply that plan's workout piece, only after his yes"),
                Action(
                    "accept_proposal", "accept_proposal",
                    "write an advisor's stored proposal (id), only after his yes",
                ),
            ),
        ),
        ActionTool(
            name = "phone",
            summary = "The phone itself: calls (never texts, never emergency numbers), screens, the " +
                "camera, books and LEGION's own update.",
            actions = listOf(
                Action("answer_call", "answer_call", "answer the ringing call (speaker)"),
                Action("decline_call", "decline_call", "decline it"),
                Action(
                    "place_call", "place_call",
                    "call a contact or number; read back the result's sentence, $YES",
                ),
                Action("show_app", "show_app", "bring this app up"),
                Action("show_agenda_modal", "show_agenda_modal", "show today's agenda"),
                Action(
                    "show_generated_view", "show_generated_view",
                    "a one-off spend chart from the given options; never state a figure yourself",
                ),
                Action("show_vault_item", "show_vault_item", "open the ID vault; you never see or say a value"),
                Action("import_receipt", "import_receipt", "the grocery receipt camera"),
                Action("look", "look", "'look at this': the camera into this conversation"),
                Action(
                    "book_status", "books", "what he is reading and where; opens nothing",
                    mapOf("action" to "status"),
                ),
                Action(
                    "book_continue", "books", "open the last book (listen to read aloud)",
                    mapOf("action" to "continue"),
                ),
                Action("book_open", "books", "open a book by title", mapOf("action" to "open")),
                Action("update_check", "app_update", "is a LEGION update ready", mapOf("action" to "check")),
                Action("update_whats_new", "app_update", "its notes", mapOf("action" to "whats_new")),
                Action("update_install", "app_update", "install it; $YES", mapOf("action" to "install")),
            ),
        ),
        ActionTool(
            name = "records",
            summary = "The household engine's own aspects and records (anything without its own tool).",
            actions = listOf(
                Action("list_aspects", "list_aspects", "which aspects exist"),
                Action("describe_aspect", "describe_aspect", "an aspect's record types and fields"),
                Action("query_records", "query_records", "find records"),
                Action("create_record", "create_record", "add one (fields)"),
                Action("update_record", "update_record", "change one (recordId, fields)"),
                Action("delete_record", "delete_record", "delete; confirm only after his yes"),
                Action("aspect_clerk", "aspect_clerk", "a multi-step records job in words (instruction)"),
                Action("create_aspect", "create_aspect", "a new aspect; draft first, confirm after his yes"),
                Action("update_aspect", "update_aspect", "change an aspect; draft first, confirm after his yes"),
            ),
        ),
        // The routers, folded into two declarations (Kevin 2026-11-10: "about 15 always-loaded
        // core tools plus helpers"). The domain value is the old router's name, so every rule that
        // says "ask_fleet" still names the call. Split by behaviour: `ask`'s domains answer
        // NON_BLOCKING when "Answer lookups while talking" is on (ADR 0065), `look_up`'s block.
        ActionTool(
            name = "ask",
            discriminator = "domain",
            summary = "READ his own records through a specialist; records NOTHING (writes are car, " +
                "body_log, goal, money). Ask in his words; raw results come back to relay.",
            actions = listOf(
                Action(
                    "ask_fleet", "ask_fleet",
                    "the cars: live readings, trouble codes, mileage, specs, recalls, service and " +
                        "build history; how the car is doing comes only from this",
                ),
                Action(
                    "ask_body", "ask_body",
                    "meals, sleep, workouts and the programme, weight trend, calorie and protein estimates",
                ),
                Action("ask_goals", "ask_goals", "the goals he set and an advisor's coaching"),
                Action("ask_pantry", "ask_pantry", "grocery receipts and grocery spend, not the shopping list"),
            ),
            paramNotes = ROUTER_NOTES,
        ),
        ActionTool(
            name = "look_up",
            discriminator = "domain",
            summary = "READ through a specialist; records nothing. Ask in his words; raw results come " +
                "back to relay.",
            actions = listOf(
                Action("ask_mail", "ask_mail", "his Gmail: find or read a message"),
                Action(
                    "ask_money", "ask_money",
                    "balances, spending against budget, recent and pending transactions",
                ),
                Action(
                    "ask_area", "ask_area",
                    "official reports around him: weather alerts, quakes, fires, disasters, reported crime",
                ),
            ),
            paramNotes = ROUTER_NOTES,
        ),
        // Extensions of direct tools: extra values on their own `action` enum.
        ActionTool(
            name = "manage_checklist",
            base = "manage_checklist",
            summary = "",
            actions = listOf(
                Action(
                    "get_last_ticked", "get_last_ticked",
                    "when a line was last ticked (item); a tick is not a purchase",
                ),
            ),
        ),
        ActionTool(
            name = "manage_item",
            base = "manage_item",
            summary = "",
            actions = listOf(Action("read_list", "read_list", "read back open reminders")),
        ),
        ActionTool(
            name = "control_music",
            base = "control_music",
            summary = "",
            actions = listOf(
                Action("browse_my_music", "browse_my_music", "his playlists, liked songs or recent plays (source)"),
                Action("get_music_queue", "get_music_queue", "what is queued"),
                // Media volume, folded 2026-11-10 (the coordinator's "go lower where nothing slows").
                Action("volume_up", "control_volume", "media volume up", mapOf("action" to "up")),
                Action("volume_down", "control_volume", "media volume down", mapOf("action" to "down")),
                Action("volume_set", "control_volume", "media volume to level 0-100", mapOf("action" to "set")),
                Action("mute", "control_volume", "mute the media, never you", mapOf("action" to "mute")),
                Action("unmute", "control_volume", "unmute the media", mapOf("action" to "unmute")),
            ),
        ),
    )

    /**
     * Rules that used to sit in a merged tool's description and only matter once it has run. They
     * ride on that tool's RESULT as `rule` (billed per call), via [withRule].
     */
    val RESULT_RULES: Map<String, String> = mapOf(
        "stop_voice_note" to "The recording is saved and is being transcribed in the background; it is " +
            "not transcribed yet. Never say it is ready or summarized. He can ask for it back later.",
        "read_voice_note" to "The summary is model-made from the transcript. Never state a number, date, " +
            "name or figure in it as confirmed fact, only as something said in the recording.",
        "log_meal" to "Calories and macros are estimates from the description, never measured: say them as estimates.",
        "log_sleep" to "This is his own report; nothing verifies it.",
        "log_pending_transaction" to "This is his own report, not confirmed by the bank: say so.",
        "categorize_transactions" to "Every category is a guess until he confirms or corrects it " +
            "(set_category); a budget figure built on guesses is reported, not proven.",
        "get_last_ticked" to "A tick is only a tap: say \"ticked\", never that a purchase happened. No " +
            "match means no record, not proof nothing was bought. For when something was bought, " +
            "use money last_bought.",
        "activate_garage" to "There is no door sensor: say the door was triggered, never opened or closed.",
        "log_build_entry" to "No currency is stored: the number is kept in the unit he said.",
        "accept_goal_plan" to "Only the workout piece was applied. The meal and sleep targets and any " +
            "goals from that plan need body_log set_meal_target / set_sleep_target and goal set_goal.",
        "show_generated_view" to "The app ran the query and shows every number: do not state one yourself.",
        "why_did_you_say_that" to "State the rule and the fact it fired on; never justify it or guess how he felt.",
        "track_package" to "Mail only, never a live carrier lookup: say which email it read and when, " +
            "and that the status is an ESTIMATE of what that mail says.",
        "flight_status" to "Mail only, may be stale if the trip changed: say which email it read and " +
            "when, and that the answer is an ESTIMATE of what that mail says.",
        // Direct tools trimmed 2026-11-10: their outcome rules, moved off the description.
        "control_music" to "Say the state the result reports (shuffle, repeat, liked), not what was " +
            "asked. Already liked is a real answer, not a failure. A seek past the end moved to the " +
            "next song: say so. Nothing playing or not Spotify: say you cannot tell who is playing, " +
            "never guess. A playlist he does not own or collaborate on fails: never say it was added " +
            "or that the playlist does not exist.",
        "play_music" to "If the result says a playlist came from the public catalogue, say so and " +
            "name what played. A history row with replayable false cannot be replayed: say so.",
    )

    private val byName: Map<String, ActionTool> = ACTION_TOOLS.associateBy { it.name }

    /** Every old tool an action tool stands for, to its (tool, action value). */
    val ACTION_HOME: Map<String, Pair<String, String>> = buildMap {
        for (t in ACTION_TOOLS) for (a in t.actions) if (a.target !in this) put(a.target, t.name to a.value)
    }

    /** Old tools folded away (each now an action value), so [isFolded] names them. */
    fun isFolded(name: String): Boolean = name in ACTION_HOME && byName[name]?.base != name

    /**
     * [nonBlocking] (real tool names, `LiveToolbox.NON_BLOCKING_READ_TOOLS`) plus every action tool
     * whose targets are ALL in it, so the declaration the model sees (`ask`) carries NON_BLOCKING
     * exactly when each call it can resolve to answers that way. A mixed tool stays BLOCKING.
     */
    fun nonBlockingWireNames(nonBlocking: Set<String>): Set<String> =
        nonBlocking + ACTION_TOOLS
            .filter { t -> t.base == null && t.actions.all { it.target in nonBlocking } }
            .map { it.name }

    /** A call as the model made it, resolved to the tool it stands for. Unchanged when not an action. */
    fun resolve(name: String, args: JSONObject): Resolved {
        val tool = byName[name] ?: return Resolved(name, args)
        val value = args.optString(tool.discriminator)
        val action = tool.actions.firstOrNull { it.value == value }
            ?: return Resolved(tool.base ?: name, args)
        val out = JSONObject(args.toString())
        out.remove(tool.discriminator)
        action.fixed.forEach { (k, v) -> out.put(k, v) }
        return Resolved(action.target, out)
    }

    /** The failure an action tool's call gets when its action is missing or unknown; null otherwise. */
    fun unknownAction(name: String, args: JSONObject): JSONObject? {
        val tool = byName[name]?.takeIf { it.base == null } ?: return null
        val values = tool.actions.joinToString(", ") { it.value }
        val asked = args.optString(tool.discriminator).ifBlank { "nothing" }
        return JSONObject()
            .put("success", false)
            .put("message", "Nothing was done: $name has no action \"$asked\". Use one of: $values.")
    }

    /** [result] with the tool's [RESULT_RULES] entry as `rule`, when it has one and no rule yet. */
    fun withRule(name: String, result: JSONObject): JSONObject {
        val rule = RESULT_RULES[name] ?: return result
        if (!result.has("rule")) result.put("rule", rule)
        return result
    }

    /**
     * The surface from [candidates] (every declaration that could be top-level, in order):
     * folded tools are dropped, each action tool is placed where its first target was, and a base
     * tool is replaced by its extended declaration.
     */
    fun apply(candidates: List<JSONObject>): JSONArray {
        val byDeclName = candidates.associateBy { it.getString("name") }
        val placed = mutableSetOf<String>()
        val out = JSONArray()
        for (decl in candidates) {
            val name = decl.getString("name")
            val extended = byName[name]?.takeIf { it.base == name }
            when {
                extended != null -> out.put(declaration(extended, byDeclName))
                name in ACTION_HOME && !isBaseTool(name) -> {
                    val tool = byName.getValue(ACTION_HOME.getValue(name).first)
                    if (tool.base == null && placed.add(tool.name)) out.put(declaration(tool, byDeclName))
                }
                else -> out.put(decl)
            }
        }
        return out
    }

    private fun isBaseTool(name: String): Boolean = byName[name]?.base == name

    /** Builds [tool]'s declaration from its targets' declarations in [decls]. */
    fun declaration(tool: ActionTool, decls: Map<String, JSONObject>): JSONObject {
        val hints = tool.actions.joinToString("; ") { "${it.value}: ${it.hint}" }
        if (tool.base != null) {
            val base = JSONObject(decls.getValue(tool.base).toString())
            val params = base.getJSONObject("parameters")
            val props = params.getJSONObject("properties")
            val disc = props.getJSONObject(tool.discriminator)
            val enum = disc.optJSONArray("enum")
            if (enum != null) tool.actions.forEach { enum.put(it.value) }
            mergeParams(props, tool, decls, skip = setOf(tool.discriminator))
            base.put("description", base.getString("description") + " Also: $hints.")
            return base
        }
        val props = JSONObject()
        props.put(
            tool.discriminator,
            JSONObject().put("type", "string").put("enum", JSONArray(tool.actions.map { it.value })),
        )
        mergeParams(props, tool, decls, skip = setOf(tool.discriminator))
        return JSONObject()
            .put("name", tool.name)
            .put("description", "${tool.summary} $hints.".trim())
            .put(
                "parameters",
                JSONObject().put("type", "object").put("properties", props)
                    .put("required", JSONArray(listOf(tool.discriminator))),
            )
    }

    /**
     * Adds every target's params to [props], descriptions dropped (the action hints name what each
     * takes) except [ActionTool.paramNotes]. A name two targets declare with different types is
     * a build error, not a silent first-wins.
     */
    private fun mergeParams(
        props: JSONObject,
        tool: ActionTool,
        decls: Map<String, JSONObject>,
        skip: Set<String>,
    ) {
        for (action in tool.actions) {
            val target = decls[action.target] ?: error("${tool.name}: no declaration for ${action.target}")
            val tProps = target.optJSONObject("parameters")?.optJSONObject("properties") ?: continue
            for (key in tProps.keys()) {
                if (key in skip || key in action.fixed) continue
                val src = tProps.getJSONObject(key)
                val slim = JSONObject().put("type", src.optString("type", "string"))
                src.optJSONArray("enum")?.let { slim.put("enum", JSONArray(it.toString())) }
                tool.paramNotes[key]?.let { slim.put("description", it) }
                val existing = props.optJSONObject(key)
                if (existing == null) {
                    props.put(key, slim)
                } else {
                    val (t1, t2) = existing.optString("type") to slim.optString("type")
                    check(t1 == t2) { "${tool.name}: param $key is $t1 and $t2" }
                    // An extended tool's own params are left exactly as they were.
                    if (tool.base != null) continue
                    // Two targets with different enums for one name: the union of both.
                    val e1 = existing.optJSONArray("enum")
                    val e2 = slim.optJSONArray("enum")
                    if (e1 != null && e2 != null) {
                        val seen = (0 until e1.length()).map { e1.getString(it) }.toMutableSet()
                        for (i in 0 until e2.length()) if (seen.add(e2.getString(i))) e1.put(e2.getString(i))
                    } else if (e1 != null && e2 == null) {
                        existing.remove("enum")
                    }
                }
            }
        }
    }
}
