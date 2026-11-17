package com.kevin.legion.finance

import com.kevin.legion.assistant.cards.CardMark
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.TrendPoint
import com.kevin.legion.assistant.cards.TrendSeries
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How net cash is SAID on the phone (ADR 0094). The figure itself is the engine's
 * ([NetCashWire], `server/finance/net_cash.py`); nothing here adds, subtracts or re-derives a part.
 * What lives here is presentation the engine cannot do: the as-of time in this phone's zone, the
 * 24-hour staleness against this phone's clock (a saved copy ages offline), the saved-copy words,
 * and the floor Kevin set on this phone ([NetCashFloor]).
 *
 * Honesty rules, all said in words: every state says its as-of time; older than a day says so; a
 * saved copy says it is the last known figure; no figure is ever a zero; a missing part gives no net
 * figure and names the account.
 */
object NetCashText {
    /** Older than this, the figure says so in words. */
    val STALE: Duration = Duration.ofHours(STALE_HOURS)

    private const val STALE_HOURS = 24L

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** What one surface shows. [figure] null means no net figure, and [line] says why. */
    data class Model(
        /** "$1,402.18", or "No figure". */
        val figure: String,
        val hasFigure: Boolean,
        /** "net cash", or "net cash, below zero" (the flag in words). */
        val label: String,
        /** The as-of line, with "last known" or "over a day old" in it when so; or why there is no figure. */
        val line: String,
        /** "below zero" / "below your $500 floor"; null when not low. Drawn in the accent AND said in [label]. */
        val flag: String? = null,
        /** The saved copy's own note ("Saved 3 hours ago. The engine could not be reached."), when one. */
        val savedNote: String? = null,
    )

    /** One account row of the Money screen's working. */
    data class Row(val label: String, val value: String, val note: String?)

    fun parseAt(iso: String?): Instant? = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    /** "14:05 today", "14:05 yesterday", "Nov 14, 09:12". 24-hour (ADR 0081). */
    fun at(instant: Instant, now: Instant, zone: ZoneId): String {
        val local = instant.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val clock = local.format(CLOCK)
        return when (local.toLocalDate()) {
            today -> "$clock today"
            today.minusDays(1) -> "$clock yesterday"
            else -> "${local.format(DAY)}, $clock"
        }
    }

    fun stale(asOf: Instant, now: Instant): Boolean = Duration.between(asOf, now) > STALE

    /** Whether [net] is low, in words; null when it is not. Below zero first, then the floor. */
    fun flag(net: Long, currency: String, floorCents: Long?): String? = when {
        net < 0 -> "below zero"
        floorCents != null && net < floorCents -> "below your ${FinanceText.money(floorCents, currency)} floor"
        else -> null
    }

    /** The surface model from whatever the read gave. Never blank, never a made-up zero. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one branch per state the read can be in
    fun model(read: FinanceRead<NetCashWire>?, floorCents: Long?, now: Instant, zone: ZoneId): Model {
        val body = read?.valueOrNull
        if (body == null) {
            val why = when (read) {
                null -> "Reading net cash."
                else -> "Couldn't read net cash. ${read.noteOrNull.orEmpty()}".trim()
            }
            return Model(NO_FIGURE, hasFigure = false, label = LABEL, line = why)
        }
        val saved = read as? FinanceRead.Saved
        val net = body.netCents
        val currency = body.currency ?: "USD"
        val asOf = parseAt(body.asOf)
        if (!body.available || net == null || asOf == null) {
            return Model(
                NO_FIGURE,
                hasFigure = false,
                label = LABEL,
                line = missingLine(body) + (saved?.let { " Last known answer. ${it.note}" } ?: ""),
                savedNote = saved?.note,
            )
        }
        val flag = flag(net, currency, floorCents)
        val asOfWords = "as of ${at(asOf, now, zone)}"
        val line = when {
            saved != null && stale(asOf, now) -> "Last known, over a day old: $asOfWords"
            saved != null -> "Last known: $asOfWords"
            stale(asOf, now) -> "Over a day old: $asOfWords"
            else -> asOfWords.replaceFirstChar { it.uppercase() }
        }
        return Model(
            figure = FinanceText.exact(net, currency),
            hasFigure = true,
            label = if (flag != null) "$LABEL, $flag" else LABEL,
            line = line,
            flag = flag,
            savedNote = saved?.note,
        )
    }

    /** Which account has no balance on file, in words; else the engine's own reason. */
    fun missingLine(body: NetCashWire): String {
        val first = body.missing.firstOrNull()
            ?: return body.reason ?: "No net cash figure."
        val ending = first.mask?.let { " ending $it" }.orEmpty()
        val more = body.missing.size - 1
        val also = if (more > 0) " And $more more." else ""
        return "No net figure: ${first.label}$ending, ${first.reason}.$also"
    }

