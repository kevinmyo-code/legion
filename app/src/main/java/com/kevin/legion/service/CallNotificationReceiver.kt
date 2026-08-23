package com.kevin.legion.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ANSWER / DECLINE tapped directly on the incoming-call notification (command-center ticket 05,
 * ADR 0035's founding case). Must work with the phone locked in a mount and no session running -
 * a receiver reached straight from the notification shade, same shape [ReminderActionReceiver]
 * already uses for SNOOZE/DONE, not a path through [LiveSessionController] or any voice turn.
 *
 * **Calls the exact same [CallActions.answer]/[CallActions.reject] the voice tools call.** Not a
 * second implementation - this receiver is a second DOOR to the one function, which is the whole
 * point of ADR 0035 ("both paths call the same controller").
 *
 * **Never claims an outcome it did not observe (CLAUDE.md §7).** [CallActions.answer]/[reject]
 * already do the honest thing - they watch the platform's own call state rather than trusting the
 * API call that returns `void` - so this receiver inherits that discipline for free by calling
 * through rather than re-implementing it. The only place THIS file could still overclaim is the
 * toast it shows on a failure, which is why that toast is built from [CallActions.describe] and
 * nothing hand-written.
 *
 * **Cancelling the notification is [TelephonyController]'s job, not this receiver's** - the ring
 * window closes on the platform's own RINGING -> OFFHOOK/IDLE transition, which
 * [TelephonyController.handleState] already reacts to regardless of what caused it (a real hang-up,
 * the voice path, or this receiver). Cancelling here too, unconditionally, would race a
 * simultaneous voice "decline it" and dismiss a notification for a call that is, in fact, still
 * ringing because THIS action failed - see [CallActions.Outcome.DidNotTake].
 */
class CallNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val handler = handlerFor(intent.action) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val outcome = handler(context)
                logAndMaybeToast(context, intent.action ?: "", outcome)
            } finally {
                pending.finish()
            }
        }
    }

    /** Logs every outcome (so a silent [CallActions.Outcome.DidNotTake] is at least visible in
     * logcat) and toasts only the DID-NOT-TAKE case - a tap that visibly did something (the call
     * connected, or the ringing stopped) needs no extra confirmation, but a tap that silently did
     * nothing is exactly the failure mode this whole file exists to make honest about. The toast
     * text is [CallActions.describe]'s own sentence, never a paraphrase, so it can never say more
     * than the observed state supports. */
    private fun logAndMaybeToast(context: Context, action: String, outcome: CallActions.Outcome) {
        Log.d(TAG, "$action -> $outcome")
        if (outcome is CallActions.Outcome.DidNotTake || outcome is CallActions.Outcome.NoPermission) {
            // Runs on a background dispatcher (goAsync's coroutine); Toast must show from the main
            // looper, so this hops there explicitly rather than assuming Toast.makeText is safe off it.
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, CallActions.describe(outcome), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "CallNotificationReceiver"
        const val ACTION_ANSWER = "com.kevin.legion.action.CALL_ANSWER"
        const val ACTION_DECLINE = "com.kevin.legion.action.CALL_DECLINE"

        /**
         * Maps a broadcast action to the [CallActions] function it dispatches to, or null for
         * anything else (including a null action). Pulled out of [onReceive] as its own function
         * so the ROUTING - which of the two actions calls which of [CallActions.answer]/[reject] -
         * is reachable from a plain Robolectric test without going through `goAsync()`, which
         * needs a real system-delivered `PendingResult` that calling `onReceive` directly does not
         * set up. The actual answer/reject dispatch itself is [CallActions]'s own responsibility and
         * is verified on the real device, per this ticket's own deferred verification step.
         */
        internal fun handlerFor(action: String?): (suspend (Context) -> CallActions.Outcome)? = when (action) {
            ACTION_ANSWER -> { context -> CallActions.answer(context) }
            ACTION_DECLINE -> { context -> CallActions.reject(context) }
            else -> null
        }

        /** One fixed id: only one call can ring at a time, so there is never a second incoming-call
         * notification to collide with. Distinct from every other notification id in the app
         * (reminders key off `item.id.toInt()`, proactive off `raise.ruleId.hashCode()`), and small
         * enough it can never collide with either by chance. */
        const val NOTIFICATION_ID = 9100

        /** Cancels the ring-time notification. Called from [TelephonyController] on every state
         * transition that closes the ring window - see this file's own doc for why cancellation
         * lives there and not in [onReceive]. */
        fun cancel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
