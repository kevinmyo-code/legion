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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime

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
 * | **Quiet hours** | Ticket 05 call 1 - per category, per window; one per-rule exemption (ADR 0082) |
 * | **Spoken, posted or rung** | [ProactiveDelivery.route] - not silenced, no live meeting (ADR 0082) |
 *
 * **There is no daily cap** (Kevin, 2026-11-10, ADR 0082: *"no cap on proactive spoken lines"*).
 * Ticket 05 call 3's three-a-day budget and its uncapped-category list are gone; the switches,
 * quiet hours, the decline window, the speech gate and one delivery per raise are what remain.
 *
 * ### Two sentences that are easy to get wrong, so they are stated here
 *
 * **"Safety always speaks" always means *while the master is on*.** Settled decision 2 has no
 * exemptions and none of the carve-outs below create one - the master is checked first, for
 * everything, with no branch around it. Read every future "X is exempt" against this paragraph.
 *
 * **Gated does not mean lost.** [RaiseOutcome] says exactly why a raise did not get spoken, and
 * ticket 06 call 3 turns most of those into a notification instead. The gate governs whether a line
 * is SPOKEN, not whether it exists. A silently dropped safety warning is the worst outcome on this
 * map, and nothing here produces one.
 *
 * **Two refusals deliberately do NOT become a notification**, and both would be wrong if they did:
 * [RaiseOutcome.MutedByUser], because a kill switch that reroutes to the shade is not a kill
 * switch; and [RaiseOutcome.Suppressed], because the user already brushed that exact rule off and
 * posting it would be the same nag through a different door. Quiet hours and the speech gate DO become
 * notifications - those mean "not out loud right now", not "you have heard enough of this".
 */
object ProactiveBus {
    /**
     * One line for the live session to voice, and whether the microphone opens with it.
     *
     * The flow used to carry a bare `String`. It carries [listensForReply] now because whether a
     * proactive line opens the mic is a property OF THAT LINE - see [ProactiveRaise.listensForReply]
     * - and threading it through a side channel would let the two disagree.
     */
    data class SpeakRequest(
        val prompt: String,
        val listensForReply: Boolean = false,
        /** See [ProactiveRaise.carriesReadThroughContent] - the turn this opens must not be
         * written to episodic memory. */
        val carriesReadThroughContent: Boolean = false,
        /** See [ProactiveRaise.listenHoldMs]. */
        val listenHoldMs: Long = 0L,
    )

    private val _requestSpeak = MutableSharedFlow<SpeakRequest>(extraBufferCapacity = 4)
    val requestSpeak: SharedFlow<SpeakRequest> = _requestSpeak

    /**
     * Fires when a ring-listening window should close - the phone stopped ringing, because it was
     * answered, declined, or the caller gave up.
     *
     * Separate from [requestSpeak] because it is the OPPOSITE of a raise: nothing is said, a
     * microphone is closed. Collected in `AriaForegroundService` alongside the speak flow, so
     * `TelephonyController` (a platform callback with no session reference) can end the window it
     * indirectly opened.
     */
    private val _stopListening = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val stopListening: SharedFlow<Unit> = _stopListening

    /** Ends any open ring-listening window. Safe to call when none is open - it is a no-op then. */
    fun stopListening() {
        _stopListening.tryEmit(Unit)
    }

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
         * It could not be spoken, so it was posted instead - the phone was silenced, a meeting was
         * running, quiet hours, or nothing was running to speak it.
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

        /** An emergency [ProactiveUrgency.Alarm] RANG on the ring screen; the line is said after
         * Dismiss. [rowId] is its [ProactiveRaiseRow]. */
        data class Rang(val rowId: Long) : RaiseOutcome()

        /** A [ProactiveUrgency.Alarm] on a category other than Safety. **A bug in the caller**, like
         * [StatedNoFacts]: only an emergency may ring. */
        data object AlarmNotAllowed : RaiseOutcome()

