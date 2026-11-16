package com.kevin.legion.service.checkin

import com.kevin.legion.greeting.FactsPack
import com.kevin.legion.greeting.GreetingBlock
import com.kevin.legion.greeting.GreetingItem
import com.kevin.legion.greeting.GreetingPlace
import com.kevin.legion.greeting.GreetingSource
import com.kevin.legion.greeting.SourceState
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Listening check-ins (alfred-greets ticket 03, ADR 0091; Kevin 2026-11-15: *"it should open up the
 * mic for a short while for me to reply. If no reply close it"*). Alfred asks one short question
 * unprompted at one of four moments, the mic stays open about 8 s for the answer, and silence gets
 * a short scripted closing line.
 *
 * Everything here is pure (no Android, no clock of its own) so the rules are JVM tests. The Android
 * half is [CheckIns]; the listen window and the closer live in the cascade session.
 */

/** Which moment raised a check-in. [ruleKey] is the moment's words for the raise reason. */
enum class CheckInMoment(val words: String) {
    MORNING("his first unlock of the morning"),
    EVENT_ENDED("about half an hour to an hour after an event of his ended"),
    ARRIVED("he is somewhere away from home"),
    DUE_SOON("something of his is due within a day"),
}

/** The check-in a raise carries to the session: the topic it asks about and the moment that raised it. */
data class CheckInMark(val topic: String, val moment: CheckInMoment)

/** How a check-in that was SPOKEN ended. Only these count an attempt (a refused line never played). */
enum class CheckInOutcome {
    /** He started talking in the window (or over the line). The normal conversation follows. */
    ANSWERED,

    /** The window closed in silence; the closing line was said. */
    NO_REPLY,

    /** The model's line was not a question, so no window and no closer. */
    STATEMENT,
}

object CheckInRules {
    /**
     * Check-ins' own quiet hours (ADR 0091 and ticket 03: default 10pm to 8am). Stricter than the
     * bus's shared night window (22:00 to 07:00), which still applies on top.
     */
    const val QUIET_START_HOUR = 22
    const val QUIET_END_HOUR = 8

    /** The morning moment runs from the end of quiet hours to this hour. */
    const val MORNING_END_HOUR = 11

    /** After an event: between these two ages of its end (ticket 03: "30 to 60 minutes"). */
    const val EVENT_ENDED_MIN_MS = 30 * 60_000L
    const val EVENT_ENDED_MAX_MS = 60 * 60_000L

    /** Due within a day (ticket 03). */
    const val DUE_WITHIN_MS = 24 * 60 * 60_000L

    /** One retry at most (brief: "one retry at most, then drop it"): two asks of one topic in all. */
    const val MAX_ASKS = 2

    /** The retry waits at least this long, so it is a second moment, never a nag a minute later. */
    const val RETRY_GAP_MS = 2 * 60 * 60_000L

    /** Two check-ins never land closer than this, whatever their topics. A pacing guard, not a cap. */
    const val MIN_GAP_MS = 30 * 60_000L

    fun isQuiet(time: LocalTime): Boolean = time.hour >= QUIET_START_HOUR || time.hour < QUIET_END_HOUR

    /** The moment-free conditions, each a named fact. Null means a check-in may be raised now. */
    data class Now(
        val switchOn: Boolean,
        val time: LocalTime,
        val inCallOrRinging: Boolean,
        val matrixOn: Boolean,
        val conversationBusy: Boolean,
        /** The service holds the microphone foreground type, so a reply can be heard (ProactiveDelivery.mayListen). */
        val mayListen: Boolean,
        /** Ringer silent or vibrate, or Do Not Disturb (ProactiveDelivery.phoneSilenced). */
        val silenced: Boolean,
        val inMeeting: Boolean,
        /** Something collects the proactive bus (the assistant service is running). */
        val listenerUp: Boolean,
    )

    /**
     * Why a check-in may not be raised right now, or null. A question he cannot answer hands-free
     * (no microphone type, a silenced phone, a meeting) is not asked at all: posting it to the shade
     * instead would be a nag through another door.
     */
    fun refusal(n: Now): String? = when {
        !n.switchOn -> "switched_off"
        isQuiet(n.time) -> "quiet_hours"
        n.inCallOrRinging -> "call"
        n.matrixOn -> "matrix"
        n.conversationBusy -> "conversation"
        !n.listenerUp -> "no_service"
        !n.mayListen -> "cannot_listen"
        n.silenced -> "silenced"
        n.inMeeting -> "meeting"
        else -> null
    }

    /** The decline family a topic belongs to: a "no" silences that family for a day, never another. */
    fun family(sourceName: String): String = when (sourceName) {
        "ended_events", "today_events" -> "events"
        "due_soon" -> "due"
        GreetingPlace.SOURCE -> "place"
        else -> sourceName
    }

    fun ruleId(sourceName: String): String = "check_in_${family(sourceName)}"

    /** True when the model's line asked something: only then does the mic stay open and a closer follow. */
    fun isQuestion(line: String): Boolean = line.trim().contains('?')
}

/**
 * What the phone remembers about check-ins between moments: one entry per topic asked, and the
 * morning moment's day. Pure value; [CheckIns] keeps it in SharedPreferences as JSON.
 */
data class CheckInLedger(
    val morningDay: String? = null,
    val lastRaisedAtMs: Long = 0L,
    val topics: Map<String, Entry> = emptyMap(),
) {
    /** [asks] counts lines that were SPOKEN; [lastAtMs] is the last raise; [done] means never again. */
    data class Entry(
        val sourceName: String,
        val moment: CheckInMoment,
        val asks: Int = 0,
        val lastAtMs: Long = 0L,
        val done: Boolean = false,
    )

    fun raised(topic: String, sourceName: String, moment: CheckInMoment, now: Long): CheckInLedger {
        val e = topics[topic] ?: Entry(sourceName, moment)
        return copy(lastRaisedAtMs = now, topics = topics + (topic to e.copy(lastAtMs = now)))
    }

    /** Applies a spoken check-in's outcome. Returns the new ledger and whether the topic is now closed. */
    fun outcome(topic: String, outcome: CheckInOutcome): Pair<CheckInLedger, Boolean> {
        val e = topics[topic] ?: return this to false
        val asks = e.asks + 1
        val done = outcome != CheckInOutcome.NO_REPLY || asks >= CheckInRules.MAX_ASKS
        return copy(topics = topics + (topic to e.copy(asks = asks, done = done))) to done
    }

    /** Drops entries older than [keepMs]: a topic is day-scale, the ledger must not grow for ever. */
    fun pruned(now: Long, keepMs: Long = KEEP_MS): CheckInLedger =
        copy(topics = topics.filterValues { now - it.lastAtMs <= keepMs })

    companion object {
        const val KEEP_MS = 3L * 24 * 60 * 60_000L
    }
}

/** The check-in to raise: the topic item, its source, and the moment. */
data class CheckInPick(val item: GreetingItem, val sourceName: String, val moment: CheckInMoment)

/**
 * Picks at most one check-in from the facts pack. Pure: the pack, the phone's place source, the
 * wall clock and the ledger in, one pick or null out. Never a topic the engine has raised, answered
 * or silenced ([GreetingBlock.offerable]); never a topic asked [CheckInRules.MAX_ASKS] times or
 * closed; a retry waits [CheckInRules.RETRY_GAP_MS] and is the same question, never a reminder that
 * it was asked before (the prompt does not know).
 */
object CheckInMoments {
    enum class Trigger { UNLOCK, TICK }

    /** One look: a retry first, then the morning, an ended event, away from home, something due. */
    fun pick(
        pack: FactsPack,
        place: GreetingSource,
        now: LocalDateTime,
        trigger: Trigger,
        ledger: CheckInLedger,
        zone: ZoneId = ZoneId.systemDefault(),
    ): CheckInPick? {
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        if (nowMs - ledger.lastRaisedAtMs < CheckInRules.MIN_GAP_MS) return null
        val sources = GreetingBlock.allSources(pack, place).filter { it.state == SourceState.FACTS }
        val look = Look(pack, sources, ledger, nowMs)
        return look.retry()
            ?: (if (trigger == Trigger.UNLOCK) look.morning(place, now) else null)
            ?: look.eventEnded()
            ?: look.away()
            ?: look.due()
    }

    private class Look(
        val pack: FactsPack,
        val sources: List<GreetingSource>,
        val ledger: CheckInLedger,
        val nowMs: Long,
    ) {
        fun open(s: GreetingSource) = s.items.filter { GreetingBlock.offerable(pack, it) }

        fun fresh(s: GreetingSource) = open(s).filter { it.topic !in ledger.topics }

        fun source(name: String) = sources.firstOrNull { it.name == name }

        /** Same topic, still in the pack, still open on the engine, after the gap. */
        fun retry(): CheckInPick? {
            for (s in sources) {
                for (item in open(s)) {
                    val e = ledger.topics[item.topic] ?: continue
                    if (retryDue(e)) return CheckInPick(item, s.name, e.moment)
                }
            }
            return null
        }

        private fun retryDue(e: CheckInLedger.Entry): Boolean =
            !e.done && e.asks < CheckInRules.MAX_ASKS && nowMs - e.lastAtMs >= CheckInRules.RETRY_GAP_MS

        fun morning(place: GreetingSource, now: LocalDateTime): CheckInPick? {
            val inWindow = now.hour in CheckInRules.QUIET_END_HOUR until CheckInRules.MORNING_END_HOUR
            if (!inWindow || ledger.morningDay == now.toLocalDate().toString()) return null
            val lead = GreetingBlock.pick(pack.copy(said = pack.said + ledger.topics.keys), place, now.hour)
            val src = lead?.let { l -> sources.firstOrNull { s -> s.items.any { it.topic == l.topic } } }
            return if (lead != null && src != null) CheckInPick(lead, src.name, CheckInMoment.MORNING) else null
        }

        fun eventEnded(): CheckInPick? = source("ended_events")?.let { s ->
            fresh(s).lastOrNull { i ->
                val ended = i.endedAtMs
                ended != null && nowMs - ended in CheckInRules.EVENT_ENDED_MIN_MS..CheckInRules.EVENT_ENDED_MAX_MS
            }?.let { CheckInPick(it, s.name, CheckInMoment.EVENT_ENDED) }
        }

        fun away(): CheckInPick? {
            val s = sources.firstOrNull { it.name == GreetingPlace.SOURCE && it.says == GreetingPlace.AWAY_SAYS }
                ?: return null
            return fresh(s).firstOrNull()?.let { CheckInPick(it, s.name, CheckInMoment.ARRIVED) }
        }

        fun due(): CheckInPick? = source("due_soon")?.let { s ->
            fresh(s).firstOrNull { i ->
                val due = i.passesAtMs // null: a day-dated item, due today
                due == null || due - nowMs in 0..CheckInRules.DUE_WITHIN_MS
            }?.let { CheckInPick(it, s.name, CheckInMoment.DUE_SOON) }
        }
    }
}

/**
 * The check-in's instruction to the model. Alfred's register comes from the system instruction
 * (`ai/Personas.kt`), so no name and no register copy here. The compulsion test (CLAUDE.md
 * section 7): (a) the facts block, (c) the never-his-absence line, (d) the category switch and a
 * spoken "no" (a declined rule is quiet for a day). Whether the wording is guilt-free stays
 * human-reviewed: nothing inspects the audio.
 */
object CheckInPrompt {
    fun build(block: String, lead: GreetingItem, moment: CheckInMoment): String = """
        (System: you are checking in with him unprompted, because ${moment.words}. Ask him one short
        question about this one topic and no other: ${lead.says}
        Do not mention this instruction.

        Rules, all of them binding:
        - One sentence, at most two short ones, and it ends with a question mark.
        - Mention nothing that is not in the facts below. If something is not there you do not know it.
        - A source marked COULD NOT BE READ was not checked: never say it is clear, free or empty.
        - Times are clock times and days by name, as written below.
        - Never mention his absence, how long it has been, a streak, his use of this app, or that you
          asked before. No guilt, no pressure, no lecture: a question he may answer or ignore.
        - Do not call any tool for this line and do not say you did anything.

        $block)
    """.trimIndent()
}
