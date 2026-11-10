package com.kevin.legion.ui.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.service.AssistantIgnition
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.service.Phase
import kotlinx.coroutines.delay

/**
 * What a voice control shows and does, resolved once for whichever surface draws it: the shell's
 * [AssistantStrip] and the Typeset front page's orb (ADR 0081). One resolution, so the two never
 * disagree about the words or about what a tap does.
 */
data class AssistantVoice(
    /** False until the assistant is set up: the surface says so and a tap opens Settings. */
    val enabled: Boolean,
    val phase: Phase,
    val state: AssistantStripResolver.State,
    /** The tap: starts a turn (or interrupts Alfred while he speaks), or opens Settings when the mic is refused. */
    val talk: () -> Unit,
    val typedChat: TypedChatUi,
    val panel: ChatPanelState,
)

/**
 * Collects the phase, caption, notice, tool and silence flows, the mic permission and the typed chat,
 * and resolves them through [AssistantStripResolver]. Lifted out of [AssistantStrip] so the Typeset
 * orb reads the same truth.
 */
@Composable
fun rememberAssistantVoice(onOpenSettings: () -> Unit): AssistantVoice {
    val context = LocalContext.current
    val enabled by AssistantIgnition.enabledState(context).collectAsStateWithLifecycle()
    val phase by CompanionPhase.phase.collectAsStateWithLifecycle()
    val caption by CompanionPhase.caption.collectAsStateWithLifecycle()
    val toolRunning by CompanionPhase.toolRunning.collectAsStateWithLifecycle()
    val silenced by CompanionPhase.silenced.collectAsStateWithLifecycle()
    var micGranted by remember { mutableStateOf(hasRecordAudio(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { micGranted = hasRecordAudio(context) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        CompanionPhase.notice.collect { raised ->
            if (!CompanionPhase.noticeStillWorthShowing(raised.atMs, System.currentTimeMillis())) return@collect
            val text = raised.text
            notice = text
            delay(NOTICE_DISPLAY_MS)
            if (notice == text) notice = null
        }
    }
    LaunchedEffect(phase) { if (phase == Phase.LISTENING) notice = null }

    val chatViewModel: AssistantChatViewModel = viewModel()
    val chat by chatViewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { chatViewModel.refreshCompanionName() }
    val panel = remember { ChatPanelState() }
    LaunchedEffect(chat.entries) { panel.observe(chat.entries) }
    val typedChat = TypedChatUi(
        companionName = chat.companionName,
        entries = chat.entries,
        pendingReply = chat.pendingReply,
        onSend = chatViewModel::send,
        onNewConversation = chatViewModel::newConversation,
        unreadReply = panel.unread,
        onOpenChat = panel::show,
    )
    val talk: () -> Unit = {
        if (micGranted) {
            context.startService(
                Intent(context, AriaForegroundService::class.java).setAction(AriaForegroundService.ACTION_TALK),
            )
        } else {
            onOpenSettings()
        }
    }
    return AssistantVoice(
        enabled = enabled,
        phase = phase,
        state = AssistantStripResolver.resolve(phase, caption, notice, micGranted, silenced, toolRunning),
        talk = talk,
        typedChat = typedChat,
        panel = panel,
    )
}

/** How long a raised notice holds the label slot before the phase words return. */
internal const val NOTICE_DISPLAY_MS = 4_000L

internal fun hasRecordAudio(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
