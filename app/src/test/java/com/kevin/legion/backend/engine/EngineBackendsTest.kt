package com.kevin.legion.backend.engine

import com.kevin.legion.backend.SupabaseEventsBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EngineBackends] - the transport gate itself.
 *
 * **This class doc used to say the property being pinned was "only when set": an install that has
 * not explicitly flipped an aspect to Django must behave exactly as it did before ticket 09.**
 * That was true until 2026-09-06, when `events` and `checklists` took Django as their default on a
 * device signed in to an engine, and it is emphatically false since 2026-09-10, when **all nine
 * aspects** moved into [EngineTransport.DJANGO_BY_DEFAULT]. The property NOW is narrower and is the
 * guard on that flip: **an install with no engine behaves exactly as it did before**, whether it
 * flipped anything or not. Most cases below therefore pin their transports explicitly in `setUp`
 * rather than leaning on a default; the ones that exercise the default say so in their names, and
 * they build their transport from the same signed-in config as `backends()` - a default-constructed
 * [EngineTransport] is a signed-OUT device and would answer SUPABASE for every one of them.
 *
 * No Supabase project is configured in this test environment, so the Supabase branch resolves to
 * null rather than to a [SupabaseEventsBackend] - which is itself the correct answer for an
 * unconfigured install, and is what the "not Django" assertions below actually check: whatever
 * comes back, it is never a [DjangoEventsBackend] unless the toggle says so.
 */
@RunWith(RobolectricTestRunner::class)
class EngineBackendsTest {

    private val context = RuntimeEnvironment.getApplication()

    // No explicit `transport`: it now defaults FROM the config passed here, so the transport and
    // the backends agree about whether this device has an engine. See EngineBackends' own note on
    // its parameter order.
    private fun backends() = EngineBackends(
        context = context,
        config = EngineTestSupport.signedInConfig(context),
    )

    @Before
    fun setUp() {
        // Every aspect explicitly PINNED to Supabase before each case - EngineTransport is
        // SharedPreferences-backed and Robolectric carries prefs across tests in a class.
        //
        // This comment used to read "back to the shipped default", which stopped being true on
        // 2026-09-06: events and checklists now default to Django on a device with an engine, and
        // this class's `backends()` is exactly such a device. Pinning is what keeps the
        // "only once the transport is flipped" cases below testing what they say they test -
        // EngineTransportTest owns the defaults themselves.
        val transport = EngineTransport(context)
        EngineTransport.KNOWN_ASPECTS.forEach { transport.setTransport(it, Transport.SUPABASE) }
    }

    @Test
    fun `events resolves to the Django backend only once the transport is flipped`() = runBlocking {
        // Pinned to Supabase by setUp: never Django, even though this device is signed in to
        // the engine and Django is now the DEFAULT for events. An explicit choice wins.
        assertTrue(backends().eventsBackendNow() !is DjangoEventsBackend)
        assertTrue(backends().eventsBackendAfterAuth() !is DjangoEventsBackend)

        EngineTransport(context).setTransport(EngineBackends.ASPECT_EVENTS, Transport.DJANGO)

        assertTrue(backends().eventsBackendNow() is DjangoEventsBackend)
        assertTrue(backends().eventsBackendAfterAuth() is DjangoEventsBackend)
    }

    @Test
    fun `checklists resolve to nothing at all until the transport is flipped`() {
        // There is no Supabase checklists backend to fall back to - those tables are Django-owned
        // end to end - so "not on the engine" means "not synced". setUp has pinned this aspect to
        // Supabase; the shipped default on a signed-in device is Django, which the two
        // default-path cases below cover.
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
        val backends = EngineBackends(context, signedOut)

        assertNull(backends.eventsBackendNow())
        assertNull(backends.checklistsBackend())
        assertTrue(!backends.isConfiguredFor(EngineBackends.ASPECT_EVENTS))
    }

