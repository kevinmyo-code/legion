package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.ai.OnboardingState
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ProactiveRaiseRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.Calendar

/**
 * Events from the proactive engine to the service-owned [LiveSessionController].
 *
 * **One raw emit, two named doors, both gated by WHO asked** - not a choke point that treats all
 * speech alike (`.scratch/proactive-mode/issues/01-one-gate-not-three.md`). The raw emit is
 * PRIVATE; nothing outside this object can raise without going through one of:
 *
 *  - [speakSolicited] - the user asked, by voice or by tap. **Never gated.** A kill switch that
 *    silences a button someone just pressed is not a kill switch anyone would trust either way.
 *  - [speakIfAllowed] - the ONLY unsolicited path, and the whole subject of the proactive-mode map.
 *
 * ### What [speakIfAllowed] actually checks, and why each one is there
 *
 * | Check | Decision |
 * |---|---|
 * | Onboarding complete | Never talk over first-run setup |
 * | Not mid-conversation | Never talk over the user |
 * | Not in a phone call | The call owns the speakers |
 * | **Master switch** | Settled decision 2 - a TRUE kill switch, **nothing exempt, safety included** |
 * | **Category switch** | Settled decision 1 - five categories, two states each |
 * | **States its facts** | Settled decision 7 / ticket 10 - see [ProactiveRaise] |
 * | **Not suppressed by a brush-off** | Ticket 08 call 3 - a declined rule goes quiet for a window, silently |
 * | **Quiet hours** | Ticket 05 call 1 - per category, per window |
 * | **Daily cap** | Ticket 05 call 3 - three spoken lines a day, Safety outside it |
 *
 * ### Two sentences that are easy to get wrong, so they are stated here
 *
 * **"Safety always speaks" always means *while the master is on*.** Settled decision 2 has no
 * exemptions and none of the carve-outs below create one - the master is checked first, for
 * everything, with no branch around it. Read every future "X is exempt" against this paragraph.
 *
 * **Gated does not mean lost.** [RaiseOutcome] says exactly why a raise did not get spoken, and
 * ticket 06 call 3 turns most of those into a notification instead. The cap governs whether a line
 * is SPOKEN, not whether it exists. A silently dropped safety warning is the worst outcome on this
 * map, and nothing here produces one.
 *
 * **Two refusals deliberately do NOT become a notification**, and both would be wrong if they did:
 * [RaiseOutcome.MutedByUser], because a kill switch that reroutes to the shade is not a kill
 * switch; and [RaiseOutcome.Suppressed], because the user already brushed that exact rule off and
 * posting it would be the same nag through a different door. Quiet hours and the cap DO become
 * notifications - those mean "not out loud right now", not "you have heard enough of this".
 */
object ProactiveBus {
    private val _requestSpeak = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val requestSpeak: SharedFlow<String> = _requestSpeak

    /**
     * The night window. Inside it only [QUIET_HOURS_EXEMPT] may speak.
     *
     * **Wellbeing is in that set because the nudge this whole map came from lives at night** -
     * Kevin, 2026-08-16: *"it's past 10pm, perhaps rest is in order."* A window that muted the night
     * would have killed the founding line, which is exactly the tension ticket 05 was written to
     * resolve rather than dodge.
     */
    const val QUIET_START_HOUR = 22
    const val QUIET_END_HOUR = 7

    /** Safety, because a warning at 3am is the point of Safety. Wellbeing, because the rest nudge
     * lives there. Both still inside the master switch. */
    val QUIET_HOURS_EXEMPT = setOf(ProactiveCategory.SAFETY, ProactiveCategory.WELLBEING)

    /**
     * Three spoken unprompted lines a day (ticket 05 call 3). Low enough that each has to earn its
     * slot - **a cap you never hit is not a cap**.
     *
     * The only anti-compulsion mechanism on this map that is *countable*. Tone is not testable;
     * three is.
     */
    const val DAILY_SPOKEN_CAP = 3

    /** [ProactiveCategory.SAFETY] does not spend from the cap. A real warning must never lose its
     * slot to a rest nudge, and a budget that can silence one is worse than no budget. */
    val UNCAPPED_CATEGORY = ProactiveCategory.SAFETY

    /** How long a brushed-off rule stays quiet. When it returns, **the tone is identical** - it
     * never mentions there was a first time (ticket 08 call 3), which is enforceable precisely
     * because the suppression happens here, before the model ever sees the raise. */
    const val DECLINE_SUPPRESSION_MS = 24L * 60 * 60 * 1000

