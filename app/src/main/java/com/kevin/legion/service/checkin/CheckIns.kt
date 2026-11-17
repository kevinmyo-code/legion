package com.kevin.legion.service.checkin

import android.content.Context
import com.kevin.legion.MidnightApplication
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.greeting.AndroidPlaceSignals
import com.kevin.legion.backend.engine.DjangoGreetingBackend
import com.kevin.legion.greeting.FactsPack
import com.kevin.legion.greeting.GreetingBlock
import com.kevin.legion.greeting.GreetingFactory
import com.kevin.legion.greeting.GreetingOpening
import com.kevin.legion.greeting.GreetingPackCache
import com.kevin.legion.greeting.GreetingPlace
import com.kevin.legion.greeting.GreetingSource
import com.kevin.legion.service.ConversationState
import com.kevin.legion.service.LiveLatency
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveDelivery
import com.kevin.legion.service.ProactiveRaise
import com.kevin.legion.service.ProactiveSettings
import com.kevin.legion.service.TelephonyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The Android half of listening check-ins ([CheckInRules] holds the rules): when to look, what the
 * phone remembers, and what happens after a check-in was spoken.
 *
 * **When it looks:** at an unlock (the morning moment needs one) and on the warmup timer
 * (`CascadeWarmup`, every [GreetingPackCache.TIMER_MS]), each time against the greeting's facts
 * pack in hand. One check-in per look at most.
 *
 * **What it remembers** ([CheckInLedger], SharedPreferences): each topic asked and how often it was
 * SPOKEN, so one retry at most and then the topic is closed; the engine is told only once a topic
 * is done (`answered` when he spoke, `raised` otherwise), which is what keeps the greeting and the
 * next pack from asking it again (ADR 0091, never the same question twice).
 *
 * Nothing here logs what was asked or answered: the log says the moment, the outcome or the reason
 * a look raised nothing, never the topic or its words.
 */
object CheckIns {
    private const val PREFS = "check_ins"
    private const val KEY_LEDGER = "ledger"

    /** An unlock is usually followed by a tap within seconds: a check-in waits that long first. */
    private const val UNLOCK_DELAY_MS = 4_000L

    /** The most a look waits for a fresh facts pack before using the one in hand (or none). */
    private const val PACK_WAIT_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private fun log(line: String) = LiveLatency.log("CHECK_IN", line)

    /** Looks for a check-in to raise now. Never throws, never blocks the caller. */
    fun evaluate(context: Context, trigger: CheckInMoments.Trigger) {
        val app = context.applicationContext
        scope.launch {
            if (trigger == CheckInMoments.Trigger.UNLOCK) delay(UNLOCK_DELAY_MS)
            runCatching { lock.withLock { look(app, trigger) } }
                .onFailure { log("look failed: ${it.javaClass.simpleName}") }
        }
    }

    @Suppress("ReturnCount") // one worded exit per refusal
    private suspend fun look(app: Context, trigger: CheckInMoments.Trigger) {
        ProactiveSettings.load(app)
        val now = LocalDateTime.now()
        val silenced = ProactiveDelivery.phoneSilenced(app)
        val refusal = CheckInRules.refusal(
            CheckInRules.Now(
                switchOn = ProactiveSettings.mayRaise(ProactiveCategory.CHECK_INS),
                time = now.toLocalTime(),
                inCallOrRinging = TelephonyController.isInCall || TelephonyController.isRinging,
                matrixOn = (app as? MidnightApplication)?.dumb?.controller?.state?.value?.on == true,
                conversationBusy = ConversationState.isBusy,
                mayListen = ProactiveDelivery.mayListen(),
                silenced = silenced,
                inMeeting = !silenced && !ProactiveDelivery.maySpeakAloud(app),
                listenerUp = ProactiveBus.hasListener,
            ),
        )
        if (refusal != null) {
            log("none trigger=${trigger.name.lowercase()} reason=$refusal")
            return
        }
        val cache = GreetingFactory.cache(app)
        withTimeoutOrNull(PACK_WAIT_MS) { cache.refresh("check_in").await() }
        val usable = cache.usable() ?: run {
            log("none trigger=${trigger.name.lowercase()} reason=no_pack")
            return
        }
        val pack = usable.pack
        val place = readPlace(app, pack.said, pack.closed)
        var ledger = load(app).pruned(System.currentTimeMillis())
        val pick = CheckInMoments.pick(pack, place, now, trigger, ledger, history = cache.history())
        if (trigger == CheckInMoments.Trigger.UNLOCK && now.hour in
            CheckInRules.QUIET_END_HOUR until CheckInRules.MORNING_END_HOUR
        ) {
            ledger = ledger.copy(morningDay = LocalDate.now().toString())
        }
        if (pick == null) {
            save(app, ledger)
            log("none trigger=${trigger.name.lowercase()} reason=no_moment")
            return
        }
        val outcome = ProactiveBus.speakIfAllowed(app, raiseFor(pack, place, now.toLocalTime(), pick))
        if (outcome is ProactiveBus.RaiseOutcome.Raised) {
            ledger = ledger.raised(pick.item.topic, pick.sourceName, pick.moment, System.currentTimeMillis())
        }
        save(app, ledger)
        log("raise moment=${pick.moment.name.lowercase()} outcome=${outcome.javaClass.simpleName}")
    }

