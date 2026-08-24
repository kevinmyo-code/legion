package com.kevin.legion.advisor.digest

import android.content.Context
import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.advisor.DigestBuilder
import com.kevin.legion.advisor.DigestText
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.BudgetTarget
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.plan.combinedTier
import com.kevin.legion.util.compactDate
import com.kevin.legion.vehicle.VehicleController
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * HOME's cross-aspect digest (ticket 09/17): ONE headline line per aspect (BIO/CRED/FLEET/LOG) -
 * the gap that matters, trend direction where cheap to compute, any goal visibly off track - sized
 * at roughly one aspect digest, never four ([The cross-aspect HOME advisor](../../../../../../../../.scratch/aspect-advisors/issues/09-home-advisor.md),
 * answer call 1: "The alternatives were both rejected on cost: four raw digests is ~4x the prompt
 * of any other question, and running the four advisors first is five Gemini calls").
 *
 * **Computes every headline directly off the aspect DAOs. Never calls [FleetDigestBuilder]/
 * [LogDigestBuilder]/a BIO or CRED `DigestBuilder`.** That was the design ticket 09 explicitly
 * rejected - see this object's own headline functions, each independently minimal (one or two
 * queries, one line out), not a call into another aspect's full digest logic. This is deliberately
 * NOT as rich as any single aspect's own digest: HOME's job is spotting the cross-aspect
 * connection and naming which goal is most at risk, "defers domain depth to the aspect advisor
 * rather than improvising" (ticket 09 answer call 2) - a HOME headline that tried to be BIO's or
 * CRED's own digest in miniature would be exactly the improvising ticket 09 ruled out.
 *
 * **Goals are NOT repeated in this digest's own text.** [com.kevin.legion.advisor.AdvisorAgent.ask]
 * already fetches [com.kevin.legion.data.local.GoalDao.allCurrentGoals] for HOME specifically and
 * [com.kevin.legion.advisor.AdvisorAgent.composeContext] appends them under their own "GOALS:"
 * section on every aspect's prompt, HOME included - see that file's `ask`/`composeContext`. Writing
 * them a second time into this digest's own text would duplicate a section the harness already
 * builds for free. This digest's own "EXCEPTIONS" line instead flags the one goal fact worth
 * calling out that the plain goal listing doesn't itself say in words: an ACTIVE goal whose own
 * stated [Goal.deadlineEpoch] has already passed.
 */
object HomeDigestBuilder : DigestBuilder {
    override val aspect = AdvisorAspect.HOME

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val BODYWEIGHT_TREND_LOOKBACK_MS = 28 * DAY_MS
    private const val BODYWEIGHT_TREND_WINDOW_MS = 14 * DAY_MS
    /** Below this, a bodyweight comparison reads "flat" rather than manufacturing a direction out
     * of ordinary day-to-day water-weight noise. */
    private const val BODYWEIGHT_FLAT_THRESHOLD_KG = 0.2

    override suspend fun build(context: Context): String {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val monthStart = monthStartUtcMs(now)

        val bioLine = bioHeadline(
            latest = db.bodyweightLogDao().mostRecent(),
            lookback = db.bodyweightLogDao().forWindow(now - BODYWEIGHT_TREND_LOOKBACK_MS, now - BODYWEIGHT_TREND_WINDOW_MS),
        )

        val credTargetsByCurrency = LedgerCurrency.values().associateWith { db.budgetTargetDao().currentTargets(it, monthStart) }
        // Cutover 3: reads through LedgerController's engine-backed seam, not the (now-frozen,
        // zero-writer) legacy ledgerTransactionDao() directly.
        val credTxnsByCurrency = LedgerCurrency.values().associateWith {
            com.kevin.legion.ledger.LedgerController.transactionsForCurrencyInRange(context, it, monthStart, now)
        }
        val credLine = credHeadline(credTargetsByCurrency, credTxnsByCurrency)

        val vehicle = VehicleController.currentVehicle(context)
        val maintenanceItems = com.kevin.legion.vehicle.FleetEngineStore.getForVehicle(context, vehicle.obdMac)
        val fleetLine = fleetHeadline(
            items = maintenanceItems,
            due = VehicleController.dueItems(context, vehicle),
            next = VehicleController.nextService(context, vehicle),
        )

        // Cutover 1: rewired off ListItemDao onto NotesController, which is now engine-backed.
        val logLine = logHeadline(
            allActive = com.kevin.legion.notes.NotesController.allItems(context),
            missed = com.kevin.legion.notes.NotesController.missedItems(context),
        )

        val goals = db.goalDao().allCurrentGoals()
        val exceptionsLine = exceptionsLine(goals, now)

        return listOf(bioLine, credLine, fleetLine, logLine, exceptionsLine).joinToString("\n")
    }

