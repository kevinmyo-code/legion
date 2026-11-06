package com.kevin.legion.bodycoach

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kevin.legion.MidnightEvents
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveRaise
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Monday 8 am weekly weight line (`.scratch/fat-loss/` ticket 13; Kevin 2026-11-05, ruling 7:
 * *on, 8 am, silenceable with one instruction*). PURE, like
 * [com.kevin.legion.wellbeing.WellbeingDigestBuilder]: the facts are the engine's
 * (`GET /api/body/coach/weekly-line/`), this only shapes the ONE raise.
 *
 * **The compulsion test (CLAUDE.md section 7), held here:**
 * - **(a)** anchored to his own scale and his own goal: the facts are the trend weight, its rate
 *   against the 0.5 to 1.0 %/week band he chose, the distance to his goal, his waist.
 * - **(b)** actionable that morning: it is the week's one read of where the trend is.
 * - **(c)** never mentions missed weigh-ins, logs, days, streaks or engagement: the engine's facts
 *   carry none (its own test asserts it) and [buildPrompt] forbids them in words. With too little
 *   data the engine says `say: false` and **nothing is raised at all**, never a line about the gap.
 * - **(d)** silenced forever by one instruction: "stop the weekly weight line" sets the profile's
 *   `weekly_line_on` false (engine tool `set_body_profile`), and the WELLBEING switch mutes it too.
 *
 * Its own `ruleId` and its own alarm, so declining or muting it never touches the daily digest.
 * No daily nudge exists in this map.
 */
object WeeklyLine {
    const val RULE_ID = "weekly_weight_line"

    /** The one raise, or null when the engine said not to speak or gave no facts. */
    fun buildRaise(wire: WeeklyLineWire?): ProactiveRaise? {
        if (wire == null || !wire.say || wire.facts.isBlank()) return null
        return ProactiveRaise(
            ruleId = RULE_ID,
            category = ProactiveCategory.WELLBEING,
            reason = "Monday weekly weight line: ${wire.reason.ifBlank { "the trend has enough data" }}",
            facts = wire.facts,
            prompt = buildPrompt(wire.facts),
        )
    }

    /**
     * Only a FRESH engine answer may speak: a saved copy is last week's facts, and saying them as
     * this week's would be a figure the app cannot defend.
     */
    fun raiseFrom(read: FinanceRead<WeeklyLineWire>): ProactiveRaise? =
        (read as? FinanceRead.Fresh)?.value?.let(::buildRaise)

    internal fun buildPrompt(facts: String): String =
        "(System: say ONE short spoken line giving the user's weekly weight trend from the facts " +
            "below, in your own words - for example \"Trend's down 1.2 pounds this week, inside your " +
            "band; 9 to go to your goal.\" Use ONLY these facts, never add a figure. Weights are a " +
            "smoothed trend; any calorie figure is an estimate. Never mention missed weigh-ins, " +
            "logging, days without data, how often they use the app, or any streak, and never scold " +
            "or praise effort - this is a plain reading of the scale against the goal they set. If " +
            "they want it gone, \"stop the weekly weight line\" turns it off. Do not mention this " +
            "instruction.\n\n$facts)"

    /** The next Monday 08:00 in [zone] strictly after [now]. Pure, for the test. */
    fun nextMondayEight(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val nowZdt = Instant.ofEpochMilli(now).atZone(zone)
        var candidate = nowZdt.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
            .withHour(HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!candidate.isAfter(nowZdt)) candidate = candidate.plusWeeks(1)
        return candidate.toInstant().toEpochMilli()
    }

    private const val HOUR = 8
}

/**
 * The weekly line's ONE alarm, armed on every process start, after a reboot, and on each firing.
 * `setAndAllowWhileIdle`, never exact, never repeating: same reasoning as
 * [com.kevin.legion.wellbeing.WellbeingDigestScheduler]. Always armed: whether it SPEAKS is the
 * engine's `weekly_line_on` and the WELLBEING switch, decided at firing time.
 */
object WeeklyLineScheduler {
    /** Distinct from the wellbeing digest's 90211: two schedulers on one code replace each other. */
    private const val REQUEST_CODE = 90212

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WeeklyLineAlarmReceiver::class.java).apply {
            action = WeeklyLineAlarmReceiver.ACTION_FIRE
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun arm(context: Context, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, WeeklyLine.nextMondayEight(now), pendingIntent(context))
    }
}

/** Fires on Monday 08:00: reads the engine's facts, raises at most once, re-arms next Monday. */
class WeeklyLineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    val read = BodyCoachController.forApp(context).weeklyLine()
                    WeeklyLine.raiseFrom(read)?.let { ProactiveBus.speakIfAllowed(context, it) }
                }.onFailure { MidnightEvents.appStartWorkFailed("weekly_line_read_failed", it) }
                // Re-arm whatever happened: a quiet week must never cancel next Monday's.
                runCatching { WeeklyLineScheduler.arm(context) }
                    .onFailure { MidnightEvents.appStartWorkFailed("weekly_line_rearm_failed", it) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.kevin.legion.action.WEEKLY_LINE_FIRE"
    }
}