        /** This alert id was already delivered at this severity or higher. One alarm per alert. */
        data object AlreadyAlarmed : RaiseOutcome()
    }

    /**
     * The rule of the most recent unsolicited line, while a reply to it could still arrive.
     *
     * This is what makes decline detection possible at all: `markDeclined` takes a `ruleId`, and by
     * the time the user answers, the only thing that knows which rule spoke is this. Cleared as
     * soon as one turn has been read, so a refusal can never be attributed to a nudge two
     * conversations ago.
     */
    @Volatile private var awaitingReplyFor: String? = null

    /**
     * Offers the user's reply to whatever was last raised, marking it declined if it reads as a
     * brush-off (ticket 05 call 5).
     *
     * **Called once per completed turn, and only the FIRST turn after a raise counts.** A nudge is
     * answered immediately or not at all; letting a later "no" - to some entirely different
     * question - suppress last hour's rule would be worse than never detecting a decline.
     *
     * Silence is deliberately not a refusal; see [NudgeReply.isDecline].
     */
    suspend fun noteReply(context: Context, reply: String) {
        val ruleId = awaitingReplyFor ?: return
        awaitingReplyFor = null
        if (!NudgeReply.isDecline(reply)) return
        markDeclined(context, ruleId)
    }

    /**
     * The only place that touches the raw flow. **A reply microphone opens only when it can hear**
     * ([ProactiveDelivery.mayListen], ADR 0082): with the screen off and no `microphone` foreground
     * type the capture would hear silence, so the line is spoken without listening. One place, so
     * no raise site has to know.
     */
    private fun emit(
        prompt: String,
        listensForReply: Boolean = false,
        carriesReadThroughContent: Boolean = false,
        listenHoldMs: Long = 0L,
    ) {
        val listens = listensForReply && ProactiveDelivery.mayListen()
        _requestSpeak.tryEmit(
            SpeakRequest(prompt, listens, carriesReadThroughContent, if (listens) listenHoldMs else 0L),
        )
    }

    /**
     * How many collect [requestSpeak] - in practice 1 while `AriaForegroundService` runs, 0 when it
     * does not. The flow keeps nothing for a late subscriber, so a line emitted with nobody
     * listening is lost; [ProactiveDelivery.route] posts it instead. Waited on by
     * [ProactiveDelivery.awaitListener].
     */
    val listeners: StateFlow<Int> = _requestSpeak.subscriptionCount

    /** True when something is collecting [requestSpeak]. */
    val hasListener: Boolean get() = listeners.value > 0

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
     * Suspending because the suppression window is a database read - the state it replaced was a
     * set of process-lifetime fields that a `START_STICKY` restart wiped, which made "never nag
     * twice" impossible rather than merely weak.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // one named refusal per check, in order
    suspend fun speakIfAllowed(
        context: Context,
        raise: ProactiveRaise,
        // Test seam only (a fixed hour, so a test of the spoken route cannot fail at night).
        time: LocalTime = LocalTime.now(),
    ): RaiseOutcome {
        // --- the checks that need nothing but this moment ---------------------------------
        // Deliberately ALL of them before the first database read, so a raise that is going to be
        // refused anyway never opens the database. That ordering is also what lets the gate's own
        // tests cover these branches without Room - see [decideOnHistory]'s doc.
        if (!OnboardingState.isComplete(context)) return RaiseOutcome.NotNow
        if (ConversationState.isBusy) return RaiseOutcome.NotNow
        // Ringing counts too: an unsolicited line over a ringing phone is the same intrusion as
        // one over a connected call. The VOICE path deliberately differs - see
        // LiveSessionController - because a ringing phone is exactly when Kevin may want to speak.
        if (TelephonyController.isInCall || TelephonyController.isRinging) return RaiseOutcome.NotNow

        // The switches. Master first, for everything, with no branch around it - an emergency
        // alarm included (settled decision 2).
        ProactiveSettings.load(context)
        if (!ProactiveSettings.mayRaise(raise.category)) return RaiseOutcome.MutedByUser

        // Ticket 10's contract, and the only checks here that indicate a bug rather than a setting.
        if (!raise.statesItsFacts) return RaiseOutcome.StatedNoFacts
        val alarm = raise.urgency as? ProactiveUrgency.Alarm
        if (alarm != null && raise.category != ProactiveCategory.SAFETY) return RaiseOutcome.AlarmNotAllowed
        if (alarm != null && !SafetyAlarms.isNew(context, alarm)) return RaiseOutcome.AlreadyAlarmed

        // --- the checks that need the raise history ---------------------------------------
        val dao = CarDatabase.getDatabase(context).proactiveRaiseDao()
        val now = System.currentTimeMillis()
        val refusal = decideOnHistory(raise, now, dao.lastForRule(raise.ruleId), time)

        // --- deliver, or say in the return value that it was not delivered ------------------
        // Ticket 06 call 3: a raise that cannot be SPOKEN is POSTED. Nothing is silently dropped -
        // today's behaviour drops everything while the phone is idle-but-locked, and a silently
        // dropped safety warning is the worst outcome on this map.
        //
        // Suppressed is the ONE refusal that does not become a notification, and the reason is the
        // whole point of the suppression: the user already brushed this exact rule off. Posting it
        // to the shade instead would be the same nag through a different door.
        if (refusal == RaiseOutcome.Suppressed) return refusal

        var route = ProactiveDelivery.route(context, raise, gateAllowsSpeech = refusal == null, time = time)
        if (alarm != null) SafetyAlarms.remember(context, alarm)
        if (route == ProactiveDelivery.Route.RING && !SafetyAlarms.ring(context, raise)) {
            // The ring could not start (Android refused the background start): post it, never drop it.
            route = ProactiveDelivery.Route.NOTIFY
        }
        val rowId = dao.insert(
            ProactiveRaiseRow(
                ruleId = raise.ruleId,
                category = raise.category.key,
                reason = raise.reason,
                spokenAt = now,
                delivery = when (route) {
                    ProactiveDelivery.Route.SPEAK -> ProactiveRaiseRow.DELIVERY_SPOKEN
                    ProactiveDelivery.Route.RING -> ProactiveRaiseRow.DELIVERY_RUNG
                    ProactiveDelivery.Route.NOTIFY -> ProactiveRaiseRow.DELIVERY_NOTIFIED
                },
            )
        )

        when (route) {
            ProactiveDelivery.Route.SPEAK -> {
                // Exactly one delivery per raise, never both (ticket 06 call 5). The cost is real and
                // accepted: a spoken line leaves nothing on the lock screen to find afterwards.
                // Arm decline detection BEFORE the line goes out, so a fast reply cannot beat it.
                awaitingReplyFor = raise.ruleId
                emit(raise.prompt, raise.listensForReply, raise.carriesReadThroughContent, raise.listenHoldMs)
                return RaiseOutcome.Raised(rowId)
            }
            ProactiveDelivery.Route.RING -> return RaiseOutcome.Rang(rowId)
            ProactiveDelivery.Route.NOTIFY -> Unit
        }

        // Nothing here reports that the notification was SEEN, only that it was handed to the
        // system - the channel is a kill switch the user can pull without the app knowing
        // (CLAUDE.md §7's outcome-verb rule, applied to delivery).
        if (!raise.callerPostsItsOwnNotification) {
            ProactiveDelivery.notify(context, raise, raise.notificationText ?: raise.reason)
        }
        return RaiseOutcome.Notified(rowId, postedByCaller = raise.callerPostsItsOwnNotification)
    }

    /**
     * The line of an emergency alarm, said once he has dismissed its ring (ADR 0082). Not a second
     * gate around the first: the master and the Safety switch are checked again (he may have
     * switched them off while it rang), and nothing else is - quiet hours and the silenced phone
     * are what made it ring, and Dismiss is him attending to it. Posted when nothing is running to
     * speak it. True when it was handed to the session.
     */
    suspend fun speakAfterAlarm(context: Context, raise: ProactiveRaise): Boolean {
        ProactiveSettings.load(context)
        val spoken = ProactiveSettings.mayRaise(raise.category) && hasListener
        when {
            spoken -> {
                awaitingReplyFor = raise.ruleId
                emit(raise.prompt, raise.listensForReply, raise.carriesReadThroughContent, raise.listenHoldMs)
            }
            ProactiveSettings.mayRaise(raise.category) ->
                ProactiveDelivery.notify(context, raise, raise.notificationText ?: raise.facts)
            else -> Unit
        }
        return spoken
    }

    /**
     * The suppression / quiet-hours decision, as a PURE function of values already fetched.
     * Returns the refusing outcome, or null when the raise may go ahead.
     *
     * **Split out because the arithmetic is the part worth testing and Room is the part that makes
     * it untestable here.** `CarDatabase` hands out a process-wide singleton while Robolectric
     * resets its SQLite between tests, so a Robolectric test that reaches Room across class
     * boundaries dies with `IllegalStateException: Illegal connection pointer` - the same class of
     * gap CLAUDE.md §10 already records for `LedgerController` and `PantryController`. Rather than
     * leave the suppression window covered by nothing, the decision moves somewhere a plain JUnit
     * test can reach it and the storage stays where it was.
     *
     * **What that leaves untested is the WIRING** - that `speakIfAllowed` passes the right values in
     * - which is one single-statement DAO call sitting directly above this call, inspectable by
     * reading it. Said out loud rather than implied by a green suite.
     */
    internal fun decideOnHistory(
        raise: ProactiveRaise,
        now: Long,
        last: ProactiveRaiseRow?,
        time: LocalTime,
    ): RaiseOutcome? = when {
        last != null && last.declined && now - last.spokenAt < DECLINE_SUPPRESSION_MS -> RaiseOutcome.Suppressed
        isQuietHour(time) && raise.category !in QUIET_HOURS_EXEMPT && !raise.exemptFromQuietHours ->
            RaiseOutcome.QuietHours
        else -> null
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
