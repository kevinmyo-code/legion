package com.kevin.legion.dumb

import android.content.Context
import android.content.SharedPreferences

/** Persistence seam for [RecentCallers]: number digits and the instant each was last seen, nothing else. */
interface RecentCallStore {
    fun read(): Map<String, Long>
    fun write(seen: Map<String, Long>)
}

/**
 * The numbers dumb mode's call screener has seen lately (ADR 0062). A number that rang within the
 * last [WINDOW_MS] rings when it rings again: a stranger who really needs Kevin calls twice. Only
 * the number's digits and the time are kept, pruned as they age out, and nothing else about a call.
 *
 * "Within" is inclusive: a second call exactly [WINDOW_MS] after the first still rings. Every sighting
 * refreshes the time, so a caller who keeps ringing keeps ringing.
 */
class RecentCallers(private val store: RecentCallStore) {
    private val lock = Any()
    private var seen: Map<String, Long> = store.read()

    /** True when [number] (any format) was seen within the window before [nowMs]. */
    fun seenWithin(number: String?, nowMs: Long): Boolean = synchronized(lock) {
        seen.any { (known, at) -> nowMs - at in 0..WINDOW_MS && PhoneNumbers.same(known, number) }
    }

    /** Notes a sighting and drops anything older than the window. A blank number is not recorded. */
    fun record(number: String?, nowMs: Long) = synchronized(lock) {
        val digits = PhoneNumbers.normalize(number)
        val kept = seen.filter { (_, at) -> nowMs - at in 0..WINDOW_MS }
        val next = if (digits.isEmpty()) {
            kept
        } else {
            kept.filterKeys { !PhoneNumbers.same(it, digits) } + (digits to nowMs)
        }
        if (next != seen) {
            seen = next
            store.write(next)
        }
    }

    companion object {
        const val WINDOW_MS = 15L * 60 * 1000
    }
}

/** SharedPreferences persistence as "digits:millis;digits:millis". A pair that does not parse is dropped. */
class PrefsRecentCallStore(private val prefs: SharedPreferences) : RecentCallStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override fun read(): Map<String, Long> = parse(prefs.getString(KEY, "").orEmpty())

    override fun write(seen: Map<String, Long>) {
        prefs.edit().putString(KEY, format(seen)).apply()
    }

    internal companion object {
        const val PREFS = "dumb_mode_recent_callers"
        private const val KEY = "seen"

        fun format(seen: Map<String, Long>): String = seen.entries.joinToString(";") { "${it.key}:${it.value}" }

        fun parse(raw: String): Map<String, Long> = raw.split(';').mapNotNull { pair ->
            val digits = pair.substringBefore(':', "")
            val at = pair.substringAfter(':', "").toLongOrNull()
            if (digits.isNotEmpty() && digits.all(Char::isDigit) && at != null) digits to at else null
        }.toMap()
    }
}
