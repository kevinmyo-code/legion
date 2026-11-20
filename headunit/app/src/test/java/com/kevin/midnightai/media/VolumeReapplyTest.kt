package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU-1 T2: the Volume check panel offers one re-apply button per strategy, each named plainly. */
class VolumeReapplyTest {

    @Test fun everyStrategyHasItsOwnButtonLabel() {
        val labels = VolumeController.Reapply.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() && it == it.uppercase() })
    }

    @Test fun theTodayReassertIsOneOfThem() {
        // The 2026-10-13 guess (step away, set back, both with FLAG_SHOW_UI) stays testable by hand.
        assertTrue(VolumeController.Reapply.STEP_AWAY_AND_BACK in VolumeController.Reapply.entries)
    }

    @Test fun eachStrategyExplainsWhatItCalls() {
        VolumeController.Reapply.entries.forEach { assertTrue(it.name, it.what.contains("STREAM_MUSIC")) }
    }
}
