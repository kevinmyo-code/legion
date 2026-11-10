package com.kevin.legion.navigation.audit

import com.kevin.legion.navigation.AvoidKind
import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.NavDestination
import com.kevin.legion.navigation.NavRouteInfo
import com.kevin.legion.navigation.RerouteStatus
import com.kevin.legion.navigation.RouteFailure
import com.kevin.legion.navigation.resolve.Candidate
import com.kevin.legion.navigation.resolve.Geo
import com.kevin.legion.navigation.resolve.SourceKind
import com.kevin.legion.service.LiveAudit
import java.util.Locale
import kotlin.math.roundToLong

/** Where a destination phrase came from: said to the assistant, typed in the nav screen, or a tapped chip. */
enum class SearchOrigin(val token: String) {
    VOICE("voice"),
    TYPED("typed"),
    TAP("tap"),
    UNKNOWN("unknown"),
}

/** How one resolver source answered, for the `search` line's `tried=` list. */
enum class SourceOutcome(val token: String) {
    HITS("hits"),
    NO_MATCH("no_match"),
    UNREADABLE("unreadable"),
    NO_ADDRESS("no_address"),
}

/** One source the resolver actually asked: how long it took and how many places it gave back. */
data class SourceTry(val kind: SourceKind, val ms: Long, val results: Int, val outcome: SourceOutcome)

/** The resolver's verdict on one phrase, as the `search` line names it. */
enum class SearchVerdict(val token: String) {
    RESOLVED("resolved"),
    AMBIGUOUS("ambiguous"),
    NOT_FOUND("not_found"),
    NO_ADDRESS("no_address"),
    EMPTY("empty"),
}

/** What the voice tool's place lookup decided (`resolve` line). */
enum class ResolveVerdict(val token: String) {
    GO("go"),
    ASK("ask"),
    NOT_FOUND("not_found"),
    CHOICE_OK("choice_ok"),
    CHOICE_OUT_OF_RANGE("choice_out_of_range"),
    CACHED("cached"),
}

/** Why a route request was made: a fresh preview, or a change to a preview or trip (a stop, an avoid). */
enum class RoutePurpose(val token: String) { PREVIEW("preview"), CHANGE("change") }

/** How a trip finished. Process death leaves no line at all; the report counts that trip as unknown. */
enum class TripEnd(val token: String) { ARRIVED("arrived"), CANCELLED("cancelled"), TOKEN("token") }

/** What happened to one turn cue. Counts only: the cue's text names a street, which is a place. */
enum class CueOutcome(val token: String) {
    SPOKEN("spoken"),
    MUTED("muted"),
    NO_ENGINE("no_engine"),
    REFUSED("refused"),
    SUPERSEDED("superseded"),
    CANCELLED("cancelled"),
}

