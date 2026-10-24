package com.kevin.legion.ui.dumb

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.car.CarAspectSummaries
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One car on the Matrix fleet screen. [status] is the Fleet row's own sentence ("mileage - what is due
 * next", guess caveats included). [unknown] says the items it cannot rank. [failed] means this car's
 * maintenance schedule could not be read, which is a different thing from "no schedule yet".
 */
internal data class MatrixCar(val label: String, val status: String, val unknown: String?, val failed: Boolean)

/** The data seam under the Matrix fleet. Reads only. */
internal interface MatrixFleetSource {
    /** Every car. Throws when the car list cannot be read; a car whose schedule fails comes back [MatrixCar.failed]. */
    suspend fun cars(): List<MatrixCar>
}

/** Everything the Matrix fleet draws. [readFailed] is "could not read the cars", never "no cars". */
internal data class MatrixFleetUiState(
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val cars: List<MatrixCar> = emptyList(),
)

/** Matrix mode's fleet lookups ViewModel (matrix-open ticket 06): cars and the next maintenance on each. */
internal class MatrixFleetViewModel(
    private val source: MatrixFleetSource,
    scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(MatrixFleetUiState())
    val state: StateFlow<MatrixFleetUiState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val read = withContext(io) { runCatching { source.cars() } }
            _state.update { current ->
                current.copy(loading = false, readFailed = read.isFailure, cars = read.getOrDefault(current.cars))
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MatrixFleetViewModel(AndroidMatrixFleetSource(app)) as T
    }
}

/**
 * [MatrixFleetSource] over [VehicleController], the read funnel the Soft Fleet screen uses. The status
 * sentence is [CarAspectSummaries.serviceSubtitle], the same function the Fleet tab row calls.
 */
internal class AndroidMatrixFleetSource(private val context: Context) : MatrixFleetSource {
    @Suppress("TooGenericExceptionCaught") // one car's failed schedule read is shown on that car, not hidden
    override suspend fun cars(): List<MatrixCar> =
        VehicleController.allVehicles(context).map { vehicle ->
            val label = VehicleController.label(vehicle)
            val mileage = VehicleController.mileageLabel(vehicle).ifBlank { "odometer not set" }
            try {
                MatrixFleetText.carOf(label, mileage, VehicleController.nextService(context, vehicle))
            } catch (e: Exception) {
                android.util.Log.w("MatrixFleet", "nextService failed for $label: ${e.message}")
                MatrixFleetText.unreadableCar(label, mileage)
            }
        }
}