    // ------------------------------------------------------------------------------ BIO headline

    /** Bodyweight trend only (the playbook's own "weekly averages, never daily" figure is the most
     * legible single BIO fact for a cross-aspect glance). [BodyweightLog.weightUnit] varies PER
     * ROW ("lbs" or "kg"), so every value is normalised to kg before any comparison - comparing raw
     * [BodyweightLog.weightValue] across mixed units would silently read a unit change as a real
     * trend. `internal` for direct unit testing. */
    internal fun bioHeadline(latest: BodyweightLog?, lookback: List<BodyweightLog>): String {
        if (latest == null) return DigestText.line("BIO", DigestText.notLogged())
        val latestKg = toKg(latest.weightValue, latest.weightUnit)
        val priorAvgKg = lookback.takeIf { it.isNotEmpty() }?.map { toKg(it.weightValue, it.weightUnit) }?.average()
        val trend = when {
            priorAvgKg == null -> "insufficient history for a trend"
            abs(latestKg - priorAvgKg) < BODYWEIGHT_FLAT_THRESHOLD_KG -> "flat vs 4wk ago (%.1f kg)".format(priorAvgKg)
            latestKg < priorAvgKg -> "down vs 4wk ago (%.1f kg)".format(priorAvgKg)
            else -> "up vs 4wk ago (%.1f kg)".format(priorAvgKg)
        }
        // Ticket 08: every figure carries its tier VIA combinedTier(), same as [BioDigestBuilder]'s
        // own weightLine - never a bare hardcode. [latest] always contributes the current-kg figure;
        // [lookback] only contributes a real number when it actually produced a trend average (an
        // empty/unused lookback should not taint or inflate the tier of a figure it didn't shape).
        val tiers = listOf(latest.trustTier) + if (priorAvgKg != null) lookback.map { it.trustTier } else emptyList()
        return DigestText.withTier(DigestText.line("BIO bodyweight", "%.1f kg, trend $trend".format(latestKg)), tiers.combinedTier())
    }

    private fun toKg(value: Double, unit: String): Double = if (unit.equals("lbs", ignoreCase = true)) value * 0.45359237 else value

    // ----------------------------------------------------------------------------- CRED headline

