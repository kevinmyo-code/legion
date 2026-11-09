package com.kevin.legion.ui.clock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kevin.legion.clock.ClockController
import com.kevin.legion.clock.ClockGraph
import com.kevin.legion.clock.ClockHealth
import com.kevin.legion.clock.ClockResult
import com.kevin.legion.ui.theme.soft.SoftTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Alfred's clock screen (ADR 0079): Alarms, Timers, Stopwatch, Bedtime. Matrix home's big clock
 * and the "Clock" launcher entry open it; every button calls the same [ClockController] the `clock`
 * voice tool does (ADR 0035). Problems that would stop an alarm ringing are shown in words at the
 * top, each with the system page that fixes it, re-checked on every resume.
 */
class ClockActivity : ComponentActivity() {
    private val clock: ClockController by lazy { ClockGraph.controller(this) }
    private var tab by mutableIntStateOf(TAB_ALARMS)
    private var notice by mutableStateOf<String?>(null)
    private var health by mutableStateOf<List<ClockHealth.Problem>>(emptyList())
    private var dnd by mutableStateOf<ClockHealth.Problem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = intent.getIntExtra(EXTRA_TAB, savedInstanceState?.getInt(EXTRA_TAB) ?: TAB_ALARMS)
        setContent { SoftTheme { Route() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab = intent.getIntExtra(EXTRA_TAB, tab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        health = ClockHealth.problems(this)
        dnd = ClockHealth.dndProblem(this)
    }

    @Composable
    private fun Route() {
        val state by clock.state.collectAsStateWithLifecycle()
        val tick by produceState(0L) {
            while (true) {
                delay(TICK_MS)
                value++
            }
        }
        val problems = health.mapIndexed { i, p -> ProblemRow(p.sentence, p.button, "h$i") }
        val bedtimeProblem = dnd?.let { ProblemRow(it.sentence, it.button, "dnd") }
        // tick is a key so timers and the stopwatch redraw twice a second.
        val ui = remember(state, tick, tab, problems, bedtimeProblem, notice) {
            ClockUi.from(state, clock.time, tab, problems, bedtimeProblem, notice)
        }
        ClockScreen(ui, actions())
    }

    private fun actions() = ClockActions(
        onTab = { tab = it },
        onSaveAlarm = { a -> io { clock.alarms.save(a); null } },
        onToggleAlarm = { id, on -> io { clock.alarms.setEnabled(id, on); null } },
        onDeleteAlarm = { id -> io { clock.alarms.delete(id); null } },
        onStartTimer = { s, l -> io { clock.timers.start(s, l) } },
        onPauseTimer = { id -> io { clock.timers.pause(clock.timers.idLabel(id)) } },
        onResumeTimer = { id -> io { clock.timers.resume(clock.timers.idLabel(id)) } },
        onCancelTimer = { id -> io { clock.timers.remove(id); null } },
        onStopwatch = { v ->
            io {
                when (v) {
                    StopwatchVerb.START -> clock.stopwatch.start()
                    StopwatchVerb.STOP -> clock.stopwatch.stop()
                    StopwatchVerb.LAP -> clock.stopwatch.lap()
                    StopwatchVerb.RESET -> clock.stopwatch.reset()
                }
                null
            }
        },
        onSaveBedtime = { on, h, m, days -> io { clock.bedtime.schedule(on, h, m, days) } },
        onBedtimeNow = { start -> io { if (start) clock.bedtime.startNow() else clock.bedtime.end() } },
        onFix = ::fix,
        onDismissNotice = { notice = null },
        onClose = ::finish,
    )

    /** Runs a controller call off the main thread; a result's sentence becomes the notice. */
    private fun io(block: () -> ClockResult?) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching(block).getOrElse { ClockResult(false, "That did not work: ${it.message}") }
            }
            result?.let { notice = it.message }
        }
    }

    private fun fix(key: String) {
        val problem = if (key == "dnd") dnd else key.removePrefix("h").toIntOrNull()?.let { health.getOrNull(it) }
        val intent = problem?.intent ?: return
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { notice = NO_SETTINGS_PAGE }
    }

    companion object {
        const val TAB_ALARMS = 0
        const val TAB_TIMERS = 1
        const val TAB_STOPWATCH = 2
        const val TAB_BEDTIME = 3
        private const val EXTRA_TAB = "tab"
        private const val TICK_MS = 500L
        private const val NO_SETTINGS_PAGE =
            "That settings page would not open on this phone. Find it under Settings, Apps, LEGION."

        fun intent(context: Context, tab: Int = TAB_ALARMS): Intent =
            Intent(context, ClockActivity::class.java).putExtra(EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
