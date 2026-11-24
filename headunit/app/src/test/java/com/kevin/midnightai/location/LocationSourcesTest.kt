package com.kevin.midnightai.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU-1 T4: fallback order unit GPS > phone > network, and the words for each. */
class LocationSourcesTest {

    private val beacon = "midnight-beacon"

    @Test fun providersMapToSources() {
        assertEquals(FixSource.UNIT_GPS, LocationSources.of("gps", beacon))
        assertEquals(FixSource.PHONE, LocationSources.of(beacon, beacon))
        assertEquals(FixSource.NETWORK, LocationSources.of("network", beacon))
        assertEquals(FixSource.NETWORK, LocationSources.of("fused", beacon))
        assertEquals(FixSource.NETWORK, LocationSources.of(null, beacon))
    }

    @Test fun labels() {
        assertEquals("GPS on this unit", LocationSources.label(FixSource.UNIT_GPS))
        assertEquals("Phone", LocationSources.label(FixSource.PHONE))
        assertEquals("Network location", LocationSources.label(FixSource.NETWORK))
    }

    @Test fun aLiveUnitGpsSuppressesThePhoneAndTheNetwork() {
        val seen = mapOf(FixSource.UNIT_GPS to 1_000L)
        assertFalse(LocationSources.accepts(FixSource.PHONE, seen, 5_000L))
        assertFalse(LocationSources.accepts(FixSource.NETWORK, seen, 5_000L))
        assertTrue(LocationSources.accepts(FixSource.UNIT_GPS, seen, 5_000L))
    }

    @Test fun aLivePhoneBeatsTheNetworkButNotTheUnitGps() {
        val seen = mapOf(FixSource.PHONE to 1_000L)
        assertFalse(LocationSources.accepts(FixSource.NETWORK, seen, 2_000L))
        assertTrue(LocationSources.accepts(FixSource.PHONE, seen, 2_000L))
        assertTrue(LocationSources.accepts(FixSource.UNIT_GPS, seen, 2_000L))
    }

    @Test fun aSilentBetterSourceHandsOverAfterTheHold() {
        val seen = mapOf(FixSource.PHONE to 1_000L)
        assertFalse(LocationSources.accepts(FixSource.NETWORK, seen, 1_000L + LocationSources.HOLD_MS))
        assertTrue(LocationSources.accepts(FixSource.NETWORK, seen, 1_001L + LocationSources.HOLD_MS))
    }

    @Test fun aLastSeenTimeInTheFutureSuppressesNothing() {
        assertTrue(LocationSources.accepts(FixSource.NETWORK, mapOf(FixSource.PHONE to 9_000L), 1_000L))
    }
}
