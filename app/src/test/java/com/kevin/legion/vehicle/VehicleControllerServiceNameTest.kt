package com.kevin.legion.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-function regression for the duplicate-row mechanism tickets 07/08 found:
 * `.scratch/fleet-maintenance/issues/07-hand-added-items-and-what-delete-means.md` and
 * `.scratch/fleet-maintenance/issues/08-matching-a-logged-service-to-an-item.md`.
 *
 * No Robolectric needed - [VehicleController.canonicalizeServiceName] and
 * [VehicleController.looksLikeExistingItem] are pure, `internal`, and take no
 * Context/DB (per CLAUDE.md sec 11's testing convention).
 */
class VehicleControllerServiceNameTest {

    // --- canonicalizeServiceName: the titlecase bug (ticket 07) -----------------------------

    /**
     * The bug, pinned exactly as ticket 07 described it: the OLD fallback was
     * `serviceName.trim().replaceFirstChar { it.titlecase() }`, which only
     * capitalises the FIRST character of the WHOLE string - so a hand-typed
     * "transfer case fluid" stored as "Transfer case fluid" while the LLM seed
     * independently produced "Transfer Case Fluid". Two different strings on
     * half of a composite primary key is two different rows for one real
     * service - the duplicate-row engine behind Kevin's 54-row schedule.
     */
    @Test
    fun `canonicalizeServiceName titlecases every word, not just the first character`() {
        assertEquals("Transfer Case Fluid", VehicleController.canonicalizeServiceName("transfer case fluid"))
        assertEquals("Transfer Case Fluid", VehicleController.canonicalizeServiceName("Transfer Case Fluid"))
        assertEquals("Differential Fluid", VehicleController.canonicalizeServiceName("differential fluid"))
        assertEquals("Pcv Valve", VehicleController.canonicalizeServiceName("pcv valve"))
    }

    /** Sanity: the 10-entry keyword table still wins over the titlecase fallback where it applies. */
    @Test
    fun `canonicalizeServiceName still matches the keyword table before falling back`() {
        assertEquals("Oil Change", VehicleController.canonicalizeServiceName("I just changed the oil"))
        assertEquals("Cabin Air Filter", VehicleController.canonicalizeServiceName("cabin air filter"))
        // Longest-match, not first-match (see the function's own doc) - "cabin
        // air filter" must not fall through to the bare "air filter" keyword.
        assertEquals("Air Filter", VehicleController.canonicalizeServiceName("engine air filter"))
    }

    // --- looksLikeExistingItem: comparator, never a rewriter (ticket 07) --------------------

    @Test
    fun `looksLikeExistingItem returns the colliding EXISTING name verbatim, never a rewritten typed name`() {
        val existing = listOf("Oil Change", "Brake Pads")

        // Case- and canonicalisation-insensitive collision.
        val collision = VehicleController.looksLikeExistingItem("oil change", existing)

        assertEquals("Oil Change", collision)
    }

    @Test
    fun `looksLikeExistingItem catches the exact titlecase-bug collision`() {
        // The hand-typed name that used to silently create a duplicate row
        // (ticket 07's finding) must now be caught as a collision against the
        // canonical form already on file.
        val existing = listOf("Transfer Case Fluid")

        assertEquals("Transfer Case Fluid", VehicleController.looksLikeExistingItem("transfer case fluid", existing))
    }

    @Test
    fun `looksLikeExistingItem returns null when nothing collides`() {
        val existing = listOf("Oil Change", "Brake Pads")

        assertNull(VehicleController.looksLikeExistingItem("Transmission Fluid Flush", existing))
    }

    @Test
    fun `looksLikeExistingItem never rewrites - storage stays verbatim by construction`() {
        // This test documents the contract by exercising it: the function
        // returns only a name FROM existingNames (or null), never a
        // transformation of the typed argument - so a caller storing the
        // TYPED name verbatim (ticket 07 decision 2) can never be tricked into
        // storing something else by calling this comparator. The existing name
        // is deliberately a weird hand-typed original (mixed case, not the
        // titlecase form the fallback would produce) to prove the function
        // returns exactly THAT string back, not a canonicalised version of it.
        val handTypedOriginal = "diFFerential FLUID"
        val existing = listOf(handTypedOriginal)

        val collision = VehicleController.looksLikeExistingItem("Differential Fluid", existing)

        assertEquals(
            "The comparator must return the EXISTING string verbatim, never a canonicalised or rewritten form",
            handTypedOriginal,
            collision,
        )
    }
}
