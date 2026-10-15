package com.kevin.midnightai.bluetooth

/**
 * Which phones carrying Bluetooth audio appeared or left between two refreshes. Pure, so the
 * diff behind the `phone_link_changed` breadcrumb is a JVM test.
 *
 * Exists for the 2026-10-15 car report (head-unit Spotify's App Remote link was gone after the
 * driver's phone disconnected, and ADB is unavailable on the unit): nothing in the code ties a
 * phone's Bluetooth loss to App Remote, so the next Crashlytics log should show the ORDER of
 * `phone_link_changed` and `spotify_app_remote_lost` instead of leaving it to guesswork.
 * Counts only, never addresses: a MAC is a hardware identifier and does not belong in a log.
 */
object PhoneLinkChange {

    data class Diff(val lost: Int, val gained: Int, val nowConnected: Int)

    /** Null when the set of audio-connected phones did not change. */
    fun diff(before: Set<String>, after: Set<String>): Diff? {
        if (before == after) return null
        return Diff(
            lost = (before - after).size,
            gained = (after - before).size,
            nowConnected = after.size,
        )
    }

    fun describe(d: Diff): String = "audio_lost=${d.lost} audio_gained=${d.gained} audio_now=${d.nowConnected}"
}
