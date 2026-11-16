package com.kevin.legion.greeting

import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.json.JSONObject

/** A facts pack in hand, and when the engine answered it. */
data class CachedPack(val pack: FactsPack, val fetchedAtMs: Long)

/** Where the last good pack is kept across process deaths. A seam so tests hold it in memory. */
interface PackStore {
    fun load(): Pair<String, Long>?

    fun save(body: String, fetchedAtMs: Long)

    /** Nothing kept: every process starts with no pack. */
    object None : PackStore {
        override fun load(): Pair<String, Long>? = null

        override fun save(body: String, fetchedAtMs: Long) = Unit
    }
}

/** One small file in the app's private storage: the pack body and its fetch time. */
class FilePackStore(private val file: File) : PackStore {
    override fun load(): Pair<String, Long>? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        o.getString(BODY) to o.getLong(AT)
    }.getOrNull()

    override fun save(body: String, fetchedAtMs: Long) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put(BODY, body).put(AT, fetchedAtMs).toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        const val BODY = "body"
        const val AT = "fetched_at_ms"
    }
}

/**
 * The greeting's facts pack, fetched AHEAD of the tap (2026-11-15). The 11-15 phone logs had every
 * open fall back to "Yes?" with `pack_timeout`: the pack was fetched at the tap with a 1.5 s budget
 * ([GreetingBudget.PACK_MS]) while `GET /api/greeting/facts` took 1.6 to 2.0 s on the engine itself
 * (Cloud Run request logs). So the pack is now fetched on the same moments the prompt is prepared
 * (app start, the screen coming on and unlock, a wake word's onset, after each conversation) and
 * on a timer while the screen is on (`CascadeWarmup`), and a tap uses the last good one at once
 * while it refreshes.
 *
 * **When a kept pack may be used** ([usable]): fetched within [MAX_AGE_MS] and for today. The facts
 * are day-scale (what is due today, today's workout, the month's budget pace); the parts that move
 * by the minute are handled on the phone, not by a short limit: an event or a due time that has
 * passed since the fetch is dropped at use ([FactsPack.asOf]), a topic raised since is marked raised
 * ([noteRaised]), and the place is always read at the tap. What a kept pack can still miss is an
 * event that ENDED since the fetch, or a row added in the last [MAX_AGE_MS]; the refresh every tap
 * starts covers the next one. Past [MAX_AGE_MS] the tap waits for a fresh pack within the budget.
 *
 * Process-wide by construction: one instance per process ([GreetingFactory.cache]), because a
 * conversation's [GreetingOpening] is built per tap and would otherwise start empty every time.
 */
class GreetingPackCache(
    private val backend: GreetingBackend,
    private val scope: CoroutineScope,
    private val store: PackStore = PackStore.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var held: CachedPack? = null
    @Volatile private var loaded = false
    @Volatile private var inFlight: Deferred<GreetingOutcome<FactsPack>>? = null
    @Volatile private var lastStartMs = 0L

    /** Topics raised on this phone since the packs in hand were fetched, by the day they were raised. */
    private val raisedHere = ConcurrentHashMap<String, LocalDate>()

    /** The pack a tap may use now without waiting, or null. Already [FactsPack.asOf] now. */
    fun usable(): CachedPack? {
        val now = clock()
        val c = latest()?.takeIf { now - it.fetchedAtMs <= MAX_AGE_MS && it.pack.today == today().toString() }
        return c?.copy(pack = adjusted(c.pack, now))
    }

    /** The last good pack whatever its age, for the log. */
    fun latest(): CachedPack? {
        if (!loaded) loadFromStore()
        return held
    }

    /** [pack] as of [now], with the topics raised here since it was fetched marked raised. */
    fun adjusted(pack: FactsPack, now: Long = clock()): FactsPack {
        val day = today()
        raisedHere.entries.removeIf { it.value != day }
        return pack.asOf(now).withSaid(raisedHere.keys)
    }

    /**
     * Starts a fetch unless one is running (that one is returned) or one finished within
     * [MIN_GAP_MS] (the pack in hand is returned as is). Never throws; the outcome is the engine's.
     */
    @Suppress("ReturnCount") // the fetch in flight, the pack just fetched, or a new fetch
    fun refresh(reason: String): Deferred<GreetingOutcome<FactsPack>> {
        inFlight?.takeIf { it.isActive }?.let { return it }
        val now = clock()
        // Only a pack fetched inside the gap stands in for a fetch: after a failed one the pack in
        // hand may be hours old, and handing it back here would pass it off as fresh.
        val c = held?.takeIf { now - it.fetchedAtMs < MIN_GAP_MS }
        if (c != null) return CompletableDeferred(GreetingOutcome.Ok(c.pack))
        lastStartMs = now
        val started = now
        val d = scope.async {
            val o = try {
                backend.facts()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                GreetingOutcome.Unconfirmed("pack fetch failed: ${e.javaClass.simpleName}")
            }
            val ms = clock() - started
            if (o is GreetingOutcome.Ok) {
                val at = clock()
                held = CachedPack(o.value, at)
                loaded = true
                o.value.raw?.let { store.save(it, at) }
                log("greeting_pack_fetch ms=$ms reason=$reason result=ok")
            } else {
                log("greeting_pack_fetch ms=$ms reason=$reason result=${o.javaClass.simpleName}")
            }
            o
        }
        inFlight = d
        return d
    }

    /**
     * Ahead of a tap: fetches unless the pack in hand is younger than [PREFETCH_FRESH_MS]. The
     * triggers (start, onset, close, timer) can fire close together; this keeps it to one fetch.
     */
    fun prefetch(reason: String) {
        val c = latest()
        if (c != null && clock() - c.fetchedAtMs < PREFETCH_FRESH_MS) return
        refresh(reason)
    }

    /** A topic was raised: a kept pack must not offer it again (ADR 0091, never the same question twice). */
    fun noteRaised(topic: String) {
        raisedHere[topic] = today()
    }

    private fun loadFromStore() {
        synchronized(this) {
            if (loaded) return
            loaded = true
            if (held == null) {
                held = store.load()?.let { (body, at) ->
                    runCatching { FactsParser.parse(body) }.getOrNull()?.let { CachedPack(it, at) }
                }
            }
        }
    }

    companion object {
        /** A kept pack older than this is not used without a fresh one (see the class doc). */
        const val MAX_AGE_MS = 30 * 60_000L

        /** A prefetch is skipped when the pack in hand is younger than this. */
        const val PREFETCH_FRESH_MS = 5 * 60_000L

        /** Two refreshes this close together are one (a tap right after an onset prefetch). */
        const val MIN_GAP_MS = 30_000L

        /** The screen-on timer's period (`CascadeWarmup`): a pack at most this old plus a fetch. */
        const val TIMER_MS = 10 * 60_000L
    }
}
