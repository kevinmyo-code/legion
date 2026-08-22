package com.kevin.legion.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * The Today screen's signature component (`.scratch/legion-shape/issues/05-target-log-gap-vocabulary.md`
 * D5: "the gap is ONE computation - target minus actual - and remaining/adherence/overdue/
 * distance-from-target are four DISPLAYS of that one subtraction, in different units, never four
 * separate algorithms"). One row renders one tracked thing, in whatever unit it happens to be in -
 * dollars, sets, calories, miles - because [GapRowData] carries only already-FORMATTED strings and
 * a semantic sign/tier, never a raw number. **Money stays `Long` cents up to the call site**
 * (CLAUDE.md §4 rule 3); this file only ever sees the string [com.kevin.legion.ledger.formatMoney]
 * or a domain's own formatter already produced.
 *
 * Built ONCE, on purpose (Kevin's brief, 2026-08-07): if a caller needs a fifth slot or a
 * domain-shaped variant, the shared shape is wrong and that is a decision for Kevin, not a
 * per-screen workaround.
 *
 * Four parts, always in this order:
 *  1. [GapRowData.label] - what is being tracked.
 *  2. [GapRowData.actualOverTarget] - the raw numbers, mono, faint (`LegionType.stamp`) - "how you
 *     got the gap", not the headline.
 *  3. [GapRowData.gapValue] + [GapRowData.gapCaption] - the gap itself, right-aligned, mono
 *     (`LegionType.amount`). **This is the only coloured thing on the row** - see [gapColor].
 *  4. [GapRowData.tierNote] - a WORDS-only line beneath, rendered only when non-null. CLAUDE.md §4
 *     rule five/[com.kevin.legion.ui.theme.LegionSemantics.estimated]'s own doc comment: colour
 *     alone is never sufficient to mark a figure as leaning on anything unproven ([TrustTier.REPORTED]),
 *     it fails in greyscale and for colour-blind readers, so the label carries the meaning and the
 *     colour only reinforces it.
 *
 * [GapRowData.sign]/[GapRowData.tier] are resolved into an actual [androidx.compose.ui.graphics.Color]
 * only inside [GapRow] itself, via [LocalLegionSemantics] - never baked into [GapRowData] - because
 * [GapRowData] is built by plain, Compose-free resolver functions (`buildDailyMealGapRowData`,
 * ledger's own budget-line mapper, fleet's maintenance-row mapper) so those stay unit-testable JVM
 * code, the same "pure builder, thin Composable wrapper" split
 * [com.kevin.legion.ledger.buildBudgetVsActual]/[com.kevin.legion.workouts.buildWeeklyWorkoutGap]
 * already established.
 *
 * [GapRow]'s optional `onClick` (Kevin, 2026-08-07: "let me press it and drill down") turns a row
 * into a link WITHOUT changing its appearance or layout for the many callers that don't opt in -
 * default `null` means exactly today's non-interactive rendering, no ripple target added. Matches
 * `ui.ledger.BudgetSection`'s existing `Modifier.clickable(onClick = onClick)` precedent
 * (`BudgetLineRow`/`UncategorizedRow`) rather than inventing a second affordance convention - a
 * plain `clickable` already carries the platform ripple, the only visual cue a tappable row needs.
 */
data class GapRowData(
    val label: String,
    val actualOverTarget: String,
    val gapValue: String,
    val gapCaption: String,
    val sign: GapSign = GapSign.NEUTRAL,
    val tier: TrustTier = TrustTier.PROVEN,
    val tierNote: String? = null,
)

/**
 * Which way [GapRowData.gapValue] leans, BEFORE tier is taken into account. [GOOD]/[BAD] map to
 * [com.kevin.legion.ui.theme.LegionSemantics.credit]/`debit` respectively - "credit" and "debit"
 * are ledger words but the roles are domain-neutral (room left in a budget, ahead on a workout
 * plan, both read as [GOOD]; over budget or behind on sessions both read as [BAD]).
 * [TrustTier.REPORTED] always overrides to `estimated` regardless of [sign] - see [GapRow]'s doc.
 */
enum class GapSign { GOOD, BAD, NEUTRAL }

@Composable
fun GapRow(data: GapRowData, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val sem = LocalLegionSemantics.current
    // D6 (ticket 05): "one reported actual makes the WHOLE gap reported" - a
    // REPORTED tier always wins over sign, matching BudgetLineRow's existing
    // `if (tier == REPORTED) sem.estimated else colorForGap(...)` precedent.
    val gapColor = when {
        data.tier == TrustTier.REPORTED -> sem.estimated
        data.sign == GapSign.GOOD -> sem.credit
        data.sign == GapSign.BAD -> sem.debit
        else -> MaterialTheme.colorScheme.onSurface
    }
    // `onClick == null` -> the exact same Modifier chain as before this change, byte for byte -
    // every non-opted-in caller (Body, ledger's own rows, fleet) renders identically.
    val rowModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Column(rowModifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(data.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(data.actualOverTarget, style = LegionType.stamp, color = sem.faint)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(data.gapValue, style = LegionType.amount, color = gapColor)
                Text(data.gapCaption, style = LegionType.stamp, color = sem.faint)
            }
        }
        if (data.tierNote != null) {
            Spacer(Modifier.height(2.dp))
            Text(data.tierNote, style = LegionType.stamp, color = sem.faint)
        }
    }
}

