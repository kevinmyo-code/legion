package com.kevin.legion.dumb

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.MidnightApplication
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

private const val TICK_MS = 1_000L
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * Home A and the Leave flow's ViewModel (ADR 0062): one [StateFlow] of [DumbUiState], rebuilt every
 * second while something is collecting it (the clock and the leave countdown both move). The
 * numbers come from [DumbUiStates.build], which is what the tests cover.
 */
class DumbModeViewModel(
    private val controller: DumbModeController,
    private val clock: () -> Long = System::currentTimeMillis,
    peopleNames: () -> List<String> = { emptyList() },
    zone: () -> ZoneId = ZoneId::systemDefault,
    locale: () -> Locale = Locale::getDefault,
) : ViewModel() {

    private val ticks = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<DumbUiState> = combine(controller.state, ticks) { s, now ->
        DumbUiStates.build(s, now, zone(), locale(), peopleNames())
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        DumbUiStates.build(controller.state.value, clock(), zone(), locale(), peopleNames()),
    )

    fun requestLeave() {
        controller.requestLeave()
    }

    fun stay() = controller.stay()

    fun leave(typed: String): LeaveResult = controller.leave(typed)

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext as MidnightApplication

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DumbModeViewModel(app.dumbMode, peopleNames = { app.dumb.people.names() }) as T
    }
}
