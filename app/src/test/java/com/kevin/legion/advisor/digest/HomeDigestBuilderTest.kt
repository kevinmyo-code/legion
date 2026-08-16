package com.kevin.legion.advisor.digest

import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.BudgetTarget
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.vehicle.VehicleController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [HomeDigestBuilder]'s per-aspect headline functions and the whole-digest
 * size ceiling - ticket 17's explicit requirement ("an explicit test that HOME's digest stays small
 * with all five aspects populated"). No Room, no `Context`.
 */
class HomeDigestBuilderTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000L

    // ------------------------------------------------------------------------------ empty domain

    @Test
    fun `bioHeadline with no bodyweight log at all reads not logged`() {
        val line = HomeDigestBuilder.bioHeadline(latest = null, lookback = emptyList())
        assertTrue(line.contains("BIO"))
        assertTrue(line.contains("not logged"))
    }

    @Test
    fun `credHeadline with no ledger data at all reads not logged`() {
        val line = HomeDigestBuilder.credHeadline(emptyMap(), emptyMap())
        assertTrue(line.contains("CRED"))
        assertTrue(line.contains("not logged"))
    }

    @Test
    fun `fleetHeadline with no maintenance schedule at all reads not logged`() {
        val line = HomeDigestBuilder.fleetHeadline(items = emptyList(), due = emptyList(), next = null)
        assertTrue(line.contains("FLEET"))
        assertTrue(line.contains("not logged"))
    }

    @Test
    fun `logHeadline with nothing open and nothing missed reads not logged`() {
        val line = HomeDigestBuilder.logHeadline(allActive = emptyList(), missed = emptyList())
        assertTrue(line.contains("LOG"))
        assertTrue(line.contains("not logged"))
    }

    @Test
    fun `exceptionsLine with no goals at all reads none flagged, not not logged`() {
        val line = HomeDigestBuilder.exceptionsLine(emptyList(), now)
        assertTrue(line.contains("none flagged"))
    }

    // ---------------------------------------------------------------------------------- BIO

    @Test
    fun `bioHeadline normalises lbs to kg before comparing trend`() {
        val latest = BodyweightLog(weightValue = 180.0, weightUnit = "lbs", loggedAt = now, trustTier = TrustTier.REPORTED)
        // 200 lbs ~= 90.7 kg, well above the flat threshold vs 180 lbs ~= 81.6 kg
        val lookback = listOf(BodyweightLog(weightValue = 200.0, weightUnit = "lbs", loggedAt = now - 20 * day, trustTier = TrustTier.REPORTED))
        val line = HomeDigestBuilder.bioHeadline(latest, lookback)
        assertTrue(line.contains("down"))
    }

    @Test
    fun `bioHeadline with no lookback history reads insufficient history, not a fabricated trend`() {
        val latest = BodyweightLog(weightValue = 82.0, weightUnit = "kg", loggedAt = now, trustTier = TrustTier.REPORTED)
        val line = HomeDigestBuilder.bioHeadline(latest, emptyList())
        assertTrue(line.contains("insufficient history"))
    }

    @Test
    fun `bioHeadline computes its tier via combinedTier over the rows it actually drew from`() {
        // Every BodyweightLog row is REPORTED by construction (its own class doc), so this can
        // never surface [proven] - but it must still be a COMPUTED combinedTier() call, not a bare
        // hardcode, per the defect fix. Assert the tag is present and correct rather than absent.
        val latest = BodyweightLog(weightValue = 82.0, weightUnit = "kg", loggedAt = now, trustTier = TrustTier.REPORTED)
        val line = HomeDigestBuilder.bioHeadline(latest, emptyList())
        assertTrue(line.contains("[reported]"))
    }

    // --------------------------------------------------------------------------------- CRED

    @Test
    fun `credHeadline reports the worst gap across currencies without summing them`() {
        val targets = mapOf(
            LedgerCurrency.USD to listOf(BudgetTarget(category = "Groceries", currency = LedgerCurrency.USD, amountCents = 40000, effectiveFromMonthEpoch = 0, updatedAt = 0)),
            LedgerCurrency.SGD to listOf(BudgetTarget(category = "Groceries", currency = LedgerCurrency.SGD, amountCents = 40000, effectiveFromMonthEpoch = 0, updatedAt = 0)),
        )
        val overspendUsd = LedgerTransaction(
            sourceFile = "f", accountId = "a", currency = LedgerCurrency.USD, txnDate = now,
            description = "Whole Foods", amountCents = -50000, lineRef = "1",
            ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC, category = "Groceries",
        )
        val txns = mapOf(LedgerCurrency.USD to listOf(overspendUsd), LedgerCurrency.SGD to emptyList())
        val line = HomeDigestBuilder.credHeadline(targets, txns)
        assertTrue(line.contains("USD"))
        assertTrue(line.contains("over by"))
    }

    @Test
    fun `credHeadline reports a computed PROVEN tier when every contributing row reconciled`() {
        // Defect 3 fix: the tier must be COMPUTED via combinedTier() over the rows the headline
        // actually summed, not a bare hardcoded TrustTier.REPORTED - a DETERMINISTIC, non-pending
        // row is PROVEN, and this headline is built from exactly one such row.
        val targets = mapOf(
            LedgerCurrency.USD to listOf(
                BudgetTarget(category = "Groceries", currency = LedgerCurrency.USD, amountCents = 40000, effectiveFromMonthEpoch = 0, updatedAt = 0),
            ),
        )
        val provenTxn = LedgerTransaction(
            sourceFile = "f", accountId = "a", currency = LedgerCurrency.USD, txnDate = now,
            description = "Whole Foods", amountCents = -5000, lineRef = "1",
            ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC, category = "Groceries",
        )
        val line = HomeDigestBuilder.credHeadline(targets, mapOf(LedgerCurrency.USD to listOf(provenTxn)))
        assertTrue("every contributing row reconciled, so the headline must read [proven]", line.contains("[proven]"))
    }

    @Test
    fun `credHeadline reports a computed REPORTED tier when a contributing row is unreconciled`() {
        val targets = mapOf(
            LedgerCurrency.USD to listOf(
                BudgetTarget(category = "Groceries", currency = LedgerCurrency.USD, amountCents = 40000, effectiveFromMonthEpoch = 0, updatedAt = 0),
            ),
        )
        val unreconciledTxn = LedgerTransaction(
            sourceFile = "f", accountId = "a", currency = LedgerCurrency.USD, txnDate = now,
            description = "Card CSV row", amountCents = -5000, lineRef = "1",
            ingestMethod = com.kevin.legion.data.local.IngestMethod.UNRECONCILED, category = "Groceries",
        )
        val line = HomeDigestBuilder.credHeadline(targets, mapOf(LedgerCurrency.USD to listOf(unreconciledTxn)))
        assertTrue("one unreconciled row taints the whole gap REPORTED, D6's own rule", line.contains("[reported]"))
    }

    @Test
    fun `credHeadline with spend but no budget target states no budget set`() {
        val txn = LedgerTransaction(
            sourceFile = "f", accountId = "a", currency = LedgerCurrency.USD, txnDate = now,
            description = "Whole Foods", amountCents = -5000, lineRef = "1",
            ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC, category = "Groceries",
        )
        val line = HomeDigestBuilder.credHeadline(emptyMap(), mapOf(LedgerCurrency.USD to listOf(txn)))
        assertTrue(line.contains("no budget set"))
    }

    @Test
    fun `credHeadline leaves uncategorized rows out of spend and says so in words`() {
        // Kevin 2026-08-15: spend is categorised rows only, everywhere - including here, where the
        // headline sums raw rows rather than reading a built BudgetVsActual.
        val categorised = LedgerTransaction(
            sourceFile = "f", accountId = "a", currency = LedgerCurrency.USD, txnDate = now,
            description = "Whole Foods", amountCents = -5000, lineRef = "1",
            ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC, category = "Groceries",
        )
        val mystery = LedgerTransaction(
            sourceFile = "f", accountId = "a", currency = LedgerCurrency.USD, txnDate = now,
            description = "UNKNOWN MERCHANT", amountCents = -3412, lineRef = "2",
            ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC,
        )
        val line = HomeDigestBuilder.credHeadline(emptyMap(), mapOf(LedgerCurrency.USD to listOf(categorised, mystery)))

        assertTrue("only the categorised row is spend", line.contains("spent 50.00"))
        assertTrue("what was left out is stated, never silently dropped", line.contains("34.12 uncategorized not counted"))
    }

    // -------------------------------------------------------------------------------- FLEET

    @Test
    fun `fleetHeadline names the most urgent overdue item when any are overdue`() {
        val item = MaintenanceItem(vehicleId = "v", serviceName = "Oil Change", neverDone = true)
        val line = HomeDigestBuilder.fleetHeadline(items = listOf(item), due = listOf(item), next = null)
        assertTrue(line.contains("1 item(s) overdue"))
        assertTrue(line.contains("Oil Change"))
    }

    @Test
    fun `fleetHeadline with nothing overdue names the next upcoming item`() {
        val next = VehicleController.NextService(
            byMiles = VehicleController.ServiceCandidate("Oil Change", 400, VehicleController.ScheduleUnit.MILES),
            byTime = null, unknownCount = 0, unknownNames = emptyList(), odometerUnset = false, allDue = false,
        )
        val item = MaintenanceItem(vehicleId = "v", serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 100)
        val line = HomeDigestBuilder.fleetHeadline(items = listOf(item), due = emptyList(), next = next)
        assertTrue(line.contains("next Oil Change in"))
    }

    @Test
    fun `fleetHeadline's next-upcoming branch carries a guess suffix when the candidate is unconfirmed`() {
        // Mission-control ticket 16 (`.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-
        // dead-surface-and-missed-a-live-one.md`): this branch renders an interval-derived timing
        // figure straight into HOME's own digest (a model's context), so ServiceCandidate.isGuess
        // must carry through here too.
        val next = VehicleController.NextService(
            byMiles = VehicleController.ServiceCandidate("Oil Change", 400, VehicleController.ScheduleUnit.MILES, isGuess = true),
            byTime = null, unknownCount = 0, unknownNames = emptyList(), odometerUnset = false, allDue = false,
        )
        val item = MaintenanceItem(vehicleId = "v", serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 100, intervalSource = "SEEDED")
        val line = HomeDigestBuilder.fleetHeadline(items = listOf(item), due = emptyList(), next = next)
        assertTrue(line.contains("next Oil Change in 400 mi - guess, unconfirmed"))
    }

    @Test
    fun `fleetHeadline's overdue branch names no interval or timing figure, so it carries no guess suffix even for an unconfirmed item`() {
        // The overdue branch above names a count and a service name only ("N item(s) overdue, most
        // urgent X") - there is no rendered interval/timing figure for a caveat to qualify, unlike
        // the next-upcoming branch. Ticket 16's own instruction: "if one genuinely does not render
        // the interval or timing, leave it and say so."
        val item = MaintenanceItem(vehicleId = "v", serviceName = "Oil Change", neverDone = true, intervalSource = "SEEDED", intervalMiles = 5000)
        val line = HomeDigestBuilder.fleetHeadline(items = listOf(item), due = listOf(item), next = null)
        assertFalse(line.contains("guess"))
    }

    // ---------------------------------------------------------------------------------- LOG

    @Test
    fun `logHeadline counts overdue reminders and open tasks together`() {
        val open = ListItem(listId = 1, text = "Buy milk", createdAt = now, updatedAt = now)
        val missed = ListItem(listId = 1, text = "Call the vet", createdAt = now, updatedAt = now, startsAt = now - day)
        val line = HomeDigestBuilder.logHeadline(allActive = listOf(open), missed = listOf(missed))
        assertTrue(line.contains("1 reminder(s) overdue"))
        assertTrue(line.contains("1 open task(s)"))
    }

    // ------------------------------------------------------------------------------ exceptions

    @Test
    fun `exceptionsLine flags an active goal past its own deadline`() {
        val goal = Goal(lineageId = 1, aspect = "cred", statement = "Save 30k", deadlineEpoch = now - day, status = "active")
        val line = HomeDigestBuilder.exceptionsLine(listOf(goal), now)
        assertTrue(line.contains("Save 30k"))
        assertTrue(line.contains("cred"))
    }

    @Test
    fun `exceptionsLine does not flag a goal with no deadline or a future one`() {
        val noDeadline = Goal(lineageId = 1, aspect = "bio", statement = "Get to 175", status = "active")
        val future = Goal(lineageId = 2, aspect = "log", statement = "Ship the deck", deadlineEpoch = now + day, status = "active")
        val line = HomeDigestBuilder.exceptionsLine(listOf(noDeadline, future), now)
        assertTrue(line.contains("none flagged"))
    }

    // --------------------------------------------------------------------------- size ceiling

    @Test
    fun `the whole digest with all five aspects populated stays roughly one aspect digest in size`() {
        val bio = HomeDigestBuilder.bioHeadline(
            latest = BodyweightLog(weightValue = 82.0, weightUnit = "kg", loggedAt = now, trustTier = TrustTier.REPORTED),
            lookback = listOf(BodyweightLog(weightValue = 83.0, weightUnit = "kg", loggedAt = now - 20 * day, trustTier = TrustTier.REPORTED)),
        )
        val cred = HomeDigestBuilder.credHeadline(
            mapOf(LedgerCurrency.USD to listOf(BudgetTarget(category = "Groceries", currency = LedgerCurrency.USD, amountCents = 40000, effectiveFromMonthEpoch = 0, updatedAt = 0))),
            mapOf(
                LedgerCurrency.USD to listOf(
                    LedgerTransaction(
                        sourceFile = "f", accountId = "a", currency = LedgerCurrency.USD, txnDate = now,
                        description = "Whole Foods", amountCents = -31245, lineRef = "1",
                        ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC,
                    ),
                ),
            ),
        )
        val fleetItem = MaintenanceItem(vehicleId = "v", serviceName = "Oil Change", neverDone = true)
        val fleet = HomeDigestBuilder.fleetHeadline(items = listOf(fleetItem), due = listOf(fleetItem), next = null)
        val log = HomeDigestBuilder.logHeadline(
            allActive = listOf(ListItem(listId = 1, text = "Buy milk", createdAt = now, updatedAt = now)),
            missed = listOf(ListItem(listId = 1, text = "Call the vet", createdAt = now, updatedAt = now, startsAt = now - day)),
        )
        val exceptions = HomeDigestBuilder.exceptionsLine(
            listOf(Goal(lineageId = 1, aspect = "cred", statement = "Save 30k for a downpayment", deadlineEpoch = now - day, status = "active")),
            now,
        )
        val digest = listOf(bio, cred, fleet, log, exceptions).joinToString("\n")

        // Ticket 11 measured FLEET's own single-aspect digest at ~247 tokens with a real tokenizer;
        // this offline chars/4 estimate (same heuristic AdvisorAgent.estimateTokens uses, `reasoned`
        // not `measured` per that function's own doc comment) is the cheap check this ticket's
        // build step can run without a billed countTokens call - it must stay well clear of a
        // four-digest (~900+ token) blow-out, which is the failure this test exists to catch.
        val estimatedTokens = kotlin.math.ceil(digest.length / 4.0).toInt()
        assertTrue("HOME digest estimated at $estimatedTokens tokens, expected well under 300 (one aspect digest)", estimatedTokens < 300)
        assertFalse(digest.contains("PLAYBOOK"))
    }
}
