package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.location.AreaInfo
import com.kevin.legion.location.CrimeHistory
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.vehicle.DtcClearController
import com.kevin.legion.vehicle.GarageController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The place tools (`get_current_location`, `area_info`, `get_reported_crime_history`, `tag_place`,
 * `rename_place`, `forget_place`, and the four navigation tools), split out of [LiveToolbox] (shrink
 * pass, 2026-11-22) along the [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch] runs its
 * guards first, then asks this object, which answers the names it owns and returns null for every
 * other. [resolveCurrentLocation] is also what the screens read, so voice and hands share one
 * decision.
 */
object PlacesToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "get_current_location" -> getCurrentLocation(context)
        "area_info" -> areaInfo(context, args)
        "get_reported_crime_history" -> getReportedCrimeHistory(context, args)
        "navigate", "change_trip", "trip_status", "end_trip" -> navVoiceTool(context, name, args)
        "tag_place" -> tagPlace(context, args)
        "rename_place" ->
            PlaceController.renamePlace(context, args.optString("from"), args.optString("to"))
                .let { result(it.success, it.message) }
        "forget_place" ->
            PlaceController.forgetPlace(context, args.optString("label"), args.optBoolean("confirmed", false))
                .let { result(it.success, it.message) }
        else -> null
    }

    /**
     * Dispatches the clear_codes voice tool - a thin Context/JSONObject wrapper around
     * [DtcClearController.dispatchAndRecord], which owns the whole snapshot/send/re-read/record
     * transaction and the confirm gate (unit-tested directly, no Context needed there - see
     * DtcClearControllerTest). `success` follows [GarageController]'s own convention for a
     * destructive write tool: true only when the physical action actually completed as asked
     * (CLEARED), false for every other outcome including the confirm-prompt turn itself - the
     * driver-facing wording is always [message], never inferred from this flag.
     */
    /**
     * `tag_place` over [PlaceController.savePlace] - the same function the Saved places screen's
     * save buttons call (ADR 0035). `success` is true only when a place was written; the confirm
     * and pick-one turns are false with the sentence to recite, `clear_codes`'s convention. The
     * candidates ride as a list as well as in the sentence, so the model can name one exactly.
     */
    private suspend fun tagPlace(context: Context, args: JSONObject): JSONObject {
        val outcome = PlaceController.savePlace(
            context,
            args.optString("label"),
            args.optString("address", "").ifBlank { null },
            args.optBoolean("confirmed", false),
        )
        val out = result(outcome.success, outcome.message)
        if (outcome is PlaceController.SaveOutcome.Choose) {
            out.put("candidates", org.json.JSONArray(outcome.candidates.map { it.address }))
        }
        if (outcome is PlaceController.SaveOutcome.NeedsConfirm) out.put("needsConfirmation", true)
        return out
    }

    /**
     * The permission/providers-off/no-fix/available branch [getCurrentLocation] decides on, lifted
     * out to its own type (command-center ticket 08, drift-debt half) because
     * `ui/FleetScreen.kt`'s `currentLocationReadout` used to RESTATE this exact branch order in a
     * second private function - its own doc comment flagged the drift risk explicitly, since that
     * file has no business reaching into a `private` LiveToolbox function. Now there is one
     * decision (this function) and two renderers: [getCurrentLocation] turns it into the tool's
     * spoken sentences, `FleetScreen.currentLocationReadout` turns the SAME value into its
     * "Current location: ..." line. `internal` so the screen can call it directly.
     */
    internal sealed class LocationReadout {
        /** A live fix exists. [label] is the best-effort reverse-geocoded street/city/state,
         * null if the geocoder is unavailable or returned nothing - never a reason to fail the
         * whole readout, since [coords] alone is still an honest answer (CLAUDE.md §7's
         * degrade-in-words rule applied to a partial success, not just a total failure). [coords]
         * is the pre-formatted "(lat X, lng Y)" clause the spoken sentence uses; [lat]/[lon] are
         * the same two numbers un-formatted, added for command-center ticket 08's `AreaCard` so it
         * can hand a real coordinate pair to [com.kevin.legion.location.AirNow] without parsing
         * [coords] back apart. */
        data class Available(val label: String?, val coords: String, val lat: Double, val lon: Double) : LocationReadout()

        /** LEGION itself was never granted location - the one actionable case; everything else
         * below is the phone's own settings, not something re-asking fixes. */
        object NoPermission : LocationReadout()

        /** Permission granted, but the driver (or a battery saver mode) has both GPS and network
         * location switched off system-wide - a different fix than "wait longer". */
        object ProvidersOff : LocationReadout()

        /** Permission granted, a provider is on, there's just no fix yet. */
        object NoFix : LocationReadout()
    }

    /**
     * Resolves [LocationController]'s live state into a [LocationReadout], reverse-geocoding via
     * Android's built-in `Geocoder` when a fix exists (needs a GMS geocoding backend on-device;
     * degrades to coordinates-only if unavailable, per CLAUDE.md sec 9's "network calls degrade
     * gracefully" rule). Never throws and never guesses - see [LocationReadout]'s own branch docs.
     */
    internal suspend fun resolveCurrentLocation(context: Context): LocationReadout {
        // init() is idempotent (see its own doc) and, critically, seeds state from
        // getLastKnownLocation synchronously - so a driver who just granted location in Android
        // Settings and comes straight back to a voice call gets an immediate answer instead of
        // waiting on the first live update. Before this call the ONLY caller of init() anywhere
        // in the app was AriaForegroundService.onCreate, so a permission granted after the
        // service already started did nothing until the service was recreated - traced via
        // `grep -rn "LocationController.init"`, one hit.
        LocationController.init(context)
        val loc = LocationController.state.value
            ?: return when {
                !LocationController.hasPermission(context) -> LocationReadout.NoPermission
                !LocationController.anyProviderEnabled(context) -> LocationReadout.ProvidersOff
                else -> LocationReadout.NoFix
            }

        val coords = "(lat ${loc.latitude}, lng ${loc.longitude})"
        val label = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                android.location.Geocoder(context, java.util.Locale.getDefault())
                    .getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()
                    ?.let { listOfNotNull(it.thoroughfare, it.locality, it.adminArea).joinToString(", ") }
                    ?.ifBlank { null }
            }.getOrNull()
        }
        return LocationReadout.Available(label, coords, loc.latitude, loc.longitude)
    }

    private suspend fun getCurrentLocation(context: Context): JSONObject =
        when (val readout = resolveCurrentLocation(context)) {
            // The one actionable case: LEGION itself was never granted location. Everything
            // else below is the phone's own settings, not something re-asking the tool fixes.
            LocationReadout.NoPermission -> result(success = false,
                message = "LEGION doesn't have location permission. Grant it in Android's " +
                    "app settings for LEGION and try again.")
            LocationReadout.ProvidersOff -> result(success = false,
                message = "Location services are switched off on the phone. Turn on GPS or " +
                    "network location and try again.")
            // The ONLY case the old blanket message was actually true for.
            LocationReadout.NoFix -> result(
                success = false,
                message = "I don't have a GPS fix yet. ${FieldToolbox.readout(context)}",
            )
            is LocationReadout.Available -> if (readout.label != null)
                result(success = true, message = "Current location: ${readout.label} ${readout.coords}. " +
                    FieldToolbox.readout(context))
            else
                result(success = true, message = "Current location: ${readout.coords} (couldn't resolve an address). " +
                    FieldToolbox.readout(context))
        }

    /**
     * The single "do we have an actual live GPS fix, with NO fallback" gate (location-intelligence
     * map decision 14) - shared by [areaInfo] and [getReportedCrimeHistory] so both tools' failure
     * sentences say exactly what [getCurrentLocation] already says for the same three cases, rather
     * than drifting into a slightly different wording per call site. Never touches a saved place or
     * a last-known position - a null [android.location.Location] here means "say so and check
     * nothing", not "guess".
     */
    private fun requireLiveFix(context: Context): Pair<android.location.Location?, JSONObject?> {
        LocationController.init(context)
        val loc = LocationController.state.value
        if (loc != null) return loc to null
        val failure = when {
            !LocationController.hasPermission(context) -> result(success = false,
                message = "LEGION doesn't have location permission. Grant it in Android's " +
                    "app settings for LEGION and try again.")
            !LocationController.anyProviderEnabled(context) -> result(success = false,
                message = "Location services are switched off on the phone. Turn on GPS or " +
                    "network location and try again.")
            else -> result(success = false, message = "I don't have a GPS fix yet.")
        }
        return null to failure
    }

    /**
     * `area_info` (location-intelligence ticket 02 §D): dispatches to [AreaInfo.fetch] for whatever
     * `category` the model asked for, after the shared [requireLiveFix] gate. An unrecognized
     * category string (a model hallucinating a fifth value outside the enum) fails in words rather
     * than silently defaulting to one of the four real ones.
     */
    private suspend fun areaInfo(context: Context, args: JSONObject): JSONObject {
        val category = when (args.optString("category").lowercase(java.util.Locale.US)) {
            "weather" -> AreaInfo.Category.WEATHER
            "quake" -> AreaInfo.Category.QUAKE
            "fire" -> AreaInfo.Category.FIRE
            "disaster" -> AreaInfo.Category.DISASTER
            else -> return result(success = false, message = "I don't know that area_info category.")
        }
        val (loc, failure) = requireLiveFix(context)
        if (loc == null) return failure!!
        val message = AreaInfo.fetch(context, category, loc.latitude, loc.longitude)
        return result(success = true, message = message)
    }

    /**
     * `get_reported_crime_history` (location-intelligence ticket 02 §E, map decision 7): dispatches
     * to [CrimeHistory.historyNear] after the same [requireLiveFix] gate `area_info` uses. Defaults
     * to violent-crime when the model omits `offense_type` - the more commonly asked-about family -
     * never to `is_area_safe`-shaped behaviour, which [CrimeHistory] does not implement at all.
     */
    private suspend fun getReportedCrimeHistory(context: Context, args: JSONObject): JSONObject {
        val (loc, failure) = requireLiveFix(context)
        if (loc == null) return failure!!
        val offenseType = when (args.optString("offense_type", "violent")) {
            "property" -> CrimeHistory.OffenseType.PROPERTY
            else -> CrimeHistory.OffenseType.VIOLENT
        }
        val place = args.optString("place").ifBlank { null }
        val message = CrimeHistory.historyNear(context, loc.latitude, loc.longitude, place, offenseType)
        return result(success = true, message = message)
    }

    /**
     * The four navigation tools (mapbox-nav tickets 04 and 11). The logic is [NavVoiceTools]; this
     * only parses arguments and maps its result. Run on Main because the Mapbox SDK and the
     * controller are main-thread only.
     */
    private suspend fun navVoiceTool(context: Context, name: String, args: JSONObject): JSONObject {
        val app = context.applicationContext as? com.kevin.legion.MidnightApplication
            ?: return result(false, "Navigation isn't available here. Nothing has started.")
        val tools = app.navVoiceTools
        val r = withContext(Dispatchers.Main) {
            when (name) {
                "navigate" -> tools.navigate(
                    destination = args.optString("destination"),
                    via = args.optString("via").ifBlank { null },
                    avoid = args.optString("avoid").ifBlank { null },
                    preview = args.optBoolean("preview", false),
                    choice = args.optInt("choice", 0).takeIf { it > 0 },
                )
                "change_trip" -> tools.changeTrip(
                    action = args.optString("action"),
                    place = args.optString("place").ifBlank { null },
                    avoid = args.optString("avoid").ifBlank { null },
                    route = args.optInt("route", 0).takeIf { it > 0 },
                    choice = args.optInt("choice", 0).takeIf { it > 0 },
                )
                "trip_status" -> tools.tripStatus(args.optString("ask"))
                else -> tools.endTrip()
            }
        }
        r.trip?.let(com.kevin.legion.assistant.cards.DomainToolCards::trip)
        return result(r.success, r.message)
    }
}
