package com.kevin.midnightai.backend

import com.kevin.midnightai.backend.engine.EngineHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review P4: the engine client never follows a redirect. A 307/308 keeps a POST body, so following
 * one could carry a voice-turn body to another (possibly plain http) host.
 */
class EngineRedirectTest {
    @Test fun p4_aRedirectFromTheEngineIsNotFollowed() {
        val other = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200)); start() }
        val engine = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/elsewhere").toString()))
            start()
        }
        try {
            val body = """{"turn":"made-up words"}""".toRequestBody("application/json".toMediaType())
            EngineHttp.buildClient().newCall(Request.Builder().url(engine.url("/api/x/")).post(body).build()).execute().use {
                assertEquals(307, it.code)
            }
            assertEquals("the body went to another host", 0, other.requestCount)
        } finally {
            engine.shutdown(); other.shutdown()
        }
    }
}
