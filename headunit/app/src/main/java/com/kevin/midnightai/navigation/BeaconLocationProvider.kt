package com.kevin.midnightai.navigation

import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import com.mapbox.common.Cancelable
import com.mapbox.common.location.DeviceLocationProvider
import com.mapbox.common.location.DeviceLocationProviderFactory
import com.mapbox.common.location.GetLocationCallback
import com.mapbox.common.location.Location
import com.mapbox.common.location.LocationObserver
import com.mapbox.common.location.toCommonLocation
import com.mapbox.bindgen.ExpectedFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.location.Location as AndroidLocation

/**
 * The head unit's position for Mapbox (HU-1 T5): the unit's own GPS is dead (headunit/CLAUDE.md
 * §14), so the navigator and the map follow [com.kevin.midnightai.location.LocationController.navFixes]:
 * ONLY the phone's authenticated feed (MNAI2) or the unit's own GPS, never the getLastKnownLocation
 * seed and never a network fix (review NAV-2). Nothing is fabricated or extrapolated, a fix is
 * passed on once, and a fix older than the last one passed is dropped. No fix means no callback.
 *
 * Conversion is Mapbox's own `LocationServiceUtils.toCommonLocation` (traced by javap: it maps
 * `elapsedRealtimeNanos` to `monotonicTimestamp`), so every fix carries the unit's own monotonic
 * receive time that LocationController stamped.
 */
class BeaconLocationProvider(
    private val fixes: StateFlow<AndroidLocation?>,
    private val convert: (AndroidLocation) -> Location = { it.toCommonLocation() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : DeviceLocationProvider {

    private class Registered(val observer: LocationObserver, val looper: Looper?)

    private val observers = CopyOnWriteArrayList<Registered>()
    private var job: Job? = null
    @Volatile private var last: Location? = null
    @Volatile private var lastNanos: Long = Long.MIN_VALUE

    override fun getName(): String = NAME

    override fun addLocationObserver(observer: LocationObserver) = add(Registered(observer, null))

    override fun addLocationObserver(observer: LocationObserver, looper: Looper) = add(Registered(observer, looper))

    private fun add(r: Registered) {
        observers.removeAll { it.observer === r.observer }
        observers += r
        start()
    }

    override fun removeLocationObserver(observer: LocationObserver) {
        observers.removeAll { it.observer === observer }
        if (observers.isEmpty()) stop()
    }

    override fun getLastLocation(callback: GetLocationCallback): Cancelable {
        last?.let { callback.run(it) }
        return Cancelable { }
    }

    /** PendingIntent delivery is not offered: the navigator registers observers. */
    override fun requestLocationUpdates(pendingIntent: PendingIntent) = Unit

    override fun removeLocationUpdates(pendingIntent: PendingIntent) = Unit

    @Synchronized
    private fun start() {
        if (job?.isActive == true) return
        job = scope.launch { fixes.collect { it?.let(::offer) } }
    }

    @Synchronized
    private fun stop() {
        job?.cancel()
        job = null
    }

    /** Passes one real fix on, once, if it is newer than the last one passed. */
    internal fun offer(fix: AndroidLocation) {
        val nanos = fix.elapsedRealtimeNanos
        if (nanos <= lastNanos) return
        lastNanos = nanos
        val converted = convert(fix)
        last = converted
        val batch = listOf(converted)
        for (r in observers) {
            val looper = r.looper
            if (looper == null || looper == Looper.myLooper()) {
                r.observer.onLocationUpdateReceived(batch)
            } else {
                Handler(looper).post { r.observer.onLocationUpdateReceived(batch) }
            }
        }
    }

    /** For the navigator's options: always this one provider. */
    class Factory(private val provider: BeaconLocationProvider) : DeviceLocationProviderFactory {
        override fun build(
            request: com.mapbox.common.location.LocationProviderRequest?,
        ): com.mapbox.bindgen.Expected<com.mapbox.common.location.LocationError, DeviceLocationProvider> =
            ExpectedFactory.createValue(provider)
    }

    companion object {
        const val NAME = "car-link-phone"
    }
}
