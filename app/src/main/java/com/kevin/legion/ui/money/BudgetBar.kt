@file:Suppress("FunctionNaming", "MagicNumber")
// FunctionNaming: @Composable convention is PascalCase. MagicNumber: layout dp from the prototype.

package com.kevin.legion.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.finance.BudgetLineWire
import com.kevin.legion.finance.FinanceText
import com.kevin.legion.ui.theme.soft.SoftColors

/** A bar's tone. Never the only signal: [BudgetBarModel.note] says over and near in words. */
enum class BarTone { NORMAL, NEAR, OVER }

/**
 * One category against its own cap (household-finance tickets 13 and 14): the bar is filled to
 * spent / cap, NOT to the share of the largest category (the retired `MoneyBars`). No pace tick
 * (Kevin, 2026-11-05: "no need for the daily bar"); the engine's `pace_cents` is not drawn.
 * Shared by the Home panel and the Money view.
 */
data class BudgetBarModel(
    val category: String,
    /** "119 / 170". */
    val amountText: String,
    /** spent / cap, 0..1. */
    val fill: Float,
    val tone: BarTone,
    /** "over by $49", "includes $12 pending", joined; null when there is nothing to say. */
    val note: String?,
    val percent: Int,
    val status: String,
)

private const val NEAR_PERCENT = 80

/** The bar for a capped line, or null for a category with no cap (nothing to fill against). */
fun budgetBarModel(line: BudgetLineWire, currency: String = "USD"): BudgetBarModel? {
    val cap = line.capCents?.takeIf { it > 0 } ?: return null
    val spent = line.spentCents
    val percent = line.percent ?: ((spent * 100 + cap / 2) / cap).toInt()
    val tone = when {
        line.status == "over" || spent > cap -> BarTone.OVER
        line.status == "near" || percent >= NEAR_PERCENT -> BarTone.NEAR
        else -> BarTone.NORMAL
    }
    val notes = buildList {
        if (spent > cap) add("over by ${FinanceText.money(spent - cap, currency)}")
        else if (tone == BarTone.NEAR) add("$percent% of cap")
        if (line.pendingCents > 0) add("includes ${FinanceText.money(line.pendingCents, currency)} pending")
        if (line.unverified) add("includes current-period rows")
    }
    return BudgetBarModel(
        category = line.category,
        amountText = "${FinanceText.bare(spent)} / ${FinanceText.bare(cap)}",
        fill = fraction(spent, cap),
        tone = tone,
        note = notes.takeIf { it.isNotEmpty() }?.joinToString(", "),
        percent = percent,
        status = line.status,
    )
}

/** [part] / [whole], held to 0..1. */
fun fraction(part: Long, whole: Long): Float =
    if (whole <= 0L) 0f else (part.toDouble() / whole.toDouble()).coerceIn(0.0, 1.0).toFloat()

/** The bars by percent of cap used, highest first (over-cap lines lead). Uncapped lines are left out. */
fun closestToCap(lines: List<BudgetLineWire>, currency: String = "USD"): List<BudgetBarModel> =
    lines.mapNotNull { budgetBarModel(it, currency) }
        .sortedWith(compareByDescending<BudgetBarModel> { it.percent }.thenByDescending { it.fill })

/** "+ 11 more on track", or "+ 4 more, 2 near or over cap" when a hidden one is not on track. */
fun moreLine(all: List<BudgetBarModel>, shown: Int): String? {
    val hidden = all.drop(shown)
    if (hidden.isEmpty()) return null
    val notOk = hidden.count { it.tone != BarTone.NORMAL }
    return if (notOk == 0) "+ ${hidden.size} more on track" else "+ ${hidden.size} more, $notOk near or over cap"
}

fun BarTone.fillColor(): Color = when (this) {
    BarTone.NORMAL -> BAR_FILL
    BarTone.NEAR -> SoftColors.caution
    BarTone.OVER -> SoftColors.onAlert
}

/** The prototype's bar blue (`--fill`). */
val BAR_FILL = Color(0xFF8FA7D6)

/**
 * The bar: name and "spent / cap" on one line, the track filled to spent / cap, and the note
 * under it in the tone's colour. Tapping opens the category's transactions.
 */
@Composable
fun BudgetBar(model: BudgetBarModel, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val description = listOfNotNull(
        model.category, "${model.amountText} dollars", "${model.percent}% of cap", model.note,
    ).joinToString(", ")
    Column(
        modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 3.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                model.category,
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                model.amountText,
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text2,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        BarTrack(model.fill, model.tone.fillColor(), Modifier.padding(top = 4.dp))
        model.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (model.tone == BarTone.NORMAL) SoftColors.text3 else model.tone.fillColor(),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The track alone: [fill] of it coloured. Also the More card's month bar. */
@Composable
fun BarTrack(fill: Float, color: Color, modifier: Modifier = Modifier, height: Int = 8) {
    val shape = RoundedCornerShape((height / 2).dp)
    Box(
        modifier
            .padding(vertical = 3.dp)
            .fillMaxWidth()
            .height(height.dp)
            .background(SoftColors.cardHighest, shape),
    ) {
        if (fill > 0f) Box(Modifier.fillMaxWidth(fill).height(height.dp).background(color, shape))
    }
}
