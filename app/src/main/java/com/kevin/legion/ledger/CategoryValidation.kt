package com.kevin.legion.ledger

/**
 * [validateNewCategoryName]'s outcome - a refusal always carries the reason IN WORDS (CLAUDE.md §4
 * rule 5's "never fail silently" posture, applied to input validation, not just money).
 */
sealed class NewCategoryValidation {
    /** [trimmed] is what should actually be inserted - never the caller's raw, unstripped input. */
    data class Valid(val trimmed: String) : NewCategoryValidation()
    data class Invalid(val reason: String) : NewCategoryValidation()
}

/**
 * The longest a category name may be - long enough for anything a driver would actually type
 * ("Coffee & Snacks" is 16), short enough that it never overruns a
 * [com.kevin.legion.ui.ledger.BudgetLineRow]'s single line.
 */
const val MAX_CATEGORY_NAME_LENGTH = 40

/**
 * Kevin adding a category through the ledger screen (2026-08-07) is a DIFFERENT actor at a
 * DIFFERENT boundary than `set_category`'s ticket 07 D14 "fixed list, not freeform" - that rule
 * stops the MODEL inventing a category at the voice-tool boundary, not stops Kevin growing the
 * stored list itself (see [com.kevin.legion.data.local.Category]'s doc comment: "Room-backed
 * rather than a hardcoded enum so the set can be edited later without a schema migration"). This
 * is the validation that boundary still deserves: reject blank, reject a case-insensitive
 * duplicate (`Pets` vs `pets` - SQLite's `UNIQUE INDEX` on `categories.name` alone is byte-wise,
 * not case-folding, so this check has to happen up here too, not just trust the DAO), trim, and
 * cap the length.
 *
 * [existingNames] is expected to already be every stored name
 * ([com.kevin.legion.data.local.CategoryDao.allNames]'s own return) - this function is pure and
 * does no I/O itself, so both [com.kevin.legion.ledger.LedgerController.addCategory] (the write
 * path) and [com.kevin.legion.ui.ledger.LedgerCategoryResolver] (the UI-side re-export, for the
 * Compose call site's own pattern) share the one check rather than drifting.
 */
fun validateNewCategoryName(raw: String, existingNames: List<String>): NewCategoryValidation {
    val trimmed = raw.trim()
    return when {
        trimmed.isEmpty() -> NewCategoryValidation.Invalid("Category name can't be blank.")
        trimmed.length > MAX_CATEGORY_NAME_LENGTH ->
            NewCategoryValidation.Invalid("Category name is too long (max $MAX_CATEGORY_NAME_LENGTH characters).")
        existingNames.any { it.equals(trimmed, ignoreCase = true) } ->
            NewCategoryValidation.Invalid("\"$trimmed\" already exists.")
        else -> NewCategoryValidation.Valid(trimmed)
    }
}
