package com.kevin.legion.data.local

/**
 * The single canonical starter-category list, Kevin 2026-08-07 (the fresh-install seeding bug):
 * before this existed there were two independent sources of "what categories exist" - [MIGRATION_5_6]'s
 * inline `starterCategories` list, which only ever ran for an install that upgraded THROUGH v5->v6,
 * and nothing at all for a fresh install, because [CarDatabase] had no `RoomDatabase.Callback`
 * and Room does not replay migrations against a freshly-created schema - it builds the schema
 * straight from the `@Entity` set. A stranger cloning the repo (CLAUDE.md §2's clone-and-run
 * requirement) got zero categories: `set_category` had a fixed list of nothing to validate against,
 * and categorisation had nothing to assign.
 *
 * [starter] is now read by BOTH paths that need it:
 *  - [CarDatabase]'s `RoomDatabase.Callback.onCreate`, for a fresh install (v12 schema straight
 *    from the entity set, this list seeded directly).
 *  - [MIGRATION_11_12], for Kevin's existing v11 install, which inserts only the row(s) not already
 *    present in [MIGRATION_5_6]'s original 15 - currently just `Pets`.
 *
 * [MIGRATION_5_6] itself is deliberately NOT rewritten to point at this list - a historical
 * migration must keep producing exactly what it always produced (CLAUDE.md §5 "additive
 * migrations only"), and changing its literal `starterCategories` would rewrite what an old
 * install receives on its way through v5->v6, silently changing a step in the migration chain
 * that `CarDatabaseMigration5To6Test` already pins.
 */
object CategorySeed {
    /**
     * name to isFoodCategory (D15), same shape [MIGRATION_5_6]'s inline list used. Order matches
     * [MIGRATION_5_6]'s original 15 exactly, with `Pets` appended last - never reordered, so a diff
     * against the old list stays a pure addition.
     */
    val starter: List<Pair<String, Boolean>> = listOf(
        "Groceries" to true, "Dining Out" to true, "Coffee & Snacks" to true,
        "Transport" to false, "Housing" to false, "Utilities" to false,
        "Subscriptions" to false, "Shopping" to false, "Health" to false,
        "Travel" to false, "Entertainment" to false, "Income" to false,
        "Fees" to false, "Insurance" to false, "Other" to false,
        "Pets" to false,
    )

    /**
     * The starter categories that are NOT spending (v71, Kevin 2026-09-29: "ignore zelle for
     * spending"), seeded with [Category.excludedFromSpend] on - money moving between the household's
     * own places. A seed default only: after seeding, the flag on the row is the definition, and it
     * syncs like every other category field.
     *
     * **A separate list from [starter], deliberately.** [MIGRATION_11_12] and [MIGRATION_16_17] both
     * iterate [starter], so appending here would change what those historical migrations insert -
     * which CLAUDE.md section 5 forbids. Read by the fresh-install callback and [MIGRATION_70_71] only.
     */
    val notSpending: Set<String> = setOf("Transfers")
}
