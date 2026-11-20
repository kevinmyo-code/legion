package com.kevin.midnightai.navigation

import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.kevin.midnightai.location.FixSource
import com.kevin.midnightai.location.LocationController
import com.kevin.midnightai.location.PhoneFix
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * Review NAV-2: the navigator and the route origin get ONLY the phone's (MNAI2) fixes or the unit's
 * own GPS: never the hours-old getLastKnownLocation seed, never a network fix; and a route is
 * planned only from a fix under 3 s old. Made-up positions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class NavFixesTest {
    @Before fun clean() = LocationController.resetNavFixesForTest()

    private fun network(lat: Double) = Location("network").apply {
        latitude = lat
        longitude = -95.4
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    @Test fun aNetworkFixNeverReachesTheNavigator() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(500))
        LocationController.publish(FixSource.NETWORK, network(29.70))
        assertNull("a network fix reached the navigator", LocationController.navFixes.value)
    }

    @Test fun aPhoneFixReachesTheNavigator() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(600))
        LocationController.acceptPhone(PhoneFix(29.71, -95.41, 5f, 90f, 12f, null, 0), SystemClock.elapsedRealtimeNanos())
        assertEquals(29.71, LocationController.navFixes.value!!.latitude, 0.0)
    }

    @Test fun aRouteStartsOnlyFromAFixUnderThreeSecondsOld() {
        val now = 50_000_000_000L
        val fix = Location("midnight-beacon").apply { latitude = 29.72; longitude = -95.42; elapsedRealtimeNanos = now - 2_900_000_000L }
        assertEquals(GeoPoint(29.72, -95.42), NavFix.freshOrigin(fix, now))
        fix.elapsedRealtimeNanos = now - 3_100_000_000L
        assertNull("planned from a stale fix", NavFix.freshOrigin(fix, now))
        assertNull(NavFix.freshOrigin(null, now))
        assertTrue(NavFix.FRESH_MS == 3_000L)
    }
}
