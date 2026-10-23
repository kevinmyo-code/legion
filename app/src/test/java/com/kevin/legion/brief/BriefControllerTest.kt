package com.kevin.legion.brief

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.sitrep.SitrepModule
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BriefController] with a fake engine, an in-memory cache, a fixed clock and a fixed zone: the
 * cache fallback, the staleness wording, the refresh error mapping and the `get_sitrep` text.
 * The brief's own body is [BriefWireContractTest.BODY], built 2026-10-22 10:02:07Z (5:02 AM Chicago).
 */
class BriefControllerTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val body = BriefWireContractTest.BODY
    private val brief = parseBrief(body)

    private class FakeBackend(
        var latest: Result<BriefReply>,
        var refresh: Result<BriefReply.Got> = Result.failure(IllegalStateException("unused")),
    ) : BriefBackend {
        var refreshCalls = 0
        override suspend fun latest() = latest
        override suspend fun refresh(): Result<BriefReply.Got> {
            refreshCalls++
            return refresh
        }
    }

    private class MemoryCache(var body: String? = null, var savedAt: Long = 0L) : BriefCache {
        override fun read(): CachedBrief? = body?.let { CachedBrief(parseBrief(it), savedAt) }
        override fun write(rawServerBody: String, savedAtMs: Long) {
            body = rawServerBody
            savedAt = savedAtMs
        }
    }

    private class FakePhone(var newsletters: Boolean = false) : BriefPhone {
        var newsletterReads = 0
        override suspend fun sections(filter: Set<SitrepModule>?): List<PhoneSection> {
            fun on(m: SitrepModule) = filter == null || m in filter
            return listOfNotNull(
                PhoneSection("Calendar", "clear for the next 24h").takeIf { on(SitrepModule.CALENDAR) },
                PhoneSection("Weather", "71F, clear").takeIf { on(SitrepModule.WEATHER) },
            )
        }
        override suspend fun newslettersEnabled() = newsletters
        override suspend fun newsletters(): String {
            newsletterReads++
            return "NEWS Two stories on AI chips."
        }
    }

    private fun unreachable() = Result.failure<Nothing>(
        EngineHttpException(
            EngineFailure.Unreachable(
                "https://engine",
                "Couldn't read the brief. Can't reach the engine at https://engine.",
            ),
        ),
    )

    private fun refused(status: Int, body: String) =
        Result.failure<BriefReply.Got>(EngineHttpException(EngineFailure.Refused(status, body)))

    private fun controller(
        backend: BriefBackend?,
        cache: MemoryCache = MemoryCache(),
        phone: FakePhone = FakePhone(),
        now: Long = at(2026, 10, 22, 8, 0),
    ) = BriefController({ backend }, cache, phone, clock = { now }, zone = { zone })

    // ------------------------------------------------------------------------- load and cache

    @Test
    fun `a fresh read is shown with its built time and is cached`() = runBlocking {
        val cache = MemoryCache()
        val view = controller(FakeBackend(Result.success(BriefReply.Got(brief, body))), cache).load()
        view as BriefView.Showing
        assertEquals("Built 5:02 AM today", view.header)
        assertNull(view.savedCopy)
        assertEquals(body, cache.body)
        assertEquals(at(2026, 10, 22, 8, 0), cache.savedAt)
    }

    @Test
    fun `unreachable with a saved copy shows the copy, says it is saved, how old, and why`() = runBlocking {
        val cache = MemoryCache(body, savedAt = at(2026, 10, 22, 5, 0))
        val view = controller(FakeBackend(unreachable()), cache).load()
        view as BriefView.Showing
        assertEquals(brief, view.brief)
        assertEquals("Built 5:02 AM today", view.header)
        assertEquals(
            "This is the saved copy on this phone, saved 3 hours ago, because a fresh one could not be read. " +
                "Couldn't read the brief. Can't reach the engine at https://engine.",
            view.savedCopy,
        )
    }

    @Test
    fun `unreachable with no saved copy says why, never blank`() = runBlocking {
        val view = controller(FakeBackend(unreachable())).load()
        assertEquals(
            BriefView.Unavailable("Couldn't read the brief. Can't reach the engine at https://engine."),
            view,
        )
    }

    @Test
    fun `no engine signed in falls back to the saved copy, or says so`() = runBlocking {
        assertEquals(BriefView.Unavailable(BriefWords.NO_ENGINE), controller(null).load())
        val cached = controller(null, MemoryCache(body, at(2026, 10, 21, 8, 0))).load() as BriefView.Showing
        assertTrue(cached.savedCopy!!.contains("saved 24 hours ago"))
        assertTrue(cached.savedCopy!!.endsWith(BriefWords.NO_ENGINE))
    }

    @Test
    fun `a 404 shows the engine's sentence and is not covered by an older saved copy`() = runBlocking {
        val sentence = "No brief has been built for you yet."
        val cache = MemoryCache(body, at(2026, 10, 21, 8, 0))
        val view = controller(FakeBackend(Result.success(BriefReply.NoneYet(sentence))), cache).load()
        assertEquals(BriefView.NoneYet(sentence), view)
        assertEquals(at(2026, 10, 21, 8, 0), cache.savedAt)
    }

    @Test
    fun `a server error on read names the status and keeps the engine's words`() = runBlocking {
        val failing = FakeBackend(
            Result.failure(EngineHttpException(EngineFailure.Refused(500, """{"detail": "Boom."}"""))),
        )
        assertEquals(
            BriefView.Unavailable("Couldn't read the brief: the engine answered HTTP 500. Boom."),
            controller(failing).load(),
        )
    }

    // ------------------------------------------------------------------------- staleness words

    @Test
    fun `built time reads today, yesterday, or the day and date`() {
        val now = at(2026, 10, 22, 8, 0)
        assertEquals("Built 5:02 AM today", BriefWords.built(at(2026, 10, 22, 5, 2), now, zone))
        assertEquals("Built yesterday 5:01 AM", BriefWords.built(at(2026, 10, 21, 5, 1), now, zone))
        assertEquals("Built Tue Oct 20, 5:01 AM", BriefWords.built(at(2026, 10, 20, 5, 1), now, zone))
        assertEquals("Built 12:30 PM today", BriefWords.built(at(2026, 10, 22, 12, 30), now, zone))
    }

    @Test
    fun `age steps follow the server's own ago`() {
        val now = 1_000_000_000L
        assertEquals("just now", BriefWords.ago(now - 59_000, now))
        assertEquals("1 minute ago", BriefWords.ago(now - 60_000, now))
        assertEquals("59 minutes ago", BriefWords.ago(now - 59 * 60_000L, now))
        assertEquals("1 hour ago", BriefWords.ago(now - 3_600_000L, now))
        assertEquals("47 hours ago", BriefWords.ago(now - 47 * 3_600_000L, now))
        assertEquals("2 days ago", BriefWords.ago(now - 48 * 3_600_000L, now))
        assertEquals("just now", BriefWords.ago(now + 5_000, now))
    }

    // ------------------------------------------------------------------------- refresh

    @Test
    fun `a refresh that lands replaces the shown brief and the cache`() = runBlocking {
        val refreshed = body.replace(""""trigger": "scheduled"""", """"trigger": "refresh"""")
        val cache = MemoryCache(body, at(2026, 10, 21, 8, 0))
        val backend = FakeBackend(unreachable(), Result.success(BriefReply.Got(parseBrief(refreshed), refreshed)))
        val outcome = controller(backend, cache).refresh() as RefreshOutcome.Refreshed
        assertEquals(ServerBrief.TRIGGER_REFRESH, outcome.view.brief.trigger)
        assertEquals(refreshed, cache.body)
    }

    @Test
    fun `409 and 503 are the engine's own sentences, and the cache is untouched`() = runBlocking {
        val locked = "A brief is being built for this household right now, so this refresh did not run and " +
            "nothing changed. Try again in a minute."
        val cache = MemoryCache(body, 7L)
        val backend = FakeBackend(unreachable(), refused(409, """{"detail": "$locked"}"""))
        assertEquals(RefreshOutcome.NotRefreshed(locked), controller(backend, cache).refresh())
        val failed = "The brief could not be built, so nothing new was stored and the previous brief is unchanged."
        backend.refresh = refused(503, """{"detail": "$failed"}""")
        assertEquals(RefreshOutcome.NotRefreshed(failed), controller(backend, cache).refresh())
        assertEquals(7L, cache.savedAt)
    }

    @Test
    fun `429 says the refresh did not run before DRF's throttle sentence`() = runBlocking {
        val backend = FakeBackend(
            unreachable(),
            refused(429, """{"detail": "Request was throttled. Expected available in 1234 seconds."}"""),
        )
        assertEquals(
            RefreshOutcome.NotRefreshed(
                "The engine limits how often a brief can be refreshed, so this refresh did not run and nothing " +
                    "changed. Request was throttled. Expected available in 1234 seconds.",
            ),
            controller(backend).refresh(),
        )
    }

    @Test
    fun `an unreachable refresh, a bare 5xx and no engine each say what did not happen`() = runBlocking {
        val backend = FakeBackend(
            unreachable(),
            Result.failure(
                EngineHttpException(
                    EngineFailure.Unreachable("h", "Couldn't refresh the brief. Can't reach the engine at h."),
                ),
            ),
        )
        assertEquals(
            RefreshOutcome.NotRefreshed("Couldn't refresh the brief. Can't reach the engine at h."),
            controller(backend).refresh(),
        )
        backend.refresh = refused(502, "<html>Bad Gateway</html>")
        assertEquals(
            RefreshOutcome.NotRefreshed("The refresh did not run: the engine answered HTTP 502. Nothing changed."),
            controller(backend).refresh(),
        )
        assertEquals(RefreshOutcome.NotRefreshed(BriefWords.NO_ENGINE), controller(null).refresh())
    }

    // ------------------------------------------------------------------------- get_sitrep

    @Test
    fun `the voice result is the server brief in order, then the phone's calendar and weather`() = runBlocking {
        val voice = controller(FakeBackend(Result.success(BriefReply.Got(brief, body)))).forVoice(null)
        assertTrue(voice.success)
        val text = voice.text
        val order = listOf("BRIEF Built 5:02 AM today.", "NOTE ", "CANVAS (ok)", "MONEY (ok)", "CAR (empty)",
            "NEWS (ok)", "CALENDAR clear for the next 24h", "WEATHER 71F, clear")
        val positions = order.map { text.indexOf(it) }
        val inOrder = positions.all { it >= 0 } && positions == positions.sorted()
        assertTrue("missing or out of order: $positions\n$text", inOrder)
        assertTrue(text.contains("Unverified: includes bank rows no statement gate has checked. [unverified]"))
        assertTrue(text.contains("(sources: example.com, news.example.org)"))
        assertTrue(text.contains("Canvas last synced 2 days ago."))
        assertFalse(text.contains("https://"))
    }

    @Test
    fun `a saved copy still counts as read, and says it is the saved copy`() = runBlocking {
        val cache = MemoryCache(body, at(2026, 10, 22, 6, 0))
        val voice = controller(FakeBackend(unreachable()), cache).forVoice(null)
        assertTrue(voice.success)
        assertTrue(voice.text.contains("This is the saved copy on this phone, saved 2 hours ago"))
    }

    @Test
    fun `no brief read is a failure result that says what is missing and still gives the phone's half`() =
        runBlocking {
            val voice = controller(FakeBackend(unreachable())).forVoice(null)
            assertFalse(voice.success)
            assertTrue(voice.text.startsWith("No brief was read. Couldn't read the brief. Can't reach the engine"))
            assertTrue(voice.text.contains("Canvas, money, car and web news are not included."))
            assertTrue(voice.text.contains("CALENDAR clear for the next 24h"))
        }

    @Test
    fun `newsletters are read live only when that module is on, and never cached`() = runBlocking {
        val phone = FakePhone(newsletters = false)
        val cache = MemoryCache()
        val backend = FakeBackend(Result.success(BriefReply.Got(brief, body)))
        assertFalse(controller(backend, cache, phone).forVoice(null).text.contains("NEWSLETTERS"))
        assertEquals(0, phone.newsletterReads)
        phone.newsletters = true
        val text = controller(backend, cache, phone).forVoice(null).text
        val header = "NEWSLETTERS, read from your mail on this phone just now and not stored:"
        assertTrue(text.contains("$header\nNEWS Two stories"))
        assertFalse(cache.body!!.contains("Two stories"))
    }

    @Test
    fun `a modules filter narrows both halves`() = runBlocking {
        val backend = FakeBackend(Result.success(BriefReply.Got(brief, body)))
        val news = controller(backend).forVoice(setOf(SitrepModule.NEWS)).text
        assertTrue(news.contains("NEWS (ok)"))
        assertFalse(news.contains("CANVAS"))
        assertFalse(news.contains("MONEY"))
        assertFalse(news.contains("CALENDAR"))
        val car = controller(backend).forVoice(setOf(SitrepModule.FLEET, SitrepModule.WEATHER)).text
        assertTrue(car.contains("CAR (empty) No vehicle on file."))
        assertTrue(car.contains("WEATHER 71F, clear"))
        assertFalse(car.contains("NEWS (ok)"))
    }
}
