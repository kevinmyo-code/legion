"""A service logged with NO mileage, read by the brief and by `next_service`.

Blocker 3 of the RED review on PR #5. The phone stopped filing its own odometer
ESTIMATE on a logged service (the car plan's slice 0), so `service_history` rows
now legitimately carry a null mileage. `brief.maintenance.schedule_status` took
`max(mileage)` across every row for an item, so the superseded row's mileage went
on standing as the anchor here while the phone had already dropped it - and both
renderers then called the just-logged service OVERDUE, by name, from a figure the
phone no longer held.

The anchor is now BOTH axes of the single most-recently-stated row, the phone's
own rule (`FleetRecordBridge.projectAnchorLegacy`). These tests pin the one
verdict across both renderers over ONE history, so the brief and the tool cannot
drift apart again, and the phone-side half of the same history is pinned by
`FleetAnchorParityTest` on the app side (which runs this file's own history; the predicate itself is
pinned by `VehicleControllerNeedsMileageTest`).
"""

from __future__ import annotations

import datetime as dt

import pytest

from brief.compose import Viewer, _maintenance_lines
from tests.test_engine_mcp import call, client_with

pytestmark = pytest.mark.django_db

VEHICLES = "/api/fleet/vehicles/"
TODAY = dt.date(2026, 10, 15)


@pytest.fixture
def mcp_client(settings, household_user):
    settings.LEGION_MCP = True
    return client_with(household_user)


def _put(client, path, body):
    response = client.put(f"/api/fleet/{path}", body, format="json")
    assert response.status_code == 200, (path, response.data)
    return response.data


def _car(client):
    return _put(
        client,
        "vehicles/car-1/",
        {
            "name": "M3",
            "make": "BMW",
            "model": "M3",
            "year": 2003,
            "confirmed": True,
            "archived": False,
        },
    )["id"]


def _service(client, key, vehicle, name, on, mileage, kind="OBSERVED"):
    """One service history row. `mileage=None` is the honest value for a service
    the driver logged without stating a reading; `on=None` is the honest value for
    an anchor the driver stated with no day attached ("I did it at 232,000"); and
    `kind="ASSERTED"` is a driver's own anchor statement with no event behind it."""
    _put(
        client,
        f"service_history/{key}/",
        {
            "vehicle_id": vehicle,
            "service_name": name,
            "kind": kind,
            "service_date": on.isoformat() if on else None,
            "mileage": mileage,
        },
    )


def _lines(household, member):
    from legacy.models.fleet import Vehicle

    vehicle = Vehicle.objects.get(household=household)
    return _maintenance_lines(Viewer(user=member), vehicle, TODAY)


def _entry(mcp_client):
    is_error, text, structured = call(mcp_client, "next_service", {"date": TODAY.isoformat()})
    assert not is_error, text
    return structured["vehicles"][0], text


def _history_with_a_mileage_less_newest(client):
    """One car, two items, each logged twice: an older service that stated a
    reading, and today's which did not. The odometer has moved on since
    (a trouble-code read at 235,000), so an anchor left standing from the
    superseded row reads as overdue."""
    vehicle = _car(client)
    _put(
        client,
        "code_events/c1/",
        {
            "vehicle_id": vehicle,
            "occurred_at": "2026-10-10T12:00:00Z",
            "mileage": 235_000,
            "codes": ["P0128"],
        },
    )
    # Dual-axis: a months interval AND a miles interval.
    _put(
        client,
        f"maintenance_schedules/{vehicle}/oil change/",
        {
            "interval_miles": 5_000,
            "interval_months": 6,
            "interval_source": "SEEDED",
            "never_done": False,
        },
    )
    # Miles only - legal everywhere, and nothing times it but a reading.
    _put(
        client,
        f"maintenance_schedules/{vehicle}/spark plugs/",
        {"interval_miles": 30_000, "interval_source": "SEEDED", "never_done": False},
    )
    _service(client, "s1", vehicle, "oil change", dt.date(2026, 4, 20), 227_000)
    _service(client, "s2", vehicle, "oil change", TODAY, None)
    _service(client, "s3", vehicle, "spark plugs", dt.date(2025, 1, 10), 71_200)
    _service(client, "s4", vehicle, "spark plugs", TODAY, None)
    return vehicle


