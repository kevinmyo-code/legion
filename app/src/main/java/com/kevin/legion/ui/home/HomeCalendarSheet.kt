package com.kevin.legion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.LocalDate

/** The sheet's share of the screen: enough for the 6-week grid and three agenda rows. */
private const val SHEET_HEIGHT_FRACTION = 0.85f

/**
 * The month grid as a modal sheet (household-finance ticket 13): the calendar HOME used to show
 * whole, header, grid and the selected day's agenda, unchanged. **One sheet, two triggers**: HOME's
 * [Calendar] button and Alfred's `show_agenda_modal` both call
 * `VoiceModalController.show(VoiceModalTarget.AGENDA)`, and [com.kevin.legion.ui.VoiceModalHost]
 * draws this inside its ModalBottomSheet.
 *
 * It reads its own [HomeCalendarViewModel], scoped to whatever hosts the sheet (the activity, since
 * the host sits outside the NavHost), so picking a day here does not move HOME's own agenda.
 * A row or "+N more" closes the sheet and opens that day, the same path HOME's rows take.
 */
@Composable
fun HomeCalendarSheetBody(onOpenDay: (day: LocalDate, reminderId: Long?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val viewModel: HomeCalendarViewModel = viewModel(factory = HomeCalendarViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    SoftTheme {
        HomeCalendarArea(
            state,
            HomeCalendarCallbacks(
                onPreviousMonth = viewModel::previousMonth,
                onNextMonth = viewModel::nextMonth,
                onSelectDay = viewModel::selectDay,
                onOpenRow = { day, row ->
                    onDismiss()
                    onOpenDay(day, row.reminderId)
                },
                onOpenDay = { day ->
                    onDismiss()
                    onOpenDay(day, null)
                },
            ),
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                .background(SoftColors.ground)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
