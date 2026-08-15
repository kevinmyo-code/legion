package com.kevin.legion.ui.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for [LedgerRecategorizeResolver] - no Android dependency, plain JVM test. */
class LedgerRecategorizeResolverTest {

    @Test
    fun `default key reduces both Petco descriptions to the same merchant key`() {
        // The concrete case Kevin drilled into: one rule 'PETCO -> Shopping' governs both, and
        // he wants both moved to 'Pets' with a single edit.
        assertEquals("PETCO", LedgerRecategorizeResolver.defaultKey("PETCO 5421 CYPRESS TX"))
        assertEquals(
            "PETCO",
            LedgerRecategorizeResolver.defaultKey("PETCO 5421 08/01 PURCHASE CYPRESS TX"),
        )
    }

    @Test
    fun `a key at exactly the four-character floor is long enough`() {
        assertTrue(LedgerRecategorizeResolver.isKeyLongEnough("PETC"))
    }

    @Test
    fun `a three-character key is refused, matching setCategory's own floor`() {
        assertFalse(LedgerRecategorizeResolver.isKeyLongEnough("PET"))
    }

    @Test
    fun `surrounding whitespace does not count toward the floor`() {
        // 'PET' padded to look four characters long must still read as three once trimmed - the
        // controller trims before measuring, and this must never disagree with it.
        assertFalse(LedgerRecategorizeResolver.isKeyLongEnough("  PET  "))
    }

    @Test
    fun `an empty typed key is refused`() {
        assertFalse(LedgerRecategorizeResolver.isKeyLongEnough(""))
    }

    @Test
    fun `normalized key is trimmed and uppercased, matching the LIKE match it stands in for`() {
        assertEquals("PETCO", LedgerRecategorizeResolver.normalizedKey("  petco  "))
    }
}
