package com.kevin.midnightai.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Events from the proactive engine (in [AriaForegroundService]) to the
 * service-owned [LiveSessionController].
 *
 *  - [requestSpeak] opens a session if needed and injects a text turn so Gemini
 *    voices a proactive line (openers, health alerts); the session then stays
 *    open for the driver's reply.
 *
 * The floating button drives push-to-talk (and starting a session) by calling
 * the controller directly, so there's no event for that here.
 *
 * Not the mute choke point on purpose: [DtcContent]'s "ASK" button is driver-
 * tapped (an explicit request, not chatter) and calls this directly, so the
 * [ProactivePreferences] mute check lives at each unsolicited caller instead
 * (`AriaForegroundService.speakProactive`, `TelephonyController`'s call
 * announcement) - covering idle chatter and alerts without silencing a
 * button the driver just pressed.
 *
 * **Every request names its [ProactiveKind]**, with no default, so adding a caller is a decision
 * about whether a goodbye may swallow its line rather than an omission. See [ProactiveKind] for
 * the ruling that needed it.
 */
object ProactiveBus {
    /**
     * One line for the live session to voice.
     *
     * The flow used to carry a bare `String`, which is what left the goodbye rule unable to obey
     * its own word "routine": urgency is a property OF THE LINE, and a side channel would let the
     * two disagree. Same reasoning as the phone's own `SpeakRequest`.
     */
    data class SpeakRequest(
        val prompt: String,
        val kind: ProactiveKind,
        /**
         * Fired when this line really reached the socket, and only then. It is how a caller with
         * an "already told" marker keeps it honest: see [AnnouncedCodes]. Never fired for a line
         * that was gated, held and then dropped, evicted, went stale, or sat on a socket that
         * failed to connect, so such a caller raises it again rather than believing it was said.
         *
         * May run on any thread, and must be cheap: the controller calls it inline on the path
         * that feeds the socket.
         */
        val onSpoken: () -> Unit = {},
    )

    private val _requestSpeak = MutableSharedFlow<SpeakRequest>(extraBufferCapacity = 4)
    val requestSpeak: SharedFlow<SpeakRequest> = _requestSpeak

    fun requestSpeak(prompt: String, kind: ProactiveKind, onSpoken: () -> Unit = {}) {
        _requestSpeak.tryEmit(SpeakRequest(prompt, kind, onSpoken))
    }
}
