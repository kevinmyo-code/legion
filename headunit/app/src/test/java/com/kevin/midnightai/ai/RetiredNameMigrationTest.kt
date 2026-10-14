package com.kevin.midnightai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-planner tests for the "Zero" -> Uncle Moose rename (no SharedPreferences on the JVM). */
class RetiredNameMigrationTest {

    private fun plan(vararg kv: Pair<String, Any>) =
        CompanionProfile.planRetiredNameMigration(mapOf(*kv))

    @Test
    fun legacyFlatZero_becomesMoose_andNeverTouchesTheClock() {
        val w = plan("name" to "Zero", "companion_updated_at" to 10L)
        assertEquals(mapOf<String, Any>("name" to CompanionProfile.DEFAULT_NAME), w)
    }

    @Test
    fun perCarZero_trimmedAndCaseInsensitive_renamedWithoutClockWrites() {
        val w = plan("name:car:abc" to " zero ", "name:AA:BB" to "ZERO")
        assertEquals(CompanionProfile.DEFAULT_NAME, w["name:car:abc"])
        assertEquals(CompanionProfile.DEFAULT_NAME, w["name:AA:BB"])
        assertEquals(2, w.size)
    }

    @Test
    fun adoptedRemoteName_zeroIsSanitized_othersPassThrough() {
        assertEquals(CompanionProfile.DEFAULT_NAME, CompanionProfile.sanitizeAdoptedName(" ZERO "))
        assertEquals("Zed", CompanionProfile.sanitizeAdoptedName("Zed"))
        assertEquals("Zero Hour", CompanionProfile.sanitizeAdoptedName("Zero Hour"))
        assertEquals("", CompanionProfile.sanitizeAdoptedName(""))
    }

    @Test
    fun customNames_blank_andAlreadyMoose_areUntouched() {
        val w = plan(
            "name" to "Zed",
            "name:car:1" to "Zero Hour",
            "name:car:2" to "",
            "name:car:3" to CompanionProfile.DEFAULT_NAME,
        )
        assertTrue(w.isEmpty())
    }

    @Test
    fun nonNameKeys_andNonStrings_areIgnored() {
        val w = plan("persona" to "Zero", "voice:car:1" to "Zero", "name:car:9" to 7L)
        assertTrue(w.isEmpty())
    }
}
