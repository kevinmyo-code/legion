package com.kevin.legion.ui.home

import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.ExcludedOwnAccountMovements
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.meals.DailyMealGap
import com.kevin.legion.meals.MacroTotals
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.ui.fleet.DueRowView
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every HOME tile/today-card status string, pinned as plain-JUnit cases (home-launcher ticket 03,
 * verification step 2's own list: "over budget, groceries over, uncategorized present, provisional
 * present, estimated body, not logged, overdue maintenance, calendar read failure, weather null,
 * location refused"). `budgetFixture` mirrors `ui/MeterReadingsTest.kt`'s own helper of the same
 * name - not shared, since that one is a package-private detail of its own class, same posture that
 * file's own doc comment already states about `TodayGapResolversTest`.
 */
class HomeTileReadingsTest {

    private fun budgetFixture(
        lines: List<BudgetLine>,
        uncategorized: UncategorizedSpend = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
    ): BudgetVsActual =
        BudgetVsActual(
            entity = LedgerEntity.US,
            month = YearMonth.of(2026, 9),
            lines = lines,
            uncategorized = uncategorized,
            coverage = listOf(
                AccountCoverage(
                    "7823", coversWholeMonth = true, coveredFromMs = 0L, coveredToMs = 1L, coveredThroughMs = 1L,
                ),
            ),
            excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
        )

    @Test
    fun `the Money tile says in words how much Transfers left out of spend`() {
        val budget = budgetFixture(emptyList()).copy(
            notSpendingExcluded = com.kevin.legion.ledger.NotSpendingExcluded(
                2, 650_00L, listOf("Transfers"), emptyList(),
            ),
        )
        val line = moneyDisclosureLine(budget)!!
        assertTrue(line, line.contains("Excludes"))
        assertTrue(line, line.contains("in Transfers"))
        assertTrue(line, line.contains(com.kevin.legion.ledger.formatMoney(650_00L, LedgerCurrency.USD)))
    }

    private fun dueRow(overdue: Boolean): DueRowView =
        DueRowView(label = "Oil change", value = "1,200 mi", sub = "every 5,000 mi", overdue = overdue)

    // ---------------------------------------------------------------- Calendar / chips

    @Test
    fun `calendar read failure renders its own worded chip, in alert, never a silent nothing due`() {
        val chips = buildTodayChips(dueTodayCount = 0, overdueCount = 0, calendarReadFailed = true)
        assertTrue(chips.readFailed)
        assertEquals("Couldn't read the calendar", chips.dueTodayText)
        assertNull(chips.overdueText)
        val tile = calendarTileStatus(chips)
        assertTrue(tile.alert)
        assertEquals("Couldn't read the calendar", tile.text)
    }

    @Test
    fun `the calendar tile restates the same due count the today card's chips carry`() {
        val chips = buildTodayChips(dueTodayCount = 3, overdueCount = 1, calendarReadFailed = false)
        assertEquals("3 due today", chips.dueTodayText)
        assertEquals("1 overdue", chips.overdueText)
        val tile = calendarTileStatus(chips)
        assertEquals("3 due today, 1 overdue", tile.text)
        assertTrue("a due-count restatement is not itself an alert", !tile.alert)
    }

    @Test
    fun `zero due and zero overdue carries no overdue chip at all`() {
        val chips = buildTodayChips(dueTodayCount = 0, overdueCount = 0, calendarReadFailed = false)
        assertEquals("0 due today", chips.dueTodayText)
        assertNull(chips.overdueText)
    }

    // ---------------------------------------------------------------- Lists

    @Test
    fun `lists tile wording for zero, one and many`() {
        assertEquals("No lists yet", listsTileStatus(0).text)
        assertEquals("1 list", listsTileStatus(1).text)
        assertEquals("4 lists", listsTileStatus(4).text)
    }

    @Test
    fun `a failed lists read wins over a zero count, in alert - audit finding 1`() {
        val status = listsTileStatus(checklistCount = 0, failed = true)
        assertTrue(status.alert)
        assertEquals("Couldn't read lists", status.text)
    }

    // ---------------------------------------------------------------- Money

    @Test
    fun `over budget wins and is worded Over by, in alert`() {
        val line = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 20_000L, actual = 24_500L, gap = -4_500L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val status = moneyTileStatus(budgetFixture(listOf(line)))
        assertTrue(status.alert)
        assertEquals("Over by USD 45.00", status.text)
    }

    @Test
    fun `groceries over its own target wins when the whole month is not over`() {
        val groceries = BudgetLine(
            category = "Groceries",
            gap = PlanGap(target = 60_000L, actual = 70_000L, gap = -10_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val dining = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 40_000L, actual = 1_000L, gap = 39_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val status = moneyTileStatus(budgetFixture(listOf(groceries, dining)))
        assertTrue(status.alert)
        assertEquals("Groceries over by USD 100.00", status.text)
    }

    @Test
    fun `spend against a real target, no breach`() {
        val line = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 40_000L, actual = 10_000L, gap = 30_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val status = moneyTileStatus(budgetFixture(listOf(line)))
        assertTrue(!status.alert)
        assertEquals("USD 100.00 of USD 400.00", status.text)
    }

    @Test
    fun `spend with no budget target set reads spent this month`() {
        val line = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 0L, actual = 12_000L, gap = -12_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        assertEquals("USD 120.00 this month", moneyTileStatus(budgetFixture(listOf(line))).text)
    }

    @Test
    fun `no budget and no spend at all reads No spending yet`() {
        assertEquals("No spending yet", moneyTileStatus(null).text)
        assertEquals("No spending yet", moneyTileStatus(budgetFixture(emptyList())).text)
    }

    @Test
    fun `a failed budget read wins over No spending yet, in alert - audit finding 1`() {
        val status = moneyTileStatus(budget = null, failed = true)
        assertTrue(status.alert)
        assertEquals("Couldn't read spending", status.text)
    }

    @Test
    fun `uncategorized present discloses the excluded figure, not counted`() {
        val budget = budgetFixture(
            lines = emptyList(),
            uncategorized = UncategorizedSpend(spentCents = 4_250L, hasProvisionalRows = false),
        )
        val line = moneyDisclosureLine(budget)
        // Audit finding 2: the wording must SAY the exclusion ("Excludes ..."), not just carry the
        // figure and the word "uncategorized" with no verb tying the two into "not counted".
        assertEquals("Excludes USD 42.50 uncategorized", line)
    }

    @Test
    fun `provisional rows disclose unverified`() {
        val line = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 40_000L, actual = 10_000L, gap = 30_000L, tier = TrustTier.REPORTED),
            hasProvisionalRows = true,
            hasPendingCategoryGuesses = false,
        )
        val disclosure = moneyDisclosureLine(budgetFixture(listOf(line)))
        assertTrue(disclosure != null && disclosure.contains("unverified"))
    }

    @Test
    fun `nothing to disclose is null, never a furniture line`() {
        val line = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 40_000L, actual = 10_000L, gap = 30_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        assertNull(moneyDisclosureLine(budgetFixture(listOf(line))))
        assertNull(moneyTileDisclosure(budgetFixture(listOf(line))))
    }

    // ---------------------------------------------------------------- Body

    @Test
    fun `body not logged with a target set`() {
        assertEquals("Nothing logged today", bodyTileStatus(DailyMealGap.NotLogged, hasMealTarget = true).text)
    }

    @Test
    fun `body with no target at all`() {
        assertEquals("No calorie target", bodyTileStatus(DailyMealGap.NotLogged, hasMealTarget = false).text)
    }

    @Test
    fun `a failed meal-gap read wins over No calorie target, in alert - audit finding 1`() {
        val status = bodyTileStatus(DailyMealGap.NotLogged, hasMealTarget = false, failed = true)
        assertTrue(status.alert)
        assertEquals("Couldn't read meals", status.text)
    }

    @Test
    fun `body logged states actual of target, grouped by thousands`() {
        val gap = DailyMealGap.Logged(
            PlanGap(
                target = MacroTotals(2_200, 0.0, 0.0, 0.0),
                actual = MacroTotals(1_450, 0.0, 0.0, 0.0),
                gap = MacroTotals(750, 0.0, 0.0, 0.0),
                tier = TrustTier.PROVEN,
            ),
        )
        assertEquals("1,450 of 2,200 kcal", bodyTileStatus(gap, hasMealTarget = true).text)
    }

    @Test
    fun `body estimated tier discloses estimated, not measured`() {
        val gap = DailyMealGap.Logged(
            PlanGap(
                target = MacroTotals(2_200, 0.0, 0.0, 0.0),
                actual = MacroTotals(1_450, 0.0, 0.0, 0.0),
                gap = MacroTotals(750, 0.0, 0.0, 0.0),
                tier = TrustTier.REPORTED,
            ),
        )
        assertEquals("estimated, not measured", bodyDisclosureLine(gap))
    }

    @Test
    fun `body proven tier discloses nothing, and an unlogged day discloses nothing`() {
        val proven = DailyMealGap.Logged(
            PlanGap(
                target = MacroTotals(2_200, 0.0, 0.0, 0.0),
                actual = MacroTotals(1_450, 0.0, 0.0, 0.0),
                gap = MacroTotals(750, 0.0, 0.0, 0.0),
                tier = TrustTier.PROVEN,
            ),
        )
        assertNull(bodyDisclosureLine(proven))
        assertNull(bodyDisclosureLine(DailyMealGap.NotLogged))
    }

    // ---------------------------------------------------------------- Fleet

    @Test
    fun `overdue maintenance is an alert, singular and plural wording`() {
        val one = fleetTileStatus(listOf(dueRow(overdue = true)), unknownCount = 0)
        assertTrue(one.alert)
        assertEquals("1 overdue", one.text)

        val many = fleetTileStatus(listOf(dueRow(overdue = true), dueRow(overdue = true)), unknownCount = 0)
        assertEquals("2 overdue", many.text)
    }

    @Test
    fun `no schedule at all reads No maintenance schedule`() {
        assertEquals("No maintenance schedule", fleetTileStatus(emptyList(), unknownCount = 0).text)
    }

    @Test
    fun `a failed maintenance read wins over No maintenance schedule, in alert - audit finding 1`() {
        val status = fleetTileStatus(emptyList(), unknownCount = 0, failed = true)
        assertTrue(status.alert)
        assertEquals("Couldn't read maintenance", status.text)
    }

    @Test
    fun `every item unknown, none overdue, reads Mileage unknown`() {
        assertEquals("Mileage unknown", fleetTileStatus(emptyList(), unknownCount = 3).text)
    }

    @Test
    fun `a real schedule with nothing overdue reads buildFleetTile's own caption`() {
        val status = fleetTileStatus(listOf(dueRow(overdue = false)), unknownCount = 0)
        assertTrue(!status.alert)
        assertTrue(status.text.contains("Oil change"))
    }

    // ---------------------------------------------------------------- Recordings

    @Test
    fun `recordings tile counts, and recording wins as an alert`() {
        assertEquals("0 saved", recordingsTileStatus(0, recording = false).text)
        assertEquals("1 saved", recordingsTileStatus(1, recording = false).text)
        assertEquals("5 saved", recordingsTileStatus(5, recording = false).text)
        val recording = recordingsTileStatus(5, recording = true)
        assertTrue(recording.alert)
        assertEquals("Recording", recording.text)
    }

    @Test
    fun `a failed voice-notes read wins over N saved, in alert - audit finding 1`() {
        val status = recordingsTileStatus(0, recording = false, failed = true)
        assertTrue(status.alert)
        assertEquals("Couldn't read recordings", status.text)
    }

    @Test
    fun `an active recording still wins over a failed count read`() {
        val status = recordingsTileStatus(0, recording = true, failed = true)
        assertTrue(status.alert)
        assertEquals("Recording", status.text)
    }

    // ---------------------------------------------------------------- weather / area / location

    @Test
    fun `weather null reads its own honest sentence, never a blank or a fabricated clear`() {
        assertEquals("Weather not available yet - no location fix", com.kevin.legion.ui.weatherLine(null))
    }

    @Test
    fun `location refused states only its own message, never a half-populated area line`() {
        assertEquals(
            "Location permission not granted - grant it to see the area.",
            areaAqiLine(LiveToolbox.LocationReadout.NoPermission, aqi = null),
        )
        assertEquals(
            "Location services are off on the phone.",
            areaAqiLine(LiveToolbox.LocationReadout.ProvidersOff, aqi = null),
        )
        assertEquals(
            "No GPS fix yet - try again in a moment.",
            areaAqiLine(LiveToolbox.LocationReadout.NoFix, aqi = null),
        )
    }

    @Test
    fun `a resolved location with no AQI reading yet says so in words, not a blank`() {
        val available = LiveToolbox.LocationReadout.Available(
            label = "Houston, TX", coords = "29.7,-95.3", lat = 29.7, lon = -95.3,
        )
        val line = areaAqiLine(available, aqi = null)
        assertTrue(line.startsWith("Houston, TX"))
        assertTrue(line.contains("Checking air quality"))
    }
}
