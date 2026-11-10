@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.fleet

import com.kevin.legion.service.tools.VEHICLE_PARAM
import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import com.kevin.legion.vehicle.CarToolbelt
import com.kevin.legion.vehicle.MpgTrust
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VinDecoder
import com.kevin.legion.vehicle.matchPid
import org.json.JSONObject

/**
 * The fleet tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        // Category A - live-hardware tools (fleet-wide voice, ticket 01 §0). The
        // OBD dongle is physically plugged into ONE car; these read it directly
        // and can never answer for any other car, so - unlike Category B below -
        // they take NO `vehicle` argument. Every description below says so, so
        // the model doesn't try to pass one expecting it to switch cars.
        add(fn(
            name = "get_vehicle_data",
            description = "Read a live value from the car's OBD-II port: fuel level, coolant " +
                "temperature, RPM, road speed, battery voltage, engine load, intake air " +
                "temperature, air flow, fuel trims, or whether any stored trouble/check-engine " +
                "codes are present. Use whenever the user asks what any of those are RIGHT NOW - " +
                "'how much fuel have I got', 'what's the temp', 'how's the battery'. To explain " +
                "what a code MEANS or how to FIX it, use diagnose_codes instead. Reads the OBD " +
                "dongle in the car it is plugged into right now. Cannot answer for any other car. " +
                "coolant_temp and intake_air_temp return `temperature` already in the user's " +
                "chosen unit (see `temperatureUnit`) - do not convert it.",
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

        add(fn(
            name = "diagnose_codes",
            description = "Hand off to the diagnostics specialist to explain the car's trouble / " +
                "check-engine codes: what they mean, likely causes, how serious, and how to fix. Use " +
                "whenever the user asks what's wrong with the car, about the check engine light, " +
                "what a code means, or how to fix one. The specialist reads the live codes from the " +
                "port itself - only pass 'codes' when the user names specific ones (e.g. 'what's P0420'). " +
                "Tell the user you're digging into it before calling this - it takes a little while. " +
                "Reads the OBD dongle in the car it is plugged into right now. Cannot answer for any " +
                "other car.",
            params = obj(
                "question" to schema("string", "The user's question in their own words, e.g. " +
                    "'what's wrong with my car' or 'how do I fix P0301'."),
                "codes" to schema("string", "Optional: specific codes the user named, comma-" +
                    "separated, e.g. 'P0301, P0420'. Leave empty to use the live codes from the port."),
            ),
            required = listOf("question"),
        ))

        add(fn(
            name = "get_codes",
            description = "Quick read of the stored trouble codes themselves - just the codes and " +
                "their names, no causal explanation. Instant, offline, free. Use for a plain factual " +
                "ask like 'what's the codes' or 'what codes are stored' - use diagnose_codes instead " +
                "when the user wants to know what's WRONG or how to fix it. Reads the OBD dongle in " +
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
        add(fn(
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
        add(fn(
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
                "sensor" to schema("string", "What to read, in the user's words - 'oil temp', " +
                    "'boost', 'fuel rate', 'ambient'. Omit entirely to list everything this car supports."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf(),
        ))

        add(fn(
            name = "triage_symptom",
            description = "Use when the user describes how the car is BEHAVING rather than naming " +
                "a code: a noise, smell, vibration, leak, rough idle, hard start, loss of power, or " +
                "how a warning light is acting - 'what's that rattle', 'why does it shudder at idle', " +
                "'it smells like burning'. The specialist reasons from the symptom plus live readings " +
                "and stored codes, grounded to this exact car. Use diagnose_codes instead when the " +
                "user names a specific code. Tell the user you're digging into it before calling " +
                "this - it takes a little while. Reads the OBD dongle in the car it is plugged into " +
                "right now. Cannot answer for any other car.",
            params = obj("symptom" to schema("string", "The user's description of the problem in " +
                "their own words, e.g. 'grinding when I brake' or 'rough idle when cold'.")),
            required = listOf("symptom"),
        ))

        add(fn(
            name = "get_health",
            description = "Read a quick health snapshot from the OBD port - battery voltage, coolant " +
                "temperature, and whether any trouble codes are stored. Use when the user asks if " +
                "the car's okay, wants a health check, or is about to set off on a long drive (a " +
                "pre-trip check). Reads the OBD dongle in the car it is plugged into right now. Cannot " +
                "answer for any other car. The coolant reading is already in the user's chosen " +
                "unit (see coolantUnit) - do not convert it.",
            params = obj(),
            required = listOf(),
        ))

        // Fleet's first WRITE to the car (D1-D10, `.scratch/hands-and-senses/issues/01-clear-dtc.md`).
        // Category A like the seven other DTC tools above (D10) - live-hardware, no `vehicle` param,
        // guarded by CATEGORY_A_TOOLS/refuseIfNotConnectedCar below. The confirm shape is copied
        // VERBATIM from activate_garage (D4): required confirmed boolean, model instructed to call
        // false first and true only after a yes in the very next turn - and, same as activate_garage,
        // this only works inside a live session (D4.4), never a one-shot.
        add(fn(
            name = "clear_codes",
            description = "Erase the car's stored trouble codes - OBD Mode 04, a REAL WRITE to the " +
                "ECU. This also wipes the freeze frame and RESETS THE EMISSIONS READINESS MONITORS, " +
                "so the car will fail an inspection until it has driven enough to reset them. " +
                "Destructive and not reversible. ALWAYS call this first with confirmed=false - it " +
                "returns the exact warning and question to recite; say it, then STOP and wait. Call " +
                "again with confirmed=true only when the user's next words are a plain yes to that " +
                "question - \"try again\", a question, or anything else is not a yes; ask again. The " +
                "app checks the user's own words and refuses a confirmed call without a yes. Only " +
                "say 'cleared' if this tool's result says so. Use ONLY when the user asks to clear, " +
                "reset or erase codes; to read or ask about codes, use ask_fleet. Reads the OBD " +
                "dongle in the car it is plugged into right now. Cannot answer for any other car.",
            params = obj(
                "confirmed" to schema("boolean", "False on the first call to read the codes and get " +
                    "the confirm question. True only after the user answered it with a plain yes."),
            ),
            required = listOf("confirmed"),
        ))

        // Category B - stored-data tools (fleet-wide voice, ticket 01 §0). Room
        // is already keyed by vehicleId, so these all take an optional `vehicle`
        // argument; omitted means the car currently being driven, which is what
        // keeps every existing utterance answering exactly as it did before.
        add(fn(
            name = "get_trend",
            // "mpg" deliberately absent from this metric list (ticket 09,
            // `.scratch/drive-ui/issues/09-mpg-scale-bug.md` - see MpgTrust's own doc): mpg display
            // is suppressed app-wide pending a fill-up calibration, and get_mpg below refuses in
            // words rather than silently answering through this tool with a wrong number instead.
            description = "Fetch how a vehicle metric has trended over recent weeks from the recorded " +
                "history (coolant, rpm, voltage, load, fuel_trim). Use when the user asks how " +
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

        add(fn(
            name = "get_mpg",
            // Description rewritten under MpgTrust.SHOW_MPG == false (ticket 09): the tool stays
            // registered so the model can still route an mpg question here and get a spoken refusal,
            // but it must never promise, let alone deliver, a figure while suppressed.
            description = "Fuel economy is currently WITHHELD on this car - LEGION's own on-board " +
                "estimate was found to read almost 2x the real figure and needs a tank-to-tank " +
                "fill-up to calibrate before it can be trusted again. Calling this tool returns that " +
                "refusal, in words, never a number. Use when the user asks about gas mileage or " +
                "fuel economy, so the refusal can be spoken rather than the question going unanswered.",
            params = obj("vehicle" to VEHICLE_PARAM),
            required = listOf(),
        ))

        add(fn(
            name = "check_readiness",
            description = "Read the emissions readiness monitors live from the OBD port - which " +
                "self-tests are complete and which still need drive time. Use before a state " +
                "inspection or smog check, or when the user asks if the car will pass. Reads the " +
                "OBD dongle in the car it is plugged into right now. Cannot answer for any other car.",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "check_cold_start",
            description = "Analyze the most recent recorded cold start (the first minute of warm-up: " +
                "idle, fuel trims, warm-up rate) against earlier ones. Use when the user asks how " +
                "the car has been starting, mentions rough cold idle, or asks about warm-up health. " +
                "Tell the user you're digging into it before calling this - it takes a little while. " +
                "Reads the OBD dongle in the car it is plugged into right now. Cannot answer for any " +
                "other car.",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "get_next_service",
            description = "Instant, free read of what's coming up next on the maintenance schedule - " +
                "the OVERALL soonest item by miles and the overall soonest by time, straight from the " +
                "logged intervals and mileage, no reasoning involved. Use this for 'what's next', " +
                "'what's coming up', 'how far off am I', or 'how many miles until my next service' - " +
                "only when the user has NOT named a specific service. If they name one (e.g. 'how " +
                "many miles until my oil change'), use ask_maintenance instead - it has per-item " +
                "access and this tool can't answer about a single named item, only the schedule's " +
                "overall next-up one. This also only covers UPCOMING items - it does not report " +
                "already-overdue ones (those are already in what you know from context), so for " +
                "'am I overdue for X' use ask_maintenance too. Do NOT use ask_maintenance for a plain " +
                "'what's next' with no named service - it's slower and this already has the answer.",
            params = obj("vehicle" to VEHICLE_PARAM),
            required = listOf(),
        ))

        add(fn(
            name = "ask_maintenance",
            description = "Hand off to the maintenance specialist for anything about the car's " +
                "service schedule: how to do / what's involved in a specific service (oil change, " +
                "brake fluid, spark plugs, etc), whether something's worth worrying about, general " +
                "maintenance questions, 'am I overdue for X', or how far off a NAMED service is (e.g. " +
                "'how many miles until my oil change') - get_next_service only knows the schedule's " +
                "overall next-up item and can't answer about one named item or an overdue one. It " +
                "uses the car's logged intervals, mileage, and service history. Use get_next_service " +
                "instead for a plain, no-service-named 'what's due/coming up next' - it's instant and " +
                "free; don't call this for that. Tell the user you're digging into it before calling " +
                "this - it takes a little while.",
            params = obj(
                "question" to schema("string", "The user's maintenance question in their own " +
                    "words, e.g. 'how do I change the brake fluid' or 'should I be worried about " +
                    "this noise'."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("question"),
        ))

        add(fn(
            name = "set_odometer",
            description = "Record the car's current odometer reading (the user is the source of " +
                "truth). Use when the user states their mileage, e.g. 'my odometer is at 142500'.",
            params = obj(
                "miles" to schema("integer", "Current odometer reading in miles."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("miles"),
        ))

        add(fn(
            name = "log_service",
            description = "Record that a maintenance service was just completed, clearing its 'due' " +
                "status. Use when the user says they did some work, e.g. 'I just changed the oil'.",
            params = obj(
                "service" to schema("string", "The service performed, e.g. oil change, tire rotation, brake pads."),
                "cost" to schema("number", "Dollar amount paid, only if the user stated one. Omit otherwise."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("service"),
        ))

        add(fn(
            name = "log_past_service",
            description = "Backfill when a maintenance item was last done from memory, not a fresh " +
                "confirmation of work just finished (that's log_service). Use when the user recalls " +
                "an approximate mileage, how long ago, or a date - e.g. 'I think I changed the oil " +
                "around 8,000 miles ago' or 'the brakes were done last spring'. Use never_done ONLY " +
                "for a confirmed 'it's never been done', which is different from not knowing. " +
                "CRITICAL: if the user says they don't know or can't remember, do NOT call this " +
                "tool at all - leave it unknown rather than guessing a value.",
            params = obj(
                "service" to schema("string", "The service, e.g. oil change, tire rotation, brake pads."),
                "mileage" to schema("integer", "Absolute odometer reading it was done at, if the user gave one, e.g. 185000."),
                "miles_ago" to schema("integer", "How many miles ago, if that's how the user phrased it, e.g. 8000."),
                "date" to schema("string", "The date it was done, ISO-8601 YYYY-MM-DD or YYYY-MM, if the user gave one."),
                "never_done" to schema("boolean", "True only if the user confirms this has never been done."),
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
        add(fn(
            name = "set_maintenance_interval",
            description = "Set or change how often a maintenance item is due, e.g. 'change the oil " +
                "interval to every 7,500 miles' or 'set tire rotation to every 6 months'. Marks the " +
                "interval as user-confirmed, so the automatic schedule lookup will never silently " +
                "overwrite it again. Always reads the value back so the user can hear it actually " +
                "changed.",
            params = obj(
                "service" to schema("string", "The service, e.g. oil change, tire rotation, brake pads."),
                "interval_miles" to schema("integer", "New mileage interval, if the user gave one, e.g. 7500."),
                "interval_months" to schema("integer", "New time interval in months, if the user gave one, e.g. 6."),
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
        add(fn(
            name = "lookup_vin",
            description = "Read the car's VIN from the connected OBD adapter and look up its year, " +
                "make, model, and trim. Use when the user wants you to identify the car from the " +
                "port, fill in its details automatically, or asks 'what car is this' / 'pull my VIN'. " +
                "This only reads the facts - it does NOT save them. Read them back and ask the user " +
                "to confirm, then call register_vehicle to save. Needs the OBD adapter connected. " +
                "Reads the OBD dongle in the car it is plugged into right now. Cannot answer for any " +
                "other car.",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "get_specs",
            description = "Read the car's stored factory specs from its build-details encyclopedia - " +
                "engine, displacement, horsepower, transmission, drivetrain, body, assembly plant, and " +
                "any paint/notes the user saved. Use when the user asks about the car's specs, " +
                "engine, how much power it makes, what transmission/drivetrain it has, where it was " +
                "built, etc. If nothing's on file, tell them to run a VIN lookup (lookup_vin) or fill " +
                "it in under Logbook â†’ Specs.",
            params = obj("vehicle" to VEHICLE_PARAM),
            required = listOf(),
        ))

        add(fn(
            name = "check_recalls",
            description = "Look up active manufacturer recalls for a car (live from NHTSA by year/" +
                "make/model). Use when the user asks if the car has any recalls or open safety " +
                "campaigns. If the car's record lacks its year, make or model and the user said them " +
                "(e.g. 'it's a 1998 Jeep Cherokee'), pass year, make and model; otherwise leave them " +
                "out - never guess the car.",
            params = obj(
                "vehicle" to VEHICLE_PARAM,
                "year" to schema("integer", "Model year the user stated, e.g. 1998."),
                "make" to schema("string", "Make the user stated, e.g. Jeep."),
                "model" to schema("string", "Model the user stated, e.g. Cherokee."),
            ),
            required = listOf(),
        ))

        add(fn(
            name = "register_vehicle",
            description = "Identify the car being driven RIGHT NOW, overwriting whatever is on file " +
                "for it. Triggers an online lookup of its maintenance schedule. Use ONLY for 'THIS " +
                "car is a 2003 BMW 330i', or to save details after lookup_vin found them. " +
                "This OVERWRITES the active car - it never creates a second one. To add another car " +
                "the user owns ('add my F-150', 'I also have a...'), or to fix a car that has the " +
                "wrong details saved, use manage_vehicle instead.",
            params = obj(
                "year" to schema("integer", "Model year, e.g. 2003."),
                "make" to schema("string", "Manufacturer, e.g. BMW."),
                "model" to schema("string", "Model, e.g. 330i."),
            ),
            required = listOf("year", "make", "model"),
        ))

        add(fn(
            name = "log_build_entry",
            description = "Log something on the car's build sheet / spend ledger - a mod, part, " +
                "repair, consumable, or general purchase for the car. Use when the user says they " +
                "did, bought, or installed something, or spent on the car (e.g. 'I just put on " +
                "coilovers', 'logged a new clutch, six hundred bucks'). Only capture a cost if they " +
                "actually state one. This log has no currency column - the number is stored plainly, " +
                "in whatever unit the user said it in.",
            params = obj(
                "title" to schema("string", "What it is, e.g. 'BC Racing coilovers' or 'new clutch'."),
                "type" to schema("string", "Category of the entry.",
                    enum = listOf("mod", "part", "repair", "consumable", "other")),
                "cost" to schema("number", "Dollar amount, only if the user stated one. Omit otherwise."),
                "vendor" to schema("string", "Where it was bought / the shop, if mentioned."),
                "notes" to schema("string", "Any extra detail the user gives."),
                "vehicle" to VEHICLE_PARAM,
            ),
            required = listOf("title"),
        ))

        add(fn(
            name = "list_build_history",
            description = "Read back the car's build history - what's been done, installed, or bought " +
                "and when. Use when the user asks what's on the build sheet, what they've done to the " +
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

        add(fn(
            name = "get_spend",
            description = "Report how much has been spent on the car - the grand total or one category. " +
                "Only use when the user explicitly asks about money, cost, or total spent. This build " +
                "sheet spend log never recorded a currency for any entry - state the number plainly, " +
                "never attach a currency to it or guess one.",
            params = obj("category" to schema("string", "Optional category, e.g. 'mods' or 'maintenance'.")),
            required = listOf(),
        ))

        // Fleet-wide voice (ticket 01): this is what makes the fleet knowable at
        // all. Every other stored-data tool CAN take a `vehicle` argument, but
        // nothing tells the model a second car exists to ask about unless it
        // calls this first - a pull-based tool cannot bootstrap its own
        // discovery. See AriaBrain.assembleBase's small pre-injected fleet
        // fragment for the other half of that bootstrap.
        add(fn(
            name = "list_vehicles",
            description = "List every car on file: name, year, make, model, trim, whether it's the " +
                "one currently being driven, whether the OBD dongle is plugged into it right now, and " +
                "its last-known odometer. This is how to find out whether there's more than one car - " +
                "call this whenever the user asks what cars they have, or before assuming there's " +
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
        // 2026-10-28: "add" was invited by a mere mention ("I also drive a..."), against
        // WRITE_ON_REQUEST_CLAUSE. A mention gets an offer; only a request adds.
        add(fn(
            name = "manage_vehicle",
            description = "Add a car to the fleet, fix a car's saved details, rename one, switch " +
                "which car you're on, or archive/unarchive one. Use 'add' only when asked " +
                "('add my F-150'); a car merely mentioned gets an offer first. This is the ONLY way " +
                "to create a second car, and it does NOT change which car is active. Use 'correct' " +
                "when a car has the wrong year/make/model saved; its drives and service history stay " +
                "with it. Call list_vehicles first if you're not sure which car they mean.",
            params = obj(
                "action" to schema("string", "What to do.",
                    enum = listOf("add", "correct", "rename", "switch", "archive", "unarchive")),
                "vehicle" to schema("string", "Which car, as the user says it, e.g. 'the Outlander'. " +
                    "Required for everything EXCEPT add; for add, leave blank."),
                "year" to schema("integer", "Model year, e.g. 2020. For add and correct."),
                "make" to schema("string", "Manufacturer, e.g. Mitsubishi. Required for add."),
                "model" to schema("string", "Model, e.g. Outlander. Required for add."),
                "trim" to schema("string", "Optional trim, e.g. SEL."),
                "name" to schema("string", "What the user calls it, e.g. 'the truck'. For add and rename."),
            ),
            required = listOf("action"),
        ))
    }
}
