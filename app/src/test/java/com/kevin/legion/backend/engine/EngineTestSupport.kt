package com.kevin.legion.backend.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException

/**
 * Shared [MockEngine] plumbing for the Django-engine backend tests - the same posture
 * `EngineAuthTest` already takes ("a [MockEngine] standing in for the network transport only"),
 * lifted out so `DjangoEventsBackendTest`/`DjangoChecklistsBackendTest`/`ChecklistsWriteThroughTest`
 * do not each hand-roll it. **No socket is ever opened by any test in this package.**
 */
internal object EngineTestSupport {

    const val BASE_URL = "http://192.168.1.117:8000"

    /** An [EngineConfig] with an address and a token, over Robolectric's real SharedPreferences.
     * `encrypt`/`decrypt` are the trivial reversible fakes `EngineConfigTest` also uses - there is
     * no Android Keystore in a JVM test, as [com.kevin.legion.ai.KeyVault]'s own doc comment
     * establishes. */
    fun signedInConfig(context: android.content.Context): EngineConfig {
        val config = EngineConfig(
            context = context,
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(BASE_URL)
        config.saveSession("test-device-token", "11111111-1111-1111-1111-111111111111")
        return config
    }

    /** Records every request it is handed, so a test can assert on what actually went out (or that
     * nothing did) rather than only on what came back. */
    class RecordingEngine(
        private val handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val requests = mutableListOf<HttpRequestData>()

        fun client(): HttpClient = HttpClient(
            MockEngine { request ->
                requests += request
                handler(request)
            },
        )
    }

    fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    /** Stands in for an offline phone or a laptop engine that is not running - OkHttp raises an
     * [IOException] for a refused connection, which is the branch
     * [EngineFailure.Unreachable] exists for. */
    fun unreachableClient(): HttpClient = HttpClient(MockEngine { throw IOException("connection refused") })

    fun fixture(name: String): String =
        EngineTestSupport::class.java.classLoader!!.getResourceAsStream("engine_fixtures/$name")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("engine_fixtures/$name missing from test resources")
}
