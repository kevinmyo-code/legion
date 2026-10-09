package com.kevin.legion.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One address the geocoder resolved: the human string to store and speak, and its point. */
data class GeocodedAddress(val address: String, val latitude: Double, val longitude: Double)

/** What a forward lookup ("123 Main St, Katy") came back with. */
sealed interface ForwardLookup {
    /** Several results, distinct addresses. Empty never happens; that is [NotFound]. */
    data class Found(val results: List<GeocodedAddress>) : ForwardLookup

    data object NotFound : ForwardLookup

    /** No answer at all - offline, no geocoding service on this phone. [why] is said in words. */
    data class Unavailable(val why: String) : ForwardLookup
}

/** What a reverse lookup (a point to its address) came back with. */
sealed interface ReverseLookup {
    data class Found(val address: String) : ReverseLookup

    data object NotFound : ReverseLookup

    data class Unavailable(val why: String) : ReverseLookup
}

/**
 * Address lookups for saved places (Kevin, 2026-10-09: "save this place, its address is X").
 *
 * **Why Android's own `Geocoder` and not the Mapbox Search SDK the nav screen uses.** Mapbox's
 * Product Terms 2.7.2 forbid storing a temporary geocode, and Search Box results are temporary
 * only; `.scratch/mapbox-nav/issues/03-resolving-a-spoken-destination.md` ruled on exactly this
 * ("Nothing from Mapbox is stored ... 'Save this search result as a place' would need permanent
 * geocoding"). A saved place IS a stored geocode, so it cannot come from Mapbox without permanent
 * geocoding. `android.location.Geocoder` is what `get_current_location`, `AreaInfo` and
 * `CrimeHistory` already use, needs no token, and carries no storage clause in its API.
 *
 * A seam so the decisions in [PlaceController] are tested with a fake; [AndroidPlaceGeocoder] is
 * the one real implementation.
 */
interface PlaceGeocoder {
    suspend fun forward(query: String): ForwardLookup

    suspend fun reverse(latitude: Double, longitude: Double): ReverseLookup
}

/** [PlaceGeocoder] over `android.location.Geocoder`. Blocking calls run on [Dispatchers.IO]. */
class AndroidPlaceGeocoder(context: Context) : PlaceGeocoder {
    private val app = context.applicationContext

    override suspend fun forward(query: String): ForwardLookup {
        if (!Geocoder.isPresent()) return ForwardLookup.Unavailable(NO_SERVICE)
        return withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val found = Geocoder(app, Locale.getDefault()).getFromLocationName(query, MAX_RESULTS).orEmpty()
                val results = found.mapNotNull { it.toGeocoded() }.distinctBy { it.address }
                if (results.isEmpty()) ForwardLookup.NotFound else ForwardLookup.Found(results)
            } catch (e: IOException) {
                ForwardLookup.Unavailable(unreachable(e))
            } catch (e: IllegalArgumentException) {
                ForwardLookup.Unavailable("the address lookup refused that text (${e.message})")
            }
        }
    }

    override suspend fun reverse(latitude: Double, longitude: Double): ReverseLookup {
        if (!Geocoder.isPresent()) return ReverseLookup.Unavailable(NO_SERVICE)
        return withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val first = Geocoder(app, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
                    ?.firstOrNull()
                    ?.let(::formatAddress)
                if (first == null) ReverseLookup.NotFound else ReverseLookup.Found(first)
            } catch (e: IOException) {
                ReverseLookup.Unavailable(unreachable(e))
            } catch (e: IllegalArgumentException) {
                ReverseLookup.Unavailable("the address lookup refused that point (${e.message})")
            }
        }
    }

    private fun Address.toGeocoded(): GeocodedAddress? =
        formatAddress(this)
            ?.takeIf { hasLatitude() && hasLongitude() }
            ?.let { GeocodedAddress(it, latitude, longitude) }

    private fun unreachable(e: IOException): String =
        "the address lookup couldn't be reached (${e.message ?: "no connection"})"

    private companion object {
        const val MAX_RESULTS = 5
        const val NO_SERVICE = "this phone has no address lookup service"
    }
}

/**
 * The one-line address of an [Address], or null when it has none. The full first address line
 * when the geocoder gives one ("123 Main St, Katy, TX 77494, USA"), otherwise the parts it does
 * have. A trailing country is dropped for a US address: "USA" read aloud after every place is
 * noise. Never invents a part the geocoder did not return.
 */
internal fun formatAddress(a: Address): String? {
    val line = a.getAddressLine(0)?.trim()?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(
            listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").ifBlank { null },
            a.locality,
            listOfNotNull(a.adminArea, a.postalCode).joinToString(" ").ifBlank { null },
        ).joinToString(", ").ifBlank { null }
        ?: return null
    return if (a.countryCode == "US") line.removeSuffix(", USA").removeSuffix(", United States") else line
}

/**
 * Turns a forward lookup into one address to save, or the reason there is not one. Pure, so
 * every branch is tested without a geocoder.
 *
 * - One distinct result: that one.
 * - Several: one of them only when its address IS what was asked for (case and punctuation
 *   aside) - which is how a second call naming one of the offered candidates lands on it.
 *   Otherwise several plausible places, and nothing is chosen.
 */
object AddressChoice {
    sealed interface Decision {
        data class One(val address: GeocodedAddress) : Decision

        data class Several(val candidates: List<GeocodedAddress>) : Decision
    }

    fun decide(query: String, results: List<GeocodedAddress>): Decision {
        val distinct = results.distinctBy { it.address }
        if (distinct.size == 1) return Decision.One(distinct.single())
        val wanted = squash(query)
        val exact = distinct.filter { squash(it.address) == wanted }
        return if (exact.size == 1) Decision.One(exact.single()) else Decision.Several(distinct)
    }

    private fun squash(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
}
