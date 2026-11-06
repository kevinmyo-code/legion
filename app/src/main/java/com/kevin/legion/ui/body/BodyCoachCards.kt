@file:Suppress("FunctionNaming", "MagicNumber", "LongParameterList", "TooManyFunctions")
// FunctionNaming: @Composable convention is PascalCase. MagicNumber: layout dp from the prototype.

package com.kevin.legion.ui.body

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.bodycoach.EnergyWire
import com.kevin.legion.bodycoach.TrendWire
import com.kevin.legion.ui.money.BAR_FILL
import com.kevin.legion.ui.money.BarTrack
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Body screen's cards, direction A of the fat-loss revamp (Kevin picked it 2026-11-05;
 * `.scratch/fat-loss/` tickets 20 and 21): one scroll of cards, closest to the Money view. Plain
 * data in, callbacks out; [com.kevin.legion.ui.BodyScreen] is the one state holder.
 *
 * **Every figure the engine estimates says "estimate" in words** (CLAUDE.md section 4 rule 5), the
 * band status is a sentence, never a colour alone, and nothing here counts days missed or a streak
 * (CLAUDE.md section 7). Today's bars are Money's budget bars (fill, no pace marker). Unlogged is
 * "nothing logged yet", never a zero.
 */

/** The band Kevin chose, 0.5 to 1.0 % of bodyweight a week (ruling 10). */
private const val BAND_LOW = 0.5
private const val BAND_HIGH = 1.0
private const val BAND_SCALE_MAX = 1.5

@Composable
internal fun BodyCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SoftColors.card, RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(title: String, aside: String? = null, estimate: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = SoftColors.text, fontWeight = FontWeight.SemiBold)
        aside?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text3) }
        if (estimate) EstimateTag()
    }
}

