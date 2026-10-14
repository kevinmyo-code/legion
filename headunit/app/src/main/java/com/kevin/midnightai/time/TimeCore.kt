package com.kevin.midnightai.time

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Where the trusted offset came from. */
enum class ClockSource { SNTP, HTTP_DATE }

/** Where the zone in use came from, in priority order. */
enum class ZoneSource { PHONE, MANUAL, ENGINE, DEFAULT }

/** One accepted network time reading: trusted UTC = elapsedRealtime + [offsetMs]. */
data class ClockSync(
    val offsetMs: Long,
    val source: ClockSource,
    val atElapsedMs: Long,
)

/**
 * The pure heart of [TrustedClock]: an offset against a monotonic clock, with injected clocks so it
 * runs under a fake clock in a JVM test. Until a sync lands it answers with the wall clock and says
 * so ([isTrusted] false).
 */
class TrustedClockCore(
    private val elapsed: () -> Long,
    private val wall: () -> Long,
) {
    @Volatile
    var sync: ClockSync? = null
        private set

    fun accept(s: ClockSync) {
        sync = s
    }

    fun clear() {
        sync = null
    }

    val isTrusted: Boolean get() = sync != null

    fun nowMs(): Long = sync?.let { elapsed() + it.offsetMs } ?: wall()

    /** Device wall clock minus trusted UTC (positive = device is ahead); null until synced. */
    fun skewMs(): Long? = sync?.let { wall() - (elapsed() + it.offsetMs) }

    /** ms since the last accepted sync, on the monotonic clock; null until synced. */
    fun ageMs(): Long? = sync?.let { elapsed() - it.atElapsedMs }
}

/**
 * The offset as stored, scoped to ONE boot: `elapsedRealtime` restarts at zero on reboot, so an
 * offset from a previous boot is meaningless. [bootCount] is `Settings.Global.BOOT_COUNT` (-1 where
 * the unit does not have it); [bootWallMs] is the wall clock minus elapsed at save time, the boot
 * instant as the wall clock saw it, used only when there is no boot counter.
 */
data class PersistedSync(
    val offsetMs: Long,
    val source: ClockSource,
    val atElapsedMs: Long,
    val bootCount: Int,
    val bootWallMs: Long,
) {
    fun toSync() = ClockSync(offsetMs, source, atElapsedMs)

    companion object {
        /** How far the wall-clock estimate of the boot instant may wander and still be "same boot". */
        const val BOOT_MATCH_TOLERANCE_MS = 120_000L

        /** Older than this, restore nothing: monotonic drift on a cheap oscillator adds up. */
        const val MAX_AGE_MS = 3L * 24 * 3_600_000L

        fun restorable(p: PersistedSync, currentBootCount: Int, nowElapsed: Long, nowWall: Long): Boolean {
            if (nowElapsed < p.atElapsedMs) return false // the monotonic clock went backwards: rebooted
            if (nowElapsed - p.atElapsedMs > MAX_AGE_MS) return false
            if (p.bootCount >= 0 && currentBootCount >= 0) return p.bootCount == currentBootCount
            // No boot counter on this unit. A wall-clock reset inside the same boot also fails this,
            // which only costs a re-sync.
            return abs((nowWall - nowElapsed) - p.bootWallMs) < BOOT_MATCH_TOLERANCE_MS
        }
    }
}

/** Picks the zone. The head unit's own zone is never a candidate. */
object ZoneResolver {
    fun parse(id: String?): ZoneId? {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { ZoneId.of(trimmed) }.getOrNull()
    }

    fun resolve(
        phone: String?,
        followPhone: Boolean,
        manual: String?,
        engine: String?,
        default: String,
    ): Pair<ZoneId, ZoneSource> {
        if (followPhone) parse(phone)?.let { return it to ZoneSource.PHONE }
        parse(manual)?.let { return it to ZoneSource.MANUAL }
        parse(engine)?.let { return it to ZoneSource.ENGINE }
        return (parse(default) ?: ZoneOffset.UTC) to ZoneSource.DEFAULT
    }
}

/** HTTP `Date` header fallback (RFC 7231 IMF-fixdate, one-second resolution). */
object HttpDate {
    fun parseMs(header: String?): Long? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** `UTC - elapsed` for a Date stamped somewhere between [sentElapsed] and [receivedElapsed].
     * The header truncates to the second, so +500 ms centres it. */
    fun offsetMs(dateMs: Long, sentElapsed: Long, receivedElapsed: Long): Long =
        dateMs + 500L - (sentElapsed + receivedElapsed) / 2
}

/**
 * Every sentence the app says about "now". Deliberately carries a UTC OFFSET and never the IANA
 * zone id: LEGION's CLAUDE.md records that handing the model `America/Chicago` made it talk about
 * Chicago to a man in Houston. The zone id stays in Settings, where a person reads it.
 */
