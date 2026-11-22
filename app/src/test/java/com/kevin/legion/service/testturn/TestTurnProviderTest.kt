package com.kevin.legion.service.testturn

import android.os.Process
import com.kevin.legion.service.cascade.EngineBrainSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBinder

/**
 * The provider's two gates, as built: only adb's shell (or root) is answered at all, and the
 * Settings switch then gates the test-turn commands. The `engine_brain` developer switch is NOT
 * behind the Settings switch, by design (EngineBrainSettings: "set over the existing adb door,
 * shell or root only"): it is the engine brain's rollback, so it must work with test turns off.
 * Pinned here so a change to either gate is a decision, not an accident (review 2026-11-22).
 */
@RunWith(RobolectricTestRunner::class)
class TestTurnProviderTest {
    private val provider = Robolectric.buildContentProvider(TestTurnProvider::class.java).create().get()

    @After
    fun reset() {
        ShadowBinder.reset()
        provider.context?.let { EngineBrainSettings.setOn(it, false) }
    }

    @Test
    fun `an app caller gets adb_only for every method, engine_brain included`() {
        ShadowBinder.setCallingUid(Process.myUid())
        for (m in listOf("status", "turn", "end", EngineBrainSettings.ADB_METHOD)) {
            val b = provider.call(m, "on", null)
            assertEquals(m, false, b.getBoolean("ok"))
            assertEquals(m, "adb_only", b.getString("error"))
        }
        assertEquals(false, EngineBrainSettings.on(provider.context!!))
    }

    @Test
    fun `from the shell with test turns off, a turn is refused but engine_brain answers`() {
        ShadowBinder.setCallingUid(SHELL_UID)
        val turn = provider.call("turn", "hello", null)
        assertEquals(false, turn.getBoolean("ok"))
        assertTrue(turn.getString("error")!!.startsWith("disabled"))
        val brain = provider.call(EngineBrainSettings.ADB_METHOD, "status", null)
        assertEquals(true, brain.getBoolean("ok"))
        assertEquals(false, brain.getBoolean("engine_brain"))
    }

    private companion object {
        const val SHELL_UID = 2000
    }
}