# =============================================================================
# The anchor
# =============================================================================


def test_the_anchor_is_the_newest_row_both_axes_not_the_highest_mileage(
    auth_client, household_a, household_user
):
    from brief.maintenance import schedule_status
    from legacy.models.fleet import Vehicle

    _history_with_a_mileage_less_newest(auth_client)
    vehicle = Vehicle.objects.get(household=household_a)

    items = {i.service_name: i for i in schedule_status(Viewer(user=household_user), vehicle)}
    oil = items["oil change"]

    assert oil.last_done_on == TODAY, "the newest row is today's"
    assert oil.last_done_miles is None, "today's row states no reading, so neither does the anchor"
    assert oil.due_at_miles is None, "nothing to add an interval to, so there is no due mileage"
    assert oil.due_on == dt.date(2027, 4, 15), "the date axis still times it, from today"


# =============================================================================
# The brief and the tool, over one history
# =============================================================================


def test_neither_surface_calls_the_just_logged_service_overdue(
    auth_client, mcp_client, household_a, household_user
):
    _history_with_a_mileage_less_newest(auth_client)

    lines = _lines(household_a, household_user)
    entry, text = _entry(mcp_client)

    assert not any("oil change overdue" in line.lower() for line in lines), lines
    assert "oil change" not in [row["service_name"] for row in entry["overdue"]], entry
    assert "232,000" not in " ".join(lines) and "232,000" not in text, (
        "227,000 + 5,000 is an anchor the phone has already dropped"
    )


def test_a_miles_only_item_with_no_reading_is_named_by_both_surfaces(
    auth_client, mcp_client, household_a, household_user
):
    _history_with_a_mileage_less_newest(auth_client)

    lines = _lines(household_a, household_user)
    entry, text = _entry(mcp_client)

    # Not dropped: a `continue` left it out of the brief entirely, which reads as "nothing to
    # report about it".
    assert any("spark plugs" in line for line in lines), lines
    assert entry["needs_mileage"] == ["spark plugs"], entry
    assert "spark plugs" in text
    # And not overdue either - the 71,200 reading is two services old.
    assert "spark plugs" not in [row["service_name"] for row in entry["overdue"]], entry


def test_a_stated_reading_times_the_item_again_on_both_surfaces(
    auth_client, mcp_client, household_a, household_user
):
    vehicle = _history_with_a_mileage_less_newest(auth_client)
    # He gives the reading: the same row, now carrying it.
    _service(auth_client, "s4", vehicle, "spark plugs", TODAY, 235_000)

    lines = _lines(household_a, household_user)
    entry, _text = _entry(mcp_client)

    assert entry["needs_mileage"] == [], entry
    assert entry["next_by_miles"]["service_name"] == "spark plugs"
    assert entry["next_by_miles"]["due_at_miles"] == 265_000
    assert not any("spark plugs" in line for line in lines), (
        "265,000 is well past the brief's horizon, so it is simply not due"
    )


def test_a_miles_ago_backfill_leaves_the_asserted_anchor_standing(
    auth_client, household_a, household_user
):
    """`log_past_service` with "about 8,000 miles ago" plus a date writes BOTH an
    ASSERTED anchor carrying the remembered figure and an OBSERVED event carrying
    none (an OBSERVED row may not hold a figure derived from the live estimate).
    The phone's `explainedBy` does not supersede the anchor - it states a mileage
    the event does not - so both rows survive with the same date, and
    `projectAnchorLegacy` picks the ASSERTED one. This side must pick it too, or
    the two disagree about the same history in words. The phone's half is
    `FleetAnchorParityTest.a miles-ago backfill leaves the ASSERTED anchor
    standing`."""
    from brief.maintenance import schedule_status
    from legacy.models.fleet import Vehicle

    vehicle = _car(auth_client)
    _put(
        auth_client,
        f"maintenance_schedules/{vehicle}/oil change/",
        {"interval_miles": 5_000, "interval_source": "SEEDED", "never_done": False},
    )
    on = dt.date(2026, 4, 20)
    _service(auth_client, "a1", vehicle, "oil change", on, 42_000, kind="ASSERTED")
    _service(auth_client, "s1", vehicle, "oil change", on, None)

    item = schedule_status(
        Viewer(user=household_user), Vehicle.objects.get(household=household_a)
    )[0]

    assert item.last_done_miles == 42_000, "the driver's own anchor statement stands"
    assert item.due_at_miles == 47_000
    assert not item.needs_mileage, "a stated anchor times it, so nothing is owed"


