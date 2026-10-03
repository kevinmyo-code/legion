package com.kevin.legion.navigation

import com.kevin.legion.ui.MapboxTokenSectionCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapboxTokenProviderTest {
    private class FakeStore(var value: String = "") : MapboxTokenStore {
        override fun load() = value
        override fun save(token: String) { value = token }
        override fun clear() { value = "" }
    }

    private val applied = mutableListOf<String>()
    private fun provider(stored: String = "", baked: String = "", store: FakeStore = FakeStore(stored)) =
        MapboxTokenProvider(store, baked) { applied += it }

    @Test fun pastedTokenBeatsBaked() {
        assertEquals("pk.pasted", provider(stored = "pk.pasted", baked = "pk.baked").token())
    }

    @Test fun bakedUsedWhenNothingPasted() {
        assertEquals("pk.baked", provider(baked = "pk.baked").token())
    }

    @Test fun nothingAnywhereIsNotSetUp() {
        val p = provider()
        assertEquals("", p.token())
        assertFalse(p.state.value.isSet)
        assertEquals(NavPhase.NOT_SET_UP, NavFormat.stateForToken(p.state.value)?.phase)
    }

    @Test fun whitespaceOnlyStoredFallsThroughToBaked() {
        assertEquals("pk.baked", provider(stored = "   ", baked = " pk.baked ").token())
    }

    @Test fun saveStoresTrimsAndAppliesWithoutRestart() {
        val store = FakeStore()
        val p = provider(baked = "pk.baked", store = store)
        assertEquals(MapboxTokenCheck.OK, p.save("  pk.new123  "))
        assertEquals("pk.new123", store.value)
        assertEquals("pk.new123", p.token())
        assertEquals(listOf("pk.new123"), applied)
    }

    @Test fun clearFallsBackToBakedAndApplies() {
        val p = provider(stored = "pk.pasted", baked = "pk.baked")
        p.clear()
        assertEquals("pk.baked", p.token())
        assertEquals(listOf("pk.baked"), applied)
        assertFalse(p.hasPasted())
    }

    @Test fun clearWithNoBakedTokenAppliesBlank() {
        val p = provider(stored = "pk.pasted")
        p.clear()
        assertFalse(p.state.value.isSet)
        assertEquals(listOf(""), applied)
    }

    @Test fun secretTokenIsRefusedByNameAndNeverStored() {
        val store = FakeStore()
        val p = provider(store = store)
        assertEquals(MapboxTokenCheck.SECRET, p.save("sk.eyJabc"))
        assertEquals("", store.value)
        assertTrue(MapboxTokenCheck.SECRET.message!!.contains("secret"))
        assertTrue(applied.isEmpty())
    }

    @Test fun validation() {
        assertEquals(MapboxTokenCheck.OK, MapboxTokenProvider.check("pk.abc.def"))
        assertEquals(MapboxTokenCheck.BLANK, MapboxTokenProvider.check("  "))
        assertEquals(MapboxTokenCheck.SECRET, MapboxTokenProvider.check("SK.abc"))
        assertEquals(MapboxTokenCheck.NOT_PUBLIC, MapboxTokenProvider.check("tk.abc"))
        assertEquals(MapboxTokenCheck.NOT_PUBLIC, MapboxTokenProvider.check("pk."))
        assertEquals(MapboxTokenCheck.NOT_PUBLIC, MapboxTokenProvider.check("pk.ab cd"))
        assertEquals(MapboxTokenCheck.NOT_PUBLIC, MapboxTokenProvider.check("PK.abc"))
    }

    @Test fun startupAppliesOnlyANonBlankToken() {
        provider().applyAtStartup()
        assertTrue(applied.isEmpty())
        provider(baked = "pk.baked").applyAtStartup()
        assertEquals(listOf("pk.baked"), applied)
    }

    @Test fun rejectionIsClearedByANewToken() {
        val p = provider(stored = "pk.old")
        p.markRejected()
        assertTrue(p.state.value.rejected)
        p.save("pk.fresh")
        assertFalse(p.state.value.rejected)
    }

    @Test fun rejectedTokenReadsAsRefusedInWords() {
        val p = provider(stored = "pk.old")
        p.markRejected()
        val s = NavFormat.stateForToken(p.state.value)!!
        assertEquals(NavPhase.TOKEN_REFUSED, s.phase)
        assertEquals("Mapbox refused the token. Check it in Setup.", s.message)
    }

    @Test fun noTokenBeatsStaleRejection() {
        assertEquals(NavPhase.NOT_SET_UP, NavFormat.stateForToken(MapboxTokenState("", rejected = true))?.phase)
    }

    @Test fun goodTokenForcesNoState() {
        assertNull(NavFormat.stateForToken(MapboxTokenState("pk.x", rejected = false)))
    }

    @Test fun notSetUpSentenceIsTheSpecifiedOne() {
        assertEquals("Navigation isn't set up. Add a Mapbox token in Setup.", NavFormat.NOT_SET_UP)
    }

    @Test fun authFailureWordingIsRecognised() {
        assertTrue(NavFormat.isAuthFailure("Not Authorized - Invalid Token"))
        assertTrue(NavFormat.isAuthFailure("HTTP 401"))
        assertTrue(NavFormat.isAuthFailure("Unauthorized"))
        assertFalse(NavFormat.isAuthFailure("No route found"))
        assertFalse(NavFormat.isAuthFailure("Network is unreachable"))
        assertFalse(NavFormat.isAuthFailure(null))
    }

    @Test fun setupRowHeadlineDistinguishesTheFourStates() {
        assertNotNull(MapboxTokenSectionCopy.headline(false, false, false))
        val all = setOf(
            MapboxTokenSectionCopy.headline(false, false, false),
            MapboxTokenSectionCopy.headline(true, true, true),
            MapboxTokenSectionCopy.headline(true, false, true),
            MapboxTokenSectionCopy.headline(true, false, false),
        )
        assertEquals(4, all.size)
        assertTrue(MapboxTokenSectionCopy.explainer.contains("pk."))
    }
}
