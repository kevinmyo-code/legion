package com.kevin.legion.ui

import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.ledger.AccountBalance
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
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
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

    private fun budgetFixture(
        lines: List<BudgetLine>,
        uncategorizedCents: Long = 0L,
        complete: Boolean = true,
        coverage: List<AccountCoverage>? = null,
    ): BudgetVsActual =
        BudgetVsActual(
            entity = LedgerEntity.US,
            month = YearMonth.of(2026, 8),
            lines = lines,
            uncategorized = UncategorizedSpend(spentCents = uncategorizedCents, hasProvisionalRows = false),
            coverage = coverage ?: if (complete) {
                listOf(AccountCoverage("7823", coversWholeMonth = true, coveredFromMs = 0L, coveredToMs = 1L, coveredThroughMs = 1L))
            } else {
                emptyList()
            },
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
        assertEquals("spent so far", tile.caption)
    }

    private fun aug(day: Int): Long = LocalDate.of(2026, 8, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /**
     * 2026-08-18, Kevin: "2 figures, 2 subtitles: $847 spent so far, $1208 in debit account.
     * thats it." The caption is now CONSTANT - it says what the figure IS, not how complete it is.
     *
     * These three fixtures are the ones that used to produce `THROUGH AUG 5`, `NOT COVERED YET`
     * and `OF $3,000 AUG`. They are kept, rather than deleted with the branches, because the point
     * worth protecting is that NONE of them changes the caption any more. A future edit that
     * reintroduces a coverage or target variant here fails these.
     */
    @Test
    fun `CRED tile - caption is always 'spent so far', whatever the coverage`() {
        val line = BudgetLine(
            category = "Groceries",
            gap = PlanGap(target = 300_000L, actual = 241_800L, gap = 58_200L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val partial = listOf(
            AccountCoverage("checking", coversWholeMonth = false, coveredFromMs = aug(1), coveredToMs = aug(12), coveredThroughMs = aug(12)),
            AccountCoverage("card", coversWholeMonth = false, coveredFromMs = aug(1), coveredToMs = aug(5), coveredThroughMs = aug(5)),
        )
        val neverReachesStart = listOf(
            AccountCoverage("checking", coversWholeMonth = false, coveredFromMs = aug(1), coveredToMs = aug(12), coveredThroughMs = aug(12)),
            AccountCoverage("card", coversWholeMonth = false, coveredFromMs = aug(20), coveredToMs = aug(31), coveredThroughMs = null),
        )

        assertEquals("spent so far", buildCredTile(budgetFixture(listOf(line), coverage = partial), "AUG").caption)
        assertEquals("spent so far", buildCredTile(budgetFixture(listOf(line), coverage = neverReachesStart), "AUG").caption)
        assertEquals("spent so far", buildCredTile(budgetFixture(listOf(line), complete = false), "AUG").caption)
        // A fully covered month with a target set reads the same - the "OF $3,000 AUG" comparison
        // went with the same instruction.
        assertEquals("spent so far", buildCredTile(budgetFixture(listOf(line)), "AUG").caption)
        // The hero itself is untouched by any of this.
        assertEquals("$2,418", buildCredTile(budgetFixture(listOf(line)), "AUG").hero)
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
        assertEquals("spent so far", tile.caption)
    }

    @Test
    fun `CRED tile - uncategorised spend is not counted in the hero figure`() {
        // Kevin 2026-08-15: spend is categorised lines only. A month holding ONLY uncategorised
        // rows reads a real $0 - not "NOT LOGGED", which would claim nothing was ever imported -
        // and TodayScreen states the excluded figure beside the tile in words.
        val tile = buildCredTile(budgetFixture(emptyList(), uncategorizedCents = 5_000L), "AUG")
        assertEquals("$0", tile.hero)
        assertEquals("spent so far", tile.caption)
    }

    @Test
    fun `FLEET tile - no schedule at all is NO LINK`() {
        val tile = buildFleetTile(emptyList(), unknownCount = 0)
        assertEquals("NO LINK", tile.hero)
    }

    @Test
    fun `FLEET tile - overdue items read N DUE`() {
        val rows = listOf(
            DueRowView("Oil Change", "OVERDUE", "every 5,000 mi", overdue = true),
            DueRowView("Tire Rotation", "in 3 mo", "every 6 mo", overdue = false),
        )
        val tile = buildFleetTile(rows, unknownCount = 0)
        assertEquals("1 DUE", tile.hero)
    }

    @Test
    fun `FLEET tile - nothing overdue and nothing unknown reads OK with the next item named`() {
        val rows = listOf(DueRowView("Tire Rotation", "in 3 mo", "every 6 mo", overdue = false))
        val tile = buildFleetTile(rows, unknownCount = 0)
        assertEquals("OK", tile.hero)
        assertTrue(tile.caption.contains("Tire Rotation"))
    }

    @Test
    fun `FLEET tile - ticket 09 Jeep case, unknowns present, never reads OK`() {
        // Kevin's real phone, 2026-08-15: seven of ten items unanchored. Old logic read
        // "OK / NEXT BRAKE FLUID -" here, which was not true.
        val rows = listOf(DueRowView("Tire Rotation", "in 3 mo", "every 6 mo", overdue = false))
        val tile = buildFleetTile(rows, unknownCount = 7)
        assertTrue("hero must never be OK while unknowns exist, was ${tile.hero}", tile.hero != "OK")
        assertEquals("0 DUE", tile.hero)
        assertEquals("0 due - 7 unknown", tile.caption)
    }

    @Test
    fun `FLEET tile - overdue and unknown both non-zero states both in the caption`() {
        val rows = listOf(DueRowView("Oil Change", "OVERDUE", "every 5,000 mi", overdue = true))
        val tile = buildFleetTile(rows, unknownCount = 7)
        assertEquals("1 DUE", tile.hero)
        assertEquals("1 due - 7 unknown", tile.caption)
    }

    @Test
    fun `FLEET tile - entirely unknown schedule is not NO LINK, a schedule does exist`() {
        val tile = buildFleetTile(emptyList(), unknownCount = 3)
        assertTrue("hero must never be NO LINK when a schedule exists, was ${tile.hero}", tile.hero != "NO LINK")
        assertEquals("0 DUE", tile.hero)
    }

    @Test
    fun `FLEET tile - hero never exceeds the seven-character half-tile budget`() {
        val tile = buildFleetTile(listOf(DueRowView("Oil Change", "OVERDUE", "every 5,000 mi", overdue = true)), unknownCount = 12)
        assertTrue("hero '${tile.hero}' exceeds 7 characters", tile.hero.length <= 7)
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
    fun `alertRowsHaveAlarm is true exactly when an ALARM row is present`() {
        val withAlarm = buildAlertRows(
            quarantined = listOf(quarantinedFile("a.pdf")),
            hasGeminiKey = false,
            overdueGoals = listOf(overdueGoal("bio")),
        )
        assertTrue(alertRowsHaveAlarm(withAlarm))

        val advisoryOnly = buildAlertRows(
            quarantined = emptyList(),
            hasGeminiKey = false,
            overdueGoals = listOf(overdueGoal("bio")),
        )
        assertTrue(!alertRowsHaveAlarm(advisoryOnly))

        assertTrue(!alertRowsHaveAlarm(emptyList()))
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

    // ---------------------------------------------- CRED tile balance line (2026-08-18, Kevin)

    @Test
    fun `nothing nominated says so and where to fix it, never guesses an account`() {
        val balances = listOf(
            AccountBalance("BOFA-CHECKING", LedgerCurrency.USD, balanceCents = 381_200L, asOfMs = 1_000L),
        )
        val line = buildCredBalanceLine(balances, nominatedAccountId = null)
        assertEquals("NO ACCOUNT NOMINATED", line.primary)
        assertEquals("set one in Money", line.secondary)
        assertTrue(line.isAdvisory)
    }

    @Test
    fun `a blank nominated id is treated the same as none`() {
        val line = buildCredBalanceLine(emptyList(), nominatedAccountId = "   ")
        assertEquals("NO ACCOUNT NOMINATED", line.primary)
        assertTrue(line.isAdvisory)
    }

    @Test
    fun `nominated account no longer present says so plainly, never falls back to another account`() {
        val balances = listOf(
            AccountBalance("DBS-CHECKING", LedgerCurrency.SGD, balanceCents = 216_582L, asOfMs = 1_000L),
        )
        val line = buildCredBalanceLine(balances, nominatedAccountId = "BOFA-CHECKING")
        assertEquals("BOFA-CHECKING NOT FOUND", line.primary)
        assertEquals("renamed or no longer seen - pick a different account in Money", line.secondary)
        assertTrue(line.isAdvisory)
    }

    @Test
    fun `a nominated account that never printed a balance reuses BalancesSection's own words`() {
        val balances = listOf(
            AccountBalance("BOFA ****4471", LedgerCurrency.USD, balanceCents = null, asOfMs = null),
        )
        val line = buildCredBalanceLine(balances, nominatedAccountId = "BOFA ****4471")
        assertEquals("BOFA ****4471", line.primary)
        assertEquals("no balance ever printed for this account", line.secondary)
        assertTrue(line.isAdvisory)
    }

    /**
     * 2026-08-18, Kevin's own words for this line: "$1208 in debit account". The figure stands
     * alone as a hero and the account moves into the subtitle, so the two figures on this tile
     * read as a matched pair.
     *
     * **The as-of date is deliberately absent from THIS surface.** It still ships in full on
     * Money's balance row. See buildCredBalanceLine's own comment for why the later instruction
     * wins and what the fix is if a stale figure reading as current here ever bites.
     */
    @Test
    fun `the normal case is the figure, with the account named in the subtitle`() {
        val asOfMs = LocalDate.of(2026, 8, 12).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val balances = listOf(
            AccountBalance("debit", LedgerCurrency.USD, balanceCents = 120_800L, asOfMs = asOfMs),
        )
        val line = buildCredBalanceLine(balances, nominatedAccountId = "debit")
        assertEquals("$1,208", line.primary)
        assertEquals("in debit account", line.secondary)
        assertTrue(!line.isAdvisory)
    }

    @Test
    fun `an as-of date present or absent makes no difference to what this tile prints`() {
        val dated = AccountBalance("debit", LedgerCurrency.USD, balanceCents = 120_800L,
            asOfMs = LocalDate.of(2026, 8, 12).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        val undated = AccountBalance("debit", LedgerCurrency.USD, balanceCents = 120_800L, asOfMs = null)

        val withDate = buildCredBalanceLine(listOf(dated), nominatedAccountId = "debit")
        val withoutDate = buildCredBalanceLine(listOf(undated), nominatedAccountId = "debit")

        assertEquals(withDate.primary, withoutDate.primary)
        assertEquals(withDate.secondary, withoutDate.secondary)
    }
}
