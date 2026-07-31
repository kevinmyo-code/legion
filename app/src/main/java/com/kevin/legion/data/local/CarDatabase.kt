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
    ],
    version = 1,
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
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
