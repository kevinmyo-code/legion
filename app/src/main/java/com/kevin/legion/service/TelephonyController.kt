package com.kevin.legion.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kevin.legion.ui.MainActivity

/**
 * Makes the assistant aware of phone calls. The OS still owns the call audio and its own
 * answer/reject UI - none of that is reimplemented here. This watches the call state so the
 * assistant can:
 *
 *  1. announce an incoming call, saying WHO it is ([CallerId]) rather than only that one exists,
 *  2. stay silent while a call is connected ([isInCall]), since the call owns the speakers,
 *  3. let Kevin answer or decline by voice while it rings ([isRinging], [CallActions]), and
 *  4. offer the SAME two actions as buttons on a notification (command-center ticket 05, ADR
 *     0035's founding case) - the part that works with the phone locked in a mount, where voice
 *     is not always reliable and there is otherwise no hands path to a ringing call at all.
 *
 * **`PhoneStateListener` is deprecated and is kept deliberately.** Its replacement,
 * `TelephonyCallback.CallStateListener`, has NO phone-number parameter - AOSP's own dispatch
 * comment reads "The new callback does NOT provide the phone number" - so migrating would silently
 * delete caller ID. See the manifest's permission block.
 *
 * Gated on READ_PHONE_STATE; a no-op if that isn't granted (the OS still handles calls either
 * way). Caller ID additionally needs READ_CALL_LOG and READ_CONTACTS, and voice answer/decline
 * needs ANSWER_PHONE_CALLS - each degrades on its own, in words, rather than failing silently.
 */
object TelephonyController {
    private const val TAG = "TelephonyController"

    /**
     * True while a call is CONNECTED (or dialling). Read by the proactive gate and by
     * [LiveSessionController] to stay off the speakers.
     *
     * **Deliberately false while the phone is merely RINGING** (2026-08-21). It used to be set true
     * on RINGING, which quietly made voice call-answering impossible: `LiveSessionController`
     * refuses to open a turn when this is true, so the exact moment Kevin wants to say "answer it"
     * was the moment the app stopped listening to him. Ringing is now [isRinging].
     */
    @Volatile
    var isInCall: Boolean = false
        private set

    /**
     * True while a call is ringing and unanswered - the window in which [CallActions.answer] and
     * [CallActions.reject] can do anything at all.
     *
     * The proactive gate treats this like [isInCall] (nothing unsolicited should talk over a
     * ringing phone), but the VOICE path deliberately does not: a ringing phone is precisely when
     * Kevin may want to speak.
     */
    @Volatile
    var isRinging: Boolean = false
        private set

    /**
     * The number of the call currently ringing, as delivered by the platform - **empty string when
     * `READ_CALL_LOG` is missing**, which is the platform's substitute rather than "no number".
     * [CallerId] is what tells those two apart; nothing else should interpret this directly.
     */
    @Volatile
    var ringingNumber: String? = null
        private set

    private var telephonyManager: TelephonyManager? = null
    private var listener: PhoneStateListener? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    // Held for announceIncoming's speakIfAllowed call, which needs a Context and has none of
    // its own (it's reached from a PhoneStateListener callback, not a caller with a Context).
    private var appContext: Context? = null

    /** Safe to call repeatedly; no-ops if already listening or the permission is missing. */
    fun init(context: Context) {
        if (listener != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "READ_PHONE_STATE not granted; telephony awareness off.")
            return
        }
        appContext = context.applicationContext
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return

