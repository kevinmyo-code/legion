package com.kevin.midnightai.location

import android.location.Location
import android.os.Build
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** HU-1 T4: the phone path into LocationController and the fallback order, on the real controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class LocationControllerPhoneTest {

    private fun phone(lat: Double, age: Long = 0) = PhoneFix(lat, 0.25, 4f, 10f, 12f, null, age)

    private fun network(lat: Double) = Location("network").apply { latitude = lat; longitude = 0.25 }

    @Test fun aPhoneFixIsPublishedWithItsAgeAndNeverStampedInTheFuture() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(100))
        val now = SystemClock.elapsedRealtimeNanos()
        LocationController.acceptPhone(phone(0.11), fixNanos = now - 400_000_000L)
        val loc = LocationController.state.value!!
        assertEquals(0.11, loc.latitude, 0.0)
        assertEquals(LocationController.BEACON_PROVIDER, loc.provider)
        assertEquals(now - 400_000_000L, loc.elapsedRealtimeNanos)

        LocationController.acceptPhone(phone(0.12), fixNanos = now + 5_000_000_000L)
        assertTrue(LocationController.state.value!!.elapsedRealtimeNanos <= SystemClock.elapsedRealtimeNanos())
    }

    @Test fun aLivePhoneBeatsANetworkFixAndTheNetworkTakesOverWhenThePhoneGoesQuiet() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1_000))
        LocationController.acceptPhone(phone(0.3), SystemClock.elapsedRealtimeNanos())
        LocationController.publish(FixSource.NETWORK, network(0.31))
        assertEquals(0.3, LocationController.state.value!!.latitude, 0.0)

        ShadowSystemClock.advanceBy(Duration.ofMillis(LocationSources.HOLD_MS + 1))
        LocationController.publish(FixSource.NETWORK, network(0.31))
        assertEquals(0.31, LocationController.state.value!!.latitude, 0.0)
    }

    @Test fun h1_anUnauthenticatedMnai1FixIsLabelledUnverifiedNeverPhone() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2_000))
        LocationController.acceptExternal(BeaconFix(0.4, 0.4, 5f, null, null, null))
        val loc = LocationController.state.value!!
        assertEquals(FixSource.PHONE_UNVERIFIED, LocationSources.of(loc.provider, LocationController.BEACON_PROVIDER))
        assertEquals("Phone (unverified)", LocationSources.label(LocationSources.of(loc.provider, LocationController.BEACON_PROVIDER)))
        // An authenticated phone fix outranks it.
        LocationController.acceptPhone(phone(0.5), SystemClock.elapsedRealtimeNanos())
        LocationController.acceptExternal(BeaconFix(0.6, 0.6, 5f, null, null, null))
        assertEquals(0.5, LocationController.state.value!!.latitude, 0.0)
    }
}
