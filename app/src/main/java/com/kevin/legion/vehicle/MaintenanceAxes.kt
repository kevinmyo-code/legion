package com.kevin.legion.vehicle

import com.kevin.legion.data.local.MaintenanceItem

/**
 * Which axis can actually bring a maintenance item due, when one of them is dark.
 *
 * Its own file rather than another member of [VehicleController]: that object is the project's
 * largest and detekt holds a class-size ceiling on it (CLAUDE.md's 1000-line rule), and this is a
 * pure predicate over one entity with no `Context`, no Room and no state - the same split
 * [LogServiceSpeech]'s copy already took out of the same object. Top-level and `internal` in this
 * package, so the controller calls it unqualified and `ui.fleet` imports it; imports point inward,
 * never from here out to the UI.
 */

/**
 * True when the only interval this item has is a MILEAGE one and its mileage anchor is unknown, so
 * nothing can ever bring it due - the car plan's slice 0 shape, made visible instead of silent.
 *
 * The case: an item with `intervalMiles` and no `intervalMonths` (legal everywhere -
 * [VehicleController.setMaintenanceInterval] requires only one of the two, and the factory-schedule
 * prompt emits `intervalMonths` as null) whose newest service row carries no mileage, which is what
 * a plain "I got my oil changed today" now writes. [VehicleController.isDue]'s mileage axis needs
 * [MaintenanceItem.lastDoneMileage] and its time axis needs [MaintenanceItem.intervalMonths], so the
 * item is never due; [VehicleController.isUnknown] is false because the DATE anchor is set. It would
 * simply drop off the schedule, and - worse - [VehicleController.computeNextService] would count it
 * on neither axis nor as unknown, which is exactly the condition
 * [VehicleController.NextService.allDue] reads as "everything is already due".
 *
 * So it is reported, on the axis that is actually missing: the item is known to have been done, the
 * reading is not known, and the honest move is to ask for the reading rather than to time it by a
 * date nobody can time it by. An item that CAN be timed by date (both an `intervalMonths` and a
 * `lastDoneDate`) is not this case - the date axis genuinely times it, and only its mileage axis is
 * dark. [VehicleController.isUnknown]'s own case (no anchor at all) is not this one either; the two
 * never overlap, so [VehicleController.NextService.unknownNames] and
 * [VehicleController.NextService.needsMileage] never name the same item twice.
 */
internal fun needsMileageReading(item: MaintenanceItem): Boolean {
    if (item.neverDone || VehicleController.isUnknown(item)) return false
    val timeable = item.intervalMonths != null && item.lastDoneDate != null
    return item.intervalMiles != null && item.lastDoneMileage == null && !timeable
}
