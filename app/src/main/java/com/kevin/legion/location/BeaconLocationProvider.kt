package com.kevin.legion.location

import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import com.mapbox.bindgen.Expected
import com.mapbox.bindgen.ExpectedFactory
import com.mapbox.common.Cancelable
import com.mapbox.common.location.DeviceLocationProvider
import com.mapbox.common.location.DeviceLocationProviderFactory
import com.mapbox.common.location.GetLocationCallback
import com.mapbox.common.location.Location as MapboxLocation
import com.mapbox.common.location.LocationError
import com.mapbox.common.location.LocationObserver
import com.mapbox.common.location.LocationProviderRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import android.location.Location as AndroidLocation

/**
 * Feeds [LocationController.state] into Mapbox Nav SDK's own location engine.
 *
 * The SDK never reads our `StateFlow` on its own - it owns its own internal
 * `LocationService` (Android's fused/GPS providers by default) and has no idea a
 * beacon exists. Wiring THIS provider in via `LocationOptions.locationProviderFactory`
 * (see [EmbeddedNavActivity]) is what makes it read the merge point instead: same
 * fix, same object, whether it came from this device's own GPS or a phone beacon
 * over the hotspot (see [LocationController]'s class doc). Nothing here talks to
 * [BeaconClient]/[BeaconServer] directly - bridging the merge point, not the beacon,
 * is what keeps this working identically on a phone with real GPS.
 */
object BeaconLocationProvider : DeviceLocationProvider {

    /**
     * One collector shared by every observer, started on the first
     * [addLocationObserver] and stopped on the last [removeLocationObserver] - the
     * SDK may register more than one internal observer (map puck, trip session,
     * viewport data source all separately observe), and a collector per observer
     * would leak one for every observer that's added without ever unsubscribing in
     * matched pairs.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null

    /**
     * Keyed by observer identity so `removeLocationObserver` can tell which delivery
     * path (bare vs. Looper-targeted) to tear down, and so the same observer added
     * twice with different Loopers is tracked as two independent registrations
     * rather than clobbering one entry.
     */
    private data class Registration(val observer: LocationObserver, val handler: Handler?)
    private val registrations = mutableListOf<Registration>()
    private val lock = Any()

    override fun addLocationObserver(observer: LocationObserver) = register(observer, handler = null)

    override fun addLocationObserver(observer: LocationObserver, looper: Looper) =
        register(observer, handler = Handler(looper))

    private fun register(observer: LocationObserver, handler: Handler?) {
        synchronized(lock) {
            registrations += Registration(observer, handler)
            if (collectJob == null) {
                // Started lazily rather than at object-init: this is a process-wide
                // singleton and should not spin a collector before anything ever
                // asks it for fixes (e.g. app process alive with EmbeddedNavActivity
                // never opened).
                collectJob = LocationController.state.onEach(::dispatch).launchIn(scope)
            }
        }
    }

    override fun removeLocationObserver(observer: LocationObserver) {
        synchronized(lock) {
            registrations.removeAll { it.observer == observer }
            if (registrations.isEmpty()) {
                collectJob?.cancel()
                collectJob = null
            }
        }
    }

    private fun dispatch(location: AndroidLocation?) {
        val fix = location?.toMapboxLocation() ?: return
        val list = listOf(fix)
        // Snapshot under the lock, deliver outside it - onLocationUpdateReceived can
        // re-enter this class (an observer's callback removing itself, which the SDK
        // does on teardown), and holding the lock across that call would deadlock.
        val snapshot = synchronized(lock) { registrations.toList() }
        for (reg in snapshot) {
            if (reg.handler != null) {
                reg.handler.post { reg.observer.onLocationUpdateReceived(list) }
            } else {
                reg.observer.onLocationUpdateReceived(list)
            }
        }
    }

    override fun getLastLocation(callback: GetLocationCallback): Cancelable {
        // LocationController.state.value is nullable and Mapbox's own interface
        // gives us no "no fix yet" signal to hand back through GetLocationCallback -
        // its contract is Location, not Location?. A caller polling for the last fix
        // before one has ever arrived (app cold-start, no beacon connected yet) is
        // simply told nothing by never being called back, rather than being handed
        // a fabricated (0,0) that would silently look like a real position off the
        // coast of Africa.
        LocationController.state.value?.toMapboxLocation()?.let { callback.run(it) }
        // Nothing async is started, so there is nothing to cancel; a no-op Cancelable
        // still has to be a real object per the interface's non-null return type.
        return Cancelable { }
    }

    /**
     * We do not support the PendingIntent delivery path (background updates that
     * survive process death) - every consumer in this app reads
     * [LocationController.state] directly, which is exactly what this provider
     * mirrors, so there is no code that would ever receive that intent. Historically
     * `SecurityException` is this method's documented failure mode; the SDK's own
     * engine calls it internally during teardown paths we don't control, and CLAUDE.md
     * §14 blocks ADB logcat on this head unit - a thrown exception from inside the
     * SDK's call stack is a crash we would have no way to diagnose in the field. A
     * silent no-op is the safe failure: nothing is requested, nothing crashes.
     */
    override fun requestLocationUpdates(pendingIntent: PendingIntent) = Unit

    override fun removeLocationUpdates(pendingIntent: PendingIntent) = Unit

    override fun getName(): String = LocationController.BEACON_PROVIDER

    private fun AndroidLocation.toMapboxLocation(): MapboxLocation =
        MapboxLocation.Builder()
            .latitude(latitude)
            .longitude(longitude)
            .timestamp(time)
            // NANOSECONDS, not millis, despite the generic field name - Mapbox's own
            // LocationServiceUtils.toCommonLocation passes elapsedRealtimeNanos through
            // unscaled, and toAndroidLocation feeds it straight back into
            // setElapsedRealtimeNanos (verified against the common-ndk27 24.26.0
            // bytecode). Dividing here would have shrunk every value by 1e6, so the
            // SDK would read consecutive 1 Hz fixes as microseconds apart and compute
            // absurd speeds off them. `timestamp` above is the millis field.
            // LocationController stamps this on arrival with THIS device's clock (see
            // its acceptExternal doc), so it is meaningful regardless of source.
            .monotonicTimestamp(elapsedRealtimeNanos)
            // Android's Location getters return 0f/0.0 for fields that were never
            // set, not null - an unconditional read would tell Mapbox "0 m/s, due
            // north, dead accurate" for a fix that simply never carried that field
            // (e.g. a beacon FIX with an empty optional slot, see BeaconProtocol).
            // hasX() is what distinguishes absent from a genuine zero.
            .horizontalAccuracy(if (hasAccuracy()) accuracy.toDouble() else null)
            .speed(if (hasSpeed()) speed.toDouble() else null)
            .bearing(if (hasBearing()) bearing.toDouble() else null)
            .altitude(if (hasAltitude()) altitude else null)
            .source(provider)
            .build()

    /** Hands this singleton to `LocationOptions.Builder.locationProviderFactory`. */
    object Factory : DeviceLocationProviderFactory {
        override fun build(request: LocationProviderRequest?): Expected<LocationError, DeviceLocationProvider> =
            ExpectedFactory.createValue(BeaconLocationProvider)
    }
}
