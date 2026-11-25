"""Where each maintenance item stands for one vehicle, from the records on
file. One computation, two readers: the brief's Car section
(`brief/compose.py` `_maintenance_lines`) and the engine tool `next_service`
(`engine_mcp/tools.py`), so the two never disagree about what is due.

Every read goes through `household.tenancy.scoped()` with the caller's
request (or the brief job's `Viewer`), ADR 0045's choke point.

**Where "last done" comes from.** Both axes come off ONE row, the most
recently STATED service on file for that item (`_newest_first`, which ranks on
`_stated_at`), never the latest date from one row paired with the highest
mileage from another - the rule the phone's
`FleetRecordBridge.projectAnchorLegacy` holds, ordering on its own
`ServiceRecord.updatedAt`, which `_stated_at` reproduces. A service logged with no
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

from django.utils import timezone

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
    history = list(
        scoped(ServiceHistory, viewer)
        .filter(vehicle=vehicle, deleted_at__isnull=True)
        .order_by("created_at", "id")
    )
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
        # `is not None`, never truthiness: the phone tests `!= null`, so an interval stored as 0
        # (meaningless, but storable) must not read as "no interval" on one side and as one on the
        # other. Round 2 finding, non-blocking and one line.
        timeable = last_on is not None and schedule.interval_months is not None
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
                # The phone's [needsMileageReading] exactly: a miles interval on file, no mileage
                # anchor, and no date timing. `last_on is not None` is the half that keeps the two
                # lists disjoint the way the phone's `isUnknown` check does - a row carrying
                # NEITHER a date nor a mileage leaves the item unknown, not waiting for a reading.
                # No live row has that shape today, which is why nothing here renders it: an
                # OBSERVED row is inserted with a non-null date (`insertObserved`'s signature), and
                # an assertion with neither axis soft-deletes its row rather than writing a
                # null/null one (`writeAssertedAnchorLegacy`). Traced, not assumed.
                needs_mileage=(
                    bool(done)
                    and schedule.interval_miles is not None
                    and last_miles is None
                    and last_on is not None
                    and not timeable
                ),
            )
        )
    return out


def _stated_at(row) -> dt.datetime:
    """WHEN this row was last stated, in the phone's own terms - its
    `ServiceRecord.updatedAt`, restated against this schema. That field is what
    `FleetRecordBridge.projectAnchorLegacy` orders on, so reproducing it is what makes the two
    sides name the same anchor, and the phone stamps it from TWO different clocks:

    - `FleetEngineStore.insertObserved` stamps it with the EVENT date. For a service logged on the
      day it happened that date IS the moment of writing; for a backdated log it is the past day.
    - `FleetEngineStore.setAnchor` stamps the WALL CLOCK, because an assertion has no event behind
      it to date, and re-stamps it every time the driver re-states the anchor. An undated
      assertion is still a statement made at a moment.

    So: an ASSERTED row, and an OBSERVED row whose service date is the day it was written, rank by
    when the row was written; a BACKDATED observation ranks by the day the work happened. No row
    is pushed to the bottom for lacking a date, which is what round 2's blocker A was - an undated
    ASSERTED anchor written today lost to a dated OBSERVED row from last year here while winning on
    the phone, so this side called a just-anchored service overdue by name in the spoken brief.
    Two writers produce that undated row: the hands "mark done" save with a mileage and no date
    (`ui/fleet/MaintenanceWrites.kt`'s `writeSetAnchor`, which passes the form's date through as
    given), and a mileage-only `log_past_service` on an item with no logged record yet - that call
    refuses once a record exists, so the hands path is the one that reaches the shape this fixture
    holds.

    The server's own `updated_at` cannot stand in for the phone's. An INSERT stamps
    `STATEMENT_TIMESTAMP()` (advancing) while the `touch_updated_at` trigger stamps `now()`
    (transaction start), so EDITING a row moves its `updated_at` backward past rows inserted after
    it - measured, and it picked a two-services-old row as the anchor the first time this was
    written that way. `created_at` is only ever stamped once, so it is the honest write time.

    Gap left open: a DELAYED sync. These timestamps are when the engine was told, not when the
    phone wrote, so two writes made offline in one order and synced in the other rank by the sync.
    Closing it needs the phone's own stamp on the wire, which `ServiceHistoryWriteDto` does not
    carry."""
    written = row.created_at
    # An ASSERTED row is RE-STATED IN PLACE, so it ranks by the later of its two timestamps.
    # `writeAssertedAnchorLegacy` keeps ONE ASSERTED row per item and rewrites it, so the sync's
    # PUT updates that row and `created_at` still names the FIRST statement - and blocker B's own
    # flow re-states exactly that row (a reading pinned onto an item anchored long ago). Without
    # this, the brief would go on asking for a reading the phone had already taken.
    #
    # `max` and not `updated_at` alone because the trigger stamps transaction start while an INSERT
    # stamps the statement timestamp, so on a freshly inserted row `updated_at` can sit just behind
    # `created_at` - which picked a two-services-old row the first time this was written that way.
    # Taking the later of the two can only move a row FORWARD, never behind rows inserted after it.
    #
    # It does mean any write to an old ASSERTED row makes it the anchor again. Checked rather than
    # assumed: `syncAssertedAnchorToServer` has exactly two callers, `setAnchor` and
    # `upsertNewItem`, both of them real anchor writes, so nothing re-PUTs an unchanged anchor row.
    #
    # An OBSERVED row is deliberately NOT ranked on `updated_at`: the phone's own edit of a row's
    # mileage (`ServiceRecordDao.editMileageAndCost`) sets `mileage` and `costCents` and does NOT
    # re-stamp `updatedAt`, so an edited row does not become the anchor there either. Promoting it
    # here would be this side inventing an order the phone does not have.
    if row.kind == "ASSERTED" or row.service_date is None:
        return max(written, row.updated_at)
    # Written on the day it records (within a day): the row was stated at the event, so when it
    # was written IS the moment the phone stamps. The day of slack is deliberate - this side keeps
    # UTC timestamps while `service_date` is the household's own calendar day, so an evening log
    # is written after midnight UTC and would otherwise read as backdated and rank below an
    # assertion made before it. Measured: that is the case that failed when this compared dates
    # exactly. Anything older than that is a real backdate and ranks by the day the work happened.
    if row.service_date >= timezone.localtime(written).date() - dt.timedelta(days=1):
        return written
    return timezone.make_aware(dt.datetime.combine(row.service_date, dt.time.min))


def _newest_first(row):
    """Which service history row the anchor comes from: the most recently STATED one
    ([_stated_at]), then when it was written for an exact tie.

    What matters beyond the order, and what fixed the first disagreement with the phone, is that
    the whole key belongs to ONE row: the anchor is never a mileage from one row paired with a date
    from another, and a newer service logged with no reading supersedes an older one's mileage
    rather than letting it stand."""
    return (_stated_at(row), row.created_at)
