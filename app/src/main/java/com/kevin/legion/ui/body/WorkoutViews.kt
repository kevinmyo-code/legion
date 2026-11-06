@file:Suppress("FunctionNaming", "MagicNumber", "LongParameterList", "TooManyFunctions")
// FunctionNaming: @Composable convention is PascalCase. MagicNumber: layout dp from the prototype.

package com.kevin.legion.ui.body

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.bodycoach.PlanWire
import com.kevin.legion.bodycoach.SessionWire
import com.kevin.legion.bodycoach.SetWire
import com.kevin.legion.bodycoach.TargetWire
import com.kevin.legion.bodycoach.WeekWire
import com.kevin.legion.bodycoach.WorkoutTodayWire
import com.kevin.legion.bodycoach.WorkoutText
import com.kevin.legion.ui.common.DrilldownHeader
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * The workout on the Body screen, direction 2 of the workout prototypes (Kevin picked it
 * 2026-11-06): a card on the Body tab that says today's targets and why, and a session screen that
 * Start opens, one row per set with a big tick and tappable numbers. **Every number shown is the
 * engine's** (`server/body_coach/programme.py`); these composables hold no controller, they draw
 * plain data and call back (the `compose-state-holder-ui-split` skill; [com.kevin.legion.ui.BodyScreen]
 * is the state holder). Voice reaches the same controller (ADR 0035).
 */

private val GoodContainer = Color(0xFF173D2B)
private val CautionContainer = Color(0xFF3D331A)

// ------------------------------------------------------------------------------ the card

/**
 * "Today's workout". On a training day it lists the targets and a Start; on a rest day it shows the
 * next workout under a quieter title, Start still there (he may train when he likes). A running
 * workout says how far along it is and Resume; a finished one says it is logged.
 */
