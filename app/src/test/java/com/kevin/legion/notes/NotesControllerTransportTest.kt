package com.kevin.legion.notes

import com.kevin.legion.backend.SupabaseConfig
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.testutil.RoomTestReset
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A REMINDER's write follows the `events` transport switch, exactly as a TASK's tick already does.
 *
 * **The defect this pins down, found on the A25 2026-09-06.** `NotesController.backend` was
 * hardcoded to `SupabaseClientProvider.get(context)?.let { SupabaseEventsBackend(it) }`, while
 * `EventsAppointmentWriter.backend` had already been moved onto [EngineBackends] by commit
 * `4d71da8`. With `events` on [Transport.DJANGO] that split one table's writes across two servers
 * from one file: a task's tick went to Django, a reminder's went to Supabase, silently.
 *
 * **How this is observable in a JVM test at all, since neither backend can actually be built here.**
 * A signed-in engine cannot be faked - `EngineConfig`'s production `decrypt` is `KeyVault::decrypt`
 * and there is no Android Keystore in a JVM test, the limit `EventsTickOverTheEngineTest` already
 * records - and a Supabase client cannot be CONSTRUCTED either: `createSupabaseClient` reaches
 * supabase-kt's `SettingsCodeVerifierCache`, which throws `IllegalStateException: Failed to create
 * default settings for SettingsSessionManager` under Robolectric (measured, this session).
 *
 * That second limit is what makes the test possible. **With a Supabase project configured, merely
 * ASKING for the Supabase backend is observable, because it throws**; so:
 * - `events` on Supabase -> the resolution reaches supabase-kt. The switch routed there.
 * - `events` on Django with no engine token -> null, cleanly, with supabase-kt never touched.
 *
 * Before the fix the second case threw too, because this file asked Supabase for a backend
 * whatever the switch said. That difference is the whole assertion.
 */
@RunWith(RobolectricTestRunner::class)
class NotesControllerTransportTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        NotesController.backendOverride = null
        SupabaseConfig.save(context, "https://fake-project.supabase.co", "fake-anon-key")
    }

    @After
    fun tearDown() {
        SupabaseConfig.clear(context)
        NotesController.backendOverride = null
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `a reminder on the Django transport never reaches for the Supabase backend`() {
        EngineTransport(context).setTransport(EngineBackends.ASPECT_EVENTS, Transport.DJANGO)

        // No engine token on this device, so there is no backend - and, the load-bearing half,
        // getting to that answer did not construct a Supabase client. A throw here would mean this
        // file was still resolving Supabase regardless of the switch, which is the defect.
        assertFalse(NotesController.isBackendConfigured(context))
        // The appointment writer's own resolution, called the same way it calls it, agrees.
        assertNull(EngineBackends(context).eventsBackendNow())
    }

    @Test
    fun `a reminder on the Supabase transport does reach for the Supabase backend`() {
        EngineTransport(context).setTransport(EngineBackends.ASPECT_EVENTS, Transport.SUPABASE)

        // Tolerant of the day supabase-kt can be built in a JVM test: what is asserted is that the
        // Supabase branch was TAKEN, either by reaching a client or by failing to build one.
        // A quiet `false` would mean the client was never asked for.
        val reminderPath = runCatching { NotesController.isBackendConfigured(context) }
        val appointmentPath = runCatching { EngineBackends(context).eventsBackendNow() != null }

        assertTrue(reminderPath.isFailure || reminderPath.getOrThrow())
        assertTrue(appointmentPath.isFailure || appointmentPath.getOrThrow())
        // Same switch, same answer, whichever of those two it is.
        assertTrue(reminderPath.isFailure == appointmentPath.isFailure)
    }
}
