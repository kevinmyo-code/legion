package com.kevin.legion.location

import com.kevin.legion.data.local.TaggedPlace
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure selection-logic test for `.scratch/location-intelligence/issues/05-geofences.md` part B -
 * [GeofenceManager.nearest] takes plain lat/lon and a [TaggedPlace] list, no Context, no
 * `android.location.Location`, so this runs as a plain JUnit test with no Robolectric shadow,
 * same shape as [BackgroundLocationAccessTest].
 */
class GeofenceManagerTest {
    private fun place(label: String, lat: Double, lon: Double) =
        TaggedPlace(label = label, latitude = lat, longitude = lon, timestamp = 0L)

    // Houston-ish reference point, with a handful of places at increasing distance north.
    private val origin = place("origin", 29.7604, -95.3698)

    @Test
    fun `returns places ordered nearest first`() {
        val near = place("near", 29.7614, -95.3698)   // ~111m north
        val mid = place("mid", 29.7704, -95.3698)      // ~1.1km north
        val far = place("far", 30.7604, -95.3698)      // ~111km north

        val result = GeofenceManager.nearest(listOf(far, near, mid), origin.latitude, origin.longitude, limit = 10)

        assertEquals(listOf("near", "mid", "far"), result.map { it.label })
    }

    @Test
    fun `caps at the limit when there are more places than the cap`() {
        // 150 places at strictly increasing distance north - well over any sane cap, including
        // GeofenceManager's own NEAREST_LIMIT, to exercise the actual "more than the cap" case
        // the ticket calls out by name.
        val places = (0 until 150).map { i -> place("place_$i", 29.7604 + i * 0.01, -95.3698) }

        val result = GeofenceManager.nearest(places, origin.latitude, origin.longitude, limit = 80)

        assertEquals(80, result.size)
        // The nearest 80 are place_0..place_79, since distance increases monotonically with i.
        assertEquals((0 until 80).map { "place_$it" }, result.map { it.label })
    }

    @Test
    fun `limit above the available count returns everything, still ordered`() {
        val a = place("a", 29.7704, -95.3698)
        val b = place("b", 29.7614, -95.3698)

        val result = GeofenceManager.nearest(listOf(a, b), origin.latitude, origin.longitude, limit = 80)

        assertEquals(listOf("b", "a"), result.map { it.label })
    }

    @Test
    fun `empty place list returns empty regardless of limit`() {
        assertEquals(emptyList<TaggedPlace>(), GeofenceManager.nearest(emptyList(), origin.latitude, origin.longitude, limit = 80))
    }
}
