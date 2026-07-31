package com.kevin.legion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide flag for whether a driver-initiated conversation turn is
 * currently active - the mic is open, Zero is thinking, or Zero is speaking a
 * reply. The proactive engine in [AriaForegroundService] gates on [isBusy] so it
 * never speaks over the driver (openers/alerts only fire while idle).
 */
object ConversationState {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val isBusy: Boolean get() = _busy.value

    fun setBusy(value: Boolean) {
        _busy.value = value
    }
}
