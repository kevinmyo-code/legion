package com.kevin.midnightai.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU-1 T5 voice: a saved place by its label, or none (never a guess). Made-up places. */
class NavVoiceTest {
    private val home = NavVoice.Place("home", GeoPoint(29.76, -95.37))
    private val work = NavVoice.Place("work", GeoPoint(29.74, -95.46))
    private val gym = NavVoice.Place("the gym", GeoPoint(29.70, -95.40))
    private val places = listOf(home, work, gym)

    @Test fun anExactLabelWins() = assertEquals(home, NavVoice.resolve("Home", places))

    @Test fun aLabelInsideTheWordsIsFound() {
        assertEquals(home, NavVoice.resolve("take me home", places))
        assertEquals(gym, NavVoice.resolve("navigate to the gym please", places))
    }

    @Test fun twoCandidatesOrNoneIsNull() {
        assertNull(NavVoice.resolve("home or work", places))
        assertNull(NavVoice.resolve("the airport", places))
        assertNull(NavVoice.resolve("homework", places))
    }

    @Test fun notFoundSaysOnlySavedPlacesAndNamesThem() {
        val words = NavVoice.notFound("the airport", places)
        assertTrue(words, words.contains("saved places") && words.contains("home") && words.contains("work"))
        assertTrue(NavVoice.notFound("x", emptyList()).contains("none are saved"))
    }
}
