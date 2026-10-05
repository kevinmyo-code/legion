package com.kevin.legion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-global holder for the assistant panel's [ChatTranscript]: the service-owned
 * [LiveSessionController] writes, the strip's ViewModel reads. Same reason and same shape as
 * [CompanionPhase] - the controller lives in the foreground service and the UI cannot hold a
 * reference to it. It holds only the pure value; all the logic is in [ChatTranscript] and is tested
 * there. **Memory only: never persisted, never synced** (session-only history, ticket 06).
 */
object AssistantChatStore {
    private val _transcript = MutableStateFlow(ChatTranscript())
    val transcript: StateFlow<ChatTranscript> = _transcript.asStateFlow()

    fun update(change: (ChatTranscript) -> ChatTranscript) = _transcript.update(change)

    /** "New conversation": drop everything on screen. */
    fun clear() {
        _transcript.value = ChatTranscript()
    }
}
