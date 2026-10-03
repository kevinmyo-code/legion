package com.kevin.legion.navigation.voice

import com.kevin.legion.navigation.AvoidKind
import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.GuidanceSnapshot
import com.kevin.legion.navigation.MapboxNavController
import com.kevin.legion.navigation.NavDestination
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.NavPhase
import com.kevin.legion.navigation.NavResult
import com.kevin.legion.navigation.TripStatus
import com.kevin.legion.navigation.resolve.Candidate
import com.kevin.legion.navigation.resolve.DestinationResolver
import com.kevin.legion.navigation.resolve.LookupContext
import com.kevin.legion.navigation.resolve.QueryText
import com.kevin.legion.navigation.resolve.Resolution

/**
 * What a navigation voice tool hands back. [success] follows the honesty table of ticket 04: for a
 * mutation it is true only when the SDK's own state, read after the call, shows the change; a
 * refusal, a "needs a yes" and a failure are all false and say in words what did NOT happen.
 * [message] is safe to read aloud.
 */
data class NavToolResult(val success: Boolean, val message: String)

/** Brings the nav screen to the front. Returns whether it is actually showing afterwards. */
fun interface NavScreenLauncher {
    suspend fun bringForward(): Boolean
}

/**
 * The four voice tools over the live trip (mapbox-nav ticket 04, built in ticket 11):
 * `navigate`, `change_trip`, `trip_status`, `end_trip`. **Thin wrappers**: each calls the same
 * [MapboxNavController] verb the nav screen's tiles call (ADR 0035's hands path), after resolving
 * any spoken place through the same [DestinationResolver] (ticket 03). Every `success` comes from
 * the controller's [NavResult], which reads the SDK after the call; nothing here infers an outcome
 * from a call having been made (CLAUDE.md sec 7, ADR 0031).
 *
 * **Several plausible places is not a start.** [navigate] and the `add_stop` action then change
 * nothing and return the top pick with its distance and the others, for the assistant to read back
 * and wait on. The user's yes is a second call with the same words and `choice` (1 is the top pick,
 * the others are numbered in the order read out). The candidates are held here in memory only for
 * [PENDING_TTL_MS]: they are Mapbox search results (ToS 2.7.2 / 2.10.1) and are never written down.
 *
 * Main-thread only, like the controller; the toolbox switches to Main before calling in.
 */
