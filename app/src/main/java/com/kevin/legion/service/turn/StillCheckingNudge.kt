package com.kevin.legion.service.turn

/**
 * The still-checking nudge (async-lookups ticket 07, Kevin 2026-10-28: *"if checks are longer than
 * x seconds, send another 'still checking' etc msg"*). Ticket 04 run 3 heard a two-call `ask_fleet`
 * chain run 9 s with no holding line at all: 13.8 s from the end of the user's speech to the first
 * audio. A NON_BLOCKING lookup (ADR 0065) leaves the model idle while it runs, and nothing asks it
 * to speak again until the result lands.
 *
 * So when a non-blocking lookup has run for [STILL_CHECKING_AFTER_MS] and the conversation is quiet,
 * the session hands the model one system note ([instruction]) asking for one short still-checking
 * line; at most once more at [STILL_CHECKING_AGAIN_AFTER_MS]. The clock is each call's own start, so
 * the second call of a chain gets its own 4 s. Never:
 *  - while anyone is speaking or a reply is being generated (the caller's `quiet`, and
 *    `clientContent` "will interrupt any current model generation", ai.google.dev/api/live);
 *  - after the call's result was sent ([onResult]) or it was cancelled ([onCancelled]);
 *  - while a BLOCKING tool is in flight (the model cannot answer mid-call);
 *  - for a call the user has spoken over since it started ([onUserActivity]: barge-in, speech onset).
 *
 * No two nudges closer than [STILL_CHECKING_AFTER_MS] apart, so two lookups running together do not
 * double up. Pure and clock-free (the caller passes `nowMs`), so every rule is a JVM test.
 */
internal class StillCheckingNudge(
    private val firstAfterMs: Long = STILL_CHECKING_AFTER_MS,
    private val againAfterMs: Long = STILL_CHECKING_AGAIN_AFTER_MS,
) {
    /** Say a still-checking line for [tool]; [afterMs] is how long its call has been running. */
    data class Nudge(val tool: String, val afterMs: Long)

    private class Call(val name: String, val startedAtMs: Long) {
        var nudges = 0
    }

    private val async = LinkedHashMap<String, Call>()
    private val blocking = HashSet<String>()
    private var lastNudgeAtMs = 0L

    @Synchronized
    fun onAsyncCall(id: String, name: String, atMs: Long) {
        async[id] = Call(name, atMs)
    }

    @Synchronized
    fun onBlockingCall(id: String) {
        blocking += id
    }

    /** The call's response went back (or was swallowed): it is never nudged again. */
    @Synchronized
    fun onResult(id: String) {
        async.remove(id)
        blocking.remove(id)
    }

    @Synchronized
    fun onCancelled(ids: Collection<String>) = ids.forEach { onResult(it) }

    /** The user spoke or barged in: no lookup already running is nudged. */
    @Synchronized
    fun onUserActivity() = async.clear()

    /** Session end. */
    @Synchronized
    fun clear() {
        async.clear()
        blocking.clear()
        lastNudgeAtMs = 0L
    }

    @Synchronized
    fun hasPending(): Boolean = async.isNotEmpty()

    /**
     * The nudge due at [nowMs], or null. [quiet] is the caller's whole "nobody is speaking and no
     * reply is being generated" judgement. Counts the nudge as sent when it returns one.
     */
    @Synchronized
    fun due(nowMs: Long, quiet: Boolean): Nudge? {
        if (!quiet || blocking.isNotEmpty()) return null
        val call = async.values.maxByOrNull { it.startedAtMs } ?: return null
        val threshold = when (call.nudges) {
            0 -> firstAfterMs
            1 -> againAfterMs
            else -> return null
        }
        val elapsed = nowMs - call.startedAtMs
        val tooSoon = lastNudgeAtMs > 0L && nowMs - lastNudgeAtMs < firstAfterMs
        if (elapsed < threshold || tooSoon) return null
        call.nudges++
        lastNudgeAtMs = nowMs
        return Nudge(call.name, elapsed)
    }

    companion object {
        /** Kevin, 2026-10-28. The first nudge. */
        const val STILL_CHECKING_AFTER_MS = 4_000L

        /** Kevin, 2026-10-28. The second and last nudge. */
        const val STILL_CHECKING_AGAIN_AFTER_MS = 12_000L

        /**
         * The system note sent as a user-role text turn, framed exactly like
         * [CancelledToolReport.noteFor]'s. No example line (an example in a prompt becomes a
         * catchphrase, ticket 06) and no assistant name (CLAUDE.md sec 1).
         */
        fun instruction(tool: String): String =
            "(System note, not spoken by the user: the `$tool` lookup is still running. Say one " +
                "short line, in your own voice, that you are still checking, and nothing else: no " +
                "figure, date, name, fact or outcome. Then stop and wait; its result will come to " +
                "you when it is ready.)"
    }
}
