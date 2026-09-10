package com.kevin.legion.ui

import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ledger.uncategorizedExcludedSentence
import com.kevin.legion.ui.fleet.DueRowView

/**
 * The pure, plain-JUnit-testable builders `ui/MetersScreen.kt`'s own "Needs you"/Money/Groceries
 * panes used - **rehomed here, one-home ticket 02, once `MetersScreen.kt` folded into HOME**
 * (`.scratch/one-home/issues/02-rehome-the-orphans.md`). Nothing here is new logic; this is the
 * move ticket 02's own text asked for verbatim ("they should move, not die... put them in a file
 * that is not a screen"). See `ui/HomeMeterBands.kt` for the composable that now renders them and
 * `ui/MeterReadingsTest.kt` (moved alongside, same package, same assertions) for the 14 tests that
 * pinned this behaviour on `MetersScreen.kt` before the move.
 */

// ------------------------------------------------------------- Needs-you breach detection

/** Which callback a [MeterBreach] taps through to - see `ui/HomeMeterBands.kt`'s own `when` for the
 * real navigation lambda each one resolves to. No BODY/NOTES member: neither Intake nor the Lists
 * pane currently has a breach condition this file defines (see [buildMeterBreaches]'s own doc for
 * exactly which three do), and inventing a target nothing ever returns would be dead code a later
 * change could silently miss wiring correctly. */
enum class MetersBreachTarget { MONEY, MONEY_PANTRY, FLEET }

/** One breaching meter, worded rather than coloured (CLAUDE.md §7's "never colour alone" applied to
 * this pane) - [reason] is a full sentence fragment ready to sit in a [com.kevin.legion.ui.common.DeckRow]'s
 * value slot, e.g. "over budget by $42", never a bare boolean the row would have to re-word itself. */
data class MeterBreach(val label: String, val reason: String, val target: MetersBreachTarget)

/**
 * Three breach conditions, matching the three concrete types the original brief named ("overdue,
 * over budget, behind target") that actually apply to the five meters HOME now renders - intake and
 * the two list counts have no breach condition defined here, on purpose: CLAUDE.md's "do not invent
 * computation" reads onto breach detection as much as onto a meter's own reading, and nothing in
 * that brief described what "breaching" would even mean for a calorie count or an open-task count.
 *
 * **Money and Groceries both require a real, positive target before they can be "over" it** - the
 * same `target > 0` guard [com.kevin.legion.ui.common.DeckMeter]'s own callers already use, because
 * a month with no budget set is an empty state, not a breach (CLAUDE.md's empty-vs-unreadable
 * discipline again: "no budget set" and "over budget" are different facts about the same null gap).
 * **Money's own total deliberately does not double-report Groceries' own overage as a SEPARATE
 * breach reason** - it does not need to: [BudgetVsActual.spentCents] already sums every category's
 * actual, Groceries included, so a Groceries overage that also pushes the whole month over is
 * reported as ONE Money breach AND, separately, its own Groceries breach - two true facts about two
 * different totals, not one fact stated twice.
 */
fun buildMeterBreaches(budget: BudgetVsActual?, maintenanceRows: List<DueRowView>): List<MeterBreach> {
    val breaches = mutableListOf<MeterBreach>()

    if (budget != null) {
        val targetCents = budget.lines.sumOf { it.gap.target }
        val spentCents = budget.spentCents
        if (targetCents > 0 && spentCents > targetCents) {
            val overCents = spentCents - targetCents
            breaches += MeterBreach(
                label = "Money",
                reason = "over budget by ${formatMoney(overCents, budget.entity.currency)}",
                target = MetersBreachTarget.MONEY,
            )
        }

        val groceriesLine = budget.lines.firstOrNull { it.category == "Groceries" }
        if (groceriesLine != null && groceriesLine.gap.target > 0 && groceriesLine.gap.gap < 0) {
            val overCents = -groceriesLine.gap.gap
            breaches += MeterBreach(
                label = "Groceries",
                reason = "over budget by ${formatMoney(overCents, budget.entity.currency)}",
                target = MetersBreachTarget.MONEY_PANTRY,
            )
        }
    }

    val overdueCount = maintenanceRows.count { it.overdue }
    if (overdueCount > 0) {
        breaches += MeterBreach(
            label = "Maintenance",
            reason = if (overdueCount == 1) "1 item overdue" else "$overdueCount items overdue",
            target = MetersBreachTarget.FLEET,
        )
    }

    return breaches
}

// ------------------------------------------------------------- Money pane helpers

/**
 * The Money pane's uncategorised-exclusion caveat, `null` exactly when there is nothing to
 * disclose.
 *
 * **Deliberately NOT a passthrough of [uncategorizedExcludedSentence]** - that builder is
 * empty-safe by its own design (it always returns a sentence, wording the zero case as "Nothing
 * uncategorised this month..." rather than returning nothing), which is correct for a surface with
 * room for a permanent caveat line but would be furniture on a pane this sparse: a sentence that
 * reads the same whether there is something to disclose or not is not a disclosure, CLAUDE.md's
 * standing "a disclosure is never furniture" rule. So the gate is here, at the call site, extracted
 * into its own plain-JUnit-testable function rather than left inline in the composable - and it is
 * a gate, never a second reading of the figure: the non-zero branch still asks
 * [uncategorizedExcludedSentence] for the words, once.
 */
fun moneyUncategorizedSentence(budget: BudgetVsActual): String? {
    if (budget.uncategorized.spentCents == 0L) return null
    return uncategorizedExcludedSentence(budget.uncategorized, budget.entity.currency)
}

/**
 * The Groceries meter's hero: the actual spend, never the budget target. A pure wrapper around
 * [formatMoney] rather than an inline expression at the call site so this exact regression - a
 * confident hero number that is not the number its own caption promises - has a unit test pinned
 * to it (see `MeterReadingsTest`'s own case). [BudgetLine.gap]'s own `actual` field is the money
 * that was really spent this month on this category; `target` is the budget line's ceiling and
 * `gap` is the REMAINING/OVER distance between the two - three different numbers, and only `actual`
 * belongs in a row whose label reads "Groceries" and whose caption directly beneath states "USD X
 * of USD Y".
 */
fun groceriesHeroValue(line: BudgetLine, currency: LedgerCurrency): String =
    formatMoney(line.gap.actual, currency)
