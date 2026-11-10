@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.nav

import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import org.json.JSONObject

/**
 * The nav tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
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
            name = "navigate",
            description = "Start turn-by-turn navigation in LEGION's own map ('take me to', 'directions " +
                "to', 'nearest gas station'), or with preview=true only show the routes. Looks in " +
                "saved places, calendar and contacts, then the map. Say navigation started, or give " +
                "an arrival time, only if the result says so; on failure nothing is navigating, say " +
                "why. If the result says several places match, NOTHING started: read the top pick " +
                "back with its distance, wait for a yes, then call again with the same destination " +
                "and choice=1 (choice=N for another, in the order read). Refuses while a trip " +
                "runs: use change_trip or end_trip. If the map could not be brought up, say so; " +
                "guidance still runs.",
            params = obj(
                "destination" to schema("string",
                    "Where to, in the user's words, keeping 'nearest' or 'closest': saved place, " +
                        "event, contact, address, business."),
                "via" to schema("string", "Optional stop on the way."),
                "avoid" to schema("string", "Optional: tolls, highways, ferries (comma separated)."),
                "preview" to schema("boolean", "True to only show the routes, not start."),
                "choice" to schema("integer",
                    "Only after the user answered 'several places match': 1 is the top pick."),
            ),
            required = listOf("destination"),
        ))

        add(fn(
            name = "change_trip",
            description = "Change the running or previewed trip: add_stop, remove_stop, avoid, " +
                "take_alternative, mute/unmute (spoken TURN cues only, never you), overview, " +
                "recenter. With no trip, say nothing is navigating. If add_stop " +
                "says several places match, read the top pick back, wait, then call again with choice.",
            params = obj(
                "action" to schema("string", "What to do.",
                    enum = listOf("add_stop", "remove_stop", "avoid", "take_alternative", "mute",
                        "unmute", "overview", "recenter")),
                "place" to schema("string", "add_stop: where. remove_stop: part of the stop's name."),
                "avoid" to schema("string", "avoid: tolls, highways, ferries, or none to allow all."),
                "route" to schema("integer",
                    "take_alternative: the number in the order the routes were read out; 1 is in use."),
                "choice" to schema("integer", "Only after 'several places match' for add_stop."),
            ),
            required = listOf("action"),
        ))

        add(fn(
            name = "trip_status",
            description = "Read the running trip: time_left, distance_left, arrival, next_turn, road, " +
                "speed_limit, traffic. If not navigating, or a value is unknown, say exactly that; " +
                "never invent a figure.",
            params = obj(
                "ask" to schema("string", "What to read.",
                    enum = listOf("time_left", "distance_left", "arrival", "next_turn", "road",
                        "speed_limit", "traffic")),
            ),
            required = listOf("ask"),
        ))

        add(fn(
            name = "end_trip",
            description = "End the running trip or clear a route preview. Say it ended only if the " +
                "result says so.",
            params = obj(),
            required = emptyList(),
        ))
    }
}
