"""Where each maintenance item stands for one vehicle, from the records on
file. One computation, two readers: the brief's Car section
(`brief/compose.py` `_maintenance_lines`) and the engine tool `next_service`
(`engine_mcp/tools.py`), so the two never disagree about what is due.

Every read goes through `household.tenancy.scoped()` with the caller's
request (or the brief job's `Viewer`), ADR 0045's choke point.

**What is a fact and what is an estimate.** A due DATE is the last dated
service plus the schedule's months: arithmetic on dates on file. A due
MILEAGE is the last recorded service mileage plus the schedule's miles, and
"miles to go" is that minus the last odometer reading on file. Neither is a
reading of the car today, and service mileage is itself an estimate field
(`engine_mcp.tools.ESTIMATE_FIELDS`), so anything worked out in miles is said
as an estimate by whoever renders it.
"""

from __future__ import annotations

import calendar
import datetime as dt
from dataclasses import dataclass

from household.tenancy import scoped


def add_months(day: dt.date, months: int) -> dt.date:
    total = day.year * 12 + day.month - 1 + months
    year, month = divmod(total, 12)
    month += 1
    return dt.date(year, month, min(day.day, calendar.monthrange(year, month)[1]))


@dataclass(frozen=True)
class Odometer:
    miles: int
    read_on: dt.date
    # "vehicle" (the reading entered on the car itself), "service" or "code".
    source: str


@dataclass(frozen=True)
class ItemStatus:
    service_name: str
    interval_months: int | None
    interval_miles: int | None
    # No service history row names this item.
    never_logged: bool
    last_done_on: dt.date | None
    last_done_miles: int | None
    # Last dated service plus interval_months; None when either is missing.
    due_on: dt.date | None
    # Last recorded service mileage plus interval_miles; None when either is missing.
    due_at_miles: int | None


def latest_odometer(viewer, vehicle) -> Odometer | None:
    """The highest mileage on file for this vehicle, with the date it was
    recorded: the vehicle's own entered reading (`odometer_baseline`), any
    service history row, or any trouble-code read or clear. A recorded value,
    never a reading of the car today."""
    from legacy.models.fleet import CodeClearEvent, CodeEvent, ServiceHistory

    readings: list[Odometer] = []
    if vehicle.odometer_baseline is not None and vehicle.odometer_baseline_at is not None:
        readings.append(
            Odometer(vehicle.odometer_baseline, vehicle.odometer_baseline_at.date(), "vehicle")
        )
    for row in scoped(ServiceHistory, viewer).filter(
        vehicle=vehicle, deleted_at__isnull=True, mileage__isnull=False
    ):
        readings.append(
            Odometer(row.mileage, row.service_date or row.created_at.date(), "service")
        )
    for model in (CodeEvent, CodeClearEvent):
        for row in scoped(model, viewer).filter(
            vehicle=vehicle, deleted_at__isnull=True, mileage__isnull=False
        ):
            readings.append(Odometer(row.mileage, row.occurred_at.date(), "code"))
    if not readings:
        return None
    return max(readings, key=lambda r: (r.miles, r.read_on))


def schedule_status(viewer, vehicle) -> list[ItemStatus]:
    """Every live schedule item for the vehicle, by name, with where it
    stands. Empty when no schedule is logged."""
    from legacy.models.fleet import MaintenanceSchedule, ServiceHistory

    schedules = list(
        scoped(MaintenanceSchedule, viewer)
        .filter(vehicle=vehicle, deleted_at__isnull=True)
        .order_by("service_name")
    )
    if not schedules:
        return []
    history = list(scoped(ServiceHistory, viewer).filter(vehicle=vehicle, deleted_at__isnull=True))
    out = []
    for schedule in schedules:
        key = schedule.service_name.strip().lower()
        done = [h for h in history if h.service_name.strip().lower() == key]
        dated = [h.service_date for h in done if h.service_date]
        miles = [h.mileage for h in done if h.mileage is not None]
        last_on = max(dated) if dated else None
        last_miles = max(miles) if miles else None
        out.append(
            ItemStatus(
                service_name=schedule.service_name,
                interval_months=schedule.interval_months,
                interval_miles=schedule.interval_miles,
                never_logged=not done,
                last_done_on=last_on,
                last_done_miles=last_miles,
                due_on=(
                    add_months(last_on, schedule.interval_months)
                    if last_on and schedule.interval_months
                    else None
                ),
                due_at_miles=(
                    last_miles + schedule.interval_miles
                    if last_miles is not None and schedule.interval_miles
                    else None
                ),
            )
        )
    return out