@Composable
internal fun BodyWorkoutCard(
    today: WorkoutTodayWire?,
    note: String?,
    busy: Boolean,
    message: String?,
    onStart: () -> Unit,
    onOpen: () -> Unit,
) {
    BodyCard(Modifier.border(2.dp, AreaAccent.BODY.container, RoundedCornerShape(20.dp))) {
        val session = today?.session
        val plan = today?.next
        val title = when {
            today == null -> "Workout"
            session != null || today.trainingDay -> "Today's workout"
            else -> "Next workout"
        }
        val dayKey = session?.dayKey ?: plan?.dayKey
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SoftColors.text, fontWeight = FontWeight.SemiBold)
            dayKey?.let { DayTag("Day $it") }
            Spacer(Modifier.weight(1f))
            val aside = when {
                session != null -> "${session.setsDone} of ${session.setCount} sets"
                plan != null -> "${plan.exercises.size} exercises, ${plan.setCount} sets"
                else -> null
            }
            aside?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2) }
        }
        when {
            today == null -> Text(
                note ?: "Reading the workout...",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text2,
                modifier = Modifier.padding(top = 8.dp),
            )
            session != null -> SessionSummary(session, onOpen)
            plan != null -> PlanTargets(plan, today.trainingDay, busy, onStart)
        }
        note?.takeIf { today != null }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3, modifier = Modifier.padding(top = 8.dp))
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun DayTag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = SoftColors.text3,
        modifier = Modifier.border(1.dp, SoftColors.outline, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun PlanTargets(plan: PlanWire, trainingDay: Boolean, busy: Boolean, onStart: () -> Unit) {
    if (plan.calibrating) CalibrationNote()
    if (plan.deload) {
        Text(
            "Deload session: lighter and one set fewer. Progress resumes next time.",
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.caution,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    plan.exercises.forEachIndexed { i, t ->
        if (i > 0) RowRule()
        TargetRow(t)
    }
    PrimaryButton(
        label = if (busy) "Starting..." else if (trainingDay) "Start" else "Start anyway",
        icon = R.drawable.ms_play_arrow,
        enabled = !busy,
        onClick = onStart,
        modifier = Modifier.padding(top = 12.dp),
    )
    val ups = plan.exercises.filter { it.rule == "add" }
    val line = when {
        plan.calibrating -> "After this session the rules take over: weight goes up when every set reaches the top of its range."
        ups.isNotEmpty() -> ups.joinToString(" ") { "${it.name} goes up today." } + " Everything else holds until every set hits the top."
        else -> "Numbers come from your programme's rules: add weight when every set hits the top of the range."
    }
    Text(line, style = MaterialTheme.typography.labelSmall, color = if (ups.isNotEmpty()) SoftColors.good else SoftColors.text3, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CalibrationNote() {
    Text(
        "Calibration. Where it says your pick, choose a weight you could lift for 2 more reps than " +
            "the top of the range. Log what you used; next time the rules take over.",
        style = MaterialTheme.typography.bodySmall,
        color = SoftColors.caution,
        modifier = Modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .fillMaxWidth()
            .background(CautionContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun TargetRow(t: TargetWire) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.name, style = MaterialTheme.typography.bodyLarge, color = SoftColors.text, modifier = Modifier.weight(1f))
        val target = WorkoutText.target(t).removePrefix(t.name).trim()
        Text(
            target,
            style = MaterialTheme.typography.titleSmall,
            color = if (t.calibrating) SoftColors.caution else SoftColors.text,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun RowRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SoftColors.barRule))
}

@Composable
private fun SessionSummary(session: SessionWire, onOpen: () -> Unit) {
    ProgressStrip(session, Modifier.padding(top = 10.dp))
    if (session.inProgress) {
        PrimaryButton("Resume", R.drawable.ms_play_arrow, enabled = true, onClick = onOpen, modifier = Modifier.padding(top = 12.dp))
    } else {
        val said = if (session.status == SessionWire.STATUS_FINISHED) "Logged: ${session.setsDone} sets." else "Cancelled; nothing was logged."
        Text(said, style = MaterialTheme.typography.bodyMedium, color = SoftColors.good, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun ProgressStrip(session: SessionWire, modifier: Modifier = Modifier) {
    val planned = session.sets.filterNot { it.extra }
    Row(modifier.fillMaxWidth().semantics { contentDescription = "${session.setsDone} of ${session.setCount} sets ticked" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        planned.forEach { s ->
            Box(Modifier.weight(1f).height(6.dp).background(if (s.done) SoftColors.good else SoftColors.cardHighest, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun PrimaryButton(label: String, icon: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, quiet: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (quiet) Color.Transparent else SoftColors.primaryContainer, RoundedCornerShape(24.dp))
            .then(if (quiet) Modifier.border(1.dp, SoftColors.outline, RoundedCornerShape(24.dp)) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (quiet) SoftColors.text else SoftColors.onPrimaryContainer
        MsIcon(icon, contentDescription = null, tint = tint, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** The "This week" line's subtitle: the programme, in words. */
internal fun weekSubtitle(week: WeekWire?): String =
    if (week == null) "Workouts finished this week" else "Full body, A and B alternating, on ${week.trainingDays.joinToString(", ")}"

// ----------------------------------------------------------------------- the session screen

/**
 * The session tick-off view: one card per exercise, one row per set. The circle ticks a set at
 * what the row shows; tapping the numbers opens a small editor for the reps and weight actually
 * done. Swap is per exercise. Finish and log sits at the bottom.
 */
@Composable
internal fun WorkoutSessionScreen(
    session: SessionWire,
    note: String?,
    message: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onTick: (SetWire, Boolean) -> Unit,
    onEdit: (SetWire, Int, Double?) -> Unit,
    onSwap: (SetWire) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    var editing by remember { mutableStateOf<SetWire?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    val groups = WorkoutText.exercises(session)
    Surface(Modifier.fillMaxSize(), color = SoftColors.ground) {
        Column(Modifier.fillMaxSize()) {
            DrilldownHeader(title = "Day ${session.dayKey}", onBack = onBack, accent = AreaAccent.BODY)
            Column(Modifier.padding(horizontal = 16.dp)) {
                ProgressStrip(session)
                Text(
                    "${session.setsDone} of ${session.setCount} sets." + if (session.deload) " Deload session." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftColors.text3,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (session.calibrating) CalibrationNote()
                (message ?: note)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2, modifier = Modifier.padding(top = 4.dp))
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(groups, key = { it.second.first().exerciseKey }) { (name, sets) ->
                    ExerciseCard(name, sets, session.inProgress && !busy, onTick, { editing = it }, onSwap)
                }
                item(key = "cancel") {
                    if (session.inProgress) {
                        Text(
                            "Cancel this workout",
                            style = MaterialTheme.typography.labelLarge,
                            color = SoftColors.text3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { confirmCancel = true }
                                .padding(vertical = 14.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(SoftColors.barLow).padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (session.inProgress) {
                    PrimaryButton(if (busy) "Saving..." else "Finish and log", R.drawable.ms_check, enabled = !busy, onClick = onFinish)
                } else {
                    PrimaryButton("Back to Body", R.drawable.ms_arrow_back, enabled = true, onClick = onBack, quiet = true)
                }
            }
        }
    }
    editing?.let { set ->
        EditSetDialog(set, onDismiss = { editing = null }, onSave = { reps, weight -> editing = null; onEdit(set, reps, weight) })
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            containerColor = SoftColors.cardHigh,
            title = { Text("Cancel this workout?", color = SoftColors.text) },
            text = { Text("Nothing is logged and it does not count toward the week.", color = SoftColors.text2) },
            confirmButton = { TextButton(onClick = { confirmCancel = false; onCancel() }) { Text("Cancel workout", color = SoftColors.onAlert) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep going", color = SoftColors.text) } },
        )
    }
}

@Composable
private fun ExerciseCard(
    name: String,
    sets: List<SetWire>,
    enabled: Boolean,
    onTick: (SetWire, Boolean) -> Unit,
    onEdit: (SetWire) -> Unit,
    onSwap: (SetWire) -> Unit,
) {
    val first = sets.first()
    val planned = sets.count { !it.extra }
    val allDone = sets.all { it.done }
    Column(
        Modifier
            .fillMaxWidth()
            .background(SoftColors.card, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = SoftColors.text, fontWeight = FontWeight.Bold)
                Text(
                    WorkoutText.target(name, planned, first.repsLow, first.repsHigh, first.unit, first.kind, first.targetWeightLbs).removePrefix(name).trim() +
                        if (allDone) ", done" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftColors.text2,
                )
            }
            if (enabled && sets.none { it.done }) {
                Row(
                    Modifier
                        .background(SoftColors.cardHigh, RoundedCornerShape(18.dp))
                        .clickable(role = Role.Button, onClickLabel = "Swap $name") { onSwap(first) }
                        .heightIn(min = 36.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MsIcon(R.drawable.ms_repeat, contentDescription = null, tint = SoftColors.text2, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Swap", style = MaterialTheme.typography.labelLarge, color = SoftColors.text2)
                }
            }
        }
        if (first.words.isNotBlank()) {
            Text(
                first.words,
                style = MaterialTheme.typography.labelSmall,
                color = if (first.rule == "add") SoftColors.good else SoftColors.text3,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        sets.forEach { s -> SetRow(s, enabled, onTick, onEdit) }
    }
}

@Composable
private fun SetRow(s: SetWire, enabled: Boolean, onTick: (SetWire, Boolean) -> Unit, onEdit: (SetWire) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (s.extra) "Extra" else "Set ${s.setNumber}",
            style = MaterialTheme.typography.bodySmall,
            color = SoftColors.text3,
            modifier = Modifier.width(52.dp),
        )
        val edited = s.done && ((s.reps ?: s.repsHigh) < s.repsLow || (s.targetWeightLbs != null && s.weightLbs != null && s.weightLbs < s.targetWeightLbs))
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .background(
                    when {
                        edited -> CautionContainer
                        s.done -> GoodContainer
                        else -> SoftColors.cardHigh
                    },
                    RoundedCornerShape(12.dp),
                )
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Change set ${s.setNumber}") { onEdit(s) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                WorkoutText.setValue(s),
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    edited -> SoftColors.caution
                    s.done -> SoftColors.good
                    else -> SoftColors.text
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(48.dp)
                .then(
                    if (s.done) Modifier.background(SoftColors.good, CircleShape) else Modifier.border(2.dp, SoftColors.outline, CircleShape),
                )
                .clickable(enabled = enabled, role = Role.Checkbox, onClickLabel = if (s.done) "Untick set ${s.setNumber}" else "Tick set ${s.setNumber}") {
                    onTick(s, !s.done)
                }
                .semantics { contentDescription = "${s.name} set ${s.setNumber}, ${if (s.done) "ticked" else "not ticked"}" },
            contentAlignment = Alignment.Center,
        ) {
            if (s.done) MsIcon(R.drawable.ms_check, contentDescription = null, tint = SoftColors.ground, size = 26.dp)
        }
    }
}

@Composable
private fun EditSetDialog(set: SetWire, onDismiss: () -> Unit, onSave: (Int, Double?) -> Unit) {
    var reps by remember { mutableStateOf(set.reps ?: set.repsHigh) }
    var weight by remember { mutableStateOf(set.weightLbs ?: set.targetWeightLbs ?: if (set.weighted) 45.0 else null) }
    val unit = if (set.unit == "seconds") "s" else "reps"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SoftColors.cardHigh,
        title = { Text("${set.name}, set ${set.setNumber}", color = SoftColors.text) },
        text = {
            Column {
                Text(
                    "Target " + WorkoutText.target(set.name, 1, set.repsLow, set.repsHigh, set.unit, set.kind, set.targetWeightLbs).removePrefix("${set.name} 1 x ").trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftColors.text2,
                )
                Stepper(if (set.unit == "seconds") "Seconds" else "Reps", "$reps", unit = "", onMinus = { reps = (reps - 1).coerceAtLeast(0) }, onPlus = { reps = (reps + 1).coerceAtMost(1000) })
                weight?.let { w ->
                    Stepper("Weight", WorkoutText.lbs(w), unit = "lb", onMinus = { weight = (w - 5).coerceAtLeast(0.0) }, onPlus = { weight = (w + 5).coerceAtMost(1995.0) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(reps, weight) }) { Text("Tick set", color = SoftColors.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = SoftColors.text) } },
    )
}

@Composable
private fun Stepper(label: String, value: String, unit: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2, modifier = Modifier.weight(1f))
        StepButton("-", "Less $label", onMinus)
        Text(
            if (unit.isBlank()) value else "$value $unit",
            style = MaterialTheme.typography.titleLarge,
            color = SoftColors.text,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(96.dp),
        )
        StepButton("+", "More $label", onPlus)
    }
}

@Composable
private fun StepButton(symbol: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .background(SoftColors.cardHighest, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge, color = SoftColors.text)
    }
}

// ------------------------------------------------------------------------------ previews

private val PREVIEW_PLAN = PlanWire(
    dayKey = "A",
    sessionNumber = 4,
    setCount = 13,
    exercises = listOf(
        TargetWire("back_squat", "Back squat", sets = 3, repsLow = 6, repsHigh = 8, weightLbs = 155.0, rule = "add", words = "+10 lb: every set reached 8 last time."),
        TargetWire("bench_press", "Bench press", sets = 3, repsLow = 6, repsHigh = 8, weightLbs = 125.0, rule = "hold"),
        TargetWire("barbell_row", "Barbell row", sets = 3, repsLow = 8, repsHigh = 10, weightLbs = 105.0, rule = "hold"),
        TargetWire("lateral_raise", "Lateral raise", sets = 2, repsLow = 12, repsHigh = 15, weightLbs = 15.0, rule = "hold"),
        TargetWire("plank", "Plank", kind = "timed", sets = 2, repsLow = 30, repsHigh = 45, unit = "seconds", rule = "reps"),
    ),
)

@Preview(name = "Workout card: training day", widthDp = 360)
@Composable
private fun PreviewWorkoutCard() = SoftTheme {
    BodyWorkoutCard(WorkoutTodayWire(trainingDay = true, next = PREVIEW_PLAN), note = null, busy = false, message = null, onStart = {}, onOpen = {})
}

@Preview(name = "Workout session", widthDp = 360, heightDp = 800)
@Composable
private fun PreviewWorkoutSession() = SoftTheme {
    val session = com.kevin.legion.bodycoach.WorkoutSessionController.localSession(PREVIEW_PLAN, "preview", "2026-11-06").let {
        com.kevin.legion.bodycoach.WorkoutSessionController.applyTick(it, "back_squat", 1, true, 8, 155.0)
    }
    WorkoutSessionScreen(session, note = null, message = null, busy = false, onBack = {}, onTick = { _, _ -> }, onEdit = { _, _, _ -> }, onSwap = {}, onFinish = {}, onCancel = {})
}