    /**
     * App-lifetime scope for [speakIfAllowedAsync]. A stored scope in an object is normally a smell,
     * and it is deliberate here: this bus is a process-lifetime singleton, and its non-suspending
     * callers ([TelephonyController.announceIncoming] fires from a platform listener callback) have
     * no scope of their own to borrow. `SupervisorJob` so one failed raise never cancels the next.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Why a raise did or did not get spoken. Named rather than boolean so a caller can tell
     * "silently gated" from "the user has it switched off" - and so ticket 06's delivery layer can
     * decide which outcomes deserve a notification instead. */
    sealed class RaiseOutcome {
        /** It was SPOKEN ALOUD. [rowId] is its [ProactiveRaiseRow]. */
        data class Raised(val rowId: Long) : RaiseOutcome()

        /**
         * It could not be spoken, so it was posted instead - the phone was asleep, a meeting was
         * running, quiet hours, or the daily cap.
         *
         * **Distinct from [Raised] on purpose.** An earlier cut returned `Raised` for both, which
         * made "did it actually get said out loud?" unanswerable - and `ReminderAlarmReceiver` has
         * to answer exactly that to avoid posting twice. A single outcome meaning "delivered
         * somehow" is the kind that quietly turns into a wrong claim about what the user heard.
         *
         * [postedByCaller] is true when the caller owns the notification (see
         * [ProactiveRaise.callerPostsItsOwnNotification]) - the bus posted nothing and the caller
         * must.
         */
        data class Notified(val rowId: Long, val postedByCaller: Boolean) : RaiseOutcome()

        /** The user switched it off. **Never becomes a notification** - a kill switch that reroutes
         * to the shade is not a kill switch. */
        data object MutedByUser : RaiseOutcome()

        /** Setup is still running, or a conversation or phone call is in progress. Transient. */
        data object NotNow : RaiseOutcome()

        /** The raise stated no facts, so it was refused (ticket 10). **A bug in the caller, not a
         * user setting** - it must never be silently swallowed as if it were one. */
        data object StatedNoFacts : RaiseOutcome()

        /** Brushed off recently; that rule is quiet for [DECLINE_SUPPRESSION_MS]. */
        data object Suppressed : RaiseOutcome()

        /** Inside quiet hours and not exempt. */
        data object QuietHours : RaiseOutcome()

        /** Today's spoken budget is spent. */
        data object OverDailyCap : RaiseOutcome()
    }

    /** The only place that touches the raw flow. */
    private fun emit(prompt: String) {
        _requestSpeak.tryEmit(prompt)
    }

    /**
     * User-initiated speech: voice, or a tap. Never gated by any of the above - they just asked,
     * directly, and none of the reasons to stay quiet apply to an answer.
     */
    fun speakSolicited(prompt: String) {
        emit(prompt)
    }

    /**
     * The ONLY unsolicited path. Runs every check in the class table and, when they all pass,
     * records a [ProactiveRaiseRow] and emits.
     *
     * Suspending because the cap and the suppression window are database reads - the state they
     * replaced was a set of process-lifetime fields that a `START_STICKY` restart wiped, which made
     * "never nag twice" impossible rather than merely weak.
     */
    suspend fun speakIfAllowed(context: Context, raise: ProactiveRaise): RaiseOutcome {
        // --- the checks that need nothing but this moment ---------------------------------
        // Deliberately ALL of them before the first database read, so a raise that is going to be
        // refused anyway never opens the database. That ordering is also what lets the gate's own
        // tests cover these branches without Room - see [decideOnHistory]'s doc.
        if (!OnboardingState.isComplete(context)) return RaiseOutcome.NotNow
        if (ConversationState.isBusy) return RaiseOutcome.NotNow
        if (TelephonyController.isInCall) return RaiseOutcome.NotNow

        // The switches. Master first, for everything, with no branch around it.
        ProactiveSettings.load(context)
        if (!ProactiveSettings.mayRaise(raise.category)) return RaiseOutcome.MutedByUser

        // Ticket 10's contract, and the only check here that indicates a bug rather than a setting.
        if (!raise.statesItsFacts) return RaiseOutcome.StatedNoFacts

        // --- the checks that need the raise history ---------------------------------------
        val dao = CarDatabase.getDatabase(context).proactiveRaiseDao()
        val now = System.currentTimeMillis()
        val last = dao.lastForRule(raise.ruleId)
        val spokenToday =
            if (raise.category == UNCAPPED_CATEGORY) 0
            else dao.spokenCountSince(startOfToday(now), UNCAPPED_CATEGORY.key)

        val refusal = decideOnHistory(raise, now, last, spokenToday, LocalTime.now())

        // --- deliver, or say in the return value that it was not delivered ------------------
        // Ticket 06 call 3: a raise that cannot be SPOKEN is POSTED. Nothing is silently dropped -
        // today's behaviour drops everything while the phone is idle-but-locked, and a silently
        // dropped safety warning is the worst outcome on this map.
        //
        // Suppressed is the ONE refusal that does not become a notification, and the reason is the
        // whole point of the suppression: the user already brushed this exact rule off. Posting it
        // to the shade instead would be the same nag through a different door.
        if (refusal == RaiseOutcome.Suppressed) return refusal
        // A raise the user switched off must not reroute to the shade either - a kill switch that
        // redirects is not a kill switch (settled decision 2). Same for the pre-database refusals,
        // which returned above before reaching here.
        if (refusal == RaiseOutcome.MutedByUser) return refusal

        val spokenAloud = refusal == null && ProactiveDelivery.maySpeakAloud(context)
        val rowId = dao.insert(
            ProactiveRaiseRow(
                ruleId = raise.ruleId,
                category = raise.category.key,
                reason = raise.reason,
                spokenAt = now,
                delivery = if (spokenAloud) ProactiveRaiseRow.DELIVERY_SPOKEN
                else ProactiveRaiseRow.DELIVERY_NOTIFIED,
            )
        )

        if (spokenAloud) {
            // Exactly one delivery per raise, never both (ticket 06 call 5). The cost is real and
            // accepted: a spoken line leaves nothing on the lock screen to find afterwards.
            emit(raise.prompt)
            return RaiseOutcome.Raised(rowId)
        }

        // Nothing here reports that the notification was SEEN, only that it was handed to the
        // system - the channel is a kill switch the user can pull without the app knowing
        // (CLAUDE.md §7's outcome-verb rule, applied to delivery).
        if (!raise.callerPostsItsOwnNotification) {
            ProactiveDelivery.notify(context, raise, raise.reason)
        }
        return RaiseOutcome.Notified(rowId, postedByCaller = raise.callerPostsItsOwnNotification)
    }