/**
 * The navigation audit log (Kevin, 2026-11-06: *"do we have a way to audit what routes were called, its
 * performance etc? my goal is to have the search and route etc as good as googlemaps"*). One `NAV_AUDIT`
 * line per event, in `LIVE_AUDIT`'s shape: `<event> key=value ...`, the one free-text field LAST and
 * JSON-quoted through [LiveAudit.quote] (scrubbed of anything token-shaped, Mapbox's `pk.`/`sk.` included).
 * `tools/nav_audit_report.py` reads it, so change both together.
 *
 * Events: `search`, `pick`, `resolve`, `route_request`, `trip_start`, `route_update`, `reroute`,
 * `off_route`, `arrival`, `trip_end`. Cue outcomes are counted and ride on `trip_end`, never per cue.
 *
 * **What is never logged** (ToS 2.7.2 / 2.10.1, the rule `Candidate` and `NavDestination` carry): a
 * Mapbox search result's name or address. A `SEARCH` hit in `top=` is its rank, `mapbox_id` (ToS 2.7.6,
 * map decision D1), kind, category and distance only; a first-party hit (saved place, calendar,
 * suggestion, contact) carries its label, which is the user's own data, and no id. No `detail` (address
 * text) from any source, and no contact's or event's address in a `resolve` reason (D9). A destination's
 * coordinates are logged only for a saved or recent place, whose point is the household's own GPS fix (D2); a
 * search, calendar, suggestion or contact destination is a Mapbox geocode and logs `to=none`.
 * Coordinates are rounded to three decimals (about 110 m). Distance and ETA are logged knowingly against
 * 2.10.1 (D2, risk accepted in words). The query is the user's own words, which `LIVE_AUDIT` already logs.
 *
 * **Where it goes:** [sink] is `LiveLatency.audit` in production, so it follows the same two switches as
 * the conversation log (logcat mirror, and the spool uploaded to the household's own engine as
 * `reason=conversation`, CLAUDE.md sec 7). It is NOT gated on a Live session: a typed trip logs too.
 * [onTripEnd] asks the conversation log to upload, since a trip started by hand opens no Live session
 * and the session-end upload would never fire for it.
 *
 * Holds one trip's running numbers (start time, first ETA, reroutes, cue counts) so the controller only
 * reports events. Thread-safe: the resolver's coroutine and the SDK's main-thread callbacks both call in.
 */
