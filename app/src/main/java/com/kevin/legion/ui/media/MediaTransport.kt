package com.kevin.legion.ui.media

import android.content.Context
import com.kevin.legion.service.LiveToolbox

/**
 * The hands path onto the SAME two-backend transport dispatch `control_music` uses
 * ([LiveToolbox.controlMusicTransport]). Kept as its own object rather than inlined in
 * [MediaScreen] so [MediaMiniBar] (Home's compact export) shares the identical dispatch rather
 * than a second copy of it.
 *
 * **No longer a second implementation (command-center ticket 08, drift-debt half).** This object
 * used to re-state [LiveToolbox]'s MusicController-then-SpotifyController ordering here, because
 * `controlMusicTransport` was `private` and Kotlin's `private` is file-scoped even within the
 * same module - there was no legal way to call it. That function is `internal` now, so [run]
 * calls it directly: one ordering, read by both the voice tool and this screen, rather than two
 * copies that could quietly disagree about which backend goes first. [LiveToolbox.musicFailureMessage]
 * is reused for the same reason it already was - the sentence a driver reads here for "I can't
 * reach the transport controls" is the literal same String the assistant would have spoken for
 * the identical failure.
 */
object MediaTransport {

    enum class Action { PLAY, PAUSE, NEXT, PREVIOUS }

    /** True on success. On failure, [failureMessage] names which of the two reasons it was. */
    suspend fun run(context: Context, action: Action): Boolean {
        val musicAction = when (action) {
            Action.PLAY -> LiveToolbox.MusicAction.PLAY
            Action.PAUSE -> LiveToolbox.MusicAction.PAUSE
            Action.NEXT -> LiveToolbox.MusicAction.NEXT
            Action.PREVIOUS -> LiveToolbox.MusicAction.PREVIOUS
        }
        return LiveToolbox.controlMusicTransport(context, musicAction).optBoolean("success")
    }

    /** Same wording `control_music` itself would speak for this exact failure - see this object's own doc. */
    fun failureMessage(context: Context): String = LiveToolbox.musicFailureMessage(context)
}
