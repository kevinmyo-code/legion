package com.kevin.midnightai.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for B20's pure conflict-decision helpers. No device needed. */
class DriveConflictTest {

    @Test
    fun versionChanged_trueWhenBothPresentAndUnequal() {
        assertTrue(DriveConflict.versionChanged("1", "2"))
    }

    @Test
    fun versionChanged_falseWhenEqual() {
        assertFalse(DriveConflict.versionChanged("5", "5"))
    }

    @Test
    fun versionChanged_falseWhenEitherSideUnknown() {
        assertFalse(DriveConflict.versionChanged(null, "2"))
        assertFalse(DriveConflict.versionChanged("2", null))
        assertFalse(DriveConflict.versionChanged(null, null))
    }

    @Test
    fun shouldRetry_trueUnderCap() {
        assertTrue(DriveConflict.shouldRetry(attempt = 1, maxAttempts = 3))
        assertTrue(DriveConflict.shouldRetry(attempt = 2, maxAttempts = 3))
    }

    @Test
    fun shouldRetry_falseAtOrOverCap() {
        assertFalse(DriveConflict.shouldRetry(attempt = 3, maxAttempts = 3))
        assertFalse(DriveConflict.shouldRetry(attempt = 4, maxAttempts = 3))
    }
}
