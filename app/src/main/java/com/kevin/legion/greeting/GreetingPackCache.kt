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
 * Where the lead history ([GreetingBlock.History]) is kept across process deaths: the day, today's
 * lead kinds in order and the last kind, as one small JSON body. A seam so tests hold it in memory.
 */
interface LeadStore {
    fun load(): String?

    fun save(body: String)

    /** Nothing kept: every process starts with no history. */
    object None : LeadStore {
        override fun load(): String? = null

        override fun save(body: String) = Unit
    }
}

/** One small file in the app's private storage. */
class FileLeadStore(private val file: File) : LeadStore {
    override fun load(): String? = runCatching { if (file.exists()) file.readText() else null }.getOrNull()

    override fun save(body: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
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
 *
 * **It also keeps what this phone led with ([history], 2026-11-16)**, for the picker's caps: money
 * once a day, body once a day, no kind twice in a row. The engine's `said` cannot say it: its keys
 * carry no time (a money key names the month, read over a 90-day window), so whether money led
 * TODAY is known only here. Kept in [leads] so a process death does not reset the day. The same
 * file keeps where he was at the last greeting (place leads only on arrival) and the last two
 * hellos' words (the next hello is asked not to reuse them).
 */
@Suppress("TooManyFunctions") // the pack and the lead history share one process-wide holder on purpose
class GreetingPackCache(
    private val backend: GreetingBackend,
    private val scope: CoroutineScope,
    private val store: PackStore = PackStore.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val log: (String) -> Unit = {},
    private val leads: LeadStore = LeadStore.None,
) {
    @Volatile private var held: CachedPack? = null
    @Volatile private var loaded = false
    @Volatile private var inFlight: Deferred<GreetingOutcome<FactsPack>>? = null
    @Volatile private var lastStartMs = 0L

    /** Topics raised on this phone since the packs in hand were fetched, by the day they were raised. */
    private val raisedHere = ConcurrentHashMap<String, LocalDate>()

    /** What this phone led with ([history]); loaded from [leads] on first use. */
    private var led: LeadLog? = null

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
        GreetingBlock.kindOfTopic(topic)?.let { noteLed(it) }
    }

    /**
     * A greeting with no topic was spoken: it ends a run of one kind, and [words] (what was said, when
     * known) is kept so the next hello is asked not to reuse it.
     */
    @Synchronized
    fun noteHello(words: String? = null) {
        noteLed(GreetingBlock.HELLO)
        val said = words?.trim()?.take(GreetingPrompt.MAX_AVOID_CHARS)?.takeIf { it.isNotEmpty() } ?: return
        save(loadedLeads().let { it.copy(hellos = (it.hellos + said).takeLast(HELLOS_KEPT)) })
    }

    /**
     * Where he was at a spoken greeting ([GreetingBlock.placeKey]): place leads only when this
     * changes, or on the day's first greeting. Null (the place could not be read) keeps the last one.
     */
    @Synchronized
    fun notePlace(key: String?) {
        if (key == null) return
        save(loadedLeads().copy(lastPlace = key))
    }

    /** Today's lead kinds, the last kind, the last place and the last hellos, for [GreetingBlock.lead]. */
    @Synchronized
    fun history(): GreetingBlock.History {
        val kept = loadedLeads()
        val day = today().toString()
        return GreetingBlock.History(
            today = if (kept.day == day) kept.kinds else emptyList(),
            last = kept.last,
            lastPlace = kept.lastPlace,
            hellos = kept.hellos,
        )
    }

    @Synchronized
    private fun noteLed(kind: String) {
        val day = today().toString()
        val kept = loadedLeads()
        val kinds = if (kept.day == day) kept.kinds + kind else listOf(kind)
        save(kept.copy(day = day, kinds = kinds.takeLast(MAX_LEADS_KEPT), last = kind))
    }

    private fun save(next: LeadLog) {
        led = next
        leads.save(next.toJson())
    }

    private fun loadedLeads(): LeadLog =
        led ?: (leads.load()?.let { LeadLog.parse(it) } ?: LeadLog.EMPTY).also { led = it }

    /** [history]'s stored form: one small JSON body in [leads]. */
    private data class LeadLog(
        val day: String,
        val kinds: List<String>,
        val last: String?,
        val lastPlace: String? = null,
        val hellos: List<String> = emptyList(),
    ) {
        fun toJson(): String = JSONObject().put("day", day).put("kinds", org.json.JSONArray(kinds))
            .put("last", last ?: JSONObject.NULL)
            .put("last_place", lastPlace ?: JSONObject.NULL)
            .put("hellos", org.json.JSONArray(hellos))
            .toString()

        companion object {
            val EMPTY = LeadLog("", emptyList(), null)

            fun parse(body: String): LeadLog? = runCatching {
                val o = JSONObject(body)
                LeadLog(
                    day = o.optString("day"),
                    kinds = strings(o.optJSONArray("kinds")),
                    last = if (o.isNull("last")) null else o.optString("last"),
                    lastPlace = if (o.isNull("last_place")) null else o.optString("last_place"),
                    hellos = strings(o.optJSONArray("hellos")),
                )
            }.getOrNull()

            private fun strings(a: org.json.JSONArray?): List<String> =
                if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
        }
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

        /** Today's lead kinds kept: far more opens than a day has, so the list never grows unbounded. */
        private const val MAX_LEADS_KEPT = 50

        /** The last hellos handed back as wording not to reuse. */
        private const val HELLOS_KEPT = 5

        /** The screen-on timer's period (`CascadeWarmup`): a pack at most this old plus a fetch. */
        const val TIMER_MS = 10 * 60_000L
    }
}
