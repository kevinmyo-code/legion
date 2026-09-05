package com.kevin.legion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which PRE-MADE surface a voice call brings forward. A closed enum, not a payload of content:
 * ADR 0040's corrected model is "voice is a LAUNCHER, not a renderer" - the modal is ordinary
 * hand-built UI that exists whether or not anyone speaks, and the voice tool only names WHICH one
 * to bring up. Adding a target here means wiring one more pre-made composable into
 * [com.kevin.legion.ui.VoiceModalHost]'s `when`, never inventing new rendering.
 *
 * **`GROCERIES` retired (one-today ticket 10 slice B, 2026-09-05): "everything is a checklist
 * now".** The grocery trip screen (`ui/notes/GroceryScreen.kt`) and `show_groceries_modal` are gone
 * with it - a shopping list is a checklist named "Groceries" now, reached through the checklists
 * screen, not a voice-called modal.
 *
 * **`WHOLE_LIST` retired (one-today ticket 10 slice C, 2026-09-05), same shape, same reason.** It
 * used to be deliberately not called `NAMED_LIST` - `show_list_modal` took no parameters and showed
 * the ONE persistent list `manage_item`/`read_list` used to operate on, so a name promising a
 * choice of list would have been a name promising something the code did not do (the exact shape
 * that bit this codebase twice before: `EventReplicaDao.upsert`'s defeated guarantee,
 * `GeneratedFormScreen`'s "PHOTO ON FILE"). The persistent list itself is gone now
 * (`ui/NotesScreen.kt`) and `show_list_modal`'s own tool declaration retired with it - see
 * `service/LiveToolbox.kt`'s own retirement comment on that tool.
 */
enum class VoiceModalTarget { AGENDA }

/**
 * [target] plus [shownAt], mirroring [GlanceCardPayload]'s own shape: a bare enum value would
 * compare equal to itself on a REPEAT call for the same target (e.g. "show my agenda" twice in a
 * row), and [kotlinx.coroutines.flow.MutableStateFlow] only emits on a value that is
 * `!=` its predecessor - a repeat call with no observable change would then silently fail to
 * re-trigger anything a collector keys off. [shownAt] costs nothing to carry and makes every
 * `show()` call structurally distinct.
 */
data class VoiceModalPayload(
    val target: VoiceModalTarget,
    val shownAt: Long = System.currentTimeMillis(),
)

/**
 * Ephemeral voice-called-modal state - **a sibling to [GlanceCardController], not an extension of
 * it, and the difference is the whole point.** A glance card is read-at-a-glance, non-interactive,
 * and auto-dismisses on a timer because the driver may be looking at the road; that policy exists
 * because the content is a passive answer, not a place to act. A voice-called modal is the
 * opposite: it hosts an existing INTERACTIVE surface (check off a list item, tick a grocery,
 * dismiss a dialog) and must stay up until the user explicitly closes it - a 7s countdown mid-tick
 * would yank the sheet out from under a driver's thumb. One auto-dismiss policy cannot serve both
 * shapes, so this is its own controller with its own (nonexistent) dismiss timer, not a second
 * mode bolted onto [GlanceCardController].
 *
 * Same posture as [GlanceCardController] otherwise: pure state, no owned coroutine scope, not
 * persisted - no Room table. This is display-only, driver-requested state for the current moment.
 *
 * `show()`/`dismiss()` are the only mutators. [com.kevin.legion.ui.VoiceModalHost] is the sole
 * collector, mounted in `LegionShell`'s outer Box beside [GlanceCardOverlay] so a modal call can
 * paint over whatever destination is already showing.
 */
object VoiceModalController {
    private val _current = MutableStateFlow<VoiceModalPayload?>(null)
    val current: StateFlow<VoiceModalPayload?> = _current.asStateFlow()

    /** Shows [target]. A modal already showing is replaced immediately (newest wins). */
    fun show(target: VoiceModalTarget) {
        _current.value = VoiceModalPayload(target)
    }

    /** Explicit dismiss only - scrim tap, swipe, back. There is no auto-dismiss timer; see class doc. */
    fun dismiss() {
        _current.value = null
    }
}
