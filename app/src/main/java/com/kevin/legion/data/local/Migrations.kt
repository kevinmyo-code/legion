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
