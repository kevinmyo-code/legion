package com.kevin.legion.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-global mirror of the conversation [Phase], published by
 * [LiveSessionController] so surfaces outside the service (the Cruise screen in
 * the Activity) can reflect the live state - which avatar face to show and the
 * status. The floating button reads the controller's flow directly; this exists
 * for the UI process that doesn't hold a controller reference.
 */
object CompanionPhase {
    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    // Live caption of what Zero is currently saying, mirrored here so the Cruise
    // screen can render subtitles under the avatar (the floating button reads the
    // controller's SharedFlow directly). Empty string = nothing to show.
    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption.asStateFlow()

    // Transient driver-facing notice (a failed tap, a dropped connection). The
    // Cruise/Lights Out screens flash it briefly so a failure is never silent.
    // A SharedFlow, NOT a StateFlow: a frustrated double-tap fires the same
    // string twice, and StateFlow's conflation would swallow the second flash.
    // (Setter is showNotice to avoid a name clash with the val.)
    private val _notice = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4)
    val notice: SharedFlow<String> = _notice.asSharedFlow()

    // Crisis path (CLAUDE.md sec 9.1). True once CrisisDetector matched the
    // driver's speech, until the driver dismisses it.
    //
    // A StateFlow and NOT [notice], which is the transient flash used for failed
    // taps: sec 9.1 requires that we surface real resources and STOP performing
    // the character, and a message that vanishes after two seconds does neither.
    // It persists until acknowledged, and it deliberately outlives the session
    // that triggered it, since the controller closes that session immediately.
    private val _crisis = MutableStateFlow(false)
    val crisis: StateFlow<Boolean> = _crisis.asStateFlow()

    // True while the platform is handing the open capture ZEROES instead of audio,
    // because another app took a privacy-sensitive capture and won arbitration
    // (ticket 15 - .scratch/android-auto/issues/15-the-live-session-can-be-silenced.md).
    // Mirrored here from [GeminiLiveSession.isSilenced] by [LiveSessionController]
    // so the driver-facing strip can say it in WORDS, rather than the signal
    // reaching only CarProbeLog and a Settings diagnostic page.
    //
    // A StateFlow and NOT [notice]: this is an ONGOING condition for as long as the
    // other app holds the mic, not a moment that has passed. A four-second flash
    // would clear while LEGION was still deaf, which is the exact lie the ticket
    // exists to stop - "appearing to listen" while nothing is on the wire.
    //
    // Always false below API 29: `AudioRecordingConfiguration.isClientSilenced` is
    // the only signal the platform offers and it does not exist there. A false here
    // means "not known to be silenced", never "confirmed hearing you".
    private val _silenced = MutableStateFlow(false)
    val silenced: StateFlow<Boolean> = _silenced.asStateFlow()

    fun set(phase: Phase) {
        _phase.value = phase
    }

    fun setCrisis() {
        _crisis.value = true
    }

    /** Driver dismissed the crisis card. Only the UI should call this. */
    fun clearCrisis() {
        _crisis.value = false
    }

    /**
     * Publishes whether the live capture is currently being silenced by platform
     * arbitration. Only [LiveSessionController] should call this - it is a mirror of
     * the session's own signal, not an independent source of truth.
     */
    fun setSilenced(value: Boolean) {
        _silenced.value = value
    }

    fun setCaption(text: String) {
        _caption.value = text
    }

    fun showNotice(text: String) {
        _notice.tryEmit(text)
    }
}
