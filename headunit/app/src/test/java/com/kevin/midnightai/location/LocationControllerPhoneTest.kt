package com.kevin.midnightai.location

import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.kevin.midnightai.service.DeviceRole
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

    private fun phone(lat: Double, age: Long = 0) = PhoneFix(lat, -95.0, 4f, 10f, 12f, null, age)

    private fun network(lat: Double) = Location("network").apply { latitude = lat; longitude = -95.0 }

    @Test fun aPhoneFixIsPublishedWithItsAgeAndNeverStampedInTheFuture() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(100))
        val now = SystemClock.elapsedRealtimeNanos()
        LocationController.acceptPhone(phone(29.1), fixNanos = now - 400_000_000L)
        val loc = LocationController.state.value!!
        assertEquals(29.1, loc.latitude, 0.0)
        assertEquals(LocationController.BEACON_PROVIDER, loc.provider)
        assertEquals(now - 400_000_000L, loc.elapsedRealtimeNanos)

        LocationController.acceptPhone(phone(29.2), fixNanos = now + 5_000_000_000L)
        assertTrue(LocationController.state.value!!.elapsedRealtimeNanos <= SystemClock.elapsedRealtimeNanos())
    }

    @Test fun aLivePhoneBeatsANetworkFixAndTheNetworkTakesOverWhenThePhoneGoesQuiet() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1_000))
        LocationController.acceptPhone(phone(30.0), SystemClock.elapsedRealtimeNanos())
        LocationController.publish(FixSource.NETWORK, network(31.0))
        assertEquals(30.0, LocationController.state.value!!.latitude, 0.0)

        ShadowSystemClock.advanceBy(Duration.ofMillis(LocationSources.HOLD_MS + 1))
        LocationController.publish(FixSource.NETWORK, network(31.0))
        assertEquals(31.0, LocationController.state.value!!.latitude, 0.0)
    }

    @Test fun onlyAnUnsetRoleListensWhenPaired() {
        assertTrue(DeviceRole.listensForPhone(DeviceRole.Role.HEAD_UNIT, paired = false))
        assertTrue(DeviceRole.listensForPhone(DeviceRole.Role.OFF, paired = true))
        assertFalse(DeviceRole.listensForPhone(DeviceRole.Role.OFF, paired = false))
        assertFalse(DeviceRole.listensForPhone(DeviceRole.Role.GPS_BEACON, paired = true))
    }
}