    /** The single largest budget-vs-actual gap across whichever [LedgerCurrency] has a current
     * target this month - magnitudes are only ever COMPARED across currencies to pick the worst
     * one to report, never summed or converted (CLAUDE.md §4 rule 5: SGD and USD are never
     * combined without a printed exchange rate). Falls back to a bare spend total (no target set)
     * when no currency has a budget target at all, and to [DigestText.notLogged] when there is no
     * ledger data whatsoever. `internal` for direct unit testing. */
    internal fun credHeadline(
        targetsByCurrency: Map<LedgerCurrency, List<BudgetTarget>>,
        txnsByCurrency: Map<LedgerCurrency, List<LedgerTransaction>>,
    ): String {
        data class Gap(val currency: LedgerCurrency, val targetCents: Long, val spendCents: Long, val gapCents: Long, val tier: TrustTier)

        val gaps = targetsByCurrency.mapNotNull { (currency, targets) ->
            if (targets.isEmpty()) return@mapNotNull null
            val targetCents = targets.sumOf { it.amountCents }
            val spendTxns = txnsByCurrency[currency].orEmpty().filter(::countsAsSpend)
            val spendCents = -(spendTxns.sumOf { it.amountCents })
            // Ticket 08: computed VIA combinedTier() over the exact rows summed into spendCents -
            // an unspent budget line (no rows at all) reduces to PROVEN per [combinedTier]'s own
            // doc comment ("nothing to be cautious about yet"), never a bare REPORTED hardcode.
            Gap(currency, targetCents, spendCents, targetCents - spendCents, spendTxns.map(::rowTier).combinedTier())
        }

        if (gaps.isNotEmpty()) {
            val worst = gaps.minByOrNull { it.gapCents } ?: gaps.first() // most negative (most over) reads worst
            val status = if (worst.gapCents < 0) "over by ${formatCents(-worst.gapCents)}" else "remaining ${formatCents(worst.gapCents)}"
            return DigestText.withTier(
                DigestText.line("CRED budget ${worst.currency}", "target ${formatCents(worst.targetCents)} actual ${formatCents(worst.spendCents)} $status"),
                worst.tier,
            )
        }

        val anySpend = txnsByCurrency.entries.firstOrNull { it.value.isNotEmpty() }
        if (anySpend == null) return DigestText.line("CRED", DigestText.notLogged())
        val (currency, txns) = anySpend
        val spendTxns = txns.filter(::countsAsSpend)
        val spendCents = -(spendTxns.sumOf { it.amountCents })
        val uncategorizedCents = -(txns.filter { it.amountCents < 0 && it.category == null }.sumOf { it.amountCents })
        // The exclusion, said out loud rather than left for the advisor to infer from a figure that
        // is smaller than it expects (CLAUDE.md §4 rule 7, and see UncategorizedSpend's own doc
        // comment) - only when there IS something excluded, this being a token-budgeted line.
        val excludedNote = if (uncategorizedCents > 0L) ", ${formatCents(uncategorizedCents)} uncategorized not counted" else ""
        return DigestText.withTier(
            DigestText.line("CRED $currency", "no budget set, spent ${formatCents(spendCents)} this month$excludedNote"),
            spendTxns.map(::rowTier).combinedTier(),
        )
    }

    /**
     * What counts toward a CRED spend figure: an expense row (negative) that someone has actually
     * classified. **Uncategorised rows are excluded (Kevin, 2026-08-15)** - the same rule
     * [com.kevin.legion.ledger.BudgetVsActual.spentCents] applies on every screen, restated here
     * because this headline sums raw rows rather than reading a built `BudgetVsActual` (see this
     * function's callers), and a HOME line that quietly used the wider definition would disagree
     * with the CRED tile sitting inches from it.
     */
    private fun countsAsSpend(row: LedgerTransaction): Boolean = row.amountCents < 0 && row.category != null

    /** Mirrors [CredDigestBuilder]'s own private `rowTier` exactly (not visible outside its file -
     * that one already mirrors [com.kevin.legion.ledger.LedgerBudget]'s for the same reason). See
     * [TrustTier]'s own doc comment for why a REPORTED row is UNRECONCILED or category-pending. */
    private fun rowTier(row: LedgerTransaction): TrustTier =
        if (row.ingestMethod == IngestMethod.UNRECONCILED || row.categoryPending) TrustTier.REPORTED else TrustTier.PROVEN

    /** Cents to a plain "123.45" string - no currency symbol (the label already names the
     * [LedgerCurrency]), matching the ticket 08 worked example's own bare-decimal convention. */
    private fun formatCents(cents: Long): String = "%.2f".format(cents / 100.0)

    // ---------------------------------------------------------------------------- FLEET headline

