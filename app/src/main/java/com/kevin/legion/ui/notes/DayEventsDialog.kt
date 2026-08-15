package com.kevin.legion.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.AgendaEntry
import com.kevin.legion.ui.AgendaSource
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Quant-viz ticket 16: tapping a dotted month-grid day pops this up rather than requiring a scroll
 * down to the (below-the-fold) filtered list to find out what the dot meant - Kevin, 2026-08-14:
 * "tapping the date with event on it should pop up a UI showing things due on that date."
 *
 * [entries] is `ui/NotesScreen.kt`'s own `monthEntries` state run through [entriesForDay] for
 * [dayStart] - the SAME `merged` list [buildWeekAheadDayCounts] already bucketed to draw the dots
 * on the cell that was tapped, never a second fetch. That is what makes the row count in this
 * dialog and the dot count on the cell two renderings of one list rather than two windows that
 * could drift apart (the ticket's own stated failure mode, and the false-empty bug a previous QA
 * pass caught).
 *
 * `AlertDialog`, not a bottom sheet - every existing modal in this app is an `AlertDialog`
 * ([com.kevin.legion.ui.fleet.CarRows.AddCarDialog], `CompanionRows.CompanionEditorDialog`,
 * [com.kevin.legion.ui.goals.GoalsPanel]'s `GoalEditDialog`), and matching the house pattern beats
 * introducing a second modal idiom for one screen.
 */
@Composable
fun DayEventsDialog(
    dayStart: Long,
    entries: List<AgendaEntry>,
    onShowInList: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val title = Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate().format(DAY_EVENTS_TITLE_FORMAT)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (entries.isEmpty()) {
                // An honest empty - can only ever appear when the same list that drew zero dots on
                // this cell is genuinely empty, because both read from the one `merged` list.
                Text("Nothing on this day.", style = LegionType.stamp, color = LocalLegionSemantics.current.faint)
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(entries, key = { "${it.label}-${it.timeMs}-${it.source}" }) { entry ->
                        DayEventRow(entry)
                    }
                }
            }
        },
        confirmButton = {
            // Keeps ticket 14's day-filter feature (QA-verified, not deleted here) as the way to
            // actually act on these rows - the popup is a look, SHOW IN LIST is the doing.
            TextButton(onClick = onShowInList) { Text("SHOW IN LIST") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE") }
        },
    )
}

/**
 * One row inside [DayEventsDialog]: left, the clock time (or `ALL DAY`) - the same [clockTime]
 * formatter `ui/TodayScreen.kt`'s own `AgendaRow` uses for the identical value; right, the entry's
 * label, wrapping rather than truncating (this dialog IS the detail view for a title - a title
 * clipped here has nowhere else to be read). A [AgendaSource.GOOGLE] row carries the same `CAL`
 * `DeckTag` `AgendaRow` puts on a Google row, in words, never colour alone (CLAUDE.md §4 rule 5).
 */
@Composable
private fun DayEventRow(entry: AgendaEntry) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (entry.allDay) "ALL DAY" else clockTime(entry.timeMs),
            style = LegionType.stamp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(64.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.label,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (entry.source == AgendaSource.GOOGLE) {
                    DeckTag("CAL", DeckTagStyle.OUTLINE_MUTED, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** "Friday 7 August" - the ticket's own example wording for the dialog title. */
private val DAY_EVENTS_TITLE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")
