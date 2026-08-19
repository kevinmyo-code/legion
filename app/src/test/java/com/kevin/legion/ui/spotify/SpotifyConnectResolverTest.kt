package com.kevin.legion.ui.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [SpotifyConnectResolver] - the Connect Spotify
 * screen's state derivation. No Android dependency and no App Remote aar on
 * the path, plain JVM test, same shape as `GoogleGrantResolverTest`.
 */
class SpotifyConnectResolverTest {

    // ------------------------------------------------------------------ stage

    @Test
    fun `no client id wins even when a refresh token is somehow on file`() {
        // Reachable, not hypothetical: saveSpotifyClientId clears the tokens when the ID
        // CHANGES, but saving a blank ID drops the ID while leaving tokens minted for the old one.
        assertEquals(
            SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID,
            SpotifyConnectResolver.stage(hasClientId = false, isAuthorized = true),
        )
        assertEquals(
            SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID,
            SpotifyConnectResolver.stage(hasClientId = false, isAuthorized = false),
        )
    }

    @Test
    fun `client id saved but not authorized is the half-set-up stage`() {
        assertEquals(
            SpotifyConnectResolver.Stage.NEEDS_AUTHORIZATION,
            SpotifyConnectResolver.stage(hasClientId = true, isAuthorized = false),
        )
    }

    @Test
    fun `both grants present reads as ready`() {
        assertEquals(
            SpotifyConnectResolver.Stage.READY,
            SpotifyConnectResolver.stage(hasClientId = true, isAuthorized = true),
        )
    }

    @Test
    fun `a stale grant reads as needing reauthorization, not the same stage as never-authorized`() {
        // The whole point of ticket 05's addition: a driver who connected BEFORE the scopes
        // widened must not see the same copy as a driver who never connected at all.
        assertEquals(
            SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION,
            SpotifyConnectResolver.stage(hasClientId = true, isAuthorized = false, hasStaleGrant = true),
        )
    }

    @Test
    fun `ready wins over a stale-grant flag that should never be true alongside it`() {
        // isAuthorized already implies the grant is CURRENT, so hasStaleGrant=true here is a
        // contradiction that should never happen in practice - but the resolver must still
        // resolve deterministically to READY rather than accidentally downgrading a good grant.
        assertEquals(
            SpotifyConnectResolver.Stage.READY,
            SpotifyConnectResolver.stage(hasClientId = true, isAuthorized = true, hasStaleGrant = true),
        )
    }

    // -------------------------------------------------------------- client id

    @Test
    fun `blank and whitespace-only client ids are rejected`() {
        assertEquals(SpotifyConnectResolver.ClientIdCheck.BLANK, SpotifyConnectResolver.checkClientId(""))
        assertEquals(SpotifyConnectResolver.ClientIdCheck.BLANK, SpotifyConnectResolver.checkClientId("   "))
        assertEquals(SpotifyConnectResolver.ClientIdCheck.BLANK, SpotifyConnectResolver.checkClientId("\n\t"))
    }

    @Test
    fun `a real-shaped client id passes`() {
        assertEquals(
            SpotifyConnectResolver.ClientIdCheck.OK,
            SpotifyConnectResolver.checkClientId("0123456789abcdef0123456789abcdef"),
        )
    }

    @Test
    fun `surrounding whitespace from a paste does not fail the shape check`() {
        assertEquals(
            SpotifyConnectResolver.ClientIdCheck.OK,
            SpotifyConnectResolver.checkClientId("  0123456789abcdef0123456789abcdef\n"),
        )
    }

    @Test
    fun `uppercase hex passes - Spotify issues lowercase but case is not the point of the check`() {
        assertEquals(
            SpotifyConnectResolver.ClientIdCheck.OK,
            SpotifyConnectResolver.checkClientId("0123456789ABCDEF0123456789ABCDEF"),
        )
    }

    @Test
    fun `the realistic wrong pastes are all flagged, none of them blocked`() {
        val wrong = listOf(
            "0123456789abcdef0123456789abcde",            // truncated copy, 31 chars
            "0123456789abcdef0123456789abcdef0",          // 33 chars
            "https://developer.spotify.com/dashboard",    // the dashboard URL
            "my-client-id",                               // a placeholder
            "0123456789abcdefg123456789abcdef",           // 32 chars, but 'g' is not hex
        )
        for (value in wrong) {
            assertEquals(
                "expected $value to be flagged",
                SpotifyConnectResolver.ClientIdCheck.UNEXPECTED_FORMAT,
                SpotifyConnectResolver.checkClientId(value),
            )
            // The contract that matters: flagged is not blocked. Only BLANK stops a save.
            assertNotEquals(SpotifyConnectResolver.ClientIdCheck.BLANK, SpotifyConnectResolver.checkClientId(value))
        }
    }

    // ------------------------------------------------------------------- copy

    @Test
    fun `every stage has a distinct headline and detail`() {
        val headlines = SpotifyConnectResolver.Stage.entries.map { SpotifyConnectResolver.headline(it) }
        val details = SpotifyConnectResolver.Stage.entries.map { SpotifyConnectResolver.detail(it) }
        assertEquals(headlines.size, headlines.toSet().size)
        assertEquals(details.size, details.toSet().size)
        assertTrue(headlines.none { it.isBlank() })
        assertTrue(details.none { it.isBlank() })
    }

    @Test
    fun `only the two authorization stages offer a row action, with distinct labels`() {
        assertNull(SpotifyConnectResolver.actionLabel(SpotifyConnectResolver.Stage.NEEDS_CLIENT_ID))
        assertEquals("AUTHORIZE", SpotifyConnectResolver.actionLabel(SpotifyConnectResolver.Stage.NEEDS_AUTHORIZATION))
        // Distinct label on purpose - "RE-AUTHORIZE" reads as a renewal, "AUTHORIZE" would read
        // as first-time setup to a driver who has already done this once.
        assertEquals("RE-AUTHORIZE", SpotifyConnectResolver.actionLabel(SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION))
        assertNull(SpotifyConnectResolver.actionLabel(SpotifyConnectResolver.Stage.READY))
    }

    @Test
    fun `the reauthorization detail explains why in words - new permissions, not a failure`() {
        val detail = SpotifyConnectResolver.detail(SpotifyConnectResolver.Stage.NEEDS_REAUTHORIZATION).lowercase()
        assertTrue(detail.contains("already connected") || detail.contains("connected spotify"))
        assertTrue(detail.contains("permission"))
    }

    @Test
    fun `the redirect instruction reproduces the URI verbatim`() {
        // The whole point of the row: Spotify rejects on any mismatch, so the copy must not
        // reformat, truncate, or case-fold what the driver has to register.
        val uri = "com.kevin.legion://spotify-callback"
        assertTrue(SpotifyConnectResolver.redirectUriInstruction(uri).contains(uri))
    }

    @Test
    fun `the premium note states a requirement rather than claiming to know the account tier`() {
        // The app cannot read the tier, so this string must never assert one. Guards the copy
        // against a well-meaning future edit to "your account is Free".
        val note = SpotifyConnectResolver.PREMIUM_NOTE.lowercase()
        assertTrue(note.contains("requires premium"))
        assertTrue(note.contains("if you're on free"))
    }
}
