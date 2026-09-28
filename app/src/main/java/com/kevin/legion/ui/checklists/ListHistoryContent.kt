package com.kevin.legion.ui.checklists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.checklists.historyGroupedByDayDescending
import com.kevin.legion.checklists.measureTargetResult
import com.kevin.legion.checklists.measureTargetResultLabel
import com.kevin.legion.checklists.measureValueDisplay
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** How far back this screen looks - unchanged from the pre-04 screen's own constant. */
private const val HISTORY_WINDOW_DAYS = 30
private val HISTORY_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
private fun historyDayLabel(day: Int): String = LocalDate.ofEpochDay(day.toLong()).format(HISTORY_DAY_FORMAT)

/**
 * "Look back and see what i did" - unchanged behaviour from the pre-04 screen (this ticket's own
 * instruction: "the history mode keeps its behaviour and moves onto the soft theme"), restyled
 * onto [SoftColors]/soft typography. Shown, never scored: no streak, no percentage, only which
 * lines were ticked on which day.
 */
@Composable
fun ListHistoryContent(state: ListHistoryState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                MsIcon(res = R.drawable.ms_arrow_back, contentDescription = "Back", tint = SoftColors.text)
            }
            Text(
                "${state.checklistName} - history",
                style = MaterialTheme.typography.titleLarge,
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        if (state.loading) return@Column

        if (state.lines.isEmpty()) {
            Text(
                "No ticks in the last $HISTORY_WINDOW_DAYS days - either this list is new, or nothing on it has " +
                    "been ticked in that window.",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text2,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(historyGroupedByDayDescending(state.lines), key = { it.first }) { (day, dayLines) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(historyDayLabel(day), style = MaterialTheme.typography.labelLarge, color = SoftColors.text2)
                    dayLines.forEach { line -> HistoryLineRow(line) }
                }
            }
        }
    }
}

@Composable
private fun HistoryLineRow(line: ChecklistController.ChecklistHistoryLine) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(SoftColors.tickedBox),
            contentAlignment = Alignment.Center,
        ) {
            MsIcon(res = R.drawable.ms_check, contentDescription = null, tint = SoftColors.ground, size = 12.dp)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(line.item.text, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text)
            val value = line.value
            if (value != null) {
                val resultLabel = measureTargetResult(line.item, value)?.let { " - ${measureTargetResultLabel(it)}" } ?: ""
                Text(measureValueDisplay(line.item, value) + resultLabel, style = MaterialTheme.typography.labelSmall, color = SoftColors.text2)
            }
        }
    }
}
