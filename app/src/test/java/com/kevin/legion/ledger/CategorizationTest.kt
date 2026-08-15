package com.kevin.legion.ledger

import com.kevin.legion.data.local.CategoryRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [matchCategory] and [extractMerchantKey] - ticket 07 D16/D18, pure and unit-testable without Room. */
class CategorizationTest {

    @Test
    fun `D16 - a substring rule matches regardless of the store number`() {
        val rules = listOf(
            CategoryRule(id = 1, category = "Groceries", substring = "KROGER", createdAt = 1L),
        )
        assertEquals("Groceries", matchCategory("KROGER #115 CYPRESS TX", rules))
        assertEquals("Groceries", matchCategory("KROGER #122 KATY TX", rules))
    }

    @Test
    fun `matchCategory is null when nothing matches - stays uncategorised, D11`() {
        val rules = listOf(CategoryRule(id = 1, category = "Groceries", substring = "KROGER", createdAt = 1L))
        assertNull(matchCategory("UNKNOWN MERCHANT LLC", rules))
    }

    @Test
    fun `the earliest rule for a merchant governs it - a later looser rule never silently takes over`() {
        val rules = listOf(
            CategoryRule(id = 1, category = "Groceries", substring = "KROGER", createdAt = 100L),
            CategoryRule(id = 2, category = "Shopping", substring = "KRO", createdAt = 200L),
        )
        // Both substrings match "KROGER #115..." - the OLDER rule wins.
        assertEquals("Groceries", matchCategory("KROGER #115 CYPRESS TX", rules))
    }

    @Test
    fun `extractMerchantKey - the two worked examples from ticket 07 §2`() {
        assertEquals("WM SUPERCENTER", extractMerchantKey("WM SUPERCENTER #4512 KATY TX"))
        assertEquals("KROGER", extractMerchantKey("KROGER #115 CYPRESS TX"))
    }

    @Test
    fun `extractMerchantKey - two store numbers of the same chain reduce to the same key`() {
        assertEquals(extractMerchantKey("KROGER #115 CYPRESS TX"), extractMerchantKey("KROGER #122 KATY TX"))
    }

    @Test
    fun `extractMerchantKey - a description with no digit run falls back to the whole trimmed uppercase string`() {
        assertEquals("COFFEE SHOP DOWNTOWN", extractMerchantKey("coffee shop downtown"))
    }

    @Test
    fun `extractMerchantKey - a bare run of 3+ digits with no hash also splits`() {
        assertEquals("SAFEWAY", extractMerchantKey("SAFEWAY 4512 SEATTLE WA"))
    }

    // ---- 2026-08-13: real Bank of America card-line shapes, the CHECKCARD bug ----
    //
    // These are Kevin's actual statement text (CLAUDE.md's ban on quoting real transaction detail
    // in a REPORT doesn't apply to test fixtures - these are the literal shapes the parser must
    // handle, not private financial facts). `extractMerchantKey` used to split at the first 3+-digit
    // run, which on these lines is the MMDD posting date immediately after "CHECKCARD"/"PURCHASE",
    // so every one of these collapsed to the bank's own word instead of the merchant.

    @Test
    fun `extractMerchantKey - CHECKCARD prefix and its MMDD date are stripped before the store-number split`() {
        assertEquals(
            "TMOBILE PREPD BELLEVUE WA",
            extractMerchantKey("CHECKCARD  0429 TMOBILE PREPD BELLEVUE     WA"),
        )
    }

    @Test
    fun `extractMerchantKey - PURCHASE prefix strips, then the store-number split still fires on the reference number`() {
        // The store-number split (unchanged behaviour) still finds "12555" inside the reference
        // number and cuts there - narrow and a little odd, but per CLAUDE.md's brief: narrow is
        // a correctable annoyance, never a silently wrong merged-merchant key like CHECKCARD was.
        assertEquals(
            "EBAY O*08-",
            extractMerchantKey("PURCHASE   0108 eBay O*08-12555-3 4083766151   CA"),
        )
    }

    @Test
    fun `extractMerchantKey - CHECKCARD prefix strips even when the remaining merchant text has no digits at all`() {
        assertEquals(
            "WM SUPERCENTER KATY TX",
            extractMerchantKey("CHECKCARD  0115 WM SUPERCENTER KATY         TX"),
        )
    }

    @Test
    fun `extractMerchantKey - a merchant that merely CONTAINS the word PURCHASE is untouched`() {
        // The noise-prefix strip is anchored at the START of the description only - it must never
        // eat into a real merchant name that happens to contain one of these words mid-string.
        assertEquals("BIG PURCHASE OUTLET", extractMerchantKey("BIG PURCHASE OUTLET 4512 KATY TX"))
    }

    // ---- isBankNoiseKey: the systemic half of the fix (rule creation refusal) ----

    @Test
    fun `isBankNoiseKey - the bare noise word alone, any case, is flagged`() {
        assertTrue(isBankNoiseKey("CHECKCARD"))
        assertTrue(isBankNoiseKey("checkcard"))
        assertTrue(isBankNoiseKey("CHKCARD"))
        assertTrue(isBankNoiseKey("PURCHASE"))
    }

    @Test
    fun `isBankNoiseKey - the noise word plus only a date-shaped fragment is still pure noise`() {
        assertTrue(isBankNoiseKey("CHECKCARD 0429"))
    }

    @Test
    fun `isBankNoiseKey - a real merchant name is never flagged`() {
        assertFalse(isBankNoiseKey("TMOBILE PREPD BELLEVUE WA"))
        assertFalse(isBankNoiseKey("KROGER"))
        assertFalse(isBankNoiseKey("WM SUPERCENTER"))
    }

    @Test
    fun `isBankNoiseKey - real merchant text surviving after the noise word means it's NOT pure noise`() {
        // Real content after the bank's own word is a normal, if oddly-prefixed, key - only the
        // BARE word (or the word plus nothing but its date) is refused.
        assertFalse(isBankNoiseKey("CHECKCARD 0429 TMOBILE"))
    }

    // ---- isBankNoiseKey extended 2026-08-13: transfer-shaped keys share the same gate ----
    // (`.scratch/car-probe-transfers/`) - see isBankNoiseKey's own doc comment for why bank-noise
    // prefixes and transfer wording are refused through one function rather than two.

    @Test
    fun `isBankNoiseKey - Kevin's real transfer wordings are refused, any case`() {
        assertTrue(isBankNoiseKey("MOBILE BANKING PAYMENT TO CRD"))
        assertTrue(isBankNoiseKey("ONLINE BANKING PAYMENT TO CRD"))
        assertTrue(isBankNoiseKey("payment from chk"))
        assertTrue(isBankNoiseKey("PAYMENT TO CRD"))
    }

    @Test
    fun `isBankNoiseKey - every TRANSFER_KEYWORDS entry is refused as a bare key`() {
        for (keyword in TRANSFER_KEYWORDS) {
            assertTrue("expected '$keyword' to be refused as transfer-shaped", isBankNoiseKey(keyword))
        }
    }

    @Test
    fun `isBankNoiseKey - a real merchant name is still never flagged by the transfer extension`() {
        assertFalse(isBankNoiseKey("KROGER"))
        assertFalse(isBankNoiseKey("NETFLIX.COM"))
        assertFalse(isBankNoiseKey("TMOBILE PREPD BELLEVUE WA"))
    }
}
