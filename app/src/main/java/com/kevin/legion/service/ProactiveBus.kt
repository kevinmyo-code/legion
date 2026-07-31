package com.kevin.legion.service

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
 * Not the mute choke point on purpose: [DtcSheet]'s "ASK" button is driver-
 * tapped (an explicit request, not chatter) and calls this directly, so the
 * [ProactivePreferences] mute check lives at each unsolicited caller instead
 * (`AriaForegroundService.speakProactive`, `TelephonyController`'s call
 * announcement) - covering idle chatter and alerts without silencing a
 * button the driver just pressed.
 */
object ProactiveBus {
    private val _requestSpeak = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val requestSpeak: SharedFlow<String> = _requestSpeak

    fun requestSpeak(prompt: String) {
        _requestSpeak.tryEmit(prompt)
    }
}
