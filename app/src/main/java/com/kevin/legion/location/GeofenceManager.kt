package com.kevin.legion.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.kevin.legion.data.local.TaggedPlace
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Registers [TaggedPlace] rows as real OS geofences (location-intelligence ticket 05, settled
 * decision 9), replacing [PlaceController]'s raw distance-on-GPS-poll math as the PRIMARY arrival
 * signal - event-driven, cheaper on battery, and (the whole point) it works with the app process
 * not running at all. The poll loop in `AriaForegroundService.startArrivalMonitor` is kept
 * running unconditionally as the fallback (ticket 05's hard rule: do not remove it in this
 * commit) - see [BackgroundLocationAccess] below for why a fallback still matters.
 *
 * ### Why nearest-N, not "register everything"
 *
 * Android hard-caps a single app at 100 active geofences (`GeofencingClient` throws past that).
 * [NEAREST_LIMIT] is set well below the cap so there is headroom for the cap to be real slack, not
 * a number a driver with a lot of saved places could actually hit. [nearest] is the pure selection
 * function - no `Context`, no Android types, just lat/lon math - specifically so it is unit
 * testable without Robolectric (CLAUDE.md's "run the real build" lesson only helps once there is a
 * real test to run).
 *
 * ### Why this re-registers on a timer instead of standing up its own location-triggered job
 *
 * The ticket names re-registration-as-he-moves as "itself a location-triggered job" to design
 * up front. `AriaForegroundService.startArrivalMonitor` already IS a location-triggered job - it
 * polls `LocationController.state` every 20s and was already the thing computing arrival. Piggy-
 * backing [registerNearest] on that existing cadence (see its call site) means no second timer,
 * and [registerNearest] itself is cheap: it diffs the newly-chosen set against
 * [lastRegisteredLabels] and only touches the delta, so calling it every 20s costs nothing on a
 * poll where the nearest-N set hasn't changed.
 *
 * ### Why no Room migration
 *
 * [TaggedPlace] already has everything a `Geofence` needs (label, latitude, longitude) - nothing
 * new is persisted. Which places are CURRENTLY registered as OS geofences is process-local,
 * in-memory state ([lastRegisteredLabels]), not a database fact - a fresh process (including after
 * a reboot) has registered nothing yet, which is exactly the state [com.kevin.legion.service.BootReceiver]
 * corrects by calling [registerNearest] again from scratch.
 */
object GeofenceManager {
    private const val TAG = "GeofenceManager"

    /** Sensibly below Android's 100-geofences-per-app cap (see class doc), not up against it. */
    const val NEAREST_LIMIT = 80

    /** Same radius [PlaceController] already uses for its own arrival match, so the two paths
     * agree on what "arrived" means rather than one being stricter than the other. */
    private const val GEOFENCE_RADIUS_M = 150f

    /** In-memory only, deliberately (see class doc's "why no Room migration"). Reset to empty on
     * every fresh process - the next [registerNearest] call re-derives it from scratch, which is
     * correct because a fresh process has registered nothing with the OS yet either. */
    @Volatile
    private var lastRegisteredLabels: Set<String> = emptySet()

    /**
     * Pure nearest-N selection - haversine distance, no Android `Location` type, so this is
     * testable in a plain JUnit test with no Robolectric shadow. [limit] is a parameter rather
     * than always [NEAREST_LIMIT] specifically so a test can exercise "more places than the cap"
     * without needing 81 fixture rows.
     */
    fun nearest(places: List<TaggedPlace>, lat: Double, lon: Double, limit: Int): List<TaggedPlace> =
        places.sortedBy { haversineMeters(lat, lon, it.latitude, it.longitude) }.take(limit)

