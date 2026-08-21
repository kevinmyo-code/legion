package com.kevin.legion.location

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure fork test for `.scratch/location-intelligence/issues/01-background-location.md`'s
 * three-state rule - no Context, no Robolectric, because [resolveLocationAccess] takes plain
 * booleans (see its own doc comment on why the Android-touching read is a separate, thin wrapper).
 */
class BackgroundLocationAccessTest {
    @Test
    fun `background granted resolves to Granted regardless of the foreground flag`() {
        assertEquals(
            LocationAccessState.Granted,
            resolveLocationAccess(foregroundGranted = true, backgroundGranted = true),
        )
        // Real Android can never produce foreground=false/background=true (the OS enforces the
        // foreground-first grant order), but the resolver answers honestly off what it's given
        // rather than assuming that invariant - see resolveLocationAccess's own doc comment.
        assertEquals(
            LocationAccessState.Granted,
            resolveLocationAccess(foregroundGranted = false, backgroundGranted = true),
        )
    }

    @Test
    fun `foreground only, background refused or never asked, resolves to ForegroundOnly`() {
        assertEquals(
            LocationAccessState.ForegroundOnly,
            resolveLocationAccess(foregroundGranted = true, backgroundGranted = false),
        )
    }

    @Test
    fun `neither granted resolves to None`() {
        assertEquals(
            LocationAccessState.None,
            resolveLocationAccess(foregroundGranted = false, backgroundGranted = false),
        )
    }
}
