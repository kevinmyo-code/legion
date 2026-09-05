package com.kevin.legion.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.service.VoiceModalController
import com.kevin.legion.service.VoiceModalTarget
import com.kevin.legion.ui.notes.InboxScreen

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
            // Today's due items, filtered exactly the way tapping a dotted day on the NOTES
            // calendar already filters `ui/NotesScreen.kt`'s InboxScreen (`selectedDayStart`,
            // wired from `DayEventsDialog`'s own SHOW IN LIST button) - same screen, same filter,
            // reached by hand today from NOTES -> tap a day -> SHOW IN LIST.
            VoiceModalTarget.AGENDA -> InboxScreen(
                dayFilterStartMs = dayStartEpoch(System.currentTimeMillis()),
                onClearDayFilter = { /* the sheet closes rather than clearing in place */ },
            )
            // The unfiltered notes stream - reached by hand today as the NOTES tab's ITEMS mode
            // (`ui/NotesScreen.kt`'s `LogMode.ITEMS` branch), the same composable with no day
            // filter applied.
            VoiceModalTarget.WHOLE_LIST -> InboxScreen()
            // VoiceModalTarget.GROCERIES (the grocery trip screen) retired one-today ticket 10
            // slice B alongside `ui/notes/GroceryScreen.kt` and `show_groceries_modal` - see
            // [VoiceModalTarget]'s own doc comment.
        }
    }
}
