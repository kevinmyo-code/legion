package com.kevin.legion.ui.clock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.clock.AlarmMath
import com.kevin.legion.clock.ClockAlarm
import com.kevin.legion.ui.theme.soft.SoftColors
import java.time.DayOfWeek

private const val SECONDS_PER_MINUTE = 60L
private const val MAX_DIGITS = 3

@Composable
internal fun StopwatchPane(sw: StopwatchRow, onVerb: (StopwatchVerb) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(sw.total, color = SoftColors.text, fontSize = 64.sp, fontWeight = FontWeight.Light,
            modifier = Modifier.padding(vertical = 32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                sw.running -> {
                    OutlinedButton(onClick = { onVerb(StopwatchVerb.LAP) }) { Text("Lap", color = SoftColors.text) }
                    PrimaryButton("Stop") { onVerb(StopwatchVerb.STOP) }
                }
                sw.started -> {
                    OutlinedButton(onClick = { onVerb(StopwatchVerb.RESET) }) { Text("Reset", color = SoftColors.text) }
                    PrimaryButton("Resume") { onVerb(StopwatchVerb.START) }
                }
                else -> PrimaryButton("Start") { onVerb(StopwatchVerb.START) }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            items(sw.laps) { Text(it, color = SoftColors.text2, fontSize = 15.sp, modifier = Modifier.padding(8.dp)) }
        }
    }
}

@Composable
internal fun BedtimePane(b: BedtimeRow, problem: ProblemRow?, actions: ClockActions) {
    var time by remember(b.hour, b.minute) { mutableStateOf(b.hour to b.minute) }
    var days by remember(b.days) { mutableStateOf(b.days) }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        problem?.let { ProblemCard(it, actions.onFix) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bedtime", color = SoftColors.text, fontSize = 18.sp)
                Text("Quiet and the feed block come on, and go off when you dismiss the next alarm. " +
                    "A reminder comes ${b.windDownMin} minutes before.", color = SoftColors.text2, fontSize = 13.sp)
            }
            Switch(checked = b.enabled, onCheckedChange = { actions.onSaveBedtime(it, time.first, time.second, days) })
        }
        TextButton(onClick = { picking = true }) {
            BigTime(time, size = 48)
        }
        DayChips(days) { days = it }
        PrimaryButton("Save schedule") { actions.onSaveBedtime(true, time.first, time.second, days) }
        val status = if (b.active) "Bedtime is on now." else "Bedtime is off now."
        Text(status, color = SoftColors.text2, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        OutlinedButton(onClick = { actions.onBedtimeNow(!b.active) }) {
            Text(if (b.active) "End bedtime" else "Start bedtime now", color = SoftColors.text)
        }
    }
    if (picking) {
        TimeDialog(time, onPick = { time = it; picking = false }, onDismiss = { picking = false })
    }
}

@Composable
internal fun DayChips(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DayOfWeek.entries.forEach { d ->
            val on = d in days
            val name = d.name.lowercase().replaceFirstChar(Char::uppercaseChar)
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(if (on) SoftColors.primaryContainer else SoftColors.cardHighest)
                    .toggleable(value = on, role = Role.Checkbox) { onChange(if (on) days - d else days + d) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                val ink = if (on) SoftColors.onPrimaryContainer else SoftColors.text2
                Text(d.name.take(1), color = ink, fontSize = 13.sp)
            }
        }
    }
}

/** New or edited alarm. Days empty = rings once. */
@Composable
internal fun AlarmEditor(alarm: ClockAlarm, onSave: (ClockAlarm) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var time by remember { mutableStateOf(alarm.hour to alarm.minute) }
    var days by remember { mutableStateOf(alarm.days) }
    var label by remember { mutableStateOf(alarm.label) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SoftColors.cardHigh,
        title = { Text(if (alarm.id == 0) "New alarm" else "Edit alarm", color = SoftColors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { picking = true }) {
                    BigTime(time, size = 44)
                }
                DayChips(days) { days = it }
                val repeats = if (days.isEmpty()) "Rings once" else "Repeats ${AlarmMath.daysLabel(days)}"
                Text(repeats, color = SoftColors.text2, fontSize = 13.sp)
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (h, m) = time
                onSave(alarm.copy(hour = h, minute = m, days = days, label = label.trim(), enabled = true))
            }) { Text("Save", color = SoftColors.primary) }
        },
        dismissButton = {
            if (alarm.id != 0) TextButton(onClick = onDelete) { Text("Delete", color = SoftColors.onAlert) }
            else TextButton(onClick = onDismiss) { Text("Cancel", color = SoftColors.text2) }
        },
    )
    if (picking) TimeDialog(time, onPick = { time = it; picking = false }, onDismiss = { picking = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(time: Pair<Int, Int>, onPick: (Pair<Int, Int>) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = time.first, initialMinute = time.second, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SoftColors.cardHigh,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour to state.minute) }) { Text("OK", color = SoftColors.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SoftColors.text2) } },
    )
}

/** A timer from minutes and seconds typed in, with an optional name. */
@Composable
internal fun TimerEditor(onStart: (Long, String) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf("5") }
    var seconds by remember { mutableStateOf("0") }
    var label by remember { mutableStateOf("") }
    val total = (minutes.toLongOrNull() ?: 0L) * SECONDS_PER_MINUTE + (seconds.toLongOrNull() ?: 0L)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SoftColors.cardHigh,
        title = { Text("New timer", color = SoftColors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(minutes, "Minutes", Modifier.weight(1f)) { minutes = it }
                    NumberField(seconds, "Seconds", Modifier.weight(1f)) { seconds = it }
                }
                OutlinedTextField(label, { label = it }, label = { Text("Name (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(total, label) }, enabled = total > 0) {
                Text("Start", color = SoftColors.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SoftColors.text2) } },
    )
}

@Composable
private fun NumberField(value: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(MAX_DIGITS)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = primaryColors(),
    ) { Text(text) }
}

@Composable
internal fun primaryColors() =
    ButtonDefaults.buttonColors(
        containerColor = SoftColors.primaryContainer,
        contentColor = SoftColors.onPrimaryContainer,
    )

@Composable
private fun BigTime(time: Pair<Int, Int>, size: Int) {
    val text = AlarmMath.hhmm(time.first, time.second)
    Text(text, color = SoftColors.text, fontSize = size.sp, fontWeight = FontWeight.Light)
}
