package com.kevin.legion.ui.typeset

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.MidnightApplication
import com.kevin.legion.assistant.cards.ConversationCards
import com.kevin.legion.clock.AlarmMath
import com.kevin.legion.clock.ClockGraph
import com.kevin.legion.dumb.FeedOffRule
import com.kevin.legion.quiet.QuietMode
import com.kevin.legion.service.Phase
import com.kevin.legion.ui.assistant.ChatPanelSheet
import com.kevin.legion.ui.assistant.rememberAssistantVoice
import com.kevin.legion.ui.common.startPhoneSettings
import com.kevin.legion.ui.dumb.FeedOffGatePanel
import com.kevin.legion.ui.home.DockSlotUi
import com.kevin.legion.ui.home.HomeCalendarUiState
import com.kevin.legion.ui.home.HomeMoneyUiState
import com.kevin.legion.ui.home.rememberClaudeWatchLine
import com.kevin.legion.ui.theme.soft.LocalTypesetEdition
import com.kevin.legion.ui.theme.soft.LocalTypesetEditionToggle
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetEdition
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Typeset front page, stateful (ADR 0081): HOME's own view-model state in, the glance and the
 * conversation out. Owns nothing the shell already owns: the clock string is the shell's ticker, the
 * dock slots and their launcher are `HomeScreen`'s, the voice truth is [rememberAssistantVoice]
 * (shared with the strip, which the shell hides on this route). The Controls panel is an overlay
 * here, not a route, so the gate's text field stays above the keyboard.
 */
@Composable
@Suppress("LongParameterList") // one host: HOME's state and the shell's hand-offs, nothing invented here
fun TypesetHomeHost(
    calendar: HomeCalendarUiState,
    money: HomeMoneyUiState,
    /** The shell's clock, "HH:mm". */
    clock: String,
    /** Off-normal shell words (not synced, OBD, quiet, alarms), or null when nothing needs saying. */
    statusWords: String?,
    dockSlots: List<DockSlotUi>,
    onLaunchSlot: (DockSlotUi) -> Unit,
    nav: TypesetHomeNav,
) {
    val context = LocalContext.current
    val voice = rememberAssistantVoice(nav.onOpenSettings)
    val cards by ConversationCards.state.collectAsStateWithLifecycle()
    val edition = LocalTypesetEdition.current
    val toggleEdition = LocalTypesetEditionToggle.current
    var controlsOpen by remember { mutableStateOf(false) }

    val now = remember(clock) { runCatching { LocalTime.parse(clock) }.getOrDefault(LocalTime.now()) }
    val glance = TypesetGlance(
        dateline = calendar.today.format(DATELINE),
        time = clock,
        next = TypesetHomeDerive.next(calendar.dayRows, now),
        due = TypesetHomeDerive.due(calendar.openTodoCount, calendar.todos),
        left = TypesetHomeDerive.left(money.budgets, money.today.month.getDisplayName(TextStyle.FULL, Locale.US)),
    )
    val status = if (voice.enabled) voice.state.label else "Assistant off, tap to set it up"
    val onTalk = if (voice.enabled) voice.talk else nav.onOpenSettings
    val actions = TypesetHomeActions(
        onHome = voice.typedChat.onNewConversation,
        onControls = { controlsOpen = true },
        onToggleEdition = toggleEdition,
        onOpenClock = nav.onOpenClock,
        onOpenToday = nav.onOpenToday,
        onOpenMoney = nav.onOpenMoney,
        onOpenCar = nav.onOpenCar,
        onOpenField = nav.onOpenField,
        onBreathe = { BreathActivity.start(context) },
        onTalk = onTalk,
        onType = voice.panel::show,
        onDismissCard = ConversationCards::dismiss,
        onOpenApp = { tile -> dockSlots.firstOrNull { slotTile(it) == tile }?.let(onLaunchSlot) },
        onAllApps = nav.onOpenApps,
        onMore = nav.onOpenMore,
    )
    Box(Modifier.fillMaxSize().background(SoftColors.ground)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TypesetHomeContent(
                glance = glance,
                phase = orbPhase(voice.phase, voice.enabled),
                status = status,
                statusWords = statusWords,
                night = edition == TypesetEdition.NIGHT,
                cards = cards.cards,
                actions = actions,
                dock = dockSlots.map(::slotTile),
            )
        }
        if (controlsOpen) ControlsOverlay(onClose = { controlsOpen = false }, nav = nav)
    }
    if (voice.panel.open) ChatPanelSheet(voice.typedChat, onTalk = onTalk, onClose = voice.panel::hide)
}

