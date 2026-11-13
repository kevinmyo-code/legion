package com.kevin.legion.greeting

import android.content.Context
import android.location.Geocoder
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What reverse-geocoding a fix to a city or area came back with. Never a timezone id. */
sealed interface AreaLookup {
    data class Found(val name: String) : AreaLookup
    data object NotFound : AreaLookup
    data class Unavailable(val why: String) : AreaLookup
}

/** The phone-side inputs to the place source, behind a seam so every state is tested with a fake. */
interface PlaceSignals {
    /** The current fix as latitude and longitude, or null when the phone has none. */
    fun fix(): Pair<Double, Double>?

    suspend fun area(latitude: Double, longitude: Double): AreaLookup

    /** True within a saved place labelled home, false inside any other or none, null when no home is saved. */
    suspend fun atHome(): Boolean?
}

/**
 * The phone's own source in the greeting pack (ticket 01): where he is, when that is somewhere other
 * than home. Same three states as the engine's sources, with the same rule: no fix, or a fix that
 * could not be named, is UNREADABLE ("I do not know where he is"), never EMPTY and never a guess.
 *
 * The topic key is `place:<area>`, so having raised or silenced a city is remembered per city, and
 * the engine's `closed` list carries the phone's keys back (`server/greeting/facts.py`, "closed").
 */
object GreetingPlace {
    const val SOURCE = "place"
    private const val KEY_PREFIX = "place:"

    const val NO_FIX =
        "The phone has no location fix, so where he is is unknown. Do not guess a city or say he is " +
            "away or at home."

    suspend fun read(signals: PlaceSignals): GreetingSource {
        val fix = signals.fix() ?: return unreadable(NO_FIX)
        val area = try {
            signals.area(fix.first, fix.second)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            AreaLookup.Unavailable(e.javaClass.simpleName)
        }
        return when (area) {
            is AreaLookup.Found -> located(area.name, signals.atHome())
            AreaLookup.NotFound -> unreadable(
                "The phone has a fix but could not name the area, so where he is is unknown. Do not guess.",
            )
            is AreaLookup.Unavailable -> unreadable(
                "The phone has a fix but the area lookup failed (${area.why}), so where he is is unknown. " +
                    "Do not guess.",
            )
        }
    }

    private fun located(name: String, atHome: Boolean?): GreetingSource {
        val topic = "$KEY_PREFIX${name.lowercase(Locale.ROOT)}"
        return when (atHome) {
            true -> GreetingSource(SOURCE, SourceState.EMPTY, "He is at home, so there is no place to mention.")
            false -> GreetingSource(
                SOURCE, SourceState.FACTS, "He is away from home.",
                items = listOf(GreetingItem(topic, "He is in $name, away from home.", kind = "place")),
            )
            // No saved home: away-ness is unknown, so only the area is a fact.
            null -> GreetingSource(
                SOURCE, SourceState.FACTS, "No home place is saved, so whether he is away is unknown.",
                items = listOf(
                    GreetingItem(
                        topic,
                        "He is in $name. No home place is saved, so do not say he is away from home.",
                        kind = "place",
                    ),
                ),
            )
        }
    }

    /** A place source that failed to read at all (a thrown error, a timeout): unreadable, in words. */
    fun failed(why: String): GreetingSource =
        unreadable("The place could not be read ($why), so where he is is unknown.")

    private fun unreadable(words: String) = GreetingSource(SOURCE, SourceState.UNREADABLE, words, reason = words)

    /** Applies the pack's own memory to the phone's source: an item raised or closed is marked or dropped. */
    fun applyTopics(source: GreetingSource, said: Set<String>, closed: Set<String>): GreetingSource {
        if (source.state != SourceState.FACTS) return source
        val kept = source.items.filter { it.topic !in closed }.map { it.copy(raised = it.topic in said) }
        return if (kept.isEmpty()) {
            GreetingSource(
                SOURCE, SourceState.EMPTY,
                "He answered or silenced the place topic already; nothing new to raise.",
            )
        } else {
            source.copy(items = kept)
        }
    }
}

/** [PlaceSignals] over [LocationController], Android's `Geocoder` and the saved places. */
class AndroidPlaceSignals(context: Context) : PlaceSignals {
    private val app = context.applicationContext

    override fun fix(): Pair<Double, Double>? =
        LocationController.state.value?.let { it.latitude to it.longitude }

    override suspend fun area(latitude: Double, longitude: Double): AreaLookup {
        if (!Geocoder.isPresent()) return AreaLookup.Unavailable("this phone has no address lookup service")
        return withContext(Dispatchers.IO) {
            try {
                // Same call AriaBrain.geocodeArea makes; the async overload needs an API 33 callback.
                @Suppress("DEPRECATION")
                val first = Geocoder(app, Locale.getDefault()).getFromLocation(latitude, longitude, 1)?.firstOrNull()
                val name = first?.let { listOfNotNull(it.locality, it.adminArea).joinToString(", ").ifBlank { null } }
                if (name == null) AreaLookup.NotFound else AreaLookup.Found(name)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                AreaLookup.Unavailable(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    override suspend fun atHome(): Boolean? {
        val places = PlaceController.all(app)
        if (places.none { it.label == HOME }) return null
        return PlaceController.currentLabel(app) == HOME
    }

    private companion object {
        const val HOME = "home"
    }
}
