package com.kevin.legion.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.assistant.cards.CarPart
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.ConversationCards
import com.kevin.legion.service.VoiceModalController
import com.kevin.legion.service.VoiceModalTarget
import com.kevin.legion.ui.assistant.ConversationCardView
import com.kevin.legion.ui.theme.soft.SoftTheme

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
        // The conversation's latest card rides at the top of the sheet (2026-11-04 phone test: a
        // sheet opened by "what's on today" covered the strip, so every car card after it was
        // drawn underneath). Not when the sheet already draws the same thing.
        val cards by ConversationCards.state.collectAsStateWithLifecycle()
        cards.latest?.takeIf { cardOverModal(current.target, it) }?.let { card ->
            SoftTheme {
                ConversationCardView(
                    card,
                    ConversationCards::dismiss,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        when (current.target) {
            // Today's due items. **REPOINTED one-today ticket 10 slice C, 2026-09-05: this used to
            // host `ui/notes/InboxScreen.kt` with a day filter set to today** (the same screen
            // tapping a dotted day on the old NOTES calendar filtered) - that screen is deleted.
            // [CalendarScreen] is the hands-reachable Notes/Dates surface now (the top-level
            // CALENDAR tab), and it already opens with TODAY selected by default
            // (`selectedDayStart`'s own `remember` default), so hosting it bare here shows exactly
            // "today's due items" with no day-filter parameter needed at all.
            VoiceModalTarget.AGENDA -> CalendarScreen()
            // The codes card (2026-11-03). Hands path: Fleet tab, Faults page, "Codes card" -
            // the same CodesCardHost, so voice and hands show one composable (ADR 0035).
            VoiceModalTarget.CODES -> current.vehicleId?.let { id ->
                com.kevin.legion.ui.fleet.CodesCardHost(id, onClose = { VoiceModalController.dismiss() })
            }
            // VoiceModalTarget.WHOLE_LIST (the unfiltered persistent-list stream) retired one-today
            // ticket 10 slice C alongside `ui/NotesScreen.kt` and `show_list_modal` - see
            // [VoiceModalTarget]'s own doc comment.
            // VoiceModalTarget.GROCERIES (the grocery trip screen) retired one-today ticket 10
            // slice B alongside `ui/notes/GroceryScreen.kt` and `show_groceries_modal` - see
            // [VoiceModalTarget]'s own doc comment.
        }
    }
}

/** Whether the conversation's [card] goes at the top of the sheet showing [target]: not a timeline
 * over the agenda, and not a codes-only car over the codes card, which draw the same rows. */
internal fun cardOverModal(target: VoiceModalTarget, card: ConversationCard): Boolean = when (target) {
    VoiceModalTarget.AGENDA -> card !is ConversationCard.Timeline
    VoiceModalTarget.CODES -> !(card is ConversationCard.Car && card.parts.keys == setOf(CarPart.CODES))
}
