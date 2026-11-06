package com.kevin.legion.ui.body

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.kevin.legion.bodycoach.SessionWire
import com.kevin.legion.bodycoach.WorkoutSessionController
import com.kevin.legion.bodycoach.WorkoutStep
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import com.kevin.legion.ui.theme.soft.SoftColors
import kotlinx.coroutines.launch

/**
 * The session screen's state holder (workouts, 2026-11-06): every tap goes to
 * [WorkoutSessionController], the controller the voice tools call (ADR 0035), and the screen redraws
 * from what it hands back. A tick lands on this phone at once and reaches the engine when it can;
 * the line under the progress strip says which, in words.
 */
@Composable
internal fun WorkoutSessionHost(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { WorkoutSessionController.forApp(context) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<SessionWire?>(controller.current()) }
    var note by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val read = controller.today()
        read.valueOrNull?.session?.let { session = it }
        note = read.noteOrNull
        loaded = true
    }

    fun run(action: suspend () -> WorkoutStep) {
        busy = true
        scope.launch {
            val step = action()
            step.session?.let { session = it }
            message = if (step.ok && step.sentence == "Saved.") null else step.sentence
            busy = false
        }
    }

    val current = session
    if (current == null) {
        androidx.compose.foundation.layout.Column {
            com.kevin.legion.ui.common.DrilldownHeader(title = "Workout", onBack = onBack, accent = com.kevin.legion.ui.theme.soft.AreaAccent.BODY)
            Text(
                if (loaded) (note ?: "No workout is in progress. Start one from the Body screen.") else "Reading the workout...",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text2,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }
    WorkoutSessionScreen(
        session = current,
        note = note,
        message = message,
        busy = busy,
        onBack = onBack,
        onTick = { s, done -> run { controller.tick(s.exerciseKey, s.setNumber, done, s.reps, s.weightLbs) } },
        onEdit = { s, reps, weight -> run { controller.tick(s.exerciseKey, s.setNumber, true, reps, weight) } },
        onSwap = { s -> run { controller.swap(s.name) } },
        onFinish = { run { controller.finish() } },
        onCancel = { run { controller.cancel() } },
    )
}
