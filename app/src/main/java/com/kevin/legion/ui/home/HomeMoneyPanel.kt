@file:Suppress("FunctionNaming", "MagicNumber", "LongParameterList")
// FunctionNaming: @Composable convention is PascalCase. MagicNumber: layout dp from the approved
// prototype (legion-money-prototypes/money-screens.html). LongParameterList: one callback per tap.

package com.kevin.legion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.ui.money.BarTone
import com.kevin.legion.ui.money.BarTrack
import com.kevin.legion.ui.money.BudgetBar
import com.kevin.legion.ui.money.fillColor
import com.kevin.legion.ui.money.moreLine
import com.kevin.legion.ui.theme.soft.SoftColors

/** The panel shows at most this many category bars; the rest are one tap away in the Money view. */
private const val MAX_BARS = 3

private val CARD_SHAPE = RoundedCornerShape(18.dp)

/**
 * HOME's Money panel (household-finance ticket 13, layout A): safe to spend with its [Calendar] and
 * [Brief] buttons, then this month's spent of cap and the categories closest to their cap.
 *
 * **It fits [fitPx] by dropping bars before anything else**: the full two-card layout with 3, 2, 1
 * bars; then one compact card (the figure on the buttons' row, the month line inside it) with 2, 1,
 * 0 bars. The figure, the top-up warning, any note, the month line and "+N more" are never dropped:
 * when even the compact card is taller than [fitPx], it takes what it needs (up to the layout's
 * limit) and the agenda under it gives way instead (CLAUDE.md section 4: a disclosure is not clipped).
 */
@Composable
fun HomeMoneyPanel(
    model: HomeMoneyPanelModel,
    onOpenMoney: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onCalendar: () -> Unit,
    onBrief: (() -> Unit)?,
    modifier: Modifier = Modifier,
    fitPx: Int? = null,
) {
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(minWidth = width, maxWidth = width)
        val bounded = constraints.maxHeight != Constraints.Infinity
        val room = fitPx ?: if (bounded) constraints.maxHeight else Int.MAX_VALUE
        val most = minOf(MAX_BARS, model.bars.size)
        val options = (most downTo minOf(1, most)).map { true to it } +
            (minOf(2, most) downTo 0).map { false to it }
        var chosen = emptyList<androidx.compose.ui.layout.Placeable>()
        for ((full, shown) in options) {
            chosen = subcompose("${if (full) "full" else "compact"}-$shown") {
                if (full) FullPanel(model, shown, onOpenMoney, onOpenCategory, onCalendar, onBrief)
                else CompactPanel(model, shown, onOpenMoney, onOpenCategory, onCalendar, onBrief)
            }.map { it.measure(loose) }
            if (chosen.sumOf { it.height } <= room) break
        }
        val total = chosen.sumOf { it.height }
        val height = if (bounded) total.coerceAtMost(constraints.maxHeight) else total
        layout(width, height) {
            var y = 0
            chosen.forEach { it.placeRelative(0, y); y += it.height }
        }
    }
}

@Composable
private fun FullPanel(
    model: HomeMoneyPanelModel,
    shown: Int,
    onOpenMoney: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onCalendar: () -> Unit,
    onBrief: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        SafeCard(model.safe, onOpenMoney, onCalendar, onBrief)
        if (model.monthLine != null || model.budgetNote != null) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier.fillMaxWidth().background(SoftColors.card, CARD_SHAPE)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) { BudgetLines(model, shown, onOpenMoney, onOpenCategory) }
        }
    }
}

/** The squeezed layout: one card, the figure on the buttons' row, the month line inside it. */
@Composable
private fun CompactPanel(
    model: HomeMoneyPanelModel,
    shown: Int,
    onOpenMoney: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onCalendar: () -> Unit,
    onBrief: (() -> Unit)?,
) {
    val safe = model.safe
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(SoftColors.card)
            .padding(start = 14.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open Money", onClick = onOpenMoney),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                safe.figure ?: "Safe to spend",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = if (safe.negative) SoftColors.onAlert else SoftColors.text,
                maxLines = 1,
            )
            Text(
                safe.until.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = SoftColors.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            onBrief?.let {
                PillButton("Brief", "Open today's brief", SoftColors.primaryContainer, SoftColors.onPrimaryContainer, it)
            }
            Spacer(Modifier.width(4.dp))
            PillButton("Calendar", "Open the calendar", SoftColors.cardHigh, SoftColors.text, onCalendar)
        }
        safe.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (safe.figure == null) SoftColors.text2 else SoftColors.caution,
            )
        }
        safe.topUp?.let { AlertLine(it, Modifier.padding(vertical = 2.dp)) }
        BudgetLines(model, shown, onOpenMoney, onOpenCategory)
    }
}