object TimeText {
    private val CLOCK_24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    /** "UTC-05:00", "UTC+00:00", "UTC+05:30". */
    fun offsetLabel(offset: ZoneOffset): String =
        "UTC" + if (offset.totalSeconds == 0) "+00:00" else offset.id

    private fun dayName(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.US)
    private fun dayShort(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.US)

    /** "Wed 10-14, Thu 10-15, ..." for the next [days] days, so the model never has to compute a
     * weekday itself (it got "Wednesday" wrong on a Tuesday). */
    fun comingDays(now: ZonedDateTime, days: Int = 7): String =
        (1..days).joinToString(", ") { i ->
            val d = now.toLocalDate().plusDays(i.toLong())
            "${dayShort(d.dayOfWeek)} ${d.format(ISO_DATE)}"
        }

    /** The one-line "now" for a model prompt. */
    fun promptLine(now: ZonedDateTime, trusted: Boolean): String {
        val date = now.toLocalDate()
        val sb = StringBuilder()
            .append("Today is ").append(dayName(date.dayOfWeek)).append(' ').append(date.format(ISO_DATE))
            .append(", ").append(now.format(CLOCK_24)).append(" local time (")
            .append(offsetLabel(now.offset)).append("). ")
            .append("Next days: ").append(comingDays(now)).append('.')
        if (!trusted) {
            sb.append(" (The clock is not network-verified yet; if the driver says the date or time is " +
                "wrong, believe them.)")
        }
        return sb.toString()
    }

    /**
     * What the engine sub-agent is told. The engine (LEGION `engine_mcp/tools.py` `_parse_instant`)
     * reads a BARE date as a UTC day and answers in UTC, so a local "the 16th" asked as `2026-10-16`
     * is shifted by the offset. The rule below makes the agent send offset-bearing instants and
     * convert the UTC times it gets back.
     */
    fun engineLine(now: ZonedDateTime, trusted: Boolean): String {
        val off = now.offset
        val offId = if (off.totalSeconds == 0) "+00:00" else off.id
        val example = now.toLocalDate().plusDays(3)
        val exUtc = example.atTime(2, 0).atOffset(ZoneOffset.UTC)
        val exLocal = exUtc.atZoneSameInstant(now.zone)
        return promptLine(now, trusted) + " The user's dates are LOCAL dates in ${offsetLabel(off)}: " +
            "resolve today, tomorrow, weekdays and 'the Nth' against the date above. The engine reads a " +
            "bare YYYY-MM-DD in a from/to/since argument as a UTC day, which is wrong by the offset, so " +
            "always pass full date-times with the offset: for ${example.format(ISO_DATE)} use from " +
            "${example.format(ISO_DATE)}T00:00:00$offId to ${example.format(ISO_DATE)}T23:59:59$offId. " +
            "The engine's times are UTC: convert each to ${offsetLabel(off)} before you say its day or " +
            "time (e.g. ${exUtc.format(UTC_STAMP)} is " +
            "${dayName(exLocal.dayOfWeek)} ${exLocal.toLocalDate().format(ISO_DATE)} " +
            "${exLocal.format(CLOCK_24)} local). A field documented as the household's local date " +
            "(YYYY-MM-DD) takes the local date as is."
    }

    /** "within 1 s", "12 s ahead", "3 min 4 s behind", "2 h 5 min ahead", "3 d 2 h behind". */
    fun skewLabel(skewMs: Long): String {
        val abs = abs(skewMs)
        if (abs < 1_000L) return "within 1 s"
        val s = abs / 1000
        val text = when {
            s < 60 -> "$s s"
            s < 3_600 -> "${s / 60} min ${s % 60} s"
            s < 86_400 -> "${s / 3_600} h ${(s % 3_600) / 60} min"
            else -> "${s / 86_400} d ${(s % 86_400) / 3_600} h"
        }
        return text + if (skewMs > 0) " ahead" else " behind"
    }

    /** The Settings status line: "Clock: synced (device off by 3 min 4 s ahead)". */
    fun statusLine(trusted: Boolean, skewMs: Long?, source: ClockSource?): String = when {
        !trusted || skewMs == null -> "Clock: not synced yet, using the head unit's clock"
        abs(skewMs) < 1_000L -> "Clock: synced via ${sourceName(source)} (device within 1 s)"
        else -> "Clock: synced via ${sourceName(source)} (device off by ${skewLabel(skewMs)})"
    }

    private fun sourceName(source: ClockSource?) = when (source) {
        ClockSource.SNTP -> "time.google.com"
        ClockSource.HTTP_DATE -> "web time"
        null -> "network"
    }
}
