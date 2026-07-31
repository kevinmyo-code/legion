package com.kevin.legion.service

import android.content.Context
import android.content.Intent
import com.kevin.legion.MidnightEvents
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import com.kevin.legion.util.shortDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kevin.legion.location.ReminderController
import com.kevin.legion.media.MusicController
import com.kevin.legion.media.SpotifyController
import com.kevin.legion.media.SpotifyWebApi
import com.kevin.legion.media.VolumeController
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.vehicle.BuildSheetController
import com.kevin.legion.vehicle.CarToolbelt
import com.kevin.legion.vehicle.CarTaskController
import com.kevin.legion.vehicle.ColdStartAgent
import com.kevin.legion.vehicle.DiagnosticAgent
import com.kevin.legion.vehicle.DtcDescriptions
import com.kevin.legion.vehicle.GarageController
import com.kevin.legion.vehicle.GaragePreferences
import com.kevin.legion.vehicle.MaintenanceAgent
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.SymptomAgent
import com.kevin.legion.vehicle.TelemetryRecorder
import com.kevin.legion.vehicle.VehicleController
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

    /** Function declarations to advertise in the Live setup message. */
    fun declarations(): JSONArray {
        val fns = JSONArray()

        fns.put(fn(
            name = "get_vehicle_data",
            description = "Read a live value from the car's OBD-II port. Use whenever the driver " +
                "asks about the engine's current temperature, RPM, or just whether any stored " +
                "trouble/check-engine codes are present. To explain what a code MEANS or how to FIX " +
                "it, use diagnose_codes instead.",
            params = obj(
                "metric" to schema("string", "Which live reading to fetch.",
                    enum = listOf("coolant_temp", "rpm", "trouble_codes"))
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
                "Tell the driver you're digging into it before calling this - it takes a little while.",
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
                "when the driver wants to know what's WRONG or how to fix it.",
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
                "this - it takes a little while.",
            params = obj("symptom" to schema("string", "The driver's description of the problem in " +
                "their own words, e.g. 'grinding when I brake' or 'rough idle when cold'.")),
            required = listOf("symptom"),
        ))

        fns.put(fn(
            name = "get_health",
            description = "Read a quick health snapshot from the OBD port - battery voltage, coolant " +
                "temperature, and whether any trouble codes are stored. Use when the driver asks if " +
                "the car's okay, wants a health check, or is about to set off on a long drive (a " +
                "pre-trip check).",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "get_trend",
            description = "Fetch how a vehicle metric has trended over recent weeks from the recorded " +
                "history (coolant, rpm, voltage, load, fuel_trim, mpg). Use when the driver asks how " +
                "something has been running lately, whether it's been getting worse, or how it compares " +
                "to before.",
            params = obj(
                "metric" to schema(
                    "string", "Which metric to trend.",
                    enum = listOf("coolant", "rpm", "voltage", "load", "fuel_trim", "mpg"),
                ),
                "days" to schema("integer", "How many days back to look. Default 30."),
            ),
            required = listOf("metric"),
        ))

        fns.put(fn(
            name = "get_mpg",
            description = "Real fuel economy measured from the engine's own airflow plus GPS distance - " +
                "no fill-up entry needed. Returns the current drive's MPG, the lifetime average, and " +
                "recent per-drive numbers. Use when the driver asks about gas mileage or fuel economy.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "check_readiness",
            description = "Read the emissions readiness monitors live from the OBD port - which " +
                "self-tests are complete and which still need drive time. Use before a state " +
                "inspection or smog check, or when the driver asks if the car will pass.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "check_cold_start",
            description = "Analyze the most recent recorded cold start (the first minute of warm-up: " +
                "idle, fuel trims, warm-up rate) against earlier ones. Use when the driver asks how " +
                "the car has been starting, mentions rough cold idle, or asks about warm-up health. " +
                "Tell the driver you're digging into it before calling this - it takes a little while.",
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
            params = obj(),
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
            name = "set_odometer",
            description = "Record the car's current odometer reading (the driver is the source of " +
                "truth). Use when the driver states their mileage, e.g. 'my odometer is at 142500'.",
            params = obj("miles" to schema("integer", "Current odometer reading in miles.")),
            required = listOf("miles"),
        ))

        fns.put(fn(
            name = "log_service",
            description = "Record that a maintenance service was just completed, clearing its 'due' " +
                "status. Use when the driver says they did some work, e.g. 'I just changed the oil'.",
            params = obj("service" to schema("string", "The service performed, e.g. oil change, tire rotation, brake pads.")),
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
            ),
            required = listOf("service"),
        ))

        fns.put(fn(
            name = "lookup_vin",
            description = "Read the car's VIN from the connected OBD adapter and look up its year, " +
                "make, model, and trim. Use when the driver wants you to identify the car from the " +
                "port, fill in its details automatically, or asks 'what car is this' / 'pull my VIN'. " +
                "This only reads the facts - it does NOT save them. Read them back and ask the driver " +
                "to confirm, then call register_vehicle to save. Needs the OBD adapter connected.",
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
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "check_recalls",
            description = "Look up active manufacturer recalls for this car (live from NHTSA by year/" +
                "make/model). Use when the driver asks if the car has any recalls or open safety " +
                "campaigns. The driver must have told you their car's year, make, and model first; " +
                "if they haven't, this returns an error asking you to get those - never guess or " +
                "assume the car.",
            params = obj(),
            required = listOf(),
        ))

        fns.put(fn(
            name = "register_vehicle",
            description = "Register the connected car's year, make, and model. Triggers an online " +
                "lookup of its maintenance schedule. Use when the driver identifies the car, e.g. " +
                "'this car is a 2003 BMW 330i', or to save the details after lookup_vin found them.",
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

        fns.put(fn(
            name = "add_car_task",
            description = "Add an item to the car's to-do / wishlist. Use whenever the driver says they " +
                "need to, want to, or are planning to do or buy something for the car: maintenance to " +
                "get to ('change the oil soon', 'replace the bushings'), a future project or build " +
                "('LS swap', 'new coilovers'), or an accessory to buy ('new wheels', 'a light bar').",
            params = obj(
                "task" to schema("string", "What to add, in the driver's own words, e.g. 'replace the front bushings'."),
                "category" to schema("string", "Which kind of item this is.",
                    enum = listOf("maintenance", "project", "wishlist")),
            ),
            required = listOf("task"),
        ))

        fns.put(fn(
            name = "complete_car_task",
            description = "Check an item off the car's to-do / wishlist because the driver did the work " +
                "or bought the thing. Use for 'I changed the oil', 'cross off the light bar', " +
                "'I got the new wheels', 'check off the bushings'.",
            params = obj("query" to schema("string", "Which item to check off, in the driver's words.")),
            required = listOf("query"),
        ))

        fns.put(fn(
            name = "remove_car_task",
            description = "Remove an item from the car's to-do / wishlist because the driver changed " +
                "their mind or no longer wants it. This is different from completing it.",
            params = obj("query" to schema("string", "Which item to remove, in the driver's words.")),
            required = listOf("query"),
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
            name = "list_car_tasks",
            description = "Read back what's on the car's to-do / wishlist. Use when the driver asks " +
                "what's on their list, what they still need to do, or what's left to do or buy for the car.",
            params = obj("category" to schema("string", "Optional filter.",
                enum = listOf("maintenance", "project", "wishlist"))),
            required = listOf(),
        ))

        fns.put(fn(
            name = "log_build_entry",
            description = "Log something on the car's build sheet / spend ledger - a mod, part, " +
                "repair, consumable, or general purchase for the car. Use when the driver says they " +
                "did, bought, or installed something, or spent on the car (e.g. 'I just put on " +
                "coilovers', 'logged a new clutch, six hundred bucks'). Only capture a cost if they " +
                "actually state one.",
            params = obj(
                "title" to schema("string", "What it is, e.g. 'BC Racing coilovers' or 'new clutch'."),
                "type" to schema("string", "Category of the entry.",
                    enum = listOf("mod", "part", "repair", "consumable", "other")),
                "cost" to schema("number", "Dollar amount, only if the driver stated one. Omit otherwise."),
                "vendor" to schema("string", "Where it was bought / the shop, if mentioned."),
                "notes" to schema("string", "Any extra detail the driver gives."),
            ),
            required = listOf("title"),
        ))

        fns.put(fn(
            name = "list_build_history",
            description = "Read back the car's build history - what's been done, installed, or bought " +
                "and when. Use when the driver asks what's on the build sheet, what they've done to the " +
                "car, or wants a build rundown (e.g. for selling it). The history is always available; " +
                "dollar amounts are only included when the spend log is unlocked.",
            params = obj("type" to schema("string", "Optional category filter.",
                enum = listOf("mod", "part", "repair", "consumable", "other"))),
            required = listOf(),
        ))

        fns.put(fn(
            name = "get_spend",
            description = "Report how much has been spent on the car - the grand total or one category. " +
                "Only use when the driver explicitly asks about money, cost, or total spent.",
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
                "to ground a nearby search or navigation request.",
            params = obj(),
            required = listOf(),
        ))

        return fns
    }

    /**
     * Runs a tool call. Returns a JSON response to hand back to Gemini, or null
     * if the tool is UI-scoped (`show_saved_places`) and must be handled by the
     * caller that owns the screen.
     */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? {
        MidnightEvents.toolDispatched(name)
        return when (name) {
            "get_vehicle_data" -> getVehicleData(args.optString("metric"))
            "get_codes" -> getCodes(context)
            "diagnose_codes" -> diagnoseCodes(context, args)
            "triage_symptom" -> triageSymptom(context, args)
            "get_health" -> getHealth()
            "get_trend" -> getTrend(context, args)
            "get_mpg" -> getMpg(context)
            "check_readiness" -> checkReadiness()
            "check_cold_start" -> checkColdStart(context)
            "get_next_service" -> getNextService(context)
            "ask_maintenance" -> askMaintenance(context, args)
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
            "set_odometer" -> result(success = true, message = VehicleController.setOdometer(context, args.optInt("miles")))
            "log_service" -> result(success = true, message = VehicleController.logServiceDirect(context, args.optString("service")))
            "log_past_service" -> logPastService(context, args)
            "lookup_vin" -> lookupVin(context)
            "get_specs" -> getSpecs(context)
            "check_recalls" -> checkRecalls(context)
            "register_vehicle" -> result(
                success = true,
                message = VehicleController.registerDirect(
                    context, args.optInt("year"), args.optString("make"), args.optString("model")
                )
            )
            "remember" -> result(success = true, message = AriaBrain.get(context).remember(args.optString("text")))
            "recall_memory" -> recallMemory(context, args.optString("query"))
            "add_car_task" -> result(
                success = true,
                message = CarTaskController.add(context, args.optString("task"), args.optString("category")),
            )
            "complete_car_task" -> {
                val msg = CarTaskController.complete(context, args.optString("query"))
                result(success = msg != null, message = msg ?: "I couldn't find that on your list.")
            }
            "remove_car_task" -> {
                val msg = CarTaskController.remove(context, args.optString("query"))
                result(success = msg != null, message = msg ?: "I couldn't find that on your list.")
            }
            "list_car_tasks" -> listCarTasks(context, args.optString("category"))
            "log_build_entry" -> logBuildEntry(context, args)
            "list_build_history" -> listBuildHistory(context, args.optString("type"))
            "get_spend" -> getSpend(context, args.optString("category"))
            "activate_garage" -> activateGarage(context, args)
            // Session-scoped tools the owning controller handles (it has the live
            // session / capture controller / activity), so dispatch returns null:
            "show_saved_places" -> null // caller launches the saved-places screen and replies
            else -> result(success = false, message = "Unknown tool: $name")
        }
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

    /** Reads the active car's stored factory specs ([VehicleSpecController]) for Zero to read out. */
    private suspend fun getSpecs(context: Context): JSONObject {
        val spec = VehicleSpecController.current(context)
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

    /** Live NHTSA recall lookup for the active car ([VehicleSpecController]); on-request only. */
    private suspend fun checkRecalls(context: Context): JSONObject {
        // Recalls are keyed by year/make/model, which - with no OBD and no
        // registration - would be the default mascot seed (1998 Jeep Cherokee).
        // Refuse until the driver has actually confirmed the car so we never
        // report recalls for a vehicle they never claimed.
        if (!VehicleController.isConfirmed(context)) {
            return result(
                success = false,
                message = "I don't know which car this is yet, so I can't check recalls. " +
                    "Tell me the year, make, and model first.",
            )
        }
        val recalls = VehicleSpecController.recalls(context)
        val arr = JSONArray()
        for (r in recalls) {
            arr.put(JSONObject()
                .put("campaign", r.campaign)
                .put("component", r.component)
                .put("summary", r.summary)
                .put("remedy", r.remedy))
        }
        return JSONObject().put("success", true).put("count", recalls.size).put("recalls", arr)
    }

    /** Searches long-term memory for entries relevant to [query], for Zero to read out. */
    private suspend fun recallMemory(context: Context, query: String): JSONObject {
        val memories = AriaBrain.get(context).recallMemories(query)
        val arr = JSONArray()
        for (m in memories) arr.put(m)
        return JSONObject().put("success", true).put("count", memories.size).put("memories", arr)
    }

    // --- Build sheet / spend ledger -------------------------------------

    /** Logs a build-sheet entry (mod/part/repair/consumable/other), with optional cost. */
    private suspend fun logBuildEntry(context: Context, args: JSONObject): JSONObject {
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
        )
        return result(success = true, message = msg)
    }

    /**
     * Reads back build history (what/when, plus cost - ungated, see [getSpend]'s doc).
     */
    private suspend fun listBuildHistory(context: Context, type: String): JSONObject {
        val entries = BuildSheetController.history(context, type)
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject().put("title", e.title).put("type", e.type).put("date", shortDate(e.date))
            if (e.mileage != null) o.put("mileage", e.mileage)
            if (e.vendor.isNotBlank()) o.put("vendor", e.vendor)
            if (e.notes.isNotBlank()) o.put("notes", e.notes)
            if (e.cost != null) o.put("cost", e.cost)
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", entries.size).put("entries", arr)
    }

    /**
     * Reports total / per-category spend. No gating - SpendGate was retired
     * 2026-07-31 with no replacement built yet.
     */
    private suspend fun getSpend(context: Context, category: String): JSONObject {
        val byCat = BuildSheetController.spendByCategory(context)
        val cat = category.trim().lowercase()
        if (cat.isNotBlank()) {
            val amount = byCat[cat] ?: byCat[BuildSheetController.normalizeType(cat)] ?: 0.0
            return JSONObject().put("success", true).put("category", cat).put("spend", amount)
        }
        val catObj = JSONObject()
        for ((k, v) in byCat) catObj.put(k, v)
        return JSONObject().put("success", true)
            .put("total", BuildSheetController.totalSpend(context))
            .put("byCategory", catObj)
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

    /** Returns the open car to-do/wishlist items (optionally filtered) for Zero to read out. */
    private suspend fun listCarTasks(context: Context, category: String): JSONObject {
        val cat = category.trim()
        val tasks = CarTaskController.openTasks(context)
            .let { if (cat.isNotBlank()) it.filter { t -> t.category.equals(cat, ignoreCase = true) } else it }
        val arr = JSONArray()
        for (t in tasks) arr.put(JSONObject().put("task", t.text).put("category", t.category))
        return JSONObject().put("success", true).put("count", tasks.size).put("tasks", arr)
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
     */
    private suspend fun agentResult(failMessage: String, call: suspend () -> AgentResult): JSONObject {
        if (!GeminiKeyProvider.hasKey()) {
            return result(
                false,
                "I need a Gemini key to do that - add your own in Setup to keep going.",
            )
        }
        return when (val r = call()) {
            is AgentResult.Success -> { KeyHealth.noteOk(); result(true, r.text) }
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
     */
    private suspend fun getTrend(context: Context, args: JSONObject): JSONObject {
        val text = CarToolbelt.trendSummary(context, args.optString("metric"), args.optInt("days", 30))
        // Preserve the pre-delegation success flag for the two soft-fail sentences.
        val ok = !text.startsWith("Unknown metric") && !text.startsWith("Not enough history")
        return result(ok, text)
    }

    /** Measured fuel economy: current drive, lifetime, and recent per-drive numbers. */
    private suspend fun getMpg(context: Context): JSONObject {
        val vehicle = VehicleController.currentVehicle(context)
        val trips = CarDatabase.getDatabase(context).odbSampleDao()
            .getLatest(vehicle.obdMac, "MPG_TRIP", 5)
        val current = TelemetryRecorder.currentDriveMpg()
        val lifetime = TelemetryRecorder.lifetimeMpg(context)
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
     */
    private suspend fun getNextService(context: Context): JSONObject {
        val vehicle = VehicleController.currentVehicle(context)
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
                        if (it.remaining <= 0L) "${it.serviceName} is due today."
                        else "By time, ${it.serviceName} is next, about ${VehicleController.formatRemaining(it.remaining, days)} out."
                    } ?: "Nothing's coming up by time either yet."
                )
            }
            sameItem && next.byTime!!.remaining <= 0L -> "${next.byMiles!!.serviceName} is due today."
            sameItem -> "${next.byMiles!!.serviceName}, about ${VehicleController.formatRemaining(next.byMiles.remaining, miles)} or " +
                "${VehicleController.formatRemaining(next.byTime!!.remaining, days)}, whichever comes first."
            next.byMiles != null && next.byTime != null -> buildString {
                append("${next.byMiles.serviceName} is soonest by mileage, about ${VehicleController.formatRemaining(next.byMiles.remaining, miles)} out. ")
                append(
                    if (next.byTime.remaining <= 0L) "${next.byTime.serviceName} is due today."
                    else "${next.byTime.serviceName} is soonest by time, about ${VehicleController.formatRemaining(next.byTime.remaining, days)} out."
                )
            }
            next.byMiles != null -> "${next.byMiles.serviceName} is next, about ${VehicleController.formatRemaining(next.byMiles.remaining, miles)} out."
            next.byTime != null -> {
                if (next.byTime.remaining <= 0L) "${next.byTime.serviceName} is due today."
                else "${next.byTime.serviceName} is next, about ${VehicleController.formatRemaining(next.byTime.remaining, days)} out."
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

        return result(true, body + unknownNote)
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
    private suspend fun logPastService(context: Context, args: JSONObject): JSONObject {
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

        val message = VehicleController.logPastServiceDirect(context, service, mileage, milesAgo, date, neverDone)
        val finalMessage = if (dateError != null) "$message $dateError" else message
        return result(success = true, message = finalMessage)
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
     * round-trip, so Zero typically says "let me check..." first.
     */
    private suspend fun askMaintenance(context: Context, args: JSONObject): JSONObject {
        val question = args.optString("question")
        val vehicle = VehicleController.currentVehicle(context)
        val label = VehicleController.displayLabel(vehicle)

        val items = CarDatabase.getDatabase(context).maintenanceItemDao().getForVehicle(vehicle.obdMac)
        val mileage = VehicleController.currentMileage(vehicle)

        return agentResult("I couldn't reach the maintenance specialist just now - try again in a sec.") {
            MaintenanceAgent.answer(context, label, mileage, items, question)
        }
    }

    private suspend fun getVehicleData(metric: String): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return JSONObject().put("connected", false)
                .put("note", "OBD adapter not connected; tell the driver to check it's plugged in.")
        }
        return when (metric) {
            "coolant_temp" -> {
                val c = ObdBluetoothManager.getCoolantTemp()
                JSONObject().put("connected", true).apply {
                    if (c == null) put("available", false)
                    else put("available", true).put("celsius", c).put("fahrenheit", c * 9 / 5 + 32)
                }
            }
            "rpm" -> {
                val rpm = ObdBluetoothManager.getRpm()
                JSONObject().put("connected", true).apply {
                    if (rpm == null) put("available", false) else put("available", true).put("rpm", rpm)
                }
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
     * Controls whatever's playing via Android's media-session framework
     * ([MusicController]) - transport only (play/pause/next/previous). Works
     * against any active session (phone-BT relay, Spotify, etc); there's no
     * search-to-play here, only [playMusic] (Spotify-direct) supports that.
     *
     * The media framework expects to run on a thread with a Looper; Live tool
     * dispatch runs on a worker, so every MusicController call is marshalled to
     * [Dispatchers.Main] (a bare worker thread threw "Can't create handler ...",
     * crashing the app on next/play).
     */
    private suspend fun controlMusic(context: Context, args: JSONObject): JSONObject {
        return when (val action = args.optString("action")) {
            "play"     -> simpleMusicResult(withContext(Dispatchers.Main) { MusicController.play(context) })
            "pause"    -> simpleMusicResult(withContext(Dispatchers.Main) { MusicController.pause(context) })
            "next"     -> simpleMusicResult(withContext(Dispatchers.Main) { MusicController.next(context) })
            "previous" -> simpleMusicResult(withContext(Dispatchers.Main) { MusicController.previous(context) })
            else       -> result(success = false, message = "Unknown music action: $action")
        }
    }

    private fun simpleMusicResult(ok: Boolean): JSONObject =
        if (ok) result(success = true, message = null)
        else result(success = false,
            message = "I couldn't reach the music — make sure your phone's connected and playing over Bluetooth.")

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
     * sec 9's "network calls degrade gracefully" rule). Embedded navigation and its
     * Mapbox-backed reverse geocoding were retired in the 2026-07-31 pivot - this is
     * a plain fallback, not the BYO-token path Midnight AI used.
     */
    private suspend fun getCurrentLocation(context: Context): JSONObject {
        val loc = LocationController.state.value
            ?: return result(success = false, message = "I don't have a GPS fix yet.")

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

        val uri = SpotifyWebApi.searchTrackUri(context, query)
            ?: return result(
                success = false,
                message = "I couldn't find \"$query\" on Spotify, or couldn't reach it just now.",
            )

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
