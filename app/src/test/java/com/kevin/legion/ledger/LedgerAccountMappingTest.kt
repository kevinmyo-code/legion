package com.kevin.legion.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JVM, no Robolectric - [resolveAccountHint] is pure map lookup, no
 * SharedPreferences/Context. [LedgerAccountMappingPreferences.accountFor]
 * delegates straight to it; this is the testable half of that object per
 * `playbook-coding.md`'s "keep platform calls out of it" testing note.
 */
class LedgerAccountMappingTest {
    @Test
    fun `a mapped folder id resolves to its account`() {
        val mapping = mapOf("folder-checking" to "BOFA-CHECKING", "folder-credit" to "BOFA-CREDIT")
        assertEquals("BOFA-CHECKING", resolveAccountHint("folder-checking", mapping))
        assertEquals("BOFA-CREDIT", resolveAccountHint("folder-credit", mapping))
    }

    @Test
    fun `an unmapped folder id resolves to null, never a guess`() {
        val mapping = mapOf("folder-checking" to "BOFA-CHECKING")
        assertNull(resolveAccountHint("folder-unknown", mapping))
    }

    @Test
    fun `a null folder id (file directly in the connected root) always resolves to null`() {
        val mapping = mapOf("folder-checking" to "BOFA-CHECKING")
        assertNull(resolveAccountHint(null, mapping))
    }

    @Test
    fun `an empty mapping resolves everything to null`() {
        assertNull(resolveAccountHint("folder-checking", emptyMap()))
    }
}
