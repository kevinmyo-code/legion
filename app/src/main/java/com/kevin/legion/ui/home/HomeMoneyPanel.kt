@file:Suppress("FunctionNaming", "MagicNumber", "LongParameterList", "LongMethod")
// FunctionNaming: @Composable convention is PascalCase. MagicNumber: layout dp from the approved
// prototype (legion-money-prototypes/money-screens.html). LongParameterList: one callback per tap.
// LongMethod: each card is one composable with its arguments wrapped one per line.

package com.kevin.legion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.money.BudgetBar
import com.kevin.legion.ui.money.BudgetBoardModel
import com.kevin.legion.ui.money.FixedLabel
import com.kevin.legion.ui.money.FixedRow
import com.kevin.legion.ui.money.TotalBudgetBar
import com.kevin.legion.ui.money.moreLine
import com.kevin.legion.ui.theme.soft.SoftColors

private val CARD_SHAPE = RoundedCornerShape(18.dp)

/** Below this the bars get no less room: one bar shows, and the agenda's squeezed floor gives way. */
private val MIN_BODY = 44.dp

/**
 * HOME's Money panel (Kevin, 2026-11-05: "how much ive spent this month vs how much left on the
 * budget, then drill into each category on tap"). The month's total budget bar with the [Calendar]
 * and [Brief] buttons on its label row, then EVERY capped category as a [BudgetBar], highest percent
 * used first, and the engine's fixed categories last under "Fixed".
 *
 * **It fits [fitPx], the room [HomeTodayArea] leaves above the agenda's minimum rows**: the header
 * (buttons, total bar, its words and any note) always shows whole; the bars under it scroll inside
 * what is left. Only when the header leaves less than one bar's room does the panel take more, up to
 * the layout's limit (the agenda's squeezed floor), so a disclosure is never clipped (CLAUDE.md
 * section 4).
 */
@Composable
fun HomeMoneyPanel(
    model: BudgetBoardModel,
    onOpenMoney: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onCalendar: () -> Unit,
    onBrief: (() -> Unit)?,
    modifier: Modifier = Modifier,
    fitPx: Int? = null,
) {
    SubcomposeLayout(modifier.fillMaxWidth().clip(CARD_SHAPE).background(SoftColors.card)) { constraints ->
        val width = constraints.maxWidth
        val bounded = constraints.maxHeight != Constraints.Infinity
        val limit = if (bounded) constraints.maxHeight else Int.MAX_VALUE
        val room = minOf(fitPx ?: limit, limit)
        val header = subcompose("header") { PanelHeader(model, onOpenMoney, onCalendar, onBrief) }
            .map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
        val headerH = header.sumOf { it.height }
        val floor = minOf(MIN_BODY.roundToPx(), (limit - headerH).coerceAtLeast(0))
        val bodyMax = (room - headerH).coerceAtLeast(floor)
        val body = subcompose("body") { PanelBody(model, onOpenCategory) }
            .map { it.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = bodyMax)) }
        val height = (headerH + body.sumOf { it.height }).coerceAtMost(limit)
        layout(width, height) {
            var y = 0
            (header + body).forEach { it.placeRelative(0, y); y += it.height }
        }
    }
}

@Composable
private fun PanelHeader(
    model: BudgetBoardModel,
    onOpenMoney: () -> Unit,
    onCalendar: () -> Unit,
    onBrief: (() -> Unit)?,
) {
    val buttons: @Composable RowScope.() -> Unit = {
        onBrief?.let {
            PillButton("Brief", "Open today's brief", SoftColors.primaryContainer, SoftColors.onPrimaryContainer, it)
        }
        Spacer(Modifier.width(4.dp))
        PillButton("Calendar", "Open the calendar", SoftColors.cardHigh, SoftColors.text, onCalendar)
    }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 2.dp, bottom = 2.dp)) {
        val total = model.total
        if (total != null) {
            TotalBudgetBar(total, onClick = onOpenMoney, trailing = buttons)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Budget",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = SoftColors.text,
                    modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpenMoney),
                )
                buttons()
            }
        }
        model.note?.let {
            Text(
                it,
                style = if (total == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                color = if (model.noteIsCaution) SoftColors.caution else SoftColors.text2,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
            )
        }
    }
}

/** The bars, then "Fixed", scrolling in whatever height the panel gives them. */
@Composable
private fun PanelBody(model: BudgetBoardModel, onOpenCategory: (String) -> Unit) {
    if (model.total == null) {
        Spacer(Modifier.height(8.dp))
        return
    }
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 14.dp, end = 14.dp, bottom = 8.dp)) {
            BoardLines(model, onOpenCategory)
        }
        if (scroll.canScrollForward) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, SoftColors.card))),
            )
        }
    }
}

/** Every category bar, then the fixed rows under their label. */
@Composable
private fun BoardLines(model: BudgetBoardModel, onOpenCategory: (String) -> Unit) {
    if (model.bars.isEmpty() && model.fixed.isEmpty()) {
        Text(
            "No category caps set yet.",
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.text2,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    model.bars.forEach { bar ->
        BudgetBar(bar, Modifier.padding(top = 2.dp)) { onOpenCategory(bar.category) }
    }
    if (model.fixed.isNotEmpty()) {
        FixedLabel()
        model.fixed.forEach { line -> FixedRow(line) { onOpenCategory(line.category) } }
    }
}

@Composable
internal fun AlertLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = SoftColors.onAlert,
        modifier = modifier
            .fillMaxWidth()
            .background(SoftColors.alertContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun PillButton(label: String, clickLabel: String, container: Color, content: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = content,
            maxLines = 1,
            modifier = Modifier
                .background(container, RoundedCornerShape(percent = 50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/**
 * The More sheet's Money card: the month's total budget bar and the three categories closest to or
 * over their cap. One tap anywhere opens the Money view, so its bars take no tap of their own.
 */
@Composable
fun MoneyGlanceCard(model: BudgetBoardModel, onOpenMoney: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(SoftColors.cardHigh)
            .clickable(role = Role.Button, onClickLabel = "Open Money", onClick = onOpenMoney)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "Money",
                style = MaterialTheme.typography.titleSmall,
                color = SoftColors.text,
                modifier = Modifier.weight(1f),
            )
            Text("open ›", style = MaterialTheme.typography.labelMedium, color = SoftColors.text3)
        }
        model.total?.let { TotalBudgetBar(it, Modifier.padding(top = 8.dp)) }
        model.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (model.noteIsCaution) SoftColors.caution else SoftColors.text2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        val closest = model.bars.take(GLANCE_BARS)
        closest.forEach { BudgetBar(it, Modifier.padding(top = 4.dp)) }
        moreLine(model.bars, closest.size)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = SoftColors.text3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The More card shows the three categories closest to or over their cap. */
private const val GLANCE_BARS = 3
