package com.kevin.legion.greeting

import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every deadline the greeting keeps, in one place (ADR 0091, alfred-greets ticket 02).
 *
 * Sized against what the scripted path costs, which is the thing a tap is compared with: on Live the
 * cue is local PCM and plays the moment the socket is up (about 0.5 to 1 s after the tap, one
 * connect); on the laptop pipeline the ack line is a TTS first-audio, 0.75 s median after the cache
 * is warm (`CascadeTimings`).
 *
 * - [PACK_MS]: the pack fetch and the place read, started AT THE TAP and overlapping the connect
 *   (Live) or the probe, connect and prompt build (laptop). Both already take at least a second, so
 *   a pack answering inside 1.5 s costs the tap nothing; a slow or unreachable engine costs at most
 *   this and then the scripted line plays.
 * - [FIRST_OUTPUT_MS]: from the greeting request going out to its first text (laptop) or first audio
 *   (Live). 2.5 s is `CascadeTimings.TTS_FIRST_AUDIO_TIMEOUT_MS` and about 2.5 times the measured
 *   model first-token time (`llm_first_ms`, about 1 s); past it the person has already waited
 *   longer than for any scripted line and the scripted line plays instead.
 *
 * A proposal until ticket 02's device run measures tap-to-first-audio; change them here only.
 */
object GreetingBudget {
    const val PACK_MS = 1_500L
    const val FIRST_OUTPUT_MS = 2_500L
}

/** What a tap opens with. */
sealed interface GreetingPlan {
    /** A personal greeting: [prompt] is [GreetingPrompt.build]'s text and [topic] is the key it leads with. */
    data class Personal(val prompt: String, val topic: String) : GreetingPlan

    /** The scripted line. [reason] is for the log only; it is never spoken. */
    data class Scripted(val reason: String) : GreetingPlan
}

/**
 * Turns a tap into a [GreetingPlan]: fetches the pack, reads the place, renders the block and picks
 * the topic. Never throws and never blocks past [GreetingBudget.PACK_MS]; every miss (engine
 * unreachable, route missing, refused, slow, nothing to say) is [GreetingPlan.Scripted], and nothing
 * is spoken about it.
 */
class GreetingOpening(
    private val backend: GreetingBackend,
    private val place: PlaceSignals,
    private val scope: CoroutineScope,
    private val packBudgetMs: Long = GreetingBudget.PACK_MS,
) {
    @Suppress("ReturnCount") // one exit per way the pack can fail
    suspend fun prepare(now: LocalTime): GreetingPlan {
        val result = try {
            withTimeoutOrNull(packBudgetMs) {
                coroutineScope {
                    val facts = async { backend.facts() }
                    val where = async {
                        try {
                            GreetingPlace.read(place)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            GreetingPlace.failed(e.javaClass.simpleName)
                        }
                    }
                    facts.await() to where.await()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return GreetingPlan.Scripted("pack_exception_${e.javaClass.simpleName}")
        } ?: return GreetingPlan.Scripted("pack_timeout")
        val (outcome, where) = result
        val pack = when (outcome) {
            is GreetingOutcome.Ok -> outcome.value
            is GreetingOutcome.Unreachable -> return GreetingPlan.Scripted("engine_unreachable")
            is GreetingOutcome.Refused -> return GreetingPlan.Scripted("engine_refused_${outcome.status}")
            is GreetingOutcome.Unconfirmed -> return GreetingPlan.Scripted("engine_unconfirmed")
        }
        val place = GreetingPlace.applyTopics(where, pack.said, pack.closed)
        val lead = GreetingBlock.pick(pack, place, now.hour) ?: return GreetingPlan.Scripted("nothing_to_say")
        return GreetingPlan.Personal(GreetingPrompt.build(GreetingBlock.render(pack, place, now), lead), lead.topic)
    }

    /**
     * Records [topic] as raised, fire and forget: the conversation never waits on it and a failure
     * costs only a possible repeat tomorrow, which the next pack's `said` list would have prevented.
     */
    fun noteRaised(topic: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { backend.recordTopic(topic, STATE_RAISED) }
        }
    }

    companion object {
        const val STATE_RAISED = "raised"
    }
}
