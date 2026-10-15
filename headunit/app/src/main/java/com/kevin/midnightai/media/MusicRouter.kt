package com.kevin.midnightai.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Routes transport commands to whichever player [MusicSource] says is active:
 * [MixtapePlayer] for a driver-assembled tape, [SpotifyController] for a live BYO
 * App Remote connection, or [MusicController] (remote AVRCP control of the phone
 * over Bluetooth) otherwise. This is the one thing CruiseScreen, CompanionBadge,
 * and LiveToolbox call for play/pause/next/previous - it's a drop-in swap for the
 * old direct MusicController calls, same signatures.
 *
 * Head-unit Spotify transport is App Remote ONLY (Kevin, 2026-10-15). With the link down a
 * Spotify command fails and says so; it no longer falls through to a Web API player call, which
 * once started a song on the driver's phone instead of the head unit.
 */
object MusicRouter {
    fun play(context: Context): Boolean = when (MusicSource.current.value) {
        Source.MIXTAPE -> { MixtapePlayer.resume(); true }
        Source.SPOTIFY -> SpotifyController.play()
        Source.PHONE -> MusicController.play(context)
    }

    fun pause(context: Context): Boolean = when (MusicSource.current.value) {
        Source.MIXTAPE -> { MixtapePlayer.pause(); true }
        Source.SPOTIFY -> SpotifyController.pause()
        Source.PHONE -> MusicController.pause(context)
    }

    fun next(context: Context): Boolean = when (MusicSource.current.value) {
        Source.MIXTAPE -> { MixtapePlayer.next(); true }
        Source.SPOTIFY -> SpotifyController.next()
        Source.PHONE -> MusicController.next(context)
    }

    fun previous(context: Context): Boolean = when (MusicSource.current.value) {
        Source.MIXTAPE -> { MixtapePlayer.previous(); true }
        Source.SPOTIFY -> SpotifyController.previous()
        Source.PHONE -> MusicController.previous(context)
    }

    /**
     * The voice path's transport (F4, 2026-10-15): [message] is null on success, else a sentence
     * naming the source that failed and what did not happen. Head-unit Spotify awaits Spotify's own
     * answer ([SpotifyController.transport]); the phone and the mixtape are dispatch-only by nature
     * (AVRCP gives no result), so for them `ok` means "dispatched to a session that exists".
     * Runs the main-looper parts on Main itself; call from any dispatcher.
     */
    data class VoiceTransport(val ok: Boolean, val message: String?)

    suspend fun voiceTransport(context: Context, cmd: SpotifyController.TransportCmd): VoiceTransport =
        when (MusicSource.current.value) {
            Source.SPOTIFY -> {
                val outcome = SpotifyController.transport(context, cmd)
                val message = SpotifyController.transportMessage(outcome, cmd)
                VoiceTransport(message == null, message)
            }
            Source.MIXTAPE -> withContext(Dispatchers.Main) {
                when (cmd) {
                    SpotifyController.TransportCmd.PLAY -> MixtapePlayer.resume()
                    SpotifyController.TransportCmd.PAUSE -> MixtapePlayer.pause()
                    SpotifyController.TransportCmd.NEXT -> MixtapePlayer.next()
                    SpotifyController.TransportCmd.PREVIOUS -> MixtapePlayer.previous()
                }
                VoiceTransport(true, null)
            }
            Source.PHONE -> {
                val ok = withContext(Dispatchers.Main) {
                    when (cmd) {
                        SpotifyController.TransportCmd.PLAY -> MusicController.play(context)
                        SpotifyController.TransportCmd.PAUSE -> MusicController.pause(context)
                        SpotifyController.TransportCmd.NEXT -> MusicController.next(context)
                        SpotifyController.TransportCmd.PREVIOUS -> MusicController.previous(context)
                    }
                }
                VoiceTransport(ok, if (ok) null else phoneFailureMessage(cmd))
            }
        }

    /** Names the PHONE as the thing that could not be reached (F12: it used to blame the phone for Spotify). */
    internal fun phoneFailureMessage(cmd: SpotifyController.TransportCmd): String =
        "I couldn't reach your phone's music to ${cmd.verb} - make sure it's connected over Bluetooth " +
            "and has something playing."

    /** Pauses head-unit Spotify over App Remote; false when the link is down (nothing was paused). */
    fun pauseSpotify(context: Context): Boolean = SpotifyController.pause()

    /**
     * Gracefully switches TO head-unit Spotify (acct B): pauses whatever's
     * currently outgoing (the phone stream, or the mixtape player) first, so
     * the driver doesn't get two sources talking over each other, then
     * resumes Spotify if it had something queued (a no-op if not) and flips
     * [MusicSource]. Requires an existing App Remote connection - this is a
     * source switch, not a connect flow; if Spotify isn't connected, this
     * fails and the driver stays on their current source. Callers that want
     * the connect step too use [switchToSpotifyConnecting].
     */
    fun switchToSpotify(context: Context): Boolean {
        if (!SpotifyController.isConnected) return false
        PlayRequests.supersede()
        when (MusicSource.current.value) {
            Source.MIXTAPE -> MixtapePlayer.pause()
            else -> MusicController.pausePhone(context)
        }
        SpotifyController.play() // resume acct B if it had something queued; no-op otherwise
        MusicSource.set(Source.SPOTIFY)
        return true
    }

    /**
     * [switchToSpotify] after a bounded connect (F10, 2026-10-15): the voice `set_music_source` and
     * the RECONNECT tap are one capability and call the same thing (ADR 0035). Returns the gate
     * that failed, or null when the switch happened. Main-thread work is marshalled here.
     */
    suspend fun switchToSpotifyConnecting(context: Context): SpotifyController.Gate? {
        val gate = SpotifyController.connectGate(context)
        if (gate != SpotifyController.Gate.Connected) return gate
        val switched = withContext(Dispatchers.Main) { switchToSpotify(context) }
        return if (switched) null else SpotifyController.Gate.Failed(SpotifyController.lastFailure)
    }

    /**
     * Gracefully switches back TO the driver's phone: pauses head-unit
     * Spotify (acct B) first, flips [MusicSource], then best-effort resumes
     * whatever the phone (acct A) was doing over Bluetooth. Always flips the
     * source even if the phone-side resume fails - the source itself isn't
     * conditional on Spotify or the phone being in any particular state.
     */
    fun switchToPhone(context: Context): Boolean {
        PlayRequests.supersede()
        // Pause only when Spotify had the speakers: on an idle account it is a wasted call.
        if (SpotifyController.isConnected || MusicSource.current.value == Source.SPOTIFY) pauseSpotify(context)
        MusicSource.set(Source.PHONE)
        return MusicController.resumePhone(context)
    }
}
