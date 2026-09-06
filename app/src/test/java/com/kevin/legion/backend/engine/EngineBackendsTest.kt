package com.kevin.legion.backend.engine

import com.kevin.legion.backend.SupabaseEventsBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EngineBackends] - the transport gate itself. **The property being pinned down is "only when
 * set"**: an install that has not explicitly flipped an aspect to Django must behave exactly as it
 * did before this ticket, which for `events` means the Supabase path and for `checklists` means no
 * sync at all.
 *
 * No Supabase project is configured in this test environment, so the Supabase branch resolves to
 * null rather than to a [SupabaseEventsBackend] - which is itself the correct answer for an
 * unconfigured install, and is what the "not Django" assertions below actually check: whatever
 * comes back, it is never a [DjangoEventsBackend] unless the toggle says so.
 */
@RunWith(RobolectricTestRunner::class)
class EngineBackendsTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backends() = EngineBackends(
        context = context,
        transport = EngineTransport(context),
        config = EngineTestSupport.signedInConfig(context),
    )

    @Before
    fun setUp() {
        // Both aspects back to the shipped default before every case - EngineTransport is
        // SharedPreferences-backed and Robolectric carries prefs across tests in a class.
        val transport = EngineTransport(context)
        EngineTransport.KNOWN_ASPECTS.forEach { transport.setTransport(it, Transport.SUPABASE) }
    }

    @Test
    fun `events resolves to the Django backend only once the transport is flipped`() = runBlocking {
        // Default: never Django, even though this device is signed in to the engine.
        assertTrue(backends().eventsBackendNow() !is DjangoEventsBackend)
        assertTrue(backends().eventsBackendAfterAuth() !is DjangoEventsBackend)

        EngineTransport(context).setTransport(EngineBackends.ASPECT_EVENTS, Transport.DJANGO)

        assertTrue(backends().eventsBackendNow() is DjangoEventsBackend)
        assertTrue(backends().eventsBackendAfterAuth() is DjangoEventsBackend)
    }

    @Test
    fun `checklists resolve to nothing at all until the transport is flipped`() {
        // There is no Supabase checklists backend to fall back to - those tables are Django-owned
        // end to end - so "not on the engine" means "not synced", and the shipped default is
        // exactly that. Flipping the row is an opt-in, per the Setup screen's own promise.
        assertNull(backends().checklistsBackend())

        EngineTransport(context).setTransport(EngineBackends.ASPECT_CHECKLISTS, Transport.DJANGO)

        assertNotNull(backends().checklistsBackend())
    }

    @Test
    fun `a Django aspect with no engine token resolves to nothing, never a backend that cannot send`() {
        EngineTransport(context).setTransport(EngineBackends.ASPECT_EVENTS, Transport.DJANGO)
        EngineTransport(context).setTransport(EngineBackends.ASPECT_CHECKLISTS, Transport.DJANGO)
        val signedOut = EngineConfig(
            context = context,
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        signedOut.clearSession()
        val backends = EngineBackends(context, EngineTransport(context), signedOut)

        assertNull(backends.eventsBackendNow())
        assertNull(backends.checklistsBackend())
        assertTrue(!backends.isConfiguredFor(EngineBackends.ASPECT_EVENTS))
    }

    @Test
    fun `isConfiguredFor answers for the aspect's OWN transport, not a global one`() {
        EngineTransport(context).setTransport(EngineBackends.ASPECT_CHECKLISTS, Transport.DJANGO)

        // checklists is on Django and this device has an address and a token, so it is configured;
        // events is still on Supabase, which is not configured in this environment.
        assertTrue(backends().isConfiguredFor(EngineBackends.ASPECT_CHECKLISTS))
        assertTrue(!backends().isConfiguredFor(EngineBackends.ASPECT_EVENTS))
    }
}
