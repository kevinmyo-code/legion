package com.kevin.legion.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Answering and rejecting a ringing call by voice (2026-08-21, Kevin: *"can i also pick it up or
 * decline via voice?"*).
 *
 * Both operations are one `TelecomManager` call guarded by one ordinary runtime permission,
 * `ANSWER_PHONE_CALLS`. Verified against AOSP rather than assumed: `TelecomServiceImpl`'s
 * `enforceAnswerCallPermission` checks that permission and its app-op and **nothing else** - there
 * is no default-dialer check, so a sideloaded non-dialer app can do this with no special role.
 *
 * ### The honesty problem, and how it is solved
 *
 * **`acceptRingingCall()` returns `void`.** It reports nothing about whether the call was actually
 * answered. CLAUDE.md §7 forbids saying "answered" after a call whose outcome was not observed -
 * an outcome verb may follow only a tool result that came back successful - so returning "probably
 * fine" here would push a lie one layer up into the assistant's mouth.
 *
 * So this file does not trust the call. It **watches the phone's own call state** afterwards and
 * reports what actually happened: answering means RINGING becomes OFFHOOK, rejecting means the
 * ringing stops. That is a falsifiable observation rather than an assumption, which is the same
 * standard the reconciliation gate holds data to.
 *
 * ### What genuinely cannot be done, stated rather than worked around
 *
 * **Self-managed calls - WhatsApp, Signal, Teams, any VoIP app that owns its own calling - are
 * silently ignored by both APIs.** AOSP's `acceptRingingCallInternal` and `endCallInternal` both
 * bail on `call.isSelfManaged()` for a non-privileged caller, with no error and no signal. The
 * only fix is becoming the default dialer and binding an `InCallService`, which LEGION is not and
 * does not want to be. The state check below is what turns that silence into an honest "it did not
 * work" instead of a confident false claim.
 *
 * **Emergency calls cannot be ended at all**; Telecom returns false unconditionally.
 */
object CallActions {

    private const val TAG = "CallActions"

    /** How long to watch the call state before deciding it did not work. Generous enough for the
     * Telecom round trip, short enough that the assistant is not left silent mid-sentence. */
    private const val CONFIRM_TIMEOUT_MS = 2_500L
    private const val POLL_INTERVAL_MS = 100L

    /** What actually happened, in a form a tool result can state without overclaiming. */
    sealed class Outcome {
        /**
         * Observed: the call is now connected. [route] is non-null only when the speaker was
         * asked for, and carries whether the route ACTUALLY moved - a refused route still leaves
         * the call answered, so this is a separate fact rather than a second failure mode.
         */
        data class Answered(val route: CallAudioRoute.Result? = null) : Outcome()

        /** Observed: the ringing stopped. */
        data object Rejected : Outcome()

        /** Nothing was ringing when the command arrived. */
        data object NothingRinging : Outcome()

        /** `ANSWER_PHONE_CALLS` is not granted. */
        data object NoPermission : Outcome()

        /**
         * The API was called and the call state did NOT change - almost always a self-managed
         * VoIP call, which Telecom ignores silently. **This is the branch that exists so the
         * assistant can say it did not work**, rather than reporting success into a void.
         */
        data class DidNotTake(val detail: String) : Outcome()
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    private fun telecom(context: Context): TelecomManager? =
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    private fun callState(context: Context): Int {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return TelephonyManager.CALL_STATE_IDLE
        return runCatching {
            @Suppress("DEPRECATION") // getCallState() needs no extra permission at our target.
            tm.callState
        }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
    }

    /**
     * Answers the ringing call, then confirms by watching for RINGING to become OFFHOOK.
     *
     * `acceptRingingCall` needs API 26; [Build.VERSION_CODES.O] is below this app's `minSdk` of 24
     * but above it, so the guard is real rather than decorative.
     */
    suspend fun answer(context: Context, speaker: Boolean = false): Outcome {
        if (!hasPermission(context)) return Outcome.NoPermission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Outcome.DidNotTake("this Android version has no accept-call API")
        }
        if (!TelephonyController.isRinging) return Outcome.NothingRinging

        val manager = telecom(context) ?: return Outcome.DidNotTake("no telecom service")
        runCatching { manager.acceptRingingCall() }
            .onFailure {
                Log.d(TAG, "acceptRingingCall threw: ${it.message}")
                return Outcome.DidNotTake("the system refused the request")
            }

        // acceptRingingCall returns void, so the ONLY honest confirmation is the state itself.
        val connected = awaitState(context) { it == TelephonyManager.CALL_STATE_OFFHOOK }
        if (!connected) {
            return Outcome.DidNotTake(
                "the call did not connect - if it is a WhatsApp, Signal or Teams call, Android " +
                    "does not let this app answer it"
            )
        }
        // Routing is requested only AFTER the call is confirmed connected: there is nothing to
        // route before that, and asking early is how a "put it on speaker" silently does nothing.
        val route = if (speaker) CallAudioRoute.toSpeaker(context) else null
        return Outcome.Answered(route)
    }

    /**
     * Rejects the ringing call. `endCall()` rejects rather than hangs up when the call is ringing -
     * AOSP branches to `rejectCall` on `CallState.RINGING` - so this is a genuine decline.
     *
     * Unlike [answer] this API returns a boolean, and it is used: `false` means Telecom refused
     * outright. The state check still runs, because a `true` return is not the same as the ringing
     * having stopped.
     */
    suspend fun reject(context: Context): Outcome {
        if (!hasPermission(context)) return Outcome.NoPermission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return Outcome.DidNotTake("this Android version has no end-call API")
        }
        if (!TelephonyController.isRinging) return Outcome.NothingRinging

        val manager = telecom(context) ?: return Outcome.DidNotTake("no telecom service")
        val accepted = runCatching { manager.endCall() }.getOrElse {
            Log.d(TAG, "endCall threw: ${it.message}")
            false
        }
        if (!accepted) {
            return Outcome.DidNotTake(
                "the system refused - an emergency call cannot be ended, and WhatsApp, Signal or " +
                    "Teams calls are outside this app's reach"
            )
        }

        val stopped = awaitState(context) { it != TelephonyManager.CALL_STATE_RINGING }
        return if (stopped) Outcome.Rejected else Outcome.DidNotTake("the call kept ringing")
    }

    /** Polls the platform call state until [predicate] holds or [CONFIRM_TIMEOUT_MS] elapses. */
    private suspend fun awaitState(context: Context, predicate: (Int) -> Boolean): Boolean {
        var waited = 0L
        while (waited < CONFIRM_TIMEOUT_MS) {
            if (predicate(callState(context))) return true
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        return predicate(callState(context))
    }

    /**
     * The sentence a tool result hands back. Pure and exhaustive, so it is unit-testable and so a
     * new [Outcome] cannot be added without deciding what it says out loud.
     *
     * **Only [Outcome.Answered] and [Outcome.Rejected] are phrased as things that happened.**
     * Everything else says what did NOT happen, in words, per CLAUDE.md §7.
     */
    fun describe(outcome: Outcome): String = when (outcome) {
        is Outcome.Answered ->
            "The call is answered and connected." +
                (outcome.route?.let { CallAudioRoute.describe(it) } ?: "")
        Outcome.Rejected -> "The call was declined and has stopped ringing."
        Outcome.NothingRinging -> "Nothing is ringing right now, so nothing was done."
        Outcome.NoPermission ->
            "I do not have permission to answer or decline calls. Nothing happened. It can be " +
                "granted in Setup."
        is Outcome.DidNotTake -> "That did not work: ${outcome.detail}. The call was not changed."
    }
}
