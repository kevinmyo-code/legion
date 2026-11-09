package com.kevin.legion.ui.clock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.legion.clock.ClockAlarm
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/** Alfred's clock (ADR 0079): four tabs over one controller. Stateless; [ClockActivity] feeds it. */
@Composable
fun ClockScreen(ui: ClockUi, actions: ClockActions) {
    var editing by remember { mutableStateOf<ClockAlarm?>(null) }
    var newTimer by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(SoftColors.ground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            ClockTopBar(actions.onClose)
            TabRow(selectedTabIndex = ui.tab, containerColor = SoftColors.ground, contentColor = SoftColors.primary) {
                TABS.forEachIndexed { i, title ->
                    // The content form of Tab carries no 16 dp side padding, so "Stopwatch" fits a quarter.
                    Tab(selected = ui.tab == i, onClick = { actions.onTab(i) }) {
                        Text(title, maxLines = 1, fontSize = 14.sp, modifier = Modifier.padding(vertical = 14.dp))
                    }
                }
            }
            ui.notice?.let { NoticeBar(it, actions.onDismissNotice) }
            ui.problems.forEach { ProblemCard(it, actions.onFix) }
            Box(Modifier.weight(1f)) {
                when (ui.tab) {
                    ClockActivity.TAB_ALARMS -> AlarmsPane(ui.alarms, actions, onEdit = { editing = it })
                    ClockActivity.TAB_TIMERS -> TimersPane(ui.timers, actions)
                    ClockActivity.TAB_STOPWATCH -> StopwatchPane(ui.stopwatch, actions.onStopwatch)
                    else -> BedtimePane(ui.bedtime, ui.bedtimeProblem, actions)
                }
            }
        }
        val fab: (() -> Unit)? = when (ui.tab) {
            ClockActivity.TAB_ALARMS -> { { editing = ClockAlarm(id = 0, hour = 7, minute = 0) } }
            ClockActivity.TAB_TIMERS -> { { newTimer = true } }
            else -> null
        }
        if (fab != null) {
            FloatingActionButton(
                onClick = fab,
                containerColor = SoftColors.primaryContainer,
                contentColor = SoftColors.onPrimaryContainer,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            ) {
                val what = if (ui.tab == ClockActivity.TAB_ALARMS) "New alarm" else "New timer"
                MsIcon(R.drawable.ms_add, what, SoftColors.onPrimaryContainer)
            }
        }
    }
    editing?.let { a ->
        AlarmEditor(a, onSave = { actions.onSaveAlarm(it); editing = null }, onDelete = {
            actions.onDeleteAlarm(a.id); editing = null
        }, onDismiss = { editing = null })
    }
    if (newTimer) {
        TimerEditor(
            onStart = { s, l -> actions.onStartTimer(s, l); newTimer = false },
            onDismiss = { newTimer = false },
        )
    }
}

private val TABS = listOf("Alarms", "Timers", "Stopwatch", "Bedtime")

@Composable
private fun ClockTopBar(onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            MsIcon(R.drawable.ms_arrow_back, "Close", SoftColors.text)
        }
        Text("Clock", color = SoftColors.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp, 8.dp).clip(RoundedCornerShape(12.dp)).background(SoftColors.cardHigh)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = SoftColors.text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        TextButton(onClick = onDismiss) { Text("OK", color = SoftColors.primary) }
    }
}

@Composable
internal fun ProblemCard(p: ProblemRow, onFix: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp, 6.dp).clip(RoundedCornerShape(12.dp))
            .background(SoftColors.alertContainer)
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
    ) {
        Text(p.sentence, color = SoftColors.text, fontSize = 14.sp, lineHeight = 20.sp)
        TextButton(onClick = { onFix(p.key) }, modifier = Modifier.align(Alignment.End)) {
            Text(p.button, color = SoftColors.onAlert)
        }
    }
}

@Composable
private fun AlarmsPane(rows: List<AlarmRow>, actions: ClockActions, onEdit: (ClockAlarm) -> Unit) {
    if (rows.isEmpty()) {
        EmptyText("No alarms. Tap + or say \"set an alarm for 7\".")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.padding(top = 4.dp)) }
        items(rows, key = { it.alarm.id }) { row -> AlarmCard(row, actions, onEdit) }
        item { Spacer(Modifier.padding(bottom = 88.dp)) }
    }
}

@Composable
private fun AlarmCard(row: AlarmRow, actions: ClockActions, onEdit: (ClockAlarm) -> Unit) {
    val on = row.alarm.enabled
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(SoftColors.card)
            .clickable(onClickLabel = "Edit alarm") { onEdit(row.alarm) }.padding(20.dp, 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val ink = if (on) SoftColors.text else SoftColors.text3
            Text(row.time, color = ink, fontSize = 40.sp, fontWeight = FontWeight.Light)
            Text(row.detail, color = if (on) SoftColors.text2 else SoftColors.text3, fontSize = 13.sp)
        }
        Switch(checked = on, onCheckedChange = { actions.onToggleAlarm(row.alarm.id, it) })
    }
}

@Composable
private fun TimersPane(rows: List<TimerRow>, actions: ClockActions) {
    if (rows.isEmpty()) {
        EmptyText("No timers. Tap + or say \"set a timer for 10 minutes\".")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.padding(top = 4.dp)) }
        items(rows, key = { it.id }) { row -> TimerCard(row, actions) }
        item { Spacer(Modifier.padding(bottom = 88.dp)) }
    }
}

@Composable
private fun TimerCard(row: TimerRow, actions: ClockActions) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(SoftColors.card)
            .padding(20.dp, 14.dp),
    ) {
        Text(row.label, color = SoftColors.text2, fontSize = 14.sp)
        val status = when {
            row.ringing -> "Done"
            row.paused -> "${row.left} · paused"
            else -> row.left
        }
        val ink = if (row.ringing) SoftColors.primary else SoftColors.text
        Text(status, color = ink, fontSize = 40.sp, fontWeight = FontWeight.Light)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!row.ringing) {
                if (row.paused) {
                    TextButton(onClick = { actions.onResumeTimer(row.id) }) {
                        Text("Resume", color = SoftColors.primary)
                    }
                } else {
                    TextButton(onClick = { actions.onPauseTimer(row.id) }) { Text("Pause", color = SoftColors.primary) }
                }
            }
            TextButton(onClick = { actions.onCancelTimer(row.id) }) {
                Text(if (row.ringing) "Stop" else "Cancel", color = SoftColors.text2)
            }
        }
    }
}

@Composable
internal fun EmptyText(text: String) {
    Text(text, color = SoftColors.text2, fontSize = 15.sp, modifier = Modifier.padding(24.dp))
}
