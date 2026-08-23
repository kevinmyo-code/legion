package com.kevin.legion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A minimal, honest [ReconciliationGate] implementation - Long-cents rows, exact-sum reconciliation
 * - so ticket 16's "gate behaviors" tests exercise the CONTRACT (CLAUDE.md §4 rules 2/3/6/7) without
 * depending on a real ingestion plugin, which this ticket deliberately does not build (see
 * [ReconciliationGate]'s own doc comment: "this ticket rehomes the CONTRACT, not the ledger/pantry
 * implementations").
 */
private class MoneyCentsGate : ReconciliationGate<Long> {
    override fun reconcile(extracted: List<Long>, statedAnchorCents: Long): GateResult<Long> {
        val sum = extracted.sum()
        return if (sum == statedAnchorCents) {
            GateResult.Reconciled(extracted)
        } else {
            GateResult.Quarantined("extracted rows summed to $sum cents, statement says $statedAnchorCents cents")
        }
    }

    override fun provisional(extracted: List<Long>): GateResult.Provisional<Long> = GateResult.Provisional(extracted)
}

class ReconciliationGateTest {
    private val gate = MoneyCentsGate()

    @Test
    fun `exact sum reconciles`() {
        val result = gate.reconcile(listOf(1000L, 2000L, 500L), 3500L)
        assertTrue(result is GateResult.Reconciled)
        assertEquals(listOf(1000L, 2000L, 500L), (result as GateResult.Reconciled).rows)
    }

    @Test
    fun `a one-cent mismatch quarantines, never rounds or accepts close-enough`() {
        val result = gate.reconcile(listOf(1000L, 2000L, 501L), 3500L)
        assertTrue(result is GateResult.Quarantined)
        val reason = (result as GateResult.Quarantined).reason
        assertTrue("reason must be worded, not empty", reason.isNotBlank())
    }

    @Test
    fun `an empty extraction against a nonzero anchor quarantines - a pass is not free`() {
        // CLAUDE.md §4 rule 6: "a check that passes when nothing parsed is not a gate." An empty
        // list summing to 0 against any nonzero stated anchor must quarantine, not silently pass.
        val result = gate.reconcile(emptyList(), 100L)
        assertTrue(result is GateResult.Quarantined)
    }

    @Test
    fun `an empty extraction against a zero anchor is the one legitimate empty-passes case`() {
        // The exception rule 6 itself carves out: a document that legitimately states a $0.00
        // total/section and produced zero rows is correctly reconciled, not quarantined - the
        // defect rule 6 documents was BofA's interest section silently matching rows it never
        // parsed, not a genuinely empty section reconciling against its own genuinely-zero total.
        val result = gate.reconcile(emptyList(), 0L)
        assertTrue(result is GateResult.Reconciled)
    }

    @Test
    fun `quarantined carries zero rows by construction - nothing partial to accidentally write`() {
        // GateResult.Quarantined has no `rows` field at all - the Kotlin compiler itself is the
        // check here (this would not compile if a `rows` accessor existed), which is the point:
        // a `rows = emptyList()` field would still let a caller mistakenly treat a quarantine as
        // "zero rows to write" instead of "write nothing".
        val result: GateResult.Quarantined = gate.reconcile(listOf(1L), 999L) as GateResult.Quarantined
        assertEquals("extracted rows summed to 1 cents, statement says 999 cents", result.reason)
    }

    @Test
    fun `provisional always tags every row, whatever the extraction shape`() {
        val result = gate.provisional(listOf(10L, 20L))
        assertEquals(listOf(10L, 20L), result.rows)
    }

    @Test
    fun `provisional never reconciles - it is a different code path entirely`() {
        // The type system already forbids a GateResult.Provisional from being mistaken for
        // Reconciled (sealed class, distinct subtypes) - this test documents that as the load-
        // bearing property CLAUDE.md §4 rule 7 depends on: a provisional row must never be
        // consumed by code that only checks "is this GateResult.Reconciled".
        val result: GateResult<Long> = gate.provisional(listOf(5L))
        assertTrue(result is GateResult.Provisional)
        assertTrue(result !is GateResult.Reconciled)
    }
}
