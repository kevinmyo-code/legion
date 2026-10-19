package com.kevin.legion.games.tetris

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 04's rule vectors, read from the ONE file the PWA's vitest also reads
 * (`shared/tetris/fixtures.json`). The file is found by walking up from the test's working
 * directory (Gradle runs unit tests from `app/`), so it works from the repo root or the module.
 */
class TetrisFixturesTest {
    private val root: JsonObject = Json.parseToJsonElement(fixtureFile().readText()).jsonObject

    private fun fixtureFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared/tetris/fixtures.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("shared/tetris/fixtures.json not found above ${System.getProperty("user.dir")}")
    }

    private fun list(key: String): JsonArray = root.getValue(key).jsonArray

    // seeds go past Int.MAX_VALUE (uint32), so they are read as Long.
    @Test
    fun rngFirstFloatsMatch() {
        val vectors = list("rng")
        assertTrue(vectors.isNotEmpty())
        for (v in vectors) {
            val o = v.jsonObject
            val rng = Mulberry32(o.getValue("seed").jsonPrimitive.long)
            for (expected in o.getValue("firstFloats").jsonArray) {
                assertEquals("seed ${o.getValue("seed")}", expected.jsonPrimitive.double, rng.next(), 1e-12)
            }
        }
    }

    @Test
    fun bagSequencesMatch() {
        val vectors = list("bags")
        assertTrue(vectors.isNotEmpty())
        for (v in vectors) {
            val o = v.jsonObject
            val expected = o.getValue("pieces").jsonArray.map { it.jsonPrimitive.content }
            val bag = SevenBag(o.getValue("seed").jsonPrimitive.long)
            val actual = List(expected.size) { bag.take().name }
            assertEquals("seed ${o.getValue("seed")}", expected, actual)
        }
    }

    @Test
    fun peekDoesNotConsume() {
        val bag = SevenBag(42)
        val previewed = bag.peek(5)
        assertEquals(previewed, List(5) { bag.take() })
    }

    @Test
    fun scoreVectorsMatch() {
        val vectors = list("score")
        assertTrue(vectors.isNotEmpty())
        for (v in vectors) {
            val o = v.jsonObject
            val actual = Scoring.points(
                o.getValue("level").jsonPrimitive.int,
                o.getValue("linesCleared").jsonPrimitive.int,
                o.getValue("softDropCells").jsonPrimitive.int,
                o.getValue("hardDropCells").jsonPrimitive.int,
            )
            assertEquals("score vector $o", o.getValue("points").jsonPrimitive.int, actual)
        }
    }

    @Test
    fun levelVectorsMatch() {
        for (v in list("level")) {
            val o = v.jsonObject
            assertEquals(
                o.getValue("level").jsonPrimitive.int,
                Scoring.level(o.getValue("totalLines").jsonPrimitive.int),
            )
        }
    }

    @Test
    fun gravityVectorsMatch() {
        val vectors = list("gravity")
        assertTrue(vectors.isNotEmpty())
        for (v in vectors) {
            val o = v.jsonObject
            assertEquals(
                o.getValue("secondsPerRow").jsonPrimitive.double,
                Scoring.secondsPerRow(o.getValue("level").jsonPrimitive.int),
                1e-9,
            )
        }
    }
}