    @Test
    fun `a fresh install with no engine token is on Supabase for every aspect`() {
        // The 2026-09-06 default flip's guard, and the property that makes it safe to ship: with
        // no engine on the device, nothing about a fresh install changes. `events` resolves the
        // Supabase way (null here only because no Supabase project is configured in this
        // environment either), `checklists` resolves to nothing at all, and the poll that stands
        // in for Realtime does not run - exactly as before the flip.
        val signedOut = EngineConfig(
            context = context,
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        signedOut.clearSession()
        // Nothing pinned: this is the DEFAULT path, so the pins from setUp are cleared first.
        context.getSharedPreferences("engine_transport", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        val transport = EngineTransport(context, signedOut)
        val backends = EngineBackends(context, signedOut, transport)

        assertEquals(Transport.SUPABASE, transport.transportFor(EngineBackends.ASPECT_EVENTS))
        assertEquals(Transport.SUPABASE, transport.transportFor(EngineBackends.ASPECT_CHECKLISTS))
        assertTrue(backends.eventsBackendNow() !is DjangoEventsBackend)
        assertNull(backends.checklistsBackend())
        assertTrue(!EnginePoll(transport, {}, {}).shouldPoll())
        // And it is reported, not silent.
        assertTrue(backends.isFallingBackToSupabase(EngineBackends.ASPECT_EVENTS))
    }

    @Test
    fun `signed in to an engine, both slice aspects are on Django with nothing flipped by hand`() {
        val config = EngineTestSupport.signedInConfig(context)
        context.getSharedPreferences("engine_transport", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        val backends = EngineBackends(context, config)

        assertTrue(backends.eventsBackendNow() is DjangoEventsBackend)
        assertNotNull(backends.checklistsBackend())
        assertTrue(!backends.isFallingBackToSupabase(EngineBackends.ASPECT_EVENTS))
    }

    @Test
    fun `the four Phase 5 aspects are on Django by default and follow their own row`() {
        // **Reversed 2026-09-10, and the direction of the assertions is the whole change.** This
        // test used to prove these four default to SUPABASE and reach a Django backend only once
        // their row is flipped. All nine aspects are in EngineTransport.DJANGO_BY_DEFAULT now, so
        // with no row at all each resolves to Django, and the toggle is proven by flipping one the
        // OTHER way. What is pinned either way is that the ROW decides, never the aspect's name.
        //
        // **The transport is built from the same signedInConfig as `backends()`, and that is
        // load-bearing.** A default-constructed `EngineTransport(context)` is a signed-OUT device,
        // and DJANGO_BY_DEFAULT is conditional on the engine being usable - so a bare
        // `EngineTransport(context)` here answers SUPABASE while `backends()` answers Django, and
        // the two halves of this test would be describing two different devices.
        val transport = EngineTransport(context, EngineTestSupport.signedInConfig(context))
        context.getSharedPreferences("engine_transport", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        assertEquals(Transport.DJANGO, transport.transportFor(EngineBackends.ASPECT_PLACES))
        assertEquals(Transport.DJANGO, transport.transportFor(EngineBackends.ASPECT_VOICE_NOTES))
        assertEquals(Transport.DJANGO, transport.transportFor(EngineBackends.ASPECT_BODY))
        assertEquals(Transport.DJANGO, transport.transportFor(EngineBackends.ASPECT_MEMORY))

        assertTrue(backends().placesBackend() is DjangoPlacesBackend)
        assertTrue(backends().voiceNotesBackend() is DjangoVoiceNotesBackend)
        assertTrue(backends().bodyBackend() is DjangoBodyBackend)
        assertTrue(backends().memoryBackend() is DjangoMemoryBackend)

        // Nor does a default claim to be a fallback: nothing fell back, the aspect is ON Django,
        // and saying otherwise would put a sentence on the Setup screen about a flip that never
        // happened.
        assertTrue(!backends().isFallingBackToSupabase(EngineBackends.ASPECT_BODY))

        // The row still decides, in the direction that can now regress unnoticed: flipped by hand
        // to Supabase, the aspect leaves Django even though Django is the shipped default. (The
        // Supabase branch resolves to null in this environment because no Supabase project is
        // configured, which is itself right for an unconfigured install - so these pin "not
        // Django", never "is Supabase".)
        transport.setTransport(EngineBackends.ASPECT_PLACES, Transport.SUPABASE)
        transport.setTransport(EngineBackends.ASPECT_VOICE_NOTES, Transport.SUPABASE)
        transport.setTransport(EngineBackends.ASPECT_BODY, Transport.SUPABASE)
        transport.setTransport(EngineBackends.ASPECT_MEMORY, Transport.SUPABASE)

        assertTrue(backends().placesBackend() !is DjangoPlacesBackend)
        assertTrue(backends().voiceNotesBackend() !is DjangoVoiceNotesBackend)
        assertTrue(backends().bodyBackend() !is DjangoBodyBackend)
        assertTrue(backends().memoryBackend() !is DjangoMemoryBackend)
    }

    @Test
    fun `the last three aspects are on Django by default and follow their own row`() {
        // **Reversed 2026-09-10 with the four above**, and built from the same signedInConfig for
        // the same reason (see that test's note): ledger, pantry and fleet are in
        // EngineTransport.DJANGO_BY_DEFAULT now, so each resolves to Django with no row set.
        // `ledger` still answers TWO backends - the gated read-only half and the writable config
        // half - and both must move together, which is the property the ledger pair pins in
        // either direction.
        val transport = EngineTransport(context, EngineTestSupport.signedInConfig(context))
        context.getSharedPreferences("engine_transport", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        assertEquals(Transport.DJANGO, transport.transportFor(EngineBackends.ASPECT_LEDGER))
        assertEquals(Transport.DJANGO, transport.transportFor(EngineBackends.ASPECT_PANTRY))
        assertEquals(Transport.DJANGO, transport.transportFor(EngineBackends.ASPECT_FLEET))

        assertTrue(backends().ledgerBackend() is DjangoLedgerBackend)
        assertTrue(backends().ledgerConfigBackend() is DjangoLedgerConfigBackend)
        assertTrue(backends().pantryBackend() is DjangoPantryBackend)
        assertTrue(backends().fleetBackend() is DjangoFleetBackend)
        assertTrue(!backends().isFallingBackToSupabase(EngineBackends.ASPECT_FLEET))

        // Both ledger halves move together when the single `ledger` row is flipped by hand, and
        // pantry and fleet follow their own rows the same way.
        transport.setTransport(EngineBackends.ASPECT_LEDGER, Transport.SUPABASE)
        transport.setTransport(EngineBackends.ASPECT_PANTRY, Transport.SUPABASE)
        transport.setTransport(EngineBackends.ASPECT_FLEET, Transport.SUPABASE)

        assertTrue(backends().ledgerBackend() !is DjangoLedgerBackend)
        assertTrue(backends().ledgerConfigBackend() !is DjangoLedgerConfigBackend)
        assertTrue(backends().pantryBackend() !is DjangoPantryBackend)
        assertTrue(backends().fleetBackend() !is DjangoFleetBackend)
    }

    @Test
    fun `the last three aspects flipped to Django with no engine token resolve to nothing`() {
        // Same guard, same reason as the Phase 5 case below: an explicit flip is honoured verbatim
        // even with no token, so a write fails loudly rather than being re-pointed at Supabase.
        val transport = EngineTransport(context)
        transport.setTransport(EngineBackends.ASPECT_LEDGER, Transport.DJANGO)
        transport.setTransport(EngineBackends.ASPECT_PANTRY, Transport.DJANGO)
        transport.setTransport(EngineBackends.ASPECT_FLEET, Transport.DJANGO)
        val signedOut = EngineConfig(
            context = context,
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        signedOut.clearSession()
        val backends = EngineBackends(context, signedOut)

        assertNull(backends.ledgerBackend())
        assertNull(backends.ledgerConfigBackend())
        assertNull(backends.pantryBackend())
        assertNull(backends.fleetBackend())
    }

    @Test
    fun `a Phase 5 aspect flipped to Django with no engine token resolves to nothing`() {
        // Same guard the slice aspects have: an explicit flip is honoured verbatim even with no
        // token, so the write fails loudly rather than being re-pointed at Supabase behind the
        // driver's back. See EngineTransport's class doc, third paragraph.
        val transport = EngineTransport(context)
        transport.setTransport(EngineBackends.ASPECT_BODY, Transport.DJANGO)
        transport.setTransport(EngineBackends.ASPECT_MEMORY, Transport.DJANGO)
        transport.setTransport(EngineBackends.ASPECT_PLACES, Transport.DJANGO)
        transport.setTransport(EngineBackends.ASPECT_VOICE_NOTES, Transport.DJANGO)
        val signedOut = EngineConfig(
            context = context,
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        signedOut.clearSession()
        val backends = EngineBackends(context, signedOut)

        assertNull(backends.bodyBackend())
        assertNull(backends.memoryBackend())
        assertNull(backends.placesBackend())
        assertNull(backends.voiceNotesBackend())
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
