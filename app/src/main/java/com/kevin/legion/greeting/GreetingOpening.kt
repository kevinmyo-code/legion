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
 * - [PACK_MS]: the most a tap waits for the facts pack and, separately, for the place read. The pack
 *   is normally in hand before the tap ([GreetingPackCache], 2026-11-15: fetched at app start, a wake
 *   word's onset, after each conversation and on a timer while the screen is on), so a tap with a
 *   usable kept pack waits for nothing; only a tap with no usable pack waits, at most this, for the
 *   refresh it started, and then the scripted line plays. The 11-15 logs showed why waiting at the
 *   tap alone could not work: the engine answers in 1.6 to 2.0 s, past this budget, every time.
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
    /** A line the model writes from [prompt], under the same deadlines, with the scripted line as fallback. */
    sealed interface Spoken : GreetingPlan {
        val prompt: String
    }

    /** A personal greeting: [prompt] is [GreetingPrompt.build]'s text and [topic] is the key it leads with. */
    data class Personal(override val prompt: String, val topic: String) : Spoken

    /**
     * Nothing to raise: a short hello about nothing ([GreetingPrompt.hello]), no facts handed over and
     * no topic recorded (2026-11-16; before, this was the bare scripted line).
     */
    data class Hello(override val prompt: String) : Spoken

    /** The scripted line. [reason] is for the log only; it is never spoken. */
    data class Scripted(val reason: String) : GreetingPlan
}

/**
 * Turns a tap into a [GreetingPlan]: takes the pack ([GreetingPackCache]), reads the place, renders
 * the block and picks the topic. Never throws and never blocks past [GreetingBudget.PACK_MS]; every
 * miss (no usable pack and the engine unreachable, refused or slow) is [GreetingPlan.Scripted], and
 * nothing is spoken about it. A usable pack with nothing to raise is [GreetingPlan.Hello].
 *
 * Logs `greeting_pack source=cache|fresh|none age_s=<n>` once per tap, so a phone audit can see
 * whether the pack was in hand.
 */
class GreetingOpening(
    private val backend: GreetingBackend,
    private val place: PlaceSignals,
    private val scope: CoroutineScope,
    private val packBudgetMs: Long = GreetingBudget.PACK_MS,
    private val cache: GreetingPackCache = GreetingPackCache(backend, scope),
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Suppress("ReturnCount") // one exit per way the pack can fail
    suspend fun prepare(now: LocalTime): GreetingPlan = coroutineScope {
        val where = async { readPlace() }
        // Every tap refreshes the pack for the next one; this tap waits on it only with none in hand.
        val refresh = cache.refresh("tap")
        val kept = cache.usable()
        val outcome: GreetingOutcome<FactsPack> = if (kept != null) {
            log("greeting_pack source=cache age_s=${(clock() - kept.fetchedAtMs) / MS_PER_S}")
            GreetingOutcome.Ok(kept.pack)
        } else {
            val fresh = try {
                withTimeoutOrNull(packBudgetMs) { refresh.await() }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                GreetingOutcome.Unconfirmed("pack_exception_${e.javaClass.simpleName}")
            }
            val stale = cache.latest()?.let { (clock() - it.fetchedAtMs) / MS_PER_S } ?: -1
            if (fresh is GreetingOutcome.Ok) {
                log("greeting_pack source=fresh age_s=0")
                GreetingOutcome.Ok(cache.adjusted(fresh.value))
            } else {
                log("greeting_pack source=none age_s=$stale")
                if (fresh == null) {
                    where.cancel()
                    return@coroutineScope GreetingPlan.Scripted("pack_timeout")
                }
                fresh
            }
        }
        val pack = when (outcome) {
            is GreetingOutcome.Ok -> outcome.value
            is GreetingOutcome.Unreachable -> return@coroutineScope scripted(where, "engine_unreachable")
            is GreetingOutcome.Refused ->
                return@coroutineScope scripted(where, "engine_refused_${outcome.status}")
            is GreetingOutcome.Unconfirmed -> return@coroutineScope scripted(where, "engine_unconfirmed")
        }
        val place = GreetingPlace.applyTopics(where.await(), pack.said, pack.closed)
        placeAtPrepare = GreetingBlock.placeKey(place)
        val history = cache.history()
        val lead = GreetingBlock.lead(pack, place, now.hour, history)
        if (lead == null) {
            log("greeting_lead none hello")
            return@coroutineScope GreetingPlan.Hello(GreetingPrompt.hello(history.hellos))
        }
        log("greeting_lead source=${lead.source}")
        val block = GreetingBlock.render(pack, place, now, lead.item)
        GreetingPlan.Personal(GreetingPrompt.build(block, lead), lead.item.topic)
    }

    private fun scripted(where: kotlinx.coroutines.Deferred<GreetingSource>, reason: String): GreetingPlan {
        where.cancel()
        return GreetingPlan.Scripted(reason)
    }

    /** The place, bounded on its own: a slow fix or geocoder costs the place, never the greeting. */
    private suspend fun readPlace(): GreetingSource {
        val t = clock()
        val source = try {
            withTimeoutOrNull(packBudgetMs) { GreetingPlace.read(place) } ?: GreetingPlace.failed("timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            GreetingPlace.failed(e.javaClass.simpleName)
        }
        log("greeting_place ms=${clock() - t} state=${source.state.name.lowercase()}")
        return source
    }

    /**
     * The greeting [plan] was spoken: a personal one records its topic ([noteRaised]); a hello
     * records only that this open led with no topic, so a run of one kind is broken
     * ([GreetingBlock.History]), and its [words] so the next hello differs. Nothing goes to the
     * engine for a hello. Either way, where he was ([GreetingBlock.placeKey]) is kept: place leads
     * only when that changes.
     */
    fun noteOpened(plan: GreetingPlan.Spoken, words: String? = null) {
        when (plan) {
            is GreetingPlan.Personal -> noteRaised(plan.topic)
            is GreetingPlan.Hello -> cache.noteHello(words)
        }
        cache.notePlace(placeAtPrepare)
    }

    /** Where he was when [prepare] last ran, for [noteOpened]. */
    @Volatile private var placeAtPrepare: String? = null

    /**
     * Records [topic] as raised, fire and forget: the conversation never waits on it and a failure
     * costs only a possible repeat tomorrow, which the next pack's `said` list would have prevented.
     */
    fun noteRaised(topic: String) {
        cache.noteRaised(topic)
        scope.launch(Dispatchers.IO) {
            runCatching { backend.recordTopic(topic, STATE_RAISED) }
        }
    }

    companion object {
        const val STATE_RAISED = "raised"
        private const val MS_PER_S = 1_000L
    }
}
