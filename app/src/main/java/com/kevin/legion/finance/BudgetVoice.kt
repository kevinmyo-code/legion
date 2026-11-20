package com.kevin.legion.finance

import com.kevin.legion.brief.BriefWords
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * What `get_monthly_spend` says for budget left, one category's spend and the top categories
 * (2026-11-19). Every figure is one number of the engine's `GET /api/finance/budgets` body, the read
 * HOME's "left for <month>" and the Money screen make ([FinanceController.budgets]); the only sum is
 * [BudgetLeft], which HOME calls too. The words are code's ([SpokenMoney]) and the cascade says
 * [Answer.speech] itself ([com.kevin.legion.ai.VerbatimSpeech]), so no amount he hears is the model's.
 * A category is matched by membership only ([match]); a miss hands the valid names back for the model
 * to ask him, never a guess. Pure: the clock comes in.
 */
// One public function per view plus the pieces they share; splitting would scatter one answer's rules.
@Suppress("TooManyFunctions")
object BudgetVoice {
    /** The month asked about, the phone's current month, and now. */
    data class At(val month: YearMonth, val current: YearMonth, val nowMs: Long)

    data class Line(val category: String, val cents: Long) {
        val amount: String get() = decimal(cents)
    }

    sealed interface Match {
        data class One(val name: String) : Match
        data class Several(val names: List<String>) : Match
        data object None : Match
    }

    data class Answer(
        val view: String,
        /** False when there is no read, or no single category matched. */
        val ok: Boolean,
        val month: YearMonth,
        val currency: String = "USD",
        /** ISO instant the figures were read (fresh) or saved (saved copy); null with no read. */
        val asOf: String? = null,
        val stale: Boolean = false,
        val saved: Boolean = false,
        val category: String? = null,
        /** The figure asked for: a category's spend, or for `left` the amount left. */
        val amountCents: Long? = null,
        val capCents: Long? = null,
        /** Cap minus spent; negative when over; null with no cap. */
        val leftCents: Long? = null,
        val pendingCents: Long = 0,
        val top: List<Line> = emptyList(),
        val validCategories: List<String> = emptyList(),
        val matches: List<String> = emptyList(),
        /** Said by the phone itself, word for word; null when the model must ask him which category. */
        val speech: String?,
        /** For the model when [speech] is null. */
        val note: String? = null,
    ) {
        val periodStart: String get() = month.atDay(1).toString()
        val periodEnd: String get() = month.atEndOfMonth().toString()

        /** [amountCents] as an exact decimal string ("412.07"). */
        val amount: String? get() = amountCents?.let(::decimal)
    }

    private const val TOP = 3
    private const val VIEW_LEFT = "left"
    private const val VIEW_CATEGORY = "category"
    private const val VIEW_TOP = "top"

