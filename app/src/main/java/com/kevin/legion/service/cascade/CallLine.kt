package com.kevin.legion.service.cascade

import com.kevin.legion.service.turn.VoiceOutlet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * The call that is up, for the screen that draws it (Kevin, 2026-11-24: the whole exchange should
 * feel like a phone call). Process-global, the same shape and the same reason as
 * [com.kevin.legion.service.CompanionPhase]: the session owns the call and the Activity has no
 * reference to it.
 *
 * [answeredAtMs] is null until the pickup line's first audio, so the screen says `Connecting...` and
 * the timer starts when the other side answers, not when he pressed the button.
 *
 * Every setter carries the [callId] of the call it is about and is ignored for any other, so a
 * session tearing down after the next call opened cannot wipe the live one's state.
 */
internal data class CallLineState(
    val callId: Long,
    val answeredAtMs: Long? = null,
    val muted: Boolean = false,
    /** Where his voice really goes, from the route's own readback ([VoiceOutput.live]). */
    val outlet: VoiceOutlet = VoiceOutlet.EARPIECE,
)

internal object CallLine {
    private val ids = AtomicLong(0)
    private val state = MutableStateFlow<CallLineState?>(null)

    /** The call up now; null when none is. */
    val line: StateFlow<CallLineState?> = state.asStateFlow()

    /** One id per call, so a late setter from a closed call cannot touch the live one. */
    fun nextId(): Long = ids.incrementAndGet()

    /** A call is being placed: the screen goes up saying `Connecting...`. */
    fun began(callId: Long, outlet: VoiceOutlet) {
        state.value = CallLineState(callId, outlet = outlet)
    }

    /** The pickup line's first audio: the timer starts here. Idempotent, so a later clip does not reset it. */
    fun answered(callId: Long, atMs: Long) {
        edit(callId) { if (it.answeredAtMs == null) it.copy(answeredAtMs = atMs) else it }
    }

    fun muted(callId: Long, on: Boolean) {
        edit(callId) { it.copy(muted = on) }
    }

    fun outlet(callId: Long, outlet: VoiceOutlet) {
        edit(callId) { it.copy(outlet = outlet) }
    }

    /** The call is over. A stale id is ignored: the next call has already taken the line. */
    fun ended(callId: Long) {
        state.update { now -> if (now?.callId == callId) null else now }
    }

    /** Atomic: the session, its route follower and the screen's controls all write from own threads. */
    private inline fun edit(callId: Long, change: (CallLineState) -> CallLineState) {
        state.update { now -> if (now == null || now.callId != callId) now else change(now) }
    }
}

/**
 * One control on the in-call screen: its name, whether it can be pressed, and what TalkBack says.
 * The screen draws [glyph], the icon a phone's dialer uses for it (Kevin, 2026-11-28: "regular call
 * icons and not words"), lit when [active], as a dialer lights Mute and Speaker while they are on.
 * [label] is the control's short name, kept for the rule's own tests and logs; the screen shows no word.
 */
internal data class CallControl(
    val label: String,
    val enabled: Boolean,
    val detail: String,
    val glyph: CallGlyph = CallGlyph.HANG_UP,
    val active: Boolean = false,
)

/** The icons the in-call screen draws, one per face a control can have. */
internal enum class CallGlyph { MIC_OFF, SPEAKER, HEADPHONES, HANG_UP }

/**
 * What the in-call screen says. Pure, so every case is a unit test: the status line, the elapsed
 * timer and the three controls' faces.
 */
internal object InCallRule {

    const val CONNECTING = "Connecting..."

    /** `Connecting...` until the pickup is heard, then the elapsed time. */
    fun status(answeredAtMs: Long?, nowMs: Long): String =
        if (answeredAtMs == null) CONNECTING else elapsed(nowMs - answeredAtMs)

    /**
     * `mm:ss`, as a phone shows a call. Minutes are not folded into hours: a call to Alfred that runs
     * past an hour is worth seeing as the 63 minutes it is. A clock that went backwards reads zero
     * rather than a negative.
     */
    fun elapsed(ms: Long): String {
        val seconds = (ms / MS_PER_S).coerceAtLeast(0)
        return "%02d:%02d".format(seconds / S_PER_MIN, seconds % S_PER_MIN)
    }

    /**
     * Mute: his own mic, off and on. Always pressable, because a call he cannot mute is a call he
     * cannot step away from.
     */
    fun mute(muted: Boolean): CallControl =
        if (muted) {
            CallControl(
                "Unmute",
                enabled = true,
                detail = "Muted. He cannot hear you. Tap to let him hear you again",
                glyph = CallGlyph.MIC_OFF,
                active = true,
            )
        } else {
            CallControl(
                "Mute",
                enabled = true,
                detail = "Mute your microphone. Alfred keeps talking",
                glyph = CallGlyph.MIC_OFF,
            )
        }

    /**
     * Speaker moves the call off the ear and back. Disabled on a headset or headphones, where the
     * route is theirs and the ask would be a button that claims a move it cannot make
     * ([com.kevin.legion.service.turn.CallAudioMode] is hands-off on a hands-free link), and during a
     * real phone call, where the whole conversation is hands off.
     */
    fun speaker(outlet: VoiceOutlet): CallControl = when (outlet) {
        VoiceOutlet.EARPIECE ->
            CallControl("Speaker", enabled = true, detail = "Move the call to the loudspeaker", glyph = CallGlyph.SPEAKER)
        VoiceOutlet.LOUDSPEAKER ->
            CallControl(
                "Earpiece",
                enabled = true,
                detail = "On the loudspeaker. Tap to move the call back to your ear",
                glyph = CallGlyph.SPEAKER,
                active = true,
            )
        VoiceOutlet.WIRED ->
            CallControl(
                "Headphones",
                enabled = false,
                detail = "The call is on the connected headphones",
                glyph = CallGlyph.HEADPHONES,
            )
        VoiceOutlet.BLUETOOTH ->
            CallControl(
                "Headset",
                enabled = false,
                detail = "The call is on the connected headset",
                glyph = CallGlyph.HEADPHONES,
            )
        VoiceOutlet.PHONE_CALL ->
            CallControl("Speaker", enabled = false, detail = "A phone call has the line", glyph = CallGlyph.SPEAKER)
    }

    /**
     * Hang up ends the call: the red round button every phone has (Kevin, 2026-11-28), an exception to
     * ADR 0081's one accent, because a call screen whose end button is not red does not read as a call.
     */
    fun hangUp(): CallControl =
        CallControl("Hang up", enabled = true, detail = "End the call with Alfred", glyph = CallGlyph.HANG_UP)

    private const val MS_PER_S = 1_000L
    private const val S_PER_MIN = 60L
}
