package com.kevin.legion.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AlarmScheduler] has ZERO other tests repo-wide - `schedule`/`rescheduleAll` reach a real
 * `AlarmManager`/`PendingIntent`, which a plain JVM test cannot exercise meaningfully. What IS
 * testable without any of that is the one decision the 2026-08-26 incident turned on -
 * [AlarmScheduler.shouldSweepMarkMissed] - so that is what this file covers. The end-to-end
 * behaviour (a configured sweep writes nothing, an unconfigured sweep still marks a real overdue
 * item) is covered from the other side, by calling [AlarmScheduler.rescheduleAll] itself, in
 * `NotesControllerBackendTest` (configured) and `NotesControllerTest` (unconfigured).
 */
class AlarmSchedulerTest {
    @Test
    fun `configured (replica-backed) sweep never marks missed`() {
        assertFalse(AlarmScheduler.shouldSweepMarkMissed(backendConfigured = true))
    }

    @Test
    fun `unconfigured (engine) sweep keeps marking missed, unchanged`() {
        assertTrue(AlarmScheduler.shouldSweepMarkMissed(backendConfigured = false))
    }
}
