package com.kevin.legion.ui.fleet

/**
 * Pure validation for [com.kevin.legion.ui.CarsScreen]'s manual ADD CAR and RENAME forms
 * (Kevin, 2026-08-13: "i need to be able to manually rename / add cars too"). Same
 * "pure resolver, thin composable wrapper" split as [com.kevin.legion.ui.ledger.LedgerRecategorizeResolver] -
 * kept Compose- and Context-free so it is a plain JUnit test, not a Robolectric one.
 *
 * **This object never writes anything.** Both forms route through the exact functions the
 * `manage_vehicle` voice tool already calls - [com.kevin.legion.vehicle.VehicleController.addVehicle]
 * for ADD, [com.kevin.legion.vehicle.VehicleController.correctVehicle] (name-only, the same shape
 * `manage_vehicle`'s `"rename"` action uses - see `LiveToolbox.manageVehicle`) for RENAME. There is
 * deliberately no second write path here; this file only decides whether the form's CONFIRM button
 * should be enabled, and echoes back a plain-words reason when it is not.
 */
object CarManageResolver {

    /** `null` [error] means the form is ready to submit. */
    data class Validation(val error: String?) {
        val isValid: Boolean get() = error == null
    }

    /**
     * Mirrors [com.kevin.legion.vehicle.VehicleController.addVehicle]'s own precondition (blank
     * make or model is refused, verbatim: `"I need at least a make and model to add a car."`) so the
     * form can disable ADD CAR before ever making the suspend call, rather than showing the
     * controller's refusal string after a round trip that was never going to succeed.
     *
     * [yearText] is the raw text field content: blank is allowed (addVehicle takes `year = 0` as
     * "not stated", same as the seed placeholder), but anything typed that ISN'T a whole number is
     * rejected here rather than silently parsed as 0 - a driver who fat-fingers "202O" for "2020"
     * should be told, not have it land as an undated car.
     *
     * [name] is the optional display name field. A duplicate against [existingLabels] (every OTHER
     * car's [com.kevin.legion.ui.fleet.carLabel], case-insensitive, trimmed) is refused - two rows
     * in the roster reading identically is confusing enough to stop before it happens rather than
     * after. This is separate from, and does not replace, [com.kevin.legion.vehicle.VehicleController.addVehicle]'s
     * own make/model/year duplicate check, which stays authoritative and fires regardless of what
     * this form's name field said.
     */
    fun validateAddCar(
        make: String,
        model: String,
        yearText: String,
        name: String,
        existingLabels: List<String>,
    ): Validation {
        if (make.isBlank()) return Validation("Make is required.")
        if (model.isBlank()) return Validation("Model is required.")
        if (yearText.isNotBlank() && parseYear(yearText) == null) {
            return Validation("Year must be a whole number, e.g. 2020.")
        }
        val trimmedName = name.trim()
        if (trimmedName.isNotEmpty() && existingLabels.any { it.equals(trimmedName, ignoreCase = true) }) {
            return Validation("You've already got a car named \"$trimmedName\".")
        }
        return Validation(null)
    }

    /** [yearText] parsed the same way [validateAddCar] validated it: blank or non-numeric is `null`, never a silent 0. */
    fun parseYear(yearText: String): Int? = yearText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

    /**
     * Validates a RENAME before calling [com.kevin.legion.vehicle.VehicleController.correctVehicle]
     * with only `name` set. Blank or whitespace-only is refused outright here - `correctVehicle`'s
     * own `name?.takeIf { it.isNotBlank() }` would otherwise silently keep the old name and report
     * "Nothing to change", which reads as a bug from the UI rather than a refusal to accept the
     * input. A duplicate of another car's own [com.kevin.legion.ui.fleet.carLabel] is refused for
     * the same "two indistinguishable rows" reason [validateAddCar] gives; [otherLabels] must
     * already exclude the car being renamed, so renaming a car to the name it already has is a
     * no-op the controller itself reports, not a validation failure.
     */
    fun validateRename(newName: String, otherLabels: List<String>): Validation {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return Validation("Name can't be blank.")
        if (otherLabels.any { it.equals(trimmed, ignoreCase = true) }) {
            return Validation("Another car is already named \"$trimmed\".")
        }
        return Validation(null)
    }
}