/**
 * The empty-state counterpart to [GapRow] - ticket brief: "an empty row must say what to do, in
 * the interface's voice, naming the voice command... never a blank, never a zero, never 'no data'."
 * [message] is expected to end with the exact spoken command in quotes, e.g. `say "set a grocery
 * budget"` - callers own the copy, this just lays it out consistently with [GapRow]'s row rhythm
 * (same padding, same faint ink) so a panel mixing populated and empty rows doesn't visually jump.
 */
@Composable
fun GapEmptyRow(label: String, message: String, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = sem.faint)
        Spacer(Modifier.height(2.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = sem.faint)
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "GapRow: money, room left (PROVEN)", widthDp = 360)
@Composable
private fun PreviewGapRowMoneyGood() = LegionTheme {
    Surface {
        GapRow(
            GapRowData(
                label = "Groceries",
                actualOverTarget = "USD 412.00 of USD 600.00",
                gapValue = "USD 188.00",
                gapCaption = "remaining",
                sign = GapSign.GOOD,
                tier = TrustTier.PROVEN,
            ),
        )
    }
}

@Preview(name = "GapRow: money, over budget (PROVEN)", widthDp = 360)
@Composable
private fun PreviewGapRowMoneyBad() = LegionTheme {
    Surface {
        GapRow(
            GapRowData(
                label = "Dining Out",
                actualOverTarget = "USD 245.00 of USD 200.00",
                gapValue = "USD -45.00",
                gapCaption = "over",
                sign = GapSign.BAD,
                tier = TrustTier.PROVEN,
            ),
        )
    }
}

@Preview(name = "GapRow: calories, under target (REPORTED)", widthDp = 360)
@Composable
private fun PreviewGapRowCalories() = LegionTheme {
    Surface {
        GapRow(
            GapRowData(
                label = "Calories today",
                actualOverTarget = "1,650 of 2,200 kcal",
                gapValue = "550",
                gapCaption = "kcal left",
                sign = GapSign.GOOD,
                tier = TrustTier.REPORTED,
                tierNote = "estimated from what you told me, not measured",
            ),
        )
    }
}

@Preview(name = "GapRow: miles, overdue", widthDp = 360)
@Composable
private fun PreviewGapRowMilesOverdue() = LegionTheme {
    Surface {
        GapRow(
            GapRowData(
                label = "Oil Change",
                actualOverTarget = "every 5,000 mi - last at 130,200",
                gapValue = "OVERDUE",
                gapCaption = "",
                sign = GapSign.BAD,
                tier = TrustTier.PROVEN,
            ),
        )
    }
}

@Preview(name = "GapRow: miles, not yet due", widthDp = 360)
@Composable
private fun PreviewGapRowMilesUpcoming() = LegionTheme {
    Surface {
        GapRow(
            GapRowData(
                label = "Tire Rotation",
                actualOverTarget = "every 6 mo - last Feb 2026",
                gapValue = "in 3 mo",
                gapCaption = "",
                sign = GapSign.NEUTRAL,
                tier = TrustTier.PROVEN,
            ),
        )
    }
}

@Preview(name = "GapEmptyRow: no grocery budget set", widthDp = 360)
@Composable
private fun PreviewGapEmptyBudget() = LegionTheme {
    Surface {
        GapEmptyRow(
            label = "Groceries",
            message = "No budget set yet - say \"set a $500 budget for groceries\", for example.",
        )
    }
}

@Preview(name = "GapEmptyRow: meal not logged", widthDp = 360)
@Composable
private fun PreviewGapEmptyMeal() = LegionTheme {
    Surface {
        GapEmptyRow(
            label = "Calories today",
            message = "Not logged yet - say \"log a meal\" and describe what you ate.",
        )
    }
}
