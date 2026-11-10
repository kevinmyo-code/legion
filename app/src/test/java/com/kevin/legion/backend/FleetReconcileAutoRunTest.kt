package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * [FleetReconcile.maybeAutoRun] uploads nothing, on either transport (2026-11-09).
 *
 * The A25 logged `fleet_auto_reconcile_failed ... null value in column "household_id" of relation
 * "vehicles"` on every foreground pass: `fleet` resolved to Supabase there, and the Supabase upload
 * cannot succeed for any device since ADR 0045 put `household_id NOT NULL` on every table and ADR
 * 0044 retired Supabase as a store. These tests pin the stand-down: no failure line, no Supabase
 * upload path left in the file, a rate-limited breadcrumb that says why `fleet` is on Supabase, and
 * the existing silent decline on Django.
 */
@RunWith(RobolectricTestRunner::class)
class FleetReconcileAutoRunTest {

    private val context = RuntimeEnvironment.getApplication()
    private val fleet = EngineBackends.ASPECT_FLEET

    /** Far enough past 0 that the floor never blocks a first call. */
    private val now = 1_000_000_000_000L

    @Before
    fun reset() {
        for (prefs in listOf("engine_transport", "engine_config", "supabase_config")) {
            context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().clear().commit()
        }
        FleetReconcile.setLastAutoRunAtForTest(0L)
        ShadowLog.reset()
    }

    private fun fleetLines(): List<String> =
        ShadowLog.getLogsForTag("MidnightEvents").map { it.msg }.filter { it.startsWith("fleet_auto_reconcile") }

    @Test
    fun `pinned to Supabase with a Supabase project configured stands down and logs no failure`() {
        // The A25's shape: a Supabase project still configured, `fleet` resolving to SUPABASE.
        assertTrue(SupabaseConfig.save(context, "https://example-project.supabase.co", "fake-anon-key"))
        EngineTransport(context).setTransport(fleet, Transport.SUPABASE)

        FleetReconcile.maybeAutoRun(context, now)

        assertEquals(
            listOf("fleet_auto_reconcile_stood_down transport=SUPABASE pinned=true fallingBack=false"),
            fleetLines(),
        )
    }

    @Test
    fun `no engine signed in falls back to Supabase and the breadcrumb says it is the fallback`() {
        FleetReconcile.maybeAutoRun(context, now)

        assertEquals(
            listOf("fleet_auto_reconcile_stood_down transport=SUPABASE pinned=false fallingBack=true"),
            fleetLines(),
        )
    }

    @Test
    fun `on Django it stays silent, as it has since 2026-09-08`() {
        EngineTransport(context).setTransport(fleet, Transport.DJANGO)

        FleetReconcile.maybeAutoRun(context, now)

        assertEquals(emptyList<String>(), fleetLines())
        assertFalse("a Django pass must not reserve the floor", FleetReconcile.isThrottled(now))
    }

    @Test
    fun `the breadcrumb is rate-limited by the five minute floor`() {
        EngineTransport(context).setTransport(fleet, Transport.SUPABASE)

        FleetReconcile.maybeAutoRun(context, now)
        FleetReconcile.maybeAutoRun(context, now + 60_000L)
        assertEquals(1, fleetLines().size)

        FleetReconcile.maybeAutoRun(context, now + 5 * 60_000L)
        assertEquals(2, fleetLines().size)
    }

    @Test
    fun `isPinned reports only an explicit row`() {
        val transport = EngineTransport(context)
        assertFalse(transport.isPinned(fleet))
        transport.setTransport(fleet, Transport.SUPABASE)
        assertTrue(transport.isPinned(fleet))
    }

    /** Structural pin: the reconcile file no longer builds a Supabase backend or client at all, so
     * no future gate change can route its upload back to the retired store. Comments stripped so
     * the history in its KDoc does not count. */
    @Test
    fun `FleetReconcile no longer constructs a Supabase backend or client`() {
        val code = fleetReconcileSource()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        for (forbidden in listOf("SupabaseFleetBackend(", "SupabaseClientProvider", "SupabaseAuth(")) {
            assertFalse("FleetReconcile.kt still references $forbidden", code.contains(forbidden))
        }
    }

    private fun fleetReconcileSource(): String {
        val rel = "src/main/java/com/kevin/legion/backend/FleetReconcile.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "app/$rel"), File(dir, rel))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("Could not locate FleetReconcile.kt from ${System.getProperty("user.dir")}")
        error("unreachable")
    }
}
