package com.kevin.midnightai.navigation

import android.os.Build
import com.mapbox.common.location.Location
import com.mapbox.common.location.LocationObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import android.location.Location as AndroidLocation

/**
 * HU-1 T5: Mapbox follows the phone-fed fixes, REAL only: each fix once, never an older one after a
 * newer one, nothing without a fix, and nothing once the last observer leaves. Made-up positions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class BeaconLocationProviderTest {
    private val fixes = MutableStateFlow<AndroidLocation?>(null)
    private val converted = mutableListOf<Long>()

    private fun provider() = BeaconLocationProvider(
        fixes,
        convert = { fix ->
            converted += fix.elapsedRealtimeNanos
            Location.Builder().latitude(fix.latitude).longitude(fix.longitude).timestamp(fix.time)
                .monotonicTimestamp(fix.elapsedRealtimeNanos).build()
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private fun fix(nanos: Long, lat: Double = 29.7) = AndroidLocation("phone-beacon").apply {
        latitude = lat
        longitude = -95.4
        time = 1_800_000_000_000L
        elapsedRealtimeNanos = nanos
    }

    @Test fun eachRealFixIsPassedOnceInOrderAndNothingWithoutAFix() {
        val p = provider()
        val seen = mutableListOf<Long>()
        val observer = LocationObserver { batch -> batch.forEach { seen += it.monotonicTimestamp!! } }
        p.addLocationObserver(observer)
        ShadowLooper.idleMainLooper()
        assertTrue("a callback with no fix", seen.isEmpty())
        fixes.value = fix(2_000)
        ShadowLooper.idleMainLooper()
        fixes.value = fix(1_000) // older than the one already shown: dropped
        ShadowLooper.idleMainLooper()
        fixes.value = fix(3_000)
        ShadowLooper.idleMainLooper()
        assertEquals(listOf(2_000L, 3_000L), seen)
        p.removeLocationObserver(observer)
        fixes.value = fix(4_000)
        ShadowLooper.idleMainLooper()
        assertEquals("passed on after the last observer left", listOf(2_000L, 3_000L), seen)
    }

    @Test fun lastLocationIsTheLastRealFixOnly() {
        val p = provider()
        var got: Location? = null
        p.getLastLocation { got = it }
        assertEquals(null, got)
        p.addLocationObserver(LocationObserver { })
        fixes.value = fix(5_000, lat = 29.75)
        ShadowLooper.idleMainLooper()
        p.getLastLocation { got = it }
        assertEquals(29.75, got!!.latitude, 0.0)
    }
}