def test_an_undated_asserted_anchor_outranks_an_older_dated_log(
    auth_client, mcp_client, household_a, household_user
):
    """Round 2 blocker A. The mileage-only backfill ("I did the oil change at
    232,000") writes an UNDATED ASSERTED anchor, because the phone's
    `mergeBackfillAnchors` anti-pairing rule refuses to attach a date nobody
    stated. The phone orders on `ServiceRecord.updatedAt` and `setAnchor` stamps
    the wall clock, so that row is the anchor there. Sorting an undated row LAST
    here made this side keep an older dated row's mileage and call the service
    overdue by name, in the spoken brief and in `next_service`, for a service the
    phone times as fine. The phone's half is `FleetAnchorParityTest`."""
    from brief.maintenance import schedule_status
    from legacy.models.fleet import Vehicle

    vehicle = _car(auth_client)
    _put(
        auth_client,
        f"maintenance_schedules/{vehicle}/oil change/",
        {"interval_miles": 5_000, "interval_source": "SEEDED", "never_done": False},
    )
    _put(
        auth_client,
        "code_events/c1/",
        {
            "vehicle_id": vehicle,
            "occurred_at": "2026-10-10T12:00:00Z",
            "mileage": 235_000,
            "codes": ["P0128"],
        },
    )
    # The old dated log first, then today's undated assertion - the real order.
    _service(auth_client, "s1", vehicle, "oil change", dt.date(2025, 1, 10), 71_200)
    _service(auth_client, "a1", vehicle, "oil change", None, 232_000, kind="ASSERTED")

    item = schedule_status(
        Viewer(user=household_user), Vehicle.objects.get(household=household_a)
    )[0]
    lines = _lines(household_a, household_user)
    entry, text = _entry(mcp_client)

    assert item.last_done_miles == 232_000, "the phone's anchor, so this side's anchor"
    assert item.due_at_miles == 237_000
    assert not item.needs_mileage, "a stated anchor times it"
    assert not any("oil change overdue" in line.lower() for line in lines), lines
    assert "oil change" not in [row["service_name"] for row in entry["overdue"]], entry
    assert "76,200" not in " ".join(lines) and "76,200" not in text, (
        "71,200 + 5,000 is an anchor the phone has already dropped"
    )


def test_a_dated_log_today_outranks_an_asserted_anchor_stated_earlier_today(
    auth_client, household_a, household_user
):
    """The other direction, so the fix is an ORDER and not a preference for
    assertions: a hands "mark done" (ASSERTED, no mileage) followed the same day
    by a voice log that carries one. The phone takes the later write; so does
    this side, because a service logged on the day it happened ranks by when it
    was written, exactly as the assertion does."""
    from brief.maintenance import schedule_status
    from legacy.models.fleet import Vehicle

    today = dt.date.today()
    vehicle = _car(auth_client)
    _put(
        auth_client,
        f"maintenance_schedules/{vehicle}/oil change/",
        {"interval_miles": 5_000, "interval_source": "SEEDED", "never_done": False},
    )
    _service(auth_client, "a1", vehicle, "oil change", today, None, kind="ASSERTED")
    _service(auth_client, "s1", vehicle, "oil change", today, 240_000)

    item = schedule_status(
        Viewer(user=household_user), Vehicle.objects.get(household=household_a)
    )[0]

    assert item.last_done_miles == 240_000, "the newer write is the anchor"
    assert item.due_at_miles == 245_000


