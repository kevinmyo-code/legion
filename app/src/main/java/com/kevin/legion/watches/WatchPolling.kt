package com.kevin.legion.watches

import android.content.Context
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveDelivery
import com.kevin.legion.service.ProactiveRaise
import com.kevin.legion.service.ProactiveSettings

/**
 * The ONE [WatchFiringPoller] for the process, and how a firing becomes a raise (ADR 0083). It
 * rides the Claude result watcher's two existing loops (`ClaudeResultPolling`): no new alarm, no
 * WorkManager, no second loop. See [WatchFiringPoller] for exactly when that means nothing polls.
 *
 * **The raise** goes through [ProactiveBus.speakIfAllowed] in [ProactiveCategory.WATCHES]: inside
 * the master switch, quiet hours and (on this branch) the daily cap, which turn a line that may not
 * be spoken into the proactive notification instead. Several firings are one line. Its facts are
 * the engine's own sentences from the rows (ticket 10), and its reason names each watch and its
 * fact, which is what "why did you say that?" reads.
 *
 * **Compulsion test (CLAUDE.md section 7):** (a) each line is a fact from a record he can look up
 * (a bank row, a Canvas item, a service schedule), named in the firing's `sources`; (b) actionable,
 * because he asked for exactly this or it is his money, coursework or car; (c) it never mentions his
 * absence or engagement, only the fact; (d) "stop watching X" removes that watch, and the Watches
 * category switch silences them all in one instruction.
 */
object WatchPolling {
    @Volatile private var poller: WatchFiringPoller? = null

    fun poller(context: Context): WatchFiringPoller {
        poller?.let { return it }
        return synchronized(this) {
            poller ?: build(context.applicationContext).also { poller = it }
        }
    }

    private fun build(app: Context): WatchFiringPoller {
        // ONE mail check for the process, so its own 15-minute cadence holds across ticks.
        val mail = MailReplyAndroid.check(app)
        return WatchFiringPoller(
            controller = { WatchesController.forContext(app) },
            announce = { firings, more -> announce(app, firings, more) },
            said = PrefsSaidFirings(app),
            phoneChecks = listOf<suspend () -> Unit>({ mail.maybeRun() }),
        )
    }

    internal fun raise(firings: List<WatchFiring>, more: Int): ProactiveRaise {
        val facts = WatchWording.facts(firings)
        return ProactiveRaise(
            // One rule per batch (the `claude_task_result_<id>` precedent): brushing off one line
            // never suppresses the next watch's for a day.
            ruleId = "watch_firing_${firings.first().id.take(RULE_ID_CHARS)}",
            category = ProactiveCategory.WATCHES,
            reason = WatchWording.reason(firings),
            facts = facts,
            prompt = WatchWording.prompt(facts, more),
        )
    }

    private suspend fun announce(context: Context, firings: List<WatchFiring>, more: Int): WatchDelivery =
        deliver(context, raise(firings, more))

    /**
     * One raise through the bus, as a [WatchDelivery]. **Traced 2026-11-10:** the bus's spoken line
     * is collected only by `AriaForegroundService`, so with the assistant off (LEGION open, screen
     * on) a "spoken" raise would reach no one and be acked as said. So without the service it is
     * posted instead, still behind the master and Watches switches.
     */
    @Suppress("ReturnCount") // switched off, posted, or the bus's own answer
    internal suspend fun deliver(context: Context, raise: ProactiveRaise): WatchDelivery {
        if (!AriaForegroundService.isRunning) {
            ProactiveSettings.load(context)
            if (!ProactiveSettings.mayRaise(raise.category)) return WatchDelivery.SILENCED
            return if (ProactiveDelivery.notify(context, raise, raise.reason)) {
                WatchDelivery.NOTIFIED
            } else {
                WatchDelivery.RETRY
            }
        }
        return when (ProactiveBus.speakIfAllowed(context, raise)) {
            is ProactiveBus.RaiseOutcome.Raised -> WatchDelivery.SPOKEN
            is ProactiveBus.RaiseOutcome.Notified -> WatchDelivery.NOTIFIED
            ProactiveBus.RaiseOutcome.NotNow -> WatchDelivery.RETRY
            // Switched off, brushed off, or a raise the bus refused: consumed unsaid, so turning the
            // switch back on never releases a backlog in one burst.
            else -> WatchDelivery.SILENCED
        }
    }

    private const val RULE_ID_CHARS = 8
}

/** [SaidFirings] in a small private SharedPreferences file: firing ids and an outcome word, no facts. */
internal class PrefsSaidFirings(context: Context) : SaidFirings {
    private val prefs = context.getSharedPreferences("watch_said_firings", Context.MODE_PRIVATE)

    override fun outcomeOf(id: String): String? = prefs.getString(id, null)

    override fun remember(ids: List<String>, outcome: String) {
        val edit = prefs.edit()
        // Bounded: a long engine outage cannot grow this without limit.
        if (prefs.all.size > MAX_HELD) edit.clear()
        ids.forEach { edit.putString(it, outcome) }
        edit.apply()
    }

    override fun forget(ids: List<String>) {
        val edit = prefs.edit()
        ids.forEach { edit.remove(it) }
        edit.apply()
    }

    private companion object {
        const val MAX_HELD = 200
    }
}
