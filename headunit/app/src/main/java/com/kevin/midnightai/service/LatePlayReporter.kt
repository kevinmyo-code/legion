package com.kevin.midnightai.service

import android.content.Context
import com.kevin.midnightai.MidnightEvents
import com.kevin.midnightai.media.LatePlayReportLogic
import com.kevin.midnightai.media.LatePlayReportLogic.Late
import com.kevin.midnightai.media.PlayRequests
import com.kevin.midnightai.ui.OnboardingState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells the driver how a `play_music` ended after the tool had already answered "still lining
 * up", through the same [ProactiveBus] path as the other unprompted lines.
 *
 * Gates, and why each is what it is. This is the answer to a request the driver MADE, not chatter,
 * so [ProactivePreferences.muted] is deliberately not consulted (the same reasoning as the DTC
 * sheet's "ASK" button, see [ProactiveBus]). Onboarding and phone calls are respected, because
 * speaking then is wrong whoever asked. A running voice turn is WAITED OUT (bounded) rather than
 * dropped: the whole point is that the report must not silently lapse. If it is still running at
 * the bound, the line is folded into that turn ([LiveSessionController.requestSpeak] handles it).
 *
 * Not a guarantee: a call that is still up after the wait, or unfinished onboarding, drops the
 * line. Each drop leaves a breadcrumb, so a missing report is explainable.
 */
internal object LatePlayReporter {

    suspend fun report(context: Context, token: PlayRequests.Token, late: Late) {
        // Superseded, or a newer choice made while this ran: that request owns the driver now.
        if (late == Late.Superseded || !PlayRequests.shouldProceed(token)) {
            MidnightEvents.playMusicLateReport(spoken = false, why = "superseded")
            return
        }
        val line = LatePlayReportLogic.spokenLine(late) ?: return
        if (!OnboardingState.isComplete(context)) {
            MidnightEvents.playMusicLateReport(spoken = false, why = "onboarding")
            return
        }
        withTimeoutOrNull(LatePlayReportLogic.IDLE_WAIT_MS) { ConversationState.busy.first { !it } }
        if (TelephonyController.isInCall) {
            MidnightEvents.playMusicLateReport(spoken = false, why = "in_call")
            return
        }
        // Re-check after the wait: the driver may have chosen something else while it ran.
        if (!PlayRequests.shouldProceed(token)) {
            MidnightEvents.playMusicLateReport(spoken = false, why = "superseded_while_waiting")
            return
        }
        MidnightEvents.playMusicLateReport(spoken = true, why = if (late is Late.Started) "started" else "failed")
        ProactiveBus.requestSpeak(LatePlayReportLogic.prompt(line))
    }

    /** A late play that THREW: no Spotify sentence exists for it, so it gets its own. */
    fun threwReason(query: String): String =
        "I couldn't start \"$query\" on Spotify - something went wrong on the way. " +
            "Nothing is playing from that request."
}
