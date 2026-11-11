@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.core

import com.kevin.legion.location.AreaInfo
import com.kevin.legion.location.CrimeHistory
import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import com.kevin.legion.sitrep.SitrepModule
import org.json.JSONObject

/**
 * The core tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        add(fn(
            name = "get_current_time",
            // Trimmed 2026-11-10 (watches, ADR 0083) to pay for the `watch` declaration; the
            // routing ("whenever the answer depends on it") and the never-guess rule are kept.
            description = "Read the date and time from the phone's clock, in the user's timezone. " +
                "Call it whenever the answer depends on the time or date. NEVER state a time or " +
                "date from your own guess; you have no clock.",
            params = obj(),
            required = listOf(),
        ))

        // Ticket 11 (.scratch/wake-word/issues/11-let-him-say-never-mind.md). Kevin, 2026-08-20:
        // "i wanna be able to verbally stop listening. oh nothing, i dont need you right now >
        // yes sir, be here if you need me." A turn opened by voice must be closeable by voice;
        // until this, the only way out was a second tap, which needs the hand and the screen the
        // wake word exists to avoid.
        //
        // Handled by LiveSessionController, not here - it owns the session. The description
        // carries the whole burden of NOT firing on an ordinary "no", which is the failure that
        // would make this maddening: "shall I add that?" / "no" must never hang up.
        add(fn(
            name = "end_conversation",
            // Live cost diet 2026-11-10: trimmed, every trigger and the one exclusion kept.
            description = "End the conversation when he is DONE with you: 'never mind', 'nothing', " +
                "'I'm good', 'that's all', 'go away'. 'That will be all' ALWAYS means this, " +
                "however politely. 'Go to sleep', 'sleep', 'go dormant', 'stand down' mean this " +
                "too: about YOU going quiet, not his bedtime; no goodnight. Say one dry sign-off " +
                "line in your own register in the same turn; it closes when you finish. Do NOT " +
                "call this when he is answering 'no' to something you offered, declining a " +
                "suggestion, or pausing.",
            params = obj(),
            required = emptyList(),
        ))

        add(fn(
            name = "show_app",
            description = "Bring this app to the foreground. Use when the user asks to open the " +
                "app or come back to it.",
            params = obj(),
            required = listOf(),
        ))

        // show_list_modal RETIRED one-today ticket 10 slice C, 2026-09-05: the persistent-list
        // screen it brought up (`ui/NotesScreen.kt`) is gone - "everything is a checklist now". A
        // model that still calls it by name (a cached old session, for instance) gets an explicit
        // retirement message off `dispatch`'s own named branch below (§7: nothing claims success
        // unless the action ran), never a silent "Unknown tool" - same shape `manage_grocery`'s own
        // retirement comment above already established.

        // show_groceries_modal RETIRED one-today ticket 10 slice B, 2026-09-05, alongside
        // manage_grocery - see that tool's own retirement comment above. There is no checklist
        // equivalent modal in this slice; the checklists screen itself is the hands path.

        // A generated view (`.scratch/one-today/issues/06-*.md`) - a one-off chart for a niche
        // money question no screen was built for. You choose the SHAPE and the QUERY only; the app
        // runs it and fills in every number. Never state a figure yourself when this fires.
        add(fn(
            name = "show_generated_view",
            description = "Build a one-off chart/total for a niche spend question no screen " +
                "already answers, e.g. 'grocery spend by month' or 'ledger total this year'. You " +
                "pick shape/source/aggregation/window/grouping from the given options only - the " +
                "app runs the real query and supplies every number, you never state one. If the " +
                "question doesn't fit these options, say you can't build that rather than guessing.",
            params = obj(
                "shape" to schema("string", "How it renders.", enum = listOf("BAR_SERIES", "LINE_SERIES", "TOTAL_WITH_ROWS")),
                "source" to schema("string", "What it reads.", enum = listOf("LEDGER", "PANTRY")),
                "aggregation" to schema("string", "SUM for money, COUNT for how many.", enum = listOf("SUM", "COUNT")),
                "window" to schema("string", "How far back.", enum = listOf("THIS_MONTH", "LAST_3_MONTHS", "LAST_6_MONTHS", "LAST_12_MONTHS", "THIS_YEAR")),
                "grouping" to schema("string", "NONE for one figure, else BY_MONTH or BY_CATEGORY.", enum = listOf("NONE", "BY_MONTH", "BY_CATEGORY")),
                "title" to schema("string", "Short label for the view, e.g. 'Grocery spend, last 3 months'."),
            ),
            required = listOf("shape", "source", "aggregation", "window", "grouping", "title"),
        ))

        add(fn(
            name = "activate_garage",
            description = "Trigger the garage door / gate relay - a single momentary pulse, exactly " +
                "like pressing a handheld garage remote once. There is no way to know or promise " +
                "whether this will open or close the door (no door sensor), so never say 'opening' " +
                "or 'closing' - say 'triggering' or 'hitting' the door. Use when the user asks to " +
                "open, close, hit, trigger, or use the garage or gate. ALWAYS call this first with " +
                "confirmed=false to ask the user to confirm; only call it again with confirmed=true " +
                "after they say yes in the very next turn.",
            params = obj(
                "door" to schema("string", "Which door, if the user named one or there's more than " +
                    "one set up, e.g. 'garage' or 'side gate'. Leave empty for the default door."),
                "confirmed" to schema("boolean", "False on the first call to trigger the confirm " +
                    "prompt. True only after the user has just confirmed."),
            ),
            required = listOf("confirmed"),
        ))

        add(fn(
            name = "get_current_location",
            // Round 5 (field navigation): the result also carries the offline field readout
            // (FieldToolbox), so north, sunset and "bearing to the car" need no new declaration.
            description = "Get the user's GPS location and address, compass heading, sun and moon " +
                "times, and bearing to saved places: 'where am I', 'which way is north', sunset, " +
                "'bearing to the car', or to ground a web search. navigate finds its own location.",
            params = obj(),
            required = listOf(),
        ))

        // area_info (location-intelligence ticket 02): ONE tool with a category parameter rather
        // than four - four separate tools would spend four prompt-budget slots on a single idea
        // (map decision 1). Every category's result NAMES its source inside the returned string
        // ("NWS: ...", "USGS: ...", "NIFC: ...", "FEMA: ...") - see AreaInfo's own doc for why that
        // is structural rather than left to a prompt instruction. Location is the LIVE GPS fix with
        // no fallback (map decision 14) - never a saved place or a last-known position - so this
        // reuses get_current_location's exact "no permission / services off / no fix yet" branching
        // rather than inventing a fourth silent behaviour.
        add(fn(
            name = "area_info",
            description = "Check what a government or agency source is reporting around the " +
                "user's CURRENT location right now: active severe weather alerts (category " +
                "'weather', from the National Weather Service), nearby earthquakes (category " +
                "'quake', from the USGS), nearby active wildfires (category 'fire', from the " +
                "National Interagency Fire Center - named incidents only, never a raw satellite " +
                "heat detection), or recent federal disaster declarations for the user's state " +
                "(category 'disaster', from FEMA - these are administrative declarations, not " +
                "live hazards, and must never be spoken as a warning or an alert). Requires a live " +
                "GPS fix; if there isn't one yet, say so rather than guessing a location. Every " +
                "result already names its own source - never restate or paraphrase away that " +
                "attribution when repeating it back.",
            params = obj(
                "category" to schema("string", "Which source to check.",
                    enum = listOf("weather", "quake", "fire", "disaster")),
            ),
            required = listOf("category"),
        ))

        // get_reported_crime_history (map decision 7): a SEPARATE tool from area_info, not a fifth
        // category, because it answers a fundamentally different shape of question - a historical
        // count bound to one named reporting agency and one calendar year, not a live condition.
        // is_area_safe is deliberately never built (CrimeHistory's own doc has the full honesty
        // case) - this description exists specifically so the model reaches for THIS tool and this
        // caveated framing instead of inventing a safety verdict on its own.
        add(fn(
            name = "get_reported_crime_history",
            description = "Look up how many offenses a police/sheriff's agency near the user's " +
                "current location reported to the FBI, for the most recent complete calendar year " +
                "on file. THIS DOES NOT ANSWER 'is this area safe' - the data is agency-level " +
                "(a whole jurisdiction, never a neighborhood or block), roughly a year stale, and " +
                "voluntarily reported so it is incomplete by construction. If the user asks " +
                "whether somewhere is safe, say plainly that this can't answer that, THEN offer " +
                "to pull the historical figure if they still want it - never answer the safety " +
                "question from this number. Requires a live GPS fix.",
            params = obj(
                "place" to schema("string", "Optional - a specific place/agency the user named " +
                    "(e.g. 'Houston' or 'Harris County'). Leave blank to use the nearest agency " +
                    "to the current location."),
                "offense_type" to schema("string", "Which offense family to report.",
                    enum = listOf("violent", "property")),
            ),
            required = listOf(),
        ))

        // Ticket 19 (google-account-integration): the write half of calendar has existed since
        // ticket 14 (manage_item's appointment path) and the read half never did - Kevin asked
        // what was on his calendar and Alfred correctly said he couldn't check. Net +1 against a
        // budget of 71 (-> 72); see that ticket for why a domain the assistant can write to but
        // not read from is worse than either alone.
        // "Why did you say that?" - ticket 02 call 4 and ticket 08 call 4
        // (`.scratch/proactive-mode/`). The map calls an inspectable reason the cheapest trust
        // mechanism available, and it is nearly free under the hybrid engine: the rule that fired
        // IS the reason, stored on the raise row at the moment it spoke.
        //
        // The whole point is that the answer is a STORED FACT rather than the model reconstructing
        // a plausible one after the event. Ticket 08 call 4 is explicit about the wording: name
        // the rule and the fact, never justify the nudge, never guess at his state of mind. A
        // justification is unfalsifiable; a fact is checkable.
        // ADR 0082: `stop` is the voice half of "stop the morning brief" / "stop the home brief"
        // (compulsion test (d)); the description was tightened to pay for it.
        add(fn(
            name = "why_did_you_say_that",
            description = "Why you last spoke without being asked: use when the user asks why you " +
                "said or brought up something YOU started, not an answer to their question. Returns " +
                "the rule that fired and its fact; state those, never justify it or guess how they " +
                "felt. With stop, turns that spoken line off for good instead.",
            params = obj("stop" to schema("string", "Line to turn off, e.g. morning brief or home brief")),
            required = emptyList(),
        ))

        // The sitrep (ticket 22, `.scratch/hands-and-senses/issues/22-build-the-sitrep.md`,
        // building the decision at `08-morning-brief.md`). ONE tool, never six - ticket 08's
        // resolution §6 is explicit that a per-module tool would let the model choose which
        // sections to ask for on Kevin's behalf, the same "model decides what he doesn't hear"
        // failure the whole design avoids elsewhere. Askable at any hour by name - "sitrep" is
        // deliberately time-agnostic (see SitrepModule's own doc). **This is now the ONLY way a
        // sitrep is ever produced, alongside the Home card's tap** - ticket 32 (Kevin: "sitreps
        // stay tap only or via voice activation only") retired the scheduled alarm that used to
        // fire one unasked; there is no other half of this declaration anymore.
        // Ai-upgrade ticket 02: it now reads the DAILY BRIEF, the same content the Brief modal shows,
        // through the same BriefController (ADR 0035: the hands path and the voice path share it).
        add(fn(
            name = "get_sitrep",
            description = "Give the user their daily brief (also called the sitrep or morning " +
                "brief): the engine's brief - CANVAS tasks, MONEY, CAR and web NEWS, each with " +
                "its status - then CALENDAR and WEATHER read live on this phone, then " +
                "NEWSLETTERS when that module is on. **Use this for 'what's the news', 'what's " +
                "the news today', 'any news' too**, with modules=\"news\". Every sentence in the " +
                "brief was written by code from the household's records: say it as given, never " +
                "reword a figure, and say [unverified] lines are unverified. Web NEWS bullets " +
                "name their sources; say the source names, never read out a link. Under " +
                "NEWSLETTERS, NEWS is a model's SUMMARY of their newsletter mail and NEWS FEEDS " +
                "is verbatim RSS headlines - say which is which. When the result says it is the " +
                "saved copy, say so and how old it is. A section that failed or reports nothing " +
                "is said plainly. On success=false no brief was read: say that first, in the " +
                "result's words, then what the phone itself had. Never invent a detail that is " +
                "not in the result.",
            params = obj(
                "modules" to schema("string", "Optional: a comma-separated subset, e.g. " +
                    "\"calendar,weather\" or \"news\". news = the brief's web news plus " +
                    "newsletters, fleet = the brief's car section, calendar and weather = the " +
                    "phone's. With a filter, Canvas and money are left out. Omit for the whole " +
                    "brief. A phone module switched off in settings never appears."),
            ),
            required = listOf(),
        ))
    }
}
