package com.kevin.legion.service.cascade

import android.content.Context
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.DriverProfile
import com.kevin.legion.vehicle.FleetEngineStore

/**
 * The cloud transcriber's vocabulary line ([SttGuard.hint]), built from data already on the phone:
 * his cars (make, model, nickname), his own name and the companion's. Refreshed in the background at
 * each conversation's open; a request reads whatever is here (the domain words alone until then).
 * Never logged, never written anywhere: it lives in memory and rides only in the STT request.
 */
internal object SttHintStore {
    /** The STT hint: fixed words, no names ([SttGuard.hint]). */
    val line: String = SttGuard.hint()

    /**
     * The transcriber's instruction for the conversation now ([SpokenLanguages.sttInstruction]); the
     * session sets it at open and on a language switch. One conversation at a time on the phone.
     */
    @Volatile var instruction: String = CloudSpeechWire.STT_INSTRUCTION

    /** The names in [line], one word each, lower case, for [SttGuard.verdict]'s `known`. */
    @Volatile var known: Set<String> = emptySet()
        private set

    @Suppress("TooGenericExceptionCaught") // a failed read keeps the last line; it never fails a turn
    suspend fun refresh(context: Context) {
        try {
            val cars = FleetEngineStore.getAll(context)
            val names = buildList {
                add(DriverProfile.name(context))
                add(CompanionProfile.name(context))
                for (v in cars) {
                    add(v.name)
                    add(listOf(v.make, v.model).filter { it.isNotBlank() }.joinToString(" "))
                }
            }
            set(names)
        } catch (_: Exception) {
            Unit
        }
    }

    internal fun set(names: List<String>) {
        known = names.flatMap { it.split(Regex("\\s+")) }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
