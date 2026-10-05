package com.kevin.legion.ui.assistant

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.service.AssistantChatStore
import com.kevin.legion.service.ChatEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the assistant strip's typed box and reply panel draw. One value, collected with
 * `collectAsStateWithLifecycle` (CLAUDE.md sec 8).
 */
data class AssistantChatUiState(
    /** The active companion's name, from the profile. Never a literal in copy (CLAUDE.md sec 1). */
    val companionName: String,
    val entries: List<ChatEntry>,
    /** A typed reply still arriving (text so far, possibly empty), or null. */
    val pendingReply: String?,
) {
    val hasConversation: Boolean get() = entries.isNotEmpty() || pendingReply != null
}

/**
 * The assistant strip's ViewModel (`AndroidViewModel`, no Hilt - same as
 * [com.kevin.legion.ui.money.MoneyMonthViewModel]). It only reads the process-global
 * [AssistantChatStore] the foreground service's controller writes, and sends the same start-intents
 * push-to-talk does: the controller is service-owned and this never binds or constructs one
 * (see [AssistantStrip]).
 */
class AssistantChatViewModel(application: Application) : AndroidViewModel(application) {
    private val companionName = MutableStateFlow(readCompanionName())

    val state: StateFlow<AssistantChatUiState> = combine(companionName, AssistantChatStore.transcript) { name, t ->
        AssistantChatUiState(name, t.entries, t.pendingTypedReply)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        AssistantChatUiState(companionName.value, emptyList(), null),
    )

    /** The companion can change in Settings while this ViewModel lives; re-read on ON_RESUME. */
    fun refreshCompanionName() {
        companionName.value = readCompanionName()
    }

    fun send(text: String) {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, AriaForegroundService::class.java)
                .setAction(AriaForegroundService.ACTION_TYPE)
                .putExtra(AriaForegroundService.EXTRA_TYPED_TEXT, text),
        )
    }

    fun newConversation() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, AriaForegroundService::class.java).setAction(AriaForegroundService.ACTION_NEW_CHAT),
        )
        // Immediate, rather than waiting on the service round trip.
        AssistantChatStore.clear()
    }

    private fun readCompanionName(): String =
        CompanionProfile.name(getApplication()).trim().ifBlank { FALLBACK_NAME }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** Only when a profile has no name at all; a generic noun, not a persona name. */
        const val FALLBACK_NAME = "the assistant"
    }
}