/** The orb's phase from the controller's. CONNECTING reads as thinking: nothing to hear yet. */
internal fun orbPhase(phase: Phase, enabled: Boolean): OrbPhase = when {
    !enabled -> OrbPhase.IDLE
    phase == Phase.LISTENING -> OrbPhase.LISTENING
    phase == Phase.THINKING || phase == Phase.CONNECTING -> OrbPhase.THINKING
    phase == Phase.SPEAKING -> OrbPhase.SPEAKING
    else -> OrbPhase.IDLE
}

/** A dock pin as a monogram tile: the label's first two letters, as the prototype's "Wa", "Gm". */
internal fun slotTile(slot: DockSlotUi): TypesetDockTile {
    val letters = slot.label.filter { it.isLetterOrDigit() }
    val monogram = letters.take(2).lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "?" }
    return TypesetDockTile(monogram, slot.label)
}

/**
 * The Controls panel over the page: a scrim that closes it, the panel at the top. Quiet and the feed
 * blocker are wired exactly as their settings rows are (`FeedBlockRow`); turning the blocker off is
 * the ADR 0066 gate, unchanged, under its row.
 */
@Composable
@Suppress("LongMethod") // one overlay: the panel's state and its six taps, read top to bottom
private fun ControlsOverlay(onClose: () -> Unit, nav: TypesetHomeNav) {
    val context = LocalContext.current
    val graph = remember(context) { (context.applicationContext as MidnightApplication).dumb }
    val store = graph.feedBlock
    val gate = graph.feedOffGate
    var quietOn by remember { mutableStateOf(QuietMode.isOn(context)) }
    var feedOn by remember { mutableStateOf(store.get()) }
    var askingOff by remember { mutableStateOf(feedOn && gate.waitRemainingMs(FeedOffRule.SWITCH) != null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        quietOn = QuietMode.isOn(context)
        feedOn = store.get()
        askingOff = feedOn && gate.waitRemainingMs(FeedOffRule.SWITCH) != null
        graph.feedBlockWatch.check()
    }
    val clockState by remember(context) { ClockGraph.controller(context.applicationContext) }.state
        .collectAsStateWithLifecycle()
    val claude = rememberClaudeWatchLine()
    val ui = ControlsUi(
        quietOn = quietOn,
        feedBlockOn = feedOn,
        feedNote = if (feedOn) "Feed apps blocked. Off needs the 60 s wait and the sentence." else "Off",
        feedGateOpen = feedOn && askingOff,
        clockNote = nextAlarmWords(clockState.alarms.mapNotNull {
            AlarmMath.nextTrigger(it, System.currentTimeMillis(), ZoneId.systemDefault())
        }),
        claudeNote = claude ?: "None running",
    )
    val actions = ControlsActions(
        onClose = onClose,
        onToggleQuiet = {
            when (val r = QuietMode.toggle(context)) {
                is QuietMode.Result.Refused -> Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                else -> quietOn = QuietMode.isOn(context)
            }
        },
        onFeedBlockOn = {
            store.set(true)
            feedOn = true
            gate.cancel(FeedOffRule.SWITCH)
            askingOff = false
            graph.feedBlockWatch.check()
        },
        onFeedBlockOff = {
            gate.request(FeedOffRule.SWITCH)
            askingOff = true
        },
        onOpenClock = { onClose(); nav.onOpenClock() },
        onOpenClaude = { onClose(); nav.onOpenClaude() },
        onOpenLegionSettings = { onClose(); nav.onOpenSettings() },
        onOpenPhoneSettings = {
            startPhoneSettings(context)?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        },
    )
    Box(
        Modifier.fillMaxSize().background(SoftColors.text.copy(alpha = SCRIM))
            .clickable(role = Role.Button, onClickLabel = "Close controls", onClick = onClose),
        contentAlignment = Alignment.TopCenter,
    ) {
        ControlsPanel(
            ui,
            actions,
            modifier = Modifier.fillMaxWidth().clickable(enabled = false) {},
            gate = {
                FeedOffGatePanel(
                    gate, FeedOffRule.SWITCH, FeedOffRule.SWITCH_SENTENCE, "Turn off",
                    onConfirmed = {
                        store.set(false)
                        feedOn = false
                        askingOff = false
                        graph.feedBlockWatch.check()
                    },
                    onDropped = { askingOff = false },
                )
            },
        )
    }
}

/** "Next alarm 06:30", 24-hour, or "No alarm set". */
internal fun nextAlarmWords(triggersMs: List<Long>, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = triggersMs.minOrNull() ?: return "No alarm set"
    val t = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
    return "Next alarm " + AlarmMath.hhmm(t.hour, t.minute)
}

private const val SCRIM = 0.4f
private val DATELINE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.US)
