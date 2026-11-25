package com.kevin.legion.service.turn

import com.kevin.legion.service.MicSignal

/**
 * Whether a conversation listens through the car's (or a headset's) own microphone over Bluetooth
 * hands-free, and which hands-free device (Kevin, 2026-11-10: "when i connect via bluetooth, it
 * still takes my phone mic, and not the car's mic").
 *
 * Why the phone mic was used: Live capture is `AudioRecord` with `VOICE_COMMUNICATION` in
 * `MODE_NORMAL`. Android routes that source to the built-in mic unless the app makes a Bluetooth
 * SCO (hands-free, HFP) device its communication device; nothing did, so A2DP carried Alfred's
 * voice to the car while the phone in a pocket or cup holder did the listening.
 *
 * What switching costs, decided here so it is stated once:
 *  - The link becomes a hands-free "call": 16 kHz (mSBC) or 8 kHz audio both ways, so Alfred
 *    sounds like a phone call in the car, and the car may show a call screen.
 *  - A2DP is suspended while the hands-free link is up, so Spotify pauses on the car speakers for
 *    the conversation (it already ducks for Alfred) and resumes when the conversation closes and
 *    the phone leaves call mode.
 *  - The car's hands-free unit runs its own echo cancelling on its mic; the phone's echo floor
 *    (tickets 04/08) is not in play on Bluetooth, where barge-in stays off (half-duplex).
 *  - The 700 ms Bluetooth reopen delay ([MicReopen]) stays: the SCO sink buffers too.
 *  - The link takes up to [SCO_SETTLE_MS] to come up; the first capture waits for it.
 *
 * Off in a phone call (Telecom owns the route), with no hands-free device (an A2DP-only speaker
 * has no mic), or when the setting is off: the phone mic, exactly as before.
 */
internal object BluetoothMic {
    const val SCO_SETTLE_MS = 2_000L

    /**
     * How long a hands-free capture may stay at digital silence before the phone's own mic takes the
     * conversation over ([fallBack]). Past [SCO_SETTLE_MS], which is the wait BEFORE capture, so a
     * link still finding its feet is not called deaf; short enough that he repeats one sentence
     * instead of a whole conversation. **GUESSED**, exactly as [MicSignal]'s own thresholds are and
     * for the same reason: no level from a real deaf hands-free capture has ever been measured. The
     * `mic_peak=` line this change adds is what closes that gap for the next one.
     */
    const val DEAF_MS = 2_500L

    /**
     * The phone's own mic takes this conversation over, because the hands-free device it is listening
     * through has delivered nothing audible at all.
     *
     * Kevin, 2026-11-24, in the car: with the head unit's Bluetooth connected, Alfred did not hear
     * him at all, and the phone's own log read `bluetooth mic routed=true` on every turn while
     * speech-to-text saw either no speech onset or `stt_voiced ms=0`. That readback says the platform
     * SELECTED the link, never that the link carries his voice. A head unit whose hands-free
     * microphone is absent, unplugged or muted routes perfectly and sends silence, and until this
     * check nothing noticed and there was no way back to the phone's mic inside the conversation.
     *
     * Only on the hands-free mic, and only once per conversation ([alreadyFellBack]): on the phone's
     * own mic a silent window is a quiet room or a man saying nothing, and there is nowhere further
     * to fall. [MicSignal.isDeafMic] is the level decision itself, and it reads levels, never content.
     */
    fun fallBack(
        onBluetoothMic: Boolean,
        alreadyFellBack: Boolean,
        elapsedMs: Long,
        peakSinceOpen: Int,
    ): Boolean =
        onBluetoothMic && !alreadyFellBack && MicSignal.isDeafMic(elapsedMs, peakSinceOpen, DEAF_MS)

    /** True when this conversation should listen through a Bluetooth hands-free device. */
    fun wanted(settingOn: Boolean, route: AudioRoute, inCall: Boolean, handsFreeAvailable: Boolean): Boolean =
        settingOn && route == AudioRoute.BLUETOOTH && !inCall && handsFreeAvailable

    /** One available communication device, reduced to what the choice reads. */
    data class Candidate(val id: Int, val isHandsFree: Boolean, val address: String?)

    /**
     * The hands-free device to use: the one whose address matches the Bluetooth device already
     * playing media (the car the user is listening through), else the first hands-free device.
     * Null when there is none.
     */
    fun pick(candidates: List<Candidate>, mediaSinkAddresses: Set<String>): Candidate? {
        val handsFree = candidates.filter { it.isHandsFree }
        val playing = handsFree.firstOrNull { it.address != null && it.address in mediaSinkAddresses }
        return playing ?: handsFree.firstOrNull()
    }
}