@Suppress("TooManyFunctions") // one function per NAV_AUDIT event, the format's whole surface
class NavAudit(
    private val sink: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onTripEnd: () -> Unit = {},
) {
    private var seq = 0

    private class Trip(
        val id: String,
        val startedMs: Long,
        val firstEtaS: Double,
        var reroutes: Int = 0,
        var offRoutes: Int = 0,
        var offRoute: Boolean = false,
        var rerouteFetchStartMs: Long? = null,
        val cues: MutableMap<CueOutcome, Int> = mutableMapOf(),
    )

    private var trip: Trip? = null

    /** A fresh id: base36 wall clock plus a sequence digit, so ids stay distinct across a restart in one capture. */
    @Synchronized
    fun newId(prefix: String): String {
        val n = seq++ % ID_SEQ
        return prefix + nowMs().toString(ID_RADIX) + n.toString(ID_RADIX)
    }

    // ------------------------------------------------------------------ search and pick

    /** One resolver run. [top] is the merged ranking the caller would read back, at most [TOP_N] logged. */
    @Synchronized
    @Suppress("LongParameterList") // one line's fields
    fun search(
        id: String,
        origin: SearchOrigin,
        alongRoute: Boolean,
        verdict: SearchVerdict,
        source: SourceKind?,
        tried: List<SourceTry>,
        top: List<Candidate>,
        totalMs: Long,
        query: String,
    ) = emit(
        "search id=$id origin=${origin.token} along=$alongRoute verdict=${verdict.token} " +
            "source=${source?.let(::sourceToken) ?: "none"} results=${top.size} ms=$totalMs " +
            "tried=${triedToken(tried)} top=${topJson(top)} query=${LiveAudit.quote(query)}",
    )

    /**
     * The user chose among several places a [search] offered. [rank] is 1-based in the order read out;
     * 0 with [how] `dismissed` means they backed out. An unambiguous search is an implicit rank 1 and logs
     * no pick line.
     */
    @Synchronized
    fun pick(searchId: String?, rank: Int, how: String, source: SourceKind?) = emit(
        "pick search=${searchId ?: "none"} rank=$rank how=${LiveAudit.token(how)} " +
            "source=${source?.let(::sourceToken) ?: "none"}",
    )

    /**
     * The voice tool's lookup of one spoken place. [reason] is said only when it does not go (a not-found
     * sentence or a fixed word); never the read-back sentence, which carries Mapbox result names.
     */
    @Synchronized
    @Suppress("LongParameterList") // one line's fields
    fun resolve(
        tool: String,
        slot: String,
        verdict: ResolveVerdict,
        searchId: String?,
        candidates: Int,
        choice: Int?,
        source: SourceKind?,
        reason: String?,
    ) = emit(
        "resolve tool=${LiveAudit.token(tool)} slot=${LiveAudit.token(slot)} verdict=${verdict.token} " +
            "search=${searchId ?: "none"} candidates=$candidates choice=${choice ?: "none"} " +
            "source=${source?.let(::sourceToken) ?: "none"} reason=${LiveAudit.quote(reason.orEmpty())}",
    )

    // ------------------------------------------------------------------ routes

    /** The answer to one Directions request, whatever it was. [routes] empty on a failure. */
    @Synchronized
    @Suppress("LongParameterList") // one line's fields
    fun routeRequest(
        id: String,
        purpose: RoutePurpose,
        result: String,
        failure: RouteFailure?,
        ms: Long,
        routes: List<NavRouteInfo>,
        router: String?,
        origin: GeoPoint,
        destination: NavDestination,
        stops: Int,
        avoid: Set<AvoidKind>,
        message: String?,
    ) {
        val first = routes.firstOrNull()
        val profile = first?.profile
        emit(
            "route_request id=$id purpose=${purpose.token} trip=${trip?.id ?: "none"} " +
                "result=${LiveAudit.token(result)} " +
                "fail=${failure?.name?.lowercase() ?: "none"} ms=$ms routes=${routes.size} " +
                "alternatives=${(routes.size - 1).coerceAtLeast(0)} " +
                "distance_m=${first?.distanceM?.let(::whole) ?: "none"} " +
                "eta_s=${first?.durationS?.let(::whole) ?: "none"} " +
                "typical_s=${first?.typicalDurationS?.let(::whole) ?: "none"} " +
                "profile=${LiveAudit.token(profile ?: "none")} traffic=${trafficToken(profile)} " +
                "router=${LiveAudit.token(router?.lowercase() ?: "none")} stops=$stops avoid=${avoidToken(avoid)} " +
                "from=${point(origin)} to=${destinationPoint(destination)} " +
                "to_src=${LiveAudit.token(destination.source ?: "unknown")} " +
                "to_id=${destinationId(destination)} " +
                "crow_m=${whole(Geo.distanceM(origin, destination.point))} " +
                "message=${LiveAudit.quote(message.orEmpty())}",
        )
    }

    // ------------------------------------------------------------------ the trip

    /** Guidance started on [primary]. Starts the trip's running numbers; the first ETA is what arrival is judged by. */
    @Synchronized
    fun tripStarted(routeId: String?, primary: NavRouteInfo, routeIndex: Int, stops: Int, avoid: Set<AvoidKind>) {
        val t = Trip(newId("t"), nowMs(), primary.durationS)
        trip = t
        emit(
            "trip_start trip=${t.id} route=${routeId ?: "none"} route_index=$routeIndex " +
                "eta_s=${whole(primary.durationS)} distance_m=${whole(primary.distanceM)} " +
                "typical_s=${primary.typicalDurationS?.let(::whole) ?: "none"} " +
                "stops=$stops avoid=${avoidToken(avoid)} " +
                "profile=${LiveAudit.token(primary.profile ?: "none")} traffic=${trafficToken(primary.profile)}",
        )
    }

    /** Guidance did not start on a ready preview. No trip exists. */
    @Synchronized
    fun tripStartFailed(why: String) = emit("trip_start trip=none result=failed message=${LiveAudit.quote(why)}")

    /** The navigator's routes changed. [reason] is the SDK's (`reroute`, `refresh`, `alternative`, `new`). */
    @Synchronized
    fun routesUpdated(reason: String?, primary: NavRouteInfo, count: Int) {
        val t = trip ?: return
        emit(
            "route_update trip=${t.id} reason=${LiveAudit.token(reason ?: "unknown")} routes=$count " +
                "eta_s=${whole(primary.durationS)} distance_m=${whole(primary.distanceM)} " +
                "elapsed_s=${(nowMs() - t.startedMs) / MS_PER_S}",
        )
    }

    /** The SDK's reroute state. A fetch after an off-route is `reason=off_route`; any other is `other`. */
    @Synchronized
    fun reroute(state: RerouteStatus, message: String?) {
        val t = trip ?: return
        when (state) {
            RerouteStatus.FETCHING -> {
                t.reroutes++
                t.rerouteFetchStartMs = nowMs()
                emit("reroute trip=${t.id} state=fetching reason=${if (t.offRoute) "off_route" else "other"}")
            }
            RerouteStatus.FETCHED, RerouteStatus.FAILED, RerouteStatus.INTERRUPTED -> {
                val ms = t.rerouteFetchStartMs?.let { nowMs() - it }
                t.rerouteFetchStartMs = null
                emit(
                    "reroute trip=${t.id} state=${state.name.lowercase()} ms=${ms ?: "none"} " +
                        "message=${LiveAudit.quote(message.orEmpty())}",
                )
            }
            RerouteStatus.IDLE -> Unit
        }
    }

    /** The SDK's off-route flag flipped. Only a change is logged. */
    @Synchronized
    fun offRoute(offRoute: Boolean) {
        val t = trip ?: return
        if (offRoute == t.offRoute) return
        t.offRoute = offRoute
        if (offRoute) t.offRoutes++
        emit("off_route trip=${t.id} state=$offRoute elapsed_s=${(nowMs() - t.startedMs) / MS_PER_S}")
    }

    /** One turn cue's fate, counted toward the trip's `trip_end` line. */
    @Synchronized
    fun cue(outcome: CueOutcome) {
        val t = trip ?: return
        t.cues[outcome] = (t.cues[outcome] ?: 0) + 1
    }

    /** Arrived: actual elapsed time against the first ETA, then the trip's end. */
    @Synchronized
    fun arrived() {
        val t = trip ?: return
        val elapsedS = (nowMs() - t.startedMs) / MS_PER_S
        val errorS = elapsedS - t.firstEtaS.roundToLong()
        val pct = if (t.firstEtaS > 0) String.format(Locale.US, "%.1f", errorS * PERCENT / t.firstEtaS) else "none"
        emit(
            "arrival trip=${t.id} elapsed_s=$elapsedS first_eta_s=${whole(t.firstEtaS)} error_s=$errorS error_pct=$pct",
        )
        tripEnded(TripEnd.ARRIVED, distanceLeftM = null)
    }

    /** The trip finished ([reason]). A no-op with no trip, so a second ending path cannot double the line. */
    @Synchronized
    fun tripEnded(reason: TripEnd, distanceLeftM: Double?) {
        val t = trip ?: return
        trip = null
        val spoken = t.cues[CueOutcome.SPOKEN] ?: 0
        val drops = t.cues.filterKeys { it != CueOutcome.SPOKEN }
        val dropList = drops.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.token}:${it.value}" }
        emit(
            "trip_end trip=${t.id} reason=${reason.token} elapsed_s=${(nowMs() - t.startedMs) / MS_PER_S} " +
                "distance_left_m=${distanceLeftM?.let(::whole) ?: "none"} " +
                "reroutes=${t.reroutes} off_routes=${t.offRoutes} " +
                "cues_spoken=$spoken cues_dropped=${drops.values.sum()} cue_drops=${dropList.ifEmpty { "none" }}",
        )
        onTripEnd()
    }

    /** The trip in progress, for a route request made during it; null with none. */
    @Synchronized
    fun currentTripId(): String? = trip?.id

    private fun emit(line: String) = sink(line)

    companion object {
        const val TAG = "NAV_AUDIT"
        const val TOP_N = 5
        private const val ID_SEQ = 36
        private const val ID_RADIX = 36
        private const val MS_PER_S = 1000L
        private const val PERCENT = 100.0
        private const val MAX_NAME = 40
        private const val ROUND_DISTANCE_M = 10.0
        const val TRAFFIC_PROFILE = "driving-traffic"

        /** Writes nothing: the default for tests and for a class built without the app's audit. */
        val OFF = NavAudit(sink = {})

        fun sourceToken(kind: SourceKind): String = kind.name.lowercase()

        /** Rounded to three decimals (about 110 m), latitude first, never more precise. */
        fun point(p: GeoPoint): String = String.format(Locale.US, "%.3f,%.3f", p.latitude, p.longitude)

        /**
         * The destination's rounded point only when it is first-party: a saved place or a recent place,
         * both the household's own GPS fix (map decision D2). Every other source's point is a Mapbox
         * geocode (ToS 2.7.2), and a contact's is a third party's address (D9): `none`.
         */
        fun destinationPoint(d: NavDestination): String =
            if (d.source in FIRST_PARTY_POINTS) point(d.point) else "none"

        private val FIRST_PARTY_POINTS = setOf(sourceToken(SourceKind.SAVED_PLACE), sourceToken(SourceKind.RECENT))

        /** A search destination's `mapbox_id` (ToS 2.7.6, D1), so the route can be tied to the pick; else `none`. */
        fun destinationId(d: NavDestination): String =
            d.mapboxId?.takeIf { d.source == sourceToken(SourceKind.SEARCH) }?.let(LiveAudit::token) ?: "none"

        /** `true` only when the route read back says it was planned on live traffic; `unknown` with no profile. */
        fun trafficToken(profile: String?): String = when {
            profile == null -> "unknown"
            profile.endsWith(TRAFFIC_PROFILE) -> "true"
            else -> "false"
        }

        fun avoidToken(avoid: Set<AvoidKind>): String =
            avoid.map { it.excludeValue }.sorted().joinToString(",").ifEmpty { "none" }

        /** `saved_place:3:1:hits,search:812:5:hits` (kind, ms, results, outcome), or `none`. */
        fun triedToken(tried: List<SourceTry>): String =
            tried.joinToString(",") { "${sourceToken(it.kind)}:${it.ms}:${it.results}:${it.outcome.token}" }
                .ifEmpty { "none" }

        /**
         * The top [TOP_N] as compact JSON, no whitespace. A Mapbox `SEARCH` hit has NO name (ToS 2.7.2)
         * and carries its `mapbox_id` as `id` when the SDK gave one (ToS 2.7.6, D1); a first-party hit
         * carries its label and never an id. No address text from any source. Distance to 10 m.
         */
        fun topJson(top: List<Candidate>): String = top.take(TOP_N).mapIndexed { i, c ->
            val fields = buildList {
                add("\"r\":${i + 1}")
                add("\"src\":\"${sourceToken(c.source)}\"")
                if (c.source == SourceKind.SEARCH) {
                    c.mapboxId?.let { add("\"id\":${jsonString(it)}") }
                } else {
                    add("\"name\":${jsonString(c.name.take(MAX_NAME))}")
                }
                add("\"kind\":\"${c.kind.name.lowercase()}\"")
                c.category?.let { add("\"cat\":${jsonString(it.take(MAX_NAME))}") }
                val metres = c.distanceM?.let { whole((it / ROUND_DISTANCE_M).roundToLong() * ROUND_DISTANCE_M) }
                add("\"m\":${metres ?: "null"}")
                // Drive minutes when asked for ("nearest", ticket 03); kept with the risk accepted (D2).
                c.etaMinutes?.let { add("\"eta_min\":${it.roundToLong()}") }
            }
            "{" + fields.joinToString(",") + "}"
        }.joinToString(",", prefix = "[", postfix = "]")

        /** A JSON string with no whitespace that could split the token: spaces become ` `. */
        fun jsonString(s: String): String = LiveAudit.quote(s).replace(" ", "\\u0020")

        private fun whole(v: Double): String = v.roundToLong().toString()
    }
}
