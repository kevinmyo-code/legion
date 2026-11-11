package com.kevin.legion.service.cascade

/**
 * Every deadline the laptop pipeline keeps, in one place, sized to what the laptop service
 * MEASURED with Kyutai on the RTX 4060 (service report 2026-11-11, `laptop-voice-1110.md`) with
 * headroom, not to the contract's phone-side 250/400/500 ms figures (those assume a phone STT and
 * would send every reply to Live). Kevin rules on the final values; these defaults must let the
 * pipeline work. A miss is never silent: the conversation goes to Live and the log says which.
 *
 * | Deadline | Value | Measured |
 * |---|---|---|
 * | `/health` probe at open | 1.2 s | answered without touching the GPU; Tailscale adds a round trip |
 * | WebSocket open to `ready` | 2.5 s | models are already loaded when `/health` says ready |
 * | Final text after the phone ends a turn | 3.0 s | median 0.32 s, p90 0.56 s; service budget 5 s per step |
 * | First audio of a reply | 2.5 s | `tts.speak` median 0.75 s (p90 0.76); streamed text ~1.0 s |
 * | Phone silence cap when the service ends turns (`EndOfTurnPolicy.CAP_MS`) | 2.5 s | median 0.8 s, max 1.9 s |
 * | Wall-clock utterance cap | 28 s | service closes at 30 s |
 * | "Recently unreachable": skip the probe | 30 s | so the wake word does not wait out a probe while Tailscale is off |
 */
object CascadeTimings {
    const val PROBE_TIMEOUT_MS = 1_200L
    const val OPEN_TIMEOUT_MS = 2_500L

    /** Silero / Smart Turn loading on the phone (Live's own wait is similar). */
    const val MODEL_WAIT_MS = 1_500L
    const val STT_FINAL_TIMEOUT_MS = 3_000L
    const val TTS_FIRST_AUDIO_TIMEOUT_MS = 2_500L
    const val UTTERANCE_CAP_MS = 28_000L
    const val RECENT_UNREACHABLE_MS = 30_000L

    /** Settings > Test connection: a person is waiting for an answer, so a little longer. */
    const val SETTINGS_TEST_TIMEOUT_MS = 3_000L
}
