package com.kevin.legion.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.service.VoiceModalController
import com.kevin.legion.service.VoiceModalTarget

/**
 * The collector for [VoiceModalController] - a voice-called modal made real (ADR 0040's corrected
 * model: "voice is a LAUNCHER, not a renderer"). Mounted in `LegionShell`'s outer Box beside
 * [GlanceCardOverlay], NOT inside it - see [VoiceModalController]'s own doc for why these are
 * siblings and not one mechanism.
 *
 * **Dismissed explicitly only.** [ModalBottomSheet] gives scrim-tap, swipe-down and back for free;
 * there is deliberately no timer here, unlike [GlanceCardOverlay]'s 7s auto-dismiss - the content
 * hosted is interactive (tick an item, check off groceries) and a countdown mid-interaction would
 * yank the sheet out from under the user's thumb.
 *
 * **Every branch below is content that ALSO exists on an ordinary tap path** (ADR 0035's hands
 * path, restated by ADR 0040 as "every modal has a hands route to it, and voice never creates a
 * destination that hands cannot reach") - see each branch's own comment for exactly where.
 * Nothing here composes new UI; it only re-hosts an existing composable inside a sheet, per this
 * ticket's brief.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceModalHost() {
    val payload by VoiceModalController.current.collectAsStateWithLifecycle()
    val current = payload ?: return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = { VoiceModalController.dismiss() },
        sheetState = sheetState,
    ) {
        when (current.target) {
            // Today's due items. **REPOINTED one-today ticket 10 slice C, 2026-09-05: this used to
            // host `ui/notes/InboxScreen.kt` with a day filter set to today** (the same screen
            // tapping a dotted day on the old NOTES calendar filtered) - that screen is deleted.
            // [CalendarScreen] is the hands-reachable Notes/Dates surface now (the top-level
            // CALENDAR tab), and it already opens with TODAY selected by default
            // (`selectedDayStart`'s own `remember` default), so hosting it bare here shows exactly
            // "today's due items" with no day-filter parameter needed at all.
            VoiceModalTarget.AGENDA -> CalendarScreen()
            // VoiceModalTarget.WHOLE_LIST (the unfiltered persistent-list stream) retired one-today
            // ticket 10 slice C alongside `ui/NotesScreen.kt` and `show_list_modal` - see
            // [VoiceModalTarget]'s own doc comment.
            // VoiceModalTarget.GROCERIES (the grocery trip screen) retired one-today ticket 10
            // slice B alongside `ui/notes/GroceryScreen.kt` and `show_groceries_modal` - see
            // [VoiceModalTarget]'s own doc comment.
        }
    }
}
