package com.kevin.legion.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The "Wake word engine" setting: one default, stored by key, threshold clamped. */
@RunWith(RobolectricTestRunner::class)
class WakeEngineSettingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `unset reads as the one default, which is the current engine for this build`() {
        assertEquals(WakeEngineKind.DEFAULT, WakeWordPreferences.engine(context))
        assertEquals(WakeEngineKind.CURRENT, WakeEngineKind.DEFAULT)
    }

    @Test
    fun `the choice round-trips, and switching back works`() {
        WakeWordPreferences.setEngine(context, WakeEngineKind.OPEN_WAKE_WORD)
        assertEquals(WakeEngineKind.OPEN_WAKE_WORD, WakeWordPreferences.engine(context))
        WakeWordPreferences.setEngine(context, WakeEngineKind.CURRENT)
        assertEquals(WakeEngineKind.CURRENT, WakeWordPreferences.engine(context))
    }

    @Test
    fun `an unknown stored key falls back to the default, never crashes`() {
        assertEquals(WakeEngineKind.DEFAULT, WakeEngineKind.fromKey("porcupine"))
        assertEquals(WakeEngineKind.DEFAULT, WakeEngineKind.fromKey(null))
        assertEquals(WakeEngineKind.OPEN_WAKE_WORD, WakeEngineKind.fromKey("openwakeword"))
    }

    @Test
    fun `threshold defaults, round-trips and is clamped to the slider range`() {
        assertEquals(OpenWakeWordDetector.DEFAULT_THRESHOLD, WakeWordPreferences.owwThreshold(context), 0f)
        WakeWordPreferences.setOwwThreshold(context, 0.35f)
        assertEquals(0.35f, WakeWordPreferences.owwThreshold(context), 0f)
        WakeWordPreferences.setOwwThreshold(context, 2f)
        assertEquals(0.95f, WakeWordPreferences.owwThreshold(context), 0f)
        WakeWordPreferences.setOwwThreshold(context, -1f)
        assertEquals(0.05f, WakeWordPreferences.owwThreshold(context), 0f)
        assertEquals(OpenWakeWordDetector.DEFAULT_THRESHOLD, WakeEngineKind.clampThreshold(Float.NaN), 0f)
    }

    @Test
    fun `the default threshold sits inside the slider range`() {
        assertEquals(true, OpenWakeWordDetector.DEFAULT_THRESHOLD in WakeEngineKind.THRESHOLD_RANGE)
    }
}
