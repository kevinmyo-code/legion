"""Where each maintenance item stands for one vehicle, from the records on
file. One computation, two readers: the brief's Car section
(`brief/compose.py` `_maintenance_lines`) and the engine tool `next_service`
(`engine_mcp/tools.py`), so the two never disagree about what is due.

Every read goes through `household.tenancy.scoped()` with the caller's
request (or the brief job's `Viewer`), ADR 0045's choke point.

**Where "last done" comes from.** Both axes come off ONE row, the newest
service on file for that item (`_newest_first`), never the latest date from
one row paired with the highest mileage from another - the rule the phone's
`FleetRecordBridge.projectAnchorLegacy` holds. A service logged with no
mileage therefore supersedes an older row's mileage instead of letting it
stand, and the item is reported as needing a reading rather than timed from a
figure the phone has dropped.

**What is a fact and what is an estimate.** A due DATE is the last dated
service plus the schedule's months: arithmetic on dates on file. A due
MILEAGE is that row's service mileage plus the schedule's miles, and
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
    # The only interval this item has is a mileage one and no mileage is on file for its newest
    # service, so nothing can bring it due until someone states a reading. The phone computes the
    # same thing from the same history (`VehicleController.needsMileageReading`), and every
    # renderer here says so rather than leaving the item out: "not mentioned" reads as "fine".
    needs_mileage: bool = False


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
        # BOTH axes of the single NEWEST row, never the highest mileage and the latest date picked
        # from different rows - the phone's own rule (`FleetRecordBridge.projectAnchorLegacy`, one
        # row, both axes together); see `_newest_first` for which row that is here.
        # `max(mileage)` across rows was the disagreement: a newer service logged with no reading
        # left the older row's mileage standing as the anchor here while the phone had already
        # dropped it, so this side went on reporting the just-logged item overdue, by name, from a
        # figure the phone no longer held.
        newest = max(done, key=_newest_first) if done else None
        last_on = newest.service_date if newest else None
        last_miles = newest.mileage if newest else None
        timeable = bool(last_on and schedule.interval_months)
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
                needs_mileage=bool(
                    done and schedule.interval_miles and last_miles is None and not timeable
                ),
            )
        )
    return out


def _newest_first(row):
    """Which service history row the anchor comes from: the NEWEST EVENT, then a driver's own
    ASSERTED anchor over an OBSERVED row of the same date, then when the row was written. A row
    with no date at all sorts last - a dated service is a stronger anchor than an undated assertion
    about one.

    The ASSERTED step is what the phone does for one real pair, so it is not a preference invented
    here: a dated `log_past_service` with the "miles ago" phrasing writes an ASSERTED anchor
    carrying the remembered mileage AND an OBSERVED row of the same date carrying none (the
    OBSERVED row may not hold a figure derived from the live estimate), and the phone's
    `projectAnchorLegacy` picks the ASSERTED one because `insertObserved` stamps its `updatedAt`
    with the EVENT date while `setAnchor` stamps the wall clock. Without this step the two sides
    disagree about that history in words.

    Write time is the tie-break and not the primary key, deliberately. The phone orders on its own
    `ServiceRecord.updatedAt`, but the server's `updated_at` cannot carry that order: an INSERT
    stamps `STATEMENT_TIMESTAMP()` (advancing) while the `touch_updated_at` trigger stamps `now()`
    (transaction start), so EDITING a row moves its `updated_at` backward past rows inserted after
    it - which picked a two-services-old row as the anchor the first time this was written that way.

    What matters either way, and what fixes the disagreement with the phone, is that the whole key
    belongs to ONE row: the anchor is never a mileage from one row paired with a date from another,
    and a newer service logged with no reading supersedes an older one's mileage rather than letting
    it stand.

    Known gap, not closed here: an UNDATED ASSERTED anchor stated AFTER a dated OBSERVED log ("I
    did it around 50,000 miles", said the day after logging it) wins on the phone, by write time,
    and loses here, because it has no date to rank with. Closing it needs a write-time order the
    server's own `updated_at` cannot carry (see above), so it is written down rather than guessed
    at."""
    return (
        row.service_date or dt.date.min,
        row.kind == "ASSERTED",
        row.updated_at,
        row.created_at,
    )