@Composable
private fun SafeCard(
    safe: SafeLineModel,
    onOpenMoney: () -> Unit,
    onCalendar: () -> Unit,
    onBrief: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(SoftColors.card)
            .clickable(role = Role.Button, onClickLabel = "Open Money", onClick = onOpenMoney)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Safe to spend",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = SoftColors.text3,
                modifier = Modifier.weight(1f),
            )
            onBrief?.let { PillButton("Brief", "Open today's brief", SoftColors.primaryContainer, SoftColors.onPrimaryContainer, it) }
            Spacer(Modifier.width(6.dp))
            PillButton("Calendar", "Open the calendar", SoftColors.cardHigh, SoftColors.text, onCalendar)
        }
        if (safe.figure != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    safe.figure,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (safe.negative) SoftColors.onAlert else SoftColors.text,
                    maxLines = 1,
                )
                safe.until?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = SoftColors.text3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp, bottom = 5.dp),
                    )
                }
            }
        }
        safe.note?.let {
            Text(
                it,
                style = if (safe.figure == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                color = if (safe.figure == null) SoftColors.text2 else SoftColors.caution,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        safe.topUp?.let { AlertLine(it, Modifier.padding(top = 6.dp)) }
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

/** The month line, any budget note, [shown] bars and "+N more". The caller draws the card. */
@Composable
private fun BudgetLines(
    model: HomeMoneyPanelModel,
    shown: Int,
    onOpenMoney: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        model.monthLine?.let { line ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenMoney),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftColors.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    model.monthPercent.orEmpty(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = SoftColors.text,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        model.budgetNote?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = SoftColors.caution)
        }
        model.bars.take(shown).forEach { bar ->
            BudgetBar(bar, Modifier.padding(top = 2.dp)) { onOpenCategory(bar.category) }
        }
        val more = moreLine(model.bars, shown)
        val legend = if (shown > 0 && model.bars.take(shown).any { it.pace != null }) "white tick: today's pace" else null
        listOfNotNull(more, legend).takeIf { it.isNotEmpty() }?.let {
            Text(
                it.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = SoftColors.text3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The More sheet's Money card (household-finance ticket 14): safe to spend, net worth (said
 * "partial" when the engine lists accounts not in it), the month's single bar, the two categories
 * closest to their cap, and the one warning there is. Tapping it opens the Money view.
 */
@Composable
fun MoneyGlanceCard(model: HomeMoneyPanelModel, netWorth: NetWorthGlance, onOpenMoney: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(SoftColors.cardHigh)
            .clickable(role = Role.Button, onClickLabel = "Open Money", onClick = onOpenMoney)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("Money", style = MaterialTheme.typography.titleSmall, color = SoftColors.text, modifier = Modifier.weight(1f))
            Text("open ›", style = MaterialTheme.typography.labelMedium, color = SoftColors.text3)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Safe to spend", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
                Text(
                    model.safe.figure ?: "not read",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
                    color = if (model.safe.negative) SoftColors.onAlert else SoftColors.text,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Net worth", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
                Text(
                    netWorth.figure ?: "not read",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
                    color = SoftColors.text,
                    maxLines = 1,
                )
                netWorth.tag?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3) }
            }
        }
        model.monthUsedLine?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3, modifier = Modifier.padding(top = 10.dp))
            BarTrack(model.monthFill, model.monthPace, model.monthTone.fillColor(), Modifier.padding(top = 4.dp), height = 6)
        }
        val closest = model.bars.take(2)
        if (closest.isNotEmpty()) {
            Text(
                "Closest: " + closest.joinToString(", ") { "${it.category} ${it.percent}%" },
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        listOfNotNull(model.safe.note, model.budgetNote).distinct().forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = SoftColors.caution, modifier = Modifier.padding(top = 4.dp))
        }
        glanceWarning(model)?.let { AlertLine(it, Modifier.padding(top = 8.dp)) }
    }
}

/** The one warning: a top-up first, else the first category over its cap, in words. */
internal fun glanceWarning(model: HomeMoneyPanelModel): String? =
    model.safe.topUp ?: model.bars.firstOrNull { it.tone == BarTone.OVER }?.let { bar ->
        "${bar.category} is ${bar.note?.substringBefore(",") ?: "over its cap"}."
    }
