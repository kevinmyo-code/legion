package com.kevin.legion.ui

import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.ExcludedOwnAccountMovements
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.meals.MacroTotals
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.ui.common.GapSign
import com.kevin.legion.ui.fleet.DueRowView
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure `GapRowData` mappers in `TodayGapResolvers.kt` - plain JUnit, no Compose/
 * Android dependency, same posture as [com.kevin.legion.workouts.WorkoutGapTest]/
 * [com.kevin.legion.meals.MealGapTest]. All fixtures are invented.
 */
class TodayGapResolversTest {

    // ---------------------------------------------------------------- budget line

    @Test
    fun `budget line under target is GOOD and says remaining`() {
        val line = BudgetLine(
            category = "Groceries",
            gap = PlanGap(target = 60_000L, actual = 41_200L, gap = 18_800L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val data = buildBudgetLineGapRowData(line, LedgerCurrency.USD)
        assertEquals("remaining", data.gapCaption)
        assertEquals(GapSign.GOOD, data.sign)
        assertEquals("USD 188.00", data.gapValue)
        assertNull(data.tierNote)
    }

    @Test
    fun `budget line over target is BAD and says over`() {
        val line = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 20_000L, actual = 24_500L, gap = -4_500L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val data = buildBudgetLineGapRowData(line, LedgerCurrency.USD)
        assertEquals("over", data.gapCaption)
        assertEquals(GapSign.BAD, data.sign)
    }

    @Test
    fun `a provisional card row and an unconfirmed category guess both surface as separate wording`() {
        val line = BudgetLine(
            category = "Shopping",
            gap = PlanGap(target = 30_000L, actual = 12_000L, gap = 18_000L, tier = TrustTier.REPORTED),
            hasProvisionalRows = true,
            hasPendingCategoryGuesses = true,
        )
        val data = buildBudgetLineGapRowData(line, LedgerCurrency.USD)
        assertEquals(TrustTier.REPORTED, data.tier)
        assertTrue(data.tierNote!!.contains("pending transactions"))
        assertTrue(data.tierNote!!.contains("LEGION guessed"))
    }

    // ------------------------------------------------------------- uncategorized

    @Test
    fun `D11 - uncategorized spend has no sign and states its own reason in words`() {
        val data = buildUncategorizedGapRowData(UncategorizedSpend(spentCents = 3_412L, hasProvisionalRows = false), LedgerCurrency.USD)
        assertEquals(GapSign.NEUTRAL, data.sign)
        assertEquals("USD 34.12", data.gapValue)
        assertNull(data.tierNote)
    }

    @Test
    fun `uncategorized spend with provisional rows is REPORTED and says so`() {
        val data = buildUncategorizedGapRowData(UncategorizedSpend(spentCents = 1_500L, hasProvisionalRows = true), LedgerCurrency.USD)
        assertEquals(TrustTier.REPORTED, data.tier)
        assertEquals("includes pending transactions not yet on a statement", data.tierNote)
    }

    // ---------------------------------------------------------------- workouts

    @Test
    fun `behind on workouts this week is BAD`() {
        val gap = PlanGap(target = 4, actual = 2, gap = 2, tier = TrustTier.REPORTED)
        val data = buildWeeklyWorkoutGapRowData(gap)
        assertEquals("2", data.gapValue)
        assertEquals("sessions behind", data.gapCaption)
        assertEquals(GapSign.BAD, data.sign)
        assertEquals("you logged these, not independently verified", data.tierNote)
    }

    @Test
    fun `caught up or ahead on workouts this week is GOOD`() {
        val gap = PlanGap(target = 3, actual = 3, gap = 0, tier = TrustTier.PROVEN)
        val data = buildWeeklyWorkoutGapRowData(gap)
        assertEquals("sessions ahead", data.gapCaption)
        assertEquals(GapSign.GOOD, data.sign)
        assertNull(data.tierNote)
    }

    // -------------------------------------------------------------------- meals

    @Test
    fun `under the calorie target reads kcal left, GOOD`() {
        val gap = PlanGap(
            target = MacroTotals(2200, 150.0, 220.0, 70.0),
            actual = MacroTotals(1650, 90.0, 160.0, 55.0),
            gap = MacroTotals(550, 60.0, 60.0, 15.0),
            tier = TrustTier.REPORTED,
        )
        val data = buildDailyMealGapRowData(gap)
        assertEquals("550", data.gapValue)
        assertEquals("kcal left", data.gapCaption)
        assertEquals(GapSign.GOOD, data.sign)
    }

    @Test
    fun `over the calorie target reads kcal over, BAD`() {
        val gap = PlanGap(
            target = MacroTotals(2200, 150.0, 220.0, 70.0),
            actual = MacroTotals(2600, 150.0, 220.0, 70.0),
            gap = MacroTotals(-400, 0.0, 0.0, 0.0),
            tier = TrustTier.REPORTED,
        )
        val data = buildDailyMealGapRowData(gap)
        assertEquals("400", data.gapValue)
        assertEquals("kcal over", data.gapCaption)
        assertEquals(GapSign.BAD, data.sign)
    }

    // -------------------------------------------------------------------- sleep

    @Test
    fun `sleeping short of the target reads short of target, BAD`() {
        val gap = PlanGap(target = 480, actual = 390, gap = 90, tier = TrustTier.REPORTED)
        val data = buildSleepGapRowData(gap)
        assertEquals("1h 30m", data.gapValue)
        assertEquals("short of target", data.gapCaption)
        assertEquals(GapSign.BAD, data.sign)
        assertEquals("you logged this, not independently verified", data.tierNote)
    }

    @Test
    fun `meeting or exceeding the sleep target reads over target, GOOD`() {
        val gap = PlanGap(target = 420, actual = 500, gap = -80, tier = TrustTier.REPORTED)
        val data = buildSleepGapRowData(gap)
        assertEquals("1h 20m", data.gapValue)
        assertEquals("over target", data.gapCaption)
        assertEquals(GapSign.GOOD, data.sign)
    }

    // --------------------------------------------------------------- maintenance

    @Test
    fun `an overdue maintenance row is BAD with no caption - the value already says OVERDUE`() {
        val row = DueRowView("Oil Change", "OVERDUE", "every 5,000 mi - last at 130,200", overdue = true)
        val data = buildMaintenanceGapRowData(row)
        assertEquals("OVERDUE", data.gapValue)
        assertEquals("", data.gapCaption)
        assertEquals(GapSign.BAD, data.sign)
    }

    @Test
    fun `an upcoming maintenance row is NEUTRAL and carries no caption`() {
        // The caption used to read "away". Dropped 2026-08-07 after seeing it on
        // device: `value` already says "in 3 mo", so "away" added nothing and,
        // right-aligned beneath a long value, wrapped onto its own orphaned line.
        val row = DueRowView("Tire Rotation", "in 3 mo", "every 6 mo - last Feb 2026", overdue = false)
        val data = buildMaintenanceGapRowData(row)
        assertEquals("", data.gapCaption)
        assertEquals(GapSign.NEUTRAL, data.sign)
    }

    // ---------------------------------------------------------------- notes summary (ticket 07)

    @Test
    fun `notes summary names missed first when both are non-zero`() {
        assertEquals("2 missed, 3 due today", notesSummaryMessage(dueTodayCount = 3, missedCount = 2))
    }

    @Test
    fun `notes summary singular vs plural missed`() {
        assertEquals("1 missed reminder", notesSummaryMessage(dueTodayCount = 0, missedCount = 1))
        assertEquals("2 missed reminders", notesSummaryMessage(dueTodayCount = 0, missedCount = 2))
    }

    @Test
    fun `notes summary singular vs plural due today`() {
        assertEquals("1 due today", notesSummaryMessage(dueTodayCount = 1, missedCount = 0))
        assertEquals("2 due today", notesSummaryMessage(dueTodayCount = 2, missedCount = 0))
    }

    @Test
    fun `notes summary is honest when nothing is due`() {
        assertEquals("Nothing due today", notesSummaryMessage(dueTodayCount = 0, missedCount = 0))
    }

    // -------------------------------------------------- ledger cumulative spend (ticket 11 item 2)

    @Test
    fun `cumulative fold accumulates exact Long cents across covered days`() {
        val daily = listOf(1_000L, 2_500L, 0L, 4_000L)
        assertEquals(listOf(1_000L, 3_500L, 3_500L, 7_500L), cumulativeDailySpendCents(daily))
    }

    @Test
    fun `a gap day renders null and does not disturb the running total`() {
        val daily = listOf(1_000L, null, 2_000L)
        assertEquals(listOf(1_000L, null, 3_000L), cumulativeDailySpendCents(daily))
    }

    @Test
    fun `a gap day resumes at the next covered day's accumulated total, never carrying the last value forward onto the gap itself`() {
        // The gap day (index 1) must stay null, not read as 500L (the last-known total) -
        // that would silently claim the gap day spent zero, which nobody verified.
        val daily = listOf(500L, null, null, 300L)
        val out = cumulativeDailySpendCents(daily)
        assertNull(out[1])
        assertNull(out[2])
        assertEquals(800L, out[3])
    }

    @Test
    fun `leading gap days stay null and the running total starts from the first covered day`() {
        val daily = listOf(null, null, 750L, 250L)
        assertEquals(listOf(null, null, 750L, 1_000L), cumulativeDailySpendCents(daily))
    }

    @Test
    fun `all-gap series stays entirely null`() {
        val daily = listOf<Long?>(null, null, null)
        assertEquals(listOf<Long?>(null, null, null), cumulativeDailySpendCents(daily))
    }

    @Test
    fun `empty series folds to an empty series`() {
        assertEquals(emptyList<Long?>(), cumulativeDailySpendCents(emptyList()))
    }

    @Test
    fun `truncation at today is the caller's job, not this function's - it folds whatever list it is handed`() {
        // This function does no date math (see its own doc comment) - a 3-entry list already
        // truncated by the caller just folds as a 3-entry list, proving there is no hidden
        // "expects a full month" assumption baked in here.
        val daily = listOf(100L, 200L)
        assertEquals(listOf(100L, 300L), cumulativeDailySpendCents(daily))
    }

    // -------------------------------------------------------- mission-control ticket 16: HALF tiles

    private fun bwLog(kg: Double, at: Long = 0L) = BodyweightLog(weightValue = kg, weightUnit = "kg", loggedAt = at, trustTier = TrustTier.REPORTED)

    @Test
    fun `BIO tile - never logged reads NOT LOGGED and no trend`() {
        val tile = buildBioTile(latest = null, lookback = emptyList())
        assertEquals("NOT LOGGED", tile.hero)
        assertEquals("no weigh-ins yet", tile.caption)
    }

    @Test
    fun `BIO tile - a latest reading with no lookback history has no trend word`() {
        val tile = buildBioTile(latest = bwLog(82.4), lookback = emptyList())
        assertEquals("82.4", tile.hero)
        assertEquals("KG", tile.caption)
    }

    @Test
    fun `BIO tile - down vs 4wk ago`() {
        val tile = buildBioTile(latest = bwLog(80.0), lookback = listOf(bwLog(85.0)))
        assertEquals("80.0", tile.hero)
        assertEquals("KG - DOWN 4WK", tile.caption)
    }

    @Test
    fun `BIO tile - up vs 4wk ago`() {
        val tile = buildBioTile(latest = bwLog(90.0), lookback = listOf(bwLog(85.0)))
        assertEquals("KG - UP 4WK", tile.caption)
    }

    @Test
    fun `BIO tile - within the flat threshold reads FLAT, not UP or DOWN`() {
        val tile = buildBioTile(latest = bwLog(85.05), lookback = listOf(bwLog(85.0)))
        assertEquals("KG - FLAT 4WK", tile.caption)
    }

    @Test
    fun `BIO tile - lbs are normalised to kg before comparison`() {
        // 187.4 lbs = ~85.0 kg - a lbs latest against a kg lookback must not read as a huge swing.
        val tile = buildBioTile(
            latest = BodyweightLog(weightValue = 187.4, weightUnit = "lbs", loggedAt = 0L, trustTier = TrustTier.REPORTED),
            lookback = listOf(bwLog(85.0)),
        )
        assertEquals("KG - FLAT 4WK", tile.caption)
    }

    @Test
    fun `compact money hero rounds to the nearest whole unit and adds a symbol`() {
        assertEquals("$2,418", compactMoneyHero(241_750L, LedgerCurrency.USD))
        assertEquals("$2,418", compactMoneyHero(241_849L, LedgerCurrency.USD))
        assertEquals("S$500", compactMoneyHero(50_000L, LedgerCurrency.SGD))
    }

    @Test
    fun `compact money hero never truncates down - it rounds`() {
        // 99 cents must round up to the next whole unit, never silently drop them.
        assertEquals("$1", compactMoneyHero(99L, LedgerCurrency.USD))
    }

    private fun budgetFixture(lines: List<BudgetLine>, uncategorizedCents: Long = 0L, complete: Boolean = true): BudgetVsActual =
        BudgetVsActual(
            entity = LedgerEntity.US,
            month = YearMonth.of(2026, 8),
            lines = lines,
            uncategorized = UncategorizedSpend(spentCents = uncategorizedCents, hasProvisionalRows = false),
            coverage = if (complete) listOf(AccountCoverage("7823", coversWholeMonth = true, coveredFromMs = 0L, coveredToMs = 1L)) else emptyList(),
            excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
        )

    @Test
    fun `CRED tile - no budget and no spend is silent`() {
        val tile = buildCredTile(budgetFixture(emptyList()), "AUG")
        assertEquals("NOT LOGGED", tile.hero)
        assertEquals("no spend AUG", tile.caption)
    }

    @Test
    fun `CRED tile - a target set reads spend OF target`() {
        val line = BudgetLine(
            category = "Groceries",
            gap = PlanGap(target = 300_000L, actual = 241_800L, gap = 58_200L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val tile = buildCredTile(budgetFixture(listOf(line)), "AUG")
        assertEquals("$2,418", tile.hero)
        assertEquals("OF $3,000 AUG", tile.caption)
    }

    @Test
    fun `CRED tile - incomplete coverage reads COVERAGE GAP regardless of target`() {
        val line = BudgetLine(
            category = "Groceries",
            gap = PlanGap(target = 300_000L, actual = 241_800L, gap = 58_200L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val tile = buildCredTile(budgetFixture(listOf(line), complete = false), "AUG")
        assertEquals("COVERAGE GAP", tile.caption)
    }

    @Test
    fun `CRED tile - spend with no target set says so`() {
        val line = BudgetLine(
            category = "Groceries",
            gap = PlanGap(target = 0L, actual = 5_000L, gap = -5_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val tile = buildCredTile(budgetFixture(listOf(line)), "AUG")
        assertEquals("$50", tile.hero)
        assertEquals("NO TARGET SET", tile.caption)
    }

    @Test
    fun `CRED tile - uncategorised spend is not counted in the hero figure`() {
        // Kevin 2026-08-15: spend is categorised lines only. A month holding ONLY uncategorised
        // rows reads a real $0 - not "NOT LOGGED", which would claim nothing was ever imported -
        // and TodayScreen states the excluded figure beside the tile in words.
        val tile = buildCredTile(budgetFixture(emptyList(), uncategorizedCents = 5_000L), "AUG")
        assertEquals("$0", tile.hero)
        assertEquals("NO TARGET SET", tile.caption)
    }

    @Test
    fun `FLEET tile - no schedule is NO LINK`() {
        val tile = buildFleetTile(emptyList())
        assertEquals("NO LINK", tile.hero)
    }

    @Test
    fun `FLEET tile - overdue items read N DUE`() {
        val rows = listOf(
            DueRowView("Oil Change", "OVERDUE", "every 5,000 mi", overdue = true),
            DueRowView("Tire Rotation", "in 3 mo", "every 6 mo", overdue = false),
        )
        val tile = buildFleetTile(rows)
        assertEquals("1 DUE", tile.hero)
    }

    @Test
    fun `FLEET tile - nothing overdue reads OK with the next item named`() {
        val rows = listOf(DueRowView("Tire Rotation", "in 3 mo", "every 6 mo", overdue = false))
        val tile = buildFleetTile(rows)
        assertEquals("OK", tile.hero)
        assertTrue(tile.caption.contains("Tire Rotation"))
    }

    @Test
    fun `LOG tile - never used reads NOT LOGGED regardless of a real zero count`() {
        val tile = buildLogTile(openTaskCount = 0, missedCount = 0, hasAnyItems = false)
        assertEquals("NOT LOGGED", tile.hero)
    }

    @Test
    fun `LOG tile - a real zero open tasks is not silence`() {
        val tile = buildLogTile(openTaskCount = 0, missedCount = 0, hasAnyItems = true)
        assertEquals("0 NEW", tile.hero)
    }

    @Test
    fun `LOG tile - open tasks read N NEW`() {
        val tile = buildLogTile(openTaskCount = 11, missedCount = 0, hasAnyItems = true)
        assertEquals("11 NEW", tile.hero)
    }

    @Test
    fun `LOG tile - a missed reminder outranks the open-task count`() {
        val tile = buildLogTile(openTaskCount = 4, missedCount = 1, hasAnyItems = true)
        assertEquals("1 MISSED", tile.hero)
        assertEquals("4 open task(s)", tile.caption)
    }

    // ------------------------------------------------------------- mission-control ticket 16: ALERTS

    private fun quarantinedFile(name: String) = IngestedFile(
        driveFileId = name,
        treeUri = null,
        displayName = name,
        sizeBytes = 1L,
        lastModified = 0L,
        contentSha256 = null,
        state = IngestState.QUARANTINED,
        firstSeenAt = 0L,
        lastAttemptAt = 0L,
    )

    private fun overdueGoal(aspect: String, statement: String = "goal") =
        Goal(lineageId = 1L, aspect = aspect, statement = statement, deadlineEpoch = 100L)

    @Test
    fun `ALERTS - ALARM rows always come before ADVISORY rows`() {
        val rows = buildAlertRows(
            quarantined = emptyList(),
            hasGeminiKey = false,
            overdueGoals = listOf(overdueGoal("bio")),
        )
        // No quarantine here, so this only proves the key advisory and the goal advisory both land
        // in ADVISORY - the real ordering proof is the next test, which has both tiers present.
        assertTrue(rows.all { it.tier == AlertTier.ADVISORY })
    }

    @Test
    fun `ALERTS - a quarantined file sorts before every advisory, never reshuffled by content`() {
        val rows = buildAlertRows(
            quarantined = listOf(quarantinedFile("b.pdf"), quarantinedFile("a.pdf")),
            hasGeminiKey = false,
            overdueGoals = listOf(overdueGoal("bio")),
        )
        assertEquals(listOf(AlertTier.ALARM, AlertTier.ALARM, AlertTier.ADVISORY, AlertTier.ADVISORY), rows.map { it.tier })
        // Arrival order within a tier is preserved, never re-sorted by label.
        assertEquals(listOf("b.pdf", "a.pdf"), rows.take(2).map { it.label })
    }

    @Test
    fun `ALERTS - a missing Gemini key is the fresh-install advisory`() {
        val rows = buildAlertRows(quarantined = emptyList(), hasGeminiKey = false, overdueGoals = emptyList())
        assertEquals(1, rows.size)
        assertEquals(AlertTier.ADVISORY, rows.single().tier)
        assertEquals("NO KEY", rows.single().tagText)
        assertEquals(AlertTarget.KEY, rows.single().target)
    }

    @Test
    fun `ALERTS - nothing needing attention is an empty list, not a nominal row`() {
        val rows = buildAlertRows(quarantined = emptyList(), hasGeminiKey = true, overdueGoals = emptyList())
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `alertTargetForAspect routes every real aspect and falls back to NONE for HOME or unknown`() {
        assertEquals(AlertTarget.BIO, alertTargetForAspect(AdvisorAspect.BIO))
        assertEquals(AlertTarget.LOG, alertTargetForAspect(AdvisorAspect.LOG))
        assertEquals(AlertTarget.FLEET, alertTargetForAspect(AdvisorAspect.FLEET))
        assertEquals(AlertTarget.CRED, alertTargetForAspect(AdvisorAspect.CRED))
        assertEquals(AlertTarget.NONE, alertTargetForAspect(AdvisorAspect.HOME))
        assertEquals(AlertTarget.NONE, alertTargetForAspect(null))
    }

    @Test
    fun `capAlertRows passes a short list through untouched`() {
        val rows = listOf(quarantinedFile("a.pdf")).map { AlertRowData(it.displayName, "FAILED THE GATE", AlertTier.ALARM, "QUARANTINED", AlertTarget.CRED) }
        val summary = capAlertRows(rows)
        assertEquals(1, summary.visible.size)
        assertEquals(0, summary.overflowCount)
    }

    @Test
    fun `capAlertRows caps at five with a worded overflow count`() {
        val rows = (1..7).map { AlertRowData("row $it", "v", AlertTier.ADVISORY, "TAG", AlertTarget.NONE) }
        val summary = capAlertRows(rows)
        assertEquals(5, summary.visible.size)
        assertEquals(2, summary.overflowCount)
    }
}