@pytest.mark.django_db(transaction=True)
def test_a_re_stated_anchor_is_the_newest_row_even_though_its_row_is_old(
    auth_client, household_a, household_user
):
    """The flow blocker B's own fix creates. An item anchored long ago, logged
    again today with no reading (so the anchor goes dark on both sides), and then
    the driver states a reading, which `set_odometer` pins onto the item. The
    phone re-stamps that ASSERTED row's `updatedAt` and clears. Over the wire the
    sync PUTs the SAME deterministic row, so `created_at` still names the first
    statement - ranking on it alone would leave this side saying the item still
    needs a reading after the phone had stopped asking. `transaction=True`
    because the point is real timestamps: inside one test transaction the
    `touch_updated_at` trigger stamps the transaction's start for every row.
    The phone's half is `OdometerAnswersTheMileageAskTest`."""
    from brief.maintenance import schedule_status
    from legacy.models.fleet import Vehicle

    vehicle = _car(auth_client)
    _put(
        auth_client,
        f"maintenance_schedules/{vehicle}/oil change/",
        {"interval_miles": 5_000, "interval_source": "SEEDED", "never_done": False},
    )
    today = dt.date.today()
    # Anchored a year ago, then logged today with no reading.
    old = dt.date(2025, 1, 10)
    _service(auth_client, "a1", vehicle, "oil change", old, 71_200, kind="ASSERTED")
    _service(auth_client, "s1", vehicle, "oil change", today, None)
    anchor = schedule_status(
        Viewer(user=household_user), Vehicle.objects.get(household=household_a)
    )[0]
    assert anchor.needs_mileage, "today's reading-less log is the anchor, so a reading is owed"

    # He answers. set_odometer pins it, which re-states the one ASSERTED row.
    _service(auth_client, "a1", vehicle, "oil change", today, 235_000, kind="ASSERTED")

    item = schedule_status(
        Viewer(user=household_user), Vehicle.objects.get(household=household_a)
    )[0]
    assert item.last_done_miles == 235_000, "the re-stated anchor is the newest statement"
    assert item.due_at_miles == 240_000
    assert not item.needs_mileage, "so this side stops asking, exactly when the phone does"


@pytest.mark.django_db(transaction=True)
def test_logging_the_service_again_with_a_reading_supersedes_a_pinned_one(
    auth_client, household_a, household_user
):
    """The remedy for a reading that got pinned at the wrong figure: log the
    service again with the right one. A fresh OBSERVED row is the newest
    statement on both sides, so it takes the anchor back from the assertion.
    (Editing the row in the history screen does NOT do this on either side:
    `ServiceRecordDao.editMileageAndCost` sets `mileage` and `costCents` and
    leaves `updatedAt` alone, so the edited row does not out-rank a newer
    assertion - the same on the phone and here, which is the point.)
    `transaction=True` for real timestamps. The phone's half is
    `FleetAnchorParityTest.a fresh log with a reading supersedes a pinned one`."""
    from brief.maintenance import schedule_status
    from legacy.models.fleet import Vehicle

    vehicle = _car(auth_client)
    _put(
        auth_client,
        f"maintenance_schedules/{vehicle}/oil change/",
        {"interval_miles": 5_000, "interval_source": "SEEDED", "never_done": False},
    )
    today = dt.date.today()
    _service(auth_client, "s1", vehicle, "oil change", today, None)
    # set_odometer pinned a misheard reading onto the item.
    _service(auth_client, "a1", vehicle, "oil change", today, 335_000, kind="ASSERTED")
    first = schedule_status(
        Viewer(user=household_user), Vehicle.objects.get(household=household_a)
    )[0]
    assert first.last_done_miles == 335_000, "the pin is the anchor until something newer lands"

    # He logs it again, with the reading he meant.
    _service(auth_client, "s2", vehicle, "oil change", today, 235_000)

    item = schedule_status(
        Viewer(user=household_user), Vehicle.objects.get(household=household_a)
    )[0]
    assert item.last_done_miles == 235_000, "the fresh log is the newest statement"
    assert item.due_at_miles == 240_000
