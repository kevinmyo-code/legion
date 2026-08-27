package com.kevin.legion.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2: adds `ledger_transactions` (the ledger aspect's bank-statement
 * ingestion). Verbatim from the generated schema JSON
 * (`app/schemas/com.kevin.legion.data.local.CarDatabase/2.json`), per the
 * additive-migration discipline this project intends to keep from here on -
 * see CarDatabase's doc comment.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ledger_transactions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sourceFile` TEXT NOT NULL, " +
                "`accountId` TEXT NOT NULL, " +
                "`currency` TEXT NOT NULL, " +
                "`txnDate` INTEGER NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`amountCents` INTEGER NOT NULL, " +
                "`balanceCents` INTEGER, " +
                "`lineRef` TEXT NOT NULL, " +
                "`ingestMethod` TEXT NOT NULL, " +
                "`syncId` TEXT NOT NULL)"
        )
    }
}

/**
 * v2 -> v3: adds `pantry_receipts` + `pantry_line_items` (the pantry aspect's
 * grocery-receipt ingestion). Verbatim from the generated schema JSON
 * (`app/schemas/com.kevin.legion.data.local.CarDatabase/3.json`).
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pantry_receipts` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`store` TEXT NOT NULL, " +
                "`purchaseDate` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL, " +
                "`totalCents` INTEGER NOT NULL, " +
                "`sourceImagePath` TEXT NOT NULL, " +
                "`syncId` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pantry_line_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`receiptId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`quantity` REAL NOT NULL, " +
                "`unitPriceCents` INTEGER, " +
                "`totalPriceCents` INTEGER NOT NULL, " +
                "`caloriesKcal` INTEGER, " +
                "`proteinG` REAL, " +
                "`carbsG` REAL, " +
                "`fatG` REAL, " +
                "`syncId` TEXT NOT NULL)"
        )
    }
}

/**
 * v3 -> v4: adds `ingested_files` (the ledger Drive-scan work-avoidance
 * record, ticket 03) plus a nullable `ledger_transactions.sourceFileId`.
 * Verbatim from the generated schema JSON
 * (`app/schemas/com.kevin.legion.data.local.CarDatabase/4.json`), per the
 * additive-migration discipline - see CarDatabase's doc comment.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ingested_files` (" +
                "`driveFileId` TEXT NOT NULL, " +
                "`treeUri` TEXT, " +
                "`displayName` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, " +
                "`lastModified` INTEGER NOT NULL, " +
                "`contentSha256` TEXT, " +
                "`state` TEXT NOT NULL, " +
                "`duplicateOfFileId` TEXT, " +
                "`quarantineReason` TEXT, " +
                "`transactionCount` INTEGER NOT NULL, " +
                "`firstSeenAt` INTEGER NOT NULL, " +
                "`lastAttemptAt` INTEGER NOT NULL, " +
                "`accountId` TEXT, " +
                "`minTxnDate` INTEGER, " +
                "`maxTxnDate` INTEGER, " +
                "`duplicatesSkipped` INTEGER NOT NULL, " +
                "`llmAttempted` INTEGER NOT NULL, " +
                "`llmPromptTokens` INTEGER, " +
                "`llmResponseTokens` INTEGER, " +
                "PRIMARY KEY(`driveFileId`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ingested_files_contentSha256` " +
                "ON `ingested_files` (`contentSha256`)"
        )
        db.execSQL("ALTER TABLE `ledger_transactions` ADD COLUMN `sourceFileId` TEXT")
    }
}

/**
 * v4 -> v5: adds `companion_profiles` (named, synced assistant identities,
 * Kevin 2026-08-02). Verbatim from the generated schema JSON
 * (`app/schemas/com.kevin.legion.data.local.CarDatabase/5.json`), per the
 * additive-migration discipline - see CarDatabase's doc comment and
 * [CompanionProfileEntity]'s.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `companion_profiles` (" +
                "`profileId` TEXT NOT NULL, " +
                "`assistantName` TEXT NOT NULL, " +
                "`persona` TEXT NOT NULL, " +
                "`traits` TEXT NOT NULL, " +
                "`voice` TEXT NOT NULL, " +
                "`voiceStyle` TEXT NOT NULL, " +
                "`voiceStyleTraits` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`profileId`))"
        )
    }
}

/**
 * v5 -> v6: adds `categories` + `category_rules` + `budget_targets` (ledger categorisation and
 * budget-versus-actual, `.scratch/legion-shape/issues/06-budget-versus-actual.md` and
 * `07-categorisation.md`) plus nullable `ledger_transactions.category`/`categoryPending`.
 * Verbatim from the generated schema JSON
 * (`app/schemas/com.kevin.legion.data.local.CarDatabase/6.json`), per the additive-migration
 * discipline - see CarDatabase's doc comment.
 *
 * `categories` is seeded here with a starter set (ticket 07 D14 - "fixed list, not freeform"),
 * verbatim `INSERT`s rather than a runtime seeding step, so a fresh install and an upgraded
 * install see the exact same category set with zero code path divergence between them.
 * [Category.isFoodCategory] marks the three grocery/dining rows per D15, for the deferred
 * grocery-vs-meals cross-check (ticket 09) this schema deliberately leaves room for.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`isFoodCategory` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `category_rules` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`substring` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budget_targets` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`currency` TEXT NOT NULL, " +
                "`amountCents` INTEGER NOT NULL, " +
                "`effectiveFromMonthEpoch` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_targets_category_currency_effectiveFromMonthEpoch` " +
                "ON `budget_targets` (`category`, `currency`, `effectiveFromMonthEpoch`)"
        )
        db.execSQL("ALTER TABLE `ledger_transactions` ADD COLUMN `category` TEXT")
        db.execSQL("ALTER TABLE `ledger_transactions` ADD COLUMN `categoryPending` INTEGER NOT NULL DEFAULT 0")

        // Starter set, D14/D15. Food rows (isFoodCategory=1) come first so the
        // ticket 09 cross-check has a stable, easy-to-audit block to read.
        val starterCategories = listOf(
            "Groceries" to 1, "Dining Out" to 1, "Coffee & Snacks" to 1,
            "Transport" to 0, "Housing" to 0, "Utilities" to 0,
            "Subscriptions" to 0, "Shopping" to 0, "Health" to 0,
            "Travel" to 0, "Entertainment" to 0, "Income" to 0,
            "Fees" to 0, "Insurance" to 0, "Other" to 0,
        )
        for ((name, isFood) in starterCategories) {
            db.execSQL(
                "INSERT INTO `categories` (`name`, `isFoodCategory`) VALUES (?, ?)",
                arrayOf<Any>(name, isFood)
            )
        }
    }
}

/**
 * v6 -> v7: adds the workouts and meals aspects (`.scratch/legion-shape/issues/08-workouts-domain.md`
 * D20-D24 and `09-meals-domain.md` D25-D28) - six new tables, no changes to any existing table.
 * Verbatim from the generated schema JSON
 * (`app/schemas/com.kevin.legion.data.local.CarDatabase/7.json`), per the additive-migration
 * discipline - see CarDatabase's doc comment and each new entity's own doc comment.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workout_plans` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionsPerWeek` INTEGER NOT NULL, " +
                "`effectiveFromWeekEpoch` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_plans_effectiveFromWeekEpoch` " +
                "ON `workout_plans` (`effectiveFromWeekEpoch`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workout_plan_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`exercise` TEXT NOT NULL, " +
                "`targetSetsPerWeek` INTEGER NOT NULL, " +
                "`effectiveFromWeekEpoch` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_plan_items_exercise_effectiveFromWeekEpoch` " +
                "ON `workout_plan_items` (`exercise`, `effectiveFromWeekEpoch`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workout_set_logs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`exercise` TEXT NOT NULL, " +
                "`sets` INTEGER NOT NULL, " +
                "`reps` INTEGER, " +
                "`weightValue` REAL, " +
                "`weightUnit` TEXT, " +
                "`loggedAt` INTEGER NOT NULL, " +
                "`trustTier` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bodyweight_logs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`weightValue` REAL NOT NULL, " +
                "`weightUnit` TEXT NOT NULL, " +
                "`loggedAt` INTEGER NOT NULL, " +
                "`trustTier` TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `meal_targets` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`caloriesKcal` INTEGER NOT NULL, " +
                "`proteinG` REAL NOT NULL, " +
                "`carbsG` REAL NOT NULL, " +
                "`fatG` REAL NOT NULL, " +
                "`effectiveFromDateEpoch` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_meal_targets_effectiveFromDateEpoch` " +
                "ON `meal_targets` (`effectiveFromDateEpoch`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `meal_logs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`caloriesKcal` INTEGER, " +
                "`proteinG` REAL, " +
                "`carbsG` REAL, " +
                "`fatG` REAL, " +
                "`loggedAt` INTEGER NOT NULL, " +
                "`sourceImagePath` TEXT, " +
                "`trustTier` TEXT NOT NULL)"
        )
    }
}

/**
 * v7 -> v8: adds nullable `ledger_transactions.pendingLoggedAt` (voice-logged pending
 * transactions - Kevin's bank shows an available balance that nets out still-processing card
 * activity no BofA export ever prints, so he logs them by voice instead). Verbatim from the
 * generated schema JSON (`app/schemas/com.kevin.legion.data.local.CarDatabase/8.json`), per the
 * additive-migration discipline - see CarDatabase's doc comment and
 * [LedgerTransaction.pendingLoggedAt]'s own doc comment for why this is a new column rather than
 * a new [IngestMethod] constant (no schema change either way - see CarDatabase's v5 note on
 * widening a TEXT-stored enum - but a `pendingLoggedAt` row IS a real new column, unlike that
 * case).
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `ledger_transactions` ADD COLUMN `pendingLoggedAt` INTEGER")
    }
}

/**
 * v8 -> v9: adds the sleep aspect (Kevin, 2026-08-07: "i want to be able to log sleep too"),
 * modelled directly on v7's workouts/meals tables - `sleep_targets` (copy-forward nightly target,
 * same shape as `meal_targets`) and `sleep_logs` (one row per logged night, REPORTED tier, no
 * reconciliation gate - see [SleepLog]'s doc comment for why). Verbatim from the generated schema
 * JSON (`app/schemas/com.kevin.legion.data.local.CarDatabase/9.json`), per the additive-migration
 * discipline - see CarDatabase's doc comment and each new entity's own doc comment.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sleep_targets` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`targetMinutes` INTEGER NOT NULL, " +
                "`effectiveFromDateEpoch` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sleep_targets_effectiveFromDateEpoch` " +
                "ON `sleep_targets` (`effectiveFromDateEpoch`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sleep_logs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sleepDate` INTEGER NOT NULL, " +
                "`durationMinutes` INTEGER NOT NULL, " +
                "`quality` INTEGER, " +
                "`notes` TEXT, " +
                "`loggedAt` INTEGER NOT NULL, " +
                "`trustTier` TEXT NOT NULL, " +
                "`syncId` TEXT NOT NULL)"
        )
    }
}

/**
 * v9 -> v10: adds `item_lists` + `list_items` + `list_item_skips` (the notes/lists/calendar
 * domain phase 1, `.scratch/notes-lists-calendar/issues/01-entity-model-and-cartask-migration.md`
 * and `04-recurrence-model.md`), and copies `car_tasks` into a list named "Car" and
 * `place_reminders` into a list named "Reminders". Verbatim from the generated schema JSON
 * (`app/schemas/com.kevin.legion.data.local.CarDatabase/10.json`), per the additive-migration
 * discipline - see CarDatabase's doc comment.
 *
 * **`car_tasks` and `place_reminders` are NOT dropped here**, on ticket 01's explicit instruction:
 * "a copy that silently mis-maps a column is recoverable while the source table is still there,
 * and unrecoverable the moment it is not." Both stay in place, unread by any new code, for one
 * more version; dropping them is a later, separate migration.
 *
 * `syncId`/`deleted`(where it exists)/`updatedAt`/`createdAt`/`done`/`doneAt` are copied verbatim
 * from `car_tasks` - losing a tombstone would let the next sync resurrect a deleted row from a
 * remote snapshot that never saw it disappear (CarTask's own doc comment). `place_reminders` has
 * no `deleted` or `doneAt` column to carry, so migrated reminder rows get `deleted = 0` and
 * `doneAt = NULL` - the same defaults a brand-new row would get. `sortOrder` is seeded from each
 * source row's own `id` (a stable, monotonic proxy for original insertion order - neither source
 * table had a real ordering column), never a SQLite window function, since the Android SQLite
 * version backing an arbitrary install is not guaranteed to support one.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `item_lists` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`tickable` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`lastUsedAt` INTEGER NOT NULL, " +
                "`archived` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`syncId` TEXT NOT NULL DEFAULT '', " +
                "`deleted` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `list_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`listId` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`done` INTEGER NOT NULL, " +
                "`doneAt` INTEGER, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`syncId` TEXT NOT NULL DEFAULT '', " +
                "`deleted` INTEGER NOT NULL DEFAULT 0, " +
                "`startsAt` INTEGER, " +
                "`endsAt` INTEGER, " +
                "`allDay` INTEGER NOT NULL, " +
                "`triggerPlaceLabel` TEXT, " +
                "`repeatKind` TEXT, " +
                "`repeatEvery` INTEGER, " +
                "`repeatDaysOfWeek` TEXT, " +
                "`repeatDay` INTEGER, " +
                "`repeatMonth` INTEGER, " +
                "`repeatEndKind` TEXT, " +
                "`repeatEndDate` INTEGER, " +
                "`repeatEndCount` INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_list_items_listId` ON `list_items` (`listId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_list_items_startsAt` ON `list_items` (`startsAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `list_item_skips` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`itemId` INTEGER NOT NULL, " +
                "`skippedDate` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`syncId` TEXT NOT NULL DEFAULT '', " +
                "`deleted` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_list_item_skips_itemId` ON `list_item_skips` (`itemId`)")

        // One list per absorbed source table, created before the copy so each row below can
        // target it by name.
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO `item_lists` (`name`, `tickable`, `sortOrder`, `lastUsedAt`, `archived`, " +
                "`createdAt`, `updatedAt`, `syncId`, `deleted`) VALUES " +
                "('Car', 1, 0, ?, 0, ?, ?, ?, 0)",
            arrayOf<Any>(now, now, now, java.util.UUID.randomUUID().toString())
        )
        db.execSQL(
            "INSERT INTO `item_lists` (`name`, `tickable`, `sortOrder`, `lastUsedAt`, `archived`, " +
                "`createdAt`, `updatedAt`, `syncId`, `deleted`) VALUES " +
                "('Reminders', 1, 0, ?, 0, ?, ?, ?, 0)",
            arrayOf<Any>(now, now, now, java.util.UUID.randomUUID().toString())
        )

        // car_tasks -> the "Car" list, every column that exists on both sides copied verbatim.
        db.execSQL(
            "INSERT INTO `list_items` (`listId`, `text`, `done`, `doneAt`, `sortOrder`, " +
                "`createdAt`, `updatedAt`, `syncId`, `deleted`, `startsAt`, `endsAt`, `allDay`, " +
                "`triggerPlaceLabel`, `repeatKind`, `repeatEvery`, `repeatDaysOfWeek`, `repeatDay`, " +
                "`repeatMonth`, `repeatEndKind`, `repeatEndDate`, `repeatEndCount`) " +
                "SELECT (SELECT `id` FROM `item_lists` WHERE `name` = 'Car'), " +
                "`text`, `done`, `doneAt`, `id`, `createdAt`, `updatedAt`, `syncId`, `deleted`, " +
                "NULL, NULL, 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL " +
                "FROM `car_tasks`"
        )

        // place_reminders -> the "Reminders" list, carrying placeLabel forward as triggerPlaceLabel.
        // No `deleted` or `doneAt` column exists on place_reminders, so migrated rows default to
        // deleted = 0 / doneAt = NULL - identical to what a brand-new row would get.
        db.execSQL(
            "INSERT INTO `list_items` (`listId`, `text`, `done`, `doneAt`, `sortOrder`, " +
                "`createdAt`, `updatedAt`, `syncId`, `deleted`, `startsAt`, `endsAt`, `allDay`, " +
                "`triggerPlaceLabel`, `repeatKind`, `repeatEvery`, `repeatDaysOfWeek`, `repeatDay`, " +
                "`repeatMonth`, `repeatEndKind`, `repeatEndDate`, `repeatEndCount`) " +
                "SELECT (SELECT `id` FROM `item_lists` WHERE `name` = 'Reminders'), " +
                "`text`, `done`, NULL, `id`, `createdAt`, `updatedAt`, `syncId`, 0, " +
                "NULL, NULL, 1, `placeLabel`, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL " +
                "FROM `place_reminders`"
        )
    }
}

/**
 * v10 -> v11: notes/lists/calendar phase 2a - local alarms and fired-reminder state
 * (`.scratch/notes-lists-calendar/issues/03-*`/`12-*`). Four nullable-or-defaulted columns on
 * `list_items`, all additive, no destructive fallback. See [ListItem]'s doc comment for what each
 * one means; `exact`/`exactDowngraded` default to `0` (matching this schema's existing
 * boolean-as-INTEGER-DEFAULT-'0' convention) and `missedAt`/`missedDismissedAt` are plain nullable
 * columns with no default, since NULL is already their correct "not missed" / "not dismissed"
 * value and `ALTER TABLE ... ADD COLUMN` with no `NOT NULL` needs none.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `list_items` ADD COLUMN `exact` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `list_items` ADD COLUMN `exactDowngraded` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `list_items` ADD COLUMN `missedAt` INTEGER")
        db.execSQL("ALTER TABLE `list_items` ADD COLUMN `missedDismissedAt` INTEGER")
    }
}

/**
 * v11 -> v12: no schema change at all - `categories` already exists, `CREATE TABLE`/`CREATE INDEX`
 * are untouched, so there is nothing to copy from a generated schema JSON diff here (`12.json`
 * differs from `11.json` only in `identityHash`/`version`, per Room's own convention for a
 * data-only migration - see [com.kevin.legion.data.local.Category]'s doc comment for precedent:
 * "widening an enum stored as TEXT is not a migration" needed zero schema change either).
 *
 * Data only: inserts `Pets` (Kevin 2026-08-07), the one row [CategorySeed.starter] carries that
 * [MIGRATION_5_6]'s original 15 do not. `INSERT OR IGNORE` against `index_categories_name`
 * (`categories.name` is `UNIQUE`) so this is a safe no-op if the row is somehow already there -
 * matching [MIGRATION_5_6]'s own precedent for a data-only insert inside a migration.
 *
 * This is the "existing install" half of the fresh-install seeding bug fix - see [CategorySeed]'s
 * doc comment for the full story and why [MIGRATION_5_6] itself is never touched.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Everything in CategorySeed.starter that MIGRATION_5_6's original 15 did not already
        // insert - today that is exactly ["Pets"], but this is written to stay correct if a later
        // pass appends more than one new starter category in the same version bump.
        //
        // THIS ASSUMPTION IS WRONG for an install created fresh at v11 or later before v12's
        // RoomDatabase.Callback existed (CarDatabase's doc comment) - such an install never ran
        // MIGRATION_5_6 at all, so "already seeded" is false for every name below, not just Pets.
        // That is exactly what happened to Kevin's real database (firstInstallTime 2026-08-07) and
        // produced 497 mis-categorised ledger_transactions rows. This migration is NOT rewritten to
        // fix it - CLAUDE.md §5 forbids changing what an old install receives, and
        // CarDatabaseMigration11To12Test already pins this exact behaviour. See MIGRATION_16_17 for
        // the fix, which makes no assumption about history at all.
        val alreadySeededByMigration56 = setOf(
            "Groceries", "Dining Out", "Coffee & Snacks", "Transport", "Housing", "Utilities",
            "Subscriptions", "Shopping", "Health", "Travel", "Entertainment", "Income",
            "Fees", "Insurance", "Other",
        )
        for ((name, isFood) in CategorySeed.starter) {
            if (name in alreadySeededByMigration56) continue
            db.execSQL(
                "INSERT OR IGNORE INTO `categories` (`name`, `isFoodCategory`) VALUES (?, ?)",
                arrayOf<Any>(name, if (isFood) 1 else 0)
            )
        }
    }
}

/**
 * **Dissolves every named list into one** (Kevin, 2026-08-11: "dissolve the car list. merge
 * everything into one list model").
 *
 * The multi-list structure cost more than it paid. Items landed in whichever list a voice command
 * happened to resolve - an F150 recall appointment filed under "Car" rather than anywhere the driver
 * would look - and a list a driver had to remember the name of is a list that hides things. One
 * stream, sorted by due date, is the whole model now (`ui/notes/InboxScreen.kt`).
 *
 * **No schema change at all.** Not one column is added, dropped, or retyped, so `app/schemas/`'s
 * identity hash for v13 is v12's, byte for byte. This is a pure DATA migration that needs a version
 * bump only because Room will not run it otherwise - the same "confirm it rather than assume it"
 * check [ListItem]'s `ingestMethod` note describes, pointed the other way.
 *
 * **Nothing is deleted and nothing moves lists silently.** Every `list_items` row keeps its text,
 * its `startsAt`, its repeat rule, its `syncId` and its tombstone - only `listId` is repointed. The
 * now-empty [ItemList] rows are SOFT-deleted (`deleted = 1`), never `DROP`ped or hard-`DELETE`d, so
 * a cross-device sync sees a tombstone rather than a row that vanished with no record - the exact
 * reasoning [CarTask]'s own doc comment gives for why `remove_car_task` never hard-deletes.
 *
 * Target list selection, in order:
 * 1. An existing non-deleted list already named "List" (a re-run, or one the driver made by hand).
 * 2. Otherwise the OLDEST surviving list, renamed to "List" - keeping a real row means keeping its
 *    `syncId`, so a device that already knows that list sees a rename rather than an unfamiliar
 *    list plus a stranded orphan.
 * 3. Otherwise (a fresh install with no lists at all) a new row.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()

        // 1/2/3 above, resolved to a single id.
        val targetId: Long = db.query(
            "SELECT `id` FROM `item_lists` WHERE `deleted` = 0 AND `name` = 'List' " +
                "ORDER BY `createdAt` ASC LIMIT 1"
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: db.query(
                "SELECT `id` FROM `item_lists` WHERE `deleted` = 0 ORDER BY `createdAt` ASC LIMIT 1"
            ).use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: run {
                db.execSQL(
                    "INSERT INTO `item_lists` " +
                        "(`name`, `tickable`, `sortOrder`, `lastUsedAt`, `archived`, `createdAt`, " +
                        "`updatedAt`, `syncId`, `deleted`) " +
                        "VALUES ('List', 1, 0, ?, 0, ?, ?, lower(hex(randomblob(16))), 0)",
                    arrayOf<Any>(now, now, now)
                )
                db.query("SELECT last_insert_rowid()").use { it.moveToFirst(); it.getLong(0) }
            }

        // The survivor is the one list, so it can be neither archived nor un-tickable: the
        // checklist-vs-note split is gone (every item ticks), and the single list a driver writes to
        // must never be hidden behind a SHOW ARCHIVED toggle that no longer exists.
        db.execSQL(
            "UPDATE `item_lists` SET `name` = 'List', `tickable` = 1, `archived` = 0, " +
                "`lastUsedAt` = ?, `updatedAt` = ? WHERE `id` = ?",
            arrayOf<Any>(now, now, targetId)
        )

        // Every item, from every list including archived and already-soft-deleted ones, comes over.
        // Filtering by `deleted` here would strand a tombstoned item on a list that is about to be
        // tombstoned itself, which is how a deleted row comes back to life on the next sync.
        db.execSQL(
            "UPDATE `list_items` SET `listId` = ?, `updatedAt` = ? WHERE `listId` <> ?",
            arrayOf<Any>(targetId, now, targetId)
        )

        // Soft-delete, never DROP - see the doc comment.
        db.execSQL(
            "UPDATE `item_lists` SET `deleted` = 1, `updatedAt` = ? WHERE `id` <> ?",
            arrayOf<Any>(now, targetId)
        )
    }
}

/**
 * Adds the grocery aspect: the current trip's items and the staples memory that outlives it
 * (Kevin, 2026-08-11 - "a grocery list, made once and torn down once grocery is complete").
 *
 * Purely additive - two `CREATE TABLE`s, nothing existing touched. SQL copied verbatim from the
 * generated `app/schemas/.../14.json`, per CLAUDE.md §7's lean-migration rule.
 *
 * See [GroceryItem] for why these are separate tables rather than more rows in `list_items`
 * (lifecycle, not category - the notes model was collapsed to one list two versions ago precisely
 * so that named buckets would stop hiding things, and this must not quietly undo that), and
 * [GroceryStaple] for why the only history kept is a frequency count.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `grocery_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, `done` INTEGER NOT NULL, `doneAt` INTEGER, `sortOrder` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`syncId` TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `grocery_staples` (`name` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                "`timesBought` INTEGER NOT NULL, `lastBoughtAt` INTEGER NOT NULL, " +
                "`syncId` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`name`))"
        )
    }
}

/**
 * Adds `vehicle_capabilities` - the per-vehicle Mode-01 PID profile (2026-08-12).
 *
 * Purely additive: one `CREATE TABLE`, nothing existing touched. SQL copied verbatim from the
 * generated `app/schemas/.../15.json`.
 *
 * See [VehicleCapability] for why this is persisted per car rather than held in memory on the
 * Bluetooth manager, and `vehicle/PidSpec.kt` for the registry it indexes into. Together they are
 * how the app reads a vehicle it has never seen without any per-model code.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vehicle_capabilities` (`vehicleId` TEXT NOT NULL, " +
                "`pid` INTEGER NOT NULL, `detectedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`vehicleId`, `pid`))"
        )
    }
}

/**
 * Adds `goals` and `advisor_advice` (`.scratch/aspect-advisors/issues/02-goal-store.md`, built by
 * ticket 13, 2026-08-13). Two additive tables, nothing existing touched. SQL copied verbatim from
 * the generated `app/schemas/com.kevin.legion.data.local.CarDatabase/16.json` after a kapt run.
 *
 * `goals.metricKey` is plain `TEXT` with no `CHECK` constraint - confirmed here rather than
 * assumed (CLAUDE.md §5): its `createSql` fragment below reads exactly `` `metricKey` TEXT ``,
 * the same shape [LedgerTransaction.ingestMethod] and [Goal.status]/[AdvisorAdvice.outcome] use,
 * so widening any of those value sets later is a Kotlin-only change, never a migration.
 *
 * See [Goal] and [AdvisorAdvice]'s own doc comments for the schema's reasoning.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`lineageId` INTEGER NOT NULL, `aspect` TEXT NOT NULL, `statement` TEXT NOT NULL, " +
                "`targetValue` REAL, `unit` TEXT, `metricKey` TEXT, `deadlineEpoch` INTEGER, " +
                "`status` TEXT NOT NULL, `supersedesId` INTEGER, `closedAt` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                "`syncId` TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_goals_lineageId` ON `goals` (`lineageId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_goals_aspect_status` ON `goals` (`aspect`, `status`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `advisor_advice` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`aspect` TEXT NOT NULL, `questionText` TEXT NOT NULL, `gist` TEXT NOT NULL, " +
                "`adviceText` TEXT NOT NULL, `proposalJson` TEXT, `outcome` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `resolvedAt` INTEGER, " +
                "`syncId` TEXT NOT NULL DEFAULT '')"
        )
    }
}

/**
 * v16 -> v17: closes the fresh-install-then-upgraded seeding hole [MIGRATION_11_12] left open
 * (Kevin 2026-08-13: an install created fresh at v11 - not upgraded through v5->v6 - has NO
 * `RoomDatabase.Callback` on that version, so [MIGRATION_11_12]'s `alreadySeededByMigration56`
 * assumption ("[MIGRATION_5_6] already inserted these 15, so skip them") is simply false for it.
 * Room builds a fresh schema straight from the `@Entity` set and never replays migrations, so a
 * database created at v11 or later, before the fresh-install callback existed (added at v12 - see
 * [CarDatabase]'s doc comment), got a `categories` table with **zero rows**. `CategoryAgent`
 * (`ledger/CategoryAgent.kt`) was then handed an empty - or in Kevin's case, single-row `Pets` -
 * fixed list and, per its own system instruction ("every merchant must get exactly one category"),
 * complied by assigning the only entry it had to everything. 497 of Kevin's 497
 * `ledger_transactions` rows came back `category = 'Pets'`, `categoryPending = 1`.
 *
 * **No schema change at all** - `categories` and `ledger_transactions` are both untouched
 * structurally, so `17.json`'s `identityHash` differs from `16.json`'s only in `version`, the same
 * shape [MIGRATION_11_12] and [MIGRATION_12_13] already used for a data-only bump.
 *
 * **Makes NO assumption about which migrations previously ran.** This is the one rule the v11->v12
 * fix broke and the reason this migration exists at all - it does not special-case Kevin's
 * particular history (single `Pets` row) or assume [MIGRATION_5_6]/[MIGRATION_11_12] ran in any
 * order or at all. It simply inserts every row of [CategorySeed.starter] with `INSERT OR IGNORE`
 * against `categories.name`'s `UNIQUE` index (same guard [MIGRATION_11_12] uses), which is correct
 * whether the table already has all sixteen, some subset, one, or zero - the only three shapes any
 * real install chain can produce.
 *
 * **[MIGRATION_11_12] is deliberately left as-is, not rewritten.** CLAUDE.md §5: a historical
 * migration must keep producing exactly what it always produced, and `CarDatabaseMigration11To12Test`
 * already pins its behaviour. Its hardcoded `alreadySeededByMigration56` set is the defect pattern
 * this migration exists to close the hole behind, not to erase - see the comment left at that set's
 * declaration site pointing here.
 *
 * **Repairs damaged guesses, but only when this migration actually inserted a missing category.**
 * If `categories` was already complete (sixteen rows, the common case for anyone who reaches v17
 * through the normal chain), the `INSERT OR IGNORE`s are all no-ops and the migration stops there -
 * not one `ledger_transactions` row is touched. When at least one category was actually missing
 * (Kevin's case, and anyone else who was created fresh between v11 and this fix), every row with
 * `categoryPending = 1` is reset to `category = NULL, categoryPending = 0`: a pending AI guess is
 * provisional by design (see [LedgerTransaction.categoryPending]'s own doc comment), so undoing one
 * loses nothing a driver ever confirmed, and a guess made against a one-item or empty list cannot be
 * trusted regardless of which category it happened to name. **Rows with `categoryPending = 0` are
 * never touched** - those are either uncategorised (`category IS NULL`) or a driver-confirmed fact,
 * and this migration has no business overwriting either.
 *
 * See [CategoryAgent.guessBatch]'s refusal to guess against a category list smaller than two
 * entries (added alongside this migration, CLAUDE.md §4 rule 6) for the other half of this fix -
 * this migration repairs the damage already on disk, that refusal stops it from happening again.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val before = db.query("SELECT COUNT(*) FROM `categories`")
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

        for ((name, isFood) in CategorySeed.starter) {
            db.execSQL(
                "INSERT OR IGNORE INTO `categories` (`name`, `isFoodCategory`) VALUES (?, ?)",
                arrayOf<Any>(name, if (isFood) 1 else 0)
            )
        }

        val after = db.query("SELECT COUNT(*) FROM `categories`")
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

        // Only touch ledger_transactions when a category was genuinely missing - a complete
        // categories table means nothing was ever guessed against a broken list, so there is
        // nothing to repair.
        if (after > before) {
            db.execSQL(
                "UPDATE `ledger_transactions` SET `category` = NULL, `categoryPending` = 0 " +
                    "WHERE `categoryPending` = 1"
            )
        }
    }
}

/**
 * v17 -> v18: repairs the `CHECKCARD` bug (Kevin 2026-08-13, real production data on his own
 * install). [com.kevin.legion.ledger.extractMerchantKey] used to split a description at the first
 * 3+-digit run, and on every Bank of America card line that run is the MMDD posting date printed
 * right after the transaction-type word - `CHECKCARD  0429 TMOBILE PREPD BELLEVUE WA` produced
 * `CHECKCARD`, not the merchant. A `category_rules` row with substring `CHECKCARD` existed on
 * Kevin's install and had silently confirmed 48 transactions - Walmart and Panda Express among
 * them, neither remotely a subscription - into "Subscriptions", the only category his 61 CHECKCARD/
 * PURCHASE-prefixed rows had ever reached. [com.kevin.legion.ledger.BANK_NOISE_PREFIXES] is the same
 * three-word list [com.kevin.legion.ledger.extractMerchantKey] now strips before ever deriving a
 * key, and [com.kevin.legion.ledger.LedgerController.setCategory] now refuses to write a new rule on
 * one of these words (see [com.kevin.legion.ledger.isBankNoiseKey]) - this migration is the third,
 * DATA-only leg: undoing the damage the old rule already did before either of those existed.
 *
 * **No schema change** - same `app/schemas/.../18.json` identity-hash discipline
 * [MIGRATION_16_17]'s doc comment already describes for a pure data repair; the version bump exists
 * only because Room requires one to run anything at all.
 *
 * **Scoped to exactly what each deleted rule could have caused, never a blanket reset.** For each
 * noise word in turn: read every `category_rules` row whose `substring` literally IS that word
 * (never a substring match on the word - "TMOBILE PURCHASES" is a real merchant name, not noise),
 * reset `ledger_transactions.category = NULL, categoryPending = 0` on exactly the rows that rule
 * could have written - matched by the SAME substring the rule itself matched by AND the rule's own
 * `category`, so a row a driver separately, correctly filed under the same category by hand (not
 * through this rule) is never touched, and a row this rule confirmed into a DIFFERENT category than
 * its current one (impossible today, since `setCategoryForMerchant` always writes the rule's own
 * category, but the WHERE clause costs nothing extra to state explicitly) is never falsely claimed
 * either - then delete the rule itself so it stops firing on the next import.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Kept as a literal Kotlin list, not read from BANK_NOISE_PREFIXES itself - a migration must
        // keep producing exactly what it always produced (CLAUDE.md §5) even if that list is ever
        // extended later, so its own copy is deliberate, matching MIGRATION_11_12's frozen-set
        // precedent that MIGRATION_16_17's own doc comment explains.
        val noisePrefixes = listOf("CHECKCARD", "CHKCARD", "PURCHASE")
        for (prefix in noisePrefixes) {
            val affectedCategories = mutableListOf<String>()
            db.query("SELECT `category` FROM `category_rules` WHERE `substring` = '$prefix'").use { cursor ->
                while (cursor.moveToNext()) affectedCategories.add(cursor.getString(0))
            }
            for (category in affectedCategories) {
                db.execSQL(
                    "UPDATE `ledger_transactions` SET `category` = NULL, `categoryPending` = 0 " +
                        "WHERE UPPER(`description`) LIKE '%' || ? || '%' AND `category` = ?",
                    arrayOf<Any>(prefix, category)
                )
            }
            db.execSQL("DELETE FROM `category_rules` WHERE `substring` = '$prefix'")
        }
    }
}

/**
 * v18 -> v19: repairs the transfer/category defect Kevin asked for directly
 * (`.scratch/car-probe-transfers/`, 2026-08-13). [com.kevin.legion.ledger.analyzeTransfers] existed
 * and correctly recognised `PAYMENT TO CRD`/`PAYMENT FROM CHK`-shaped rows as transfers, but was
 * only ever wired into [com.kevin.legion.ledger.LedgerController.budgetVsActual]/`categoryTransactions`
 * - never into the merchant-categorisation pipeline
 * ([com.kevin.legion.ledger.LedgerController.uncategorizedMerchants]/`CategoryAgent`/`set_category`)
 * - so a transfer row could still be guessed a category and still acquire a
 * [com.kevin.legion.data.local.CategoryRule], exactly the same shape of bug [MIGRATION_17_18] repaired
 * for `CHECKCARD` one version earlier: an extraction defect fixed going FORWARD (see
 * [com.kevin.legion.ledger.LedgerController.uncategorizedMerchants] and
 * [com.kevin.legion.ledger.isBankNoiseKey]'s 2026-08-13 extension) still needs a DATA repair for what
 * already landed.
 *
 * **No schema change at all** - same `app/schemas/.../19.json` identity-hash discipline
 * [MIGRATION_16_17]/[MIGRATION_17_18] already established for a pure data repair; the version bump
 * exists only because Room requires one to run anything at all.
 *
 * **"Transfer-shaped" here means [com.kevin.legion.ledger.TRANSFER_KEYWORDS] wording alone** - the
 * same case-insensitive substring vocabulary [com.kevin.legion.ledger.analyzeTransfers]'s pass 2 and
 * [com.kevin.legion.ledger.isBankNoiseKey]'s 2026-08-13 extension both already trust. A migration
 * cannot run [com.kevin.legion.ledger.analyzeTransfers]'s pass 1 (cross-row account/amount/date
 * pairing) against raw SQL, but every [com.kevin.legion.ledger.ExclusionReason.MATCHED_TRANSFER] row
 * pass 1 would have found is, BY CONSTRUCTION, also wording it printed for a human to read - Kevin's
 * real `PAYMENT TO CRD`/`PAYMENT FROM CHK` rows are exactly this shape - so the keyword list alone is
 * sufficient to find every row this repair needs to reach; it is intentionally the SAME weaker,
 * wording-only test pass 2 uses, applied once here as a floor rather than run as the full two-pass
 * analysis.
 *
 * **Two independent repairs, matching the two places a transfer could have left a mark:**
 * 1. Any `ledger_transactions` row whose `category` is set AND whose UPPERCASED `description`
 *    contains a [com.kevin.legion.ledger.TRANSFER_KEYWORDS] entry is reset to
 *    `category = NULL, categoryPending = 0` - the same "back to genuinely uncategorised" state
 *    [MIGRATION_17_18] resets a bad `CHECKCARD` guess to, for the same reason: whatever category it
 *    holds was assigned to a row that was never eligible for one.
 * 2. Any `category_rules` row whose `substring` (uppercased) itself contains a
 *    [com.kevin.legion.ledger.TRANSFER_KEYWORDS] entry is deleted outright, so it stops confirming
 *    every future transfer that matches it into the same wrong category - the rule-creation half of
 *    this bug, [com.kevin.legion.ledger.isBankNoiseKey]'s 2026-08-13 extension only stops a NEW one
 *    from being written; it does nothing about one already on disk.
 *
 * **Deliberately UNSCOPED to a specific rule's own category**, unlike [MIGRATION_17_18]'s
 * category-matched `UPDATE`. That migration had to be careful because `CHECKCARD` is bank
 * boilerplate that could plausibly have been re-typed by hand into an unrelated rule sharing the
 * same substring but a different category, and it needed to avoid touching a row that rule never
 * reached. A transfer-shaped description carries no such ambiguity to protect against: there is no
 * category a driver could correctly, intentionally assign to their own money moving between their
 * own accounts, so every currently-categorised transfer-shaped row is repaired regardless of which
 * category it happens to hold or which rule (if any) put it there.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Kept as a literal Kotlin list, not read from TRANSFER_KEYWORDS itself - a migration must
        // keep producing exactly what it always produced (CLAUDE.md §5) even if that list is ever
        // extended later, matching MIGRATION_17_18's own frozen-copy precedent for the same reason.
        val transferKeywords = listOf(
            "PAYMENT FROM", "PAYMENT TO", "ONLINE BANKING TRANSFER", "TRANSFER FROM", "TRANSFER TO", "CONF#",
        )
        for (keyword in transferKeywords) {
            db.execSQL(
                "UPDATE `ledger_transactions` SET `category` = NULL, `categoryPending` = 0 " +
                    "WHERE `category` IS NOT NULL AND UPPER(`description`) LIKE '%' || ? || '%'",
                arrayOf<Any>(keyword)
            )
            db.execSQL(
                "DELETE FROM `category_rules` WHERE UPPER(`substring`) LIKE '%' || ? || '%'",
                arrayOf<Any>(keyword)
            )
        }
    }
}

/**
 * v19 -> v20: the fleet-maintenance map's schema
 * (`.scratch/fleet-maintenance/map.md`, "THE MIGRATION", tickets 06/07/11/14, all resolved
 * 2026-08-15). Four changes, three additive and one the map's stated exception to CLAUDE.md §5's
 * additive-only rule. Verbatim from the generated schema JSON
 * (`app/schemas/com.kevin.legion.data.local.CarDatabase/20.json`), confirmed against it after a
 * kapt run rather than assumed - see CarDatabase's v20 doc comment for the full reasoning behind
 * each column and [MaintenanceItem.intervalSource]/[MaintenanceItem.deleted]/[Vehicle.engine]/
 * [ServiceRecord.costCents]'s own doc comments.
 *
 * **`maintenance_items.intervalSource` and `.deleted`** (tickets 06/07) are plain
 * `ALTER TABLE ... ADD COLUMN`, additive, no data touched.
 *
 * **`vehicles.engine`** (ticket 14) is the same shape, a different table, riding the same version
 * bump per that ticket's own instruction not to hold 06/07 for it.
 *
 * **`service_records.cost REAL` -> `.costCents INTEGER`** (ticket 11) cannot be an `ALTER TABLE`
 * at all - SQLite has no `ALTER COLUMN`, so this is the standard create-new-table / copy / drop /
 * rename sequence, done here rather than a separate migration because doing all four changes once
 * is cheaper than three additive bumps plus one non-additive one (ticket 06's own text). The new
 * table's shape otherwise matches the old one exactly: same `id INTEGER PRIMARY KEY AUTOINCREMENT`,
 * same `syncId TEXT NOT NULL DEFAULT ''`. The copy selects a literal `NULL` for `costCents` on
 * every row, never `cost * 100` - **`cost` is provably empty** (ticket 11 verified
 * `SELECT COUNT(*) FROM service_records WHERE cost IS NOT NULL` = 0 against a copy of Kevin's real
 * database before this was written, 0 of 2 rows), so there is nothing to convert and a conversion
 * expression would be dead code implying data that was never there. `sqlite_sequence` is not
 * touched - Room's own generated migrations for a REPLACE-style table rebuild never carry the old
 * autoincrement counter forward either, and this table's PK has no cross-table foreign-key
 * dependents relying on a specific next-`id` value.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // maintenance_items: two additive columns (tickets 06/07).
        db.execSQL(
            "ALTER TABLE `maintenance_items` ADD COLUMN `intervalSource` TEXT NOT NULL DEFAULT 'SEEDED'"
        )
        db.execSQL(
            "ALTER TABLE `maintenance_items` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0"
        )

        // vehicles: one additive column (ticket 14).
        db.execSQL(
            "ALTER TABLE `vehicles` ADD COLUMN `engine` TEXT NOT NULL DEFAULT ''"
        )

        // service_records: cost REAL -> costCents INTEGER (ticket 11). Not additive - SQLite
        // cannot retype a column in place, so this is create/copy/drop/rename. The column being
        // copied is provably empty (verified against Kevin's real database before writing this),
        // so every row's costCents comes over as a literal NULL, never cost * 100.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `service_records_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`vehicleId` TEXT NOT NULL, " +
                "`serviceName` TEXT NOT NULL, " +
                "`mileage` INTEGER NOT NULL, " +
                "`date` INTEGER NOT NULL, " +
                "`costCents` INTEGER, " +
                "`syncId` TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "INSERT INTO `service_records_new` " +
                "(`id`, `vehicleId`, `serviceName`, `mileage`, `date`, `costCents`, `syncId`) " +
                "SELECT `id`, `vehicleId`, `serviceName`, `mileage`, `date`, NULL, `syncId` " +
                "FROM `service_records`"
        )
        db.execSQL("DROP TABLE `service_records`")
        db.execSQL("ALTER TABLE `service_records_new` RENAME TO `service_records`")
    }
}

/**
 * v20 -> v21: adds `service_records.deleted INTEGER NOT NULL DEFAULT 0` - the soft-delete tombstone
 * ticket 11 §2 asks for (`.scratch/fleet-maintenance/issues/11-service-history-cost-and-fleet-spend.md`,
 * resolved 2026-08-15). Purely additive, one `ALTER TABLE ... ADD COLUMN`, verbatim from the
 * generated `app/schemas/com.kevin.legion.data.local.CarDatabase/21.json` after a kapt run.
 *
 * **This tombstone is LOCAL ONLY and cannot propagate to another device** - `service_records` syncs
 * `Mode.UNION` on the portable `syncId` (`sync/SyncEngine.kt:175`), and UNION never updates an
 * existing local row, so setting `deleted = 1` here never reaches a device that already has its own
 * copy of the row. See [ServiceRecord.deleted]'s own doc comment for the full reasoning - this is
 * the deliberate exception to the tombstone pattern [MIGRATION_9_10]/[MIGRATION_19_20]'s
 * `maintenance_items.deleted` column already uses successfully, because that column's table syncs
 * LWW and this one does not.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `service_records` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v21 -> v22: adds `code_clear_events` - fleet's first WRITE to the car
 * (`.scratch/hands-and-senses/issues/01-clear-dtc.md`, resolved 2026-08-16). One additive
 * `CREATE TABLE`, nothing existing touched. SQL copied verbatim from the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/22.json` after a kapt run, per the
 * additive-migration discipline - see CarDatabase's v22 doc comment and [CodeClearEvent]'s own.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `code_clear_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`vehicleId` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`mileage` INTEGER, " +
                "`codesBeforeJson` TEXT NOT NULL, " +
                "`freezeFrameJson` TEXT NOT NULL, " +
                "`codesAfterJson` TEXT NOT NULL, " +
                "`outcome` TEXT NOT NULL, " +
                "`ackRaw` TEXT NOT NULL, " +
                "`syncId` TEXT NOT NULL DEFAULT '')"
        )
    }
}

/**
 * v22 -> v23: adds `drives` - a real drive-boundary object
 * (`.scratch/drive-ui/issues/05-trip-content.md` Q14, `09-mpg-scale-bug.md`'s "bigger finding").
 * One additive `CREATE TABLE`, nothing existing touched. SQL copied verbatim from the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/23.json` after a kapt run, per the
 * additive-migration discipline - see CarDatabase's v23 doc comment and [Drive]'s own.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `drives` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`vehicleId` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER NOT NULL, " +
                "`miles` REAL NOT NULL, " +
                "`gallons` REAL, " +
                "`endReason` TEXT NOT NULL, " +
                "`syncId` TEXT NOT NULL DEFAULT '')"
        )
    }
}

/**
 * v23 -> v24: closes the `categoryPending` default drift, ticket 13
 * (`.scratch/ledger-drive-ingestion/issues/13-categorypending-default-drift.md`).
 * [LedgerTransaction.categoryPending] gained `@ColumnInfo(defaultValue = "0")` to match
 * [MIGRATION_5_6]'s `ALTER TABLE ledger_transactions ADD COLUMN categoryPending INTEGER NOT NULL
 * DEFAULT 0`, which every device that has ever run that migration (v6 onward - i.e. every real
 * device, Kevin's included) already has physically on disk.
 *
 * **The body below is deliberately empty.** SQLite has no `ALTER COLUMN`, so in general a
 * `defaultValue` change would need the create-new-table/copy/drop/rename dance - but that dance
 * exists to make the ON-DISK DDL match a NEW expectation. Here the on-disk DDL for every migrated
 * device is ALREADY `categoryPending INTEGER NOT NULL DEFAULT 0` (verbatim what [MIGRATION_5_6]
 * wrote), which is exactly the DDL the corrected entity now expects too - there is nothing to
 * change on a migrated database, only on a fresh one, and a fresh install builds its schema
 * straight from the `@Entity` annotations, never by replaying migrations (see [MIGRATION_16_17]'s
 * doc comment for the same "fresh install never replays migrations" fact). Confirmed, not assumed:
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/24.json`'s `ledger_transactions.createSql`
 * differs from `23.json`'s ONLY by the added `DEFAULT 0` clause on `categoryPending`, and the two
 * files' `identityHash` values differ - that hash difference is exactly why Room requires a version
 * bump to run anything at all here, the same shape [MIGRATION_16_17]'s and [MIGRATION_17_18]'s own
 * doc comments already describe for a pure identity-hash bump with no real DDL change on a migrated
 * device. `MigrationTestHelper.runMigrationsAndValidate(dbName, 24, true, MIGRATION_23_24)` in
 * [CarDatabaseMigration23To24Test] validates the post-migration `PRAGMA table_info` against
 * `24.json` regardless, which is exactly what proves this empty body is sufficient rather than
 * merely convenient.
 *
 * **Do not "fix" this by touching [MIGRATION_5_6].** CLAUDE.md §5 and this file's own precedent
 * (every migration from [MIGRATION_16_17] on) are explicit: a shipped migration must keep producing
 * exactly what it always produced. The entity was what drifted; the migration was correct the whole
 * time.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Intentionally empty - see this migration's own doc comment above.
    }
}

/**
 * v24 -> v25: one `CREATE INDEX` on `obd_samples(vehicleId, pid, timestamp)` - Kevin's device,
 * 2026-08-16 (`app/schemas/com.kevin.legion.data.local.CarDatabase/25.json` after a kapt run, per
 * the additive-migration discipline this project has kept from v1). `obd_samples` had 18,694 rows
 * and zero indexes: `EXPLAIN QUERY PLAN` on [OdbSampleDao.getRange]'s shape (`WHERE vehicleId=? AND
 * pid=? AND timestamp BETWEEN ? AND ? ORDER BY timestamp`) returned `SCAN obd_samples` plus `USE
 * TEMP B-TREE FOR ORDER BY` - a full table scan and a temporary sort on every call, and the FAULTS
 * drilldown (`ui/fleet/FleetDrilldowns.kt`) calls that shape TWICE per visible code event (speed +
 * rpm), so 45 code events meant 90 full scans to draw one screen.
 *
 * The column order is not arbitrary: it matches [OdbSampleDao.getRange],
 * [OdbSampleDao.getRangeNewestFirst], [OdbSampleDao.getLatest], and [OdbSampleDao.summarize]'s
 * shared `WHERE vehicleId=? AND pid=? AND timestamp ...` shape exactly, so SQLite can use the index
 * for both the filter and the `ORDER BY timestamp` without a separate sort step. See [OdbSample]'s
 * own doc comment for the full accounting of which other `OdbSampleDao` queries this index serves
 * only partially ([OdbSampleDao.lastSampleMs]/[OdbSampleDao.firstSampleMs]/
 * [OdbSampleDao.recentTimestamps]) and why a second index was judged not worth the write cost on
 * this table specifically.
 *
 * Purely additive - one `CREATE INDEX IF NOT EXISTS`, no existing column, table, or row touched.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_obd_samples_vehicleId_pid_timestamp` " +
                "ON `obd_samples` (`vehicleId`, `pid`, `timestamp`)"
        )
    }
}

/**
 * v25 -> v26: adds `music_play_history` (LEGION's own observed-listening log, ticket 05
 * `.scratch/drive-test-2026-08-18/issues/05-reading-kevins-spotify-library.md`). One additive
 * `CREATE TABLE`, nothing existing touched. SQL copied verbatim from the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/26.json` after a kapt run, per the
 * additive-migration discipline - see [CarDatabase]'s v26 doc comment and
 * [MusicPlayHistoryEntry]'s own.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `music_play_history` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`artist` TEXT NOT NULL, " +
                "`album` TEXT NOT NULL, " +
                "`spotifyUri` TEXT, " +
                "`startedAt` INTEGER NOT NULL, " +
                "`startedByLegion` INTEGER NOT NULL DEFAULT 0)"
        )
    }
}

/**
 * v27: `memory_audit` (2026-08-20). Kevin: "leave an audit trail for us to check."
 *
 * Purely additive - one new table, nothing else touched. SQL copied verbatim from the generated
 * schema, per CLAUDE.md sec 5.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_audit` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`event` TEXT NOT NULL, " +
                "`store` TEXT NOT NULL, " +
                "`detail` TEXT NOT NULL, " +
                "`refId` INTEGER NOT NULL DEFAULT 0, " +
                "`vehicleId` TEXT NOT NULL DEFAULT '', " +
                "`at` INTEGER NOT NULL)"
        )
    }
}

/**
 * Proactive mode's two tables (`.scratch/proactive-mode/`, tickets 02 and 04, 2026-08-21).
 *
 * `proactive_settings` is the master switch plus the five category switches, key/value so adding a
 * sixth category is a row rather than a migration. It is in Room rather than SharedPreferences
 * because two phones disagreeing about whether the assistant may speak is a real failure - see
 * [com.kevin.legion.data.local.ProactiveSetting]'s own doc for why that is eligibility to sync
 * rather than syncing.
 *
 * `proactive_raises` is what fired, when, why, and whether it was brushed off. Every raise before
 * this kept its own dedup state in a field the process owned, against a `START_STICKY` service - so
 * "never nag twice" was impossible rather than merely weak.
 *
 * **No seeding here.** [com.kevin.legion.service.ProactiveSettings] seeds on first read, because the
 * seed depends on the existing SharedPreferences `muted` value and a migration cannot read
 * SharedPreferences. An install with `muted=false` must come out with Safety, Timing and Fleet on,
 * so its behaviour does not change under it (ticket 04 call 3); a fresh install comes out quiet.
 *
 * **`declined` and `delivery` carry NO SQL default here, and that is not an oversight.** They have
 * Kotlin constructor defaults, which Room does NOT turn into column defaults - the generated
 * `createSql` in `app/schemas/.../28.json` has none, so writing `DEFAULT 0` here (as the first cut
 * of this migration did) makes the identity hash disagree and fails validation on upgrade. The
 * tables are new, so a default would buy nothing anyway. **Copy the generated SQL; do not improve
 * it.** Caught by diffing this against `createSql` rather than by running a migration test, which
 * is the cheaper of the two checks and the one CLAUDE.md §5 is asking for.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `proactive_settings` (" +
                "`key` TEXT NOT NULL, " +
                "`enabled` INTEGER NOT NULL, " +
                "PRIMARY KEY(`key`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `proactive_raises` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`ruleId` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`reason` TEXT NOT NULL, " +
                "`spokenAt` INTEGER NOT NULL, " +
                "`declined` INTEGER NOT NULL, " +
                "`delivery` TEXT NOT NULL)"
        )
    }
}

/**
 * Sitrep's two tables (`.scratch/hands-and-senses/issues/22-build-the-sitrep.md`, 2026-08-21).
 *
 * `sitrep_modules` is the per-module switch (CALENDAR/WEATHER/FLEET/NEWS), key/value shaped
 * identically to `proactive_settings` above - see [SitrepModuleSetting]'s own doc for why.
 *
 * `sitrep_schedule` is the one-row schedule time plus the newsletter sender list - see
 * [SitrepSchedule]'s own doc for why it is a sibling table rather than columns on the module
 * rows.
 *
 * SQL below is copied VERBATIM from the generated `app/schemas/.../29.json`'s own `createSql` for
 * both tables (confirmed by a real `compileDebugKotlin -Pnokey` run, not hand-derived) - same
 * discipline [MIGRATION_27_28] above documents, and it held on the first attempt here because
 * neither new entity gives Room anything to turn into a column `DEFAULT` (no Kotlin constructor
 * default on `SitrepModuleSetting.enabled`, and `SitrepSchedule.hour`/`minute`/`senders` are all
 * required constructor params with no default either - only `SitrepSchedule.id` has one, and it is
 * the `@PrimaryKey`, which Room never defaults in SQL regardless).
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sitrep_modules` (" +
                "`key` TEXT NOT NULL, " +
                "`enabled` INTEGER NOT NULL, " +
                "PRIMARY KEY(`key`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sitrep_schedule` (" +
                "`id` INTEGER NOT NULL, " +
                "`hour` INTEGER NOT NULL, " +
                "`minute` INTEGER NOT NULL, " +
                "`senders` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/**
 * v29 -> v30: `conversation_audit` (ticket 23, hands-and-senses map). One additive `CREATE TABLE`,
 * nothing existing touched - see [com.kevin.legion.data.local.ConversationAudit]'s own doc for the
 * schema's shape and why it is a sibling of [MemoryAudit] rather than an extension of it.
 *
 * SQL below is copied VERBATIM from the generated `app/schemas/.../30.json`'s own `createSql`
 * (confirmed by a real `compileDebugKotlin -Pnokey` run, not hand-derived) - same discipline
 * [MIGRATION_28_29] documents.
 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `conversation_audit` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`turnSeq` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`toolName` TEXT NOT NULL DEFAULT '', " +
                "`args` TEXT NOT NULL DEFAULT '', " +
                "`content` TEXT NOT NULL, " +
                "`redacted` INTEGER NOT NULL DEFAULT 0, " +
                "`vehicleId` TEXT NOT NULL DEFAULT '', " +
                "`at` INTEGER NOT NULL)"
        )
    }
}

/**
 * v30 -> v31: `wellbeing_digest_schedule` (goal-plans ticket 05,
 * `.scratch/goal-plans/issues/05-wellbeing-digest.md` - the Wellbeing switch's first content). One
 * additive `CREATE TABLE`, nothing existing touched - see
 * [com.kevin.legion.data.local.WellbeingDigestSchedule]'s own doc for the schema's shape and why it
 * is a sibling of [SitrepSchedule] rather than columns added to it.
 *
 * SQL below is copied VERBATIM from the generated `app/schemas/.../31.json`'s own `createSql`
 * (confirmed by a real `compileDebugKotlin -Pnokey` run, not hand-derived) - same discipline
 * [MIGRATION_29_30] documents.
 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `wellbeing_digest_schedule` (" +
                "`id` INTEGER NOT NULL, " +
                "`hour` INTEGER NOT NULL, " +
                "`minute` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/**
 * v31 -> v32: two additive nullable columns (goal-plans ticket 08). `workout_plan_items` gets
 * `repsPerSet` (see [WorkoutPlanItem.repsPerSet]) and `list_items` gets `loggedAt` (see
 * [ListItem.loggedAt]) - both bare `ALTER TABLE ... ADD COLUMN`, nothing existing touched, nothing
 * backfilled.
 *
 * SQL below is copied VERBATIM from the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/32.json`'s own `createSql` (confirmed by a
 * real `compileDebugKotlin -Pnokey` run, not hand-derived) - same discipline [MIGRATION_29_30]
 * documents.
 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workout_plan_items` ADD COLUMN `repsPerSet` INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE `list_items` ADD COLUMN `loggedAt` INTEGER DEFAULT NULL")
    }
}

/**
 * v32 -> v33: one additive nullable column (goal-plans ticket 09, "a ticked workout is one act,
 * not two rows"). `workout_set_logs` gets `sourceListItemId` (see
 * [WorkoutSetLog.sourceListItemId]) - the swept log's link back to the [ListItem] that produced
 * it, so an untick can find and delete that log instead of leaving a phantom set behind forever.
 * A bare `ALTER TABLE ... ADD COLUMN`, nothing existing touched, nothing removed.
 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `workout_set_logs` ADD COLUMN `sourceListItemId` INTEGER DEFAULT NULL")
    }
}

/**
 * v33 -> v34: the aspect engine core (`.scratch/aspect-engine/issues/16-build-engine-core.md`).
 * Five additive `CREATE TABLE`s, nothing existing touched - see [CarDatabase]'s v34 doc comment for
 * what each table is and [com.kevin.legion.engine.RecordStore] for the write door in front of
 * `records`. SQL below is copied VERBATIM from the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/34.json`'s own `createSql` (confirmed by a
 * real `compileDebugKotlin -Pnokey` run, not hand-derived) - same discipline [MIGRATION_29_30]
 * documents.
 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `aspects` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`icon` TEXT NOT NULL, " +
                "`color` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, " +
                "`archived` INTEGER NOT NULL, " +
                "`archivedAt` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `record_types` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`aspectId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`primaryAmountFieldId` INTEGER, " +
                "`primaryDueDateFieldId` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_record_types_aspectId` ON `record_types` (`aspectId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `field_defs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recordTypeId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`required` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, " +
                "`config` TEXT, " +
                "`ownerPluginId` TEXT, " +
                "`locked` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_field_defs_recordTypeId` ON `field_defs` (`recordTypeId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `records` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recordTypeId` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`dueAt` INTEGER, " +
                "`amountCents` INTEGER, " +
                "`searchText` TEXT NOT NULL, " +
                "`provenance` TEXT NOT NULL, " +
                "`payload` TEXT NOT NULL, " +
                "`deletedAt` INTEGER)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_records_recordTypeId` ON `records` (`recordTypeId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_records_dueAt` ON `records` (`dueAt`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_records_deletedAt` ON `records` (`deletedAt`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `widget_instances` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, " +
                "`aspectId` INTEGER, " +
                "`recordTypeId` INTEGER, " +
                "`widgetType` TEXT NOT NULL, " +
                "`config` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_widget_instances_deviceId` ON `widget_instances` (`deviceId`)"
        )
    }
}

/**
 * v35 (aspect-engine ticket 18): the [WidgetInstance] grid-geometry columns - see that entity's own
 * v35 doc comment for why these are real columns rather than folded into its already-existing
 * `config` blob. Four bare `ALTER TABLE ... ADD COLUMN`s, each with the literal default the entity's
 * own Kotlin default already promises (`gridRow`/`gridCol` at 0, `rowSpan`/`colSpan` at 1) - SQLite
 * requires a `DEFAULT` on an `ADD COLUMN` against a `NOT NULL` column so any pre-existing row (there
 * are none yet; this table has no real caller before this ticket) has a legal value to backfill.
 * Nothing else about `widget_instances` changes, and no other table is touched.
 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `widget_instances` ADD COLUMN `gridRow` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `widget_instances` ADD COLUMN `gridCol` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `widget_instances` ADD COLUMN `rowSpan` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `widget_instances` ADD COLUMN `colSpan` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * v35 -> v36: `muted_reminders` (aspect-engine ticket 19, the Dates aspect build) - see
 * [MutedReminder]'s own doc comment for why a reminder mute is its own tiny table rather than a
 * column on `records`. `createSql` below is PASTED VERBATIM from the kapt-generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/36.json`, not hand-written (CLAUDE.md
 * sec 5's "copy generated SQL verbatim") - Room's own generator writes the primary key as a
 * trailing `PRIMARY KEY(recordId)` constraint rather than an inline `INTEGER PRIMARY KEY`
 * column modifier, which is functionally equivalent SQLite but not textually the same, and this
 * migration must match what Room's own identity-hash check expects byte for byte.
 */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `muted_reminders` (`recordId` INTEGER NOT NULL, `mutedAt` INTEGER NOT NULL, PRIMARY KEY(`recordId`))"
        )
    }
}

