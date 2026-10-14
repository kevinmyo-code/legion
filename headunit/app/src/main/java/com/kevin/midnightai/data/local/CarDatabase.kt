package com.kevin.midnightai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Midnight AI's Room database — clean schema (the com.kevin.aria migration
 * chain through v13 is dropped; a new app ID meant a fresh install with no
 * migration path needed, back when this was still version 1).
 *
 * v6 onward: real, additive migrations only. `exportSchema = true` emits a
 * versioned JSON to app/schemas/ on every build - that JSON is the source of
 * truth for each Migration's `execSQL` (copy `createSql` byte-identical, per
 * Migrations.kt's KDoc). A patch must never silently wipe a user's mixtapes,
 * taste history, or logbook - so every version bump requires a registered
 * Migration, verified by app/src/androidTest/.../MigrationTest.kt against the
 * committed schema JSONs. `fallbackToDestructiveMigrationOnDowngrade` remains
 * as a dev-only net (rolling a debug build backward) - there is no destructive
 * fallback for upgrades. Bump `version` on every entity/column change AND add
 * the matching Migration in the same commit - a version bump with no migration
 * makes Room throw (not fall back) with the destructive path removed.
 */
@Database(
    entities = [
        ServiceRecord::class, MemoryEntry::class, TaggedPlace::class, Vehicle::class,
        MaintenanceItem::class, PlaceReminder::class, CarTask::class, BuildEntry::class,
        VehicleSpec::class,
        OdbSample::class, CodeEvent::class, OilAnalysis::class,
        ChassisQuirk::class, ForesightNote::class, MonthlyRecap::class, DailyDriveLog::class,
        YearlyWrapped::class, MusicPlay::class,
        LibraryTrack::class, Mixtape::class, MixtapeTrack::class,
        DriveReassignment::class,
        EpisodicTurn::class, CompanionMemory::class,
    ],
    // v7: added `updatedAt` to the mutable tables for cross-device sync
    // last-write-wins (S1). v8: added `syncId` (portable UUID identity) to the
    // user-generated tables that lack a stable natural key (memories,
    // service_records, build_entries, code_events, oil_analyses, car_tasks,
    // place_reminders) so a row is recognisable across the head unit and phone.
    // v9: added `deleted` (soft-delete tombstone, B19) to car_tasks and places -
    // the only two actively-deletable LWW tables - so a driver's deletion
    // propagates through sync as a fact instead of being silently resurrected
    // by the next pull from a remote snapshot that never saw it disappear.
    // v10: `vehicles.archived` + `drive_reassignments` (car manager).
    // v11: companion-memory map ticket 01 - `episodic_turns` (raw Live
    // transcript buffer, per session, cleared after consolidation) and
    // `companion_memories` (durable, distilled, sec-9.1-categorized). The
    // older `memories` table (MemoryEntry) is UNCHANGED - it stays wired to
    // the explicit "remember X" tool; these are additive, separate tables.
    // v12: maintenance-schedule Part 1 - `maintenance_items.neverDone`
    // (INTEGER DEFAULT 0). Distinguishes a driver-confirmed "never done"
    // fact from an unknown anchor (both lastDone* null); see MaintenanceItem's
    // doc comment for why the two must not be conflated.
    version = 12,
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
    abstract fun musicPlayDao(): MusicPlayDao
    abstract fun libraryTrackDao(): LibraryTrackDao
    abstract fun mixtapeDao(): MixtapeDao
    abstract fun mixtapeTrackDao(): MixtapeTrackDao
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
                    "midnight_ai_database",
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
