package com.kevin.legion.ui.dumb

import com.kevin.legion.car.CarAspectSummaries
import com.kevin.legion.vehicle.VehicleController

/**
 * The words on the Matrix fleet lookups (matrix-open ticket 06), pure so they are tested. A mileage that
 * is an estimate says so (it arrives already worded by `VehicleController.mileageLabel`), an interval
 * that is a guess says "guess, unconfirmed", items with no anchor are named as not known rather than
 * dropped, and a car whose schedule could not be read says that instead of "no schedule yet".
 */
internal object MatrixFleetText {
    const val LOADING = "Reading your cars..."
    const val UNREADABLE = "Couldn't read your cars. This is not an empty garage: nothing could be read."
    const val STALE = "Couldn't refresh your cars. These are the last ones read and may be out of date."
    const val EMPTY = "No cars yet."

    fun carOf(label: String, mileageLabel: String, next: VehicleController.NextService?): MatrixCar =
        MatrixCar(
            label = label,
            status = CarAspectSummaries.serviceSubtitle(mileageLabel, next),
            unknown = unknownNote(next),
            failed = false,
        )

    /**
     * The two shapes of "not known to be fine", in one note because this screen has one slot for
     * them: items with no last-done at all, and items whose only interval is a mileage one and
     * whose newest service carries no reading ([needsMileageReading]). The second
     * is named for what it needs - a reading - because that is the thing the driver can supply.
     */
    private fun unknownNote(next: VehicleController.NextService?): String? {
        if (next == null) return null
        val parts = buildList {
            if (next.unknownCount > 0) {
                add(
                    "${next.unknownCount} maintenance item(s) have no recorded last-done date " +
                        "(${next.unknownNames.joinToString(", ")}). They are not counted as due, " +
                        "and not known to be fine.",
                )
            }
            if (next.needsMileage.isNotEmpty()) {
                add(
                    "No mileage reading against ${next.needsMileage.joinToString(", ")}, so nothing times " +
                        "${if (next.needsMileage.size == 1) "it" else "them"} by miles. Say your mileage and it will.",
                )
            }
        }
        return parts.joinToString(" ").ifBlank { null }
    }

    fun unreadableCar(label: String, mileageLabel: String): MatrixCar = MatrixCar(
        label = label,
        status = "$mileageLabel - couldn't read the maintenance schedule. This is not \"no schedule yet\".",
        unknown = null,
        failed = true,
    )

    fun listNote(state: MatrixFleetUiState): MatrixTextLine? = when {
        state.loading -> MatrixTextLine(LOADING, MatrixTone.DIM)
        state.readFailed && state.cars.isEmpty() -> MatrixTextLine(UNREADABLE, MatrixTone.ALERT)
        state.readFailed -> MatrixTextLine(STALE, MatrixTone.WARNING)
        state.cars.isEmpty() -> MatrixTextLine(EMPTY, MatrixTone.DIM)
        else -> null
    }
}
