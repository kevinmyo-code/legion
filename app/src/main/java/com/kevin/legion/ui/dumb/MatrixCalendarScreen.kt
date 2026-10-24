package com.kevin.legion.ui.dumb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.ZoneId

/**
 * Matrix mode's calendar (matrix-open ticket 04): today and the next seven days as text rows, add / edit
 * / delete an event, tick a task or reminder. Drives [MatrixCalendarViewModel], whose source calls the
 * same controllers as the Soft calendar. Back from the event form returns to the days, then to HOME.
 */
@Composable
internal fun MatrixCalendarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: MatrixCalendarViewModel = viewModel(factory = MatrixCalendarViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val form = state.form
    if (form != null) {
        MatrixScreen(if (form.id == null) "New event" else "Edit event", viewModel::cancelForm) {
            MatrixEventFormBody(form, viewModel)
        }
    } else {
        MatrixScreen("Calendar", onBack) { MatrixAgendaBody(state, viewModel) }
    }
}

@Composable
private fun MatrixAgendaBody(state: MatrixCalendarUiState, viewModel: MatrixCalendarViewModel) {
    val zone = ZoneId.systemDefault()
    MatrixRow("+ New event", { viewModel.startAdd(state.today) })
    state.notice?.let { MatrixNote(it, MatrixTone.BODY) }
    MatrixCalendarText.note(state)?.let {
        MatrixNote(it, if (state.readFailed) MatrixTone.ALERT else MatrixTone.DIM)
    }
    MatrixCalendarText.days(state, zone).forEach { day ->
        MatrixNote(day.heading, MatrixTone.HEADING)
        if (day.rows.isEmpty()) MatrixNote(MatrixCalendarText.NOTHING, MatrixTone.DIM)
        day.rows.forEach { row ->
            MatrixRow(
                MatrixCalendarText.rowLabel(row, zone),
                { viewModel.tapRow(row) },
                tone = MatrixCalendarText.rowTone(row),
                detail = MatrixCalendarText.rowDetail(row),
            )
        }
    }
}

@Composable
private fun MatrixEventFormBody(form: MatrixEventForm, viewModel: MatrixCalendarViewModel) {
    MatrixField("Title", form.title, { v -> viewModel.editForm { it.copy(title = v) } }, viewModel::save)
    MatrixField("Date (2026-10-24)", form.date, { v -> viewModel.editForm { it.copy(date = v) } }, viewModel::save)
    MatrixField(
        "Time (14:30), empty for all day",
        form.time,
        { v -> viewModel.editForm { it.copy(time = v) } },
        viewModel::save,
    )
    form.error?.let { MatrixNote(it, MatrixTone.ALERT) }
    MatrixRow("Save", viewModel::save)
    if (form.id != null) {
        if (form.confirmingDelete) {
            MatrixRow("Tap again to delete \"${form.title}\"", viewModel::confirmDelete, tone = MatrixTone.ALERT)
        } else {
            MatrixRow("Delete this event", viewModel::askDelete, tone = MatrixTone.DIM)
        }
    }
    MatrixRow("Cancel", viewModel::cancelForm, tone = MatrixTone.DIM)
}
