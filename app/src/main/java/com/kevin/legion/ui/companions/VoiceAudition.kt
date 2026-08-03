package com.kevin.legion.ui.companions

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kevin.legion.ai.GeminiVoice

/**
 * Backs the audition ("play a sample") control on [CompanionEditorDialog]'s
 * voice picker. Holds at most one live [MediaPlayer] - "exactly one clip at a
 * time" (task requirement) falls straight out of [toggle] always tearing down
 * whatever was playing before it starts anything new, rather than needing
 * separate per-row bookkeeping.
 *
 * Deliberately platform `MediaPlayer`, not ExoPlayer/Media3 - CLAUDE.md drops
 * Media3 as a dependency, and a several-second mono WAV clip is exactly the
 * "short clip, no seek bar, no queue" case the platform class was built for.
 *
 * Not itself a state holder in the `compose-state-holder-ui-split` sense (no
 * repository/DB access) - it is UI-owned playback state, same shape as e.g. a
 * remembered `ScrollState`. [rememberVoiceAuditionPlayer] is what ties its
 * lifecycle to a composition.
 */
class VoiceAuditionPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private val _playing = mutableStateOf<String?>(null)

    /** [GeminiVoice.name] currently auditioning, or null when silent. Rows read this to decide their own play/stop glyph. */
    val playing: State<String?> get() = _playing

    /**
     * Resolves [voice]'s bundled clip WITHOUT starting playback, so a row can
     * disable its control up front. `getIdentifier` returns 0 for a name that
     * doesn't resolve to a resource rather than throwing - a future voice
     * added to `CURATED_VOICES` without a regenerated sample must disable its
     * row, not crash the dialog (task requirement).
     */
    fun hasClip(voice: GeminiVoice): Boolean =
        context.resources.getIdentifier(voice.sampleRawName, "raw", context.packageName) != 0

    /**
     * Tap behaviour for one voice's row: tapping the voice that is currently
     * playing stops it (tap-to-toggle, per task); tapping any other voice
     * stops whatever was playing first, so playback never overlaps, then
     * starts this one if it has a bundled clip.
     */
    fun toggle(voice: GeminiVoice) {
        val alreadyPlaying = _playing.value == voice.name
        stop()
        if (alreadyPlaying) return
        val resId = context.resources.getIdentifier(voice.sampleRawName, "raw", context.packageName)
        if (resId == 0) return // no bundled clip for this voice - degrade silently, never crash
        // MediaPlayer.create() can itself return null on a malformed/unreadable
        // resource; treated the same as "no clip" rather than propagating a crash.
        val created = MediaPlayer.create(context, resId) ?: return
        created.setOnCompletionListener { stop() } // clip is a single sentence - runs to completion, no loop, no pause/seek UI
        player = created
        _playing.value = voice.name
        created.start()
    }

    /** Stops and releases the current player, if any. Idempotent - safe to call when nothing is playing, and this is the sole release path so it's also what [rememberVoiceAuditionPlayer]'s dispose calls. */
    fun stop() {
        player?.release()
        player = null
        _playing.value = null
    }
}

/**
 * [VoiceAuditionPlayer] scoped to the caller's composition. The
 * [DisposableEffect] is the release path the platform `MediaPlayer`/ExoPlayer
 * docs and `.claude/skills/compose-side-effects` both call out: a player left
 * unreleased holds a codec and audio-focus handle past the composable's
 * lifetime. Keyed on the player instance itself (which is in turn `remember`ed
 * against [context]) - there's exactly one player for the dialog's whole
 * lifetime, so this never restarts mid-dialog and always tears down on exit,
 * covering both "user picked a voice and saved" and "user dismissed
 * mid-playback" (task requirement) since [CompanionsScreen] removes
 * [CompanionEditorDialog] from composition on either path.
 */
@Composable
fun rememberVoiceAuditionPlayer(): VoiceAuditionPlayer {
    val context = LocalContext.current
    val player = remember(context) { VoiceAuditionPlayer(context) }
    DisposableEffect(player) {
        onDispose { player.stop() }
    }
    return player
}
