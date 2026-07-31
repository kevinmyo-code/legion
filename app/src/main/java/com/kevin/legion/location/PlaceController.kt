package com.kevin.legion.location

import android.content.Context
import android.location.Location
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace

object PlaceController {
    private const val MATCH_RADIUS_M = 150f

    /**
     * Tags the current GPS location under [rawLabel] (normalized). Returns a spoken ack.
     *
     * Address-based tagging (resolving a spoken address via geocoding) was dropped along
     * with embedded nav - it depended on Mapbox's geocoding API, which the new app doesn't
     * carry a dependency on. Only "tag where I am right now" is supported.
     */
    suspend fun tagPlace(context: Context, rawLabel: String): String {
        val label = normalizeLabel(rawLabel)
            ?: return "I didn't catch what to call this spot — try something like 'home' or 'work'."

        val loc = LocationController.state.value
            ?: return "I don't have a GPS lock yet, so I can't pin this spot. Give it a sec and try again."

        CarDatabase.getDatabase(context).placeDao().upsert(
            TaggedPlace(
                label = label,
                latitude = loc.latitude,
                longitude = loc.longitude,
                timestamp = System.currentTimeMillis(),
            )
        )
        return ackFor(label)
    }

    /** Deletes the saved place matching [rawLabel]. Returns a spoken ack, or an error if not found. */
    suspend fun forgetPlace(context: Context, rawLabel: String): String {
        val label = normalizeLabel(rawLabel) ?: return "I'm not sure which place you mean."
        val dao = CarDatabase.getDatabase(context).placeDao()
        if (dao.getAll().none { it.label == label }) return "I don't have a saved place called \"$label\"."
        dao.delete(label)
        return forgetAck(label)
    }

    /** Deletes a saved place by label (used by the UI list). */
    suspend fun forget(context: Context, label: String) {
        CarDatabase.getDatabase(context).placeDao().delete(label)
    }

    /** All saved places (used by the UI list). */
    suspend fun all(context: Context): List<TaggedPlace> =
        CarDatabase.getDatabase(context).placeDao().getAll()

    /**
     * The label of the saved place the driver is currently within
     * [MATCH_RADIUS_M] of (nearest wins), or null.
     */
    suspend fun currentLabel(context: Context): String? {
        val loc = LocationController.state.value ?: return null
        return CarDatabase.getDatabase(context).placeDao().getAll()
            .map { it to distanceTo(loc, it) }
            .filter { it.second <= MATCH_RADIUS_M }
            .minByOrNull { it.second }
            ?.first?.label
    }

    private fun distanceTo(from: Location, place: TaggedPlace): Float {
        val out = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, place.latitude, place.longitude, out)
        return out[0]
    }

    private fun normalizeLabel(raw: String): String? {
        var s = raw.lowercase()
            .replace(Regex("\\bby the way\\b"), " ")
            .replace(Regex("\\b(location|place|spot|address)\\b"), " ")
            .replace(Regex("[.!?,]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        s = s.removePrefix("my ").removePrefix("the ").removePrefix("a ").trim()
        if (s.isBlank() || s.length > 30) return null

        return when (s) {
            "work", "office", "job", "where i work" -> "work"
            "home", "house", "where i live", "live" -> "home"
            else -> s
        }
    }

    private fun ackFor(label: String): String {
        val where = if (label == "home" || label == "work") label else "\"$label\""
        return listOf(
            "Got it. This is $where now. Filed away with the rest of my baggage.",
            "Noted... $where, right here. I'll remember, don't you worry.",
            "Fine, $where it is. Pinned it.",
        ).random()
    }

    private fun forgetAck(label: String): String {
        val where = if (label == "home" || label == "work") label else "\"$label\""
        return listOf(
            "Done. Wiped $where off my map. One less thing rattling around back here.",
            "Forgotten. $where? Never heard of it.",
            "Gone. $where's off the books.",
        ).random()
    }
}