/**
 * v36 -> v37: `records.guid`, the cross-device identity column - senior review of aspect-engine
 * ticket 20 (mirror/sync), MUST-FIX 1. See [EngineRecord]'s own doc comment for the defect this
 * fixes (a per-database `AUTOINCREMENT` id was being matched across two independent phones).
 *
 * Three steps, and they must run in this order:
 * 1. `ALTER TABLE records ADD COLUMN guid TEXT NOT NULL DEFAULT ''` - matches
 *    `@ColumnInfo(defaultValue = "''")` on [EngineRecord.guid] exactly (same
 *    `TEXT NOT NULL DEFAULT ''` shape as `vehicles.engine`'s own precedent migration above), so
 *    Room's post-migration schema validation sees the same default it expects at the SQL level.
 *    SQLite requires a constant `DEFAULT` for an `ADD COLUMN` against a `NOT NULL` column - it
 *    cannot itself express "a distinct random value per existing row" in the `ALTER TABLE`
 *    statement, which is exactly why step 2 exists as a separate pass.
 * 2. **Backfill**: `UPDATE records SET guid = <uuid-v4-shaped expression> WHERE guid = ''` -
 *    every pre-existing row gets a REAL, DISTINCT identity, never left at the placeholder `''`
 *    default (the review's explicit requirement: "no row is ever guid-less"). SQLite evaluates a
 *    `SET` expression containing `random()`/`randomblob()` freshly for EACH row an `UPDATE`
 *    touches (it is not a single value computed once for the whole statement), which is what
 *    makes a plain correlated `UPDATE` sufficient to mint one distinct value per row without a
 *    loop. The expression is not a certified RFC 4122 UUID (no version/variant bit registry
 *    lookup, just the same shape) - it only needs to be effectively-unique within this table,
 *    which 122 bits of `randomblob` easily is.
 * 3. `CREATE UNIQUE INDEX` on `guid` - matches [EngineRecord]'s `Index(value = ["guid"], unique =
 *    true)`, and doubles as [com.kevin.legion.data.local.EngineRecordDao.getByGuid]'s lookup index.
 *
 * No other table is touched, and this is the only schema change at this version - `createSql`
 * shape (the `ADD COLUMN`/index text) confirmed against `vehicles.engine`'s and
 * `advisor_advice.syncId`'s own existing `TEXT NOT NULL DEFAULT ''` precedent in this same file,
 * per CLAUDE.md sec 5's "copy generated SQL verbatim" discipline - the exact generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/37.json` is committed alongside this file
 * after a real `compileDebugKotlin -Pnokey` run, and [CarDatabaseMigration36To37Test] is the
 * instrumented (compiled-not-run, see that test's own doc comment) confirmation.
 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `records` ADD COLUMN `guid` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "UPDATE records SET guid = (" +
                "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
                "substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || " +
                "lower(hex(randomblob(6)))" +
                ") WHERE guid = ''"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_records_guid` ON `records` (`guid`)")
    }
}

/**
 * v37 -> v38: `events_replica` + `event_skips_replica` (backend-erp Phase 4, aspect 4 of 5 -
 * Notes+Dates merged - see [EventReplica]'s own doc comment for the shape). Two additive
 * `CREATE TABLE`s, nothing existing touched. `createSql`/index text below is PASTED VERBATIM from
 * the kapt-generated `app/schemas/com.kevin.legion.data.local.CarDatabase/38.json` after a real
 * `compileDebugKotlin -Pnokey` run, per CLAUDE.md sec 5's "copy generated SQL verbatim" discipline -
 * not hand-written, same posture as every migration above this one in the file.
 */