    /**
     * The suppression / quiet-hours / cap decision, as a PURE function of values already fetched.
     * Returns the refusing outcome, or null when the raise may go ahead.
     *
     * **Split out because the arithmetic is the part worth testing and Room is the part that makes
     * it untestable here.** `CarDatabase` hands out a process-wide singleton while Robolectric
     * resets its SQLite between tests, so a Robolectric test that reaches Room across class
     * boundaries dies with `IllegalStateException: Illegal connection pointer` - the same class of
     * gap CLAUDE.md §10 already records for `LedgerController` and `PantryController`. Rather than
     * leave the cap and the suppression window covered by nothing, the decision moves somewhere a
     * plain JUnit test can reach it and the storage stays where it was.
     *
     * **What that leaves untested is the WIRING** - that `speakIfAllowed` passes the right values in
     * - which is three single-statement DAO calls sitting directly above this call, inspectable by
     * reading them. Said out loud rather than implied by a green suite.
     */
    internal fun decideOnHistory(
        raise: ProactiveRaise,
        now: Long,
        last: ProactiveRaiseRow?,
        spokenToday: Int,
        time: LocalTime,
    ): RaiseOutcome? {
        if (last != null && last.declined && now - last.spokenAt < DECLINE_SUPPRESSION_MS) {
            return RaiseOutcome.Suppressed
        }
        if (isQuietHour(time) && raise.category !in QUIET_HOURS_EXEMPT) {
            return RaiseOutcome.QuietHours
        }
        if (raise.category != UNCAPPED_CATEGORY && spokenToday >= DAILY_SPOKEN_CAP) {
            return RaiseOutcome.OverDailyCap
        }
        return null
    }

    /** Fire-and-forget [speakIfAllowed], for a caller with no scope of its own - see [scope]. */
    fun speakIfAllowedAsync(context: Context, raise: ProactiveRaise) {
        val app = context.applicationContext
        scope.launch { speakIfAllowed(app, raise) }
    }

    /**
     * True inside the night window. Written to handle the window WRAPPING midnight, which a naive
     * `hour in start..end` gets silently and completely wrong for 22:00-07:00 - that range is empty,
     * so quiet hours would never fire and nothing would look broken.
     */
    fun isQuietHour(time: LocalTime): Boolean {
        val hour = time.hour
        return if (QUIET_START_HOUR <= QUIET_END_HOUR) {
            hour in QUIET_START_HOUR until QUIET_END_HOUR
        } else {
            hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR
        }
    }

    /** Local midnight, so the cap is a calendar day rather than a rolling 24 hours. A rolling window
     * would let last night's three suppress this morning's first. */
    internal fun startOfToday(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * Records that the user brushed off the most recent raise of [ruleId], which suppresses that
     * rule for [DECLINE_SUPPRESSION_MS].
     *
     * **Inferred from the reply, and imperfect** (ticket 05 call 5) - a grunt may or may not be a
     * no. The cost of a wrong read is bounded on purpose: a false decline loses one nudge, and a
     * missed decline means the rule returns on schedule. Neither failure is loud, which is why
     * inference is acceptable here and would not be for anything the assistant asserts aloud.
     */
    suspend fun markDeclined(context: Context, ruleId: String) {
        CarDatabase.getDatabase(context).proactiveRaiseDao().markDeclined(ruleId)
    }
}