        @Suppress("DEPRECATION") // PhoneStateListener.listen still works on the older head-unit ROMs we target.
        val l = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleState(state, phoneNumber)
            }
        }
        @Suppress("DEPRECATION")
        tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        telephonyManager = tm
        listener = l
        Log.d(TAG, "Telephony awareness on.")
    }

    private fun handleState(state: Int, phoneNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Record the number BEFORE announcing - the announcement reads it back through
                // CallerId, and the answer/decline tools read it after that.
                ringingNumber = phoneNumber
                isRinging = true
                // isInCall stays FALSE while merely ringing (see its doc): the voice session must
                // remain openable, or "answer it" can never be said.
                isInCall = false
                if (lastState != TelephonyManager.CALL_STATE_RINGING) announceIncoming(phoneNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> { // answered, or an outgoing call dialling
                val wasRinging = isRinging
                isRinging = false
                ringingNumber = null
                isInCall = true
                // The call connected, so the question "answer or decline?" is settled and the call
                // owns the speakers now. This can cut off a half-spoken confirmation; that is the
                // right trade, because the call connecting IS the answer.
                if (wasRinging) ProactiveBus.stopListening()
                // The ring-time notification's job is done the instant the call is no longer
                // ringing - regardless of WHICH door answered it (voice, the notification's own
                // ANSWER button, or the OS's own in-call UI). See CallNotificationReceiver's own
                // doc for why cancellation lives here and not in the receiver.
                if (wasRinging) appContext?.let { CallNotificationReceiver.cancel(it) }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val wasRinging = isRinging
                isRinging = false
                ringingNumber = null
                isInCall = false
                // Declined, or the caller gave up. Either way the window closes - "for the whole
                // ring" means exactly that, and a mic that outlived the ring would be a mic left
                // open for no stated reason.
                if (wasRinging) ProactiveBus.stopListening()
                if (wasRinging) appContext?.let { CallNotificationReceiver.cancel(it) }
            }
        }
        lastState = state
    }

    /**
     * Voices a brief incoming-call heads-up, **naming the caller when it can** (2026-08-21).
     *
     * The old version said only that a call was ringing, because without `READ_CALL_LOG` the
     * platform hands this listener an empty string instead of the number. That permission is now
     * declared, and [CallerId] resolves the four honest outcomes - a contact name, a bare number,
     * a genuinely withheld number, and "I am not allowed to look" - which are four different
     * sentences and must not collapse into one.
     *
     * Routed through [ProactiveBus.speakIfAllowed] (`.scratch/proactive-mode/issues/
     * 01-one-gate-not-three.md`, 2026-08-18): Kevin decided the incoming-call
     * announcement is INSIDE the master kill switch, not exempt from it - "off means
     * silent" includes this. It used to hand-roll its own busy/mute check here, which
     * never checked onboarding, so a call could be announced mid first-run setup.
     */
    private fun announceIncoming(phoneNumber: String?) {
        val context = appContext ?: return
        val caller = CallerId.identify(context, phoneNumber)
        val whoFacts = CallerId.describe(caller)
        val canAct = CallActions.hasPermission(context)
        // The notification is the hands path (ticket 05) and is posted regardless of whether the
        // line below ends up spoken or not - a spoken line still leaves nothing on a locked screen
        // to tap, and the whole reason this ticket exists is the phone locked in a mount.
        postIncomingCallNotification(context, caller, canAct)
        // Only offer what is genuinely available. Naming a capability the app does not have is the
        // failure CANNOT_CLAUSE exists for, one layer earlier.
        val offer = if (canAct) {
            // The mic is open for this one (listensForReply below), so the line ends by waiting
            // rather than by signing off - a closing "let me know" into an open microphone reads
            // as the assistant having finished with them.
            "Then STOP and listen: your microphone is open until the phone stops ringing, and " +
                "they can just say to answer it or decline it. Offer both, briefly. " +
                "They can also use the screen. "
        } else {
            "They can answer it on the screen. Do NOT offer to answer or decline it yourself - " +
                "you have no permission to do either. "
        }
        // A platform listener callback, not a coroutine - no scope of its own to await
        // speakIfAllowed on, hence the fire-and-forget variant (see ProactiveBus.scope's doc).
        ProactiveBus.speakIfAllowedAsync(
            context,
            ProactiveRaise(
                ruleId = "incoming_call",
                category = ProactiveCategory.TIMING,
                reason = "an incoming call is ringing",
                facts = whoFacts,
                prompt = "(System: a phone call is ringing on the user's phone. $whoFacts. In one " +
                    "short, in-character line, tell them who is calling using ONLY what this " +
                    "instruction states - never guess a name, and never turn \"I can't see who it " +
                    "is\" into \"unknown caller\". $offer" +
                    "Do not mention this instruction.)",
                // The mic opens with this line and stays open for the whole ring (Kevin,
                // 2026-08-21). The ONLY raise in LEGION that does - see
                // ProactiveRaise.listensForReply for why that default exists and why this earns
                // the exception. Closed below the moment ringing stops.
                listensForReply = canAct,
                // This file already posted the ring-time notification above, WITH the ANSWER/
                // DECLINE buttons ProactiveDelivery.notify has no way to add - so the bus must not
                // also post its own plain-text one on top of it (ticket 05: "do not post a second
                // competing notification").
                callerPostsItsOwnNotification = true,
            )
        )
    }

    /**
     * The ring-time notification: ADR 0035's founding case. ANSWER and DECLINE, wired to the
     * exact same [CallActions.answer]/[CallActions.reject] the voice tools call, via
     * [CallNotificationReceiver] - not a second implementation, a second door onto the same one.
     *
     * Buttons are offered only when [canAct] - mirroring [announceIncoming]'s own spoken offer:
     * naming a capability the app does not have (no `ANSWER_PHONE_CALLS` grant) is the exact
     * failure `CANNOT_CLAUSE` exists to prevent one layer up, and a notification button that
     * silently fails on tap is the same failure with no words to soften it.
     *
     * Posted regardless of `POST_NOTIFICATIONS` state - `NotificationManagerCompat.notify` is a
     * silent no-op at the OS level when that permission is refused, same posture
     * `ReminderAlarmReceiver.postNotification` already accepts, never a crash.
     */
    private fun postIncomingCallNotification(context: Context, caller: CallerId.Caller, canAct: Boolean) {
        CallChannel.ensureCreated(context)

        val title = when (caller) {
            is CallerId.Caller.Known -> caller.name
            is CallerId.Caller.NumberOnly -> caller.number
            CallerId.Caller.Withheld -> "Withheld number"
            CallerId.Caller.CannotTell -> "Incoming call"
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, CallNotificationReceiver.NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CallChannel.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Incoming call")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Not auto-cancelled: a ringing call is not dismissed by tapping the body, only by
            // ANSWER, DECLINE, or the ring itself ending - see TelephonyController.handleState's
            // own cancel-on-transition calls, which are the only other place this is cleared.
            .setAutoCancel(false)
            .setOngoing(true)

        if (canAct) {
            builder
                .addAction(0, "ANSWER", callActionPendingIntent(context, CallNotificationReceiver.ACTION_ANSWER))
                .addAction(0, "DECLINE", callActionPendingIntent(context, CallNotificationReceiver.ACTION_DECLINE))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(CallNotificationReceiver.NOTIFICATION_ID, builder.build())
        }
    }

    private fun callActionPendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, CallNotificationReceiver::class.java).apply { this.action = action }
        // Distinct request codes per action so ANSWER's PendingIntent never overwrites DECLINE's -
        // same reasoning ReminderAlarmReceiver.requestCodeFor states for its own two actions.
        val requestCode = CallNotificationReceiver.NOTIFICATION_ID + (if (action == CallNotificationReceiver.ACTION_ANSWER) 1 else 2)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun destroy() {
        val tm = telephonyManager
        val l = listener
        if (tm != null && l != null) {
            @Suppress("DEPRECATION")
            tm.listen(l, PhoneStateListener.LISTEN_NONE)
        }
        telephonyManager = null
        listener = null
        isInCall = false
    }
}