val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `events_replica` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serverId` TEXT NOT NULL, `title` TEXT NOT NULL, `startsAt` INTEGER NOT NULL, `endsAt` INTEGER, `allDay` INTEGER NOT NULL, `location` TEXT, `notes` TEXT, `source` TEXT NOT NULL, `googleEventId` TEXT, `done` INTEGER NOT NULL, `doneAt` INTEGER, `sortOrder` INTEGER, `triggerPlaceLabel` TEXT, `repeatKind` TEXT, `repeatEvery` INTEGER, `repeatDaysOfWeek` TEXT, `repeatDay` INTEGER, `repeatMonth` INTEGER, `repeatEndKind` TEXT, `repeatEndDate` INTEGER, `repeatEndCount` INTEGER, `exact` INTEGER NOT NULL, `exactDowngraded` INTEGER NOT NULL, `missedAt` INTEGER, `missedDismissedAt` INTEGER, `loggedAt` INTEGER, `updatedAtMs` INTEGER NOT NULL, `deleted` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_events_replica_serverId` ON `events_replica` (`serverId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `event_skips_replica` (`eventServerId` TEXT NOT NULL, `skipDateEpochMs` INTEGER NOT NULL, PRIMARY KEY(`eventServerId`, `skipDateEpochMs`))"
        )
    }
}

/**
 * v38 -> v39: `pantry_receipts` gains `provenance` and `unaccountedCents` (CLAUDE.md section 4
 * rule 7's 2026-08-26 amendment, ticket 08 - `.scratch/backend-erp/issues/
 * 08-receipts-whose-anchors-were-never-stored.md`). Two additive `ALTER TABLE ADD COLUMN`s,
 * nothing existing touched: `provenance` gets a `NOT NULL DEFAULT 'LLM_RECONCILED'` so every
 * pre-existing row reads as the healthy value it always was, and `unaccountedCents` stays
 * nullable with no default, matching [PantryReceipt]'s own Kotlin default of `null`. SQL below is
 * PASTED VERBATIM from the kapt-generated `app/schemas/com.kevin.legion.data.local.CarDatabase/
 * 39.json` after a real `compileDebugKotlin -Pnokey` run, per CLAUDE.md sec 5's "copy generated
 * SQL verbatim" discipline - not hand-written, same posture as every migration above this one.
 */
val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `pantry_receipts` ADD COLUMN `provenance` TEXT NOT NULL DEFAULT 'LLM_RECONCILED'"
        )
        db.execSQL(
            "ALTER TABLE `pantry_receipts` ADD COLUMN `unaccountedCents` INTEGER DEFAULT NULL"
        )
    }
}

/**
 * v39 -> v40: `events_replica.startsAt` widens from `INTEGER NOT NULL` to nullable `INTEGER`
 * (backend-erp ticket 07, "RULED 2026-08-26: option 1" -
 * `.scratch/backend-erp/issues/07-undated-notes-have-no-server-shape.md`), mirroring
 * `public.events.starts_at` going nullable server-side
 * (`supabase/migrations/20260826000400_events_starts_at_nullable.sql`). A genuinely dateless Notes
 * `Item` (measured 53 of 56 real rows) now has a row to live in rather than being skipped by
 * [com.kevin.legion.backend.EventsReconcile] - see that object's own class doc and
 * [EventReplica]'s own doc comment for the NULLS LAST ordering policy this column now needs
 * everywhere it is sorted on.
 *
 * SQLite has no `ALTER COLUMN`, so widening `NOT NULL` away cannot be a plain `ALTER TABLE ADD
 * COLUMN` - this is the standard create-new-table / copy / drop / rename sequence, same shape
 * [MIGRATION_19_20] used for `service_records.cost` -> `.costCents`. Every column and value is
 * carried over UNCHANGED (this is a type-nullability widening, not a data transform), and the
 * unique index on `serverId` is dropped along with the old table (SQLite indexes do not survive a
 * `DROP TABLE`) and recreated on the new one. SQL below is PASTED VERBATIM from the kapt-generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/40.json`'s `events_replica` `createSql`
 * (with `${TABLE_NAME}` resolved to the real table name) after a real `compileDebugKotlin -Pnokey`
 * run, per CLAUDE.md sec 5's "copy generated SQL verbatim" discipline - not hand-written, same
 * posture as every migration above this one. `event_skips_replica` is untouched: it has no
 * `startsAt` column and no dependency on this one's identity.
 */
val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `events_replica_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`serverId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`startsAt` INTEGER, " +
                "`endsAt` INTEGER, " +
                "`allDay` INTEGER NOT NULL, " +
                "`location` TEXT, " +
                "`notes` TEXT, " +
                "`source` TEXT NOT NULL, " +
                "`googleEventId` TEXT, " +
                "`done` INTEGER NOT NULL, " +
                "`doneAt` INTEGER, " +
                "`sortOrder` INTEGER, " +
                "`triggerPlaceLabel` TEXT, " +
                "`repeatKind` TEXT, " +
                "`repeatEvery` INTEGER, " +
                "`repeatDaysOfWeek` TEXT, " +
                "`repeatDay` INTEGER, " +
                "`repeatMonth` INTEGER, " +
                "`repeatEndKind` TEXT, " +
                "`repeatEndDate` INTEGER, " +
                "`repeatEndCount` INTEGER, " +
                "`exact` INTEGER NOT NULL, " +
                "`exactDowngraded` INTEGER NOT NULL, " +
                "`missedAt` INTEGER, " +
                "`missedDismissedAt` INTEGER, " +
                "`loggedAt` INTEGER, " +
                "`updatedAtMs` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "INSERT INTO `events_replica_new` (" +
                "`id`, `serverId`, `title`, `startsAt`, `endsAt`, `allDay`, `location`, `notes`, " +
                "`source`, `googleEventId`, `done`, `doneAt`, `sortOrder`, `triggerPlaceLabel`, " +
                "`repeatKind`, `repeatEvery`, `repeatDaysOfWeek`, `repeatDay`, `repeatMonth`, " +
                "`repeatEndKind`, `repeatEndDate`, `repeatEndCount`, `exact`, `exactDowngraded`, " +
                "`missedAt`, `missedDismissedAt`, `loggedAt`, `updatedAtMs`, `deleted`) " +
                "SELECT `id`, `serverId`, `title`, `startsAt`, `endsAt`, `allDay`, `location`, `notes`, " +
                "`source`, `googleEventId`, `done`, `doneAt`, `sortOrder`, `triggerPlaceLabel`, " +
                "`repeatKind`, `repeatEvery`, `repeatDaysOfWeek`, `repeatDay`, `repeatMonth`, " +
                "`repeatEndKind`, `repeatEndDate`, `repeatEndCount`, `exact`, `exactDowngraded`, " +
                "`missedAt`, `missedDismissedAt`, `loggedAt`, `updatedAtMs`, `deleted` " +
                "FROM `events_replica`"
        )
        db.execSQL("DROP TABLE `events_replica`")
        db.execSQL("ALTER TABLE `events_replica_new` RENAME TO `events_replica`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_events_replica_serverId` ON `events_replica` (`serverId`)"
        )
    }
}
