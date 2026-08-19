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
        // These raw strings deliberately have NO SERVICE_KEYWORDS entry, so they exercise the
        // titlecase FALLBACK path this test is pinning. "transfer case fluid" and "pcv valve" used
        // to be the examples here, but ticket 14's review (BLOCKING 1b) gave both a real canonical
        // keyword entry - they now correctly hit the keyword table instead, and are covered by
        // `canonicalizeServiceName covers the factory items ticket 02 found with no canonical entry`
        // below, so this test moved to concepts still genuinely uncovered.
        assertEquals("Wheel Bearing Repack", VehicleController.canonicalizeServiceName("wheel bearing repack"))
        assertEquals("Wheel Bearing Repack", VehicleController.canonicalizeServiceName("Wheel Bearing Repack"))
        assertEquals("Timing Chain Tensioner", VehicleController.canonicalizeServiceName("timing chain tensioner"))
    }

    /** Sanity: the 17-entry keyword table still wins over the titlecase fallback where it applies. */
    @Test
    fun `canonicalizeServiceName still matches the keyword table before falling back`() {
        assertEquals("Oil Change", VehicleController.canonicalizeServiceName("I just changed the oil"))
        assertEquals("Cabin Air Filter", VehicleController.canonicalizeServiceName("cabin air filter"))
        // Longest-match, not first-match (see the function's own doc) - "cabin
        // air filter" must not fall through to the bare "air filter" keyword.
        assertEquals("Air Filter", VehicleController.canonicalizeServiceName("engine air filter"))
    }

    // --- canonicalizeServiceName: ticket 14 review's expanded keyword table (BLOCKING 1b) -------

    @Test
    fun `canonicalizeServiceName covers the factory items ticket 02 found with no canonical entry`() {
        assertEquals("Differential Fluid", VehicleController.canonicalizeServiceName("Drain and refill front and rear axles"))
        assertEquals("Transfer Case Fluid", VehicleController.canonicalizeServiceName("Drain and refill transfer case fluid"))
        assertEquals("Serpentine Belt", VehicleController.canonicalizeServiceName("Inspect drive belt, adjust tension as necessary"))
        assertEquals("Ignition Cables", VehicleController.canonicalizeServiceName("Replace ignition cables"))
        assertEquals("Chassis Lubrication", VehicleController.canonicalizeServiceName("Lubricate steering linkage"))
        assertEquals("Chassis Lubrication", VehicleController.canonicalizeServiceName("Lubricate steering and suspension ball joints"))
    }

    @Test
    fun `manual transmission fluid is a NEW entry that does not disturb the pre-existing automatic naming`() {
        // Longest-match must prefer the new, more specific entry for the manual case...
        assertEquals(
            "Manual Transmission Fluid",
            VehicleController.canonicalizeServiceName("Drain and refill manual transmission fluid"),
        )
        // ...while every string that used to land on "Transmission Fluid" still does, unchanged.
        assertEquals(
            "Transmission Fluid",
            VehicleController.canonicalizeServiceName("Drain and refill automatic transmission fluid"),
        )
        assertEquals("Transmission Fluid", VehicleController.canonicalizeServiceName("transmission fluid"))
    }

    // --- nearMissServiceName: the comparator for names OUTSIDE the keyword table (BLOCKING 1b) --

    @Test
    fun `nearMissServiceName catches two phrasings of the same job that share no keyword`() {
        val existing = listOf("Check The Wheel Alignment")

        assertEquals(
            "Check The Wheel Alignment",
            VehicleController.nearMissServiceName("Wheel Alignment Check", existing),
        )
    }

    @Test
    fun `nearMissServiceName returns null for genuinely unrelated single-word concepts`() {
        // "Belt" and "Battery" both lose their only significant word to no overlap at all - the
        // 0.5-of-the-smaller-side threshold must not let single-token names collide on nothing.
        assertNull(VehicleController.nearMissServiceName("Belt", listOf("Battery")))
    }

    @Test
    fun `nearMissServiceName returns null below the conservative overlap threshold`() {
        // Zero significant words shared - "replace"/"the" are stripped as maintenance-sentence
        // stopwords, leaving {front, bumper, cover} against {rear, differential, fluid}: no overlap
        // at all, nowhere near the 0.5-of-the-smaller-side bar.
        assertNull(
            VehicleController.nearMissServiceName(
                "Replace the front bumper cover",
                listOf("Rear differential fluid"),
            ),
        )
    }

    @Test
    fun `nearMissServiceName returns the EXISTING name verbatim, never a rewritten candidate`() {
        val handTyped = "wheel ALIGNMENT check (front end)"
        val collision = VehicleController.nearMissServiceName("Wheel Alignment Check", listOf(handTyped))

        assertEquals(handTyped, collision)
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
