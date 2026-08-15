package com.kevin.legion.ui.ledger

import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.extractMerchantKey
import com.kevin.legion.ledger.isBankNoiseKey

/**
 * Pure logic for the drill-down row's hand-recategorise panel (Kevin 2026-08-07: "PETCO 5421
 * CYPRESS TX and PETCO 5421 08/01 PURCHASE CYPRESS TX are both in Shopping, I want them in Pets,
 * and I'm already looking right at them"). Same "pure resolver, thin composable wrapper" split
 * every other `ui.ledger` decision on this screen already follows ([LedgerCategoryResolver],
 * [LedgerPendingResolver]) - kept Compose-free so the key-normalisation and floor-check branch is
 * a plain JUnit test, not a Robolectric one.
 *
 * This object never writes anything - it only derives what a driver SEES before they act, and
 * whether the Apply button in [CategoryDrilldownScreen] should be enabled. The write itself routes
 * through [LedgerController.setCategory] unchanged, exactly the same function `set_category` (the
 * voice tool) already calls - there is deliberately no second write path here.
 */
object LedgerRecategorizeResolver {
    /**
     * The key a driver sees when they first open the panel on one transaction row -
     * [extractMerchantKey]'s normalisation of that row's own description, the SAME derivation
     * [LedgerController.uncategorizedMerchants]/`categorize_transactions` already group by, so the
     * hand path and the voice path derive the identical key from the identical description
     * (ticket instruction: "so the hand path and the voice path derive the same key from the same
     * description"). For `PETCO 5421 CYPRESS TX` and `PETCO 5421 08/01 PURCHASE CYPRESS TX` this
     * is `PETCO` for both - one edit reaches both rows, rather than the raw description reaching
     * only whichever row it was tapped on.
     */
    fun defaultKey(description: String): String = extractMerchantKey(description)

    /**
     * Whether [typedKey] (whatever the driver has edited the field to, untrimmed, any case) is
     * long enough for [LedgerController.setCategory] to act on. Mirrors that function's own
     * `merchant.trim().uppercase()` normalisation and [LedgerController.MIN_MERCHANT_KEY_LENGTH]
     * floor exactly, so this can never tell a driver a key is "long enough" when the controller
     * would then silently refuse it (`keyTooShort = true`, zero rows touched, no rule written) -
     * the two checks must never drift apart from each other.
     */
    fun isKeyLongEnough(typedKey: String): Boolean =
        typedKey.trim().length >= LedgerController.MIN_MERCHANT_KEY_LENGTH

    /**
     * Whether [typedKey] is bank-generated boilerplate ("CHECKCARD", "CHKCARD", "PURCHASE" - see
     * [isBankNoiseKey]'s doc comment) rather than a merchant name, mirroring
     * [LedgerController.setCategory]'s own 2026-08-13 refusal exactly so this panel can never show
     * a live blast-radius count or an enabled APPLY button for a key the controller will then
     * refuse to act on.
     */
    fun isKeyBankNoise(typedKey: String): Boolean = isBankNoiseKey(typedKey)

    /**
     * Uppercase-and-trim, the exact form [LedgerController.setCategory]/
     * [LedgerController.previewRecategorizeCount] match against - shown back to the driver in the
     * preview sentence so what they read IS what will run against the database, never a
     * display-only approximation of it that could diverge from the real `LIKE` match.
     */
    fun normalizedKey(typedKey: String): String = typedKey.trim().uppercase()
}
