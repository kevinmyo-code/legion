package com.kevin.legion.dumb

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.util.Log
import com.kevin.legion.MidnightApplication

/**
 * Screens incoming calls while dumb mode is on (ADR 0062). Android only binds this once the user
 * gives LEGION `ROLE_CALL_SCREENING` (the setup screen's button), which **displaces whichever call
 * screener held it before, Samsung's Smart Call included**.
 *
 * The answer is always an allow with [CallResponse.Builder.setSilenceCall] on or off: never
 * `setDisallowCall`, never `setRejectCall`, never `setSkipCallLog`, never `setSkipNotification`. A
 * silenced call is still logged and still shows, so a stranger's call is quiet, not lost. If any of
 * LEGION's own logic throws, the call is allowed to ring: failing quiet would be the worse mistake.
 *
 * Calls from numbers in the user's contacts are only delivered here when LEGION holds READ_CONTACTS
 * (it is asked for when the first chosen person is added).
 */
class DumbCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val action = runCatching { decide(callDetails) }
            .onFailure { Log.w(TAG, "Call screening failed; letting the call ring", it) }
            .getOrDefault(CallAction.ALLOW)
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(action == CallAction.SILENCE)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build(),
        )
    }

    private fun decide(details: Call.Details): CallAction {
        val screener = (application as MidnightApplication).dumb.callScreener
        val incoming = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            details.callDirection == Call.Details.DIRECTION_INCOMING
        // A withheld or private number has a handle but a presentation that is not ALLOWED.
        val number = details.handle?.schemeSpecificPart
            ?.takeIf { details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED }
        return screener.decide(number, incoming, System.currentTimeMillis())
    }

    private companion object {
        const val TAG = "DumbCallScreening"
    }
}
