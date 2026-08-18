package com.kevin.legion.ledger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [sameCard] and [referencesOwnAccount] against the four real description shapes
 * Kevin's 2026-08-13 decision measured (`.scratch/car-probe-transfers/`): a card payment, a
 * savings/checking transfer, a Zelle payment TO a person, a Zelle payment FROM a person. Plain
 * JUnit, no Room, no Android - both functions are pure by construction.
 */
class LedgerAccountIdentityTest {

    @Test
    fun `sameCard matches on last-4 suffix regardless of full account length`() {
        assertTrue(sameCard("4111111111117823", "7823"))
        assertTrue(sameCard("7823", "4111111111117823"))
        assertFalse(sameCard("4111111111117823", "9999"))
    }

    @Test
    fun `a card payment naming the card's own account is an own-account reference`() {
        // Real shape: `Online Banking payment to CRD 7823 Confirmation# 0649409616`.
        assertTrue(
            referencesOwnAccount(
                "Online Banking payment to CRD 7823 Confirmation# 0649409616",
                ownAccountIds = setOf("4111111111117823"),
            ),
        )
    }

    @Test
    fun `a checking-to-card payment naming the checking account is an own-account reference`() {
        // Real shape: `PAYMENT FROM CHK 5042 CONF#m7huqidzh`.
        assertTrue(
            referencesOwnAccount(
                "PAYMENT FROM CHK 5042 CONF#m7huqidzh",
                ownAccountIds = setOf("4111111115042"),
            ),
        )
    }

    @Test
    fun `a savings transfer naming an account never on file is NOT an own-account reference - conservative by design`() {
        // Real shape: `Online Banking transfer from SAV 8267 Confirmation# 1731436758` - Kevin's
        // real data never had a statement imported for the account ending 8267, so `ownAccountIds`
        // (built from LedgerTransactionDao.accountIdsForCurrency) never contains it. Per the
        // 2026-08-13 instruction, the failure that matters is wrongly EXCLUDING real spend, so an
        // unconfirmed reference must return false here rather than guess - this row falls back to
        // whatever TRANSFER_KEYWORDS already decided (the pre-existing status quo), never worse.
        assertFalse(
            referencesOwnAccount(
                "Online Banking transfer from SAV 8267 Confirmation# 1731436758",
                ownAccountIds = setOf("4111111115042", "4111111111117823"),
            ),
        )
    }

    @Test
    fun `a Zelle payment TO a person names no account and is never an own-account reference`() {
        // Real shape, name invented: `Zelle payment to  R Alan Cole US Conf# b4nb0qacg` - a person's name, not an
        // account digit, so this must never match regardless of what ownAccountIds contains.
        assertFalse(
            referencesOwnAccount(
                "Zelle payment to  R Alan Cole US Conf# b4nb0qacg",
                ownAccountIds = setOf("4111111115042", "4111111111117823"),
            ),
        )
    }

    @Test
    fun `a Zelle payment FROM a person names no account and is never an own-account reference`() {
        // Real shape, name invented: `Zelle payment from JANE R DOE Conf# pwekdcqu8`.
        assertFalse(
            referencesOwnAccount(
                "Zelle payment from JANE R DOE Conf# pwekdcqu8",
                ownAccountIds = setOf("4111111115042", "4111111111117823"),
            ),
        )
    }

    @Test
    fun `a merchant address digit run is never mistaken for an account reference`() {
        // Real false-positive risk this repo measured: `CIRCLE K # 48267 CYPRESS TX` contains the
        // digits 48267 (which even ENDS in 8267, the savings account's own last-4) purely by
        // coincidence of a gas station's store number - it must never match because it is never
        // preceded by CRD/CHK/SAV/ACCT.
        assertFalse(
            referencesOwnAccount(
                "CIRCLE K # 48267         CYPRESS      TX",
                ownAccountIds = setOf("4111111115042", "4111111111117823", "38267"),
            ),
        )
    }

    @Test
    fun `an empty ownAccountIds set never matches anything, even a real CRD reference`() {
        assertFalse(referencesOwnAccount("PAYMENT TO CRD 7823", ownAccountIds = emptySet()))
    }

    @Test
    fun `a description naming no digits at all never matches`() {
        assertFalse(referencesOwnAccount("MOBILE BANKING PAYMENT TO CRD", ownAccountIds = setOf("4111111111117823")))
    }
}
