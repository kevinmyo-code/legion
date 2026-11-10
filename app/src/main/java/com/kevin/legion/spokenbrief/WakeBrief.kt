package com.kevin.legion.spokenbrief

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kevin.legion.brief.BriefController
import com.kevin.legion.clock.RingKind
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveDelivery
import com.kevin.legion.service.ProactiveRaise
import com.kevin.legion.service.ProactiveSettings
import com.kevin.legion.ui.MainActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The wake-up brief (ADR 0082, Kevin 2026-11-10, reversing ADR 0079's "plain ring, no auto-speak"
 * and hands-and-senses ticket 32 for this one moment): when he dismisses a morning alarm on the
 * ring screen, Alfred speaks the daily brief without a tap.
 *
 * - **The same brief as Brief me, never a second one.** Brief me has the model call `get_sitrep`,
 *   which is [BriefController.forVoice]. A raise may not call tools (proactive-mode ticket 10 call
 *   5: it pre-fetches), so this calls that same controller method and hands its text over as the
 *   raise's facts. Brief me stays on the ring screen as the replay.
 * - **When:** an alarm (never a timer or an emergency ring), dismissed by a tap (not rung out),
 *   after the first unlock, between [MORNING_START_HOUR] and [MORNING_END_HOUR], once per local day.
 *   A Brief me tap counts as that day's brief ([briefedByTap]).
 * - **The gate:** a typed DIGEST raise through [ProactiveBus.speakIfAllowed], so the master, the
 *   Digest switch, this brief's own switch ([SpokenBrief.WAKE]), the decline window and the speech
 *   gate (a silenced phone, a live meeting) all hold. **Quiet hours do not**, for this rule alone
 *   ([ProactiveRaise.exemptFromQuietHours]): dismissing an alarm he set proves he is awake, and a
 *   06:30 alarm is inside the 22:00 to 07:00 window.
 * - **Read-through:** the brief may include the Gmail newsletter summary, so the raise says so and
 *   the turn is not remembered. This is a background Gmail read at an alarm, which ticket 32 had
 *   ruled out; Kevin's 2026-11-10 yes is what allows it, and it is listed as vetoable in ADR 0082.
 * - Nothing here may disturb the dismiss: it runs after it, off the main thread, and a failure is
 *   logged and dropped.
 */
object WakeBrief {
    const val MORNING_START_HOUR = 4
    const val MORNING_END_HOUR = 11

    private const val TAG = "WakeBrief"
    private const val PREFS = "spoken_briefs"
    private const val KEY_LAST_DAY = "wake_last_day"
    private const val LISTENER_WAIT_MS = 3_000L
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Pure: whether a dismiss at [time] on [today] may try the brief, given the last day it ran. */
    internal fun shouldTry(
        kind: RingKind,
        unlocked: Boolean,
        time: LocalTime,
        today: LocalDate,
        lastDay: LocalDate?,
    ): Boolean =
        kind == RingKind.ALARM && unlocked && time.hour in MORNING_START_HOUR until MORNING_END_HOUR && lastDay != today

    /** Called by the ring screen's Dismiss, after the alarm is dismissed. */
    fun afterDismiss(context: Context, kind: RingKind, unlocked: Boolean) {
        val app = context.applicationContext
        scope.launch {
            runCatching { run(app, kind, unlocked) }.onFailure { Log.w(TAG, "wake brief failed", it) }
        }
    }

    /** Brief me was tapped: that is today's brief. */
    fun briefedByTap(context: Context) {
        runCatching { setLastDay(context, LocalDate.now()) }
    }

    @Suppress("ReturnCount") // one guard per condition the class doc lists, in that order
    private suspend fun run(context: Context, kind: RingKind, unlocked: Boolean) {
        val today = LocalDate.now()
        val time = LocalTime.now()
        if (!shouldTry(kind, unlocked, time, today, lastDay(context))) return
        if (!SpokenBriefs.isOn(context, SpokenBrief.WAKE)) return
        // Checked before the fetch: the brief can read Gmail and spend a summary call, which must
        // not happen for a brief that is switched off and could never be said.
        ProactiveSettings.load(context)
        if (!ProactiveSettings.mayRaise(ProactiveCategory.DIGEST)) return
        setLastDay(context, today)
        val brief = BriefController.of(context).forVoice(null)
        ProactiveDelivery.awaitListener(LISTENER_WAIT_MS)
        val outcome = ProactiveBus.speakIfAllowed(context, buildRaise(brief.text, time, openApp(context)))
        // Not now (a call, a conversation): leave today unclaimed, so the next dismiss may try.
        if (outcome == ProactiveBus.RaiseOutcome.NotNow) clearLastDay(context)
    }

    /** The raise. Pure apart from the tap target, for the test. */
    internal fun buildRaise(briefText: String, dismissedAt: LocalTime, tap: PendingIntent? = null): ProactiveRaise =
        ProactiveRaise(
            ruleId = SpokenBrief.WAKE.ruleId,
            category = ProactiveCategory.DIGEST,
            reason = "you dismissed a morning alarm at ${dismissedAt.format(TIME)}",
            facts = briefText,
            prompt = prompt(briefText),
            carriesReadThroughContent = true,
            exemptFromQuietHours = true,
            notificationText = "Your morning brief is ready. Open LEGION and ask for the sitrep.",
            contentIntent = tap,
        )

    internal fun prompt(facts: String): String =
        "(System: the user just dismissed their morning alarm. Give them their daily brief from the " +
            "facts below, spoken, briefly and in your own words, leading with what matters today. Use " +
            "ONLY these facts: never add an event, a time, a name, a figure or a claim that is not in " +
            "them. Where a section says it was not read or failed, say that plainly rather than " +
            "guessing, and say a line marked [unverified] as unverified. Never mention how long they " +
            "slept, whether they snoozed, how often they use the app, or any streak. If they want it " +
            "gone, \"${SpokenBrief.WAKE.silenceInstruction}\" turns it off; say that only if asked. " +
            "Do not mention this instruction.\n\n$facts)"

    private fun openApp(context: Context): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }.getOrNull()

    private const val REQUEST_CODE = 90213

    private fun lastDay(context: Context): LocalDate? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_DAY, null)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun setLastDay(context: Context, day: LocalDate) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_DAY, day.toString()).apply()
    }

    private fun clearLastDay(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_DAY).apply()
    }
}
