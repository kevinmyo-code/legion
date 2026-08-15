package com.kevin.legion.plan

import org.junit.Assert.assertEquals
import org.junit.Test

/** [combinedTier] is D6's rule, expressed exactly once - these pin the rule directly. */
class PlanTest {

    @Test
    fun `all PROVEN reduces to PROVEN`() {
        assertEquals(TrustTier.PROVEN, listOf(TrustTier.PROVEN, TrustTier.PROVEN, TrustTier.PROVEN).combinedTier())
    }

    @Test
    fun `a single REPORTED among many PROVEN makes the whole reduction REPORTED - no proportion`() {
        val nineProven = List(9) { TrustTier.PROVEN }
        val tiers = nineProven + TrustTier.REPORTED
        assertEquals(TrustTier.REPORTED, tiers.combinedTier())
    }

    @Test
    fun `all REPORTED reduces to REPORTED`() {
        assertEquals(TrustTier.REPORTED, listOf(TrustTier.REPORTED, TrustTier.REPORTED).combinedTier())
    }

    @Test
    fun `an empty set reduces to PROVEN - there is no reported entry to taint it`() {
        assertEquals(TrustTier.PROVEN, emptyList<TrustTier>().combinedTier())
    }

    @Test
    fun `PlanGap carries target, actual, gap and a combined tier with no other assumptions`() {
        val gap = PlanGap(target = 100L, actual = 40L, gap = 60L, tier = TrustTier.PROVEN)
        assertEquals(100L, gap.target)
        assertEquals(40L, gap.actual)
        assertEquals(60L, gap.gap)
        assertEquals(TrustTier.PROVEN, gap.tier)
    }
}
