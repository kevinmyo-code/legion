package com.kevin.legion.ui.brief

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.brief.BriefBackend
import com.kevin.legion.brief.BriefCache
import com.kevin.legion.brief.BriefController
import com.kevin.legion.brief.BriefPhone
import com.kevin.legion.brief.BriefReply
import com.kevin.legion.brief.BriefView
import com.kevin.legion.brief.BriefWireContractTest
import com.kevin.legion.brief.CachedBrief
import com.kevin.legion.brief.PhoneSection
import com.kevin.legion.brief.parseBrief
import com.kevin.legion.sitrep.SitrepModule
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Brief modal's states over a real [BriefController] with a fake engine. Unconfined scope: each
 * launch runs to its first real suspension before the call returns, so assertions read the state
 * straight after (the same shape as `BooksViewModelTest`).
 */
class BriefViewModelTest {
    private val body = BriefWireContractTest.BODY
    private val got = BriefReply.Got(parseBrief(body), body)

    private class Backend(var latest: Result<BriefReply>, var refresh: Result<BriefReply.Got>) : BriefBackend {
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun latest() = latest
        override suspend fun refresh(): Result<BriefReply.Got> {
            gate?.await()
            return refresh
        }
    }

    private class Cache : BriefCache {
        var raw: String? = null
        override fun read(): CachedBrief? = raw?.let { CachedBrief(parseBrief(it), 0L) }
        override fun write(rawServerBody: String, savedAtMs: Long) {
            raw = rawServerBody
        }
    }

    private class Phone(val newsletters: Boolean, val fail: Boolean = false) : BriefPhone {
        override suspend fun sections(filter: Set<SitrepModule>?): List<PhoneSection> {
            if (fail) error("Room is closed")
            return listOf(PhoneSection("Calendar", "clear for the next 24h"), PhoneSection("Weather", "71F, clear"))
        }
        override suspend fun newslettersEnabled() = newsletters
        override suspend fun newsletters() = "NEWS Two stories on AI chips."
    }

    private fun locked() = Result.failure<BriefReply.Got>(
        EngineHttpException(EngineFailure.Refused(409, """{"detail": "A brief is being built right now."}""")),
    )

    private fun model(backend: Backend, phone: Phone = Phone(newsletters = false), cache: Cache = Cache()) =
        BriefViewModel(
            BriefController({ backend }, cache, phone, clock = { 1_792_656_000_000L }, zone = { ZoneId.of("UTC") }),
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )

    @Test
    fun `before opening, nothing is read and the view is empty`() {
        val vm = model(Backend(Result.success(got), locked()))
        assertNull(vm.state.value.view)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `opening reads the brief and the phone's calendar and weather`() {
        val vm = model(Backend(Result.success(got), locked()))
        vm.open()
        val s = vm.state.value
        assertFalse(s.loading)
        assertFalse(s.phoneLoading)
        assertEquals(got.brief, (s.view as BriefView.Showing).brief)
        assertEquals(listOf("Calendar", "Weather"), s.phone.map { it.title })
        assertFalse(s.newslettersOn)
    }

    @Test
    fun `an unreachable engine with no copy is a sentence, not a blank`() {
        val down = Result.failure<BriefReply>(
            EngineHttpException(
                EngineFailure.Unreachable("h", "Couldn't read the brief. Can't reach the engine at h."),
            ),
        )
        val vm = model(Backend(down, locked()))
        vm.open()
        assertEquals(
            BriefView.Unavailable("Couldn't read the brief. Can't reach the engine at h."),
            vm.state.value.view,
        )
    }

    @Test
    fun `a phone read that throws becomes a sentence and the server half still shows`() {
        val vm = model(Backend(Result.success(got), locked()), Phone(newsletters = false, fail = true))
        vm.open()
        val s = vm.state.value
        assertTrue(s.view is BriefView.Showing)
        assertEquals("Calendar and weather could not be read: Room is closed", s.phone.single().text)
    }

    @Test
    fun `a refused refresh keeps the brief on screen and says why`() {
        val vm = model(Backend(Result.success(got), locked()))
        vm.open()
        vm.refresh()
        val s = vm.state.value
        assertFalse(s.refreshing)
        assertEquals("A brief is being built right now.", s.refreshNote)
        assertEquals(got.brief, (s.view as BriefView.Showing).brief)
    }

    @Test
    fun `refreshing is shown while the call runs, a second press is ignored, and the new brief lands`() =
        runBlocking {
            val refreshed = body.replace(""""trigger": "scheduled"""", """"trigger": "refresh"""")
            val backend = Backend(Result.success(got), Result.success(BriefReply.Got(parseBrief(refreshed), refreshed)))
            backend.gate = CompletableDeferred()
            val cache = Cache()
            val vm = model(backend, cache = cache)
            vm.open()
            vm.refresh()
            assertTrue(vm.state.value.refreshing)
            vm.refresh()
            backend.gate!!.complete(Unit)
            val s = vm.state.value
            assertFalse(s.refreshing)
            assertNull(s.refreshNote)
            assertEquals("refresh", (s.view as BriefView.Showing).brief.trigger)
            assertEquals(refreshed, cache.raw)
        }

    @Test
    fun `newsletters wait for a tap, and closing drops the summary`() {
        val cache = Cache()
        val vm = model(Backend(Result.success(got), locked()), Phone(newsletters = true), cache)
        vm.open()
        assertTrue(vm.state.value.newslettersOn)
        assertEquals(NewslettersUi.NotChecked, vm.state.value.newsletters)
        vm.checkNewsletters()
        assertEquals(NewslettersUi.Ready("NEWS Two stories on AI chips."), vm.state.value.newsletters)
        assertFalse(cache.raw!!.contains("Two stories"))
        vm.closed()
        assertEquals(NewslettersUi.NotChecked, vm.state.value.newsletters)
    }
}
