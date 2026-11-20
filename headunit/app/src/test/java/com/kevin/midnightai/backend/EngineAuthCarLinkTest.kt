package com.kevin.midnightai.backend

import com.kevin.midnightai.backend.engine.EngineAuth
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.MeResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
 * The Engine settings on the car's own sign-in: /api/auth/me and /api/auth/logout refuse that
 * token, so neither is called. The screen says in words what the sign-in covers, and removing it
 * clears this unit only. Made-up tokens.
 */
@RunWith(RobolectricTestRunner::class)
class EngineAuthCarLinkTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun carLinked(): EngineConfig = EngineConfig(
        RuntimeEnvironment.getApplication(),
        encrypt = { "ENC($it)" },
        decrypt = { it.removePrefix("ENC(").removeSuffix(")") },
    ).also {
        val base = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
        assertTrue(it.saveSessionStrict(base, "hu1_made-up", "u-1", carLink = true, vehicleId = "veh-1"))
    }

    @Test fun theSettingsSayWhatTheCarsSignInCoversWithoutAsking() = runBlocking {
        val me = EngineAuth(carLinked(), OkHttpClient()).me()
        assertTrue("$me", me is MeResult.CarLink)
        val words = (me as MeResult.CarLink).message
        assertTrue(words, words.contains("car link") && words.contains("member sign-in"))
        assertEquals(0, server.requestCount)
    }

    @Test fun removingTheCarsSignInClearsThisUnitOnly() = runBlocking {
        val c = carLinked()
        EngineAuth(c, OkHttpClient()).logout()
        assertFalse(c.isSignedIn())
        assertEquals(0, server.requestCount)
    }
}
