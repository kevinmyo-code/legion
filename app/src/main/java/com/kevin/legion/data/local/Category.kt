package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry in the ledger's fixed category set - ticket 07 D14: "Fixed list, not freeform. A
 * budget needs 'groceries' to mean the same thing in March and April." [CarTask.category] is
 * freeform today; that precedent is deliberately NOT followed here for exactly that reason.
 *
 * Room-backed rather than a hardcoded enum so the set can be edited later without a schema
 * migration (D16 already commits [CategoryRule] to the same "editable, stored in Room" shape),
 * but seeded with a starter set at v6's migration - see [com.kevin.legion.data.local.MIGRATION_5_6].
 *
 * [isFoodCategory] (D15): "Food categories are shared between ledger and meals; everything else
 * is separate." This is what makes the deferred grocery-vs-meals cross-check (ticket 09) possible
 * later with no further migration - the flag exists now, on the first version of this table,
 * even though nothing reads it yet.
 *
 * There is deliberately no "Uncategorised" row here. Ticket 06 D11's uncategorised bucket is the
 * ABSENCE of a category ([LedgerTransaction.category] `== null`), never a category of its own -
 * see [com.kevin.legion.ledger.buildBudgetVsActual]'s doc comment for why that distinction is
 * load-bearing.
 */
@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isFoodCategory: Boolean,
)