    /**
     * The raise for [pick]. A money topic is said as the greeting says it (stage 5): the category
     * and that it is over its cap, never the figures; the block shows that line in place of the
     * amounts, and the reason (logged) carries no figure either.
     */
    private fun raiseFor(pack: FactsPack, place: GreetingSource, time: LocalTime, pick: CheckInPick): ProactiveRaise {
        val money = pick.sourceName == MONEY
        val block = GreetingBlock.render(pack, place, time, lead = pick.item.takeIf { money })
        val topic = if (money) pick.item.copy(says = GreetingBlock.moneyLeadLine(pick.item)) else pick.item
        return ProactiveRaise(
            ruleId = CheckInRules.ruleId(pick.sourceName),
            category = ProactiveCategory.CHECK_INS,
            reason = "Check-in, because ${pick.moment.words}: ${topic.says}",
            facts = block,
            prompt = CheckInPrompt.build(block, topic, pick.moment),
            listensForReply = true,
            checkIn = CheckInMark(pick.item.topic, pick.moment),
        )
    }

    private suspend fun readPlace(app: Context, said: Set<String>, closed: Set<String>): GreetingSource {
        val read = withTimeoutOrNull(PACK_WAIT_MS) {
            runCatching { GreetingPlace.read(AndroidPlaceSignals(app)) }.getOrNull()
        } ?: GreetingPlace.failed("timeout")
        return GreetingPlace.applyTopics(read, said, closed)
    }

    /**
     * How a spoken check-in ended (the cascade session reports it once). Counts the ask; a topic
     * that is now done is told to the engine and marked raised in the pack cache.
     */
    fun onOutcome(context: Context, mark: CheckInMark, outcome: CheckInOutcome) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val done = lock.withLock {
                    val (next, done) = load(app).outcome(mark.topic, outcome)
                    save(app, next)
                    done
                }
                if (!done) return@runCatching
                GreetingFactory.cache(app).noteRaised(mark.topic)
                val state = if (outcome == CheckInOutcome.ANSWERED) STATE_ANSWERED else GreetingOpening.STATE_RAISED
                DjangoGreetingBackend(EngineHttp(EngineConfig(app))).recordTopic(mark.topic, state)
            }.onFailure { log("outcome not recorded: ${it.javaClass.simpleName}") }
        }
    }

    private const val STATE_ANSWERED = "answered"
    private const val MONEY = "money"

    private fun load(app: Context): CheckInLedger = runCatching {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LEDGER, null)
            ?.let(CheckInLedgerJson::parse)
    }.getOrNull() ?: CheckInLedger()

    private fun save(app: Context, ledger: CheckInLedger) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LEDGER, CheckInLedgerJson.write(ledger)).apply()
    }
}

/** [CheckInLedger] to and from JSON. Pure, so the round trip is a JVM test. */
internal object CheckInLedgerJson {
    fun write(l: CheckInLedger): String = JSONObject().apply {
        put("morningDay", l.morningDay ?: JSONObject.NULL)
        put("lastRaisedAtMs", l.lastRaisedAtMs)
        put(
            "topics",
            JSONObject().apply {
                l.topics.forEach { (k, e) ->
                    put(
                        k,
                        JSONObject()
                            .put("source", e.sourceName)
                            .put("moment", e.moment.name)
                            .put("asks", e.asks)
                            .put("lastAtMs", e.lastAtMs)
                            .put("done", e.done),
                    )
                }
            },
        )
    }.toString()

    fun parse(json: String): CheckInLedger {
        val o = JSONObject(json)
        val t = o.optJSONObject("topics") ?: JSONObject()
        val topics = t.keys().asSequence().mapNotNull { k ->
            val e = t.optJSONObject(k) ?: return@mapNotNull null
            val moment = runCatching { CheckInMoment.valueOf(e.optString("moment")) }.getOrNull()
                ?: return@mapNotNull null
            k to CheckInLedger.Entry(
                sourceName = e.optString("source"),
                moment = moment,
                asks = e.optInt("asks"),
                lastAtMs = e.optLong("lastAtMs"),
                done = e.optBoolean("done"),
            )
        }.toMap()
        return CheckInLedger(
            morningDay = if (o.isNull("morningDay")) null else o.optString("morningDay"),
            lastRaisedAtMs = o.optLong("lastRaisedAtMs"),
            topics = topics,
        )
    }
}
