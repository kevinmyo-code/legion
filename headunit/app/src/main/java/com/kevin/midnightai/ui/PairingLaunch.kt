package com.kevin.midnightai.ui

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Builds the pairing server off the main thread and hands it to the screen (review P1). The build
 * (key generation, bind, start) cannot be interrupted, so if the screen left meanwhile, whatever it
 * made is passed to [build]'s `discard` (close) instead of being left listening. Exactly one of
 * "handed over" or "discarded" happens, whichever side gets there first.
 */
internal object PairingLaunch {
    private val GONE = Any()

    suspend fun <T : Any> build(io: CoroutineContext, discard: (T) -> Unit, make: () -> T): T {
        val slot = AtomicReference<Any?>(null)
        try {
            return withContext(io) {
                val made = make()
                // The caller already left (marked GONE): nobody will ever close this, so close it now.
                if (!slot.compareAndSet(null, made)) discard(made)
                made
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            val made = slot.getAndSet(GONE)
            @Suppress("UNCHECKED_CAST")
            if (made != null && made !== GONE) discard(made as T)
            throw e
        }
    }
}
