@file:Suppress("FunctionNaming", "MagicNumber")
// FunctionNaming: @Composable convention is PascalCase. MagicNumber: layout dp.

package com.kevin.legion.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kevin.legion.finance.BudgetsWire
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.FinanceText
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import com.kevin.legion.ui.theme.soft.SoftColors
import java.time.LocalDate
import java.time.YearMonth

/**
 * The month's whole budget as one bar (Kevin, 2026-11-05: "instead of a big number, just give me
 * like a bar that gets filled up as i use just like the budgets, easy to glance"). Spent and left
 * are USD only: the engine's budgets are the US household's daily spending.
 */
data class BudgetTotalModel(
    /** "November". */
    val month: String,
    /** "$1,402 of $2,830", or "$1,402 spent" with no caps. */
    val amountText: String,
    /** total spent / total cap, 0..1. */
    val fill: Float,
    /** pace / cap, 0..1: today's even-pace tick. Null with no caps. */
    val pace: Float?,
    val tone: BarTone,
    /** "$1,428 left", "over by $12", "$300 under budget", "no caps set yet"; pending said after. */
    val words: String,
    val over: Boolean,
    /** "November: $1,402 spent, $1,428 left of $2,830": the whole sentence, for TalkBack. */
    val sentence: String,
)

/** A fixed bill's category: paid this month or not. Fixed categories carry no cap from the engine. */
data class FixedLineModel(val category: String, val status: String, val paid: Boolean)

/**
 * Home, the More card and each Money month say the same budget through this one model, from the
 * one `GET /api/finance/budgets?month=` read Alfred's `budgets` tool makes (ADR 0035).
 */
data class BudgetBoardModel(
    /** Null when there is no budget read; [note] says why. */
    val total: BudgetTotalModel?,
    /** Every capped day-to-day category, highest percent used first. */
    val bars: List<BudgetBarModel>,
    /** The engine's fixed categories (its policy's list), shown last under "Fixed". */
    val fixed: List<FixedLineModel>,
    /** A saved copy's age, why nothing could be read, or "Loading budgets.". */
    val note: String?,
    /** True when [note] is about a figure still shown (a saved copy), so it takes the caution colour. */
    val noteIsCaution: Boolean = false,
)

private const val NEAR_PERCENT = 80

/** The board for [read], or its sentence when there is none. Never a zero standing in for a failure. */
fun budgetBoard(read: FinanceRead<BudgetsWire>?, today: LocalDate, loading: Boolean = read == null): BudgetBoardModel {
    val budgets = read?.valueOrNull
    if (budgets == null) {
        val why = read?.noteOrNull
        val note = when {
            why != null -> "Couldn't read budgets. $why"
            loading -> "Loading budgets."
            else -> "Couldn't read budgets."
        }
        return BudgetBoardModel(null, emptyList(), emptyList(), note)
    }
    val fixedNames = budgets.fixed.map { it.category }.toSet()
    return BudgetBoardModel(
        total = budgetTotal(budgets, today),
        bars = closestToCap(budgets.categories.filter { it.category !in fixedNames }, USD),
        fixed = budgets.fixed.map {
            FixedLineModel(
                it.category,
                if (it.paid) "paid, ${FinanceText.money(it.paidCents, USD)}" else "not paid yet",
                it.paid,
            )
        },
        note = (read as? FinanceRead.Saved)?.note,
        noteIsCaution = read is FinanceRead.Saved,
    )
}

private const val USD = "USD"

/** The total bar's figures and words for one month's budgets. */
fun budgetTotal(budgets: BudgetsWire, today: LocalDate): BudgetTotalModel {
    val ym = runCatching { YearMonth.parse(budgets.month) }.getOrNull() ?: YearMonth.from(today)
    val name = monthName(ym)
    val spent = budgets.total.spentCents
    val cap = budgets.total.capCents
    val fixedNames = budgets.fixed.map { it.category }.toSet()
    val pending = budgets.categories
        .filter { it.category !in fixedNames && (it.capCents ?: 0L) > 0L }
        .sumOf { it.pendingCents }
    val pendingWords = if (pending > 0) ", includes ${FinanceText.money(pending, USD)} pending" else ""
    val spentText = FinanceText.money(spent, USD)
    if (cap <= 0L) {
        return BudgetTotalModel(
            month = name,
            amountText = "$spentText spent",
            fill = 0f,
            pace = null,
            tone = BarTone.NORMAL,
            words = "no caps set yet$pendingWords",
            over = false,
            sentence = "$name: $spentText spent, no caps set yet$pendingWords",
        )
    }
    val capText = FinanceText.money(cap, USD)
    val over = spent > cap
    val percent = budgets.total.percent ?: ((spent * 100 + cap / 2) / cap).toInt()
    val (words, sentenceLeft) = leftWords(spent, cap, ended = ym.isBefore(YearMonth.from(today)))
    return BudgetTotalModel(
        month = name,
        amountText = "$spentText of $capText",
        fill = fraction(spent, cap),
        pace = budgets.total.paceCents?.let { fraction(it, cap) },
        tone = if (over) BarTone.OVER else if (percent >= NEAR_PERCENT) BarTone.NEAR else BarTone.NORMAL,
        words = words + pendingWords,
        over = over,
        sentence = "$name: $spentText spent, $sentenceLeft$pendingWords",
    )
}

/** "$1,019 left" / "over by $12" / "$300 under budget" for the bar, and the same for the sentence. */
private fun leftWords(spent: Long, cap: Long, ended: Boolean): Pair<String, String> {
    val capText = FinanceText.money(cap, USD)
    val left = FinanceText.money(cap - spent, USD)
    return when {
        spent > cap -> FinanceText.money(spent - cap, USD).let { "over by $it" to "over by $it on $capText" }
        ended -> "$left under budget" to "$left under $capText"
        else -> "$left left" to "$left left of $capText"
    }
}

/** The total bar's track is this much thicker than a category's (8dp). */
private const val TOTAL_BAR_DP = 14

/**
 * The total budget bar: a small label row ("November" left, "$1,402 of $2,830" right), the thick
 * track with today's pace tick, and one short line of words under it, in the warning colour when
 * over. No large numerals: the bar does the talking.
 *
 * [trailing] takes the label row's right side (HOME's Brief and Calendar buttons); the amounts then
 * move to the right of the words line, so the row never has to fit both.
 */
@Composable
fun TotalBudgetBar(
    model: BudgetTotalModel,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                model.month,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = SoftColors.text,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                trailing()
            } else {
                Amounts(model.amountText)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
                .semantics(mergeDescendants = true) { contentDescription = model.sentence },
        ) {
            BarTrack(
                model.fill,
                model.pace,
                model.tone.fillColor(),
                Modifier.padding(top = 2.dp),
                height = TOTAL_BAR_DP,
            )
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Top) {
                Text(
                    model.words,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (model.over) SoftColors.onAlert else SoftColors.text2,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) Amounts(model.amountText)
            }
        }
    }
}

@Composable
private fun Amounts(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = SoftColors.text2,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/** The small "Fixed" label the fixed categories sit under, last. */
@Composable
fun FixedLabel(modifier: Modifier = Modifier) {
    Text(
        "Fixed",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = SoftColors.text3,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/** One fixed category: its name and "paid, $1,184" or "not paid yet". Tapping opens it. */
@Composable
fun FixedRow(model: FixedLineModel, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(vertical = 4.dp),
    ) {
        Text(
            model.category,
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.text,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            model.status,
            style = MaterialTheme.typography.bodySmall,
            color = if (model.paid) SoftColors.text2 else SoftColors.caution,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
