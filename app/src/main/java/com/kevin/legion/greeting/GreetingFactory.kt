package com.kevin.legion.greeting

import android.content.Context
import com.kevin.legion.backend.engine.DjangoGreetingBackend
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import kotlinx.coroutines.CoroutineScope

/** The real [GreetingOpening]: the household engine, the phone's fix and its saved places. */
object GreetingFactory {
    fun create(context: Context, scope: CoroutineScope): GreetingOpening {
        val app = context.applicationContext
        return GreetingOpening(DjangoGreetingBackend(EngineHttp(EngineConfig(app))), AndroidPlaceSignals(app), scope)
    }
}
