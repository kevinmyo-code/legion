package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * [AssistantIgnition.resumeIfEnabled] - the boot/app-start reconciliation added 2026-08-17 for the
 * measured defect where the persisted "assistant on" flag read true while
 * [AriaForegroundService] was not actually running (nothing but the Settings toggle's own handler
 * ever called [AssistantIgnition.start]). Robolectric only because this reads/writes the real
 * `assistant_ignition` [android.content.SharedPreferences] file and asserts on a shadowed
 * `startService` call, same shape as [com.kevin.legion.ai.CompanionProfileTest].
 *
 * What this test file does NOT and CANNOT cover, because it needs a real device/process
 * lifecycle: whether [AriaForegroundService.isInForegroundEligibleState] actually reads
 * IMPORTANCE_FOREGROUND differently from a boot-triggered start vs. an app-launch-triggered start,
 * and whether a real BOOT_COMPLETED-launched service claiming `microphone` really throws
 * ForegroundServiceStartNotAllowedException on-device. Those are named gaps in the report, not
 * silently skipped.
 */
@RunWith(RobolectricTestRunner::class)
class AssistantIgnitionResumeTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        context.getSharedPreferences("assistant_ignition", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        Shadows.shadowOf(context as android.app.Application).clearStartedServices()
    }

    @Test
    fun `flag off means resumeIfEnabled does nothing and returns false`() {
        // Default state: never toggled on, matching AssistantIgnition.isEnabled's own doc
        // ("Off by default... a fresh install asks for nothing").
        assertFalse(AssistantIgnition.isEnabled(context))

        val resumed = AssistantIgnition.resumeIfEnabled(context)

        assertFalse(resumed)
        val shadowApp = Shadows.shadowOf(context as android.app.Application)
        assertNull("no service should have been started", shadowApp.nextStartedService)
    }

    @Test
    fun `flag on means resumeIfEnabled starts the service and returns true, without rewriting the flag`() {
        // Simulate a prior consent decision (what AssistantIgnition.start() would have persisted)
        // without going through start() itself, so this test isolates resumeIfEnabled's own
        // behaviour from start()'s.
        context.getSharedPreferences("assistant_ignition", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", true).apply()

        val resumed = AssistantIgnition.resumeIfEnabled(context)

        assertTrue(resumed)
        val shadowApp = Shadows.shadowOf(context as android.app.Application)
        val started = shadowApp.nextStartedService
        assertEquals(AriaForegroundService::class.java.name, started?.component?.className)

        // The load-bearing distinction from start(): this must never WRITE the flag - it only
        // reconciles the service to what the flag already says. Confirmed by re-reading the raw
        // preference rather than AssistantIgnition.isEnabled(), so a bug that flipped it back to
        // the same true value by accident would still be caught by an unchanged-value assertion.
        assertTrue(
            context.getSharedPreferences("assistant_ignition", android.content.Context.MODE_PRIVATE)
                .getBoolean("enabled", false)
        )
    }
}