    /** "412.07": cents as an exact decimal, never through a float. */
    fun decimal(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /** The phone's local month at [nowMs]: "this month" is his month, not UTC's. */
    fun currentMonth(nowMs: Long, zone: ZoneId): YearMonth = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))

    /** Exact name first, then case- and space-folded; never a near name. */
    fun match(spoken: String, valid: List<String>): Match {
        val said = spoken.trim()
        valid.firstOrNull { it == said }?.let { return Match.One(it) }
        val hits = valid.filter { fold(it) == fold(said) }.distinct()
        return when (hits.size) {
            0 -> Match.None
            1 -> Match.One(hits.single())
            else -> Match.Several(hits)
        }
    }

    private fun fold(s: String) = s.lowercase(Locale.US).filterNot { it.isWhitespace() }

    @Suppress("ReturnCount") // no read, no cap, an answer
    fun left(read: FinanceRead<BudgetsWire>?, at: At): Answer {
        val b = read?.valueOrNull ?: return noRead(VIEW_LEFT, read, at)
        val name = monthName(at)
        val left = BudgetLeft.cents(b)
            ?: return base(VIEW_LEFT, read, at, b)
                .copy(speech = "No budget is set for $name, so there is no amount left to say.")
        val cap = b.total.capCents
        val fixedNames = b.fixed.map { it.category }.toSet()
        val pending = b.categories
            .filter { it.category !in fixedNames && (it.capCents ?: 0L) > 0L }
            .sumOf { it.pendingCents }
        val first = when {
            at.month < at.current && left >= 0 -> "$name ended ${say(left)} under its budget of ${say(cap)}."
            at.month < at.current -> "$name ended ${say(-left)} over its budget of ${say(cap)}."
            left >= 0 -> "You have ${say(left)} left of your budget of ${say(cap)} for $name" + daysToGo(b) + "."
            else -> "You are ${say(-left)} over your budget of ${say(cap)} for $name."
        }
        val words = listOfNotNull(
            first,
            "That includes ${say(pending)} of pending charges.".takeIf { pending > 0 },
            "Fixed bills are not in it.".takeIf { b.fixed.isNotEmpty() },
        )
        val answer = base(VIEW_LEFT, read, at, b)
            .copy(amountCents = left, capCents = cap, leftCents = left, pendingCents = pending)
        return answer.copy(speech = withAge(words, answer, read, at))
    }

    /** One category's spend for the month. [known] is the household's category list on this phone. */
    @Suppress("ReturnCount") // several, none, no read, an answer: flatter than a nest
    fun category(
        read: FinanceRead<BudgetsWire>?,
        spoken: String,
        known: List<String>,
        notSpending: Set<String>,
        at: At,
    ): Answer {
        val b = read?.valueOrNull
        val valid = (known + b?.categories.orEmpty().map { it.category } + b?.fixed.orEmpty().map { it.category })
            .distinct().sorted()
        val name = when (val m = match(spoken, valid)) {
            is Match.One -> m.name
            is Match.Several -> return Answer(
                VIEW_CATEGORY, ok = false, month = at.month, validCategories = valid, matches = m.names, speech = null,
                note = "\"${spoken.trim()}\" matches more than one category: " +
                    "${m.names.joinToString(", ")}. Ask him which.",
            )
            Match.None -> return Answer(
                VIEW_CATEGORY, ok = false, month = at.month, validCategories = valid, speech = null,
                note = "There is no \"${spoken.trim()}\" category. Ask him which of valid_categories he means, " +
                    "then call again with that exact name.",
            )
        }
        if (b == null) return noRead(VIEW_CATEGORY, read, at).copy(category = name)
        val base = base(VIEW_CATEGORY, read, at, b).copy(category = name)
        val month = monthName(at)
        val ended = at.month < at.current
        val line = b.categories.firstOrNull { fold(it.category) == fold(name) }
        val fixed = b.fixed.firstOrNull { fold(it.category) == fold(name) }
        val answer = when {
            name in notSpending ->
                base.copy(speech = "$name is not counted as spending, so there is no spend figure for it.")
            line != null -> lineAnswer(base, line, if (ended) "$name in $month" else "$name so far in $month", ended)
            fixed != null && fixed.paid -> base.copy(
                amountCents = fixed.paidCents,
                capCents = fixed.capCents,
                speech = "$name for $month: ${say(fixed.paidCents)} paid.",
            )
            fixed != null -> base.copy(capCents = fixed.capCents, speech = "$name for $month: not paid yet.")
            else -> base.copy(speech = "No $name spending is on file for $month.")
        }
        return answer.copy(speech = withAge(listOfNotNull(answer.speech), answer, read, at))
    }

    private fun lineAnswer(base: Answer, line: BudgetLineWire, lead: String, ended: Boolean): Answer {
        val cap = line.capCents?.takeIf { it > 0L }
        val left = cap?.let { it - line.spentCents }
        val capWords = when {
            cap == null || left == null -> "It has no budget set."
            left < 0 -> "That is ${say(-left)} over its budget of ${say(cap)}."
            ended -> "That is ${say(left)} under its budget of ${say(cap)}."
            else -> "That is ${say(left)} left of its budget of ${say(cap)}."
        }
        // Refunds larger than the charges leave a negative line (the engine nets them): said as a refund.
        val figure =
            if (line.spentCents < 0) "${say(-line.spentCents)} more refunded than spent" else say(line.spentCents)
        val words = listOfNotNull(
            "$lead: $figure.",
            capWords,
            "It includes ${say(line.pendingCents)} pending.".takeIf { line.pendingCents > 0 },
            "Some of it is not yet confirmed.".takeIf { line.unverified },
        )
        return base.copy(
            amountCents = line.spentCents,
            capCents = cap,
            leftCents = left,
            pendingCents = line.pendingCents,
            speech = words.joinToString(" "),
        )
    }

    /** The highest day-to-day categories for the month, from the engine's lines (fixed bills are apart). */
    @Suppress("ReturnCount") // no read, nothing spent, an answer
    fun top(read: FinanceRead<BudgetsWire>?, at: At): Answer {
        val b = read?.valueOrNull ?: return noRead(VIEW_TOP, read, at)
        val month = monthName(at)
        val fixedNames = b.fixed.map { it.category }.toSet()
        val lines = b.categories
            .filter { it.category !in fixedNames && it.spentCents > 0L }
            .sortedByDescending { it.spentCents }
            .take(TOP)
            .map { Line(it.category, it.spentCents) }
        val base = base(VIEW_TOP, read, at, b).copy(top = lines)
        if (lines.isEmpty()) {
            return base.copy(speech = withAge(listOf("No day-to-day spending is on file for $month."), base, read, at))
        }
        val said = lines.map { "${it.category}, ${say(it.cents)}" }
        val list = if (said.size == 1) said.single() else said.dropLast(1).joinToString("; ") + "; and " + said.last()
        val period = if (at.month < at.current) month else "$month so far"
        val words = listOfNotNull(
            "Top day-to-day spending for $period: $list.",
            "${say(b.uncategorisedCents)} is not categorised yet.".takeIf { b.uncategorisedCents > 0L },
        )
        return base.copy(speech = withAge(words, base, read, at))
    }

    private fun base(view: String, read: FinanceRead<BudgetsWire>, at: At, b: BudgetsWire): Answer {
        val saved = read as? FinanceRead.Saved
        val asOf = Instant.ofEpochMilli(saved?.savedAtMs ?: at.nowMs)
        return Answer(
            view = view,
            ok = true,
            month = at.month,
            currency = b.currency,
            asOf = asOf.toString(),
            stale = NetCashText.stale(asOf, Instant.ofEpochMilli(at.nowMs)),
            saved = saved != null,
            speech = null,
        )
    }

    private fun noRead(view: String, read: FinanceRead<BudgetsWire>?, at: At): Answer {
        val why = read?.noteOrNull ?: "it could not be read just now."
        val speech = "There is no budget figure for ${monthName(at)}: $why"
        return Answer(view, ok = false, month = at.month, speech = speech)
    }

    /** The answer's words, then how old the figure is when it is the saved copy or over a day old. */
    private fun withAge(words: List<String>, answer: Answer, read: FinanceRead<BudgetsWire>?, at: At): String {
        val saved = read as? FinanceRead.Saved
        val age = listOfNotNull(
            NetCashVoice.STALE_SENTENCE.takeIf { answer.stale },
            saved?.let { "That is the last saved figure, from ${BriefWords.ago(it.savedAtMs, at.nowMs)}." },
        )
        return (words + age).joinToString(" ")
    }

    private fun daysToGo(b: BudgetsWire): String = when (val d = BudgetLeft.daysLeft(b)) {
        0 -> ""
        1 -> ", with 1 day to go"
        else -> ", with $d days to go"
    }

    /** "November", or "November 2025" outside the current year. */
    private fun monthName(at: At): String {
        val name = at.month.month.getDisplayName(TextStyle.FULL, Locale.US)
        return if (at.month.year == at.current.year) name else "$name ${at.month.year}"
    }

    private fun say(cents: Long): String = SpokenMoney.amount(cents)
}
