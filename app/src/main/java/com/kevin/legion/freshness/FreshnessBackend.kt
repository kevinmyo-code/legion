package com.kevin.legion.freshness

import android.content.Context
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.translatingEngineCall

/** What the login-expiry poller asks. A seam so a test can fake the engine. */
interface FreshnessBackend {
    suspend fun feeds(): Result<List<FeedFreshness>>
}

class DjangoFreshnessBackend(private val http: EngineHttp) : FreshnessBackend {
    override suspend fun feeds(): Result<List<FeedFreshness>> =
        translatingEngineCall("check your feeds") {
            parseFreshness(http.get("/api/freshness").getOrThrow().body)
        }
}

/** The backend for this device's engine, or null with no engine address or no signed-in token. */
fun djangoFreshnessBackendOrNull(context: Context): FreshnessBackend? {
    val http = EngineHttp(EngineConfig(context.applicationContext))
    return if (http.isUsable()) DjangoFreshnessBackend(http) else null
}
