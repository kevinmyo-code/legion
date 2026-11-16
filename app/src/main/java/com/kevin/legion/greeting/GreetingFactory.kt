package com.kevin.legion.greeting

import android.content.Context
import com.kevin.legion.backend.engine.DjangoGreetingBackend
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.service.LiveLatency
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The real [GreetingOpening]: the household engine, the phone's fix and its saved places. */
object GreetingFactory {
    @Volatile private var shared: GreetingPackCache? = null
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun log(line: String) = LiveLatency.log(LiveLatency.TAG, line)

    /** The one pack cache of this process ([GreetingPackCache]): every opening and prefetch shares it. */
    fun cache(context: Context): GreetingPackCache {
        shared?.let { return it }
        return synchronized(this) {
            shared ?: run {
                val app = context.applicationContext
                GreetingPackCache(
                    backend = DjangoGreetingBackend(EngineHttp(EngineConfig(app))),
                    scope = cacheScope,
                    store = FilePackStore(File(File(app.filesDir, "greeting"), "pack.json")),
                    log = ::log,
                ).also { shared = it }
            }
        }
    }

    /** Fetches the pack ahead of the next tap (see [GreetingPackCache.prefetch]). Never throws. */
    fun prefetch(context: Context, reason: String) {
        runCatching { cache(context).prefetch(reason) }
    }

    fun create(context: Context, scope: CoroutineScope): GreetingOpening {
        val app = context.applicationContext
        val backend = DjangoGreetingBackend(EngineHttp(EngineConfig(app)))
        return GreetingOpening(backend, AndroidPlaceSignals(app), scope, cache = cache(app), log = ::log)
    }
}
