package com.kevin.legion.clock

import java.time.ZoneId
import java.time.ZonedDateTime

/** Test doubles for the clock's ports. Plain JVM, no Android. */
class FakeTime(
    var now: Long,
    var elapsed: Long = 1_000_000L,
    var zoneId: ZoneId = ZoneId.of("America/Chicago"),
    var boot: Int = 1,
) : ClockTime {
    override fun nowMs(): Long = now
    override fun elapsedMs(): Long = elapsed
    override fun zone(): ZoneId = zoneId
    override fun bootCount(): Int = boot

    fun advance(ms: Long) {
        now += ms
        elapsed += ms
    }

    companion object {
        fun at(iso: String, zone: String = "America/Chicago"): FakeTime =
            FakeTime(ZonedDateTime.of(java.time.LocalDateTime.parse(iso), ZoneId.of(zone)).toInstant().toEpochMilli(),
                zoneId = ZoneId.of(zone))
    }
}

class FakeScheduler : ClockScheduler {
    val alarms = mutableMapOf<Int, Long>()
    val timers = mutableMapOf<Int, Long>()
    var bedtime: Pair<Long?, Long>? = null
    override fun armAlarm(id: Int, atMs: Long) { alarms[id] = atMs }
    override fun cancelAlarm(id: Int) { alarms.remove(id) }
    override fun armTimer(id: Int, atElapsed: Long) { timers[id] = atElapsed }
    override fun cancelTimer(id: Int) { timers.remove(id) }
    override fun armBedtime(windDownAtMs: Long?, startAtMs: Long) { bedtime = windDownAtMs to startAtMs }
    override fun cancelBedtime() { bedtime = null }
}

class FakeNotices : ClockNotices {
    val timers = mutableMapOf<Int, ClockTimer>()
    val missed = mutableListOf<ClockAlarm>()
    var windDowns = 0
    var bedtimeSentence: String? = null
    override fun timer(timer: ClockTimer) { timers[timer.id] = timer }
    override fun timerGone(id: Int) { timers.remove(id) }
    override fun missedAlarm(alarm: ClockAlarm) { missed += alarm }
    override fun windDown(bedtime: Bedtime, nextAlarm: String?) { windDowns++ }
    override fun bedtimeOn(sentence: String) { bedtimeSentence = sentence }
    override fun bedtimeGone() { bedtimeSentence = null }
}

class FakeRinger : Ringer {
    val ringing = mutableListOf<RingRequest>()
    override fun ring(request: RingRequest) { ringing += request }
    override fun stop(kind: RingKind, id: Int) { ringing.removeAll { it.kind == kind && it.id == id } }
}

class FakeEffects(
    var quiet: Boolean = false,
    var feed: Boolean = false,
    var dndGranted: Boolean = true,
    var isUnlocked: Boolean = true,
) : BedtimeEffects {
    override fun unlocked(): Boolean = isUnlocked
    override fun quietOn(): BedtimeEffects.On = when {
        !dndGranted -> BedtimeEffects.On.Refused("Quiet needs Do Not Disturb access.")
        quiet -> BedtimeEffects.On.AlreadyOn
        else -> { quiet = true; BedtimeEffects.On.TurnedOn }
    }
    override fun quietOff(): String? { quiet = false; return null }
    override fun feedOn(): BedtimeEffects.On = if (feed) {
        BedtimeEffects.On.AlreadyOn
    } else {
        feed = true
        BedtimeEffects.On.TurnedOn
    }
}

/** One controller over fakes, with every fake reachable. */
class ClockRig(
    val time: FakeTime = FakeTime.at("2026-11-09T22:00"),
    val store: MemoryClockStore = MemoryClockStore(),
    val effects: FakeEffects = FakeEffects(),
) {
    val scheduler = FakeScheduler()
    val notices = FakeNotices()
    val ringer = FakeRinger()
    val clock = ClockController(store, time, scheduler, notices, ringer, effects)

    fun ms(iso: String): Long =
        ZonedDateTime.of(java.time.LocalDateTime.parse(iso), time.zoneId).toInstant().toEpochMilli()
}
