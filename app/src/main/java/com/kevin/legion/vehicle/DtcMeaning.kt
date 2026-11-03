package com.kevin.legion.vehicle

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What one trouble code shows on screen (2026-11-03, Kevin: "trouble codes show not yet described,
 * cant we describe it ?"). Where the words came from is part of the value, because a meaning the AI
 * wrote is an estimate and CLAUDE.md §4 rule 5 / §7 say an estimate is labelled in words.
 */
sealed interface DtcMeaning {
    /** From the bundled generic-code dictionary ([DtcDescriptions.loadSeed]). */
    data class Standard(val title: String, val detail: String) : DtcMeaning

    /** From the learned cache, which only the diagnostics sub-agent ever writes. */
    data class AiExplanation(val title: String, val detail: String) : DtcMeaning

    /** Unknown and a lookup is pending or running. */
    data object LookingUp : DtcMeaning

    /** Unknown and the last lookup failed (offline, no key, no answer). */
    data object NoDescription : DtcMeaning
}

object DtcMeanings {
    const val AI_LABEL = "AI explanation, not from the code standard"
    const val LOOKING_UP = "Looking this up..."
    const val NO_DESCRIPTION = "No description yet"
    const val MANUFACTURER_NOTE =
        "Manufacturer-specific code: its meaning is set by the carmaker, not the generic OBD-II standard."

    /**
     * SAE J2012's split: the second character 1 is manufacturer-controlled for every system
     * (P1, B1, C1, U1); B2, C2 and U2 are too; for powertrain, P30xx-P33xx are manufacturer and
     * P34xx-P39xx are SAE. P0 and P2 are generic.
     */
    fun isManufacturerSpecific(code: String): Boolean {
        val c = code.trim().uppercase()
        if (c.length < 3) return false
        return when (c[0]) {
            'P' -> c[1] == '1' || (c[1] == '3' && c[2] in '0'..'3')
            'B', 'C', 'U' -> c[1] == '1' || c[1] == '2'
            else -> false
        }
    }

    /** Seed first, then the learned cache; an unknown code is looking-up until a lookup fails. */
    fun resolve(
        code: String,
        seed: Map<String, Pair<String, String>>,
        learned: Map<String, Pair<String, String>>,
        failed: Set<String>,
    ): DtcMeaning {
        seed[code]?.let { return DtcMeaning.Standard(it.first, it.second) }
        learned[code]?.let { return DtcMeaning.AiExplanation(it.first, it.second) }
        return if (code in failed) DtcMeaning.NoDescription else DtcMeaning.LookingUp
    }

    /** The main line of text for a code. */
    fun headline(m: DtcMeaning): String = when (m) {
        is DtcMeaning.Standard -> m.title
        is DtcMeaning.AiExplanation -> m.title
        DtcMeaning.LookingUp -> LOOKING_UP
        DtcMeaning.NoDescription -> NO_DESCRIPTION
    }

    /** The small lines under the headline: the AI label and the manufacturer note, in words. */
    fun notes(code: String, m: DtcMeaning): List<String> = listOfNotNull(
        AI_LABEL.takeIf { m is DtcMeaning.AiExplanation },
        MANUFACTURER_NOTE.takeIf { isManufacturerSpecific(code) },
    )

    /** One-line value for the glance card, which has no room for a second line. */
    fun glanceValue(m: DtcMeaning): String = when (m) {
        is DtcMeaning.AiExplanation -> "${m.title} ($AI_LABEL)"
        else -> headline(m)
    }
}

/** Snapshot of the background lookups: codes in flight, codes whose last lookup failed, codes learned. */
data class DtcLookupState(
    val inFlight: Set<String> = emptySet(),
    val failedAt: Map<String, Long> = emptyMap(),
    val learned: Map<String, Pair<String, String>> = emptyMap(),
) {
    val failed: Set<String> get() = failedAt.keys
}

/**
 * Single-flight background lookup of unknown trouble codes through the diagnostics sub-agent.
 * The codes card and the glance card both ask at the same moment for the same codes; a code
 * already in flight is never asked twice, and a code that failed is retried only after
 * [retryAfterMs]. Answers are persisted to the learned cache by [persist], so each code costs one
 * Gemini call per install. Runs in its own process-lifetime [scope], so a lookup outlives the voice
 * tool call or the composable that started it.
 */
class DtcLookup(
    private val scope: CoroutineScope,
    private val describe: suspend (vehicleLabel: String, codes: List<String>) -> Map<String, Pair<String, String>>?,
    private val persist: suspend (Map<String, Pair<String, String>>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val retryAfterMs: Long = RETRY_AFTER_MS,
) {
    private val _state = MutableStateFlow(DtcLookupState())
    val state: StateFlow<DtcLookupState> = _state.asStateFlow()

    /**
     * Starts a lookup for those of [unknown] not already known, in flight, or recently failed.
     * [unknown] must already exclude seed and on-disk learned codes; the caller has those maps.
     */
    fun request(vehicleLabel: String, unknown: Collection<String>) {
        var toAsk: List<String> = emptyList()
        _state.update { s ->
            val t = now()
            toAsk = unknown.distinct().filter { code ->
                code !in s.learned && code !in s.inFlight &&
                    s.failedAt[code]?.let { t - it >= retryAfterMs } != false
            }
            if (toAsk.isEmpty()) s else s.copy(inFlight = s.inFlight + toAsk, failedAt = s.failedAt - toAsk.toSet())
        }
        if (toAsk.isEmpty()) return
        val asked = toAsk
        scope.launch {
            val answered = runCatching { describe(vehicleLabel, asked) }.getOrNull().orEmpty()
                .filterKeys { it in asked }
                .filterValues { it.first.isNotBlank() }
            if (answered.isNotEmpty()) runCatching { persist(answered) }
            val t = now()
            _state.update { s ->
                s.copy(
                    inFlight = s.inFlight - asked.toSet(),
                    learned = s.learned + answered,
                    failedAt = s.failedAt + (asked - answered.keys).associateWith { t },
                )
            }
        }
    }

    /** Suspends until none of [codes] is in flight. */
    suspend fun awaitSettled(codes: Collection<String>) {
        state.first { s -> codes.none { it in s.inFlight } }
    }

    companion object {
        const val RETRY_AFTER_MS = 2 * 60 * 1000L

        @Volatile private var instance: DtcLookup? = null

        /** The app's one lookup, on the household's Gemini key via [DiagnosticAgent.describeCodes]. */
        fun get(context: Context): DtcLookup = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                DtcLookup(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    describe = { label, codes -> DiagnosticAgent.describeCodes(app, label, codes) },
                    persist = { DtcDescriptions.learn(app, it) },
                ).also { instance = it }
            }
        }
    }
}
