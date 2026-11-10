package com.kevin.legion.watches

import android.content.Context
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveDelivery
import com.kevin.legion.service.ProactiveRaise

/**
 * The ONE [WatchFiringPoller] for the process, and how a firing becomes a raise (ADR 0083). It
 * rides the Claude result watcher's two existing loops (`ClaudeResultPolling`): no new alarm, no
 * WorkManager, no second loop. See [WatchFiringPoller] for exactly when that means nothing polls.
 *
 * **The raise** goes through [ProactiveBus.speakIfAllowed] in [ProactiveCategory.WATCHES]: inside
 * the master switch and quiet hours (there is no daily cap since ADR 0082), and the bus's one
 * routing decision ([ProactiveDelivery.route]) says it aloud or posts it. Several firings are one
 * line. Its facts are the engine's own sentences from the rows (ticket 10), and its reason names
 * each watch and its fact, which is what "why did you say that?" reads. It never carries an
 * emergency [com.kevin.legion.service.ProactiveUrgency.Alarm]: only Safety may ring, and the bus
 * refuses one on any other category.
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
     * One raise through the bus, as a [WatchDelivery]. **Exactly one delivery in every state**
     * (integration 2026-11-10): the bus itself routes to [ProactiveDelivery.route], which posts the
     * line when nothing collects the spoken flow (assistant service off) and when the phone is
     * silenced, in a meeting or in quiet hours, so this branch has no fallback of its own. The
     * branch's earlier own notification for the service-off case (posted before ADR 0082's bus fix
     * existed) is gone: it skipped the raise row ("why did you say that?" could not see it) and the
     * decline window. `WatchDeliveryTest` pins one delivery per firing in all three states.
     * [speak] is a test seam (a fixed clock); production is [ProactiveBus.speakIfAllowed].
     */
    internal suspend fun deliver(
        context: Context,
        raise: ProactiveRaise,
        speak: suspend (Context, ProactiveRaise) -> ProactiveBus.RaiseOutcome =
            { c, r -> ProactiveBus.speakIfAllowed(c, r) },
    ): WatchDelivery = when (speak(context, raise)) {
        is ProactiveBus.RaiseOutcome.Raised -> WatchDelivery.SPOKEN
        is ProactiveBus.RaiseOutcome.Notified -> WatchDelivery.NOTIFIED
        ProactiveBus.RaiseOutcome.NotNow -> WatchDelivery.RETRY
        // Switched off, brushed off, or a raise the bus refused: consumed unsaid, so turning the
        // switch back on never releases a backlog in one burst.
        else -> WatchDelivery.SILENCED
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
