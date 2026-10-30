package com.kevin.legion.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.service.WakeEnrollPlan
import com.kevin.legion.service.WakePhrases
import com.kevin.legion.service.WakeSampleRecorder
import com.kevin.legion.service.WakeWordEngine
import com.kevin.legion.service.WakeWordPreferences
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.DeckSwitch
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Tune the wake word to your voice" (ticket 20, Kevin 2026-10-30: "train it to be better for me").
 * Records labelled takes of the wake phrase and two minutes of ordinary sound, through the wake
 * engine's own capture, to files on this phone for the offline eval in `tools/wake_eval/`. Also
 * holds the two tuning switches: save every live wake attempt, and raw capture.
 *
 * Opening the screen sets [WakeSampleRecorder.enrolling], so a good take is logged rather than
 * opening a conversation; leaving it clears that.
 */
@Composable
fun WakeTuningScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lastTake by WakeSampleRecorder.lastTake.collectAsState()
    val takeOpen by WakeSampleRecorder.takeOpen.collectAsState()
    var savedCount by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        WakeSampleRecorder.enrolling = true
        WakeSampleRecorder.beginSession()
        onDispose {
            WakeSampleRecorder.endTake()
            WakeSampleRecorder.enrolling = false
        }
    }
    LaunchedEffect(lastTake, takeOpen) {
        savedCount = withContext(Dispatchers.IO) { WakeSampleRecorder.savedCount(context) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Tune the wake word", onBack = onBack)
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                TuningIntro()
                Spacer(Modifier.height(16.dp))
                EnrollSection(lastTake = lastTake, takeOpen = takeOpen)
                Spacer(Modifier.height(24.dp))
                TuningSwitches(context)
                Spacer(Modifier.height(16.dp))
                DeleteRecordings(
                    savedCount = savedCount,
                    takeOpen = takeOpen,
                    onDeleted = { savedCount = withContext(Dispatchers.IO) { WakeSampleRecorder.savedCount(context) } },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TuningIntro() {
    val sem = LocalLegionSemantics.current
    val running by WakeWordEngine.running.collectAsState()
    val peak by WakeWordEngine.peakLevel.collectAsState()
    Text(
        "This records your voice. Each take is saved as a sound file on this phone, in " +
            "Android/data/com.kevin.legion/files/wake-samples/, so the wake phrase can be " +
            "tuned to how you say it. Nothing is uploaded. While this screen is open the " +
            "wake phrase does not open a conversation.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        if (running) {
            "Listening. Mic level ${peak * PERCENT / Short.MAX_VALUE}%."
        } else {
            "The wake word is not listening. Turn it on in Assistant settings first, then come back."
        },
        style = LegionType.stamp,
        color = sem.faint,
    )
}

/** The twenty prompted takes and the two-minute negative, one step at a time. */
@Composable
private fun EnrollSection(lastTake: WakeSampleRecorder.TakeResult?, takeOpen: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current
    val running by WakeWordEngine.running.collectAsState()
    val phrase = remember { WakePhrases.grammar(CompanionProfile.name(context)).firstOrNull() ?: "hey <name>" }
    val steps = remember(phrase) { WakeEnrollPlan.steps(phrase) }
    var index by remember { mutableIntStateOf(0) }
    var remainingMs by remember { mutableLongStateOf(0L) }

    fun record() {
        val step = steps.getOrNull(index) ?: return
        WakeSampleRecorder.beginTake(step.label, step.windowMs)
        scope.launch {
            val started = System.currentTimeMillis()
            remainingMs = step.windowMs
            while (WakeSampleRecorder.takeStillOpen()) {
                delay(TICK_MS)
                val elapsed = System.currentTimeMillis() - started
                remainingMs = (step.windowMs - elapsed).coerceAtLeast(0L)
                // The take closes itself on its sample count; if capture stopped mid-take (the mic
                // was taken), close it on the clock so the screen never waits forever.
                if (elapsed > step.windowMs + GRACE_MS) WakeSampleRecorder.endTake()
            }
            remainingMs = 0L
            index = (index + 1).coerceAtMost(steps.size)
        }
    }

    EnrollStep(
        step = steps.getOrNull(index),
        progress = "Take ${index + 1} of ${steps.size}",
        recording = takeOpen,
        remainingMs = remainingMs,
        canRecord = running && !takeOpen,
        onRecord = ::record,
    )
    lastTake?.let { t ->
        Spacer(Modifier.height(8.dp))
        Text(
            "Last take ${t.label}: ${verdictWords(t.verdict)}" +
                (t.heard?.let { " Heard \"$it\"." } ?: "") +
                " ${t.durationMs / MS_PER_SECOND} s, peak ${t.peak * PERCENT / Short.MAX_VALUE}%.",
            style = LegionType.stamp,
            color = sem.faint,
        )
    }
    Spacer(Modifier.height(8.dp))
    Row {
        DeckButton(
            text = "Redo last take",
            enabled = index > 0 && !takeOpen,
            onClick = {
                WakeSampleRecorder.deleteLastTake()
                index = (index - 1).coerceAtLeast(0)
            },
        )
        Spacer(Modifier.width(8.dp))
        DeckButton(
            text = "Skip",
            enabled = index < steps.size && !takeOpen,
            onClick = { index = (index + 1).coerceAtMost(steps.size) },
        )
    }
}

@Composable
@Suppress("LongParameterList") // one step's display state, passed straight through
private fun EnrollStep(
    step: WakeEnrollPlan.Step?,
    progress: String,
    recording: Boolean,
    remainingMs: Long,
    canRecord: Boolean,
    onRecord: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    if (step == null) {
        Text(
            "All takes done. Ask for the tuning to be run on these recordings.",
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    Text(progress, style = LegionType.stamp, color = sem.faint)
    Spacer(Modifier.height(4.dp))
    Text(step.instruction, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    DeckButton(
        text = when {
            recording -> "Recording... ${(remainingMs + MS_PER_SECOND - 1) / MS_PER_SECOND} s"
            step.positive -> "Record, then speak"
            else -> "Start two minutes"
        },
        enabled = canRecord,
        onClick = onRecord,
    )
}

/** Save-every-attempt and raw capture. Both off by default. */
@Composable
private fun TuningSwitches(context: Context) {
    var saveLive by remember { mutableStateOf(WakeWordPreferences.saveLiveSamples(context)) }
    var rawCapture by remember { mutableStateOf(WakeWordPreferences.rawCapture(context)) }
    TuningSwitch(
        title = "Save every wake attempt",
        explanation = "records your voice and the room each time the phone hears speech, " +
            "with what the wake word decided. Keeps the newest 300. Off unless you turn it on.",
        checked = saveLive,
        onToggle = { on ->
            WakeWordPreferences.setSaveLiveSamples(context, on)
            WakeSampleRecorder.setLiveOn(on)
            saveLive = on
        },
    )
    Spacer(Modifier.height(8.dp))
    TuningSwitch(
        title = "Raw capture (for comparison)",
        explanation = "listens with the plain speech-recognition mic instead of call audio " +
            "with echo and noise cancelling. For recording a second set to compare; leave " +
            "it off day to day.",
        checked = rawCapture,
        onToggle = { on ->
            WakeWordPreferences.setRawCapture(context, on)
            rawCapture = on
            // The source is chosen when capture opens, so a running engine restarts to pick it up.
            if (WakeWordPreferences.isEnabled(context)) {
                WakeWordEngine.stop()
                WakeWordEngine.start(context)
            }
        },
    )
}

@Composable
private fun DeleteRecordings(savedCount: Int, takeOpen: Boolean, onDeleted: suspend () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current
    var deleteArmed by remember { mutableStateOf(false) }
    Text("$savedCount recordings saved on this phone.", style = LegionType.stamp, color = sem.faint)
    Spacer(Modifier.height(8.dp))
    DeckButton(
        text = if (deleteArmed) "Confirm: delete all recordings" else "Delete all recordings",
        destructive = true,
        confirming = deleteArmed,
        enabled = savedCount > 0 && !takeOpen,
        onClick = {
            if (deleteArmed) {
                WakeSampleRecorder.deleteAll(context)
                deleteArmed = false
                scope.launch {
                    delay(TICK_MS)
                    onDeleted()
                }
            } else {
                deleteArmed = true
            }
        },
    )
}

@Composable
private fun TuningSwitch(title: String, explanation: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(explanation, style = LegionType.stamp, color = sem.faint)
            }
            DeckSwitch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

private fun verdictWords(verdict: String): String = when (verdict) {
    "opened" -> "the wake word heard it and would have opened."
    "confirm_rejected" -> "the spotter heard it, the double-check rejected it."
    "kws_hit" -> "the spotter heard it."
    else -> "the wake word did not hear it."
}

private const val TICK_MS = 200L
private const val GRACE_MS = 2_000L
private const val MS_PER_SECOND = 1000L
private const val PERCENT = 100
