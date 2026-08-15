package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ticket 07 D16: "Rules are substring matches on the UPPERCASED description, stored in Room,
 * editable." Verified against Kevin's real rows in the ticket's own resolution: `KROGER #115
 * CYPRESS TX` and `KROGER #122 KATY TX` differ only in the store number, so a rule whose
 * [substring] is `KROGER` matches both - the chain is stable, the store number is not.
 *
 * [substring] is stored UPPERCASE already (never lowercased or mixed-case) so
 * [com.kevin.legion.ledger.matchCategory] can do a single case-normalizing pass on the
 * description side only, rather than normalizing both sides on every match.
 *
 * A rule can come from two places, and this entity deliberately does not distinguish them with a
 * provenance column: a driver typing one in directly, or [com.kevin.legion.ledger.LedgerController.confirmCategoryGuess]
 * auto-writing one the moment a guess is confirmed (D18 - "the whole stability answer"). Both are
 * equally a rule from here on; the guess's own [LedgerTransaction.categoryPending] flag is what
 * carries "this specific row hasn't been confirmed yet", not this table.
 */
@Entity(tableName = "category_rules")
data class CategoryRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val substring: String,
    val createdAt: Long,
)
