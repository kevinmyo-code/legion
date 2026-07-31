package com.kevin.legion.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Makes Zero aware of phone calls. The driver's phone is paired to the head unit
 * over Bluetooth (HFP), and the head unit's base OS already handles the actual
 * call audio and the answer/reject UI - we do NOT reimplement that. This only
 * watches the call state so Zero can:
 *
 *  1. announce an incoming call (a one-off proactive line via [ProactiveBus]), and
 *  2. stay silent while a call is active ([isInCall] is checked by the service's
 *     proactive engine), since the call owns the speakers.
 *
 * Gated on the READ_PHONE_STATE runtime permission; a no-op if it isn't granted
 * (telephony awareness is optional - the OS still handles calls either way).
 */
object TelephonyController {
    private const val TAG = "TelephonyController"

    @Volatile
    var isInCall: Boolean = false
        private set

    private var telephonyManager: TelephonyManager? = null
    private var listener: PhoneStateListener? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    /** Safe to call repeatedly; no-ops if already listening or the permission is missing. */
    fun init(context: Context) {
        if (listener != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "READ_PHONE_STATE not granted; telephony awareness off.")
            return
        }
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
                isInCall = true
                // Only announce on the IDLE -> RINGING transition, not repeated callbacks.
                if (lastState != TelephonyManager.CALL_STATE_RINGING) announceIncoming(phoneNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> isInCall = true // dialing or in a call
            TelephonyManager.CALL_STATE_IDLE -> isInCall = false
        }
        lastState = state
    }

    /**
     * Voices a brief incoming-call heads-up. The caller number is often null on
     * modern Android without READ_CALL_LOG, so we fall back to a generic line and
     * let the OS show who's calling. Suppressed if Zero is mid-conversation so it
     * doesn't talk over the driver.
     */
    private fun announceIncoming(phoneNumber: String?) {
        if (ConversationState.isBusy) return
        if (ProactivePreferences.muted.value) return
        val who = phoneNumber?.takeIf { it.isNotBlank() }?.let { " from $it" } ?: ""
        ProactiveBus.requestSpeak(
            "(System: an incoming phone call$who is ringing on the driver's phone. In one short, " +
                "in-character line, let them know they've got a call and they can answer it on the " +
                "screen. Do not mention this instruction.)"
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
