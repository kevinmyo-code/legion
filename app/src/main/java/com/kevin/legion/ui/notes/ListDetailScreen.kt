package com.kevin.legion.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.notes.NotesController
import com.kevin.legion.notes.RepeatEnd
import com.kevin.legion.notes.RepeatKind
import com.kevin.legion.notes.RepeatRule
import com.kevin.legion.notes.ruleFromItem
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * The item editor dialog - all that survives of the old single-list screen (2026-08-11: "dissolve
 * the car list. merge everything into one list model").
 *
 * The screen this file used to be (a list's own items, plus rename/archive/delete of the list
 * itself) went with the multi-list structure; `ui/notes/InboxScreen.kt` is the one screen now. The
 * EDITOR stayed, whole, because everything it can set is still real on an item: an exact date and
 * time, a repeat rule, a place trigger, an exact-alarm request. [InboxScreen]'s add row covers the
 * common case - text plus a due date - and opens this for the rest.
 *
 * The file name is left alone deliberately: renaming it would detach the git history of the editor
 * from the screen it grew inside, for no gain.
 */
/** Shared with [InboxScreen]'s own add-row date field, so the format a driver types when APPENDING
 * an item is byte-for-byte the one they type when editing it later - two different accepted date
 * formats in one domain is a typo the app reads as "no date". */
internal val EDIT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
/** `internal`, not `private`, as of ticket 22 - `ui/notes/InboxScreen.kt`'s calendar-event edit
 * dialog shares this exact format too, same reason [EDIT_DATE_FORMAT] is already shared: two
 * different accepted time formats in one domain is a typo the app reads as "no time". */
internal val EDIT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The item editor. Text fields for date/time rather than `DatePicker`/`TimePicker` dialogs - a
 * deliberate simple-first call (CLAUDE.md standing preference, and the map's own "simple first"):
 * this phase's job is making every verb the spec names REACHABLE, and a typed "2026-08-12" / "14:30"
 * costs far less to build and verify than wiring two more Material3 dialog hosts. Parse failures
 * disable Save rather than crashing or silently discarding - see `triggerValid`/`repeatValid` below.
 *
 * Time triggers are converted through [ZoneId.systemDefault] (device zone), matching
 * `ui/notes/NotesResolvers.kt`'s formatting convention - see that file's doc comment for why this is
 * the UTC-vs-device-zone line for a REAL future instant, as opposed to a date printed on a document.
 */
@Composable
internal fun ItemEditDialog(item: ListItem, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current

    var text by remember(item.id) { mutableStateOf(item.text) }
    var triggerKind by remember(item.id) {
        mutableStateOf(
            when {
                item.triggerPlaceLabel != null -> TriggerKind.PLACE
                item.startsAt != null -> TriggerKind.TIME
                else -> TriggerKind.NONE
            },
        )
    }
    var placeLabel by remember(item.id) { mutableStateOf(item.triggerPlaceLabel.orEmpty()) }
    var allDay by remember(item.id) { mutableStateOf(item.allDay) }
    var exact by remember(item.id) { mutableStateOf(item.exact) }
    var dateText by remember(item.id) {
        mutableStateOf(
            item.startsAt?.let { epochToLocalDate(it) }?.format(EDIT_DATE_FORMAT) ?: "",
        )
    }
    var timeText by remember(item.id) {
        mutableStateOf(
            if (item.startsAt != null && !item.allDay) epochToLocalTime(item.startsAt).format(EDIT_TIME_FORMAT) else "",
        )
    }
    var repeatKind by remember(item.id) { mutableStateOf(ruleFromItem(item)?.let { repeatKindOf(it) } ?: RepeatKind.DAILY) }
    var repeatEnabled by remember(item.id) { mutableStateOf(item.repeatKind != null) }
    var everyText by remember(item.id) { mutableStateOf((ruleFromItem(item) as? RepeatRule.Daily)?.every?.toString() ?: item.repeatEvery?.toString() ?: "1") }
    var weeklyDays by remember(item.id) { mutableStateOf((ruleFromItem(item) as? RepeatRule.Weekly)?.days ?: emptySet()) }
    var monthDayText by remember(item.id) { mutableStateOf(item.repeatDay?.toString() ?: "1") }
    var yearMonthText by remember(item.id) { mutableStateOf(item.repeatMonth?.toString() ?: "1") }

    val parsedDate = runCatching { LocalDate.parse(dateText, EDIT_DATE_FORMAT) }.getOrNull()
    val parsedTime = if (allDay) LocalTime.MIDNIGHT else runCatching { LocalTime.parse(timeText, EDIT_TIME_FORMAT) }.getOrNull()
    val triggerValid = when (triggerKind) {
        TriggerKind.NONE -> true
        TriggerKind.PLACE -> placeLabel.isNotBlank()
        TriggerKind.TIME -> parsedDate != null && parsedTime != null
    }
    val every = everyText.toIntOrNull()
    val monthDay = monthDayText.toIntOrNull()
    val yearMonth = yearMonthText.toIntOrNull()
    val repeatValid = !repeatEnabled || triggerKind != TriggerKind.TIME || when (repeatKind) {
        RepeatKind.DAILY -> every != null && every >= 1
        RepeatKind.WEEKLY -> every != null && every >= 1 && weeklyDays.isNotEmpty()
        RepeatKind.MONTHLY_ON_DATE -> every != null && every >= 1 && monthDay in 1..31
        RepeatKind.YEARLY -> monthDay in 1..31 && yearMonth in 1..12
    }
    val canSave = text.isNotBlank() && triggerValid && repeatValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit item") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") })
                Spacer12()

                Text("Trigger", style = MaterialTheme.typography.titleSmall)
                TriggerRadioRow("None", triggerKind == TriggerKind.NONE) { triggerKind = TriggerKind.NONE }
                TriggerRadioRow("Time", triggerKind == TriggerKind.TIME) { triggerKind = TriggerKind.TIME }
                TriggerRadioRow("Place", triggerKind == TriggerKind.PLACE) { triggerKind = TriggerKind.PLACE }

                if (triggerKind == TriggerKind.PLACE) {
                    OutlinedTextField(value = placeLabel, onValueChange = { placeLabel = it }, label = { Text("Place label") })
                }

                if (triggerKind == TriggerKind.TIME) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allDay, onCheckedChange = { allDay = it })
                        Text("All day")
                    }
                    OutlinedTextField(value = dateText, onValueChange = { dateText = it }, label = { Text("Date (YYYY-MM-DD)") })
                    if (!allDay) {
                        OutlinedTextField(value = timeText, onValueChange = { timeText = it }, label = { Text("Time (24h HH:MM)") })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exact, onCheckedChange = { exact = it })
                        Text("Exact alarm (may be refused by the system - see the item's own row if so)")
                    }

                    Spacer12()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = repeatEnabled, onCheckedChange = { repeatEnabled = it })
                        Text("Repeats (no checkbox once saved - ticket 04)")
                    }
                    if (repeatEnabled) {
                        RepeatKindRow(repeatKind) { repeatKind = it }
                        when (repeatKind) {
                            RepeatKind.DAILY -> OutlinedTextField(value = everyText, onValueChange = { everyText = it }, label = { Text("Every N days") })
                            RepeatKind.WEEKLY -> {
                                OutlinedTextField(value = everyText, onValueChange = { everyText = it }, label = { Text("Every N weeks") })
                                WeekdayChooser(weeklyDays) { weeklyDays = it }
                            }
                            RepeatKind.MONTHLY_ON_DATE -> {
                                OutlinedTextField(value = everyText, onValueChange = { everyText = it }, label = { Text("Every N months") })
                                OutlinedTextField(value = monthDayText, onValueChange = { monthDayText = it }, label = { Text("Day of month") })
                            }
                            RepeatKind.YEARLY -> {
                                OutlinedTextField(value = yearMonthText, onValueChange = { yearMonthText = it }, label = { Text("Month (1-12)") })
                                OutlinedTextField(value = monthDayText, onValueChange = { monthDayText = it }, label = { Text("Day") })
                            }
                        }
                    }
                }
                // ADVISORY (ticket 13 re-home): form validation errors, not failed gates.
                if (!triggerValid) {
                    Text("Date/time couldn't be read.", style = LegionType.stamp, color = sem.estimated)
                }
                if (!repeatValid) {
                    Text("Repeat fields aren't complete yet.", style = LegionType.stamp, color = sem.estimated)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    scope.launch {
                        // Senior review, 2026-08-24 (should-fix 3): NotesController.setTime/
                        // setPlaceTrigger/setExact/setRepeat now return null on a failed engine
                        // write rather than silently letting a stale item look current - each
                        // `?: current` below keeps the last known-GOOD state rather than crashing
                        // on a null, since this hands-path dialog has no spoken layer to word the
                        // failure through (unlike LiveToolbox.scheduleItem's own fix).
                        var current = item
                        if (text != item.text) NotesController.renameItem(context, current, text)
                        current = when (triggerKind) {
                            TriggerKind.NONE -> { NotesController.clearTime(context, current); current.copy(startsAt = null, triggerPlaceLabel = null) }
                            TriggerKind.PLACE -> NotesController.setPlaceTrigger(context, current, placeLabel.trim()) ?: current
                            TriggerKind.TIME -> {
                                val date = parsedDate!!
                                val time = parsedTime!!
                                val startsAt = LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                val updated = NotesController.setTime(context, current, startsAt, null, allDay) ?: current
                                NotesController.setExact(context, updated, exact) ?: updated
                            }
                        }
                        if (triggerKind == TriggerKind.TIME && repeatEnabled) {
                            val rule = when (repeatKind) {
                                RepeatKind.DAILY -> RepeatRule.Daily(every ?: 1)
                                RepeatKind.WEEKLY -> RepeatRule.Weekly(every ?: 1, weeklyDays)
                                RepeatKind.MONTHLY_ON_DATE -> RepeatRule.MonthlyOnDate(every ?: 1, monthDay ?: 1)
                                RepeatKind.YEARLY -> RepeatRule.Yearly(yearMonth ?: 1, monthDay ?: 1)
                            }
                            NotesController.setRepeat(context, current, rule, RepeatEnd.Never)
                        } else if (item.repeatKind != null) {
                            NotesController.setRepeat(context, current, null, RepeatEnd.Never)
                        }
                        onSaved()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class TriggerKind { NONE, TIME, PLACE }

private fun repeatKindOf(rule: RepeatRule): RepeatKind = when (rule) {
    is RepeatRule.Daily -> RepeatKind.DAILY
    is RepeatRule.Weekly -> RepeatKind.WEEKLY
    is RepeatRule.MonthlyOnDate -> RepeatKind.MONTHLY_ON_DATE
    is RepeatRule.Yearly -> RepeatKind.YEARLY
}

private fun epochToLocalDate(epochMs: Long): LocalDate =
    java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

private fun epochToLocalTime(epochMs: Long): LocalTime =
    java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()

@Composable
private fun Spacer12() {
    Column(Modifier.padding(top = 12.dp)) {}
}

@Composable
private fun TriggerRadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

@Composable
private fun RepeatKindRow(selected: RepeatKind, onSelect: (RepeatKind) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            RepeatKind.DAILY to "Daily",
            RepeatKind.WEEKLY to "Weekly",
            RepeatKind.MONTHLY_ON_DATE to "Monthly",
            RepeatKind.YEARLY to "Yearly",
        ).forEach { (kind, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == kind, onClick = { onSelect(kind) })
                Text(label, style = LegionType.stamp)
            }
        }
    }
}

@Composable
private fun WeekdayChooser(selected: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DayOfWeek.entries.forEach { day ->
            val isSelected = day in selected
            Text(
                day.name.take(2),
                style = LegionType.stamp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else LocalLegionSemantics.current.faint,
                modifier = Modifier
                    .padding(2.dp)
                    .background(if (isSelected) LocalLegionSemantics.current.rule else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(4.dp)
                    .clickable { onChange(if (isSelected) selected - day else selected + day) },
            )
        }
    }
}
