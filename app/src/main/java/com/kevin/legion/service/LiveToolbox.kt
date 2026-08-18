package com.kevin.legion.service

import android.content.Context
import android.content.Intent
import com.kevin.legion.MidnightEvents
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AgentTool
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.AssistantIdentity
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.advisor.AdvisorBriefs
import com.kevin.legion.advisor.AdvisorAgent
import com.kevin.legion.advisor.AdvisorProposalExecutor
import com.kevin.legion.advisor.AdvisorResult
import com.kevin.legion.advisor.HarnessPrompt
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.excludedOwnAccountMovementsSentence
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ledger.sameCard
import com.kevin.legion.ledger.uncategorizedExcludedSentence
import com.kevin.legion.pantry.PantryController
import com.kevin.legion.meals.MealController
import com.kevin.legion.workouts.WorkoutController
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import com.kevin.legion.util.documentDate
import com.kevin.legion.util.shortDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.kevin.legion.location.ReminderController
import com.kevin.legion.media.MusicController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.SpotifyController
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.media.VolumeController
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.BuildSheetController
import com.kevin.legion.vehicle.CarToolbelt
import com.kevin.legion.vehicle.PID_REGISTRY
import com.kevin.legion.vehicle.capabilitiesFor
import com.kevin.legion.vehicle.matchPid
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.goals.GoalController
import com.kevin.legion.gmail.GmailAuth
import com.kevin.legion.gmail.GmailClient
import com.kevin.legion.gmail.GmailToolLogic
import com.kevin.legion.grocery.GroceryController
import com.kevin.legion.grocery.GroceryMatch
import com.kevin.legion.grocery.buildGroceryRows
import com.kevin.legion.notes.NotesController
import com.kevin.legion.ui.notes.buildInboxRows
import com.kevin.legion.ui.notes.ScheduleIntentResolver
import com.kevin.legion.calendar.CalendarProvider
import com.kevin.legion.calendar.CalendarReadToolLogic
import com.kevin.legion.notes.ItemMatch
import com.kevin.legion.notes.RepeatEnd
import com.kevin.legion.notes.RepeatRule
import com.kevin.legion.notes.parseWeekdays
import com.kevin.legion.vehicle.ColdStartAgent
import com.kevin.legion.vehicle.DiagnosticAgent
import com.kevin.legion.vehicle.DtcClearController
import com.kevin.legion.vehicle.DtcDescriptions
import com.kevin.legion.vehicle.GarageController
import com.kevin.legion.vehicle.GaragePreferences
import com.kevin.legion.vehicle.MaintenanceAgent
import com.kevin.legion.vehicle.MpgTrust
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.RecallCheckResult
import com.kevin.legion.vehicle.SymptomAgent
import com.kevin.legion.vehicle.TelemetryRecorder
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VehicleMatch
import com.kevin.legion.vehicle.VehicleResolver
import com.kevin.legion.vehicle.VehicleSpecController
import com.kevin.legion.vehicle.VinDecoder
import org.json.JSONArray
import org.json.JSONObject

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
     * The shared parameter every "category B" (stored-data) tool below adds
     * (fleet-wide voice, ticket 01 §3, verbatim description shape). Kept as
     * one constant so the wording can't drift between the 11 tools that add it.
     */
    private val VEHICLE_PARAM: JSONObject
        get() = schema("string", "Which car, by name or model. Omit for the car currently being driven.")

    /**
     * The full, un-filtered declaration set - every tool this file knows how to build, dispatched
     * ones included. [declarations] (the one actually sent to the Live setup message) filters this
     * down; [agentToolsFor] slices it back out per domain for a dispatcher's own sub-agent. Kept
     * `private` - nothing outside this file should ever advertise the unfiltered set to a model.
     */
    private fun allDeclarations(): JSONArray {
        val fns = JSONArray()

        // Category A - live-hardware tools (fleet-wide voice, ticket 01 §0). The
        // OBD dongle is physically plugged into ONE car; these read it directly
        // and can never answer for any other car, so - unlike Category B below -
        // they take NO `vehicle` argument. Every description below says so, so
        // the model doesn't try to pass one expecting it to switch cars.
        fns.put(fn(
            name = "get_vehicle_data",
            description = "Read a live value from the car's OBD-II port: fuel level, coolant " +
                "temperature, RPM, road speed, battery voltage, engine load, intake air " +
                "temperature, air flow, fuel trims, or whether any stored trouble/check-engine " +
                "codes are present. Use whenever the driver asks what any of those are RIGHT NOW - " +
                "'how much fuel have I got', 'what's the temp', 'how's the battery'. To explain " +
                "what a code MEANS or how to FIX it, use diagnose_codes instead. Reads the OBD " +
                "dongle in the car it is plugged into right now. Cannot answer for any other car.",
            params = obj(
                "metric" to schema("string", "Which live reading to fetch.",
                    enum = listOf(
                        "fuel_level", "coolant_temp", "rpm", "speed", "battery_voltage",
                        "engine_load", "intake_air_temp", "maf",
                        "short_fuel_trim", "long_fuel_trim", "trouble_codes",
                    ))
            ),
            required = listOf("metric"),
        ))

        fns.put(fn(
            name = "diagnose_codes",
            description = "Hand off to the diagnostics specialist to explain the car's trouble / " +
                "check-engine codes: what they mean, likely causes, how serious, and how to fix. Use " +
                "whenever the driver asks what's wrong with the car, about the check engine light, " +
                "what a code means, or how to fix one. The specialist reads the live codes from the " +
                "port itself - only pass 'codes' when the driver names specific ones (e.g. 'what's P0420'). " +
                "Tell the driver you're digging into it before calling this - it takes a little while. " +
                "Reads the OBD dongle in the car it is plugged into right now. Cannot answer for any " +
                "other car.",
            params = obj(
                "question" to schema("string", "The driver's question in their own words, e.g. " +
                    "'what's wrong with my car' or 'how do I fix P0301'."),
                "codes" to schema("string", "Optional: specific codes the driver named, comma-" +
                    "separated, e.g. 'P0301, P0420'. Leave empty to use the live codes from the port."),
            ),
            required = listOf("question"),
        ))

        fns.put(fn(
            name = "get_codes",
            description = "Quick read of the stored trouble codes themselves - just the codes and " +
                "their names, no causal explanation. Instant, offline, free. Use for a plain factual " +
                "ask like 'what's the codes' or 'what codes are stored' - use diagnose_codes instead " +
                "when the driver wants to know what's WRONG or how to fix it. Reads the OBD dongle in " +
                "the car it is plugged into right now. Cannot answer for any other car.",
            params = obj(),
            required = listOf(),
        ))

        // Category B - per-vehicle STORED history. Unlike get_codes/diagnose_codes above, this
        // reads the database, not the dongle, so it answers for ANY car on file and works with
        // nothing plugged in at all.
        //
        // Added 2026-08-11 after the assistant could not answer "check the historical codes for the
        // Cherokee" despite `code_events` holding rows for it. Every code tool read the live port
        // and said so, `CarToolbelt.codeHistory` had done the per-vehicle read for ages, and nothing
        // connected the two - the same shape as the earlier "could not answer how much fuel because
        // no tool returned it". A capability the app has but exposes to nobody reads, from the
        // driver's seat, as a capability it does not have.
        fns.put(fn(
            name = "get_code_history",
            description = "Read the STORED history of trouble / check-engine codes for a car - what " +
                "codes have tripped in the past, when, and at what mileage, with the freeze-frame " +
                "conditions latched when each one tripped. Use for 'what codes has the Cherokee " +
                "thrown', 'has this happened before', 'what was that code last month', or any " +
                "question about PAST codes. Reads the database, not the port: it works for any car " +
                "on file with nothing plugged in. Use get_codes instead for what is stored in the " +
                "ECU right now, and diagnose_codes to explain what a code means. Instant, offline, free.",
            params = obj(
                "vehicle" to VEHICLE_PARAM,
                "limit" to schema("integer", "How many past events to return, newest first. Defaults to 5."),
            ),
            required = listOf(),
        ))

        // One tool covering "what can this car report" and "read me that sensor" (2026-08-12).
        //
        // Free-text `sensor` rather than an enum: the registry holds ~50 PIDs and an enum of all of
        // them would be prompt tokens on every live session, on Kevin's own key, for a list that
        // grows. Matching happens in matchPid against the READABLE set for that car, so the model
        // can never be offered a sensor the vehicle does not answer.
        //
        // Reads the DATABASE for the capability list (so it answers parked and unplugged) and the
        // PORT for a live value. That split is the whole point: "does the truck report oil temp" is
        // a different question from "what is the oil temp right now", and only the second needs a
        // dongle.
        fns.put(fn(
            name = "read_vehicle_sensor",
            description = "Read any OBD-II sensor a car supports, or - with no sensor named - list " +
                "what that car can report at all. Use for anything beyond the common readings " +
                "get_vehicle_data covers: oil temperature, boost/manifold pressure, fuel rate, " +
                "ambient temperature, timing advance, torque, catalyst temperature, distance since " +
                "the check-engine light came on. " +
                "Listing what a car supports works ANY TIME, parked and unplugged, because the " +
                "capability profile is stored per car. Reading a live VALUE needs the dongle " +
                "connected to that car. If a sensor is not on a car's list, say so plainly - " +
                "different cars genuinely support different sensors and that is not a fault.",
            params = obj(
                "sensor" to schema("string", "What to read, in the driver's words - 'oil temp', " +
                    "'boost', 'fuel rate', 'ambient'. Omit entirely to list everything this car supports."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf(),
        ))

        fns.put(fn(
            name = "get_current_time",
            description = "Read the current date and time from the phone's own clock, in the " +
                "driver's timezone. Call this whenever the driver asks what time or what day it " +
                "is, or whenever the answer depends on the current time - how long until " +
                "something, whether it is morning or evening, what 'today' or 'tonight' means. " +
                "NEVER state a time or date from your own guess: you do not have a clock, and a " +
                "wrong one is worse than asking. Instant, offline, free.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "triage_symptom",
            description = "Use when the driver describes how the car is BEHAVING rather than naming " +
                "a code: a noise, smell, vibration, leak, rough idle, hard start, loss of power, or " +
                "how a warning light is acting - 'what's that rattle', 'why does it shudder at idle', " +
                "'it smells like burning'. The specialist reasons from the symptom plus live readings " +
                "and stored codes, grounded to this exact car. Use diagnose_codes instead when the " +
                "driver names a specific code. Tell the driver you're digging into it before calling " +
                "this - it takes a little while. Reads the OBD dongle in the car it is plugged into " +
                "right now. Cannot answer for any other car.",
            params = obj("symptom" to schema("string", "The driver's description of the problem in " +
                "their own words, e.g. 'grinding when I brake' or 'rough idle when cold'.")),
            required = listOf("symptom"),
        ))

        fns.put(fn(
            name = "get_health",
            description = "Read a quick health snapshot from the OBD port - battery voltage, coolant " +
                "temperature, and whether any trouble codes are stored. Use when the driver asks if " +
                "the car's okay, wants a health check, or is about to set off on a long drive (a " +
                "pre-trip check). Reads the OBD dongle in the car it is plugged into right now. Cannot " +
                "answer for any other car.",
            params = obj(),
            required = listOf(),
        ))

        // Fleet's first WRITE to the car (D1-D10, `.scratch/hands-and-senses/issues/01-clear-dtc.md`).
        // Category A like the seven other DTC tools above (D10) - live-hardware, no `vehicle` param,
        // guarded by CATEGORY_A_TOOLS/refuseIfNotConnectedCar below. The confirm shape is copied
        // VERBATIM from activate_garage (D4): required confirmed boolean, model instructed to call
        // false first and true only after a yes in the very next turn - and, same as activate_garage,
        // this only works inside a live session (D4.4), never a one-shot.
        fns.put(fn(
            name = "clear_codes",
            description = "Erase the car's stored trouble codes - OBD Mode 04, a REAL WRITE to the " +
                "ECU. This also wipes the freeze frame and RESETS THE EMISSIONS READINESS MONITORS, " +
                "so the car will fail an inspection until it has driven enough to reset them. " +
                "Destructive and not reversible. ALWAYS call this first with confirmed=false - it " +
                "reads the stored codes and returns the exact warning to recite to the driver; only " +
                "call it again with confirmed=true after the driver says yes in the very next turn. " +
                "After sending, this re-reads the codes and reports what actually came back - only " +
                "ever say 'cleared' if that is what this tool's own result says, never assume the " +
                "send alone means it worked. Use when the driver asks to clear codes, reset the " +
                "check engine light, or erase trouble codes. Reads the OBD dongle in the car it is " +
                "plugged into right now. Cannot answer for any other car.",
            params = obj(
                "confirmed" to schema("boolean", "False on the first call to read the codes and get " +
                    "the confirm warning to recite. True only after the driver has just confirmed."),
            ),
            required = listOf("confirmed"),
        ))

        // Category B - stored-data tools (fleet-wide voice, ticket 01 §0). Room
        // is already keyed by vehicleId, so these all take an optional `vehicle`
        // argument; omitted means the car currently being driven, which is what
        // keeps every existing utterance answering exactly as it did before.
        fns.put(fn(
            name = "get_trend",
            // "mpg" deliberately absent from this metric list (ticket 09,
            // `.scratch/drive-ui/issues/09-mpg-scale-bug.md` - see MpgTrust's own doc): mpg display
            // is suppressed app-wide pending a fill-up calibration, and get_mpg below refuses in
            // words rather than silently answering through this tool with a wrong number instead.
            description = "Fetch how a vehicle metric has trended over recent weeks from the recorded " +
                "history (coolant, rpm, voltage, load, fuel_trim). Use when the driver asks how " +
                "something has been running lately, whether it's been getting worse, or how it compares " +
                "to before.",
            params = obj(
                "metric" to schema(
                    "string", "Which metric to trend.",
                    enum = listOf("coolant", "rpm", "voltage", "load", "fuel_trim"),
                ),
                "days" to schema("integer", "How many days back to look. Default 30."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("metric"),
        ))

        fns.put(fn(
            name = "get_mpg",
            // Description rewritten under MpgTrust.SHOW_MPG == false (ticket 09): the tool stays
            // registered so the model can still route an mpg question here and get a spoken refusal,
            // but it must never promise, let alone deliver, a figure while suppressed.
            description = "Fuel economy is currently WITHHELD on this car - LEGION's own on-board " +
                "estimate was found to read almost 2x the real figure and needs a tank-to-tank " +
                "fill-up to calibrate before it can be trusted again. Calling this tool returns that " +
                "refusal, in words, never a number. Use when the driver asks about gas mileage or " +
                "fuel economy, so the refusal can be spoken rather than the question going unanswered.",
            params = obj("vehicle" to VEHICLE_PARAM),
            required = listOf(),
        ))

        fns.put(fn(
            name = "check_readiness",
            description = "Read the emissions readiness monitors live from the OBD port - which " +
                "self-tests are complete and which still need drive time. Use before a state " +
                "inspection or smog check, or when the driver asks if the car will pass. Reads the " +
                "OBD dongle in the car it is plugged into right now. Cannot answer for any other car.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "check_cold_start",
            description = "Analyze the most recent recorded cold start (the first minute of warm-up: " +
                "idle, fuel trims, warm-up rate) against earlier ones. Use when the driver asks how " +
                "the car has been starting, mentions rough cold idle, or asks about warm-up health. " +
                "Tell the driver you're digging into it before calling this - it takes a little while. " +
                "Reads the OBD dongle in the car it is plugged into right now. Cannot answer for any " +
                "other car.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "get_next_service",
            description = "Instant, free read of what's coming up next on the maintenance schedule - " +
                "the OVERALL soonest item by miles and the overall soonest by time, straight from the " +
                "logged intervals and mileage, no reasoning involved. Use this for 'what's next', " +
                "'what's coming up', 'how far off am I', or 'how many miles until my next service' - " +
                "only when the driver has NOT named a specific service. If they name one (e.g. 'how " +
                "many miles until my oil change'), use ask_maintenance instead - it has per-item " +
                "access and this tool can't answer about a single named item, only the schedule's " +
                "overall next-up one. This also only covers UPCOMING items - it does not report " +
                "already-overdue ones (those are already in what you know from context), so for " +
                "'am I overdue for X' use ask_maintenance too. Do NOT use ask_maintenance for a plain " +
                "'what's next' with no named service - it's slower and this already has the answer.",
            params = obj("vehicle" to VEHICLE_PARAM),
            required = listOf(),
        ))

        fns.put(fn(
            name = "ask_maintenance",
            description = "Hand off to the maintenance specialist for anything about the car's " +
                "service schedule: how to do / what's involved in a specific service (oil change, " +
                "brake fluid, spark plugs, etc), whether something's worth worrying about, general " +
                "maintenance questions, 'am I overdue for X', or how far off a NAMED service is (e.g. " +
                "'how many miles until my oil change') - get_next_service only knows the schedule's " +
                "overall next-up item and can't answer about one named item or an overdue one. It " +
                "uses the car's logged intervals, mileage, and service history. Use get_next_service " +
                "instead for a plain, no-service-named 'what's due/coming up next' - it's instant and " +
                "free; don't call this for that. Tell the driver you're digging into it before calling " +
                "this - it takes a little while.",
            params = obj(
                "question" to schema("string", "The driver's maintenance question in their own " +
                    "words, e.g. 'how do I change the brake fluid' or 'should I be worried about " +
                    "this noise'."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("question"),
        ))

        fns.put(fn(
            name = "control_music",
            description = "Control music playback hands-free: 'play', 'pause', 'next', 'previous'. " +
                "Works with whatever's playing on the phone. Transport only.",
            params = obj(
                "action" to schema("string", "The playback action.",
                    enum = listOf("play", "pause", "next", "previous")),
            ),
            required = listOf("action"),
        ))

        fns.put(fn(
            name = "control_volume",
            description = "Adjust the phone's music/media volume - an instant on-device action. " +
                "'up'/'down' nudge it, 'set' jumps to a level (0-100), 'mute'/'unmute' silence or " +
                "restore it. This controls the music the driver hears, not your own speaking voice.",
            params = obj(
                "action" to schema("string", "The volume action.",
                    enum = listOf("up", "down", "set", "mute", "unmute")),
                "level" to schema("integer", "For 'set': target volume 0-100. Required for 'set'."),
            ),
            required = listOf("action"),
        ))

        fns.put(fn(
            name = "play_music",
            description = "Play something specific by name - a song, artist, album, or playlist - " +
                "directly in-app via Spotify (requires the driver to have connected their own Spotify " +
                "account in Setup). Use when the driver names what they want to hear, e.g. 'play " +
                "Plastic Love' or 'play some city pop'. If Spotify isn't connected, this fails with a " +
                "message telling the driver to connect it in Setup or pick something on their phone " +
                "themselves. Once something's playing, control_music handles play/pause/skip.",
            params = obj("query" to schema("string",
                "What to play, in the driver's own words, e.g. 'Plastic Love by Mariya Takeuchi'.")),
            required = listOf("query"),
        ))

        fns.put(fn(
            name = "show_app",
            description = "Bring this app to the foreground. Use when the driver asks to open the " +
                "app or come back to it.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "set_reminder",
            description = "Save a reminder tied to one of the driver's saved places, surfaced when " +
                "they next arrive there. Use for 'remind me to X when I get to / when I'm at the Y', " +
                "e.g. 'remind me to grab my gym bag when I get to the gym'.",
            params = obj(
                "place" to schema("string", "The saved place the reminder is for, e.g. home, work, gym, walmart."),
                "text" to schema("string", "What to remind the driver about, in their own words, e.g. 'grab your gym bag'."),
            ),
            required = listOf("place", "text"),
        ))

        fns.put(fn(
            name = "tag_place",
            description = "Save the driver's CURRENT location under a label like 'home', 'work', or " +
                "'gym', so it can be referenced later. Use when the driver says something like 'this " +
                "is my work' while they're there. No address-based tagging - only the current GPS spot.",
            params = obj(
                "label" to schema("string", "Short label for this place, e.g. home, work, gym."),
            ),
            required = listOf("label"),
        ))

        fns.put(fn(
            name = "forget_place",
            description = "Delete a previously saved place by its label.",
            params = obj("label" to schema("string", "Label of the saved place to forget, e.g. work.")),
            required = listOf("label"),
        ))

        fns.put(fn(
            name = "show_saved_places",
            description = "Show or hide the on-screen list of the driver's saved places.",
            params = obj("visible" to schema("boolean", "True to show the list, false to hide it.")),
            required = listOf("visible"),
        ))

        fns.put(fn(
            name = "import_statement",
            description = "Open the file picker to import a bank statement PDF into the ledger. " +
                "Use when the driver asks to import, add, or upload a statement, or add a bank " +
                "account's transactions.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "get_balance",
            description = "Report the latest known balance for a ledger account, LEADING WITH THE " +
                "AVAILABLE FIGURE the way the driver's own bank app does. If the driver doesn't " +
                "name one and only one account is on file, use that one; if several exist, ask " +
                "which, or report all of them. The available figure can include TWO different " +
                "kinds of unconfirmed activity, and both must be said out loud, not just reported " +
                "as a final number: pending_delta_cents is card activity read from a mid-cycle " +
                "export that hasn't been confirmed by a statement yet (CLAUDE.md §4 rule 7), and " +
                "pending_count is charges the DRIVER logged by voice that the bank hasn't posted " +
                "or confirmed at all. The response marks the account verified=false when either is " +
                "nonzero - say the figure is pending or unverified rather than presenting it as final.",
            params = obj("account" to schema("string", "Which account, if the driver named one. " +
                "Leave empty to get all known accounts.")),
            required = listOf(),
        ))

        fns.put(fn(
            name = "list_recent_transactions",
            description = "Read back the most recent ledger transactions (raw descriptions and " +
                "amounts - there's no spend-by-category breakdown yet, so don't imply insight this " +
                "doesn't have). Use when the driver asks what they've spent money on recently or " +
                "wants to review recent transactions. Some rows are pending card activity read from a " +
                "mid-cycle export, not yet confirmed by a statement (CLAUDE.md §4 rule 7) - each such " +
                "row has verified=false and a note; say it's pending, don't present it as confirmed.",
            params = obj("count" to schema("integer", "How many to return. Default 10.")),
            required = listOf(),
        ))

        // --- Categorisation (ticket 07 D14-D19, wired 2026-08-07) -------------
        //
        // CategoryAgent.guessBatch/LedgerController.applyCategoryGuesses/
        // confirmCategoryGuess/applyCategoryRules were all built and unit-tested
        // (ticket 07) but had ZERO callers outside the ledger package itself -
        // no tool, no button - the same structurally-unreachable shape sync/
        // sat in until a screen finally called setSyncEnabled. These two tools
        // are the trigger.

        fns.put(fn(
            name = "categorize_transactions",
            description = "Have the AI guess a spending category for every transaction that has " +
                "none yet, batched once per distinct merchant (never per transaction - cheap even " +
                "for a large backlog). Every guess is a GUESS, not a fact, until the driver " +
                "confirms or corrects it with set_category - a budget figure built on unconfirmed " +
                "guesses is REPORTED, not PROVEN, and must be said that way. Use when the driver " +
                "asks to categorize their transactions, sort out their spending, or asks why so " +
                "much is 'uncategorised'.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "set_category",
            description = "Confirm or correct the spending category for a merchant - applies to " +
                "every transaction from that merchant on file, past and future (correcting rewrites " +
                "history: last month's budget figure was wrong and is now right). Use when the " +
                "driver confirms an AI-guessed category is right, or tells you the right one, e.g. " +
                "'yes, Kroger is groceries' or 'no, that Amazon charge was actually a gift, not " +
                "shopping'. Call list_budget_categories first if unsure of the exact category name - " +
                "only a category from that fixed list is accepted.",
            params = obj(
                "merchant" to schema("string", "The merchant name, in the driver's own words, e.g. 'Kroger'."),
                "category" to schema("string", "The category to assign, spelled exactly as list_budget_categories gives it."),
            ),
            required = listOf("merchant", "category"),
        ))

        // --- Voice-logged pending transactions ------------------------------
        //
        // The driver's own bank nets still-processing card activity into an
        // "available" balance that no BofA export ever prints - the CSV
        // parser requires a printed running balance on every row, so a
        // pending charge cannot even be represented in a file. These three
        // tools are the voice-only path around that gap. Every row they
        // write is REPORTED tier (CLAUDE.md §4 rule 7's vocabulary): the
        // driver's own word, never confirmed by anything printed. Say so.

        fns.put(fn(
            name = "log_pending_transaction",
            description = "Log a charge or credit the driver knows about but that hasn't shown up " +
                "on any statement or export yet - a still-processing/pending card transaction. " +
                "This is the driver's OWN REPORT, not confirmed by the bank; the reply must say " +
                "so out loud, not present it as a normal logged transaction. Use when the driver " +
                "says something like 'I just spent forty dollars at the hardware store, put it on " +
                "the BofA card' or 'log a pending charge of twelve fifty for coffee'.",
            params = obj(
                "description" to schema("string", "What the charge or credit was for."),
                "amount" to schema("number", "The MAGNITUDE in the account's own currency, always " +
                    "positive - never a negative number, direction is its own separate field."),
                "direction" to schema(
                    "string",
                    "Whether this is money leaving the account (a charge) or arriving (a credit/refund). " +
                        "Defaults to debit if the driver doesn't say.",
                    enum = listOf("debit", "credit"),
                ),
                "account" to schema("string", "Which account this belongs to, if the driver named " +
                    "one or only one account is on file. If it's unclear which account, ASK rather " +
                    "than guessing - this tool will never invent an account."),
                "date" to schema("string", "MM/DD/YYYY. Defaults to today if the driver doesn't say."),
            ),
            required = listOf("description", "amount"),
        ))

        fns.put(fn(
            name = "list_pending_transactions",
            description = "Read back every pending transaction the driver has logged by voice - " +
                "none of these are confirmed by the bank, every one carries verified=false. Use " +
                "when the driver asks what pending charges they've logged, or wants to review or " +
                "clear one.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "clear_pending_transaction",
            description = "Remove a voice-logged pending transaction, e.g. because it posted for " +
                "real and will now show up in a statement, or the driver logged it in error. " +
                "Matches by description - if more than one pending row matches, ask which rather " +
                "than guessing. This can only ever remove a pending row, never a real imported one.",
            params = obj("description" to schema("string", "Which pending transaction to remove, " +
                "matched against what it was logged as.")),
            required = listOf("description"),
        ))

        fns.put(fn(
            name = "import_receipt",
            description = "Open the camera or gallery picker to log a grocery receipt into the " +
                "pantry. Use when the driver asks to log, add, or scan a grocery receipt.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "list_recent_groceries",
            description = "Read back recently logged grocery items. Calorie/protein/carb/fat figures " +
                "are ESTIMATES the model guessed at ingestion time, never measured - always phrase " +
                "them as estimates, never as fact.",
            params = obj("count" to schema("integer", "How many items to return. Default 10.")),
            required = listOf(),
        ))

        fns.put(fn(
            name = "get_grocery_spend",
            description = "Report total logged grocery spend across all receipts, broken out PER " +
                "CURRENCY - never add SGD and USD receipts into one number, state each currency's " +
                "total separately.",
            params = obj(),
            required = listOf(),
        ))

        // --- Workouts (ticket 08 D20-D24) ------------------------------------

        fns.put(fn(
            name = "create_workout_plan",
            description = "Have the AI write a loose weekly workout plan (which exercises, target " +
                "sets per week for each, and how many days a week) from the driver's stated goal. " +
                "Use when the driver asks for a workout plan or routine, e.g. 'make me a plan to " +
                "build strength three days a week'. Replaces the current plan going forward - past " +
                "weeks are untouched.",
            params = obj("goal" to schema("string", "The driver's goal in their own words.")),
            required = listOf("goal"),
        ))

        fns.put(fn(
            name = "log_workout_set",
            description = "Log a set-group just performed, e.g. 'three sets of squats at 225'. Use " +
                "the moment the driver reports a set - no confirmation needed, just log it and say " +
                "back what was recorded. If the driver only names the exercise with no set count, " +
                "ask once for how many sets rather than guessing.",
            params = obj(
                "exercise" to schema("string", "The exercise, e.g. squats, bench press, pushups."),
                "sets" to schema("integer", "How many sets, e.g. 3."),
                "reps" to schema("integer", "Reps per set, if stated."),
                "weight" to schema("number", "Weight used, if stated."),
                "weight_unit" to schema("string", "Unit for weight, if stated.", enum = listOf("lbs", "kg")),
            ),
            required = listOf("exercise", "sets"),
        ))

        fns.put(fn(
            name = "log_bodyweight",
            description = "Log the driver's current bodyweight, e.g. 'I weigh 180 today'. A plain " +
                "reported measurement, separate from any workout log.",
            params = obj(
                "weight" to schema("number", "The bodyweight value."),
                "weight_unit" to schema("string", "Unit for the weight.", enum = listOf("lbs", "kg")),
            ),
            required = listOf("weight", "weight_unit"),
        ))

        fns.put(fn(
            name = "get_workout_gap",
            description = "Report sessions done versus sessions planned this week - the driver's " +
                "adherence to their current plan. Use when the driver asks how they're doing this " +
                "week or how they're sticking to their plan. Says there's no plan yet if none is set.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "list_recent_workouts",
            description = "Read back recently logged workout sets.",
            params = obj("count" to schema("integer", "How many to return. Default 10.")),
            required = listOf(),
        ))

        // --- Meals (ticket 09 D25-D28) ----------------------------------------

        fns.put(fn(
            name = "log_meal",
            description = "Log a meal from the driver's spoken description, e.g. 'I had a chicken " +
                "burrito bowl for lunch'. Calories and macros are LLM ESTIMATES from the description, " +
                "never measured - always phrase them as estimates, never as fact.",
            params = obj("description" to schema("string", "What was eaten, in the driver's own words.")),
            required = listOf("description"),
        ))

        fns.put(fn(
            name = "set_meal_target",
            description = "Set the driver's daily calorie and macro target. Use when the driver " +
                "states a goal, e.g. 'I want to hit 2200 calories and 150 grams of protein a day'.",
            params = obj(
                "calories" to schema("integer", "Daily calorie target."),
                "protein_g" to schema("number", "Daily protein target in grams."),
                "carbs_g" to schema("number", "Daily carb target in grams."),
                "fat_g" to schema("number", "Daily fat target in grams."),
            ),
            required = listOf("calories", "protein_g", "carbs_g", "fat_g"),
        ))

        // The counterpart to set_meal_target, and it exists because the UI
        // promised it. TodayScreen's empty budget row says, in words, 'say
        // "set a grocery budget"' - and until this tool existed,
        // LedgerController.setBudget had no caller anywhere: not a screen, not
        // a tool. An empty state that names a command the app cannot perform is
        // the same class of failure as a figure the app cannot defend.
        fns.put(fn(
            name = "set_budget",
            description = "Set a monthly spending budget for one category. Use when the driver " +
                "states a limit, e.g. 'budget four hundred a month for groceries'. Applies from " +
                "the current month onward until changed - it does not need setting again each " +
                "month. Call list_budget_categories first if unsure of the exact category name.",
            params = obj(
                "category" to schema("string", "The spending category, e.g. 'groceries'."),
                "amount" to schema("number", "The monthly limit, in dollars."),
            ),
            required = listOf("category", "amount"),
        ))

        fns.put(fn(
            name = "list_budget_categories",
            description = "List the spending categories a budget can be set against. Categories " +
                "are a fixed set, so this is the way to find the right name before calling " +
                "set_budget.",
            params = obj(),
            required = emptyList(),
        ))

        // 2026-08-13: the voice path's own copy of the screen's US BUDGET disclosure. Before this
        // tool existed there was NO voice-callable spend total for the ledger at all - a driver
        // asking "how much have I spent this month" had nothing to call. CLAUDE.md §4 rule 7
        // requires this figure to carry the SAME "own-account movements excluded" caveat the screen
        // states in words next to the number - never a total that quietly reads more honest out loud
        // than it looks on screen.
        fns.put(fn(
            name = "get_monthly_spend",
            description = "Report this month's categorised operating spend for the US ledger " +
                "entity - the SAME figure the Money screen's SPEND pane shows. Money moved between " +
                "the driver's own accounts (a card payment, a savings transfer) is excluded from " +
                "this total; ALWAYS say how many transactions and how much were excluded, exactly " +
                "as the response's excluded_own_account_movements fields state - never present the " +
                "total as if nothing was left out. Transactions with no category are ALSO excluded " +
                "from this total: whenever uncategorized_cents is above zero, say so out loud using " +
                "uncategorized_note - that money was spent, it is simply not classified yet, and a " +
                "total presented without it would understate the month. If the response's verified field is false, say " +
                "the figure is not fully confirmed (pending bank data, an unconfirmed AI-guessed " +
                "category, or a month not fully covered by an imported statement).",
            params = obj(),
            required = emptyList(),
        ))

        fns.put(fn(
            name = "get_meal_gap",
            description = "Report today's logged calories/macros against the daily target. If " +
                "nothing has been logged today, says so explicitly - NEVER report today as zero " +
                "calories, that would be a lie, not a fact.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "list_recent_meals",
            description = "Read back recently logged meals. Calorie/macro figures are ESTIMATES, " +
                "always phrase them as estimates, never as fact.",
            params = obj("count" to schema("integer", "How many to return. Default 10.")),
            required = listOf(),
        ))

        // --- Sleep (Kevin, 2026-08-07: "i want to be able to log sleep too") -----
        //
        // Modelled on meals/workouts (ticket 08/09) - REPORTED tier, no
        // reconciliation gate (CLAUDE.md §4's gate only applies where a source
        // document states its own anchor; nothing external verifies sleep).

        fns.put(fn(
            name = "log_sleep",
            description = "Log a night's sleep, e.g. 'I slept 7 and a half hours' or 'log 8 hours, " +
                "quality 4 out of 5'. This is the driver's OWN REPORT - nothing verifies it, so " +
                "never present a sleep figure as independently confirmed.",
            params = obj(
                "duration_hours" to schema("number", "How long they slept, in hours - decimals allowed, e.g. 7.5."),
                "quality" to schema("integer", "Optional self-rated sleep quality, 1 (worst) to 5 (best)."),
                "date" to schema("string", "MM/DD/YYYY, the morning they woke up. Defaults to today if the driver doesn't say."),
                "notes" to schema("string", "Any extra detail the driver gives, e.g. 'woke up twice'."),
            ),
            required = listOf("duration_hours"),
        ))

        fns.put(fn(
            name = "set_sleep_target",
            description = "Set the driver's nightly sleep target, e.g. 'I want to get 8 hours of " +
                "sleep a night'. Applies from tonight onward until changed.",
            params = obj("hours" to schema("number", "The target hours of sleep per night, e.g. 8.")),
            required = listOf("hours"),
        ))

        fns.put(fn(
            name = "get_sleep_gap",
            description = "Report last night's logged sleep against the nightly target. If nothing " +
                "has been logged for last night, says so explicitly - NEVER report it as zero hours, " +
                "that would be a lie, not a fact.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "list_recent_sleep",
            description = "Read back recently logged nights of sleep.",
            params = obj("count" to schema("integer", "How many nights to return. Default 10.")),
            required = listOf(),
        ))

        // --- Shared undo (ticket 11 D36; extended to sleep 2026-08-07) ---------

        fns.put(fn(
            name = "undo_last_log",
            description = "Undo the single most recently logged item across workouts, bodyweight, " +
                "meals, and sleep - whichever was logged last. Use when the driver says something " +
                "like 'no, undo that' or 'that's wrong, take it back' right after logging something " +
                "in one of these domains. Never used for ledger money - that's clear_pending_transaction.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "set_odometer",
            description = "Record the car's current odometer reading (the driver is the source of " +
                "truth). Use when the driver states their mileage, e.g. 'my odometer is at 142500'.",
            params = obj(
                "miles" to schema("integer", "Current odometer reading in miles."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("miles"),
        ))

        fns.put(fn(
            name = "log_service",
            description = "Record that a maintenance service was just completed, clearing its 'due' " +
                "status. Use when the driver says they did some work, e.g. 'I just changed the oil'.",
            params = obj(
                "service" to schema("string", "The service performed, e.g. oil change, tire rotation, brake pads."),
                "cost" to schema("number", "Dollar amount paid, only if the driver stated one. Omit otherwise."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("service"),
        ))

        fns.put(fn(
            name = "log_past_service",
            description = "Backfill when a maintenance item was last done from memory, not a fresh " +
                "confirmation of work just finished (that's log_service). Use when the driver recalls " +
                "an approximate mileage, how long ago, or a date - e.g. 'I think I changed the oil " +
                "around 8,000 miles ago' or 'the brakes were done last spring'. Use never_done ONLY " +
                "for a confirmed 'it's never been done', which is different from not knowing. " +
                "CRITICAL: if the driver says they don't know or can't remember, do NOT call this " +
                "tool at all - leave it unknown rather than guessing a value.",
            params = obj(
                "service" to schema("string", "The service, e.g. oil change, tire rotation, brake pads."),
                "mileage" to schema("integer", "Absolute odometer reading it was done at, if the driver gave one, e.g. 185000."),
                "miles_ago" to schema("integer", "How many miles ago, if that's how the driver phrased it, e.g. 8000."),
                "date" to schema("string", "The date it was done, ISO-8601 YYYY-MM-DD or YYYY-MM, if the driver gave one."),
                "never_done" to schema("boolean", "True only if the driver confirms this has never been done."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("service"),
        ))

        // set_maintenance_interval (ticket 05,
        // .scratch/fleet-maintenance/issues/05-an-edit-that-actually-sticks.md): Kevin's original
        // attempt to change the oil interval by voice reported success and silently changed
        // nothing - there was no live tool for it at all, only the advisor's accept_proposal path.
        // This one ALWAYS reads the row back after writing and states the result, because a
        // read-back cannot be produced from a write that did not land - see
        // VehicleController.setMaintenanceInterval's doc for the full mechanism.
        fns.put(fn(
            name = "set_maintenance_interval",
            description = "Set or change how often a maintenance item is due, e.g. 'change the oil " +
                "interval to every 7,500 miles' or 'set tire rotation to every 6 months'. Marks the " +
                "interval as driver-confirmed, so the automatic schedule lookup will never silently " +
                "overwrite it again. Always reads the value back so the driver can hear it actually " +
                "changed.",
            params = obj(
                "service" to schema("string", "The service, e.g. oil change, tire rotation, brake pads."),
                "interval_miles" to schema("integer", "New mileage interval, if the driver gave one, e.g. 7500."),
                "interval_months" to schema("integer", "New time interval in months, if the driver gave one, e.g. 6."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("service"),
        ))

        // lookup_vin is listed as "category B" in ticket 01 §3's build spec, but
        // it reads the OBD port DIRECTLY (VinDecoder.fromObd()) - the same
        // physical constraint §0 states for category A: there is no such thing
        // as reading the SECOND car's VIN while the dongle is plugged into the
        // first one. Treated here as category-A-shaped (no `vehicle` argument)
        // rather than literally per §3's list, because §0 is the ticket's own
        // stated tie-breaker ("the single most important thing"). Flagged in
        // the ticket 01 build report as a found contradiction, not a silent
        // reclassification.
        fns.put(fn(
            name = "lookup_vin",
            description = "Read the car's VIN from the connected OBD adapter and look up its year, " +
                "make, model, and trim. Use when the driver wants you to identify the car from the " +
                "port, fill in its details automatically, or asks 'what car is this' / 'pull my VIN'. " +
                "This only reads the facts - it does NOT save them. Read them back and ask the driver " +
                "to confirm, then call register_vehicle to save. Needs the OBD adapter connected. " +
                "Reads the OBD dongle in the car it is plugged into right now. Cannot answer for any " +
                "other car.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "get_specs",
            description = "Read the car's stored factory specs from its build-details encyclopedia - " +
                "engine, displacement, horsepower, transmission, drivetrain, body, assembly plant, and " +
                "any paint/notes the driver saved. Use when the driver asks about the car's specs, " +
                "engine, how much power it makes, what transmission/drivetrain it has, where it was " +
                "built, etc. If nothing's on file, tell them to run a VIN lookup (lookup_vin) or fill " +
                "it in under Logbook â†’ Specs.",
            params = obj("vehicle" to VEHICLE_PARAM),
            required = listOf(),
        ))

        fns.put(fn(
            name = "check_recalls",
            description = "Look up active manufacturer recalls for this car (live from NHTSA by year/" +
                "make/model). Use when the driver asks if the car has any recalls or open safety " +
                "campaigns. The driver must have told you their car's year, make, and model first; " +
                "if they haven't, this returns an error asking you to get those - never guess or " +
                "assume the car.",
            params = obj("vehicle" to VEHICLE_PARAM),
            required = listOf(),
        ))

        fns.put(fn(
            name = "register_vehicle",
            description = "Identify the car being driven RIGHT NOW, overwriting whatever is on file " +
                "for it. Triggers an online lookup of its maintenance schedule. Use ONLY for 'THIS " +
                "car is a 2003 BMW 330i', or to save details after lookup_vin found them. " +
                "This OVERWRITES the active car - it never creates a second one. To add another car " +
                "the driver owns ('add my F-150', 'I also have a...'), or to fix a car that has the " +
                "wrong details saved, use manage_vehicle instead.",
            params = obj(
                "year" to schema("integer", "Model year, e.g. 2003."),
                "make" to schema("string", "Manufacturer, e.g. BMW."),
                "model" to schema("string", "Model, e.g. 330i."),
            ),
            required = listOf("year", "make", "model"),
        ))

        fns.put(fn(
            name = "remember",
            description = "Save a fact to long-term memory so it persists across drives. Use when " +
                "the driver explicitly asks you to remember something.",
            params = obj("text" to schema("string", "The fact to remember, in the driver's own terms.")),
            required = listOf("text"),
        ))

        // ----------------------------------------------------------------------------------
        // Notes / lists / calendar (`.scratch/notes-lists-calendar/`, phase 1). Absorbs the car
        // to-do list (now the "Car" list) and place reminders (now the "Reminders" list) - ticket
        // 10 retires add_car_task/complete_car_task/remove_car_task/list_car_tasks entirely, and
        // this domain's whole surface is THREE tools rather than one per verb (create/tick/
        // untick/remove/schedule/repeat/skip/archive/unarchive/copy/delete-list are all `action`
        // parameters, not separate registrations) - ticket 05/10/11's shared instruction: "every
        // tool is prompt tokens on every single live session, on Kevin's own key." Net -1 against
        // the four retired tools.
        //
        // Alarms/notifications (ticket 03/12, phase 2a) now schedule for real on startsAt - see
        // NotesController.setTime/setRepeat/setExact and notes/AlarmScheduler.kt. A place-triggered
        // item is still created only via the pre-existing set_reminder tool (ReminderController,
        // now writing triggerPlaceLabel onto the notes model instead of the legacy place_reminders
        // table - see that controller's doc comment) - ticket 05's own resolved tool list names no
        // place-trigger verb for the new model, so none is added here.
        fns.put(fn(
            name = "manage_item",
            // Trimmed 2026-08-17 (~1,004-token declaration was the single largest of the 79):
            // condensed the top-level description and cut the repeated "Only for 'schedule' with"
            // prefix off every repeat_* param (the field names and this description already carry
            // that context) without dropping any distinct rule, enum value, or capability - notes/
            // lists/calendar stays genuinely multi-shaped, this only removes restated words.
            description = "Add, tick, untick, remove, schedule (time and/or repeat), or skip one " +
                "occurrence of ONE item - the app's only list: car to-dos, errands, reminders, and " +
                "notes all live on it, so never ask which list or mention lists in the plural. A " +
                "dated appointment (see 'kind') goes to Google Calendar instead of this list. Pass " +
                "any date/time the driver gives on the SAME 'add' call via date/time - never add " +
                "first and schedule in a second call, which stores an appointment with no date at " +
                "all. Use 'schedule' only to change an existing item's date or set up a repeat. " +
                "For every action but 'add', 'item' fuzzily matches the existing item's text, " +
                "never a position like 'the third one'. A recurring item can't be ticked - edit " +
                "its repeat instead.",
            params = obj(
                "action" to schema("string", "What to do.",
                    enum = listOf("add", "tick", "untick", "remove", "schedule", "skip")),
                "item" to schema("string", "For 'add', the new item's text. For every other action, which existing item to match, in the driver's words."),
                "date" to schema("string", "Calendar date, yyyy-MM-dd. For 'add'/'schedule': the item's due date - always pass it on 'add' if the driver said one. For 'skip': which occurrence to skip."),
                "time" to schema("string", "24-hour HH:mm. For 'add'/'schedule' only if a specific time was given - omit for an all-day item. Requires 'date'."),
                "kind" to schema("string", "Only for 'add' with a date. 'appointment' goes to Google " +
                    "Calendar (e.g. 'dentist Tuesday at 3', 'lunch with Sam Friday'); 'reminder' " +
                    "stays local and private (e.g. 'remind me to change the oil', 'don't forget to " +
                    "call mom'). Omit if genuinely unclear - defaults to reminder.",
                    enum = listOf("appointment", "reminder")),
                "repeat_kind" to schema("string", "Only for 'schedule'. Omit to leave any existing repeat untouched; 'none' clears one.",
                    enum = listOf("none", "daily", "weekly", "monthly_on_date", "yearly")),
                "repeat_every" to schema("integer", "Every N days/weeks/months for repeat_kind daily/weekly/monthly_on_date. Defaults to 1."),
                "repeat_days" to schema("string", "Comma-separated days for repeat_kind weekly, e.g. 'MON,WED,FRI'."),
                "repeat_month" to schema("integer", "Month, 1-12, for repeat_kind yearly."),
                "repeat_day" to schema("integer", "Day of month, 1-31, for repeat_kind monthly_on_date or yearly. Past the month's end lands on its last day."),
                "repeat_end_kind" to schema("string", "Only with a repeat_kind set. Defaults to 'never'.",
                    enum = listOf("never", "on_date", "after_count")),
                "repeat_end_date" to schema("string", "For repeat_end_kind on_date: yyyy-MM-dd, the series' last possible date."),
                "repeat_end_count" to schema("integer", "For repeat_end_kind after_count: total occurrences."),
                "exact" to schema("boolean", "Only for 'schedule', only when the driver wants a precise, punctual " +
                    "alarm (e.g. 'wake me up at exactly 6am, don't be late'). Omit for the default, which fires " +
                    "within about an hour and needs no extra permission. Falls back silently to the default if " +
                    "exact permission isn't available - the reply must say so."),
            ),
            required = listOf("action"),
        ))

        // The grocery trip (2026-08-11). ONE tool with an `action` parameter, matching manage_item's
        // own rationale - "every tool is prompt tokens on every single live session, on Kevin's own
        // key" - rather than a registration per verb.
        //
        // Deliberately separate from manage_item even though both manage checkable lines: a grocery
        // line is expected to be destroyed within the hour and a list item is kept until removed,
        // and one tool covering both would need the model to pick the right destination every time,
        // which is exactly the fuzzy-destination guess that filed an F150 recall onto the "Car"
        // list. See GroceryItem's doc comment.
        fns.put(fn(
            name = "manage_grocery",
            description = "Manage the CURRENT grocery/shopping trip - a short-lived list that is " +
                "thrown away once the shopping is done. Use for 'add milk to the grocery list', " +
                "'what's on the shopping list', 'got the eggs', 'I'm done shopping'. This is " +
                "SEPARATE from the driver's normal list (manage_item): anything that is not " +
                "shopping for this trip belongs there instead. " +
                "'finish' is DESTRUCTIVE - it deletes the whole list and only remembers what was " +
                "ticked - so it ALWAYS confirms: call with confirmed=false first to ask, then " +
                "confirmed=true only after the driver says yes in the very next turn. " +
                "'suggest' reads back what they usually buy, for starting a trip.",
            params = obj(
                "action" to schema("string", "What to do.",
                    enum = listOf("add", "tick", "untick", "remove", "read", "suggest", "finish")),
                "item" to schema("string", "For 'add', what to add. For tick/untick/remove, which " +
                    "existing item to match, in the driver's words - never a position like 'the third one'."),
                "confirmed" to schema("boolean", "Only for 'finish': false to trigger the confirm prompt, " +
                    "true only after the driver just confirmed."),
            ),
            required = listOf("action"),
        ))

        fns.put(fn(
            name = "read_list",
            description = "Read back the driver's list - every open item, soonest due date first, " +
                "with the date on any item that has one. There is exactly one list. Use for " +
                "'what's on my list', 'what's left to do for the car', 'what have I got coming up'.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "recall_memory",
            description = "Search your long-term memory of past conversations and trips with the " +
                "driver. Use when they ask what you remember, reference something from before, or " +
                "when recalling a past detail would make your reply more personal. Returns the " +
                "closest-matching memories with the date each was noted.",
            params = obj("query" to schema("string", "What to look for, in a few words - e.g. " +
                "'brakes', 'trip to Tahoe', 'favorite coffee'. Leave empty for the most recent memories.")),
            required = listOf(),
        ))

        fns.put(fn(
            name = "log_build_entry",
            description = "Log something on the car's build sheet / spend ledger - a mod, part, " +
                "repair, consumable, or general purchase for the car. Use when the driver says they " +
                "did, bought, or installed something, or spent on the car (e.g. 'I just put on " +
                "coilovers', 'logged a new clutch, six hundred bucks'). Only capture a cost if they " +
                "actually state one. This log has no currency column - the number is stored plainly, " +
                "in whatever unit the driver said it in.",
            params = obj(
                "title" to schema("string", "What it is, e.g. 'BC Racing coilovers' or 'new clutch'."),
                "type" to schema("string", "Category of the entry.",
                    enum = listOf("mod", "part", "repair", "consumable", "other")),
                "cost" to schema("number", "Dollar amount, only if the driver stated one. Omit otherwise."),
                "vendor" to schema("string", "Where it was bought / the shop, if mentioned."),
                "notes" to schema("string", "Any extra detail the driver gives."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("title"),
        ))

        fns.put(fn(
            name = "list_build_history",
            description = "Read back the car's build history - what's been done, installed, or bought " +
                "and when. Use when the driver asks what's on the build sheet, what they've done to the " +
                "car, or wants a build rundown (e.g. for selling it). The history is always available; " +
                "dollar amounts are only included when the spend log is unlocked, and this log never " +
                "recorded a currency for any cost - state a cost plainly, never attach a currency to it.",
            params = obj(
                "type" to schema("string", "Optional category filter.",
                    enum = listOf("mod", "part", "repair", "consumable", "other")),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf(),
        ))

        fns.put(fn(
            name = "get_spend",
            description = "Report how much has been spent on the car - the grand total or one category. " +
                "Only use when the driver explicitly asks about money, cost, or total spent. This build " +
                "sheet spend log never recorded a currency for any entry - state the number plainly, " +
                "never attach a currency to it or guess one.",
            params = obj("category" to schema("string", "Optional category, e.g. 'mods' or 'maintenance'.")),
            required = listOf(),
        ))

        fns.put(fn(
            name = "activate_garage",
            description = "Trigger the garage door / gate relay - a single momentary pulse, exactly " +
                "like pressing a handheld garage remote once. There is no way to know or promise " +
                "whether this will open or close the door (no door sensor), so never say 'opening' " +
                "or 'closing' - say 'triggering' or 'hitting' the door. Use when the driver asks to " +
                "open, close, hit, trigger, or use the garage or gate. ALWAYS call this first with " +
                "confirmed=false to ask the driver to confirm; only call it again with confirmed=true " +
                "after they say yes in the very next turn.",
            params = obj(
                "door" to schema("string", "Which door, if the driver named one or there's more than " +
                    "one set up, e.g. 'garage' or 'side gate'. Leave empty for the default door."),
                "confirmed" to schema("boolean", "False on the first call to trigger the confirm " +
                    "prompt. True only after the driver has just confirmed."),
            ),
            required = listOf("confirmed"),
        ))

        fns.put(fn(
            name = "get_current_location",
            description = "Get the driver's current GPS location and human-readable address. Use " +
                "whenever the driver asks 'where am I', or when you need the current city/state " +
                "to ground a nearby search.",
            params = obj(),
            required = listOf(),
        ))

        // Fleet-wide voice (ticket 01): this is what makes the fleet knowable at
        // all. Every other stored-data tool CAN take a `vehicle` argument, but
        // nothing tells the model a second car exists to ask about unless it
        // calls this first - a pull-based tool cannot bootstrap its own
        // discovery. See AriaBrain.assembleBase's small pre-injected fleet
        // fragment for the other half of that bootstrap.
        fns.put(fn(
            name = "list_vehicles",
            description = "List every car on file: name, year, make, model, trim, whether it's the " +
                "one currently being driven, whether the OBD dongle is plugged into it right now, and " +
                "its last-known odometer. This is how to find out whether there's more than one car - " +
                "call this whenever the driver asks what cars they have, or before assuming there's " +
                "only one.",
            params = obj(),
            required = listOf(),
        ))

        // Owning the fleet by voice (2026-08-09). ONE tool with an `action`
        // parameter rather than add/rename/correct/switch/archive as five
        // registrations - the same call ticket 05/10/11 made for manage_list,
        // and for the same reason: every tool is prompt tokens on every single
        // live session, on Kevin's own key.
        //
        // This exists because there was NO voice path to a second car at all.
        // VehicleController.createCarProfile had zero callers, so "add my F-150"
        // could only land on register_vehicle, which overwrites the active car -
        // and did, turning a 2020 Outlander with 5242 stored readings into a
        // Ford F-150 without ever creating the F-150.
        fns.put(fn(
            name = "manage_vehicle",
            description = "Add a car to the fleet, fix a car's saved details, rename one, switch " +
                "which car you're on, or archive/unarchive one. Use 'add' when the driver mentions " +
                "another car they own ('add my F-150', 'I also drive a...') - this is the ONLY way " +
                "to create a second car, and it does NOT change which car is active. Use 'correct' " +
                "when a car has the wrong year/make/model saved; its drives and service history stay " +
                "with it. Call list_vehicles first if you're not sure which car they mean.",
            params = obj(
                "action" to schema("string", "What to do.",
                    enum = listOf("add", "correct", "rename", "switch", "archive", "unarchive")),
                "vehicle" to schema("string", "Which car, as the driver says it, e.g. 'the Outlander'. " +
                    "Required for everything EXCEPT add; for add, leave blank."),
                "year" to schema("integer", "Model year, e.g. 2020. For add and correct."),
                "make" to schema("string", "Manufacturer, e.g. Mitsubishi. Required for add."),
                "model" to schema("string", "Model, e.g. Outlander. Required for add."),
                "trim" to schema("string", "Optional trim, e.g. SEL."),
                "name" to schema("string", "What the driver calls it, e.g. 'the truck'. For add and rename."),
            ),
            required = listOf("action"),
        ))

        // Gmail, ticket 15 (google-account-integration). Two tools, descriptions copied
        // VERBATIM from ticket 05's Answer table - a description is the only thing the model
        // ever reads, so this repo does not paraphrase or "improve" a description that was
        // already decided. Net +2 against a budget of 69 (-> 71); see that ticket for why this
        // is the one domain landing without retiring anything to pay for it.
        fns.put(fn(
            name = "search_mail",
            description = "Search Kevin's Gmail. `query` uses Gmail search syntax; plain words " +
                "search full text. Returns sender, subject, date and a one-line snippet - never " +
                "the full message. Call with no query for a briefing of unread mail from the " +
                "last two days. Read-only; you cannot send, reply to, or delete mail. The result's " +
                "`query` field is the search that actually ran - if you translated Kevin's words " +
                "into it yourself, say that query back to him so a bad translation is visible " +
                "rather than a confident wrong answer.",
            params = obj(
                "query" to schema("string", "Gmail search syntax (from:, subject:, after:, plain " +
                    "words for full text). Omit entirely for the unread briefing."),
                "limit" to schema("integer", "Optional: how many results to return. The app " +
                    "enforces its own hard cap regardless of what's asked for."),
            ),
            required = listOf(),
        ))

        fns.put(fn(
            name = "read_mail",
            description = "Fetch the full text of ONE message by the id returned from " +
                "`search_mail`. Only call this when Kevin asks about a specific message; say " +
                "that you are opening it.",
            params = obj(
                "id" to schema("string", "The message id from a previous search_mail result."),
            ),
            required = listOf("id"),
        ))

        // Ticket 19 (google-account-integration): the write half of calendar has existed since
        // ticket 14 (manage_item's appointment path) and the read half never did - Kevin asked
        // what was on his calendar and Alfred correctly said he couldn't check. Net +1 against a
        // budget of 71 (-> 72); see that ticket for why a domain the assistant can write to but
        // not read from is worse than either alone.
        fns.put(fn(
            name = "read_calendar",
            description = "Read events from Kevin's GOOGLE CALENDAR over a date range - the same " +
                "calendar `manage_item`'s appointment kind writes to, and every calendar Kevin has " +
                "on this device whether he owns it or only subscribes to it, so a holiday or a " +
                "shared calendar he can't edit still shows up here. This does NOT read LEGION's " +
                "own list - reminders and to-dos live on `manage_item`/`read_list`, never here, " +
                "and this tool never returns them. Returns each event's title, start, end, and " +
                "whether it is all-day. Read-only: you cannot create, edit, or delete anything " +
                "with this tool - use manage_item for that.",
            params = obj(
                "from" to schema("string", "First day of the window, yyyy-MM-dd. Call " +
                    "get_current_time first if you need to know what 'today' is."),
                "to" to schema("string", "Last day of the window, yyyy-MM-dd, inclusive. For a " +
                    "single day, pass the same value as 'from'."),
            ),
            required = listOf("from", "to"),
        ))

        // --- Goals (ticket 19, `.scratch/aspect-advisors/issues/02-goal-store.md`'s Answer) -----
        //
        // Three tools, not one per aspect: a goal is uniformly statement + aspect + optional
        // number (answer call 1/2), so one `aspect` enum parameter on each tool covers all four
        // writable aspects rather than multiplying the declaration count by four. That keeps this
        // addition to +3 tools (~71 -> ~74), not +12. `aspect` uses the plain-string vocabulary
        // GoalController.ASPECTS carries (matching Goal.aspect's own TEXT column and
        // AdvisorAspect's `key`s minus HOME, which has no goals of its own - see
        // GoalController's doc comment) rather than the tab labels the driver actually says, so
        // every description below spells the mapping out for Gemini in words.
        //
        // No confirm step, matching set_budget/set_meal_target/log_bodyweight - CLAUDE.md's
        // existing direct-dictation posture for every other write tool in this file, and ticket
        // 19's brief is explicit that goals follow the same pattern (the propose-accept protocol
        // is for ADVISOR-authored writes only, a different ticket).
        fns.put(fn(
            name = "set_goal",
            description = "Set or update a long-term goal for one aspect of the driver's life - " +
                "not a daily or weekly target, a standing intention like 'get to 175 lbs', 'save " +
                "$30k by 2028', or 'ship the deck'. A NUMBER IS OPTIONAL: most goals are prose " +
                "only, and a goal with no number is a normal, complete goal, never a broken one - " +
                "never invent a fake number just to fill target_value. Restating an existing goal " +
                "for the same aspect with the same metric_key (e.g. giving the savings goal a new " +
                "dollar figure) REVISES it rather than creating a duplicate; call list_goals first " +
                "if unsure whether one already exists.",
            params = obj(
                "aspect" to schema(
                    "string",
                    "Which aspect this goal belongs to: bio (fitness/nutrition/sleep), log " +
                        "(notes, lists, planning), fleet (the car), or cred (personal finance).",
                    enum = GoalController.ASPECTS,
                ),
                "statement" to schema("string", "The goal in the driver's own words. Always required, even alongside a number."),
                "target_value" to schema("number", "Optional target number, e.g. 175 or 30000. Omit for a prose-only goal."),
                "unit" to schema("string", "Unit for target_value, e.g. 'lbs' or 'usd'. Omit if target_value is omitted."),
                "metric_key" to schema(
                    "string",
                    "Optional known metric this goal tracks for automatic progress math, e.g. " +
                        "'bodyweight_kg', 'savings_balance_cents', 'odometer_miles'. Leave unset " +
                        "for a goal with no app-tracked metric - most goals should leave this unset.",
                ),
                "deadline" to schema("string", "Optional deadline, month/day/year, e.g. 08/07/2026."),
            ),
            required = listOf("aspect", "statement"),
        ))

        fns.put(fn(
            name = "list_goals",
            description = "List the driver's current active goals. Omit aspect to list every " +
                "goal across every aspect at once - use that form for a broad 'how am I doing on " +
                "my goals' question.",
            params = obj(
                "aspect" to schema(
                    "string",
                    "Which aspect to list. Omit for every aspect at once.",
                    enum = GoalController.ASPECTS,
                ),
            ),
            required = listOf(),
        ))

        fns.put(fn(
            name = "close_goal",
            description = "Mark a goal achieved or abandoned. Identify it by aspect plus a few " +
                "words from its own statement, e.g. 'savings' or 'the old car' - if more than one " +
                "active goal in that aspect matches, this reports every match instead of guessing, " +
                "so ask the driver which one they mean rather than picking.",
            params = obj(
                "aspect" to schema("string", "Which aspect the goal belongs to.", enum = GoalController.ASPECTS),
                "statement" to schema("string", "A few words from the goal's statement, enough to identify it uniquely."),
                "status" to schema("string", "How it ended. Defaults to achieved.", enum = listOf("achieved", "abandoned")),
            ),
            required = listOf("aspect", "statement"),
        ))

        // --- Advisors (ticket 18, `.scratch/aspect-advisors/issues/18-build-ask-advisor-and-
        // accept.md`) -------------------------------------------------------------------------
        //
        // ONE tool, not five (ticket 01 answer call 2/ticket 18's own brief: "every declaration is
        // prompt tokens on every live session"). `aspect` covers bio/log/fleet/cred plus home for
        // an overall or cross-cutting question - AdvisorBriefs.forAspect resolves which brief
        // (playbook + digest builder + writable-op allowlist) actually runs. Measured +239 tokens
        // for this pair (ticket 11), judged acceptable.
        fns.put(fn(
            name = "ask_advisor",
            description = "Hand off to a domain advisor for coaching, planning, budgeting, or " +
                "maintenance-planning advice grounded in the driver's own record - it reasons from " +
                "a deterministic digest of what's actually logged, never a live web lookup, and " +
                "stays inside stated professional-referral boundaries rather than diagnosing or " +
                "prescribing past them. Pick 'bio' for fitness/nutrition/sleep coaching, 'log' for " +
                "planning/task/time-management advice, 'fleet' for maintenance planning, 'cred' for " +
                "personal-finance coaching and budgeting, and 'home' for anything OVERALL or CROSS-" +
                "CUTTING - a broad 'how am I doing' question, one that spans more than one aspect, " +
                "or whenever you are not sure which single aspect fits. ${HarnessPrompt.LATENCY_HINT} " +
                "The advisor may offer to write something back (a goal, a target, a plan, a " +
                "maintenance interval, a reminder) - it never writes anything itself. If it does, " +
                "tell the driver plainly what it is proposing, and only call accept_proposal once " +
                "they say a clear yes to exactly that - never assume, and never call accept_proposal " +
                "on your own initiative.",
            params = obj(
                "aspect" to schema(
                    "string",
                    "Which advisor to ask: bio, log, fleet, cred, or home for overall/cross-cutting.",
                    enum = AdvisorAspect.values().map { it.key },
                ),
                "question" to schema("string", "The driver's question, in their own words."),
            ),
            required = listOf("aspect", "question"),
        ))

        fns.put(fn(
            name = "accept_proposal",
            description = "Write the proposal a domain advisor just offered, EXACTLY as it was " +
                "proposed. Call this only after the driver gives a plain, explicit yes to it - never " +
                "on your own initiative. This executes the STORED proposal by its id, never values " +
                "you recall or restate yourself - you cannot pass or change any of its numbers here. " +
                "If the driver wants something different from what was proposed ('yes, but 3 days " +
                "not 4'), do NOT call this - ask the advisor again with the change and get a fresh " +
                "proposal to accept instead.",
            params = obj(
                "id" to schema("integer", "The advice id the proposal came back under, from ask_advisor's response."),
            ),
            required = listOf("id"),
        ))

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
     * `set_sleep_target` (config the meters read), `import_receipt`/`import_statement`/
     * `show_saved_places` (UI-scoped - [dispatch] returns null for these, and a sub-agent has no
     * screen to hand them to, so dispatching one from inside an investigate loop would be a silent
     * no-op), and every money/notes/media/place/core tool.
     */
    private val DISPATCHED: Map<String, List<String>> = mapOf(
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

    /**
     * The single required parameter every dispatcher tool below takes: a plain-English question
     * routed to that domain's own sub-agent, which then pulls whichever of the domain's real tools
     * its reasoning needs via [agentToolsFor].
     */
    private val DISPATCHER_QUESTION_PARAM: JSONObject
        get() = obj("question" to schema("string", "The driver's question, in their own words."))

    /**
     * Function declarations to advertise in the Live setup message: [allDeclarations] minus every
     * name [DISPATCHED] claims, plus the five dispatcher tools that stand in for them. This is the
     * ~13,300-token, 79-tool block the setup message used to carry in full on every turn - Live
     * re-bills the whole thing every turn, so trimming it is the entire point of this split (see
     * [DISPATCHED]'s doc comment for why).
     */
    fun declarations(): JSONArray {
        val dispatchedNames = DISPATCHED.values.flatten().toSet()
        val all = allDeclarations()
        val fns = JSONArray()
        for (i in 0 until all.length()) {
            val decl = all.getJSONObject(i)
            if (decl.getString("name") !in dispatchedNames) fns.put(decl)
        }

        fns.put(fn(
            name = "ask_fleet",
            description = "Anything about the cars: live sensor readings, trouble codes, mileage, " +
                "specs, recalls, service history, maintenance schedules, or the build log. Ask a " +
                "plain-English question; the answer comes back as text to speak.",
            params = DISPATCHER_QUESTION_PARAM,
            required = listOf("question"),
        ))
        fns.put(fn(
            name = "ask_body",
            description = "Anything about meals, sleep, workouts, or bodyweight: recent logs, how " +
                "today or this week compares to target, logging a new meal, night of sleep, " +
                "workout set, or weigh-in, or building a workout plan. Ask a plain-English " +
                "question; the answer comes back as text to speak.",
            params = DISPATCHER_QUESTION_PARAM,
            required = listOf("question"),
        ))
        fns.put(fn(
            name = "ask_goals",
            description = "Anything about the driver's long-term goals or domain-advisor coaching " +
                "across fitness, planning, the car, or money: listing current goals, or asking an " +
                "advisor for grounded advice. Ask a plain-English question; the answer comes back " +
                "as text to speak.",
            params = DISPATCHER_QUESTION_PARAM,
            required = listOf("question"),
        ))
        fns.put(fn(
            name = "ask_pantry",
            description = "Anything about groceries: recently logged items and their estimated " +
                "macros, total grocery spend by currency, or the current shopping trip list. Ask a " +
                "plain-English question; the answer comes back as text to speak.",
            params = DISPATCHER_QUESTION_PARAM,
            required = listOf("question"),
        ))
        fns.put(fn(
            name = "ask_mail",
            description = "Anything about Kevin's Gmail: searching for messages or reading one in " +
                "full. Read-only. Ask a plain-English question; the answer comes back as text to " +
                "speak.",
            params = DISPATCHER_QUESTION_PARAM,
            required = listOf("question"),
        ))

        return fns
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
                timeoutMs = 8_000,
                run = { args -> dispatch(context, name, args)?.toString() ?: "{}" },
            )
        }
    }

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

    // --- Dispatcher grounding clauses -------------------------------------
    //
    // Same shape as DiagnosticAgent/SymptomAgent/MaintenanceAgent's own `system(context)`
    // functions: AssistantIdentity's compressed sub-agent clause, plus what this particular
    // investigate loop is for and how to use the tools it's handed. Kept short - this text is
    // billed on every dispatcher call, not just once like the live setup block.

    private fun fleetDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are reasoning about the driver's cars using the tools you're given: live sensor " +
        "readings, trouble codes, mileage, specs, recalls, service history, and maintenance " +
        "scheduling and logging. Pull only what would change your answer, then answer in plain " +
        "spoken text, no markdown."

    private fun bodyDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are reasoning about the driver's meals, sleep, workouts, and bodyweight using the " +
        "tools you're given. Calorie and macro figures are LLM estimates, never measured - always " +
        "phrase them as estimates, never as fact. Pull only what would change your answer, then " +
        "answer in plain spoken text, no markdown."

    private fun goalsDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are reasoning about the driver's long-term goals and, when asked, handing off to a " +
        "domain advisor for grounded coaching or planning advice using the tools you're given. " +
        "Pull only what would change your answer, then answer in plain spoken text, no markdown."

    private fun pantryDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are reasoning about the driver's groceries using the tools you're given: recently " +
        "logged items and their estimated macros, total spend by currency, and the current " +
        "shopping trip list. Macro/calorie figures are estimates, never measured - phrase them " +
        "that way. Pull only what would change your answer, then answer in plain spoken text, no " +
        "markdown."

    private fun mailDispatchGrounding(context: Context) = AssistantIdentity.shortClause(context) +
        " You are searching and reading the driver's Gmail using the tools you're given. " +
        "Read-only - you cannot send, reply to, or delete mail. Pull only what would change your " +
        "answer, then answer in plain spoken text, no markdown."

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
    ): JSONObject? {
        MidnightEvents.toolDispatched(name)
        // Category A guard (ticket 01 §0): if a live-hardware tool got handed a
        // `vehicle` argument anyway (the model shouldn't, since these declare no
        // such parameter, but nothing enforces that at the wire level) and it
        // doesn't name the car the dongle is actually in, refuse before ever
        // touching the connected car's real readings. Category B tools are
        // exempt - they resolve their own `vehicle` argument below.
        if (name in CATEGORY_A_TOOLS) {
            refuseIfNotConnectedCar(context, args)?.let { return it }
        }
        return when (name) {
            "get_vehicle_data" -> getVehicleData(args.optString("metric"))
            "get_codes" -> getCodes(context)
            "get_current_time" -> getCurrentTime()
            "diagnose_codes" -> diagnoseCodes(context, args)
            "read_vehicle_sensor" -> readVehicleSensor(context, args)
            "get_code_history" -> withResolvedVehicle(context, args) { vehicle ->
                val limit = args.optInt("limit", 5).coerceIn(1, 25)
                val history = CarToolbelt.codeHistory(context, limit, vehicle.obdMac)
                JSONObject()
                    .put("success", true)
                    // Ticket 04's label rule: this "vehicle" field is a single identifier the model
                    // is expected to speak back (unlike list_vehicles' decomposed name/year/make/
                    // model/trim tuple below, which stays raw), so it goes through the one rule too.
                    .put("vehicle", VehicleController.label(vehicle))
                    .put("history", history)
            }
            "triage_symptom" -> triageSymptom(context, args)
            // The five dispatchers (see DISPATCHED's doc comment): each hands a bounded
            // investigate loop only the real tools its own domain needs (agentToolsFor), so a
            // dispatched tool's underlying behavior - including the CATEGORY_A_TOOLS guard above,
            // which still runs because these AgentTools call back into this same dispatch() - is
            // completely unchanged from before this ticket. Only who calls it, and how the result
            // gets back to the driver, changed.
            "ask_fleet" -> agentResult("I couldn't reach the fleet specialist just now - try again in a sec.") {
                SubAgent(systemInstruction = fleetDispatchGrounding(context)).investigate(
                    context = "",
                    question = args.optString("question"),
                    tools = agentToolsFor("fleet", context),
                    maxModelCalls = 4,
                    budgetMs = 30_000,
                    mutatingToolNames = mutatingToolsFor("fleet"),
                )
            }
            "ask_body" -> agentResult("I couldn't reach the health specialist just now - try again in a sec.") {
                SubAgent(systemInstruction = bodyDispatchGrounding(context)).investigate(
                    context = "",
                    question = args.optString("question"),
                    tools = agentToolsFor("body", context),
                    maxModelCalls = 4,
                    budgetMs = 30_000,
                    mutatingToolNames = mutatingToolsFor("body"),
                )
            }
            "ask_goals" -> agentResult("I couldn't reach the goals specialist just now - try again in a sec.") {
                SubAgent(systemInstruction = goalsDispatchGrounding(context)).investigate(
                    context = "",
                    question = args.optString("question"),
                    tools = agentToolsFor("goals", context),
                    maxModelCalls = 4,
                    budgetMs = 30_000,
                    mutatingToolNames = mutatingToolsFor("goals"),
                )
            }
            "ask_pantry" -> agentResult("I couldn't reach the grocery specialist just now - try again in a sec.") {
                SubAgent(systemInstruction = pantryDispatchGrounding(context)).investigate(
                    context = "",
                    question = args.optString("question"),
                    tools = agentToolsFor("pantry", context),
                    maxModelCalls = 4,
                    budgetMs = 30_000,
                    mutatingToolNames = mutatingToolsFor("pantry"),
                )
            }
            "ask_mail" -> agentResult("I couldn't reach your mail just now - try again in a sec.") {
                SubAgent(systemInstruction = mailDispatchGrounding(context)).investigate(
                    context = "",
                    question = args.optString("question"),
                    tools = agentToolsFor("mail", context),
                    maxModelCalls = 4,
                    budgetMs = 30_000,
                    mutatingToolNames = mutatingToolsFor("mail"),
                )
            }
            "get_health" -> getHealth()
            "get_trend" -> withResolvedVehicle(context, args) { getTrend(context, args, it.obdMac) }
            "get_mpg" -> withResolvedVehicle(context, args) { getMpg(context, it) }
            "check_readiness" -> checkReadiness()
            "clear_codes" -> clearCodes(context, args)
            "check_cold_start" -> checkColdStart(context)
            "get_next_service" -> withResolvedVehicle(context, args) { getNextService(context, it) }
            "ask_maintenance" -> withResolvedVehicle(context, args) { askMaintenance(context, args, it) }
            "control_music" -> controlMusic(context, args)
            "control_volume" -> controlVolume(context, args)
            "get_current_location" -> getCurrentLocation(context)
            "play_music" -> playMusic(context, args.optString("query"))
            "show_app" -> showApp(context)
            "set_reminder" -> result(
                success = true,
                message = ReminderController.add(context, args.optString("place"), args.optString("text")),
            )
            "tag_place" -> result(
                success = true,
                message = PlaceController.tagPlace(context, args.optString("label"))
            )
            "forget_place" -> result(success = true, message = PlaceController.forgetPlace(context, args.optString("label")))
            // set_odometer/log_service/log_past_service/set_maintenance_interval: success is now
            // DERIVED from the underlying write (ticket 05, "the no-op guard is law now"), never
            // hardcoded - VehicleController's *Direct functions return a WriteOutcome precisely so
            // this dispatch can't assert success above a write that failed the way the rest of the
            // 194 result( calls in this file still do (deliberately out of scope, see ticket 05's
            // answer, "scoped, not swept").
            "set_odometer" -> withResolvedVehicle(context, args) {
                val outcome = VehicleController.setOdometer(context, args.optInt("miles"), it.obdMac)
                result(success = outcome.success, message = outcome.message)
            }
            "log_service" -> withResolvedVehicle(context, args) {
                // Dollars -> cents at the voice edge (CLAUDE.md §4 rule 3: VehicleController.logServiceDirect
                // only ever sees Long cents) - same Math.round(amount * 100) shape
                // LedgerPendingLog.pendingAmountCents already uses for a spoken dollar figure, with the
                // same non-finite/non-positive guard so a misheard "cost" argument can't write a bogus
                // negative or NaN-derived value.
                val costCents = if (args.has("cost") && !args.isNull("cost")) {
                    val dollars = args.optDouble("cost")
                    if (!dollars.isNaN() && dollars.isFinite() && dollars > 0.0) Math.round(dollars * 100.0) else null
                } else {
                    null
                }
                val outcome = VehicleController.logServiceDirect(context, args.optString("service"), it.obdMac, costCents)
                result(success = outcome.success, message = outcome.message)
            }
            "log_past_service" -> withResolvedVehicle(context, args) { logPastService(context, args, it.obdMac) }
            "set_maintenance_interval" -> withResolvedVehicle(context, args) {
                val outcome = VehicleController.setMaintenanceInterval(
                    context,
                    args.optString("service"),
                    args.optInt("interval_miles", -1).takeIf { it > 0 },
                    args.optInt("interval_months", -1).takeIf { it > 0 },
                    it.obdMac,
                )
                result(success = outcome.success, message = outcome.message)
            }
            "lookup_vin" -> lookupVin(context)
            "get_specs" -> withResolvedVehicle(context, args) { getSpecs(context, it.obdMac) }
            "check_recalls" -> withResolvedVehicle(context, args) { checkRecalls(context, it.obdMac) }
            "register_vehicle" -> result(
                success = true,
                message = VehicleController.registerDirect(
                    context, args.optInt("year"), args.optString("make"), args.optString("model")
                )
            )
            // Ticket 21 (google-account-integration, "close the remember leak"): refuse in words
            // rather than silently stripping or quietly recording provenance - the mail
            // read-through rule (CLAUDE.md §7, ticket 07) is written as absolute ("mail is read,
            // used, dropped"), and a refusal is the only implementation consistent with that. See
            // rememberBlockedByReadThroughTool's doc for why this checks a pre-reduced boolean
            // rather than re-testing tool names here.
            "remember" -> if (rememberBlockedByReadThroughTool(touchedReadThroughToolThisTurn)) {
                result(success = false, message = REMEMBER_MAIL_REFUSAL)
            } else {
                result(success = true, message = AriaBrain.get(context).remember(args.optString("text")))
            }
            "recall_memory" -> recallMemory(context, args.optString("query"))
            // Absorbed the retired add_car_task/complete_car_task/remove_car_task/list_car_tasks
            // (ticket 10) - car items are now just items on the list named "Car".
            "manage_item" -> manageItem(context, args)
            "read_list" -> readList(context)
            "manage_grocery" -> manageGrocery(context, args)
            "log_build_entry" -> withResolvedVehicle(context, args) { logBuildEntry(context, args, it.obdMac) }
            "list_build_history" -> withResolvedVehicle(context, args) { listBuildHistory(context, args.optString("type"), it.obdMac) }
            "get_spend" -> getSpend(context, args.optString("category"))
            "activate_garage" -> activateGarage(context, args)
            "categorize_transactions" -> categorizeTransactions(context)
            "set_category" -> setCategory(context, args)
            "get_balance" -> getLedgerBalance(context, args.optString("account"))
            "list_recent_transactions" -> listRecentTransactions(context, args.optInt("count", 10))
            "log_pending_transaction" -> logPendingTransaction(context, args)
            "list_pending_transactions" -> listPendingTransactions(context)
            "clear_pending_transaction" -> clearPendingTransaction(context, args.optString("description"))
            "list_recent_groceries" -> listRecentGroceries(context, args.optInt("count", 10))
            "get_grocery_spend" -> getGrocerySpend(context)
            "create_workout_plan" -> result(success = true, message = WorkoutController.generatePlan(context, args.optString("goal")))
            "log_workout_set" -> logWorkoutSet(context, args)
            "log_bodyweight" -> result(
                success = true,
                message = WorkoutController.logBodyweight(context, args.optDouble("weight"), args.optString("weight_unit", "lbs")),
            )
            "get_workout_gap" -> getWorkoutGap(context)
            "list_recent_workouts" -> listRecentWorkouts(context, args.optInt("count", 10))
            "log_meal" -> result(success = true, message = MealController.logMeal(context, args.optString("description")))
            "set_meal_target" -> result(
                success = true,
                message = MealController.setTarget(
                    context, args.optInt("calories"), args.optDouble("protein_g"),
                    args.optDouble("carbs_g"), args.optDouble("fat_g"),
                ),
            )
            "set_budget" -> setBudget(context, args)
            "list_budget_categories" -> listBudgetCategories(context)
            "get_monthly_spend" -> getMonthlySpend(context)
            "get_meal_gap" -> getMealGap(context)
            "list_recent_meals" -> listRecentMeals(context, args.optInt("count", 10))
            "log_sleep" -> logSleep(context, args)
            "set_sleep_target" -> result(success = true, message = com.kevin.legion.sleep.SleepController.setTarget(context, args.optDouble("hours")))
            "get_sleep_gap" -> getSleepGap(context)
            "list_recent_sleep" -> listRecentSleep(context, args.optInt("count", 10))
            "undo_last_log" -> undoLastLog(context)
            "list_vehicles" -> listVehicles(context)
            "manage_vehicle" -> manageVehicle(context, args)
            "search_mail" -> searchMail(context, args)
            "read_mail" -> readMail(context, args)
            "read_calendar" -> readCalendar(context, args)
            "set_goal" -> setGoalTool(context, args)
            "list_goals" -> listGoalsTool(context, args)
            "close_goal" -> closeGoalTool(context, args)
            "ask_advisor" -> askAdvisorTool(context, args)
            "accept_proposal" -> acceptProposalTool(context, args)
            // Session-scoped tools the owning controller handles (it has the live
            // session / capture controller / activity), so dispatch returns null:
            "show_saved_places" -> null // caller launches the saved-places screen and replies
            "import_statement" -> null // caller launches the statement-import screen and replies
            "import_receipt" -> null // caller launches the receipt-import screen and replies
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
     * `show_saved_places`/`import_statement`/`import_receipt` are UI-scoped hand-offs - [dispatch]
     * itself writes nothing for them, the screen it launches does, downstream of this file entirely.
     */
    private val MUTATING_TOOLS = setOf(
        "clear_codes", "set_reminder", "tag_place", "forget_place", "set_odometer", "log_service",
        "log_past_service", "set_maintenance_interval", "register_vehicle", "remember",
        "manage_item", "manage_grocery", "log_build_entry", "activate_garage",
        "categorize_transactions", "set_category", "log_pending_transaction",
        "clear_pending_transaction", "create_workout_plan", "log_workout_set", "log_bodyweight",
        "log_meal", "set_meal_target", "set_budget", "log_sleep", "set_sleep_target",
        "undo_last_log", "manage_vehicle", "set_goal", "close_goal", "accept_proposal",
    )

    /**
     * Every tool whose result must never reach [com.kevin.legion.data.local.EpisodicTurn]/
     * [com.kevin.legion.data.local.CompanionMemory] (ticket 07's read-through rule, ticket 15's
     * build). [com.kevin.legion.service.GeminiLiveSession] checks a functionCall's name against
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
    val EPISODIC_EXCLUDED_TOOLS = setOf("search_mail", "read_mail", "ask_mail")

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
     * rule numbers, matching [AriaBrain]'s own REMEMBER_ACKS for tone rather than reading like a
     * system error.
     */
    private const val REMEMBER_MAIL_REFUSAL = "I don't keep anything from mail - that's on " +
        "purpose, not a slip. Tell me the fact yourself, in your own words, and I'll remember that."

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
        val connectedId = ObdBluetoothManager.connectedDeviceAddress
        if (connectedId == null) {
            // Nothing connected at all - each of these tools already returns
            // its own "OBD adapter isn't connected" message; a second,
            // contradictory refusal here would just be confusing.
            return null
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
                    "only read live data for the car it's actually in.",
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
    /**
     * The whole fleet-ownership surface behind one tool (see the registration's
     * comment for why it is one tool and not five).
     *
     * `add` is the only action that does not resolve a vehicle first - it is the
     * only one that must not be able to reach an existing row. Every other action
     * goes through [withResolvedVehicle], so an unknown or ambiguous car comes
     * back as a question to the driver rather than landing on whichever car
     * happened to be active. That distinction IS the bug this tool fixes.
     */
    private suspend fun manageVehicle(context: Context, args: JSONObject): JSONObject {
        val year = args.optInt("year").takeIf { it >= 1900 }
        val make = args.optString("make").takeIf { it.isNotBlank() }
        val model = args.optString("model").takeIf { it.isNotBlank() }
        val trim = args.optString("trim").takeIf { it.isNotBlank() }
        val name = args.optString("name").takeIf { it.isNotBlank() }

        val action = args.optString("action")

        // VehicleResolver.resolveVehicle treats a blank `vehicle` as "the active
        // car". For a READ that is a sane default; for an edit it is precisely
        // the failure being fixed here - an unnamed "correct" would silently
        // rewrite whatever car happened to be active, which is how the Outlander
        // became an F-150. Every editing action must name its target out loud.
        if (action != "add" && args.optString("vehicle").isBlank()) {
            return result(false, "Which car do you mean? Tell me and I'll $action it.")
        }

        return when (action) {
            "add" -> result(
                success = make != null && model != null,
                message = VehicleController.addVehicle(
                    context,
                    year = year ?: 0,
                    make = make.orEmpty(),
                    model = model.orEmpty(),
                    trim = trim.orEmpty(),
                    name = name.orEmpty(),
                ),
            )
            "correct" -> withResolvedVehicle(context, args) {
                result(true, VehicleController.correctVehicle(
                    context, it.obdMac, year = year, make = make, model = model, trim = trim,
                ))
            }
            "rename" -> withResolvedVehicle(context, args) {
                if (name == null) result(false, "What would you like me to call it?")
                else result(true, VehicleController.correctVehicle(context, it.obdMac, name = name))
            }
            "switch" -> withResolvedVehicle(context, args) {
                result(true, VehicleController.switchTo(context, it.obdMac))
            }
            "archive" -> withResolvedVehicle(context, args) {
                VehicleController.archive(context, it.obdMac)
                result(true, "Archived the ${VehicleController.label(it)}. Nothing was deleted - its history is still there.")
            }
            // Deliberately NOT withResolvedVehicle: VehicleResolver only ever
            // matches against the non-archived roster (see its own comment), so
            // routing unarchive through it makes the one action whose target is
            // archived by definition permanently unreachable.
            "unarchive" -> {
                val query = args.optString("vehicle")
                // Matching, not labelling - kept on displayLabel's trim-inclusive spec deliberately
                // (ticket 04's label rule is about how a car is NAMED to the driver, not what a
                // spoken query is matched against; narrowing this to the label string would drop
                // trim as a matchable word, e.g. "the Limited", for no gain).
                val hits = VehicleController.allVehiclesIncludingArchived(context)
                    .filter { it.archived }
                    .filter {
                        it.name.equals(query, true) || it.model.equals(query, true) ||
                            VehicleController.displayLabel(it).contains(query, true)
                    }
                when (hits.size) {
                    1 -> {
                        VehicleController.unarchive(context, hits[0].obdMac)
                        result(true, "The ${VehicleController.label(hits[0])} is back on the roster.")
                    }
                    0 -> result(false, "I don't have an archived car matching \"$query\".")
                    else -> result(false, "\"$query\" matches more than one archived car - " +
                        "${hits.joinToString(", ") { VehicleController.label(it) }}. Which one?")
                }
            }
            else -> result(false, "I don't know how to \"$action\" a car.")
        }
    }

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
     * `read_calendar` (ticket 19). Never queries `CalendarContract` itself - reuses
     * [CalendarProvider.eventsInWindow], the same read [com.kevin.legion.ui.TodayScreen] and
     * [com.kevin.legion.ui.notes.InboxScreen] already call, over every `com.google` calendar on
     * the device (ticket 17's split: this is a READ, so no `CAL_ACCESS_CONTRIBUTOR` floor).
     *
     * The permission check happens HERE, before [CalendarReadToolLogic.parseWindow] even runs, so
     * a refused/never-granted `READ_CALENDAR` always returns
     * [CalendarReadToolLogic.PERMISSION_MISSING_MESSAGE] and nothing else - never an `events`
     * array, empty or otherwise. A service has no Activity to raise the system permission dialog
     * from, so this sentence, naming the screen that can, is the entire recovery path.
     */
    private fun readCalendar(context: Context, args: JSONObject): JSONObject {
        if (!CalendarProvider.hasReadPermission(context)) {
            return result(false, CalendarReadToolLogic.PERMISSION_MISSING_MESSAGE)
        }
        val window = CalendarReadToolLogic.parseWindow(
            args.optString("from"), args.optString("to"), java.time.ZoneId.systemDefault(),
        ) ?: return result(false, CalendarReadToolLogic.INVALID_WINDOW_MESSAGE)
        val (startMs, endMs) = window

        val events = CalendarProvider.eventsInWindow(context, startMs, endMs)
        val arr = JSONArray()
        for (event in events) {
            arr.put(
                JSONObject()
                    .put("title", event.title)
                    .put(
                        "start",
                        if (event.allDay) com.kevin.legion.util.documentDate(event.startMs)
                        else "${com.kevin.legion.util.shortDate(event.startMs)} ${com.kevin.legion.util.clockTime(event.startMs)}",
                    )
                    .put(
                        "end",
                        if (event.allDay) com.kevin.legion.util.documentDate(event.endMs)
                        else "${com.kevin.legion.util.shortDate(event.endMs)} ${com.kevin.legion.util.clockTime(event.endMs)}",
                    )
                    .put("all_day", event.allDay),
            )
        }
        val o = JSONObject().put("success", true).put("count", arr.length()).put("events", arr)
        if (arr.length() == 0) o.put("message", "Nothing on the calendar in that window.")
        return o
    }

    private suspend fun withResolvedVehicle(
        context: Context,
        args: JSONObject,
        onResolved: suspend (Vehicle) -> JSONObject,
    ): JSONObject = when (val match = VehicleResolver.resolveVehicle(context, args.optString("vehicle").takeIf { it.isNotBlank() })) {
        is VehicleMatch.Resolved -> onResolved(match.vehicle)
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
     * Every non-archived car (fleet-wide voice, ticket 01 §3): name, year,
     * make, model, trim, whether it's the active one, whether the OBD dongle
     * is connected to it right now, and last-known odometer. This is the tool
     * that makes the fleet knowable at all - see its declaration's doc.
     */
    private suspend fun listVehicles(context: Context): JSONObject {
        val vehicles = CarDatabase.getDatabase(context).vehicleDao().getAll()
        val activeId = ActiveVehicle.current(context)
        val connectedId = ObdBluetoothManager.connectedDeviceAddress
        val arr = JSONArray()
        for (v in vehicles) {
            // Raw v.name deliberately, not VehicleController.label - this is a decomposed tuple
            // (name/year/make/model/trim as separate fields, ticket 04's label rule doesn't apply
            // to the individual raw facts), unlike the single "vehicle" identifier field other
            // tools below send when there is no sibling year/make/model to decompose into.
            val o = JSONObject()
                .put("name", v.name)
                .put("year", v.year)
                .put("make", v.make)
                .put("model", v.model)
                .put("trim", v.trim)
                .put("active", v.obdMac == activeId)
                .put("dongleConnected", connectedId != null && v.obdMac == connectedId)
            // Ticket 10: this JSON goes straight to the model, which then speaks it - a bare Int
            // here would let it state a possibly-5-15%-low estimate back as fact. mileageLabel
            // already carries its own bare/estimate split ("227,900 mi" vs. "about 227,900 mi -
            // estimated, last confirmed 3 days ago"), so the string itself is the caveat.
            val mileageLabel = VehicleController.mileageLabel(v)
            if (mileageLabel.isNotBlank()) o.put("odometer", mileageLabel)
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", vehicles.size).put("vehicles", arr)
    }

    /**
     * Reads the VIN off the OBD port and decodes it ([VinDecoder]) to year/make/
     * model/trim, for Zero to read back and confirm before saving via
     * register_vehicle. Does NOT save - the driver is the source of truth, and
     * VIN decode (vPIC) can be partial on imports.
     */
    private suspend fun lookupVin(context: Context): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return result(success = false,
                message = "The OBD adapter isn't connected, so I can't read the VIN - plug it in and try again.")
        }
        val decoded = VinDecoder.fromObd()
            ?: return result(success = false, message = "I couldn't read a usable VIN from the port - " +
                "this car may not report one (common on older or non-US cars). You can just tell me the year, make, and model.")
        // Populate the build-details encyclopedia (specs) from the same VIN; the
        // identity facts below still need driver confirmation before register_vehicle.
        VehicleSpecController.refreshFromVin(context, decoded.vin)
        return JSONObject()
            .put("success", true)
            .put("vin", decoded.vin)
            .put("year", decoded.year)
            .put("make", decoded.make)
            .put("model", decoded.model)
            .put("trim", decoded.trim)
            .put("note", "These are read-only. Read them back and ask the driver to confirm, then call register_vehicle to save. The full specs were saved to the build-details encyclopedia.")
    }

    /**
     * Reads a car's stored factory specs ([VehicleSpecController]) for Zero to
     * read out. [vehicleId] is the resolved fleet-wide-voice override (ticket
     * 01) - null means the active car.
     */
    private suspend fun getSpecs(context: Context, vehicleId: String? = null): JSONObject {
        val spec = VehicleSpecController.current(context, vehicleId)
            ?: return result(success = false, message = "No specs on file yet - run a VIN lookup, or fill them in under Logbook, Specs.")
        val o = JSONObject().put("success", true)
        spec.engineCylinders?.let { o.put("engineCylinders", it) }
        spec.displacementL?.let { o.put("displacementLiters", it) }
        spec.engineHp?.let { o.put("horsepower", it) }
        putIfSet(o, "engineConfiguration", spec.engineConfig)
        putIfSet(o, "fuelType", spec.fuelType)
        putIfSet(o, "transmission", listOf(spec.transmissionSpeeds, spec.transmissionStyle).filter { it.isNotBlank() }.joinToString(" "))
        putIfSet(o, "drivetrain", spec.driveType)
        putIfSet(o, "bodyClass", spec.bodyClass)
        spec.doors?.let { o.put("doors", it) }
        putIfSet(o, "series", spec.series)
        putIfSet(o, "manufacturer", spec.manufacturer)
        putIfSet(o, "assembledIn", listOf(spec.plantCity, spec.plantCountry).filter { it.isNotBlank() }.joinToString(", "))
        putIfSet(o, "paintColor", spec.paintColor)
        putIfSet(o, "paintCode", spec.paintCode)
        putIfSet(o, "notes", spec.buildNotes)
        return o
    }

    private fun putIfSet(o: JSONObject, key: String, value: String) {
        if (value.isNotBlank()) o.put(key, value)
    }

    /**
     * Live NHTSA recall lookup for a car ([VehicleSpecController]); on-request
     * only. [vehicleId] is the resolved fleet-wide-voice override (ticket 01) -
     * null means the active car.
     *
     * Gates on identity-present (year/make/model all set), not
     * [com.kevin.legion.data.local.Vehicle.confirmed] - ticket 12
     * (`.scratch/fleet-maintenance/issues/12-a-recall-button.md`) found this tool refusing on
     * `confirmed` while the proactive startup push read `year/make/model` directly with no check
     * at all, so the same car could get recalls announced unprompted and then refused when asked.
     * [VehicleSpecController.recalls] now owns the one gate both paths share.
     */
    private suspend fun checkRecalls(context: Context, vehicleId: String? = null): JSONObject {
        return when (val outcome = VehicleSpecController.recalls(context, vehicleId)) {
            is RecallCheckResult.IdentityMissing -> result(
                success = false,
                message = "I don't know this car's ${outcome.missing.joinToString(" and ")} yet, " +
                    "so I can't check recalls. Tell me and I'll look it up, or run a VIN lookup.",
            )
            RecallCheckResult.LookupFailed -> result(
                success = false,
                message = "Couldn't reach NHTSA to check recalls right now. Try again in a moment.",
            )
            is RecallCheckResult.Checked -> {
                val arr = JSONArray()
                for (r in outcome.recalls) {
                    arr.put(JSONObject()
                        .put("campaign", r.campaign)
                        .put("component", r.component)
                        .put("summary", r.summary)
                        .put("remedy", r.remedy))
                }
                JSONObject().put("success", true).put("count", outcome.recalls.size).put("recalls", arr)
            }
        }
    }

    /** Searches long-term memory for entries relevant to [query], for Zero to read out. */
    private suspend fun recallMemory(context: Context, query: String): JSONObject {
        val memories = AriaBrain.get(context).recallMemories(query)
        val arr = JSONArray()
        for (m in memories) arr.put(m)
        return JSONObject().put("success", true).put("count", memories.size).put("memories", arr)
    }

    // --- Build sheet / spend ledger -------------------------------------

    /**
     * Logs a build-sheet entry (mod/part/repair/consumable/other), with
     * optional cost. [vehicleId] is the resolved fleet-wide-voice override
     * (ticket 01) - null means the active car.
     */
    private suspend fun logBuildEntry(context: Context, args: JSONObject, vehicleId: String? = null): JSONObject {
        val title = args.optString("title")
        if (title.isBlank()) return result(success = false, message = "What should I log on the build sheet?")
        val cost = if (args.has("cost") && !args.isNull("cost"))
            args.optDouble("cost").takeIf { !it.isNaN() } else null
        val msg = BuildSheetController.add(
            context,
            title = title,
            type = args.optString("type"),
            cost = cost,
            vendor = args.optString("vendor"),
            notes = args.optString("notes"),
            vehicleId = vehicleId,
        )
        return result(success = true, message = msg)
    }

    /**
     * Reads back build history (what/when, plus cost - ungated, see
     * [getSpend]'s doc). [vehicleId] is the resolved fleet-wide-voice override
     * (ticket 01) - null means the active car.
     */
    private suspend fun listBuildHistory(context: Context, type: String, vehicleId: String? = null): JSONObject {
        val entries = BuildSheetController.history(context, type, vehicleId)
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject().put("title", e.title).put("type", e.type).put("date", shortDate(e.date))
            if (e.mileage != null) o.put("mileage", e.mileage)
            if (e.vendor.isNotBlank()) o.put("vendor", e.vendor)
            if (e.notes.isNotBlank()) o.put("notes", e.notes)
            // No currency column on BuildEntry (2026-08-07 currency audit, see getSpend's doc
            // comment) - a cost is still reported when the driver stated one, but never with an
            // invented currency attached.
            if (e.cost != null) {
                o.put("cost", e.cost)
                o.put("currency", JSONObject.NULL)
            }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", entries.size).put("entries", arr)
    }

    /**
     * Reports total / per-category spend. No gating - SpendGate was retired
     * 2026-07-31 with no replacement built yet.
     */
    /**
     * 2026-08-07 currency audit (CLAUDE.md §4, the "money figures must carry their currency"
     * pass): [BuildSheetController]'s `Double`-cost build-sheet entries carry NO currency column
     * at all - unlike [com.kevin.legion.ledger.LedgerTransaction]/[com.kevin.legion.data.local.PantryReceipt],
     * which both do. Per the audit's own instruction, a currency is NEVER invented or guessed
     * from the device locale here - `currency` is emitted as `JSONObject.NULL` with a `note`
     * saying plainly that the source never recorded one, so the model can still say the bare
     * number without silently attaching sterling (or any other currency) to it. This is the
     * traced root cause of the bug this whole pass exists to close: `getLedgerBalance` used to
     * emit a bare `balance` figure the same way, and the English-butler persona filled the gap
     * with pounds every time.
     */
    private suspend fun getSpend(context: Context, category: String): JSONObject {
        val byCat = BuildSheetController.spendByCategory(context)
        val currencyNote = "this figure's source data has no currency recorded - state the " +
            "number without naming a currency"
        val cat = category.trim().lowercase()
        if (cat.isNotBlank()) {
            val amount = byCat[cat] ?: byCat[BuildSheetController.normalizeType(cat)] ?: 0.0
            return JSONObject().put("success", true).put("category", cat).put("spend", amount)
                .put("currency", JSONObject.NULL).put("currency_note", currencyNote)
        }
        val catObj = JSONObject()
        for ((k, v) in byCat) catObj.put(k, v)
        return JSONObject().put("success", true)
            .put("total", BuildSheetController.totalSpend(context))
            .put("byCategory", catObj)
            .put("currency", JSONObject.NULL).put("currency_note", currencyNote)
    }

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
                },
            )
            else -> result(false, "I don't see any transactions from \"$merchant\" on file.")
        }
    }

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
        if (matched.isEmpty()) {
            return result(success = false, message = "No ledger accounts on file yet - import a statement first.")
        }
        // Fetched once, filtered per account below by the same suffix+currency
        // match pendingDeltaCents itself uses - cheaper than one DB round trip
        // per matched account for a list that is typically small.
        val allPending = LedgerController.pendingTransactions(context)
        val balances = JSONObject()
        for (b in matched) {
            val entry = JSONObject()
            val postedBalanceCents = b.balanceCents
            val availableCents = (postedBalanceCents ?: 0L) + b.provisionalDeltaCents + b.pendingDeltaCents
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
        return JSONObject().put("success", true).put("balances", balances)
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
        // never as if it were a stated balance.
        val newAvailable = (resolved.balanceCents ?: 0L) + resolved.provisionalDeltaCents +
            resolved.pendingDeltaCents + amountCents
        val figure = " New available balance for ${resolved.accountId}: ${formatMoney(newAvailable, resolved.currency)}."
        return result(
            true,
            "Logged: ${formatMoney(amountCents, resolved.currency)} pending on ${resolved.accountId}." +
                "$figure This is your own report, not yet confirmed by the bank.",
        )
    }

    /** `MM/dd/yyyy` - matches [com.kevin.legion.ledger.parsers.BofaCsvStatementParser]'s own convention. */
    private val PENDING_DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy")

    /**
     * Dispatches `list_pending_transactions`. Every row carries `verified = false` unconditionally
     * - there is no other kind of row [LedgerController.pendingTransactions] can return, unlike
     * [listRecentTransactions] where verified varies row to row.
     */
    private suspend fun listPendingTransactions(context: Context): JSONObject {
        val rows = LedgerController.pendingTransactions(context)
        val arr = JSONArray()
        var totalCents = 0L
        for (r in rows) {
            arr.put(
                JSONObject()
                    .put("date", documentDate(r.txnDate))
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
        val arr = JSONArray()
        for (t in transactions) {
            val o = JSONObject()
                .put("date", documentDate(t.txnDate))
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
        return JSONObject().put("success", true).put("count", items.size).put("items", arr)
    }

    /**
     * Total logged grocery spend, PER CURRENCY, never combined into one bare figure (2026-08-07
     * currency audit) - see [com.kevin.legion.pantry.PantryController.totalSpendCentsByCurrency]'s
     * doc comment for the SGD/USD-mixing bug this closes. `total`/`currency` are kept ONLY when
     * exactly one currency is on file, so an older caller reading those two legacy keys gets a
     * real answer in the common single-currency case rather than a silently wrong combined sum;
     * `byCurrency` is the field every new caller should read.
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
        return o
    }

    /**
     * Dispatches log_workout_set. `weight`/`weight_unit` travel together - a weight value with no
     * stated unit defaults to lbs rather than being dropped, since the driver clearly meant
     * something by naming a number.
     */
    private suspend fun logWorkoutSet(context: Context, args: JSONObject): JSONObject {
        val weight = if (args.has("weight")) args.optDouble("weight").takeIf { !it.isNaN() } else null
        val weightUnit = if (weight != null) args.optString("weight_unit", "lbs") else null
        val reps = if (args.has("reps")) args.optInt("reps") else null
        return result(
            success = true,
            message = WorkoutController.logSet(
                context, args.optString("exercise"), args.optInt("sets"), reps, weight, weightUnit,
            ),
        )
    }

    /** D24's weekly session gap, worded for the model to speak. */
    private suspend fun getWorkoutGap(context: Context): JSONObject {
        val gap = WorkoutController.weekGap(context)
            ?: return result(success = false, message = "No workout plan set yet - ask the driver if they'd like one.")
        return JSONObject()
            .put("success", true)
            .put("sessionsPlanned", gap.target)
            .put("sessionsDone", gap.actual)
            .put("sessionsRemaining", gap.gap)
            .put("reported", gap.tier == com.kevin.legion.plan.TrustTier.REPORTED)
    }

    /** Recent workout set logs. */
    private suspend fun listRecentWorkouts(context: Context, count: Int): JSONObject {
        val logs = WorkoutController.recentSets(context, count.coerceIn(1, 100))
        val arr = JSONArray()
        for (l in logs) {
            val o = JSONObject().put("exercise", l.exercise).put("sets", l.sets)
            l.reps?.let { o.put("reps", it) }
            l.weightValue?.let { o.put("weight", it) }
            l.weightUnit?.let { o.put("weightUnit", it) }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", logs.size).put("sets", arr)
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
    private suspend fun getMonthlySpend(context: Context): JSONObject {
        // "This month" is the driver's month, not UTC's - matches setBudget's own reasoning above.
        val month = YearMonth.now(java.time.ZoneId.systemDefault())
        val budget = LedgerController.budgetVsActual(context, LedgerEntity.US, month)
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
            .put("total_spent_cents", totalCents)
            .put("verified", !unverified)
            .put("excluded_own_account_movements_count", excluded.count)
            .put("excluded_own_account_movements_cents", excluded.totalCents)
            .put("uncategorized_cents", budget.uncategorized.spentCents)
            .put("uncategorized_note", uncategorizedNote)
            .put("note", excludedOwnAccountMovementsSentence(excluded, budget.entity.currency))
    }

    // --- Goals (ticket 19) --------------------------------------------------

    /** The driver never says `bio`/`log`/`fleet`/`cred` - these back every goal-tool confirmation
     * phrase into the words they'd actually recognize, matching each aspect's tab label. */
    private fun aspectLabel(aspect: String): String = when (aspect) {
        "bio" -> "Body"
        "log" -> "Notes"
        "fleet" -> "Fleet"
        "cred" -> "Money"
        else -> aspect
    }

    private suspend fun setGoalTool(context: Context, args: JSONObject): JSONObject {
        val aspect = args.optString("aspect").trim().lowercase()
        if (aspect !in GoalController.ASPECTS) return result(false, "Which aspect - body, notes, fleet, or money?")
        val statement = args.optString("statement").trim()
        if (statement.isBlank()) return result(false, "What's the goal?")
        val targetValue = if (args.has("target_value") && !args.isNull("target_value")) args.optDouble("target_value") else null
        val unit = args.optString("unit").trim().takeIf { it.isNotBlank() }
        val metricKey = args.optString("metric_key").trim().takeIf { it.isNotBlank() }
        val deadlineRaw = args.optString("deadline").trim()
        val deadlineEpoch = if (deadlineRaw.isBlank()) null else try {
            // Same MM/dd/yyyy convention as log_sleep's `date` param, device zone - see
            // PENDING_DATE_FORMAT's own doc comment.
            java.time.LocalDate.parse(deadlineRaw, PENDING_DATE_FORMAT)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            return result(false, "I couldn't read that deadline - try month/day/year, like 08/07/2026.")
        }

        return when (val outcome = GoalController.setGoal(
            context, aspect = aspect, statement = statement, targetValue = targetValue,
            unit = unit, metricKey = metricKey, deadlineEpoch = deadlineEpoch,
        )) {
            is GoalController.SetOutcome.Created -> result(true, "Goal set for ${aspectLabel(aspect)}: $statement.")
            is GoalController.SetOutcome.Revised -> result(
                true,
                "Updated the ${aspectLabel(aspect)} goal: $statement. Previous: \"${outcome.previous.statement}\".",
            )
            is GoalController.SetOutcome.Unchanged -> result(true, "That's already the current ${aspectLabel(aspect)} goal - nothing changed.")
        }
    }

    private suspend fun listGoalsTool(context: Context, args: JSONObject): JSONObject {
        val aspectRaw = args.optString("aspect").trim().lowercase()
        val goals = if (aspectRaw.isBlank()) {
            GoalController.allCurrentGoals(context)
        } else {
            if (aspectRaw !in GoalController.ASPECTS) return result(false, "Which aspect - body, notes, fleet, or money?")
            GoalController.currentGoals(context, aspectRaw)
        }
        val arr = JSONArray()
        for (g in goals) {
            val o = JSONObject()
                .put("aspect", g.aspect)
                .put("statement", g.statement)
                // Named explicitly so the model never has to infer "prose vs measurable" from the
                // mere presence of a JSON key - CLAUDE.md §4 rule 5's "say it in words" applied to
                // the tool's own output, not just user-facing strings.
                .put("measurable", g.targetValue != null)
            g.targetValue?.let { o.put("targetValue", it) }
            g.unit?.let { o.put("unit", it) }
            g.deadlineEpoch?.let { o.put("deadline", shortDate(it)) }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", goals.size).put("goals", arr)
    }

    private suspend fun closeGoalTool(context: Context, args: JSONObject): JSONObject {
        val aspect = args.optString("aspect").trim().lowercase()
        if (aspect !in GoalController.ASPECTS) return result(false, "Which aspect - body, notes, fleet, or money?")
        val query = args.optString("statement").trim()
        if (query.isBlank()) return result(false, "Which goal? Give me a few words from it.")
        val status = if (args.optString("status", "achieved").trim().lowercase() == "abandoned") "abandoned" else "achieved"

        return when (val outcome = GoalController.closeGoal(context, aspect, query, status)) {
            is GoalController.CloseOutcome.Closed -> result(true, "Closed ($status): ${outcome.goal.statement}.")
            GoalController.CloseOutcome.NotFound -> result(false, "I don't see an active ${aspectLabel(aspect)} goal matching that.")
            is GoalController.CloseOutcome.Ambiguous -> result(
                false,
                "More than one goal matches: ${outcome.matches.joinToString("; ") { it.statement }}. Which one?",
            )
        }
    }

    // --- Advisors (ticket 18) -------------------------------------------------------------------

    /** How long a stored proposal stays valid before `accept_proposal` refuses it and marks the
     * row `expired` - ticket 03 answer call 5's "24h is the starting number, the build may tune
     * it". `reasoned`, not measured: this build did not have grounds to pick a different number,
     * so it ships the ticket's own starting figure rather than inventing one. Deliberately NOT
     * conversation-scoped (the answer's literal "the conversation they were made in plus a short
     * TTL") - LiveToolbox is a stateless dispatch object with no notion of "this call's
     * conversation", so a flat wall-clock TTL off [com.kevin.legion.data.local.AdvisorAdvice
     * .createdAt] is the buildable approximation: never LESS permissive than the conversation-
     * scoped version (a same-conversation accept is always well under 24h), only more permissive
     * for a proposal accepted in a later conversation within the same day - a gap flagged here
     * rather than silently narrowed to something the ticket didn't ask for.
     */
    private const val PROPOSAL_TTL_MS = 24L * 60 * 60 * 1000

    /** `ask_advisor`. Resolves `aspect` to its [AdvisorBriefs] entry and runs one [AdvisorAgent]
     * exchange. The tool response always carries `adviceId` on a [AdvisorResult.Success] (even a
     * purely conversational answer with no proposal gets one - see [AdvisorAgent.outcomeFor]) so a
     * later `accept_proposal` call always has a real id to name, never a value the live model has
     * to invent or recall from the transcript. */
    private suspend fun askAdvisorTool(context: Context, args: JSONObject): JSONObject {
        val aspect = AdvisorAspect.fromKey(args.optString("aspect").trim().lowercase())
            ?: return result(false, "Which advisor - body, notes, fleet, money, or overall?")
        val question = args.optString("question").trim()
        if (question.isBlank()) return result(false, "What's the question?")

        if (!GeminiKeyProvider.hasKey()) {
            return result(false, "I need a Gemini key to do that - add your own in Setup to keep going.")
        }

        val brief = AdvisorBriefs.forAspect(aspect)
        return mapAdvisorResult(AdvisorAgent().ask(context, brief, question))
    }

    /**
     * Maps one [AdvisorResult] to the tool-response JSON `ask_advisor` hands back to the live
     * model. Split out from [askAdvisorTool] itself (no `Context`, no network) so a unit test can
     * hit every branch - most importantly [AdvisorResult.ParseFailed] - directly, `internal` for
     * that reason.
     *
     * Ticket 18's "known weakness", narrowed by ticket 21 but not eliminated: `AdvisorAgent` now
     * hands `SubAgent.askTyped` a real `responseSchema` (machine-enforced), on top of
     * [com.kevin.legion.advisor.AdvisorAnswer.RESPONSE_SCHEMA]'s prose copy in the system
     * instruction (belt and braces - see that constant's doc comment for why both stay). A schema
     * makes malformed output less likely, not impossible, so the model's reply can still fail to
     * parse into [com.kevin.legion.advisor.AdvisorAnswer]'s shape. **The prose is still good
     * coaching - it is the JSON envelope that failed** - so [AdvisorResult.ParseFailed] carries the
     * raw text and this branch RELAYS it, with a plain caveat that there is no concrete proposal
     * behind it. Discarding the words because their wrapper was malformed would turn a formatting
     * problem into a silent loss of the advice itself. What is genuinely lost is only the
     * structured half: no proposal to accept, and no per-figure `basis` tags - which is why the
     * caveat says so rather than implying a normal answer.
     */
    internal fun mapAdvisorResult(outcome: AdvisorResult): JSONObject = when (outcome) {
        is AdvisorResult.Success -> {
            KeyHealth.noteOk()
            result(true, outcome.answer.spoken).put("adviceId", outcome.adviceId).apply {
                if (outcome.answer.proposal != null) put("hasProposal", true)
            }
        }
        AdvisorResult.RateLimited -> {
            KeyHealth.noteRateLimited()
            result(false, "The Gemini key just hit its rate limit - give it a minute and ask me again.")
        }
        AdvisorResult.KeyInvalid -> {
            KeyHealth.noteInvalid()
            result(false, "Something's wrong with the Gemini key - worth checking it in Setup when you're parked.")
        }
        AdvisorResult.Overloaded -> result(false, "The advisor's overloaded right now - try again in a sec.")
        AdvisorResult.Offline -> result(false, "No data signal out here - ask me again when we're back in coverage.")
        AdvisorResult.Failed -> result(false, "I couldn't reach the advisor just now - try again in a sec.")
        is AdvisorResult.ParseFailed -> {
            KeyHealth.noteOk()
            val prose = outcome.rawText.trim()
            if (prose.isEmpty()) {
                result(
                    true,
                    "I've got some thoughts but couldn't put together a clean, concrete answer " +
                        "from them just now - ask me again if you'd like another pass.",
                )
            } else {
                result(
                    true,
                    prose + "\n\n(Say this as advice only - I couldn't put a concrete proposal " +
                        "together from it, so there's nothing to accept yet.)",
                )
            }
        }
    }

    /** `accept_proposal`. Reads the STORED `advisor_advice` row by id and executes its
     * `proposalJson` VERBATIM via [AdvisorProposalExecutor] - the live model supplies only the id,
     * never a value, so nothing can drift between what was read aloud and what lands (ticket 03
     * answer call 1). Refuses, in words, for: an unknown id, a row that already left `pending`
     * (already accepted/rejected/expired - nothing to do twice), a row older than
     * [PROPOSAL_TTL_MS] (marked `expired` here, not left silently stale), an op outside that
     * aspect's own [com.kevin.legion.advisor.AdvisorBrief.writableOps] (never marked - a proposal
     * that was never writable can't become writable by asking again with the same id, so there is
     * nothing to invalidate), and a row someone else already claimed (see the atomic claim below).
     *
     * **Claim before executing, settle after.** The read-then-check above is NOT the mutual-exclusion
     * point - two concurrent calls for the same id (a double-tap, or a model retry racing the
     * original past `TOOL_TIMEOUT_MS` while `handleToolCall`'s orphaned coroutine keeps running) can
     * both pass every check above and both reach here. [AdvisorAdviceDao.claimIfPending] is an
     * `UPDATE ... WHERE outcome = 'pending'`, the actual atomic gate: only one caller's claim can
     * flip the row, so only one caller ever executes. The loser's `claimed == 0` is reported honestly
     * as already-actioned, never silently retried. A claimed row settles to `accepted` on a verified
     * [AdvisorProposalExecutor.ExecuteResult.Ok], or back to `pending` via
     * [AdvisorAdviceDao.revertToPending] on [AdvisorProposalExecutor.ExecuteResult.Refused] /
     * [AdvisorProposalExecutor.ExecuteResult.WriteFailed] so the SAME id remains retryable - a
     * verified-not-written proposal must never read as `accepted` (that row could then never be
     * retried, since it no longer satisfies `outcome == "pending"`, and the advice log would show a
     * failed write as a success forever). */
    private suspend fun acceptProposalTool(context: Context, args: JSONObject): JSONObject {
        if (!args.has("id")) return result(false, "Which proposal? I need its id.")
        val id = args.optLong("id", -1L)
        if (id < 0) return result(false, "Which proposal? I need its id.")

        val db = CarDatabase.getDatabase(context)
        val advice = db.advisorAdviceDao().pending(id)
            ?: return result(false, "I don't have a proposal like that on file.")

        if (advice.outcome != "pending" || advice.proposalJson == null) {
            return result(false, "That one's already ${advice.outcome} - there's nothing left to accept.")
        }

        val age = System.currentTimeMillis() - advice.createdAt
        if (age > PROPOSAL_TTL_MS) {
            db.advisorAdviceDao().markOutcome(id, "expired", System.currentTimeMillis())
            return result(
                false,
                "That was from ${shortDate(advice.createdAt)} - it's aged out, let me re-check and propose it fresh.",
            )
        }

        val aspect = AdvisorAspect.fromKey(advice.aspect)
            ?: return result(false, "I don't recognise which advisor that proposal came from.")
        val brief = AdvisorBriefs.forAspect(aspect)

        val claimed = db.advisorAdviceDao().claimIfPending(id, "accepting", System.currentTimeMillis())
        if (claimed == 0) {
            return result(false, "That one's already being actioned - nothing left for me to do.")
        }

        return when (val outcome = AdvisorProposalExecutor.execute(context, brief, advice.proposalJson)) {
            is AdvisorProposalExecutor.ExecuteResult.Ok -> {
                db.advisorAdviceDao().markOutcome(id, "accepted", System.currentTimeMillis())
                result(true, outcome.message)
            }
            is AdvisorProposalExecutor.ExecuteResult.Refused -> {
                db.advisorAdviceDao().revertToPending(id)
                result(false, outcome.message)
            }
            is AdvisorProposalExecutor.ExecuteResult.WriteFailed -> {
                db.advisorAdviceDao().revertToPending(id)
                result(false, outcome.message)
            }
        }
    }

    private suspend fun getMealGap(context: Context): JSONObject {
        return when (val gap = MealController.dayGap(context)) {
            is com.kevin.legion.meals.DailyMealGap.NotLogged ->
                JSONObject().put("success", true).put("logged", false)
                    .put("message", "Nothing logged today - not zero, just not logged yet.")
            is com.kevin.legion.meals.DailyMealGap.Logged -> JSONObject()
                .put("success", true)
                .put("logged", true)
                .put("caloriesTarget", gap.gap.target.caloriesKcal)
                .put("caloriesActual", gap.gap.actual.caloriesKcal)
                .put("caloriesRemaining", gap.gap.gap.caloriesKcal)
                .put("proteinTargetG", gap.gap.target.proteinG)
                .put("proteinActualG", gap.gap.actual.proteinG)
                .put("carbsTargetG", gap.gap.target.carbsG)
                .put("carbsActualG", gap.gap.actual.carbsG)
                .put("fatTargetG", gap.gap.target.fatG)
                .put("fatActualG", gap.gap.actual.fatG)
                .put("reported", gap.gap.tier == com.kevin.legion.plan.TrustTier.REPORTED)
        }
    }

    /** Recent meal logs - macro fields are estimates, see the tool's own description. */
    private suspend fun listRecentMeals(context: Context, count: Int): JSONObject {
        val meals = MealController.recentMeals(context, count.coerceIn(1, 100))
        val arr = JSONArray()
        for (m in meals) {
            val o = JSONObject().put("description", m.description)
            m.caloriesKcal?.let { o.put("estimatedCaloriesKcal", it) }
            m.proteinG?.let { o.put("estimatedProteinG", it) }
            m.carbsG?.let { o.put("estimatedCarbsG", it) }
            m.fatG?.let { o.put("estimatedFatG", it) }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", meals.size).put("meals", arr)
    }

    /**
     * Ticket 11 D36 (extended to sleep 2026-08-07): finds the single most recent row across the
     * four tables [log_workout_set], [log_bodyweight], [log_meal], and [log_sleep] can write,
     * deletes only that one, and says what it removed. This is the whole-domain "pick" step
     * [com.kevin.legion.workouts.WorkoutController]'s, [com.kevin.legion.meals.MealController]'s,
     * and [com.kevin.legion.sleep.SleepController]'s own doc comments defer to here, since no one
     * controller can see another's table. **Body logs only** - ledger's voice-logged pending
     * transactions stay exclusively on `clear_pending_transaction` and must NEVER join this list,
     * because undoing money is a different blast radius from undoing a meal (see this tool's own
     * declaration doc comment).
     */
    private suspend fun undoLastLog(context: Context): JSONObject {
        val lastSet = WorkoutController.mostRecentSetLog(context)
        val lastWeight = WorkoutController.mostRecentBodyweightLog(context)
        val lastMeal = MealController.mostRecentMealLog(context)
        val lastSleep = com.kevin.legion.sleep.SleepController.mostRecentSleepLog(context)

        val candidates = listOfNotNull(
            lastSet?.let { it.loggedAt to "set" },
            lastWeight?.let { it.loggedAt to "weight" },
            lastMeal?.let { it.loggedAt to "meal" },
            lastSleep?.let { it.loggedAt to "sleep" },
        )
        if (candidates.isEmpty()) {
            return result(success = false, message = "Nothing to undo - no workout, bodyweight, meal, or sleep logged yet.")
        }
        val mostRecentKind = candidates.maxByOrNull { it.first }!!.second
        val message = when (mostRecentKind) {
            "set" -> WorkoutController.deleteSetLog(context, lastSet!!)
            "weight" -> WorkoutController.deleteBodyweightLog(context, lastWeight!!)
            "sleep" -> com.kevin.legion.sleep.SleepController.deleteSleepLog(context, lastSleep!!)
            else -> MealController.deleteMealLog(context, lastMeal!!)
        }
        return result(success = true, message = message)
    }

    /**
     * Dispatches `log_sleep`. `date` reuses [PENDING_DATE_FORMAT] (MM/dd/yyyy) - the same spoken
     * date convention `log_pending_transaction` already uses, so the model doesn't have to learn a
     * second date shape for a second domain.
     */
    private suspend fun logSleep(context: Context, args: JSONObject): JSONObject {
        val dateRaw = args.optString("date").trim()
        val sleepDateOverride = if (dateRaw.isBlank()) null else try {
            // Device zone, because SleepLog.sleepDate is a DAY KEY cut by meals.dayStartEpoch and
            // the two must agree or a spoken override lands in the neighbouring day's window.
            java.time.LocalDate.parse(dateRaw, PENDING_DATE_FORMAT)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            return result(false, "I couldn't read that date - try month/day/year, like 08/07/2026.")
        }
        val quality = if (args.has("quality") && !args.isNull("quality")) args.optInt("quality") else null
        val notes = args.optString("notes").trim().takeIf { it.isNotBlank() }
        return result(
            success = true,
            message = com.kevin.legion.sleep.SleepController.logSleep(
                context = context,
                durationHours = args.optDouble("duration_hours"),
                quality = quality,
                notes = notes,
                sleepDateOverride = sleepDateOverride,
            ),
        )
    }

    /** Tonight's (today's wake-date's) sleep gap, worded for the model to speak. */
    private suspend fun getSleepGap(context: Context): JSONObject {
        return when (val gap = com.kevin.legion.sleep.SleepController.gapFor(context)) {
            is com.kevin.legion.sleep.SleepGap.NotLogged ->
                JSONObject().put("success", true).put("logged", false)
                    .put("message", "Nothing logged for last night - not zero, just not logged yet (or no target set).")
            is com.kevin.legion.sleep.SleepGap.Logged -> JSONObject()
                .put("success", true)
                .put("logged", true)
                .put("targetMinutes", gap.gap.target)
                .put("actualMinutes", gap.gap.actual)
                .put("gapMinutes", gap.gap.gap)
                .put("reported", gap.gap.tier == com.kevin.legion.plan.TrustTier.REPORTED)
        }
    }

    /** Recent sleep logs. */
    private suspend fun listRecentSleep(context: Context, count: Int): JSONObject {
        val logs = com.kevin.legion.sleep.SleepController.recentSleep(context, count.coerceIn(1, 100))
        val arr = JSONArray()
        for (l in logs) {
            val o = JSONObject()
                .put("date", documentDate(l.sleepDate))
                .put("durationMinutes", l.durationMinutes)
            l.quality?.let { o.put("quality", it) }
            l.notes?.let { o.put("notes", it) }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", logs.size).put("nights", arr)
    }

    /**
     * Dispatches the activate_garage voice tool: a Context/JSONObject-thin
     * wrapper around [GarageController.dispatchVoiceActivate], which owns the
     * actual confirm-gate + door-resolution logic (unit-tested directly in
     * GarageLogicTest, no Context needed there).
     */
    private suspend fun activateGarage(context: Context, args: JSONObject): JSONObject {
        val doorName = args.optString("door").takeIf { it.isNotBlank() }
        val confirmed = args.optBoolean("confirmed", false)
        val doors = GarageController.configuredDoors(context)
        val defaultId = GaragePreferences.defaultDoorId(context)
        val r = GarageController.dispatchVoiceActivate(doors, defaultId, doorName, confirmed) { door ->
            GarageController.activate(context, door)
        }
        return result(r.success, r.message)
    }

    /**
     * Dispatches the clear_codes voice tool - a thin Context/JSONObject wrapper around
     * [DtcClearController.dispatchAndRecord], which owns the whole snapshot/send/re-read/record
     * transaction and the confirm gate (unit-tested directly, no Context needed there - see
     * DtcClearControllerTest). `success` follows [GarageController]'s own convention for a
     * destructive write tool: true only when the physical action actually completed as asked
     * (CLEARED), false for every other outcome including the confirm-prompt turn itself - the
     * driver-facing wording is always [message], never inferred from this flag.
     */
    private suspend fun clearCodes(context: Context, args: JSONObject): JSONObject {
        val confirmed = args.optBoolean("confirmed", false)
        if (!ObdBluetoothManager.isConnected) {
            return result(false, "The OBD adapter isn't connected, so I can't clear anything.")
        }
        val vehicle = VehicleController.currentVehicle(context)
        val r = DtcClearController.dispatchAndRecord(context, vehicle, confirmed)
        return result(success = r.outcome == DtcClearController.ClearOutcome.CLEARED, message = r.message)
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

    /** How long a voice-created Google Calendar appointment with a specific time (not all-day)
     * lasts when the driver never states a duration - ticket 14's `manage_item` doesn't expose an
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
        return runCatching { java.time.LocalDate.parse(s, NOTE_DATE_FORMAT) }.getOrNull()
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

    /** Dispatches `manage_item`: add / tick / untick / remove / schedule / skip one item. */
    private suspend fun manageItem(context: Context, args: JSONObject): JSONObject {
        val action = args.optString("action").trim().lowercase()
        val itemArg = args.optString("item").trim()
        val list = theList(context)

        if (action == "add") {
            if (itemArg.isBlank()) return result(false, "What should I add?")

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

            if (kind is ScheduleIntentResolver.Kind.Appointment) {
                // A calendar event needs SOMETHING to put on the calendar - CalendarContract's
                // DTSTART is a hard requirement (research doc §1.3), and asking "when" here is
                // cheaper and more honest than inventing a date.
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
            )
        }

        if (itemArg.isBlank()) return result(false, "Which item?")
        return when (val match = NotesController.findItem(context, list.id, itemArg)) {
            is ItemMatch.NoMatch -> result(false, "I don't see \"$itemArg\" on your list - add it?")
            is ItemMatch.Ambiguous -> result(
                false, "Which one? " + match.candidates.joinToString(", ") { it.text },
            )
            is ItemMatch.Resolved -> dispatchItemAction(context, action, list, match.item, args)
        }
    }

    /**
     * `manage_item`'s `add` action once [ScheduleIntentResolver] has decided the item is an
     * [ScheduleIntentResolver.Kind.Appointment] (ticket 14). Writes straight to
     * [CalendarProvider.insertEvent] - **never** to [NotesController], except as the explicit,
     * spoken-about fallback below. Nothing about a successful write is stored locally afterward
     * (ticket 04 point 5): the agenda re-reads the provider at render time, so there is no id to
     * keep, no row to reconcile, nothing to lose.
     *
     * [date]/[time] are already-parsed local values; [timeArg] is the raw `HH:mm` string purely so
     * the confirmation sentence echoes back exactly what the driver said rather than a reformatted
     * version of it (matching [scheduleItem]'s own reply style elsewhere in this file).
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

        // Device-zone instant, matching every other reminder date this file composes - used ONLY
        // for the fallback-to-reminder path below, never for the calendar write itself (which needs
        // the platform's own all-day convention - see the branch beneath this one).
        val fallbackStartsAt = java.time.LocalDateTime.of(date, time ?: java.time.LocalTime.MIDNIGHT)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Prefer Kevin's own (primary) calendar; fall back to whatever other writable com.google
        // calendar exists rather than refusing outright - research doc §1.3's IS_PRIMARY
        // preference, loosened because "no primary, but a secondary writable calendar exists" is a
        // real device state this should still serve.
        val calendars = CalendarProvider.writableGoogleCalendars(context)
        val calendar = calendars.firstOrNull { it.isPrimary } ?: calendars.firstOrNull()
        if (calendar == null) {
            // No writable com.google calendar reachable - WRITE_CALENDAR refused, no Google
            // account synced, or Calendar sync toggled off (research doc §1.5). Falling back to a
            // local reminder SILENTLY would bury the fact that this was asked to be an appointment
            // - "Alfred always says which he did" (ticket 14) applies to this failure path too, not
            // only the success one.
            val added = NotesController.addItemDue(context, list.id, itemArg, fallbackStartsAt, allDay = allDay)
            return result(
                true,
                "I can't reach your Google calendar right now, so I've set \"${added.text}\" as a " +
                    "reminder instead, for $whenPhrase. Grant Calendar access in the app to let me " +
                    "put appointments on it directly.",
            )
        }

        // Android's own all-day convention (Calendar Provider guide, research doc §5): UTC midnight
        // of the calendar date, EVENT_TIMEZONE "UTC", DTEND one day later - NOT the device-zone
        // instant [fallbackStartsAt] uses for a local reminder's `startsAt`. See
        // CalendarProvider.insertEvent's own doc comment for why these two zones must never be
        // confused with each other.
        val (startMs, endMs) = if (allDay) {
            val startMs = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            startMs to startMs + java.time.Duration.ofDays(1).toMillis()
        } else {
            val startMs = java.time.LocalDateTime.of(date, time)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            startMs to startMs + CALENDAR_EVENT_DEFAULT_DURATION_MS
        }

        // Never CALLER_IS_SYNCADAPTER - see CalendarProvider.insertEvent's doc comment for why that
        // is the one way this would silently never reach Google's servers.
        val eventId = CalendarProvider.insertEvent(context, calendar.id, itemArg, startMs, endMs, allDay)
        if (eventId == null) {
            // Permission or provider failure between the check above and the actual insert (a
            // narrow window, but not a zero one) - same honest fallback as the no-calendar case.
            val added = NotesController.addItemDue(context, list.id, itemArg, fallbackStartsAt, allDay = allDay)
            return result(
                true,
                "I couldn't write to your Google calendar, so I've set \"${added.text}\" as a " +
                    "reminder instead, for $whenPhrase. Grant Calendar access in the app to let me " +
                    "put appointments on it directly.",
            )
        }

        return result(
            true,
            ScheduleIntentResolver.confirmationPhrase(ScheduleIntentResolver.Kind.Appointment, itemArg, whenPhrase),
        )
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
        "untick" -> {
            NotesController.untick(context, item)
            result(true, "Unticked \"${item.text}\" on ${list.name}.")
        }
        // No confirmation on removing one item (ticket 05) - unlike deleting a whole list.
        "remove" -> {
            NotesController.removeItem(context, item)
            result(true, "Took \"${item.text}\" off ${list.name}.")
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
     * handed).
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
        }

        val repeatKindRaw = args.optString("repeat_kind").trim().lowercase()
        if (repeatKindRaw.isNotBlank()) {
            if (repeatKindRaw == "none") {
                current = NotesController.setRepeat(context, current, null, RepeatEnd.Never)
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
            }
        }

        // Exact alarms (ticket 03) - only ever requested explicitly, never implied. A downgrade
        // (permission refused) is said in the reply, not just persisted, per ticket 03's "downgrade
        // to inexact and say so in words - never fail quietly".
        var downgradeNotice = ""
        if (args.has("exact")) {
            val wantsExact = args.optBoolean("exact")
            current = NotesController.setExact(context, current, wantsExact)
            if (wantsExact && current.exactDowngraded) {
                downgradeNotice = " I can't get exact-alarm permission right now, so it'll fire " +
                    "approximately on time instead - you can grant it in Settings."
            }
        }

        return result(true, "Scheduled \"${current.text}\" on ${list.name}.$downgradeNotice")
    }

    /**
     * Dispatches `read_vehicle_sensor`: list a car's capabilities, or read one sensor live.
     *
     * Deliberately NOT in [CATEGORY_A_TOOLS]. The capability half is a database read that must work
     * with no dongle present, and blanket-refusing the whole tool when nothing is connected would
     * hide the one answer that never needed hardware - the same mistake `get_code_history` was added
     * to fix. The live-read half checks the connection itself and says so in words.
     */
    private suspend fun readVehicleSensor(context: Context, args: JSONObject): JSONObject =
        withResolvedVehicle(context, args) { vehicle ->
            val storedPids = CarDatabase.getDatabase(context).vehicleCapabilityDao()
                .pidsForVehicle(vehicle.obdMac).toSet()
            // Prefer the LIVE bitmask when this is the connected car - it is fresher than the stored
            // profile and covers a first-ever connect where nothing has been persisted yet.
            val live = ObdBluetoothManager.supportedPids.value
            // Compared against ActiveVehicle.current for the same reason persistCapabilities uses it:
            // a hand-added car's id is synthetic, so comparing the dongle's MAC to vehicle.obdMac
            // would read as "not this car" for every vehicle Kevin added by hand.
            val connectedToThisCar = ObdBluetoothManager.isConnected &&
                ActiveVehicle.current(context) == vehicle.obdMac
            val supported = if (connectedToThisCar && live.isNotEmpty()) live else storedPids

            if (supported.isEmpty()) {
                return@withResolvedVehicle result(
                    false,
                    // Ticket 04's label rule: the one rule, every surface - see
                    // VehicleController.label's own doc. This whole function used raw Vehicle.name
                    // throughout (the ticket's "capability-probe replies" surface).
                    "I haven't profiled ${VehicleController.label(vehicle)} yet - plug the adapter into it once and I'll " +
                        "record what it can report.",
                )
            }

            val caps = capabilitiesFor(supported)
            val sensorArg = args.optString("sensor").trim()

            // No sensor named: report the capability profile. Works parked and unplugged.
            if (sensorArg.isBlank()) {
                val byGroup = JSONObject()
                for ((group, specs) in caps.readable.groupBy { it.group }) {
                    byGroup.put(group.name.lowercase(), JSONArray(specs.map { it.key }))
                }
                return@withResolvedVehicle JSONObject()
                    .put("success", true)
                    .put("vehicle", VehicleController.label(vehicle))
                    .put("readable_count", caps.readable.size)
                    .put("readable_by_group", byGroup)
                    // Surfaced, never hidden: "supported but we don't decode it yet" is a real and
                    // useful state, and reporting zero of them would overstate our coverage.
                    .put("supported_but_not_yet_decoded", JSONArray(caps.undecodedPids.map { it.key }))
                    .put("unrecognised_pid_count", caps.unknownPids.size)
                    .put("profile_source", if (connectedToThisCar) "live" else "stored from a previous connection")
            }

            val matches = matchPid(sensorArg, caps.readable)
            when {
                matches.isEmpty() -> {
                    // Distinguish "this car can't" from "I don't know that word" - they need
                    // different answers and merging them would misinform.
                    val elsewhere = matchPid(sensorArg, PID_REGISTRY.filter { it.readable })
                    if (elsewhere.isNotEmpty()) {
                        result(false, "${VehicleController.label(vehicle)} doesn't report ${elsewhere.first().description.lowercase()} - " +
                            "it's not in the set of sensors that car answers.")
                    } else {
                        result(false, "I don't know a sensor called \"$sensorArg\".")
                    }
                }
                matches.size > 1 -> result(
                    false,
                    "Which one? " + matches.joinToString(", ") { it.description },
                )
                else -> {
                    val spec = matches.first()
                    if (!connectedToThisCar) {
                        return@withResolvedVehicle result(
                            false,
                            "${VehicleController.label(vehicle)} does report ${spec.description.lowercase()}, but I need the " +
                                "adapter plugged into it to read the current value.",
                        )
                    }
                    val value = ObdBluetoothManager.readPid(spec)
                        ?: return@withResolvedVehicle result(
                            false, "${VehicleController.label(vehicle)} didn't answer for ${spec.label} just now.",
                        )
                    JSONObject()
                        .put("success", true)
                        .put("vehicle", VehicleController.label(vehicle))
                        .put("sensor", spec.key)
                        .put("description", spec.description)
                        .put("value", (value * 100).roundToInt() / 100.0)
                        .put("unit", spec.unit)
                }
            }
        }

    /** Dispatches `manage_grocery`: add / tick / untick / remove / read / suggest / finish. */
    private suspend fun manageGrocery(context: Context, args: JSONObject): JSONObject {
        val action = args.optString("action").trim().lowercase()
        val itemArg = args.optString("item").trim()

        when (action) {
            "add" -> {
                if (itemArg.isBlank()) return result(false, "What should I add to the shopping list?")
                val added = GroceryController.addItem(context, itemArg)
                val left = GroceryController.items(context).count { !it.done }
                return result(true, "Added \"${added.text}\" to the shopping list - $left to get.")
            }

            "read" -> {
                val items = GroceryController.items(context)
                if (items.isEmpty()) return result(true, "The shopping list is empty - no trip on right now.")
                val arr = JSONArray()
                for (row in buildGroceryRows(items)) {
                    arr.put(JSONObject().put("text", row.text).put("in_basket", row.done))
                }
                return JSONObject()
                    .put("success", true)
                    .put("count", items.size)
                    .put("remaining", items.count { !it.done })
                    .put("items", arr)
            }

            "suggest" -> {
                val staples = GroceryController.suggestions(context)
                if (staples.isEmpty()) {
                    return result(true, "Nothing learned yet - I'll start remembering what gets bought after the first finished trip.")
                }
                val arr = JSONArray()
                for (st in staples) {
                    arr.put(JSONObject().put("item", st.displayName).put("times_bought", st.timesBought))
                }
                return JSONObject()
                    .put("success", true)
                    .put("count", staples.size)
                    .put("suggestions", arr)
                    // Said in words so the model does not read a frequency count as a claim that
                    // the driver needs the thing right now. It is history, not a prediction.
                    .put("note", "These are things bought often on past trips, not a claim anything is needed now.")
            }

            "finish" -> {
                val items = GroceryController.items(context)
                if (items.isEmpty()) return result(true, "There's no shopping trip on at the moment.")
                val bought = items.count { it.done }
                val skipped = items.size - bought
                // Destructive: confirm ALWAYS, and name what gets thrown away, not just what is
                // kept - the same two-step shape manage_list's delete used before it was retired.
                if (!args.optBoolean("confirmed", false)) {
                    return result(
                        true,
                        if (skipped > 0) {
                            "Finishing clears the whole list. $bought ticked will be remembered; " +
                                "$skipped you never ticked will be dropped and not remembered. Say yes to confirm."
                        } else {
                            "Finishing clears the list and remembers all $bought item(s). Say yes to confirm."
                        },
                    )
                }
                val summary = GroceryController.completeTrip(context)
                return result(
                    true,
                    if (summary.skipped > 0) {
                        "Trip done - ${summary.bought} bought and remembered, ${summary.skipped} dropped unticked."
                    } else {
                        "Trip done - all ${summary.bought} bought and remembered."
                    },
                )
            }
        }

        // Everything below addresses an EXISTING item, so it needs a match first.
        if (itemArg.isBlank()) return result(false, "Which item on the shopping list?")
        return when (val match = GroceryController.findItem(context, itemArg)) {
            is GroceryMatch.NoMatch -> result(false, "I don't see \"$itemArg\" on the shopping list - add it?")
            is GroceryMatch.Ambiguous -> result(
                false, "Which one? " + match.candidates.joinToString(", ") { it.text },
            )
            is GroceryMatch.Resolved -> when (action) {
                "tick" -> {
                    GroceryController.tick(context, match.item)
                    val left = GroceryController.items(context).count { !it.done }
                    result(true, "Got \"${match.item.text}\" - $left left.")
                }
                "untick" -> {
                    GroceryController.untick(context, match.item)
                    result(true, "Put \"${match.item.text}\" back on the list.")
                }
                "remove" -> {
                    GroceryController.removeItem(context, match.item)
                    result(true, "Took \"${match.item.text}\" off the shopping list.")
                }
                else -> result(false, "I don't know how to do that with the shopping list.")
            }
        }
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
        return JSONObject()
            .put("success", true)
            .put("count", rows.size)
            .put("open_count", rows.count { !it.done })
            .put("items", arr)
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
     * code just shows with no description rather than blocking on a Gemini
     * call for a quick factual "what's the codes" ask.
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
     */
    private fun getCurrentTime(): JSONObject {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a"))
        return result(true, "$stamp (${zone.id}, the phone's own timezone).")
    }

    private suspend fun getCodes(context: Context): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return result(false, "The OBD adapter isn't connected, so I can't read any codes.")
        }
        val codes = ObdBluetoothManager.getDtcCodes()
        if (codes.isEmpty()) {
            return result(true, "No codes stored - everything's clear.")
        }

        val info = withContext(Dispatchers.IO) {
            DtcDescriptions.loadSeed(context) + DtcDescriptions.loadLearned(context)
        }
        GlanceCardController.show(
            GlanceCardPayload(
                shape = GlanceShape.LIST,
                title = "Codes",
                rows = codes.map { code -> GlanceRow(code, info[code]?.first ?: "Not yet described") },
                sourceTool = "get_codes",
            )
        )

        val spoken = codes.joinToString("; ") { code ->
            val name = info[code]?.first
            if (name != null) "$code, $name" else code
        }
        return result(true, "${codes.size} code(s) stored: $spoken.")
    }

    private suspend fun diagnoseCodes(context: Context, args: JSONObject): JSONObject {
        val question = args.optString("question")
        val named = args.optString("codes")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }
        val codes = when {
            named.isNotEmpty() -> named
            ObdBluetoothManager.isConnected -> ObdBluetoothManager.getDtcCodes()
            else -> emptyList()
        }

        if (codes.isEmpty()) {
            val why = if (ObdBluetoothManager.isConnected)
                "The port's connected but there are no stored codes right now - nothing on file to diagnose."
            else
                "The OBD adapter isn't connected, so I can't read any codes - plug it in, or tell me the code you're seeing."
            return result(success = false, message = why)
        }

        // Deliberately displayLabel, not VehicleController.label (ticket 04's label rule is about
        // naming a car TO the driver; this string is grounding text fed INTO a diagnostic
        // specialist's own reasoning, where the trim it drops can be mechanically relevant, e.g.
        // "330i" vs "330Ci ZHP" - a nickname-preferring label would tell the specialist less, not
        // just render differently).
        val label = VehicleController.displayLabel(VehicleController.currentVehicle(context))

        return agentResult("I couldn't reach the diagnostics specialist just now - try again in a sec.") {
            DiagnosticAgent.diagnose(context, label, codes, question)
        }
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
     * Defaults to `false` at every current call site (all five `ask_<domain>` dispatches, and
     * every other [agentResult] caller in this file, none of which is even domain-shaped for a
     * write). This is the honest, DOCUMENTED gap this fix leaves open: [dispatch] only ever sees
     * `args.optString("question")` - free prose - for an `ask_<domain>` call, and there is no
     * reliable, non-guessing way from that string alone to tell "log my three sets of squats" (a
     * write) from "what did I lift last week" (a read) - both are legitimate questions the SAME
     * dispatcher answers. Turning this on unconditionally for a domain that mixes reads and writes
     * (fleet/body/pantry) would refuse every legitimate read that happens not to need a mutating
     * tool; turning it on for a domain with NO mutating tools at all in its [DISPATCHED] list
     * (goals/mail) would refuse EVERY call, including the reads that are its entire job. Per the
     * brief's own critical constraint ("if you cannot cleanly tell write-shaped from read-shaped
     * requests, implement the weaker but still correct version"), this fix stops at making the
     * mechanism real, tested, and available - not at guessing driver intent from text. The
     * plumbing is what closes the actual reported defect class once a caller CAN tell (e.g. a
     * dispatcher schema later extended with an explicit intent argument the model itself declares,
     * the same way `accept_proposal` already requires an explicit id rather than trusting prose).
     */
    private suspend fun agentResult(
        failMessage: String,
        requireMutation: Boolean = false,
        call: suspend () -> AgentResult,
    ): JSONObject {
        if (!GeminiKeyProvider.hasKey()) {
            return result(
                false,
                "I need a Gemini key to do that - add your own in Setup to keep going.",
            )
        }
        return when (val r = call()) {
            is AgentResult.Success -> {
                KeyHealth.noteOk()
                successOrMutationRefusal(r.text, r.mutatingToolsCalled, requireMutation)
            }
            AgentResult.RateLimited -> {
                KeyHealth.noteRateLimited()
                result(false, "The Gemini key just hit its rate limit - give it a minute and ask me again.")
            }
            AgentResult.KeyInvalid -> {
                KeyHealth.noteInvalid()
                result(false, "Something's wrong with the Gemini key - worth checking it in Setup when you're parked.")
            }
            AgentResult.Offline ->
                result(false, "No data signal out here - ask me again when we're back in coverage.")
            AgentResult.Failed, AgentResult.Overloaded -> result(false, failMessage)
        }
    }

    /**
     * Delegates to the symptom-triage specialist ([SymptomAgent]). Reads whatever
     * live values are available off the port (so the worker can weigh them) and
     * hands them plus the symptom to the worker. Another mid-conversation
     * round-trip, so Zero typically says "let me think..." first.
     */
    private suspend fun triageSymptom(context: Context, args: JSONObject): JSONObject {
        val symptom = args.optString("symptom")
        if (symptom.isBlank()) return result(success = false,
            message = "Tell me what it's doing - a noise, a smell, how it's driving - and I'll work through it.")

        val vehicle = VehicleController.currentVehicle(context)
        // displayLabel, not label - specialist grounding text, same reasoning as diagnose_codes
        // above (ticket 04's label rule governs naming the car TO the driver, not what gets fed
        // into a reasoning specialist's own prompt).
        val label = VehicleController.displayLabel(vehicle)

        val readings = StringBuilder()
        var codes = emptyList<String>()
        if (ObdBluetoothManager.isConnected) {
            ObdBluetoothManager.getCoolantTemp()?.let { readings.append("coolant ${it}C (${it * 9 / 5 + 32}F); ") }
            ObdBluetoothManager.getRpm()?.let { readings.append("RPM $it; ") }
            ObdBluetoothManager.getBatteryVoltage()?.let { readings.append("battery ${"%.1f".format(it)}V; ") }
            codes = ObdBluetoothManager.getDtcCodes()
        }

        return agentResult("I couldn't reach the diagnostics specialist just now - try again in a sec.") {
            SymptomAgent.triage(context, label, readings.toString().trim().trimEnd(';'), codes, symptom)
        }
    }

    /**
     * Quick OBD health snapshot for "is the car okay?" / pre-trip checks. Returns
     * the raw readings plus any obvious concern flags for Zero to phrase; the
     * model interprets and speaks them in character.
     */
    private suspend fun getHealth(): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return JSONObject().put("connected", false)
                .put("note", "OBD adapter not connected; tell the driver to check it's plugged in.")
        }
        val coolant = ObdBluetoothManager.getCoolantTemp()
        val voltage = ObdBluetoothManager.getBatteryVoltage()
        val codes = ObdBluetoothManager.getDtcCodes()

        val concerns = JSONArray()
        if (voltage != null && voltage < LOW_VOLTAGE) concerns.put("Battery voltage is low (${"%.1f".format(voltage)}V) - it may be weak or not charging.")
        if (coolant != null && coolant >= HOT_COOLANT_C) concerns.put("Coolant is running hot (${coolant}C).")
        if (codes.isNotEmpty()) concerns.put("${codes.size} stored trouble code(s): ${codes.joinToString(", ")}.")
        val healthSummary = if (concerns.length() == 0) "Everything reads normal - no codes, voltage and temperature look fine." else null

        val glanceRows = buildList {
            if (voltage != null) add(GlanceRow("Battery", "${"%.1f".format(voltage)}V"))
            if (coolant != null) add(GlanceRow("Coolant", "${coolant}°C"))
            for (i in 0 until concerns.length()) add(GlanceRow("Note", concerns.getString(i)))
        }
        GlanceCardController.show(
            GlanceCardPayload(
                shape = GlanceShape.HEADLINE_LIST,
                title = "Health",
                headline = healthSummary ?: if (concerns.length() == 1) "1 concern" else "${concerns.length()} concerns",
                rows = glanceRows,
                sourceTool = "get_health",
            )
        )

        return JSONObject().put("connected", true).apply {
            if (voltage != null) put("batteryVolts", voltage)
            if (coolant != null) put("coolantC", coolant).put("coolantF", coolant * 9 / 5 + 32)
            put("storedCodeCount", codes.size)
            put("concerns", concerns)
            if (healthSummary != null) put("summary", healthSummary)
        }
    }

    private const val LOW_VOLTAGE = 12.0
    private const val HOT_COOLANT_C = 105

    /**
     * Trend summary over the recorded obd_samples history. The aggregation now
     * lives in [CarToolbelt.trendSummary] (one source of truth, shared with the
     * investigating sub-agents); this just wraps its text for the Live model.
     * [vehicleId] is the resolved fleet-wide-voice override (ticket 01) - null
     * means the active car.
     */
    private suspend fun getTrend(context: Context, args: JSONObject, vehicleId: String? = null): JSONObject {
        val text = CarToolbelt.trendSummary(context, args.optString("metric"), args.optInt("days", 30), vehicleId)
        // Preserve the pre-delegation success flag for the two soft-fail sentences.
        val ok = !text.startsWith("Unknown metric") && !text.startsWith("Not enough history")
        return result(ok, text)
    }

    /**
     * Measured fuel economy: current drive, lifetime, and recent per-drive
     * numbers. [vehicle] is the already-resolved car (ticket 01 fleet-wide
     * voice).
     *
     * `currentDriveMpg`/`lifetimeMpg` come from [TelemetryRecorder], which
     * ticket 01 §2 explicitly excludes from vehicle-override threading - it
     * writes/reads live telemetry for whichever car is ACTUALLY being driven,
     * never a named one. So when [vehicle] is the second, non-active car,
     * those two fields would silently be the ACTIVE car's numbers mislabeled
     * under the requested car - the exact mislabeled-answer failure ticket 01
     * §0 exists to remove. They're only included when [vehicle] IS the active
     * car; asking about the other car gets its recorded per-drive trips only
     * (correctly scoped, straight from `odb_samples`), with a note rather than
     * a silently-wrong figure.
     *
     * **Suppressed under [MpgTrust.SHOW_MPG] == false** (ticket 09,
     * `.scratch/drive-ui/issues/09-mpg-scale-bug.md`): every branch below this refusal is dead
     * code while the flag is off, kept intact rather than deleted so re-enabling is the flag alone.
     * The refusal never reaches [TelemetryRecorder] or the DB at all - there is nothing to hedge
     * partial data against, the model must simply not be handed a number to speak.
     */
    private suspend fun getMpg(context: Context, vehicle: Vehicle): JSONObject {
        if (!MpgTrust.SHOW_MPG) return result(false, MpgTrust.VOICE_REFUSAL)

        val trips = CarDatabase.getDatabase(context).odbSampleDao()
            .getLatest(vehicle.obdMac, "MPG_TRIP", 5)
        val isActiveCar = vehicle.obdMac == ActiveVehicle.current(context)
        val current = if (isActiveCar) TelemetryRecorder.currentDriveMpg() else null
        val lifetime = if (isActiveCar) TelemetryRecorder.lifetimeMpg(context) else null
        if (current == null && lifetime == null && trips.isEmpty()) {
            return result(false, "No fuel data yet - MPG needs the OBD link and some miles on the clock.")
        }

        val glanceRows = buildList {
            if (lifetime != null) add(GlanceRow("Lifetime avg", "%.1f".format(lifetime)))
            trips.forEachIndexed { i, sample ->
                // i is a zero-based index, so the label needs i+1 - "$i drives ago"
                // rendered the second row as "1 drives ago".
                add(GlanceRow(if (i == 0) "Last drive" else "${i + 1} drives ago", "%.1f".format(sample.value)))
            }
        }
        GlanceCardController.show(
            GlanceCardPayload(
                shape = GlanceShape.HEADLINE_LIST,
                title = "MPG",
                headline = current?.let { "%.1f mpg this drive".format(it) }
                    ?: lifetime?.let { "%.1f mpg lifetime avg".format(it) },
                rows = glanceRows,
                sourceTool = "get_mpg",
            )
        )

        return JSONObject().put("success", true).apply {
            if (current != null) put("currentDriveMpg", "%.1f".format(current))
            if (lifetime != null) put("lifetimeMpg", "%.1f".format(lifetime))
            if (trips.isNotEmpty()) {
                put("recentDrivesMpg", JSONArray(trips.map { "%.1f".format(it.value) }))
            }
            if (!isActiveCar) {
                put("note", "Live/lifetime MPG only tracks the car actually being driven right now - " +
                    "these are ${VehicleController.label(vehicle)}'s recorded per-drive figures only.")
            }
        }
    }

    /** Live emissions-readiness read - which monitors are done, which need drive time. */
    private suspend fun checkReadiness(): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return result(false, "Couldn't read readiness - is the OBD link up?")
        }
        val r = ObdBluetoothManager.getReadiness()
            ?: return result(false, "The car didn't answer the readiness request.")
        val incomplete = r.monitors.filter { !it.complete }.map { it.name }
        val summary = buildString {
            append(if (r.milOn) "Check-engine light is ON" else "Check-engine light is off")
            if (r.dtcCount > 0) append(" with ${r.dtcCount} stored code(s)")
            append(". ")
            append("${r.monitors.count { it.complete }} of ${r.monitors.size} monitors complete.")
            if (incomplete.isNotEmpty()) {
                append(" Not ready yet: ${incomplete.joinToString(", ")} - ")
                append("drive a normal mixed cycle before an inspection.")
            } else {
                append(" All ready - good to go for an inspection.")
            }
        }

        val headline = buildString {
            append(if (r.milOn) "Check engine ON" else "Check engine off")
            append(" · ${r.dtcCount} code(s)")
        }
        GlanceCardController.show(
            GlanceCardPayload(
                shape = GlanceShape.STATUS_GRID,
                title = "Readiness",
                headline = headline,
                cells = r.monitors.map { GlanceCell(it.name, it.complete) },
                sourceTool = "check_readiness",
            )
        )

        return result(true, summary)
    }

    /**
     * Hands the cold-start question to the investigating [ColdStartAgent], which
     * pulls the recorded bursts itself via the toolbelt. We keep only the cheap
     * "nothing captured yet" early-out. Extra round-trip - Zero typically says
     * "let me look..." first.
     */
    private suspend fun checkColdStart(context: Context): JSONObject {
        val vehicle = VehicleController.currentVehicle(context)
        // The `label` computed below (for ColdStartAgent) stays on displayLabel deliberately -
        // see diagnose_codes' comment above for why specialist grounding text is exempt from
        // ticket 04's label rule.
        val markers = CarDatabase.getDatabase(context).odbSampleDao()
            .getLatest(vehicle.obdMac, "COLD_START", 1)
        if (markers.isEmpty()) {
            return result(false, "No cold start captured yet - I watch the first minute after a cold morning start.")
        }
        val label = VehicleController.displayLabel(vehicle)
        return agentResult("Couldn't reach the cold-start analyst - try again in a moment.") {
            ColdStartAgent.analyze(context, label)
        }
    }

    /**
     * The spoken guess caveat, appended to a sentence naming [candidate] when
     * [VehicleController.ServiceCandidate.isGuess] is set (mission-control ticket 16,
     * `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`
     * - "the caveat carry ALOUD because a tag cannot be heard"). [sentenceNoPeriod] is the sentence's
     * own words WITHOUT its trailing period; this function supplies the period either way, so every
     * call site below reads naturally whether or not the caveat fires, rather than every branch
     * having to remember to punctuate both halves itself.
     *
     * Deliberately ONE generic phrase rather than distinguishing SEEDED from LOOKUP the way
     * [MaintenanceAgent.describeItem] does - [VehicleController.ServiceCandidate.isGuess] is a plain
     * boolean (same shape as [com.kevin.legion.ui.fleet.DueRowView.isGuess]), and this is read aloud
     * mid-sentence to someone driving, not a model-facing prompt with room for a full provenance
     * clause.
     */
    private fun withGuessCaveat(sentenceNoPeriod: String, candidate: VehicleController.ServiceCandidate): String =
        sentenceNoPeriod + if (candidate.isGuess) " - though that interval is LEGION's guess, not one you've confirmed." else "."

    /**
     * Instant, zero-token read of what's coming up next - pure DB arithmetic
     * over [VehicleController.nextService], no Gemini call, so it works with no
     * key and no network. Renders plain spoken sentences (this is read aloud),
     * covering the shapes [VehicleController.NextService] can take: the same
     * item leading both axes, two different items each leading one axis, a
     * single axis only, everything already due ([VehicleController.NextService
     * .allDue]), or nothing anchored at all - plus the unknown-count offer,
     * which is how the driver discovers the backfill walkthrough exists
     * without it ever being pushed on them (CLAUDE.md sec 9.1: no unprompted
     * engagement).
     *
     * A [VehicleController.NextService.byTime] candidate at exactly 0 days
     * remaining is phrased as due today rather than "about today out" - see
     * [VehicleController.formatRemaining]'s doc for why that value can't just
     * be dropped into the usual "about X out" template.
     *
     * **Every branch that names a candidate carries [withGuessCaveat]** (ticket 16, this being the
     * spoken surface ticket 06 explicitly required the disclosure carry aloud on). The `allDue` and
     * "nothing due" branches name no candidate at all, so they carry nothing to caveat.
     */
    private suspend fun getNextService(context: Context, vehicle: Vehicle): JSONObject {
        val next = VehicleController.nextService(context, vehicle)
            ?: return result(true, "Nothing on the schedule yet - register the car or log a service and I'll start tracking it.")

        val miles = VehicleController.ScheduleUnit.MILES
        val days = VehicleController.ScheduleUnit.DAYS

        // The same item can legitimately lead both axes ("3,000 miles or 6
        // months, whichever comes first") - phrase that as one thing rather
        // than repeating the name twice.
        val sameItem = next.byMiles != null && next.byTime != null &&
            next.byMiles.serviceName == next.byTime.serviceName

        val body = when {
            next.allDue -> "Everything I've got a record for is already due."
            next.odometerUnset -> buildString {
                append("I don't have an odometer reading yet, so I can't time anything by mileage. ")
                append(
                    next.byTime?.let {
                        if (it.remaining <= 0L) withGuessCaveat("${it.serviceName} is due today", it)
                        else withGuessCaveat("By time, ${it.serviceName} is next, about ${VehicleController.formatRemaining(it.remaining, days)} out", it)
                    } ?: "Nothing's coming up by time either yet."
                )
            }
            sameItem && next.byTime!!.remaining <= 0L -> withGuessCaveat("${next.byMiles!!.serviceName} is due today", next.byMiles)
            sameItem -> withGuessCaveat(
                "${next.byMiles!!.serviceName}, about ${VehicleController.formatRemaining(next.byMiles.remaining, miles)} or " +
                    "${VehicleController.formatRemaining(next.byTime!!.remaining, days)}, whichever comes first",
                next.byMiles,
            )
            next.byMiles != null && next.byTime != null -> buildString {
                append(withGuessCaveat("${next.byMiles.serviceName} is soonest by mileage, about ${VehicleController.formatRemaining(next.byMiles.remaining, miles)} out", next.byMiles))
                append(" ")
                append(
                    if (next.byTime.remaining <= 0L) withGuessCaveat("${next.byTime.serviceName} is due today", next.byTime)
                    else withGuessCaveat("${next.byTime.serviceName} is soonest by time, about ${VehicleController.formatRemaining(next.byTime.remaining, days)} out", next.byTime)
                )
            }
            next.byMiles != null -> withGuessCaveat("${next.byMiles.serviceName} is next, about ${VehicleController.formatRemaining(next.byMiles.remaining, miles)} out", next.byMiles)
            next.byTime != null -> {
                if (next.byTime.remaining <= 0L) withGuessCaveat("${next.byTime.serviceName} is due today", next.byTime)
                else withGuessCaveat("${next.byTime.serviceName} is next, about ${VehicleController.formatRemaining(next.byTime.remaining, days)} out", next.byTime)
            }
            else -> "Nothing's due or coming up soon that I can time yet."
        }

        val unknownNote = if (next.unknownCount > 0) {
            if (next.unknownCount == 1) {
                " 1 more I don't have a last-done for - say the word and I'll fill it in."
            } else {
                " ${next.unknownCount} more I don't have a last-done for - say the word and I'll fill them in."
            }
        } else ""

        // Ticket 10: "any mileage not taken from the driver's own last reading says so, every time
        // - spoken as well as rendered." The "due in N miles" figure above is itself downstream of
        // TelemetryRecorder's speed-integration estimate whenever next.byMiles is non-null (its
        // math is currentMileage minus a stored anchor), so it carries the same caveat the estimate
        // itself does - never spoken when byMiles is null (nothing miles-derived was said) or when
        // the mileage IS the driver's own confirmed reading (mileageCaveat returns null then).
        val mileageNote = if (next.byMiles != null) {
            VehicleController.mileageCaveat(vehicle)?.let { " Your mileage is $it." } ?: ""
        } else ""

        return result(true, body + unknownNote + mileageNote)
    }

    /**
     * Backfills a maintenance item from what the driver remembers - never a
     * fresh completion (that's [logServiceDirect]/`log_service`). The model is
     * told not to call this at all on "I don't know" (see the tool
     * description); this side just parses the date and reports whether an
     * actual anchor was given, degrading to a clear failure on a date it can't
     * read rather than silently writing nothing.
     *
     * A date that fails to parse drops ONLY the date, not the whole call
     * (B6): a driver giving both a mileage anchor AND a garbled date must not
     * lose the perfectly good mileage anchor just because the date half was
     * bad. This only fails outright when NOTHING usable survives.
     *
     * A date parsed as being in the FUTURE is rejected outright (B4) rather
     * than written: [VehicleController.isDue]'s `now - lastDoneDate` math
     * assumes the anchor is in the past, and a future anchor (a mis-heard
     * year, or "2027-01" meant as this year) goes negative there, silently
     * reading as freshly-serviced for years. This tool is explicitly for a
     * PAST service, so a future date can never be a real answer here.
     */
    private suspend fun logPastService(context: Context, args: JSONObject, vehicleId: String? = null): JSONObject {
        val service = args.optString("service")
        if (service.isBlank()) return result(success = false, message = "Which service?")

        val mileage = args.optInt("mileage", -1).takeIf { it > 0 }
        val milesAgo = args.optInt("miles_ago", -1).takeIf { it >= 0 }
        val neverDone = args.optBoolean("never_done", false)
        val dateRaw = args.optString("date", "")

        var dateError: String? = null
        val date = if (dateRaw.isNotBlank()) {
            val parsed = parseIsoDate(dateRaw)
            when {
                parsed == null -> {
                    dateError = "I couldn't read that date - try year-month-day, like 2024-03-14, " +
                        "or just 2024-03 if you don't remember the day."
                    null
                }
                parsed > System.currentTimeMillis() -> {
                    dateError = "That date's in the future, so I can't log it as done already - " +
                        "did you mean an earlier year?"
                    null
                }
                else -> parsed
            }
        } else null

        val hasAnchor = neverDone || mileage != null || milesAgo != null || date != null
        if (!hasAnchor) {
            // Nothing usable survived (either nothing was given, or the only
            // thing given was a bad/future date and there's no other anchor
            // to fall back on) - fail outright rather than writing a blank row.
            return result(success = false, message = dateError ?: "I need something to go on — a mileage, how long ago, a date, or that it's never been done.")
        }

        val outcome = VehicleController.logPastServiceDirect(context, service, mileage, milesAgo, date, neverDone, vehicleId)
        // A bad/future date is a separate soft warning appended to whatever the
        // write itself reported - it does not override outcome.success, which
        // is ticket 05's no-op guard and reflects whether the write actually landed.
        val finalMessage = if (dateError != null) "${outcome.message} $dateError" else outcome.message
        return result(success = outcome.success, message = finalMessage)
    }

    /**
     * Parses a bare ISO date the driver's backfill might give as either a full
     * day (`2024-03-14`) or just a year-month (`2024-03`, treated as the 1st).
     * Returns null rather than throwing on anything else - a mis-shaped date
     * from the model's function-call args must not crash tool dispatch.
     */
    private fun parseIsoDate(raw: String): Long? {
        val trimmed = raw.trim()
        return try {
            java.time.LocalDate.parse(trimmed)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.YearMonth.parse(trimmed).atDay(1)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Delegates to the investigating maintenance specialist ([MaintenanceAgent]).
     * Pre-seeds the schedule + current mileage; the worker pulls service history
     * and anything else it needs via the toolbelt. Another mid-conversation
     * round-trip, so Zero typically says "let me check..." first. [vehicle] is
     * the already-resolved car (ticket 01 fleet-wide voice).
     *
     * **Known gap, traced, not fixed by this ticket:** the pre-seeded label,
     * mileage, and schedule below correctly describe [vehicle] even when it's
     * NOT the active car. But [MaintenanceAgent.answer] also lets the model
     * pull FURTHER data mid-investigation via [CarToolbelt.forMaintenance]'s
     * belt (get_service_history, get_trend, get_oil_analyses, ...) - and that
     * belt was not given [vehicle]'s id (see [CarToolbelt.forMaintenance]'s
     * doc), so any such follow-up call still reads the ACTIVE car. A compound
     * "ask_maintenance" question about the second car that needs the
     * specialist to pull more than the pre-seeded schedule can therefore mix
     * the requested car's schedule with the active car's history/trend data.
     * Threading the override into `MaintenanceAgent`/its belt was outside
     * ticket 01 §2's explicit controller list - flagged in the ticket 01
     * build report as a follow-up, not silently left unmentioned.
     */
    private suspend fun askMaintenance(context: Context, args: JSONObject, vehicle: Vehicle): JSONObject {
        val question = args.optString("question")
        // displayLabel deliberately - specialist grounding text, see diagnose_codes' comment above.
        val label = VehicleController.displayLabel(vehicle)

        val items = CarDatabase.getDatabase(context).maintenanceItemDao().getForVehicle(vehicle.obdMac)
        // Ticket 10: the pre-seeded mileage carries its own bare/estimated-and-caveated label now
        // (VehicleController.mileageLabel), not a raw Int the agent's own prompt had to caption
        // "(estimated)" unconditionally - a confirmed reading with nothing accrued since renders
        // bare here too, matching every other surface.
        val mileageLabel = VehicleController.mileageLabel(vehicle)

        return agentResult("I couldn't reach the maintenance specialist just now - try again in a sec.") {
            MaintenanceAgent.answer(context, label, mileageLabel, items, question)
        }
    }

    private suspend fun getVehicleData(metric: String): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return JSONObject().put("connected", false)
                .put("note", "OBD adapter not connected; tell the driver to check it's plugged in.")
        }
        // "This car does not report fuel level" and "the read failed" are
        // different answers and the driver deserves the right one. supportedPids
        // comes off the car's own Mode-01 support bitmask at connect, so when it
        // is populated and lacks the PID, the absence is a FACT about the car
        // rather than a failure - most notably fuel level (0x2F), which a large
        // share of vehicles simply do not publish over OBD-II. An empty set means
        // the bitmask never came back, so nothing can be concluded and the read
        // is attempted anyway.
        fun unsupported(pid: Int): Boolean {
            val supported = ObdBluetoothManager.supportedPids.value
            return supported.isNotEmpty() && pid !in supported
        }

        fun reading(pid: Int?, value: Any?, extras: JSONObject.() -> Unit = {}): JSONObject =
            JSONObject().put("connected", true).apply {
                when {
                    pid != null && unsupported(pid) -> put("available", false)
                        .put("supportedByCar", false)
                        .put("note", "This car does not report that over OBD-II. Say so plainly - " +
                            "it is not a fault and not a failed read.")
                    value == null -> put("available", false)
                        .put("note", "The read came back empty. Do not report a value.")
                    else -> put("available", true).apply(extras)
                }
            }

        return when (metric) {
            "fuel_level" -> {
                val pct = ObdBluetoothManager.getFuelLevel()
                reading(0x2F, pct) { put("fuelLevelPercent", "%.0f".format(pct)) }
            }
            "coolant_temp" -> {
                val c = ObdBluetoothManager.getCoolantTemp()
                reading(0x05, c) { put("celsius", c).put("fahrenheit", c!! * 9 / 5 + 32) }
            }
            "rpm" -> {
                val rpm = ObdBluetoothManager.getRpm()
                reading(0x0C, rpm) { put("rpm", rpm) }
            }
            "speed" -> {
                val kmh = ObdBluetoothManager.getSpeedKmh()
                reading(0x0D, kmh) {
                    put("kmh", kmh).put("mph", (kmh!! * 0.621371).roundToInt())
                }
            }
            "battery_voltage" -> {
                // ATRV is answered by the adapter itself, not the ECU, so there is
                // no PID to check support for - it works with the engine off.
                val v = ObdBluetoothManager.getBatteryVoltage()
                reading(null, v) { put("volts", "%.1f".format(v)) }
            }
            "engine_load" -> {
                val load = ObdBluetoothManager.getEngineLoad()
                reading(0x04, load) { put("loadPercent", "%.0f".format(load)) }
            }
            "intake_air_temp" -> {
                val iat = ObdBluetoothManager.getIntakeAirTemp()
                reading(0x0F, iat) { put("celsius", iat).put("fahrenheit", iat!! * 9 / 5 + 32) }
            }
            "maf" -> {
                val maf = ObdBluetoothManager.getMaf()
                reading(0x10, maf) { put("gramsPerSecond", "%.1f".format(maf)) }
            }
            "short_fuel_trim" -> {
                val t = ObdBluetoothManager.getShortFuelTrim()
                reading(0x06, t) { put("percent", "%+.1f".format(t)) }
            }
            "long_fuel_trim" -> {
                val t = ObdBluetoothManager.getLongFuelTrim()
                reading(0x07, t) { put("percent", "%+.1f".format(t)) }
            }
            "trouble_codes" -> {
                val codes = ObdBluetoothManager.getDtcCodes()
                JSONObject().put("connected", true)
                    .put("codes", JSONArray(codes))
                    .put("count", codes.size)
            }
            else -> result(success = false, message = "Unknown metric: $metric")
        }
    }

    /**
     * Transport only (play/pause/next/previous), over TWO independent backends.
     *
     * [MusicController] (Android's media-session framework) is preferred because it
     * drives whatever is actually playing - the phone-BT relay, Spotify, anything
     * that publishes a session. But `MediaSessionManager.getActiveSessions` requires
     * the one-time notification-access grant, and without it every command silently
     * no-ops: the SecurityException is swallowed into an empty session list
     * ([MusicController.activeSessions]), so the transport call never happens and the
     * tool just reports failure.
     *
     * Found on device 2026-08-16 (Kevin): "ai can play what i ask but i couldn't get
     * it to pause or skip". `play_music` goes to [SpotifyController.playUri] over App
     * Remote, which needs NO notification grant, so starting a track worked while
     * every transport command failed - and the grant is per-device special access,
     * so the A25 migration dropped it. The two paths had no reason to agree and
     * nothing said why.
     *
     * So App Remote is now the fallback: it drives Spotify only, but it needs no
     * grant at all. Ordering keeps the granted case byte-identical to before, and
     * only adds a second attempt where the first currently fails.
     *
     * When neither can act, [musicFailureMessage] says which of the two reasons it
     * was, in words, rather than one generic line. There is no third silent no-op.
     *
     * The media framework expects to run on a thread with a Looper; Live tool
     * dispatch runs on a worker, so every MusicController call is marshalled to
     * [Dispatchers.Main] (a bare worker thread threw "Can't create handler ...",
     * crashing the app on next/play). App Remote has no such requirement.
     */
    private suspend fun controlMusic(context: Context, args: JSONObject): JSONObject {
        val action = args.optString("action")
        if (action != "play" && action != "pause" && action != "next" && action != "previous") {
            return result(success = false, message = "Unknown music action: $action")
        }

        if (NowPlayingController.hasAccess(context)) {
            val ok = withContext(Dispatchers.Main) {
                when (action) {
                    "play"     -> MusicController.play(context)
                    "pause"    -> MusicController.pause(context)
                    "next"     -> MusicController.next(context)
                    else       -> MusicController.previous(context)
                }
            }
            if (ok) return result(success = true, message = null)
        }

        // Only attempt this against a live remote. [SpotifyController.withPlayer]
        // reports success the instant the call is DISPATCHED rather than when it
        // lands (unlike playUri, which awaits the real CallResult), so gating on
        // isConnected is what keeps a `true` here from becoming a claim the app
        // cannot support.
        if (SpotifyController.isConnected) {
            val ok = when (action) {
                "play"     -> SpotifyController.play()
                "pause"    -> SpotifyController.pause()
                "next"     -> SpotifyController.next()
                else       -> SpotifyController.previous()
            }
            if (ok) return result(success = true, message = null)
        }

        return result(success = false, message = musicFailureMessage(context))
    }

    /**
     * Names the actual reason transport failed. The missing-grant case used to be
     * indistinguishable from "nothing is playing", which is why the defect above
     * survived: the app knew exactly why it could not act and said nothing.
     */
    internal fun musicFailureMessage(context: Context): String =
        if (!NowPlayingController.hasAccess(context)) {
            "I can't reach the transport controls. Android requires notification access " +
                "to control media, and Legion doesn't have it - it's under Settings, Apps, " +
                "Special app access, Notification access. Until then I can start a track on " +
                "Spotify, but I can't pause or skip anything else."
        } else {
            "I couldn't reach the music. Make sure something's actually playing."
        }

    /**
     * Adjusts the head unit's media volume on-device ([VolumeController]) - no
     * cloud needed once the intent is known. Reports the resulting level so Zero
     * can confirm where it landed.
     */
    private fun controlVolume(context: Context, args: JSONObject): JSONObject {
        val pct = when (val action = args.optString("action")) {
            "up" -> VolumeController.raise(context)
            "down" -> VolumeController.lower(context)
            "set" -> {
                val level = args.optInt("level", -1)
                if (level < 0) return result(success = false, message = "What level should I set it to?")
                VolumeController.setPercent(context, level)
            }
            "mute" -> VolumeController.mute(context, true)
            "unmute" -> VolumeController.mute(context, false)
            else -> return result(success = false, message = "Unknown volume action: $action")
        }
        return result(success = true, message = null).put("volumePercent", pct)
    }

    /**
     * Reverse-geocodes via Android's built-in `Geocoder` (needs a GMS geocoding
     * backend on-device; degrades to coordinates-only if unavailable, per CLAUDE.md
     * sec 9's "network calls degrade gracefully" rule).
     */
    private suspend fun getCurrentLocation(context: Context): JSONObject {
        // init() is idempotent (see its own doc) and, critically, seeds state from
        // getLastKnownLocation synchronously - so a driver who just granted location in Android
        // Settings and comes straight back to a voice call gets an immediate answer instead of
        // waiting on the first live update. Before this call the ONLY caller of init() anywhere
        // in the app was AriaForegroundService.onCreate, so a permission granted after the
        // service already started did nothing until the service was recreated - traced via
        // `grep -rn "LocationController.init"`, one hit.
        LocationController.init(context)
        val loc = LocationController.state.value
            ?: return when {
                // The one actionable case: LEGION itself was never granted location. Everything
                // else below is the phone's own settings, not something re-asking the tool fixes.
                !LocationController.hasPermission(context) -> result(success = false,
                    message = "LEGION doesn't have location permission. Grant it in Android's " +
                        "app settings for LEGION and try again.")
                // Permission granted, but the driver (or a battery saver mode) has both GPS and
                // network location switched off system-wide - a different fix than "wait longer".
                !LocationController.anyProviderEnabled(context) -> result(success = false,
                    message = "Location services are switched off on the phone. Turn on GPS or " +
                        "network location and try again.")
                // Permission granted, a provider is on, there's just no fix yet - this is the
                // ONLY case the old blanket message was actually true for.
                else -> result(success = false, message = "I don't have a GPS fix yet.")
            }

        val coords = "(lat ${loc.latitude}, lng ${loc.longitude})"
        val label = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                android.location.Geocoder(context, java.util.Locale.getDefault())
                    .getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()
                    ?.let { listOfNotNull(it.thoroughfare, it.locality, it.adminArea).joinToString(", ") }
                    ?.ifBlank { null }
            }.getOrNull()
        }
        return if (label != null) result(success = true, message = "Current location: $label $coords")
        else result(success = true, message = "Current location: $coords (couldn't resolve an address)")
    }

    /**
     * Plays a specific track/artist by name, in-app, via Spotify App Remote +
     * Web API search. This is the only "play something specific" path left -
     * the old OS-level play-from-search-intent fallback (opening a separate
     * music app full-screen, with a floating companion badge over it) was
     * retired with the rest of the car-launcher UI in the 2026-07-31 pivot.
     * If Spotify isn't connected, this fails with an actionable message rather
     * than falling back to launching another app.
     */
    private suspend fun playMusic(context: Context, query: String): JSONObject {
        if (query.isBlank()) return result(success = false, message = "What should I play?")

        // ensureConnected, not isConnected: App Remote drops on its own (Spotify
        // killed/backgrounded), so this always tries a silent reconnect first.
        // Only attempts a reconnect when a client ID is actually saved, so a
        // driver who never set Spotify up pays nothing here.
        if (!SpotifyController.ensureConnected(context)) {
            return result(
                success = false,
                message = "Spotify isn't connected - connect your Spotify account in Setup, or pick " +
                    "something on your phone yourself and I'll control play/pause/skip from here.",
            )
        }

        // Not authorized = no Web API = no way to turn a name into a URI. Say so
        // plainly; this is the one failure the driver can fix themselves.
        if (!SpotifyWebApi.isAuthorized(context)) {
            return result(
                success = false,
                message = "Spotify isn't finished connecting - open Setup, tap CONNECT under the " +
                    "Spotify client ID, and approve it in the browser. Then I can play by name.",
            )
        }

        // Each outcome gets its own answer (2026-08-12). These used to be one nullable
        // String reported as "I couldn't find that", so an expired grant, a dead
        // connection and a genuinely unknown song were indistinguishable to the driver
        // AND to anyone debugging it - the exact collapse GoogleGrantResolver.diagnose
        // was written to undo on the Drive side.
        val uri = when (val outcome = SpotifyWebApi.searchTrack(context, query)) {
            is SpotifyWebApi.SearchOutcome.Found -> outcome.uri
            SpotifyWebApi.SearchOutcome.NeedsAuthorization -> return result(
                success = false,
                message = "Spotify hasn't been authorized on this device yet - open Setup, " +
                    "Spotify, and tap AUTHORIZE.",
            )
            is SpotifyWebApi.SearchOutcome.Unauthorized -> return result(
                success = false,
                // Spotify's own words are carried through rather than paraphrased: a 403
                // saying "the user may not be registered" is a dashboard problem that
                // re-authorizing will never fix, and telling the driver to tap AUTHORIZE
                // again would send them in circles.
                message = "Spotify rejected the request" +
                    (outcome.detail?.let { ": $it" } ?: ".") +
                    " Run the search test in Setup, Spotify for the details.",
            )
            SpotifyWebApi.SearchOutcome.Unreachable -> return result(
                success = false,
                message = "I couldn't reach Spotify just now. Worth trying again when you have " +
                    "a better connection.",
            )
            SpotifyWebApi.SearchOutcome.NoMatch -> return result(
                success = false,
                message = "Spotify has nothing matching \"$query\".",
            )
            is SpotifyWebApi.SearchOutcome.Failed -> return result(
                success = false,
                message = "Spotify's search returned an error (${outcome.code})" +
                    (outcome.detail?.let { ": $it" } ?: "."),
            )
        }

        // playUri awaits App Remote's real result, so this genuinely means
        // playback started - it is not just "the call didn't throw".
        if (SpotifyController.playUri(uri)) {
            return result(success = true, message = "Playing \"$query\" on Spotify.")
        }
        return result(
            success = false,
            message = "Spotify wouldn't start that one - it may not be playable on your account here.",
        )
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
            description = "Save the name the driver chose for you (the companion). Call this as soon " +
                "as they tell you what to call you.",
            params = obj("name" to schema("string", "The name the driver wants to call you.")),
            required = listOf("name"),
        ))

        fns.put(fn(
            name = "set_personality",
            description = "Save the personality you've shaped together. Call this once you have a feel " +
                "for who you are.",
            params = obj(
                "description" to schema("string", "A short second-person persona summary written as an " +
                    "instruction to yourself, e.g. 'You are warm and easygoing, quick with a dry joke, " +
                    "and you treat the driver like an old friend.'"),
                "look" to schema("string", "A brief visual descriptor of how you'd appear as a " +
                    "character, used to draw your face, e.g. 'a laid-back, warm-eyed retro mascot'."),
            ),
            required = listOf("description"),
        ))

        fns.put(fn(
            name = "set_driver",
            description = "Save who the driver is. Call this after they tell you their name and " +
                "anything they want you to know about them.",
            params = obj(
                "name" to schema("string", "The driver's name."),
                "about" to schema("string", "Optional: anything the driver wants you to know about them."),
            ),
            required = listOf("name"),
        ))

        fns.put(fn(
            name = "register_car",
            description = "Save the car the driver owns. Call this once they tell you what they drive. " +
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


    private fun result(success: Boolean, message: String?): JSONObject =
        JSONObject().put("success", success).apply { if (message != null) put("message", message) }

    /**
     * Pure decision extracted out of [agentResult] so it's unit-testable without a real Gemini
     * call: when [requireMutation] is false, or [mutatingToolsCalled] is non-empty, the sub-agent's
     * own [subAgentText] is what the driver hears, success. When [requireMutation] is true and
     * NOTHING mutating ran, [subAgentText] is discarded entirely - never spoken, since it is
     * exactly the shape of text that caused this fix ("logged it" over nothing written) - and a
     * plain, generic "not recorded" refusal takes its place instead. `internal` (not private) so
     * [LiveToolboxMutationGateTest] can exercise the three cases directly.
     */
    internal fun successOrMutationRefusal(
        subAgentText: String,
        mutatingToolsCalled: List<String>,
        requireMutation: Boolean,
    ): JSONObject = if (requireMutation && mutatingToolsCalled.isEmpty()) {
        result(false, "That didn't get written down - nothing changed. Try again and I'll log it properly.")
    } else {
        result(true, subAgentText)
    }

    // --- JSON schema helpers --------------------------------------------

    private fun fn(name: String, description: String, params: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", params)
                .put("required", JSONArray(required)))

    private fun obj(vararg props: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply { for ((k, v) in props) put(k, v) }

    private fun schema(type: String, description: String, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", type).put("description", description)
            .apply { if (enum != null) put("enum", JSONArray(enum)) }
}
