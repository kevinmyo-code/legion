package com.kevin.midnightai.media

import android.content.Context

/**
 * Routes transport commands to whichever player [MusicSource] says is active:
 * [MixtapePlayer] for a driver-assembled tape, [SpotifyController] for a live BYO
 * App Remote connection, or [MusicController] (remote AVRCP control of the phone
 * over Bluetooth) otherwise. This is the one thing CruiseScreen, CompanionBadge,
 * and LiveToolbox call for play/pause/next/previous - it's a drop-in swap for the
 * old direct MusicController calls, same signatures.
 */
object MusicRouter {
    fun play(context: Context): Boolean = when (MusicSource.current.value) {
        Source.MIXTAPE -> { MixtapePlayer.resume(); true }
        Source.SPOTIFY -> spotify(context, SpotifyConnectLogic.Command.RESUME)
        Source.PHONE -> MusicController.play(context)
    }

    fun pause(context: Context): Boolean = when (MusicSource.current.value) {
        Source.MIXTAPE -> { MixtapePlayer.pause(); true }
        Source.SPOTIFY -> spotify(context, SpotifyConnectLogic.Command.PAUSE)
        Source.PHONE -> MusicController.pause(context)
    }

    fun next(context: Context): Boolean = when (MusicSource.current.value) {
        Source.MIXTAPE -> { MixtapePlayer.next(); true }
        Source.SPOTIFY -> spotify(context, SpotifyConnectLogic.Command.NEXT)
        Source.PHONE -> MusicController.next(context)
    }

    fun previous(context: Context): Boolean = when (MusicSource.current.value) {
        Source.MIXTAPE -> { MixtapePlayer.previous(); true }
        Source.SPOTIFY -> spotify(context, SpotifyConnectLogic.Command.PREVIOUS)
        Source.PHONE -> MusicController.previous(context)
    }

    /**
     * One head-unit Spotify transport command: App Remote while it is connected, else the Web
     * API ([SpotifyConnect], fire-and-forget like App Remote's own transport calls), so play,
     * pause and skip keep working when music was started through the Web API fallback.
     */
    private fun spotify(context: Context, cmd: SpotifyConnectLogic.Command): Boolean {
        if (SpotifyController.isConnected) {
            return when (cmd) {
                SpotifyConnectLogic.Command.RESUME -> SpotifyController.play()
                SpotifyConnectLogic.Command.PAUSE -> SpotifyController.pause()
                SpotifyConnectLogic.Command.NEXT -> SpotifyController.next()
                SpotifyConnectLogic.Command.PREVIOUS -> SpotifyController.previous()
            }
        }
        SpotifyConnect.command(context, cmd)
        return true
    }

    /** Pauses head-unit Spotify over whichever channel is up (App Remote, else the Web API). */
    fun pauseSpotify(context: Context) {
        spotify(context, SpotifyConnectLogic.Command.PAUSE)
    }

    /**
     * Gracefully switches TO head-unit Spotify (acct B): pauses whatever's
     * currently outgoing (the phone stream, or the mixtape player) first, so
     * the driver doesn't get two sources talking over each other, then
     * resumes Spotify if it had something queued (a no-op if not) and flips
     * [MusicSource]. Requires an existing App Remote connection - this is a
     * source switch, not a connect flow; if Spotify isn't connected, this
     * fails and the driver stays on their current source.
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
     * Gracefully switches back TO the driver's phone: pauses head-unit
     * Spotify (acct B) first, flips [MusicSource], then best-effort resumes
     * whatever the phone (acct A) was doing over Bluetooth. Always flips the
     * source even if the phone-side resume fails - the source itself isn't
     * conditional on Spotify or the phone being in any particular state.
     */
    fun switchToPhone(context: Context): Boolean {
        PlayRequests.supersede()
        // The Web API pause only when Spotify had the speakers: on an idle account it is a wasted call.
        if (SpotifyController.isConnected || MusicSource.current.value == Source.SPOTIFY) pauseSpotify(context)
        MusicSource.set(Source.PHONE)
        return MusicController.resumePhone(context)
    }
}
