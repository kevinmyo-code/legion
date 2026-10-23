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
    private val peopleNames: () -> List<String> = { emptyList() },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val locale: () -> Locale = Locale::getDefault,
    private val entries: () -> List<KeptEntry> = { KeptEntry.entries },
) : ViewModel() {

    private val ticks = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }

    val state: StateFlow<DumbUiState> = combine(controller.state, ticks) { s, now -> build(s, now) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        build(controller.state.value, clock()),
    )

    private fun build(s: DumbModeState, now: Long): DumbUiState {
        val streak = DumbStreaks.compute(controller.history(), s, now, zone()).days
        return DumbUiStates.build(s, now, zone(), locale(), peopleNames(), streak, entries())
    }

    fun requestLeave() {
        controller.requestLeave()
    }

    fun stay() = controller.stay()

    fun leave(typed: String): LeaveResult = controller.leave(typed)

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext as MidnightApplication
        private val resolver = AndroidKeptAppResolver(app)

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DumbModeViewModel(
                app.dumbMode,
                peopleNames = { app.dumb.people.names() },
                entries = { DumbUiStates.visibleEntries(resolver.whatsappPackage() != null) },
            ) as T
    }
}
