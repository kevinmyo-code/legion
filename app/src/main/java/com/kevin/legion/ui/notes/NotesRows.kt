package com.kevin.legion.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Row furniture for the notes screens - the same "extracted shared row, thin screen wrapper" split
 * [com.kevin.legion.ui.common.CommonRows]/`ui/fleet/CarRows.kt` already use. Every row here takes
 * already-resolved view state ([InboxRowView] from `ui/notes/NotesResolvers.kt`) plus callbacks -
 * no [com.kevin.legion.notes.NotesController] reference.
 *
 * The list-of-lists, single-list and agenda rows that used to live here went with their screens
 * (2026-08-11: "dissolve the car list. merge everything into one list model").
 *
 * **CORRECTED one-today ticket 10 slice C, 2026-09-05: `ui/NotesScreen.kt` and
 * `ui/notes/InboxScreen.kt` are both DELETED** ("everything is a checklist now" - the persistent
 * list retired onto a `Todo` checklist, `notes/ReminderChecklistMigration.kt` carries any dateless
 * open reminder over). This file used to also hold `DashedHairline`, `InboxRow`, `MissedRow`,
 * `NotificationsBlockedBanner`, `DayFilterRow` and `ListsDoNotSyncNote` - every one of those had
 * exactly one caller, either screen above, and all six went with them (grep-confirmed before
 * deletion: nothing else in the tree ever called any of the six). [CalendarNotLinkedRow] and
 * [DAY_FILTER_WINDOW_MS] survive below because `ui/agenda/MonthCalendar.kt` and `ui/CalendarScreen.kt`
 * still call them respectively - this file stays for what the calendar home still shares, not for
 * the deleted screens' own furniture.
 *
 * A reminder's own edit affordance (time, repeat, place) now lives on `ui/CalendarScreen.kt`'s day
 * view directly, through the same [com.kevin.legion.ui.notes.ItemEditDialog]
 * `ui/notes/InboxScreen.kt` used to render - see that screen's own file doc comment for the full
 * account (one-today ticket 10 slice C's own precondition).
 */

/**
 * Ticket 13 point 7: said in words, with the grant action right where it is needed, rather than a
 * silent empty day/list. Shared between the (now-deleted) `ui/TodayScreen.kt` AGENDA pane and
 * `ui/agenda/MonthCalendar.kt`'s own day-selection notice - it lives here rather than in either
 * screen because this was already the shared "notes row furniture" file. Shown ABOVE whatever local
 * rows exist too, not only when the pane would otherwise be empty - a driver with local rows still
 * needs to be told a Google-owned appointment might be missing.
 *
 * **Migrated to [com.kevin.legion.ui.common.DeckButton]** (mission-control ticket 16's LOG build,
 * per that ticket's own binding: "migrate any control you touch to DeckControls" - HOME's build
 * deliberately left this bare `TextButton` alone because it is shared with LOG, and LOG is the
 * surface that finally touches it). Content and callback are unchanged; only the control itself
 * moved, same "control migrates, behaviour does not" shape [com.kevin.legion.ui.PurgeLedgerRow]'s
 * own migration doc comment describes.
 */
@Composable
fun CalendarNotLinkedRow(message: String, onGrant: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = LegionType.stamp, color = sem.faint, modifier = Modifier.weight(1f))
        DeckButton(text = "GRANT", onClick = onGrant)
    }
}

/** A local day-start in millis through the day after it, exclusive - the exact window
 * `ui/CalendarScreen.kt`'s own day view matches an [InboxRowView.instantMs] against (the same
 * window `ui/notes/InboxScreen.kt`'s own now-deleted day filter used to match against). */
const val DAY_FILTER_WINDOW_MS: Long = 24L * 60 * 60 * 1000
