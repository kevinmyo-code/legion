package com.kevin.legion.navigation.resolve

import android.content.Context
import android.content.SharedPreferences
import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.NavDestination
import com.kevin.legion.navigation.audit.NavAudit
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * One place the user drove to and arrived at (nav-quality ticket 13, Kevin's decision D4, 2026-11-06).
 * [phrase] is the user's own words, normalised ([QueryText.normalize]); the point is the phone's own GPS
 * fix at arrival. **Never a Mapbox result's name, address, coordinates or id** (ToS 2.7.2): the arrival
 * fix is first-party, like `tag_place`.
 */
data class RecentPlace(
    val phrase: String,
    val latitude: Double,
    val longitude: Double,
    val count: Int,
    val lastMs: Long,
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)
}

/** Where the rows live, as one text blob; a fake in tests, [PrefsRecentPlacesIo] on the phone. */
interface RecentPlacesIo {
    fun read(): String?

    fun write(text: String?)
}

/**
 * The phone's recent and frequent destinations. One row per phrase, kept only on an arrival at a place
 * search found (a saved place is already known; "my next appointment" from the calendar names a
 * different place each time). Private to the phone's member: it never leaves the phone.
 *
 * A repeat arrival within [MERGE_M] of the stored fix bumps the count and moves the fix to the newest
 * one; farther away, the phrase now means the new place and the count starts again. Capped at
 * [MAX_ROWS], the lowest [score] dropped first. [forget] deletes every row.
 */
class RecentPlaces(private val io: RecentPlacesIo, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    /** Every row, best [score] first. An unreadable blob reads as none, never as a crash. */
    fun all(): List<RecentPlace> = synchronized(lock) { decode(io.read()).sortedByDescending { score(it) } }

    /**
     * The arrival hook: keeps [destination] only when search found it, the user's words are known and
     * were not a "nearest" ask, at [fix], the phone's own position on arrival. Returns whether a row
     * was written.
     */
    fun arrived(destination: NavDestination?, fix: GeoPoint?): Boolean {
        val phrase = destination
            ?.takeIf { it.source == NavAudit.sourceToken(SourceKind.SEARCH) }
            ?.asked
            // "Nearest gas station" means the closest from wherever the user is, not this one place.
            ?.takeIf { it.isNotBlank() && !SearchPhrase.parse(it).nearest }
        if (phrase == null || fix == null) return false
        record(phrase, fix)
        return true
    }

    fun record(phrase: String, fix: GeoPoint) = synchronized(lock) {
        val key = QueryText.normalize(phrase)
        if (key.isEmpty()) return@synchronized
        val now = nowMs()
        val rows = decode(io.read()).toMutableList()
        val old = rows.firstOrNull { it.phrase == key }
        rows.remove(old)
        val sameSpot = old != null && Geo.distanceM(old.point, fix) <= MERGE_M
        rows += RecentPlace(key, fix.latitude, fix.longitude, if (sameSpot) old!!.count + 1 else 1, now)
        io.write(encode(rows.sortedByDescending { score(it, now) }.take(MAX_ROWS)))
    }

    /** "Forget my recent places": every row gone, at once. */
    fun forget() = synchronized(lock) { io.write(null) }

    /** Frequency weighted by recency: each arrival counts, halved for every [HALF_LIFE_DAYS] since the last. */
    fun score(p: RecentPlace, now: Long = nowMs()): Double {
        val days = (now - p.lastMs).coerceAtLeast(0L) / MS_PER_DAY
        return p.count * exp(-ln(2.0) * days / HALF_LIFE_DAYS)
    }

    companion object {
        const val MERGE_M = 150.0
        const val MAX_ROWS = 50
        const val HALF_LIFE_DAYS = 30.0
        private const val MS_PER_DAY = 86_400_000.0
        private const val FIELDS = 5
        private const val TAB = '\u0009'
        private const val PHRASE = 0
        private const val LAT = 1
        private const val LNG = 2
        private const val COUNT = 3
        private const val LAST = 4

        /** One row per line, tab-separated. A normalised phrase holds only `[a-z0-9' ]`, so no tab or newline. */
        fun encode(rows: List<RecentPlace>): String = rows.joinToString("\n") {
            String.format(
                Locale.US, "%s\t%.6f\t%.6f\t%d\t%d", it.phrase, it.latitude, it.longitude, it.count, it.lastMs,
            )
        }

        fun decode(text: String?): List<RecentPlace> = text.orEmpty().lines().mapNotNull(::row)

        private fun row(line: String): RecentPlace? {
            val f = line.split(TAB)
            if (f.size != FIELDS || f[PHRASE].isBlank()) return null
            return runCatching {
                RecentPlace(f[PHRASE], f[LAT].toDouble(), f[LNG].toDouble(), f[COUNT].toInt(), f[LAST].toLong())
            }.getOrNull()
        }
    }
}

/** [RecentPlacesIo] on the phone's own private prefs. */
class PrefsRecentPlacesIo(private val prefs: SharedPreferences) : RecentPlacesIo {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(text: String?) {
        prefs.edit().apply { if (text == null) remove(KEY) else putString(KEY, text) }.apply()
    }

    private companion object {
        const val PREFS = "nav_recent_places"
        const val KEY = "rows"
    }
}

/**
 * Recent and frequent destinations as a resolver source (ticket 13), tried after saved places and
 * before the calendar. Matches like saved places: an exact phrase wins outright; several that merely
 * contain (or are contained in) the phrase are ambiguous and asked, best [RecentPlaces.score] first.
 */
class RecentSource(private val recents: RecentPlaces) : DestinationSource {
    override val kind = SourceKind.RECENT

    @Suppress("ReturnCount") // guard clauses: empty phrase, no match, then the answer
    override suspend fun lookup(query: String, ctx: LookupContext): SourceAnswer {
        val q = QueryText.normalize(query)
        if (q.isEmpty()) return SourceAnswer.NoMatch
        val rows = recents.all()
        val exact = rows.filter { it.phrase == q }
        val matched = exact.ifEmpty {
            rows.filter { it.phrase.length >= MIN_PARTIAL && (it.phrase.contains(q) || q.contains(it.phrase)) }
        }
        if (matched.isEmpty()) return SourceAnswer.NoMatch
        val candidates = matched.map { p ->
            Candidate(
                name = p.phrase,
                detail = "recent place",
                latitude = p.latitude,
                longitude = p.longitude,
                distanceM = ctx.fix?.let { Geo.distanceM(it, p.point) },
                source = kind,
            )
        }
        return SourceAnswer.Hits(candidates, ambiguous = candidates.size > 1)
    }

    private companion object {
        const val MIN_PARTIAL = 3
    }
}
