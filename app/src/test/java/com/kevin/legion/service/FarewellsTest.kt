package com.kevin.legion.service

import com.kevin.legion.service.WakePhrasesFarewellTest.Companion.FAREWELLS
import com.kevin.legion.service.WakePhrasesFarewellTest.Companion.NAME
import com.kevin.legion.service.WakePhrasesFarewellTest.Companion.REQUESTS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Farewells]: a goodbye said as the whole turn is one; a request that contains one is not. Invented utterances. */
class FarewellsTest {

    @Test
    fun `a goodbye said as the whole turn is a farewell`() {
        for (u in FAREWELLS) assertTrue(u, Farewells.isFarewell(u, NAME))
    }

    @Test
    fun `a request that only contains a farewell is not`() {
        for (u in REQUESTS) assertFalse(u, Farewells.isFarewell(u, NAME))
    }

    @Test
    fun `after a question a lone night or later is his answer, not a goodbye`() {
        for (u in listOf("night", "Later.", "nite")) {
            assertTrue(u, Farewells.isFarewell(u, NAME))
            assertFalse(u, Farewells.isFarewell(u, NAME, afterQuestion = true))
        }
        assertTrue(Farewells.isFarewell("good night", NAME, afterQuestion = true))
    }
}
