package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.ai.OnboardingState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Events from the proactive engine (in [AriaForegroundService]) to the
 * service-owned [LiveSessionController].
 *
 *  - [requestSpeak] (the flow) opens a session if needed and injects a text
 *    turn so Gemini voices a line (openers, health alerts, a driver-asked
 *    question) - the session then stays open for the driver's reply.
 *
 * The floating button drives push-to-talk (and starting a session) by calling
 * the controller directly, so there's no event for that here.
 *
 * **One raw emit, two named doors, both gated by WHO asked - not a choke
 * point that treats all speech alike.** `.scratch/proactive-mode/issues/
 * 01-one-gate-not-three.md` found the previous shape (a single public
 * `requestSpeak(prompt)`) let three different authors each hand-roll their
 * own copy of "onboarding done / not busy / not in a call / not muted" at
 * their own call site, and a fourth caller added by anyone, ever, inherited
 * none of it. The raw emit is now PRIVATE - nothing outside this object can
 * raise without going through one of:
 *
 *  - [speakSolicited] - the driver asked, by voice or by tap. Never gated.
 *    [DtcSheet]'s "ASK" button is the reason this exists as its own method
 *    instead of folding into the gated path: that button is driver-tapped
 *    (an explicit request, not chatter), and a mute must never silence a
 *    button the driver just pressed. (`DtcSheet` doesn't exist yet - `ui/`
 *    is a clean slate as of 2026-08-18 - but the day it's built, its ASK
 *    path calls this, not [speakIfAllowed].)
 *  - [speakIfAllowed] - the ONLY unsolicited path. Runs the mute/busy/call/
 *    onboarding gate that used to be duplicated at each caller and reports
 *    back whether it actually spoke, so a caller that cares (nothing does
 *    yet) can tell "silently gated" from "raised".
 */
object ProactiveBus {
    private val _requestSpeak = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val requestSpeak: SharedFlow<String> = _requestSpeak

    /** The only place that touches the raw flow. Everything above gates through this. */
    private fun emit(prompt: String) {
        _requestSpeak.tryEmit(prompt)
    }

    /**
     * Driver-initiated speech: voice, or a tap like [DtcSheet]'s "ASK" button. Never
     * gated by mute/busy/call/onboarding - the driver just asked, directly, and a
     * kill switch that silences an explicit request is not a kill switch anyone
     * would trust either way (see this object's doc comment for the full argument).
     */
    fun speakSolicited(prompt: String) {
        emit(prompt)
    }

    /**
     * The ONLY unsolicited path - idle chatter, health/recall alerts, arrival and
     * reminder proactives, the incoming-call announcement. Runs the same four
     * checks [ProactiveGate.speakIfIdle] used to run inline (onboarding complete,
     * not mid-conversation, not in a phone call, not muted) and returns whether it
     * actually spoke, so a caller can tell "gated" from "raised" instead of both
     * looking like silence.
     */
    fun speakIfAllowed(context: Context, prompt: String): Boolean {
        // Stay silent until first-run onboarding is done.
        if (!OnboardingState.isComplete(context)) return false
        if (ConversationState.isBusy) return false
        // Don't talk over a phone call - the call owns the speakers.
        if (TelephonyController.isInCall) return false
        // The master kill switch. Nothing unsolicited is exempt (settled decision 2,
        // `.scratch/proactive-mode/map.md`) - incoming-call announcements included.
        if (ProactivePreferences.muted.value) return false
        emit(prompt)
        return true
    }
}