    /** Overdue count and the single most urgent overdue item if any exist; otherwise the soonest
     * upcoming item from [VehicleController.nextService]. [DigestText.notLogged] only when this car
     * has no maintenance schedule seeded at all - a schedule with zero due items is a real,
     * computed "on track", not an absent record. `internal` for direct unit testing.
     *
     * **The `next.byMiles`/`next.byTime` branches carry a "- guess, unconfirmed" suffix off
     * [VehicleController.ServiceCandidate.isGuess]** (mission-control ticket 16,
     * `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`)
     * - both name a real interval-derived timing figure fed straight into HOME's own digest, i.e. a
     * model's context. The overdue branch above (`due.first().serviceName`) is deliberately left
     * alone: it names a count and a service name but renders no interval or timing figure at all, so
     * there is nothing here for the caveat to qualify. */
    // FLEET stays a bare TrustTier.REPORTED rather than a combinedTier() call, deliberately, unlike
    // BIO/CRED above: [MaintenanceItem] carries no per-row TrustTier field to combine, because - per
    // FleetDigestBuilder's own class doc - EVERY fleet figure derived from a car's own odometer/
    // maintenance schedule is REPORTED by construction (nothing external to reconcile it against,
    // unlike a ledger row's printed statement total). FleetDigestBuilder itself never calls
    // combinedTier() for exactly this reason. Manufacturing a fake per-row tier here just to route it
    // through combinedTier() would be worse than the hardcode it replaced.
    internal fun fleetHeadline(items: List<MaintenanceItem>, due: List<MaintenanceItem>, next: VehicleController.NextService?): String {
        if (items.isEmpty()) return DigestText.line("FLEET", DigestText.notLogged())
        if (due.isNotEmpty()) {
            return DigestText.withTier(
                DigestText.line("FLEET", "${due.size} item(s) overdue, most urgent ${due.first().serviceName}"),
                TrustTier.REPORTED,
            )
        }
        val phrase = when {
            next == null -> "no schedule to report"
            next.allDue -> "everything anchored is already due"
            next.byMiles != null -> "next ${next.byMiles.serviceName} in ${next.byMiles.remaining} mi" +
                (if (next.byMiles.isGuess) " - guess, unconfirmed" else "")
            next.byTime != null -> "next ${next.byTime.serviceName} in ${next.byTime.remaining} days" +
                (if (next.byTime.isGuess) " - guess, unconfirmed" else "")
            else -> "on track, nothing anchored yet"
        }
        return DigestText.withTier(DigestText.line("FLEET", phrase), TrustTier.REPORTED)
    }

    // ------------------------------------------------------------------------------ LOG headline

    /** Overdue-reminder count plus a plain open-task count (non-recurring, non-timed, undone items
     * - the same "task" shape [LogDigestBuilder.openTaskItems] uses, computed independently here
     * rather than calling that builder, per this file's class doc). `internal` for direct unit
     * testing. */
    internal fun logHeadline(allActive: List<ListItem>, missed: List<ListItem>): String {
        val openTasks = allActive.count { !it.done && it.startsAt == null && it.repeatKind == null }
        if (openTasks == 0 && missed.isEmpty()) return DigestText.line("LOG", DigestText.notLogged())
        return DigestText.line("LOG", "${missed.size} reminder(s) overdue, $openTasks open task(s)")
    }

    // ------------------------------------------------------------------------ cross-aspect flags

    /** Any ACTIVE [Goal] whose own stated [Goal.deadlineEpoch] has already passed - a plain, real,
     * unambiguous fact requiring no metric-projection math (that belongs to the aspect that owns
     * the goal's [Goal.metricKey], per ticket 09 answer call 4's "defers domain depth"). Reads
     * "none flagged" when there are none - a real computed zero, not [DigestText.notLogged] (the
     * goal data is not absent, there is simply nothing to flag). `internal` for direct unit
     * testing. */
    internal fun exceptionsLine(goals: List<Goal>, now: Long): String {
        val overdue = goals.filter { it.deadlineEpoch != null && it.deadlineEpoch < now }
        if (overdue.isEmpty()) return DigestText.line("EXCEPTIONS", "none flagged")
        val names = overdue.joinToString(", ") { "[${it.aspect}] ${it.statement} (was due ${compactDate(it.deadlineEpoch!!)})" }
        return DigestText.line("EXCEPTIONS", names)
    }

    /** UTC month-start epoch millis for [nowMs] - matches [BudgetTarget.effectiveFromMonthEpoch]'s
     * own documented convention exactly, so [com.kevin.legion.data.local.BudgetTargetDao
     * .currentTargets] resolves the same "this month" a human would mean. */
    private fun monthStartUtcMs(nowMs: Long): Long =
        Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
