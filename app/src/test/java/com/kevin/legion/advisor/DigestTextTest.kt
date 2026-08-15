package com.kevin.legion.advisor

import com.kevin.legion.plan.TrustTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DigestText] is the load-bearing safety surface every [DigestBuilder] must route figures
 * through (CLAUDE.md §4 rules 5/7) - see that object's doc comment. Thorough coverage here because
 * a silent regression in these helpers would ship an unlabelled estimate or an unverified figure
 * dressed up as fact across all five advisors at once.
 */
class DigestTextTest {

    @Test
    fun `line joins label and value with a single space`() {
        assertEquals("BUDGET groceries target 400.00", DigestText.line("BUDGET groceries target", "400.00"))
    }

    @Test
    fun `line with an empty value still has the separating space`() {
        assertEquals("LABEL ", DigestText.line("LABEL", ""))
    }

    @Test
    fun `withTier appends proven in brackets, lowercase`() {
        assertEquals("actual 312.45 [proven]", DigestText.withTier("actual 312.45", TrustTier.PROVEN))
    }

    @Test
    fun `withTier appends reported in brackets, lowercase`() {
        assertEquals("actual 312.45 [reported]", DigestText.withTier("actual 312.45", TrustTier.REPORTED))
    }

    @Test
    fun `unverified appends the exact word unverified, in words`() {
        val out = DigestText.unverified("87.55")
        assertEquals("87.55 (unverified)", out)
        assertTrue("must contain the literal word 'unverified'", out.contains("unverified"))
    }

    @Test
    fun `estimate appends the exact word estimate, in words`() {
        val out = DigestText.estimate("620 kcal")
        assertEquals("620 kcal (estimate)", out)
        assertTrue("must contain the literal word 'estimate'", out.contains("estimate"))
    }

    @Test
    fun `notLogged is the fixed phrase, never a number`() {
        val out = DigestText.notLogged()
        assertEquals("not logged", out)
        assertFalse("must never render as a bare zero", out == "0")
    }

    @Test
    fun `unverified and estimate compose - a figure can be both flagged`() {
        // Not a real-world combination the builders are expected to hit, but the helpers must not
        // silently clobber each other if a future digest ever needs to stack both labels.
        val out = DigestText.estimate(DigestText.unverified("100"))
        assertTrue(out.contains("unverified"))
        assertTrue(out.contains("estimate"))
    }

    @Test
    fun `withTier composes on top of a line built from label and value`() {
        val out = DigestText.withTier(DigestText.line("BUDGET groceries actual", "312.45"), TrustTier.REPORTED)
        assertEquals("BUDGET groceries actual 312.45 [reported]", out)
    }
}
