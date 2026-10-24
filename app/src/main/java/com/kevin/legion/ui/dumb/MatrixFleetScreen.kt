package com.kevin.legion.ui.dumb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Matrix mode's fleet (matrix-open ticket 06): each car with its mileage and the next maintenance due,
 * as text. Read only. The sentences are the Fleet tab row's own ([MatrixFleetText]).
 */
@Composable
internal fun MatrixFleetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: MatrixFleetViewModel = viewModel(factory = MatrixFleetViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    MatrixScreen("Fleet", onBack) {
        MatrixFleetText.listNote(state)?.let { MatrixNote(it.text, it.tone) }
        state.cars.forEach { car ->
            MatrixInfoRow(
                car.label,
                tone = if (car.failed) MatrixTone.ALERT else MatrixTone.HEADING,
                detail = listOfNotNull(car.status, car.unknown).joinToString("\n"),
            )
        }
    }
}
