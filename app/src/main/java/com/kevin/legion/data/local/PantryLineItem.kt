package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One line item on a [PantryReceipt]. `receiptId` is a plain field, not a SQL
 * foreign key - matches this codebase's existing flat-reference convention
 * (e.g. [LedgerTransaction.accountId], [BuildEntry]'s vehicle reference).
 *
 * [caloriesKcal]/[proteinG]/[carbsG]/[fatG] are BEST-EFFORT ESTIMATES from the
 * LLM, never measured fact and never reconciled against anything (there is
 * nothing on a receipt to check them against, unlike [totalPriceCents], which
 * the reconciliation gate DOES verify). Any surface that reads these back -
 * tool descriptions, spoken responses - must say "estimated," not state them
 * as fact. This is CLAUDE.md §9.1's "anchored to falsifiable reality" thesis:
 * the money is anchored (verified against the receipt's own print); the
 * macros are not, and must never be presented as if they were.
 */
@Entity(tableName = "pantry_line_items")
data class PantryLineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val name: String,
    val quantity: Double = 1.0,
    val unitPriceCents: Long? = null,
    val totalPriceCents: Long,
    val caloriesKcal: Int? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val syncId: String = java.util.UUID.randomUUID().toString(),
)