    /** Great-circle distance in meters. Kept local (rather than `android.location.Location
     * .distanceBetween`, which [nearest] deliberately avoids) so the selection logic has zero
     * Android dependency. */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadiusM * c
    }

    private fun client(context: Context): GeofencingClient =
        LocationServices.getGeofencingClient(context.applicationContext)

    /** One `PendingIntent`, `FLAG_UPDATE_CURRENT` - re-registering replaces in place rather than
     * stacking, same shape as `AlarmScheduler.pendingIntentFor`. `FLAG_MUTABLE` is required here,
     * not merely permitted: the OS fills this intent in with the triggering geofence data before
     * delivering it, which `FLAG_IMMUTABLE` (LEGION's default elsewhere) would silently block. */
    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = GeofenceBroadcastReceiver.ACTION_GEOFENCE_EVENT
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /**
     * Registers the nearest [NEAREST_LIMIT] saved places to the current GPS fix as OS geofences,
     * removing any previously-registered place that fell out of that set this cycle. Called from
     * `AriaForegroundService.startArrivalMonitor`'s existing poll (re-registers "as he moves" -
     * decision 9) and from [com.kevin.legion.service.BootReceiver] (re-arms after a reboot, since
     * geofences do not survive one - same reasoning as `AlarmScheduler.rescheduleAll`).
     *
     * **Permission honesty (ticket 05 part E):** without [LocationAccessState.Granted]
     * (`ACCESS_BACKGROUND_LOCATION`), a geofence registers with the OS "successfully" and then
     * simply never fires once the app is backgrounded - Android does not error on this, it just
     * silently withholds delivery. Pretending to have registered here would be exactly the kind of
     * false success CLAUDE.md's outcome-verb rule exists to prevent, so this checks
     * [BackgroundLocationAccess.current] FIRST and backs off entirely (logged, not registered) when
     * it isn't Granted - leaving `PlaceController`'s GPS-poll distance math as the sole arrival
     * signal for that driver, unchanged from before this ticket, rather than a broken "primary"
     * path shadowing a working fallback.
     */
    @SuppressLint("MissingPermission") // gated on BackgroundLocationAccess.current below, not a raw permission check
    suspend fun registerNearest(context: Context) {
        val access = BackgroundLocationAccess.current(context)
        if (access != LocationAccessState.Granted) {
            Log.i(TAG, "skipping geofence registration: background location access is $access")
            return
        }

        val fix = LocationController.state.value
        if (fix == null) {
            Log.i(TAG, "skipping geofence registration: no GPS fix yet")
            return
        }

        val places = PlaceController.all(context)
        val chosen = nearest(places, fix.latitude, fix.longitude, NEAREST_LIMIT)
        val chosenLabels = chosen.map { it.label }.toSet()

        val geofenceClient = client(context)

        // addGeofences only ever adds or updates a requestId already registered - it never drops
        // one on its own. Without this explicit remove, a place that scrolled out of the
        // nearest-N as Kevin moved would stay registered forever (or the app would eventually hit
        // the 100 cap even though it only ever WANTS to hold NEAREST_LIMIT at a time).
        val toRemove = lastRegisteredLabels - chosenLabels
        if (toRemove.isNotEmpty()) {
            runCatching { geofenceClient.removeGeofences(toRemove.toList()).await() }
                .onFailure { Log.w(TAG, "failed to remove stale geofences $toRemove: ${it.message}") }
        }

        if (chosen.isEmpty()) {
            lastRegisteredLabels = emptySet()
            return
        }

        val geofences = chosen.map { place ->
            Geofence.Builder()
                .setRequestId(place.label)
                .setCircularRegion(place.latitude, place.longitude, GEOFENCE_RADIUS_M)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                // EXIT is free to add per the ticket - nothing downstream consumes it yet
                // ([GeofenceBroadcastReceiver] only acts on ENTER), but registering it now means a
                // future "left home" feature doesn't need a second registration pass.
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        val request = GeofencingRequest.Builder()
            // A driver already standing inside a place's radius when the nearest-N set is
            // (re)computed should still get the ENTER fire for it - without this trigger, arriving
            // BEFORE the app ever computed geofences for that place (e.g. it just became the
            // Nth-nearest as he approached a cluster of saved places) would be missed entirely.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        try {
            geofenceClient.addGeofences(request, pendingIntent(context)).await()
            lastRegisteredLabels = chosenLabels
        } catch (e: Exception) {
            // Registration failure (permission race, Play services down, etc.) - logged, not
            // thrown further. lastRegisteredLabels is deliberately left UNCHANGED on failure so
            // the next successful call still knows what it needs to diff against, rather than
            // assuming a failed add means nothing is registered.
            Log.w(TAG, "geofence registration failed: ${e.message}")
        }
    }

    /** Minimal Task -> suspend bridge, matching `DriveAuth`'s own (kotlinx-coroutines-play-services
     * is deliberately not a dependency here either - same reasoning, one extra artifact for one
     * awaited call is not worth it). */
    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(it) }
            addOnFailureListener { cont.resumeWithException(it) }
        }
}
