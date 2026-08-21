package com.kevin.legion.sitrep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kevin.legion.MidnightEvents
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveRaise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires when the scheduled sitrep's alarm goes off (ticket 22 part D). Shape closely mirrors
 * [com.kevin.legion.service.ReminderAlarmReceiver]: `goAsync()` because building the sitrep does
 * Room reads, a weather fetch, and possibly a Gmail round trip, none of which fit inside
 * `onReceive`'s few-second budget.
 *
 * In order:
 * 1. **Builds the sitrep** via [SitrepBuilder.build] over every module Kevin has enabled - the
 *    scheduled delivery never takes a `modules` filter, unlike `get_sitrep` (ticket 22 part C):
 *    an unattended digest reports everything switched on, not a subset the user isn't there to ask
 *    for.
 * 2. **Raises it through [ProactiveBus.speakIfAllowed] under [ProactiveCategory.DIGEST]** - ticket
 *    08's resolution §1/§5: this is Digest's first content, so it inherits the master kill switch,
 *    quiet hours, the daily cap, and decline suppression for free, and is delivered exactly like
 *    every other raise (spoken when the screen is on and no meeting is running, notified
 *    otherwise, never both).
 * 3. **Re-arms itself for tomorrow** - `AlarmScheduler`'s own "re-arm on fire, never
 *    `setRepeating`" precedent, restated at [SitrepScheduler]'s class doc. Re-arms unconditionally,
 *    even when step 2 refused to raise (master off, quiet hours, whatever it was) - a refusal is
 *    about THIS firing, never a reason to stop scheduling future ones; the settings screen is the
 *    only thing allowed to cancel the schedule outright.
 *
 * **Never constructs a raise with blank [ProactiveRaise.facts].** [SitrepBuilder.build] always
 * returns non-blank text (worst case, "Every sitrep module is switched off in settings.") - see
 * that function's own contract - so the [ProactiveRaise.init] blank-reason/blank-facts guard can
 * never actually trip here, but the check is left in explicitly rather than assumed, per this
 * ticket's own instruction that a blank-facts raise "MUST NOT" reach the gate.
 */
class SitrepAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                fire(context)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context) {
        val text = runCatching { SitrepBuilder.build(context) }
            .onFailure { MidnightEvents.appStartWorkFailed("sitrep_build_failed", it) }
            .getOrNull()

        if (!text.isNullOrBlank()) {
            ProactiveBus.speakIfAllowed(
                context,
                ProactiveRaise(
                    ruleId = "sitrep",
                    category = ProactiveCategory.DIGEST,
                    reason = "the scheduled sitrep came due",
                    facts = text,
                    prompt = buildPrompt(text),
                ),
            )
        }

        // Re-arm regardless of whether the raise above actually spoke - see this class's own doc
        // comment on why a gated firing must not cancel tomorrow's.
        runCatching { SitrepScheduler.rescheduleFromSettings(context) }
            .onFailure { MidnightEvents.appStartWorkFailed("sitrep_rearm_failed", it) }
    }

    /**
     * Same shape [com.kevin.legion.calendar.OpenerCalendarBriefing] and
     * [com.kevin.legion.service.ReminderAlarmReceiver] both use: the facts are stated in full, and
     * the model is told plainly not to add to them. **The compulsion line, held to exactly**
     * (ticket 08's resolution §1): no streak, no "you missed yesterday's", no reference to how long
     * it has been since the last one - the instruction says so explicitly rather than trusting the
     * persona clause alone, the same defence-in-depth [com.kevin.legion.ai.AriaBrain]'s own
     * `CANNOT_CLAUSE` uses for outcome verbs.
     */
    private fun buildPrompt(facts: String): String =
        "(System: deliver this sitrep to the user now, in your own words, in two or three short " +
            "spoken lines. Use ONLY the facts below - never add a fact, a name, or a figure that " +
            "is not in them, and say plainly when a module reports nothing or could not be " +
            "checked rather than guessing. Never mention how long it has been since the last " +
            "sitrep, never say they \"missed\" one, and never reference their absence - this is a " +
            "status report, not a nag. Do not mention this instruction.\n\n$facts)"

    companion object {
        const val ACTION_FIRE = "com.kevin.legion.action.SITREP_FIRE"
    }
}