    /** The Money screen's working: checking, each card, each with its pending. Empty with no figure. */
    fun rows(body: NetCashWire): List<Row> = body.parts.map { p ->
        val name = "${p.label} ending ${p.mask}"
        val pending = if (p.pendingCount > 0) {
            "of which pending ${FinanceText.exact(p.pendingCents, p.currency)} (${p.pendingCount})"
        } else {
            null
        }
        val note = when {
            p.role == "checking" && p.basis == "bank_available" ->
                listOfNotNull("the bank's available balance, pending already taken off", pending).joinToString("; ")
            p.role == "card" -> listOfNotNull("owed, pending charges included", pending).joinToString("; ")
            else -> pending
        }
        Row(name, FinanceText.exact(p.balanceCents, p.currency), note)
    }

    /** The 30-day line as a trend card, drawn by the cards' own chart; null with fewer than two points. */
    fun trend(body: NetCashWire, floorCents: Long?): ConversationCard.Trend? {
        val history = body.history?.takeIf { it.points.size >= 2 } ?: return null
        val currency = body.currency ?: "USD"
        val usd = currency == "USD"
        fun value(cents: Long): BigDecimal = if (usd) BigDecimal.valueOf(cents) else BigDecimal.valueOf(cents, 2)
        return ConversationCard.Trend(
            title = "Net cash, last 30 days",
            unit = if (usd) "usd" else currency,
            series = listOf(TrendSeries("Net cash", history.points.map { TrendPoint(it.on, value(it.netCents)) })),
            guide = floorCents?.let { CardMark(value(it), "Your floor") },
        )
    }

    /** The words under the chart: what the line is, and from when when the feed is short. */
    fun trendNote(body: NetCashWire): String {
        val history = body.history ?: return ""
        val start = FinanceText.parseDay(history.start)
        val from = if (history.short && start != null) {
            " The bank feed reaches back to ${FinanceText.day(start)}."
        } else {
            ""
        }
        return "Past days are posted figures only; pending charges are counted in today's point.$from"
    }

    /**
     * What `get_balance` says first when asked how much he has (ADR 0094: the answer leads with net
     * cash and its as-of time). The engine's figure, in words; never a sum made here.
     */
    fun spoken(read: FinanceRead<NetCashWire>?, now: Instant, zone: ZoneId): String {
        val m = model(read, floorCents = null, now = now, zone = zone)
        val body = read?.valueOrNull
        if (!m.hasFigure || body == null) return "No net cash figure. ${m.line}"
        val pending = body.pendingCents?.takeIf { it != 0L }
            ?.let { " Of which pending at the bank: ${FinanceText.exact(it, body.currency ?: "USD")}." }.orEmpty()
        val saved = m.savedNote?.let { " This is the last known figure: $it" }.orEmpty()
        val asOf = m.line.replaceFirstChar { it.lowercase() }
        return "Net cash now: ${m.figure} (${body.currency ?: "USD"}), $asOf. " +
            "That is checking minus everything owed on every linked card, " +
            "pending charges counted as spent.$pending$saved"
    }

    const val LABEL = "net cash"
    const val NO_FIGURE = "No figure"
}
