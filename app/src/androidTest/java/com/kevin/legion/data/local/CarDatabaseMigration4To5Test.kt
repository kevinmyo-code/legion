package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v4 -> v5 (named, synced companion profiles,
 * Kevin 2026-08-02). Same shape as [CarDatabaseMigration3To4Test] - see its
 * doc comment for why this needs `androidTest`, not a plain JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration4To5Test {
    private val dbName = "migration-test-4-5"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate4To5_addsCompanionProfilesTableAndPreservesExistingData() {
        // Create the v4 database and insert one representative pre-existing
        // row (an ingested_files record) to confirm the migration is purely
        // additive and leaves other tables untouched.
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                "INSERT INTO ingested_files " +
                    "(driveFileId, treeUri, displayName, sizeBytes, lastModified, contentSha256, " +
                    "state, duplicateOfFileId, quarantineReason, transactionCount, firstSeenAt, " +
                    "lastAttemptAt, accountId, minTxnDate, maxTxnDate, duplicatesSkipped, " +
                    "llmAttempted, llmPromptTokens, llmResponseTokens) VALUES " +
                    "('file-1', 'content://tree/1', 'eStmt.pdf', 1024, 1733356800000, NULL, " +
                    "'NEW', NULL, NULL, 0, 1733356800000, 1733356800000, NULL, NULL, NULL, 0, 0, NULL, NULL)"
            )
            close()
        }

        // Run the real migration under test.
        val db = helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5)

        // The pre-existing row in an unrelated table survived untouched.
        val existing = db.query("SELECT driveFileId FROM ingested_files WHERE driveFileId = 'file-1'")
        assertTrue("expected the pre-migration row to still exist", existing.moveToFirst())
        existing.close()

        // companion_profiles exists, is queryable, and starts empty - nothing
        // pre-migration wrote to it, and no destructive fallback should have run.
        val profiles = db.query("SELECT COUNT(*) FROM companion_profiles")
        assertTrue(profiles.moveToFirst())
        assertEquals(0, profiles.getInt(0))
        profiles.close()

        // A row can actually be written and read back through the new schema.
        db.execSQL(
            "INSERT INTO companion_profiles " +
                "(profileId, assistantName, persona, traits, voice, voiceStyle, voiceStyleTraits, updatedAt) " +
                "VALUES ('profile-1', 'Alfred', 'You are Alfred.', '{}', 'Charon', '', '{}', 1733356800000)"
        )
        val written = db.query("SELECT assistantName FROM companion_profiles WHERE profileId = 'profile-1'")
        assertTrue(written.moveToFirst())
        assertEquals("Alfred", written.getString(0))
        written.close()
    }
}
