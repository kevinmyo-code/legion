package com.kevin.legion.ui.media

import android.content.Context
import com.kevin.legion.media.MusicController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.media.SpotifyController
import com.kevin.legion.service.LiveToolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The hands path onto the SAME two transport backends `control_music` dispatches to
 * ([LiveToolbox]'s `controlMusicTransport` - traced, not copied, see below). Kept as its own
 * object rather than inlined in [MediaScreen] so [MediaMiniBar] (Home's compact export) shares
 * the identical dispatch rather than a second copy of the ordering.
 *
 * **Ordering mirrors `controlMusicTransport` exactly, on purpose**: [MusicController] (Android's
 * MediaSession framework) first, because it drives whatever is ACTUALLY playing - phone AVRCP,
 * Spotify, any session-publishing app - gated on [NowPlayingController.hasAccess] (the
 * notification-listener grant); [SpotifyController] second, gated on [SpotifyController.isConnected],
 * because App Remote needs no such grant but only ever drives Spotify itself. This cannot literally
 * CALL `controlMusicTransport` - it is `private` to `LiveToolbox.kt`, file-scoped in Kotlin even
 * within the same module - so the ordering is re-stated here as a second call site over the SAME
 * [MusicController]/[SpotifyController] functions, never a second implementation of either
 * controller (ADR 0035: "not a second implementation - both paths call the same controller").
 * [LiveToolbox.musicFailureMessage] IS reused directly for the failure line (it is `internal`,
 * same module), so the sentence a driver reads here for "I can't reach the transport controls"
 * is the literal same String the assistant would have spoken for the identical failure, not a
 * second copy that can drift out of sync with it.
 *
 * The media framework needs a thread with a Looper; `controlMusicTransport` marshals to
 * [Dispatchers.Main] for exactly this reason. A Compose click handler already runs on the main
 * thread, so the [withContext] hop here is a no-op in practice - kept anyway so this function's
 * correctness does not depend on which thread happens to call it.
 */
object MediaTransport {

    enum class Action { PLAY, PAUSE, NEXT, PREVIOUS }

    /** True on success. On failure, [failureMessage] names which of the two reasons it was. */
    suspend fun run(context: Context, action: Action): Boolean {
        if (NowPlayingController.hasAccess(context)) {
            val ok = withContext(Dispatchers.Main) {
                when (action) {
                    Action.PLAY -> MusicController.play(context)
                    Action.PAUSE -> MusicController.pause(context)
                    Action.NEXT -> MusicController.next(context)
                    Action.PREVIOUS -> MusicController.previous(context)
                }
            }
            if (ok) return true
        }
        if (SpotifyController.isConnected) {
            val ok = when (action) {
                Action.PLAY -> SpotifyController.play()
                Action.PAUSE -> SpotifyController.pause()
                Action.NEXT -> SpotifyController.next()
                Action.PREVIOUS -> SpotifyController.previous()
            }
            if (ok) return true
        }
        return false
    }

    /** Same wording `control_music` itself would speak for this exact failure - see this object's own doc. */
    fun failureMessage(context: Context): String = LiveToolbox.musicFailureMessage(context)
}