/** The word, in an outlined tag: the estimate label never rides on colour. */
@Composable
internal fun EstimateTag() {
    Text(
        "estimate",
        style = MaterialTheme.typography.labelSmall,
        color = SoftColors.caution,
        modifier = Modifier
            .background(SoftColors.cardHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// ------------------------------------------------------------------------------- quick log

/** Four large tiles: weight, meal, waist, workout. Each opens the hands path for that log. */
@Composable
internal fun BodyQuickLogRow(onWeight: () -> Unit, onMeal: () -> Unit, onWaist: () -> Unit, onWorkout: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickTile("Weight", R.drawable.ms_scale, onWeight, Modifier.weight(1f))
        QuickTile("Meal", R.drawable.ms_restaurant, onMeal, Modifier.weight(1f))
        QuickTile("Waist", R.drawable.ms_straighten, onWaist, Modifier.weight(1f))
        QuickTile("Workout", R.drawable.ms_fitness_center, onWorkout, Modifier.weight(1f))
    }
}

@Composable
private fun QuickTile(label: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .background(SoftColors.card, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClickLabel = "Log $label", onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MsIcon(icon, contentDescription = null, tint = SoftColors.primary, size = 26.dp)
        Text(label, style = MaterialTheme.typography.labelLarge, color = SoftColors.text)
    }
}

// ------------------------------------------------------------------------------- trend

/** The band status in words: inside, below, above, or not losing at all. Null with no rate. */
internal fun bandWords(ratePctPerWeek: Double?): String? {
    val r = ratePctPerWeek ?: return null
    val loss = -r
    return when {
        loss <= 0.0 -> "Not losing: the trend is flat or rising"
        loss < BAND_LOW -> "Below your 0.5-1.0% band"
        loss > BAND_HIGH -> "Faster than your 0.5-1.0% band"
        else -> "Inside your 0.5-1.0% band"
    }
}

internal fun signed(value: Double, decimals: Int = 1): String {
    val text = "%.${decimals}f".format(abs(value))
    return if (value < 0) "-$text" else "+$text"
}

/**
 * The trend card: smoothed weight, rate a week against the band, the EWMA line over raw dots, and
 * the milestone. Replaces the old two-reading delta (ticket 05). [fallback] is the latest raw
 * reading in words, shown when the engine's trend cannot be read; [note] says why.
 */
@Composable
internal fun BodyTrendCard(trend: TrendWire?, note: String?, fallback: String?, onOpenHistory: () -> Unit) {
    BodyCard(Modifier.clickable(role = Role.Button, onClickLabel = "Open weigh-in history", onClick = onOpenHistory)) {
        CardTitle("Trend", aside = "last 6 weeks")
        if (trend == null || trend.latestTrendLbs == null) {
            Text(fallback ?: "No weigh-ins yet.", style = MaterialTheme.typography.bodyLarge, color = SoftColors.text, modifier = Modifier.padding(top = 8.dp))
            Text(note ?: "The trend needs a weigh-in.", style = MaterialTheme.typography.bodySmall, color = SoftColors.text3, modifier = Modifier.padding(top = 4.dp))
            return@BodyCard
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
            Text("%.1f".format(trend.latestTrendLbs), style = MaterialTheme.typography.displaySmall, color = SoftColors.text, fontWeight = FontWeight.SemiBold)
            Text(" lb", style = MaterialTheme.typography.titleMedium, color = SoftColors.text2, modifier = Modifier.padding(bottom = 6.dp))
        }
        val rateText = if (trend.rateLbsPerWeek != null && trend.ratePctPerWeek != null) {
            "${signed(trend.rateLbsPerWeek)} lb a week, ${signed(trend.ratePctPerWeek, 2)}% of bodyweight"
        } else {
            "A rate needs 14 days of weigh-ins."
        }
        Text(rateText, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
        if (trend.earlyWater) {
            Text(
                "Early weeks: much of the first drop is water, so it is not projected.",
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text3,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        bandWords(trend.ratePctPerWeek)?.let { words ->
            val inside = words.startsWith("Inside")
            Row(
                Modifier.padding(top = 10.dp).background(SoftColors.cardHigh, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (inside) MsIcon(R.drawable.ms_check, contentDescription = null, tint = SoftColors.good, size = 16.dp)
                Text(words, style = MaterialTheme.typography.labelLarge, color = if (inside) SoftColors.good else SoftColors.caution)
            }
            BandMeter(trend.ratePctPerWeek?.let { -it })
        }
        TrendChart(trend, Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(SoftColors.primary, "Trend (smoothed)", line = true)
            LegendSwatch(SoftColors.text3, "Daily weigh-ins", line = false)
        }
        trend.milestone?.let { m ->
            Spacer(Modifier.height(14.dp))
            val start = m.startLbs
            val total = start?.let { abs(it - m.targetLbs) }
            val done = if (start != null) abs(start - trend.latestTrendLbs).coerceAtMost(total ?: 0.0) else null
            Row(Modifier.fillMaxWidth()) {
                Text("Milestone ${"%.0f".format(m.targetLbs)} lb", style = MaterialTheme.typography.bodyMedium, color = SoftColors.text, modifier = Modifier.weight(1f))
                if (done != null && total != null && m.progressPct != null) {
                    Text("${"%.1f".format(done)} of ${"%.0f".format(total)} lb, ${m.progressPct}%", style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
                }
            }
            BarTrack(((m.progressPct ?: 0) / 100f).coerceIn(0f, 1f), SoftColors.primary, Modifier.padding(top = 4.dp))
            val words = listOfNotNull(
                start?.let { "Started at ${"%.1f".format(it)} lb." },
                m.remainingLbs?.let { "${"%.1f".format(it)} lb to go." },
                m.weeksToGo?.let { "About ${it.roundToInt()} weeks at this rate (estimate)." },
            ).joinToString(" ")
            if (words.isNotBlank()) Text(words, style = MaterialTheme.typography.bodySmall, color = SoftColors.text3, modifier = Modifier.padding(top = 2.dp))
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** 0 to 1.5 % a week, the 0.5-1.0 band shaded, a marker at the trend's loss rate. */
@Composable
private fun BandMeter(lossPct: Double?) {
    val bandColor = SoftColors.good.copy(alpha = 0.55f)
    val track = SoftColors.cardHighest
    val marker = SoftColors.text
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(12.dp)
            .semantics { contentDescription = "Loss rate against the 0.5 to 1.0 percent band" },
    ) {
        val h = size.height / 2
        drawLine(track, Offset(0f, h), Offset(size.width, h), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        val x = { pct: Double -> (pct / BAND_SCALE_MAX * size.width).toFloat() }
        drawLine(bandColor, Offset(x(BAND_LOW), h), Offset(x(BAND_HIGH), h), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        lossPct?.let {
            val mx = x(it.coerceIn(0.0, BAND_SCALE_MAX))
            drawLine(marker, Offset(mx, 0f), Offset(mx, size.height), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text("0%", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3, modifier = Modifier.weight(1f))
        Text("0.5", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3, modifier = Modifier.weight(1f))
        Text("1.0", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3, modifier = Modifier.weight(1f))
        Text("1.5%", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
    }
}

/** The EWMA trend as a line over the raw daily weigh-ins as dots, with min and max labels. */
@Composable
private fun TrendChart(trend: TrendWire, modifier: Modifier = Modifier) {
    val points = trend.points.takeLast(42)
    if (points.size < 2) {
        Text("The chart needs two days of weigh-ins.", style = MaterialTheme.typography.bodySmall, color = SoftColors.text3, modifier = modifier)
        return
    }
    val values = points.flatMap { listOfNotNull(it.weightLbs, it.trendLbs) }
    val lo = values.min() - 1.0
    val hi = values.max() + 1.0
    val lineColor = SoftColors.primary
    val dotColor = SoftColors.text3
    val grid = SoftColors.barRule
    Row(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .weight(1f)
                .height(140.dp)
                .semantics { contentDescription = "Weight trend line over daily weigh-ins, ${points.size} days" },
        ) {
            val dx = size.width / (points.size - 1)
            val y = { v: Double -> (size.height - ((v - lo) / (hi - lo) * size.height)).toFloat() }
            for (g in 0..2) {
                val gy = size.height * g / 2
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
            }
            points.forEachIndexed { i, p ->
                p.weightLbs?.let { drawCircle(dotColor, radius = 3.dp.toPx(), center = Offset(i * dx, y(it))) }
            }
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(0f, y(p.trendLbs)) else path.lineTo(i * dx, y(p.trendLbs))
            }
            drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset((points.size - 1) * dx, y(points.last().trendLbs)))
        }
        Column(Modifier.height(140.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("%.0f".format(hi), style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
            Text("%.0f".format((hi + lo) / 2), style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
            Text("%.0f".format(lo), style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String, line: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (line) {
            Box(Modifier.width(16.dp).height(3.dp).background(color, RoundedCornerShape(2.dp)))
        } else {
            Box(Modifier.size(7.dp).background(color, CircleShape))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
    }
}

// ------------------------------------------------------------------------------- today

/** What today's bars show. Null eaten means nothing is logged today: said in words, never 0. */
internal data class TodayIntake(
    val kcalEaten: Int?,
    val kcalTarget: Int?,
    val proteinEaten: Int?,
    val proteinTarget: Int?,
)

internal fun leftWords(eaten: Int?, target: Int?, unit: String): String = when {
    eaten == null -> "Nothing logged yet today."
    target == null || target <= 0 -> "No target set. Estimate, from what you logged."
    eaten <= target -> "%,d %s left. Estimate, from what you logged.".format(target - eaten, unit)
    else -> "%,d %s over. Estimate, from what you logged.".format(eaten - target, unit)
}

@Composable
internal fun BodyTodayCard(today: TodayIntake, onSavedMeals: () -> Unit, onLogMeal: () -> Unit, onEditTarget: () -> Unit) {
    BodyCard {
        CardTitle("Today", estimate = true)
        IntakeBar("Calories", today.kcalEaten, today.kcalTarget, "kcal")
        IntakeBar("Protein", today.proteinEaten, today.proteinTarget, "g")
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BodyAction("Saved meals", onClick = onSavedMeals, quiet = true)
            BodyAction("Log meal", onClick = onLogMeal)
        }
        BodyAction("Edit target", onClick = onEditTarget, quiet = true, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun IntakeBar(label: String, eaten: Int?, target: Int?, unit: String) {
    val amount = when {
        eaten == null && target != null -> "- / %,d %s".format(target, unit)
        eaten == null -> "not logged"
        target != null -> "%,d / %,d %s".format(eaten, target, unit)
        else -> "%,d %s".format(eaten, unit)
    }
    val fill = if (eaten != null && target != null && target > 0) (eaten.toFloat() / target).coerceIn(0f, 1f) else 0f
    val words = leftWords(eaten, target, unit)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $amount, $words" },
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text, modifier = Modifier.weight(1f))
            Text(amount, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
        }
        BarTrack(fill, BAR_FILL, Modifier.padding(top = 4.dp))
        Text(words, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
    }
}

// ------------------------------------------------------------------------------- energy

internal fun basisLine(e: EnergyWire): String = when (e.basis) {
    "trend" -> "From your trend, ${e.basisDays ?: 14} days. The formula is the fallback."
    "formula" -> "From the Mifflin-St Jeor formula and your activity level."
    else -> e.basisWords.ifBlank { "Not enough to estimate yet." }
}

/**
 * Maintenance and its basis, the target for the chosen rate, protein, and a pending proposal with
 * Accept and Dismiss. A proposal is only ever applied by Accept (ticket 06). [busy] greys both
 * buttons while a write is in flight; [result] is the last write's own sentence.
 */
@Composable
internal fun BodyEnergyCard(
    energy: EnergyWire?,
    note: String?,
    busy: Boolean,
    result: String?,
    onAccept: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onEditProfile: () -> Unit,
) {
    BodyCard {
        CardTitle("Energy", estimate = true)
        if (energy == null) {
            Text(note ?: "The estimate could not be read.", style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2, modifier = Modifier.padding(top = 8.dp))
        } else {
            if (energy.maintenanceKcal == null && energy.missing.isNotEmpty()) {
                Text(
                    "The estimate needs your ${energy.missing.joinToString(", ")}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            energy.maintenanceKcal?.let { EnergyRow("Maintenance", basisLine(energy), "%,d kcal".format(it)) }
            energy.suggestedTargetKcal?.let {
                EnergyRow("Target", "For the rate you chose, ${energy.targetRatePct ?: 0.75}% of bodyweight a week.", "%,d kcal".format(it))
            }
            energy.currentTarget?.let { t ->
                if (t.caloriesKcal != energy.suggestedTargetKcal) {
                    EnergyRow("Your target now", "What the meters measure against.", "%,d kcal".format(t.caloriesKcal))
                }
            }
            energy.proteinG?.let {
                EnergyRow("Protein", "To keep muscle, ${energy.proteinGPerKg ?: 2.0} g per kg of ${energy.proteinBasis ?: "bodyweight"}.", "$it g")
            }
            energy.proposal?.let { p ->
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .background(SoftColors.cardHigh, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                ) {
                    Text("Proposal, every two weeks", style = MaterialTheme.typography.labelMedium, color = SoftColors.text3)
                    Text(
                        "Change target %,d to %,d kcal?".format(p.currentTargetKcal, p.proposedTargetKcal),
                        style = MaterialTheme.typography.titleSmall,
                        color = SoftColors.text,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (p.words.isNotBlank()) Text(p.words, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2, modifier = Modifier.padding(top = 2.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProposalButton("Accept", primary = true, enabled = !busy) { onAccept(p.id) }
                        ProposalButton("Dismiss", primary = false, enabled = !busy) { onDismiss(p.id) }
                    }
                }
            }
            if (energy.words.isNotBlank()) Text(energy.words, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3, modifier = Modifier.padding(top = 10.dp))
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3, modifier = Modifier.padding(top = 4.dp)) }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text, modifier = Modifier.padding(top = 8.dp)) }
        BodyAction("Profile", onClick = onEditProfile, quiet = true, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun EnergyRow(label: String, sub: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = SoftColors.text, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ProposalButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (primary) SoftColors.primaryContainer else SoftColors.cardHighest, RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> SoftColors.text3
                primary -> SoftColors.onPrimaryContainer
                else -> SoftColors.text
            },
        )
    }
}

// ------------------------------------------------------------------------------- this week

/** Training sessions this week and the latest waist measurement. */
@Composable
internal fun BodyWeekCard(
    sessionsDone: Int?,
    sessionsPlanned: Int?,
    waistIn: Double?,
    waistChange28d: Double?,
    onOpenTraining: () -> Unit,
) {
    BodyCard {
        CardTitle("This week")
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(role = Role.Button, onClickLabel = "Open training history", onClick = onOpenTraining)) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        sessionsPlanned == null -> "No training plan yet"
                        else -> "${sessionsDone ?: 0} of $sessionsPlanned sessions"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = SoftColors.text,
                )
                Text("Training, from what you logged", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
            }
            if (sessionsPlanned != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(sessionsPlanned.coerceAtMost(7)) { i ->
                        val done = i < (sessionsDone ?: 0)
                        Box(
                            Modifier.size(22.dp).background(if (done) SoftColors.primaryContainer else SoftColors.cardHighest, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (done) MsIcon(R.drawable.ms_check, contentDescription = null, tint = SoftColors.onPrimaryContainer, size = 14.dp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Waist", style = MaterialTheme.typography.titleSmall, color = SoftColors.text)
                Text("Measured weekly, beside the scale", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(waistIn?.let { "%.1f in".format(it) } ?: "Not measured yet", style = MaterialTheme.typography.titleSmall, color = SoftColors.text)
                waistChange28d?.let { Text("${signed(it, 2)} in over 4 weeks", style = MaterialTheme.typography.labelSmall, color = SoftColors.text3) }
            }
        }
    }
}

// ------------------------------------------------------------------------------- more

/** The rest, one row each. The checklist lives here now, off the main view (Kevin's ask). */
@Composable
internal fun BodyMoreList(
    onPlan: () -> Unit,
    onSleep: () -> Unit,
    onGoals: () -> Unit,
    onWeighIns: () -> Unit,
    onMeals: () -> Unit,
) {
    BodyCard {
        CardTitle("More")
        MoreRow("Today's plan", R.drawable.ms_checklist, onPlan)
        MoreRow("Sleep", R.drawable.ms_bedtime, onSleep)
        MoreRow("Goals", R.drawable.ms_task_alt, onGoals)
        MoreRow("Weigh-ins", R.drawable.ms_history, onWeighIns)
        MoreRow("Meals", R.drawable.ms_restaurant, onMeals)
    }
}

@Composable
private fun MoreRow(label: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MsIcon(icon, contentDescription = null, tint = SoftColors.text2, size = 20.dp)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = SoftColors.text, modifier = Modifier.weight(1f))
        MsIcon(R.drawable.ms_arrow_forward, contentDescription = null, tint = SoftColors.text3, size = 18.dp)
    }
}
