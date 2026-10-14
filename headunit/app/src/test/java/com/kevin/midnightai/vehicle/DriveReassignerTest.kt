package com.kevin.midnightai.vehicle

import com.kevin.midnightai.data.local.DriveReassignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure reassignment planner (car manager, 2026-07-16).
 *
 * The ordering cases are the point. These rules are re-applied on every sync pass,
 * so a planner that got the order wrong wouldn't just produce one bad answer - it
 * would produce a DIFFERENT bad answer every sync, which is the worst possible
 * failure for a data-correction feature.
 */
class DriveReassignerTest {

    private fun rule(
        from: String,
        to: String,
        fromMs: Long = 100,
        toMs: Long = 200,
        updatedAt: Long = 0,
    ) = DriveReassignment(
        syncId = "r-$from-$to-$updatedAt",
        vehicleId = from,
        fromMs = fromMs,
        toMs = toMs,
        newVehicleId = to,
        updatedAt = updatedAt,
    )

    @Test
    fun `single rule becomes one move`() {
        val moves = DriveReassigner.plan(listOf(rule("xj", "outlander", updatedAt = 5)))
        assertEquals(1, moves.size)
        assertEquals(DriveReassigner.Move("xj", "outlander", 100, 200), moves[0])
    }

    @Test
    fun `empty rules produce no moves`() {
        assertTrue(DriveReassigner.plan(emptyList()).isEmpty())
    }

    @Test
    fun `rules apply oldest first so a later correction wins`() {
        // Driver moved it to the Outlander, then realised it was the truck.
        val rules = listOf(
            rule("outlander", "truck", updatedAt = 20),
            rule("xj", "outlander", updatedAt = 10),
        )
        val moves = DriveReassigner.plan(rules)
        assertEquals(listOf("xj", "outlander"), moves.map { it.fromVehicleId })
        assertEquals(listOf("outlander", "truck"), moves.map { it.toVehicleId })
    }

    @Test
    fun `chained rules resolve to the final car`() {
        // A -> B at t=1 then B -> C at t=2 must leave the rows on C. Applied out of
        // order the second rule finds nothing on B and the drive strands there.
        val rules = listOf(
            rule("b", "c", updatedAt = 2),
            rule("a", "b", updatedAt = 1),
        )
        val moves = DriveReassigner.plan(rules)
        assertEquals(DriveReassigner.Move("a", "b", 100, 200), moves[0])
        assertEquals(DriveReassigner.Move("b", "c", 100, 200), moves[1])
    }

    @Test
    fun `self-moves are dropped`() {
        // A rule pointing a car at itself is a no-op that would otherwise re-run on
        // every sync pass forever.
        assertTrue(DriveReassigner.plan(listOf(rule("xj", "xj"))).isEmpty())
    }

    @Test
    fun `distinct drive windows stay independent`() {
        val rules = listOf(
            rule("xj", "outlander", fromMs = 100, toMs = 200, updatedAt = 1),
            rule("xj", "truck", fromMs = 5000, toMs = 6000, updatedAt = 2),
        )
        val moves = DriveReassigner.plan(rules)
        assertEquals(2, moves.size)
        assertEquals(100, moves[0].fromMs)
        assertEquals(5000, moves[1].fromMs)
    }

    @Test
    fun `equal clocks keep a stable relative order`() {
        // sortedBy is stable, so two rules stamped in the same millisecond apply in
        // insertion order rather than flipping between sync passes.
        val rules = listOf(
            rule("a", "b", updatedAt = 7),
            rule("c", "d", updatedAt = 7),
        )
        val moves = DriveReassigner.plan(rules)
        assertEquals(listOf("a", "c"), moves.map { it.fromVehicleId })
    }
}
