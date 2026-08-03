package com.kevin.legion.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * LEGION's Room database - fresh v1, ported from Midnight AI's v12 schema with
 * the retired tables dropped (billing, per-car companion identity, mixtape
 * library, music taste ledger). No migration chain: this is a new app with no
 * installed base, so there is nothing to migrate from. The v6-onward
 * additive-migration discipline (exportSchema + schemas/ + MigrationTest) that
 * governed Midnight AI resumes from here, starting at v1.
 *
 * v2: `ledger_transactions` (the ledger aspect's bank-statement ingestion,
 * `.claude/plans/wiggly-beaming-quasar.md`) - additive only, no destructive
 * fallback for the upgrade path even though there's no real installed base
 * yet, matching the discipline this project intends to keep from here on.
 *
 * v3: `pantry_receipts` + `pantry_line_items` (the pantry aspect's grocery-
 * receipt ingestion, same plan file).
 *
 * v4: `ingested_files` (the ledger Drive-ingestion scan's work-avoidance
 * record, `.scratch/ledger-drive-ingestion/issues/03-ingested-file-ledger.md`)
 * plus a nullable `LedgerTransaction.sourceFileId`. No `@ForeignKey` between
 * them - see [LedgerTransaction.sourceFileId]'s doc comment.
 *
 * v5: `companion_profiles` (named, synced assistant identities, Kevin
 * 2026-08-02: two people share one Google account and two phones, and each
 * wants a different assistant). Replaces the bespoke single-identity
 * `syncCompanion`/`companion-<vehicleId>.json` sync path retired in the same
 * change - see [CompanionProfileEntity]'s doc comment and
 * `ai/ActiveCompanionProfile.kt` for the device-local active selection that
 * sits beside it.
 */
@Database(
    entities = [
        ServiceRecord::class, MemoryEntry::class, TaggedPlace::class, Vehicle::class,
        MaintenanceItem::class, PlaceReminder::class, CarTask::class, BuildEntry::class,
        VehicleSpec::class,
        OdbSample::class, CodeEvent::class, OilAnalysis::class,
        ChassisQuirk::class, ForesightNote::class, MonthlyRecap::class, DailyDriveLog::class,
        YearlyWrapped::class,
        DriveReassignment::class,
        EpisodicTurn::class, CompanionMemory::class,
        LedgerTransaction::class,
        PantryReceipt::class, PantryLineItem::class,
        IngestedFile::class,
        CompanionProfileEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class CarDatabase : RoomDatabase() {
    abstract fun serviceRecordDao(): ServiceRecordDao
    abstract fun memoryDao(): MemoryDao
    abstract fun placeDao(): PlaceDao
    abstract fun vehicleDao(): VehicleDao
    abstract fun maintenanceItemDao(): MaintenanceItemDao
    abstract fun placeReminderDao(): PlaceReminderDao
    abstract fun carTaskDao(): CarTaskDao
    abstract fun buildEntryDao(): BuildEntryDao
    abstract fun vehicleSpecDao(): VehicleSpecDao
    abstract fun odbSampleDao(): OdbSampleDao
    abstract fun codeEventDao(): CodeEventDao
    abstract fun oilAnalysisDao(): OilAnalysisDao
    abstract fun chassisQuirkDao(): ChassisQuirkDao
    abstract fun foresightNoteDao(): ForesightNoteDao
    abstract fun monthlyRecapDao(): MonthlyRecapDao
    abstract fun dailyDriveLogDao(): DailyDriveLogDao
    abstract fun yearlyWrappedDao(): YearlyWrappedDao
    abstract fun driveReassignmentDao(): DriveReassignmentDao
    abstract fun episodicTurnDao(): EpisodicTurnDao
    abstract fun companionMemoryDao(): CompanionMemoryDao
    abstract fun ledgerTransactionDao(): LedgerTransactionDao
    abstract fun pantryReceiptDao(): PantryReceiptDao
    abstract fun pantryLineItemDao(): PantryLineItemDao
    abstract fun ingestedFileDao(): IngestedFileDao
    abstract fun companionProfileDao(): CompanionProfileDao

    companion object {
        @Volatile
        private var INSTANCE: CarDatabase? = null

        fun getDatabase(context: Context): CarDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CarDatabase::class.java,
                    "legion_database",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
