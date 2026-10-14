package com.kevin.midnightai.ui

import com.kevin.midnightai.media.AppRemoteLink
import com.kevin.midnightai.media.SpotifyScopes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStatusTest {

    private fun spotify(
        clientId: Boolean = true,
        authorized: Boolean = true,
        scope: String = SpotifyScopes.ALL,
        count: Int = 0,
        error: String? = null,
        remote: AppRemoteLink = AppRemoteLink.CONNECTED,
    ) = spotifyCategoryStatus(clientId, authorized, scope, count, error, remote)

    @Test fun noClientIdIsNeutralNotAFault() {
        val s = spotify(clientId = false, authorized = false, scope = "")
        assertEquals(SettingsDot.NONE, s.dot)
        assertFalse(spotifyNeedsAttention(s))
    }

    @Test fun clientIdWithoutAuthorizationNeedsAttention() {
        val s = spotify(authorized = false, scope = "")
        assertEquals(SettingsDot.WARN, s.dot)
        assertTrue(spotifyNeedsAttention(s))
    }

    @Test fun missingScopesIsAnErrorEvenWithPlaylists() {
        // Mirrors the leaf: a stale grant is broken regardless of what an old fetch returned.
        val s = spotify(scope = "user-read-private", count = 12)
        assertEquals(SettingsDot.ERROR, s.dot)
        assertTrue(s.line.contains("permissions"))
    }

    @Test fun fullGrantWithPlaylistsIsOkAndCountsThem() {
        val s = spotify(count = 7)
        assertEquals(SettingsDot.OK, s.dot)
        assertTrue(s.line.contains("7 playlists"))
    }

    @Test fun fetchErrorBeatsNotFetchedYet() {
        assertEquals(SettingsDot.ERROR, spotify(error = "HTTP 429").dot)
    }

    @Test fun authorizedButNothingFetchedIsNeitherOkNorBroken() {
        assertEquals(SettingsDot.NONE, spotify().dot)
    }

    @Test fun permissionsCountsAreHonest() {
        assertEquals(SettingsDot.OK, permissionsCategoryStatus(0).dot)
        assertEquals("1 permission missing", permissionsCategoryStatus(1).line)
        assertEquals("3 permissions missing", permissionsCategoryStatus(3).line)
        assertEquals(SettingsDot.WARN, permissionsCategoryStatus(2).dot)
    }

    @Test fun engineTokenAloneIsNotGreen() {
        // A stored token can be revoked server-side; only the engine's own answer earns OK.
        val s = engineCategoryStatus(configured = true, signedIn = true, verifiedEmail = null, refusal = false)
        assertEquals(SettingsDot.NONE, s.dot)
        val ok = engineCategoryStatus(true, true, "k@example.com", false)
        assertEquals(SettingsDot.OK, ok.dot)
        assertEquals("Signed in as k@example.com", ok.line)
    }

    @Test fun engineStates() {
        assertEquals(SettingsDot.NONE, engineCategoryStatus(false, false, null, false).dot)
        assertEquals(SettingsDot.WARN, engineCategoryStatus(true, false, null, false).dot)
        assertEquals(SettingsDot.ERROR, engineCategoryStatus(true, true, null, true).dot)
    }

    @Test fun phonesNamesTheActivePhoneOrStaysNeutral() {
        assertEquals("Playing from Pixel", phonesCategoryStatus("Pixel", 2).line)
        assertEquals(SettingsDot.NONE, phonesCategoryStatus(null, 2).dot)
        assertEquals("Bluetooth phones", phonesCategoryStatus(null, 0).line)
    }

    // --- Spotify row action (HOT / re-authorize decision) ---

    private val signedOut = CategoryStatus("x", SettingsDot.WARN)
    private val staleGrant = CategoryStatus("x", SettingsDot.ERROR)
    private val fine = CategoryStatus("x", SettingsDot.OK)

    @Test fun healthyOrAbsentStatusStaysQuiet() {
        assertEquals(SpotifyRowAction.QUIET_OPEN, spotifyRowAction(fine, true, true, false))
        assertEquals(SpotifyRowAction.QUIET_OPEN, spotifyRowAction(CategoryStatus("x"), false, false, false))
        assertEquals(SpotifyRowAction.QUIET_OPEN, spotifyRowAction(null, true, false, false))
    }

    @Test fun signedOutWithClientIdReauthorizes() {
        assertEquals(SpotifyRowAction.HOT_REAUTHORIZE, spotifyRowAction(signedOut, true, false, false))
    }

    @Test fun staleGrantReauthorizes() {
        assertEquals(SpotifyRowAction.HOT_REAUTHORIZE, spotifyRowAction(staleGrant, true, true, true))
    }

    @Test fun fetchErrorOnACompleteGrantOnlyOpensTheLeaf() {
        // Broken, but consent is not the fix: authorized and nothing missing.
        assertEquals(SpotifyRowAction.HOT_OPEN, spotifyRowAction(staleGrant, true, true, false))
    }

    @Test fun brokenWithoutAClientIdOpensTheLeaf() {
        assertEquals(SpotifyRowAction.HOT_OPEN, spotifyRowAction(signedOut, false, false, false))
    }

    @Test fun actionAgreesWithTheStatusFunctionEndToEnd() {
        val missing = spotifyCategoryStatus(true, true, "user-read-private", 3, null, AppRemoteLink.CONNECTED)
        assertEquals(SpotifyRowAction.HOT_REAUTHORIZE, spotifyRowAction(missing, true, true, true))
        val fetchFail = spotifyCategoryStatus(true, true, SpotifyScopes.ALL, 0, "HTTP 500", AppRemoteLink.CONNECTED)
        assertEquals(SpotifyRowAction.HOT_OPEN, spotifyRowAction(fetchFail, true, true, false))
    }

    // --- Chips ---

    @Test fun chipsCarryWordsForEveryDot() {
        assertEquals(SettingsChip("OK", SettingsDot.OK), settingsChip(fine))
        assertEquals(SettingsChip("ACTION NEEDED", SettingsDot.WARN), settingsChip(signedOut))
        assertEquals(SettingsChip("ACTION NEEDED", SettingsDot.ERROR), settingsChip(staleGrant))
        assertEquals(null, settingsChip(CategoryStatus("x")))
        assertEquals(null, settingsChip(null))
    }

    // --- Unknown-until-read ---

    @Test fun unreadSpotifyFactsAreNeutralAndNeverHot() {
        val s = spotifyStatusFor(null, 0, null)
        assertEquals(SettingsDot.NONE, s.dot)
        assertEquals(SpotifyRowAction.QUIET_OPEN, spotifyRowAction(s, true, false, false))
    }

    @Test fun readSpotifyFactsFeedTheSameDecision() {
        val facts = SpotifyFacts(
            hasClientId = true, authorized = false, needsReconnect = false, grantedScope = "",
            appRemote = AppRemoteLink.DISCONNECTED,
        )
        val s = spotifyStatusFor(facts, 0, null)
        assertEquals(SettingsDot.WARN, s.dot)
        assertEquals(SpotifyRowAction.HOT_REAUTHORIZE, spotifyRowAction(s, facts.hasClientId, facts.authorized, facts.needsReconnect))
    }

    // --- App Remote (playback) ---

    @Test fun webOkButPlaybackDisconnectedIsHotAndSaysConnect() {
        // The 2026-10 bug: Web API green while "play X" could never work.
        val s = spotify(count = 7, remote = AppRemoteLink.DISCONNECTED)
        assertEquals(SettingsDot.WARN, s.dot)
        assertEquals("Playback not connected - tap CONNECT", s.line)
        assertEquals(SettingsChip("ACTION NEEDED", SettingsDot.WARN), settingsChip(s))
        // Consent for the Web API is fine, so the HOT button opens the leaf where CONNECT is.
        assertEquals(SpotifyRowAction.HOT_OPEN, spotifyRowAction(s, true, true, false))
    }

    @Test fun playbackConnectingIsNeutralNotHot() {
        val s = spotify(count = 7, remote = AppRemoteLink.CONNECTING)
        assertEquals(SettingsDot.NONE, s.dot)
        assertEquals(SpotifyRowAction.QUIET_OPEN, spotifyRowAction(s, true, true, false))
    }

    @Test fun webProblemsStillOutrankPlayback() {
        assertEquals("Spotify signed out", spotify(authorized = false, scope = "", remote = AppRemoteLink.DISCONNECTED).line)
        assertEquals(SettingsDot.ERROR, spotify(scope = "user-read-private", remote = AppRemoteLink.DISCONNECTED).dot)
    }

    @Test fun spotifyAppMissingOnTheUnitIsNeutralNotAPermanentWarn() {
        for (link in listOf(AppRemoteLink.DISCONNECTED, AppRemoteLink.CONNECTING)) {
            val s = spotifyCategoryStatus(true, true, SpotifyScopes.ALL, 7, null, link, appRemoteNotInstalled = true)
            assertEquals(SettingsDot.NONE, s.dot)
            assertEquals("Playback: Spotify app not on this unit", s.line)
        }
        // Once it does connect (installed since), the stale reason is ignored.
        val ok = spotifyCategoryStatus(true, true, SpotifyScopes.ALL, 7, null, AppRemoteLink.CONNECTED, appRemoteNotInstalled = true)
        assertEquals(SettingsDot.OK, ok.dot)
    }

    @Test fun notApprovedOrLoggedOutStaysWarn() {
        // appRemoteNotInstalled false covers NotAuthorized / NotLoggedIn: still HOT.
        assertEquals(SettingsDot.WARN, spotify(count = 7, remote = AppRemoteLink.DISCONNECTED).dot)
    }

    @Test fun noClientIdIgnoresPlayback() {
        assertEquals(SettingsDot.NONE, spotify(clientId = false, authorized = false, scope = "", remote = AppRemoteLink.DISCONNECTED).dot)
    }
}