// One method per voice verb plus the small parsers each needs; splitting would scatter the table.
@Suppress("TooManyFunctions")
class NavVoiceTools(
    private val controller: MapboxNavController,
    private val resolver: DestinationResolver,
    private val fix: () -> GeoPoint?,
    private val screen: NavScreenLauncher,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private enum class Slot { DESTINATION, VIA, STOP }

    private class Pending(
        val slot: Slot,
        val key: String,
        val candidates: List<Candidate>,
        val at: Long,
    )

    private sealed interface Pick {
        data class Chosen(val destination: NavDestination, val notes: List<String>) : Pick

        /** Either "several match, ask" or "not found": nothing may start. */
        data class Stop(val result: NavToolResult) : Pick
    }

    private var pending: Pending? = null

    /** Places already confirmed within one multi-step request (a destination, then a via), by slot. */
    private val confirmed = mutableMapOf<Slot, Triple<String, NavDestination, Long>>()

    // ------------------------------------------------------------------ navigate

    /**
     * `navigate(destination, via?, avoid?, preview?, choice?)`. Starts a trip, or with [preview]
     * only shows the routes. Success without [preview] means a route is set AND the trip session is
     * running; with it, routes came back and nothing started.
     */
    @Suppress("ReturnCount") // each early return is one refusal with its own sentence
    suspend fun navigate(
        destination: String?,
        via: String? = null,
        avoid: String? = null,
        preview: Boolean = false,
        choice: Int? = null,
    ): NavToolResult {
        val to = destination?.trim().orEmpty()
        if (to.isEmpty()) return fail("I need to know where to go. Nothing has started.")
        val avoided = parseAvoid(avoid) ?: return fail(badAvoid(avoid))
        val phase = controller.state.value.phase
        if (phase == NavPhase.GUIDING) {
            val going = controller.state.value.destination?.name
            return fail(
                "Already navigating to $going, so nothing new was started. To change that trip use " +
                    "change_trip, or end_trip first.",
            )
        }
        val context = LookupContext(fix())
        val dest = when (val p = pick(Slot.DESTINATION, to, choice, context)) {
            is Pick.Stop -> return p.result
            is Pick.Chosen -> p
        }
        val stop = via?.trim()?.takeIf { it.isNotEmpty() }?.let {
            when (val p = pick(Slot.VIA, it, choice, context)) {
                is Pick.Stop -> return p.result
                is Pick.Chosen -> p
            }
        }
        confirmed.clear()
        val r = controller.navigate(dest.destination, stop?.destination, avoided, previewOnly = preview)
        val running = controller.state.value.phase == NavPhase.GUIDING
        val ok = r.ok && (preview || running)
        if (!ok) return NavToolResult(false, r.message)
        val where = destinationDetail(dest.destination)
        val notes = (dest.notes + stop?.notes.orEmpty()).joinToString(" ")
        val shown = screen.bringForward()
        val map = if (shown) "" else " $MAP_NOT_SHOWN"
        return NavToolResult(true, listOf(r.message + where, notes).filter { it.isNotBlank() }.joinToString(" ") + map)
    }

    private fun destinationDetail(d: NavDestination): String =
        d.detail?.takeIf { it.isNotBlank() && !d.name.contains(it) }?.let { " That is $it." }.orEmpty()

    // ------------------------------------------------------------------ change_trip

    /**
     * `change_trip(action, place?, avoid?, route?, choice?)`. One of [CHANGE_ACTIONS]. Success only
     * when the trip, as the SDK now holds it, reflects the change; a refused change says the trip is
     * unchanged.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one branch per action; each is one controller call
    suspend fun changeTrip(
        action: String?,
        place: String? = null,
        avoid: String? = null,
        route: Int? = null,
        choice: Int? = null,
    ): NavToolResult = when (action?.trim()?.lowercase()) {
        "add_stop" -> addStop(place, choice)
        "remove_stop" -> controller.removeStop(place?.trim()?.takeIf { it.isNotEmpty() }).toTool()
        "avoid" -> avoidChange(avoid)
        "take_alternative" -> takeAlternative(route)
        "mute" -> controller.setMuted(true).toTool(" Turn cues only: the assistant still talks.")
        "unmute" -> controller.setMuted(false).toTool()
        "overview" -> withMap(controller.overview())
        "recenter" -> withMap(controller.recenter())
        else -> fail(
            "Unknown change \"${action.orEmpty()}\". Nothing changed. Use one of: ${CHANGE_ACTIONS.joinToString()}.",
        )
    }

    private fun notChangeable(): NavToolResult? {
        val phase = controller.state.value.phase
        if (phase == NavPhase.GUIDING || phase == NavPhase.PREVIEW) return null
        return fail("There is no trip or route preview to change. ${NavFormat.NOTHING_NAVIGATING}")
    }

    @Suppress("ReturnCount") // each early return is one refusal with its own sentence
    private suspend fun addStop(place: String?, choice: Int?): NavToolResult {
        notChangeable()?.let { return it }
        val phrase = place?.trim().orEmpty()
        if (phrase.isEmpty()) return fail("I need to know where to stop. The trip is unchanged.")
        val ctx = LookupContext(fix(), controller.primaryGeometry().takeIf { it.isNotEmpty() })
        val stop = when (val p = pick(Slot.STOP, phrase, choice, ctx, "The trip is unchanged.")) {
            is Pick.Stop -> return p.result
            is Pick.Chosen -> p
        }
        confirmed.clear()
        val r = controller.addStop(stop.destination)
        return r.toTool(if (r.ok) destinationDetail(stop.destination) else "")
    }

    @Suppress("ReturnCount") // each early return is one refusal with its own sentence
    private suspend fun avoidChange(avoid: String?): NavToolResult {
        notChangeable()?.let { return it }
        val named = parseAvoid(avoid)
        if (named == null || avoid.isNullOrBlank()) return fail(badAvoid(avoid))
        // Only "none" (nothing named) clears; "no tolls" names tolls and so adds them.
        val target = if (named.isEmpty()) emptySet() else controller.state.value.avoid + named
        return controller.setAvoid(target).toTool()
    }

    @Suppress("ReturnCount") // each early return is one refusal with its own sentence
    private suspend fun takeAlternative(route: Int?): NavToolResult {
        notChangeable()?.let { return it }
        val s = controller.state.value
        val inUse = if (s.phase == NavPhase.PREVIEW) s.selectedRoute else 0
        val number = route ?: s.routes.indices.singleOrNull { it != inUse }?.plus(1)
        if (number == null) {
            val labels = NavFormat.routeLabels(s.routes)
            val list = s.routes.indices.joinToString("; ") { "${it + 1}: ${labels[it]}" }
            return fail("Which route? Say its number. Routes: $list. The trip is unchanged.")
        }
        return controller.takeAlternative(number - 1).toTool()
    }

    // ------------------------------------------------------------------ trip_status

    /**
     * `trip_status(ask)`: read-only, from the latest route progress. With no trip it says it is not
     * navigating, never zeros; a value the SDK does not have is said as unknown.
     */
    fun tripStatus(ask: String?): NavToolResult {
        val what = ask?.trim()?.lowercase().orEmpty()
        if (what !in STATUS_ASKS) {
            return fail("Unknown question \"${ask.orEmpty()}\". Use one of: ${STATUS_ASKS.joinToString()}.")
        }
        return when (val st = controller.status()) {
            is TripStatus.NotNavigating -> NavToolResult(true, st.message)
            is TripStatus.Navigating -> NavToolResult(true, answer(what, st.destination.name, st.guidance))
        }
    }

    // A flat table of one sentence per question; branching here is the table, not logic.
    @Suppress("CyclomaticComplexMethod")
    private fun answer(what: String, to: String, g: GuidanceSnapshot): String = when (what) {
        "time_left" -> g.durationLeftS?.let { "${NavFormat.duration(it)} left to $to." } ?: UNKNOWN_TIME
        "distance_left" -> g.distanceLeftM?.let { "${NavFormat.distance(it)} to go to $to." } ?: UNKNOWN_DISTANCE
        "arrival" -> g.arrivalAtMs?.let { "Arriving at $to around ${NavFormat.arrivalClock(it)}." } ?: UNKNOWN_ARRIVAL
        "next_turn" -> nextTurn(g)
        "road" -> g.road?.let { "You are on $it." } ?: "I don't have the current road yet."
        "speed_limit" -> NavFormat.speedLimit(g.speedLimit)?.let { "The posted limit here is $it." }
            ?: "I don't know the speed limit here; the map has none posted for this road."
        else -> g.traffic ?: "I have no traffic information for this route right now."
    }

    private fun nextTurn(g: GuidanceSnapshot): String {
        val turn = g.turn ?: return "I don't have the next turn yet."
        val far = turn.distanceM?.let { " in ${NavFormat.distance(it)}" }.orEmpty()
        val then = g.then?.let { " Then: $it." }.orEmpty()
        return "Next: ${turn.text}$far.$then"
    }

    // ------------------------------------------------------------------ end_trip

    /** `end_trip()`. Success on a running trip only when the SDK confirms the session stopped. */
    fun endTrip(): NavToolResult = controller.end().toTool()

    // ------------------------------------------------------------------ helpers

    private suspend fun withMap(r: NavResult): NavToolResult {
        if (!r.ok) return r.toTool()
        val map = if (screen.bringForward()) "" else " $MAP_NOT_SHOWN"
        return NavToolResult(true, r.message + map)
    }

    /** Resolves one spoken place, or says why nothing may proceed. See the class doc on [choice]. */
    @Suppress("ReturnCount") // each return is one verdict
    private suspend fun pick(
        slot: Slot,
        phrase: String,
        choice: Int?,
        ctx: LookupContext,
        unchanged: String = "Nothing has started.",
    ): Pick {
        val key = QueryText.normalize(phrase)
        val now = nowMs()
        confirmed[slot]?.takeIf { it.first == key && now - it.third < PENDING_TTL_MS }?.let {
            return Pick.Chosen(it.second, emptyList())
        }
        val held = pending?.takeIf { it.slot == slot && it.key == key && now - it.at < PENDING_TTL_MS }
        if (choice != null && held != null) {
            val c = held.candidates.getOrNull(choice - 1)
                ?: return Pick.Stop(fail("There is no option $choice; there are ${held.candidates.size}. $unchanged"))
            pending = null
            confirmed[slot] = Triple(key, c.toDestination(), now)
            return Pick.Chosen(c.toDestination(), emptyList())
        }
        return when (val r = resolver.resolve(phrase, ctx)) {
            is Resolution.NotFound -> Pick.Stop(fail("${r.message} $unchanged"))
            is Resolution.Resolved -> if (r.ambiguous) {
                pending = Pending(slot, key, r.candidates, now)
                Pick.Stop(fail(askSentence(r, unchanged)))
            } else {
                confirmed[slot] = Triple(key, r.destination, now)
                Pick.Chosen(r.destination, r.notes)
            }
        }
    }

    private fun askSentence(r: Resolution.Resolved, unchanged: String) =
        "$unchanged ${r.sentence()} Read the top pick back with its distance and wait for the answer. " +
            "On a yes, call again with the same arguments and choice=1; if the user picks another, use " +
            "its number in the order read out."

    /** The kinds named, or null when a word is neither a kind nor filler ("potholes"): refused, not guessed. */
    private fun parseAvoid(avoid: String?): Set<AvoidKind>? {
        val words = avoid.orEmpty().split(SPLIT).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val kinds = words.mapNotNull {
            when (it) {
                in TOLL_WORDS -> AvoidKind.TOLLS
                in HIGHWAY_WORDS -> AvoidKind.HIGHWAYS
                in FERRY_WORDS -> AvoidKind.FERRIES
                else -> null
            }
        }.toSet()
        val understood = words.all { it in TOLL_WORDS || it in HIGHWAY_WORDS || it in FERRY_WORDS || it in IGNORED }
        return kinds.takeIf { understood }
    }

    private fun badAvoid(avoid: String?) =
        "I can avoid tolls, highways or ferries; \"${avoid.orEmpty()}\" is not one of those. Nothing changed."

    private fun fail(message: String) = NavToolResult(false, message)

    private fun NavResult.toTool(suffix: String = "") = NavToolResult(ok, message + suffix)

    companion object {
        /** The `action` values of `change_trip`, in the order the declaration lists them. */
        val CHANGE_ACTIONS = listOf(
            "add_stop", "remove_stop", "avoid", "take_alternative", "mute", "unmute", "overview", "recenter",
        )

        /** The `ask` values of `trip_status`. */
        val STATUS_ASKS = listOf(
            "time_left", "distance_left", "arrival", "next_turn", "road", "speed_limit", "traffic",
        )

        /** How long a read-back waits for its yes before the candidates are forgotten. */
        const val PENDING_TTL_MS = 5 * 60_000L

        const val MAP_NOT_SHOWN = "The map could not be brought up, but guidance is running; the user can open " +
            "LEGION to see it."

        private const val UNKNOWN_TIME = "I don't have the time left yet."
        private const val UNKNOWN_DISTANCE = "I don't have the distance left yet."
        private const val UNKNOWN_ARRIVAL = "I don't have an arrival time yet."

        private val SPLIT = Regex("[,;/]|\\band\\b|\\s+")
        private val TOLL_WORDS = setOf("toll", "tolls", "tollway", "tollways")
        private val HIGHWAY_WORDS = setOf("highway", "highways", "motorway", "motorways", "freeway", "freeways")
        private val FERRY_WORDS = setOf("ferry", "ferries")

        /** Said around a kind or instead of one ("none"); understood, and names nothing to avoid. */
        private val IGNORED = setOf("none", "nothing", "no", "road", "roads", "the", "any", "all", "of")
    }
}
