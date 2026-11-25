package com.kevin.legion.finance

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Net cash as `get_balance` hands it to the voice model (stage 7, 2026-11-16). The money gate showed
 * flash-lite doing its own arithmetic on the parts ("twelve eighty-eight-twenty on the card": a
 * figure in no data) and its own date words ("nine last night" for 21:04 the same evening). So every
 * figure the model may say is a ready string made here from the engine's own numbers, each with what
 * it is, and the as-of time is ONE phrase with its day word resolved against this phone's local date.
 * Nothing here adds, subtracts or rounds a figure: each string is one engine number, formatted
 * ([FinanceText.exact]). Pure: no clock, no Android.
 */
object NetCashVoice {
    /** One figure the model may say, exactly as [say], and what it is. */
    data class Figure(val what: String, val say: String)

    data class Voice(
        /** Said first: the net figure with its as-of phrase, or why there is none. */
        val sayFirst: String,
        /** Every money figure it may say, ready. Empty when the engine could not be read. */
        val figures: List<Figure>,
        /** "today at 9:04 PM", "yesterday at 3:29 PM", "Nov 14 at 9:12 AM"; null without a time. */
        val asOf: String?,
        /** "That figure is over a day old." when it is; else null. */
        val stale: String?,
        /** True when there is a net figure to say. */
        val hasNet: Boolean,
        /**
         * The whole answer as SPOKEN, made here: every amount in words by [SpokenMoney], the as-of
         * phrase, the stale sentence. The cascade says it verbatim ([com.kevin.legion.ai.VerbatimSpeech]).
         */
        val speech: String = "",
    )

    const val STALE_SENTENCE = "That figure is over a day old."

    /** Beside the figures in the tool result. Result text, not a tool description: no payload cost. */
    const val RULE = "speak_verbatim has ALREADY been said to him, word for word, by the phone. Do not " +
        "restate any amount, figure or as-of time, and never add, subtract, round or work out one. If he " +
        "asked something else as well, answer only that; otherwise say nothing more."

    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** The as-of phrase, its day word from this phone's local date. */
    fun asOfPhrase(asOf: Instant, now: Instant, zone: ZoneId): String {
        val local = asOf.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val clock = local.format(CLOCK)
        return when (local.toLocalDate()) {
            today -> "today at $clock"
            today.minusDays(1) -> "yesterday at $clock"
            else -> "${local.format(DATE)} at $clock"
        }
    }

    /** The parts as ready strings: checking as counted, each card as counted (owed, pending in) and its pending. */
    fun figures(body: NetCashWire): List<Figure> {
        val out = mutableListOf<Figure>()
        body.netCents?.takeIf { body.available }?.let {
            out += Figure("net cash: checking minus everything owed on every linked card, pending counted as spent",
                FinanceText.exact(it, body.currency ?: "USD"))
        }
        for (p in body.parts) {
            val name = "${p.label} ending ${p.mask}"
            if (p.role == "card") {
                // Owed as a positive amount; the part's balance is in the ledger's sign (negative).
                out += Figure("owed on $name as net cash counts it, pending charges included",
                    FinanceText.exact(-p.balanceCents, p.currency))
                if (p.pendingCount > 0) {
                    out += Figure("of which pending charges on $name", FinanceText.exact(-p.pendingCents, p.currency))
                }
            } else {
                out += Figure("in $name as net cash counts it", FinanceText.exact(p.balanceCents, p.currency))
            }
        }
        body.pendingCents?.takeIf { it != 0L && body.available }?.let {
            // Ledger sign (charges negative): said as the amount of pending charges.
            out += Figure("pending charges at the bank, already counted in net cash",
                FinanceText.exact(-it, body.currency ?: "USD"))
        }
        return out
    }

    fun voice(read: FinanceRead<NetCashWire>?, now: Instant, zone: ZoneId): Voice {
        val body = read?.valueOrNull
        val asOf = NetCashText.parseAt(body?.asOf)
        val m = NetCashText.model(read, floorCents = null, now = now, zone = zone)
        val phrase = asOf?.let { asOfPhrase(it, now, zone) }
        if (!m.hasFigure || body == null || phrase == null) {
            val spoken = body?.let { missingSpeech(it) } ?: NO_READ_SPEECH
            val parts = body?.let { figures(it) }.orEmpty()
            return Voice("No net cash figure. ${m.line}", parts, phrase, null, false, spoken)
        }
        val stale = STALE_SENTENCE.takeIf { NetCashText.stale(asOf, now) }
        val saved = m.savedNote?.let { " This is the last known figure: $it" }.orEmpty()
        val first = "Net cash now: ${m.figure}, as of $phrase." + (stale?.let { " $it" }.orEmpty()) + saved
        val net = body.netCents ?: 0L
        val currency = body.currency ?: "USD"
        val said = if (net < 0) {
            "Net cash is below zero: ${SpokenMoney.amount(net, currency)}, as of $phrase."
        } else {
            "Net cash is ${SpokenMoney.amount(net, currency)}, as of $phrase."
        }
        val speech = listOfNotNull(said, stale, SAVED_SPEECH.takeIf { saved.isNotEmpty() }).joinToString(" ")
        return Voice(first, figures(body), phrase, stale, true, speech)
    }

    /** The missing-account sentence: which account, why, and no net figure. */
    fun missingSpeech(body: NetCashWire): String {
        val first = body.missing.firstOrNull()
            ?: return "There is no net cash figure right now."
        val ending = first.mask?.let { " ending ${SpokenMoney.digits(it)}" }.orEmpty()
        val more = body.missing.size - 1
        val also = when {
            more == 1 -> " One more account has no balance either."
            more > 1 -> " ${SpokenMoney.number(more.toLong()).replaceFirstChar { it.uppercase() }} more accounts " +
                "have no balance either."
            else -> ""
        }
        return "There is no net cash figure: ${first.label}$ending has no balance from the bank.$also"
    }

    /** One ledger account as spoken: "BOFA CARD ending one one nine nine: minus ... cents." */
    fun ledgerSpeech(accountId: String, cents: Long?, currency: String): String {
        val mask = Regex("""(\d{4})\s*$""").find(accountId)?.groupValues?.get(1)
        val name = accountId.substringBefore("*").trim().ifEmpty { "That account" }
        val ending = mask?.let { " ending ${SpokenMoney.digits(it)}" }.orEmpty()
        val figure = cents?.let { SpokenMoney.amount(it, currency) } ?: "no balance on file"
        return "$name$ending: $figure."
    }

    const val NO_READ_SPEECH = "There is no net cash figure: it could not be read just now."
    const val SAVED_SPEECH = "That is the last known figure; the engine could not be reached just now."
}
