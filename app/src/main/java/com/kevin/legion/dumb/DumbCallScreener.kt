package com.kevin.legion.dumb

/** What dumb mode does with an incoming call. There is no "reject": a silenced call still shows and can be answered. */
enum class CallAction {
    /** Leave the call exactly as it would have been. */
    ALLOW,

    /** Let the call in, but do not ring. It is still logged and still shows its notification. */
    SILENCE,
}

/**
 * Dumb mode's call decision (ADR 0062), pure. Off or outgoing: [CallAction.ALLOW], untouched, and
 * nothing is recorded. On, for an incoming call:
 * 1. a chosen person's number: ALLOW;
 * 2. a number seen within the last 15 minutes: ALLOW (the second call rings);
 * 3. anything else, including a withheld number: SILENCE.
 *
 * Every sighting is recorded after the decision, so the next call from the same number can ring.
 * A withheld number cannot be tracked, so it is always silenced. **Never rejects or disconnects**:
 * [CallAction.SILENCE] is the strongest thing this does.
 */
class DumbCallScreener(
    private val isOn: () -> Boolean,
    private val isChosen: (String?) -> Boolean,
    private val recent: RecentCallers,
) {
    fun decide(number: String?, incoming: Boolean, nowMs: Long): CallAction {
        if (!isOn() || !incoming) return CallAction.ALLOW
        val action = when {
            PhoneNumbers.normalize(number).isEmpty() -> CallAction.SILENCE
            isChosen(number) -> CallAction.ALLOW
            recent.seenWithin(number, nowMs) -> CallAction.ALLOW
            else -> CallAction.SILENCE
        }
        recent.record(number, nowMs)
        return action
    }
}
