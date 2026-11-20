package com.kevin.midnightai.backend

import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.EngineFailure
import com.kevin.midnightai.backend.engine.EngineHttp
import com.kevin.midnightai.backend.engine.EngineHttpException
import com.kevin.midnightai.backend.engine.EngineMcpClient
import com.kevin.midnightai.backend.engine.EngineSignIn
import com.kevin.midnightai.backend.engine.McpListResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The car's own sign-in (a token handed over the car link, T3b). The engine accepts it ONLY on the
 * car's own uploads (device logs, the OBD batch) and the agents turn the unit does not call yet, so
 * the unit never sends anything else on it, never loops on a 401 and never signs itself out. The
 * member sign-in typed on the unit works exactly as before. Made-up tokens.
 */
@RunWith(RobolectricTestRunner::class)
class EngineCarLinkSignInTest {
    private val server = MockWebServer()

    private fun config() = EngineConfig(
        RuntimeEnvironment.getApplication(),
        encrypt = { "ENC($it)" },
        decrypt = { it.removePrefix("ENC(").removeSuffix(")") },
    )

    private fun base() = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun carLinked(): EngineConfig = config().also {
        assertTrue(it.saveSessionStrict(base(), "hu1_made-up", "user-1", carLink = true, vehicleId = "veh-1"))
    }

    private fun Result<*>.failure(): EngineFailure = (exceptionOrNull() as EngineHttpException).failure

    @Test fun onlyAHandedOverTokenEntersCarLinkMode() {
        val c = config()
        c.saveBaseUrl(base())
        c.saveSession("member-token", "user-1")
        assertEquals(EngineSignIn.MEMBER, c.signIn())
        c.clearSession() // no member signed in: the car token is used (a member keeps precedence, F1)
        val linked = carLinked()
        assertEquals(EngineSignIn.CAR_LINK, linked.signIn())
        assertEquals("veh-1", linked.carLinkVehicleId())
        // A member sign-in typed later replaces it and leaves car-link mode.
        linked.saveSession("member-token", "user-1")
        assertEquals(EngineSignIn.MEMBER, linked.signIn())
    }

    @Test fun aRouteOutsideTheFenceIsNeverSent() = runBlocking {
        val http = EngineHttp(carLinked(), OkHttpClient())
        val r = http.get("/api/releases/latest")
        assertTrue(r.isFailure)
        assertTrue(r.failure().sentence, r.failure().sentence.contains("car's own sign-in"))
        assertEquals(0, server.requestCount)
    }

    @Test fun aFenceRouteIsSentAndA401NeitherLoopsNorSignsOut() = runBlocking {
        val c = carLinked()
        val http = EngineHttp(c, OkHttpClient())
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token."}"""))
        val first = http.post("/api/fleet/obd_samples/batch/", "[]")
        assertTrue(first.failure() is EngineFailure.Unauthorized)
        assertTrue(first.failure().sentence, first.failure().sentence.contains("Pair your phone again"))
        assertEquals(1, server.requestCount)
        assertTrue("signed out on a 401", c.isSignedIn())
        assertTrue(c.carLinkRefused())
        // Refused: nothing more goes out until a new hand-over.
        assertTrue(http.post("/api/device-logs/", "{}").isFailure)
        assertEquals(1, server.requestCount)
        assertTrue(c.saveSessionStrict(base(), "hu1_renewed", "user-1", carLink = true, vehicleId = "veh-1"))
        assertFalse(c.carLinkRefused())
    }

    /**
     * Review F1 (orchestrator's call): a member signed in on the unit stays in use; the car's token
     * from a pairing waits in reserve and is used only once no member sign-in exists.
     */
    @Test fun aMemberSignInStaysAndTheCarsTokenWaitsInReserve() {
        val c = config()
        c.saveBaseUrl(base())
        c.saveSession("member-token", "user-1")
        assertTrue(c.saveSessionStrict(base(), "hu1_made-up", "user-1", carLink = true, vehicleId = "veh-1"))
        assertEquals(EngineSignIn.MEMBER, c.signIn())
        assertEquals("member-token", c.token())
        assertTrue(c.hasCarLinkReserve())
        // The member signs out on the unit: the car's own sign-in takes over.
        c.clearSession()
        assertEquals(EngineSignIn.CAR_LINK, c.signIn())
        assertEquals("hu1_made-up", c.token())
        assertEquals("veh-1", c.carLinkVehicleId())
        assertFalse(c.hasCarLinkReserve())
    }

    @Test fun aReserveIsNeverPromotedOntoAnotherEngineAddress() {
        val c = config()
        c.saveBaseUrl(base())
        c.saveSession("member-token", "user-1")
        c.saveSessionStrict(base(), "hu1_made-up", "user-1", carLink = true, vehicleId = "veh-1")
        c.saveBaseUrl("https://another.example")
        c.clearSession()
        assertEquals(EngineSignIn.NONE, c.signIn())
    }

    @Test fun forgettingTheCarLinkClearsItsReserveAndKeepsTheMember() {
        val c = config()
        c.saveBaseUrl(base())
        c.saveSession("member-token", "user-1")
        val access = com.kevin.midnightai.link.LinkKeyStore.engineLinkAccess(c)
        assertTrue(access.saveStrict(base(), "hu1_made-up", "user-1", "veh-1"))
        assertEquals("ENC(hu1_made-up)", access.rawToken())
        assertTrue(access.clear())
        assertEquals("member-token", c.token())
        assertFalse(c.hasCarLinkReserve())
    }

    @Test fun aMember401IsUnchanged() = runBlocking {
        val c = config()
        c.saveBaseUrl(base())
        c.saveSession("member-token", "user-1")
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val http = EngineHttp(c, OkHttpClient())
        assertTrue(http.get("/api/releases/latest").failure() is EngineFailure.Unauthorized)
        assertTrue(http.get("/api/releases/latest").isSuccess)
        assertEquals(2, server.requestCount)
        assertTrue(c.isSignedIn())
    }

    @Test fun engineQuestionsSayTheyNeedAMemberSignIn() = runBlocking {
        val c = carLinked()
        val listed = EngineMcpClient(EngineHttp(c, OkHttpClient()), c::baseUrl).listTools()
        assertTrue(listed is McpListResult.Failed)
        val words = (listed as McpListResult.Failed).failure.sentence
        assertTrue(words, words.contains("member sign-in") && words.contains("Nothing was read or written"))
        assertEquals(0, server.requestCount)
    }
}
