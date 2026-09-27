package com.kevin.legion.location

import android.Manifest
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Background location must never power the GPS chip (2026-09-27). On the A25, LEGION was the only
 * reason the GPS radio stayed on around the clock: HIGH_ACCURACY every 30s, in a mode whose doc
 * called it "battery-friendly". These pin the fix at the LocationManager boundary itself, so they
 * hold whatever the provider list constant is later renamed to.
 */
@RunWith(RobolectricTestRunner::class)
class LocationControllerBackgroundTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lm get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Before
    fun setUp() {
        LocationController.resetForTest()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            shadowOf(lm).setProviderEnabled(p, true)
        }
    }

    @After
    fun tearDown() = LocationController.resetForTest()

    @Test
    fun `background init registers nothing on GPS`() {
        LocationController.init(context)
        assertTrue(
            "GPS must not be requested in the background",
            shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty(),
        )
    }

    @Test
    fun `background init still tracks via network and passive`() {
        LocationController.init(context)
        assertFalse(shadowOf(lm).getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())
        assertFalse(shadowOf(lm).getLocationRequests(LocationManager.PASSIVE_PROVIDER).isEmpty())
    }

    @Test
    fun `fast mode turns GPS on, and releasing it turns GPS off again`() {
        LocationController.init(context)
        LocationController.startFastMode(context)
        assertFalse(shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())

        LocationController.stopFastMode()
        assertTrue(shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
    }
}
