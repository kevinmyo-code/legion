package com.kevin.legion.service

/**
 * One thing the assistant wants to say unprompted, with everything the gate needs to judge it -
 * ticket 10 call 2 (`.scratch/proactive-mode/issues/10-what-a-raise-may-say.md`).
 *
 * ### Why a raise stopped being a `String`
 *
 * On 2026-08-21 the startup opener told Kevin he had **lunch with Sam**. There is no Sam. The
 * opener's prompt asked the model to work in anything "notable coming up" and handed it time,
 * place, weather and car - and no schedule at all. An instruction to mention what is coming up,
 * paired with nothing to mention, is a request for content with no source, and the model supplied
 * one.
 *
 * The rule that came out of it (settled decision 7):
 *
 * > An unsolicited prompt may ask the model to mention a subject only if the same prompt states the
 * > facts of that subject; where it has no facts, it must forbid the subject in words rather than
 * > stay silent about it.
 *
 * **A `String` cannot be checked against that rule** - `ProactiveBus` saw an opaque blob and had no
 * way to tell a raise carrying its facts from one fishing for them. The cheaper options were both
 * rejected: a convention with a comment is exactly what ticket 01 found had already failed once
 * (three copies of one gate, two of which quietly diverged), and a test over the prompt text
 * catches phrasing but never an actual absence of facts.
 *
 * **Honest limit, so the type is not mistaken for a guarantee:** the gate can check that [facts] is
 * non-empty. It cannot check that the facts are true, relevant, or complete. This turns a silent
 * failure into a refusable one; it does not make the prompt correct.
 *
 * ### It pays for three things at once
 *
 * That is what made the churn of all eleven call sites worth it, rather than the contract alone:
 *
 *  - **the contract** - [facts] is what the gate refuses on;
 *  - **the raise history** - [ruleId], [category] and [reason] are the
 *    [com.kevin.legion.data.local.ProactiveRaiseRow] this becomes, which backs the daily cap, the
 *    suppression window, and "why did you say that?";
 *  - **delivery** - ticket 06 call 5's "spoke, so do not ALSO notify" needs a real answer about
 *    whether the line got out, not an assumption.
 */
data class ProactiveRaise(
    /**
     * Stable id of the rule that fired, e.g. `coolant_overheat`, `reminder_due`. **The suppression
     * key**: a brush-off silences this id for a window, not the whole category, so declining a rest
     * nudge never mutes an overheat warning. Snake case, stable forever once a row exists under it.
     */
    val ruleId: String,
    /** Which switch governs it. */
    val category: ProactiveCategory,
    /**
     * The falsifiable fact that fired the rule, in plain words - "coolant 118C, over the 110C
     * threshold", "reminder 'call the shop' came due at 14:00".
     *
     * Stored on the raise row and read back verbatim when Kevin asks why. Ticket 08 call 4:
     * **name the rule and the fact, never a justification.** A justification is unfalsifiable; a
     * fact is checkable. Same split the reconciliation gate makes (CLAUDE.md §4).
     */
    val reason: String,
    /**
     * What the model is allowed to talk about, already looked up - never a subject it must go and
     * find. This is the load-bearing field: **empty means the prompt states no facts**, and the gate
     * refuses it (see [ProactiveBus.speakIfAllowed]).
     *
     * A raise with genuinely nothing to say should not be constructed at all. A raise whose SOURCE
     * came back empty says so here in words - "the calendar was read and is clear for 12 hours" -
     * because *"nothing is scheduled"* is a fact and silence is not.
     */
    val facts: String,
    /**
     * The full prompt handed to the live model. Must contain [facts] and must forbid, in words, any
     * subject it has no facts for - `calendar/OpenerCalendarBriefing.kt` is the worked example of
     * all three shapes (no permission / read and empty / read with content).
     */
    val prompt: String,
    /**
     * True when the CALLER posts its own notification for this event and the bus must not post the
     * generic one (ticket 06 call 5).
     *
     * Exactly one caller sets it: `ReminderAlarmReceiver`, whose fired reminders already have a
     * notification on `reminders_channel` at `IMPORTANCE_HIGH` - louder than the proactive channel,
     * and deliberately so, because a reminder is something the user explicitly asked to be
     * interrupted for while a nudge is not. Letting the bus post as well would produce two
     * notifications for one reminder, which is the echo this ticket exists to end.
     *
     * **It opts out of the bus's notification, never out of the one-delivery rule.** The caller
     * still posts only when the raise was not spoken - see `ReminderAlarmReceiver.fire`.
     */
    val callerPostsItsOwnNotification: Boolean = false,
    /**
     * True when this raise ASKS SOMETHING the user can answer out loud, so the microphone opens
     * with the line instead of the assistant speaking into a closed socket (2026-08-21, Kevin:
     * *"mic open for the whole ring"*).
     *
     * **This is a deliberate exception to a rule, not a default.** Every other proactive line is
     * speak-only - `LiveSessionController.startProactive` passes `vad = false` and the mic never
     * opens, which is why an announcement cannot be replied to. That default is right: a nudge
     * that silently opened the microphone every time it fired would be a listening device with a
     * reason attached.
     *
     * Exactly one raise sets it today, and it is the only one that has earned it: `incoming_call`
     * asks a question with an action behind it ("answer it" / "decline it"), and the window in
     * which the answer is useful is the few seconds the phone is ringing. Without this the feature
     * shipped on 2026-08-21 could not be used hands-free at all - the announcement spoke and the
     * socket closed 10 seconds later having never listened.
     *
     * **A raise that sets this MUST have something that closes the window.** Ring-listening is
     * ended by [ProactiveBus.stopListening] when the phone stops ringing; a raise that opened the
     * mic with no matching close would leave it open until the idle backstop, which is the exact
     * shape this flag's doc is warning about.
     */
    val listensForReply: Boolean = false,
) {
    init {
        require(ruleId.isNotBlank()) { "a raise must name the rule that fired it" }
        require(reason.isNotBlank()) { "a raise must carry its reason - it is what 'why did you say that?' reads" }
    }

    /** True when this raise states facts for the model to work from. What the gate refuses on. */
    val statesItsFacts: Boolean get() = facts.isNotBlank()
}
