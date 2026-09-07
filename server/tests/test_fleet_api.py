"""`/api/fleet/*` - django-engine ticket 04's last aspect, and the one that is
not one shape.

`tests/test_synced_contract.py` runs one set of assertions over sixteen tables
because those sixteen genuinely are one shape. Fleet is not, so it gets its own
module: eleven tables across FIVE identity shapes, plus `obd_samples`, which is
none of them. The parametrized half below re-runs the synced contract per shape
(PUT creates, PUT again is idempotent, DELETE tombstones, `?active=1` omits it,
`?since=` windows); the rest is what only fleet has.

## Why these tests count matching ids instead of rows

Every fleet table but `chassis_quirks` has a `vehicle_id` foreign key, so
every test needs a vehicle before it can write anything - and a vehicle is
itself a row in `/api/fleet/vehicles/`. An assertion like
`len(feed["results"]) == 2` is therefore true for ten tables and false for
`vehicles`, where the fixture's own two rows are also in the feed. Rather than
special-case that one table (and quietly weaken the assertion everywhere else),
each test looks for the ids it created and asserts about those. It is the same
claim, and it does not depend on the table being empty when the test started.

## The clock trap

`tests/test_synced_contract.py`'s own module doc explains it at length and it
applies here identically: an INSERT stamps `Now()`
(`STATEMENT_TIMESTAMP()`, advancing) while the `touch_updated_at` trigger
stamps `now()` (transaction start, fixed), and pytest-django wraps each test in
ONE transaction - so inside a test an updated row's `updated_at` moves BACKWARD
relative to rows inserted before it. Consequence, obeyed below: a watermark
taken from a created row is only ever compared against other CREATED rows, and
a tombstone is looked for from an epoch watermark.

`obd_samples` is exempt from the whole trap, and pleasantly so: its feed is
keyed on `recorded_at`, a value the CLIENT states, so nothing in those tests
depends on either clock.
"""
from __future__ import annotations

import uuid
from typing import NamedTuple

import pytest
from rest_framework.test import APIClient

pytestmark = pytest.mark.django_db

EPOCH = "1970-01-01T00:00:00Z"

FLEET = "/api/fleet/"
VEHICLES = f"{FLEET}vehicles/"
OBD = f"{FLEET}obd_samples/"
OBD_BATCH = f"{OBD}batch/"
OBD_COUNT = f"{OBD}count/"


def _iso(day: int, hour: int = 12) -> str:
    return f"2026-09-{day:02d}T{hour:02d}:00:00Z"


def a_vehicle(i: int = 0) -> dict:
    return {
        "name": f"M3 {i}",
        "make": "BMW",
        "model": "M3",
        "year": 2003 + i,
        "confirmed": True,
        "archived": False,
    }


class Fleet(NamedTuple):
    """Two vehicles, created through the real route rather than the ORM.

    Two rather than one because `drive_reassignments` needs a `vehicle_id` and
    a DIFFERENT `new_vehicle_id` to be the correction it claims to be, and
    because `vehicle_specs` is keyed BY the vehicle - two spec rows need two
    cars, which is the whole point of shape 4 and is asserted directly further
    down.
    """

    vehicle: str
    other: str

    @property
    def both(self) -> tuple[str, str]:
        return (self.vehicle, self.other)


@pytest.fixture
def fleet(auth_client) -> Fleet:
    made = []
    for i in range(2):
        response = auth_client.put(
            f"{VEHICLES}fixture-vehicle-{i}/", a_vehicle(i), format="json"
        )
        assert response.status_code == 200, response.data
        made.append(str(response.data["id"]))
    return Fleet(*made)


# =============================================================================
# One entry per routed fleet table. `detail` builds the path segment(s) AFTER
# the collection URL, which is where the five identity shapes actually differ.
# =============================================================================


class Table(NamedTuple):
    name: str
    # (fleet, n) -> everything after `/api/fleet/<name>/`, trailing slash
    # included. `n` distinguishes two rows of the same table in one test.
    detail: object
    # (fleet, i) -> the request body. `i` varies a NON-identity field so an
    # update can be told from a create.
    payload: object
    # False for the two tables with no `deleted_at` column at all.
    deletable: bool = True
    # False for the two tables whose primary key is their identity, so they
    # have no separate `id` column on the wire.
    has_id: bool = True

    @property
    def url(self) -> str:
        return f"{FLEET}{self.name}/"


TABLES = [
    # -- shape 1: origin_guid ------------------------------------------------
    Table(
        "vehicles",
        lambda f, n: f"guid-{n}/",
        # No `origin_guid` in the body: it is the identity, the URL carries it,
        # and the viewset overwrites anything a body claims - same as `places`
        # and its label.
        lambda f, i: a_vehicle(i),
    ),
    Table(
        "service_history",
        lambda f, n: f"guid-{n}/",
        lambda f, i: {
            "vehicle_id": f.vehicle,
            "service_name": f"oil change {i}",
            "kind": "OBSERVED",
            "mileage": 100000 + i,
            "cost_cents": 8999,
        },
    ),
    # -- shape 2: sync_id ----------------------------------------------------
    Table(
        "drives",
        lambda f, n: f"sync-{n}/",
        lambda f, i: {
            "vehicle_id": f.vehicle,
            "started_at": _iso(1),
            "ended_at": _iso(2),
            "miles": 12.5 + i,
            "gallons": 0.5,
            "end_reason": "ENGINE_OFF",
        },
    ),
    Table(
        "code_events",
        lambda f, n: f"sync-{n}/",
        lambda f, i: {
            "vehicle_id": f.vehicle,
            "occurred_at": _iso(1 + i),
            "mileage": 100000 + i,
            "codes": ["P0420", "P0128"],
            "freeze_frame": {"rpm": 800},
        },
    ),
    Table(
        "code_clear_events",
        lambda f, n: f"sync-{n}/",
        lambda f, i: {
            "vehicle_id": f.vehicle,
            "occurred_at": _iso(1 + i),
            "mileage": 100000 + i,
            "codes_before": ["P0420"],
            "codes_after": [],
            "outcome": "CLEARED",
            "ack_raw": "OK",
        },
    ),
    Table(
        "oil_analyses",
        lambda f, n: f"sync-{n}/",
        lambda f, i: {
            "vehicle_id": f.vehicle,
            "analyzed_at": _iso(1 + i),
            "mileage": 50000 + i,
            "oil_brand": "Mobil 1",
            "oil_grade": "5W-30",
            "iron": 12,
            "tbn": 6.5,
            "lab_notes": "",
        },
    ),
    Table(
        "build_entries",
        lambda f, n: f"sync-{n}/",
        lambda f, i: {
            "vehicle_id": f.vehicle,
            "entry_type": "part",
            "title": f"coilovers {i}",
            "vendor": "Bilstein",
            "part_number": "B14",
            "cost_cents": 129900,
            "logged_at": _iso(1 + i),
            "mileage": 90000,
            "notes": "",
        },
    ),
    Table(
        "drive_reassignments",
        lambda f, n: f"sync-{n}/",
        lambda f, i: {
            "vehicle_id": f.vehicle,
            "new_vehicle_id": f.other,
            "from_at": _iso(1),
            "to_at": _iso(2 + i),
        },
    ),
    # -- shape 3: a natural text primary key ---------------------------------
    Table(
        "chassis_quirks",
        lambda f, n: f"e46-rod-bearings-{n}/",
        # No `vehicle_id` anywhere in this payload, and that is the shape
        # rather than an omission - see `test_chassis_quirks_are_household_
        # shared_not_per_vehicle`.
        lambda f, i: {
            "chassis": "E46,E46M3",
            "engine": "S54",
            "title": f"rod bearing wear {i}",
            "symptom": "knock on cold start",
            "verification_steps": "drop the pan, inspect bearing shells",
            "mileage_low": 60000,
            "mileage_high": 120000,
            "severity": "CRITICAL",
            "cost_low_cents": 150000,
            "cost_high_cents": 400000,
            "fix_notes": "",
            "source_url": "",
        },
        deletable=False,
        has_id=False,
    ),
    # -- shape 4: a primary key that is also a foreign key --------------------
    Table(
        "vehicle_specs",
        # The identity IS a vehicle, so two rows need two vehicles.
        lambda f, n: f"{f.both[n]}/",
        lambda f, i: {
            "vin": f"WBSBL934{i}5JR00000",
            "engine_cylinders": 6,
            "displacement_l": 3.2,
            "engine_hp": 333,
            "engine_config": "In-Line",
            "fuel_type": "Gasoline",
            "transmission_style": "Manual",
            "transmission_speeds": "6",
            "drive_type": "RWD",
            "body_class": "Coupe",
            "doors": 2,
            "series": "M3",
            "vehicle_type": "PASSENGER CAR",
            "manufacturer": "BMW AG",
            "plant_city": "Regensburg",
            "plant_country": "GERMANY",
            "paint_color": "Laguna Seca Blue",
            "paint_code": "448",
            "build_notes": "",
        },
        deletable=False,
        has_id=False,
    ),
    # -- shape 5: a composite key --------------------------------------------
    Table(
        "maintenance_schedules",
        # TWO segments. Neither half of the pair is in the payload: both come
        # from the URL, exactly as `sync_id` and `origin_guid` do above.
        lambda f, n: f"{f.vehicle}/oil change {n}/",
        lambda f, i: {
            "interval_miles": 5000 + i,
            "interval_months": 6,
            "interval_source": "SEEDED",
            "never_done": False,
        },
    ),
]

DELETABLE = [t for t in TABLES if t.deletable]

ALL_LIST_ROUTES = [t.url for t in TABLES] + [OBD, OBD_BATCH, OBD_COUNT]


def _param(tables):
    return [pytest.param(t, id=t.name) for t in tables]


# =============================================================================
# Authentication. Every route, including the three that are not synced tables.
# =============================================================================


@pytest.mark.parametrize("url", ALL_LIST_ROUTES)
def test_unauthenticated_get_is_401(url):
    assert APIClient().get(url).status_code == 401


@pytest.mark.parametrize("table", _param(TABLES))
def test_unauthenticated_put_is_401(table):
    """DRF authenticates in `initial()`, before it picks a handler, so an
    anonymous caller learns it is anonymous and NOT which methods a route
    would have allowed. A 405 here would tell a stranger the shape of the
    API.

    **The identity has to be well FORMED, even though it names nothing.** Two
    of these routes use Django's `uuid` path converter (`vehicle_specs`, and
    the first segment of `maintenance_schedules`), and a path the converter
    does not match never reaches a view at all - it is a 404 from the URL
    resolver, raised before authentication runs. That is a real ordering and
    not a bug worth changing: what it discloses to an anonymous caller is that
    a path was not a uuid, which they already knew. It is written down because
    a test that used `anything/` here would fail for that reason and look like
    an authentication hole.
    """
    unreal = Fleet(str(uuid.uuid4()), str(uuid.uuid4()))
    client = APIClient()
    response = client.put(f"{table.url}{table.detail(unreal, 0)}", {}, format="json")
    assert response.status_code == 401


def test_unauthenticated_obd_batch_is_401():
    assert APIClient().post(OBD_BATCH, [], format="json").status_code == 401


# =============================================================================
# The synced contract, once per identity shape.
# =============================================================================


@pytest.mark.parametrize("table", _param(TABLES))
def test_put_creates_and_returns_the_row_as_stored(auth_client, fleet, table):
    body = table.payload(fleet, 0)
    response = auth_client.put(f"{table.url}{table.detail(fleet, 0)}", body, format="json")
    assert response.status_code == 200, response.data

    stored = response.data
    # Server facts, filled in by the server and none of them accepted from the
    # caller.
    assert stored["created_at"] is not None
    assert stored["updated_at"] is not None
    assert stored["provenance"] in {"USER", "DETERMINISTIC"}
    if table.has_id:
        assert stored["id"]
    else:
        # Shapes 3 and 4 have no separate `id` column, so the API must not
        # invent one - a client keying a cache on it would be keying on a lie.
        assert "id" not in stored
    if table.deletable:
        assert stored["deleted_at"] is None
    else:
        assert "deleted_at" not in stored

    # "The response body is the row AS STORED", so a value the server silently
    # altered shows up here.
    for field, value in body.items():
        assert str(stored[field]) == str(value), field


@pytest.mark.parametrize("table", _param(TABLES))
def test_repeated_put_is_idempotent(auth_client, fleet, table):
    """The phone retries, and for the six `sync_id` tables a retry is the
    NORMAL case rather than the exceptional one - `FleetBackend`'s own doc for
    them is "no update, no delete, so a repost is always free". A second PUT
    under the same identity updates the one row and never makes a second."""
    path = f"{table.url}{table.detail(fleet, 0)}"
    first = auth_client.put(path, table.payload(fleet, 0), format="json")
    assert first.status_code == 200, first.data

    second = auth_client.put(path, table.payload(fleet, 0), format="json")
    assert second.status_code == 200, second.data

    if table.has_id:
        assert second.data["id"] == first.data["id"]

    # Exactly one row carries this identity. Counted by identity rather than by
    # feed length - see this module's own doc comment.
    feed = auth_client.get(f"{table.url}?since={EPOCH}").data["results"]
    key = table.name.rstrip("s")
    matching = [row for row in feed if _identity_of(table, row) == _identity_of(table, first.data)]
    assert len(matching) == 1, f"{key}: {matching}"


def _identity_of(table: Table, row: dict) -> tuple:
    """Whatever uniquely names this row, per shape. Used only to count rows in
    a feed without depending on the table being empty to start with."""
    if table.name == "maintenance_schedules":
        return (str(row["vehicle_id"]), row["service_name"])
    if table.name == "chassis_quirks":
        return (row["quirk_id"],)
    if table.name == "vehicle_specs":
        return (str(row["vehicle_id"]),)
    return (str(row["id"]),)


@pytest.mark.parametrize("table", _param(TABLES))
def test_put_updates_an_existing_row_in_place(auth_client, fleet, table):
    path = f"{table.url}{table.detail(fleet, 0)}"
    created = auth_client.put(path, table.payload(fleet, 0), format="json").data
    updated = auth_client.put(path, table.payload(fleet, 1), format="json")
    assert updated.status_code == 200, updated.data
    if table.has_id:
        assert updated.data["id"] == created["id"]
    for field, value in table.payload(fleet, 1).items():
        assert str(updated.data[field]) == str(value), field


@pytest.mark.parametrize("table", _param(DELETABLE))
def test_delete_tombstones_rather_than_deleting(auth_client, fleet, table):
    gone = auth_client.put(
        f"{table.url}{table.detail(fleet, 0)}", table.payload(fleet, 0), format="json"
    ).data

    assert auth_client.delete(f"{table.url}{table.detail(fleet, 0)}").status_code == 204

    # The row is still there, carrying a tombstone - a phone that has not
    # synced since still has to learn it is gone.
    feed = auth_client.get(f"{table.url}?since={EPOCH}").data["results"]
    tombstoned = [
        row for row in feed if _identity_of(table, row) == _identity_of(table, gone)
    ]
    assert len(tombstoned) == 1
    assert tombstoned[0]["deleted_at"] is not None

    # Idempotent: a second delete is still a 204.
    assert auth_client.delete(f"{table.url}{table.detail(fleet, 0)}").status_code == 204


@pytest.mark.parametrize("table", _param(DELETABLE))
def test_active_omits_tombstones(auth_client, fleet, table):
    gone = auth_client.put(
        f"{table.url}{table.detail(fleet, 0)}", table.payload(fleet, 0), format="json"
    ).data
    kept = auth_client.put(
        f"{table.url}{table.detail(fleet, 1)}", table.payload(fleet, 1), format="json"
    ).data
    auth_client.delete(f"{table.url}{table.detail(fleet, 0)}")

    identities = [
        _identity_of(table, row)
        for row in auth_client.get(f"{table.url}?active=1").data["results"]
    ]
    assert _identity_of(table, kept) in identities
    assert _identity_of(table, gone) not in identities


@pytest.mark.parametrize("table", _param(TABLES))
def test_since_feed_omits_rows_changed_before_the_watermark(auth_client, fleet, table):
    """Both timestamps in this comparison come from the DATABASE clock - see
    this module's own doc comment for why that matters."""
    older = auth_client.put(
        f"{table.url}{table.detail(fleet, 0)}", table.payload(fleet, 0), format="json"
    ).data
    newer = auth_client.put(
        f"{table.url}{table.detail(fleet, 1)}", table.payload(fleet, 1), format="json"
    ).data

    feed = auth_client.get(f"{table.url}?since={newer['updated_at']}").data["results"]
    identities = [_identity_of(table, row) for row in feed]
    # `since` is inclusive (>=), matching the phone's own fetchChangedSince
    # contract, so the row created AT the watermark is legitimately present and
    # the one before it is not.
    assert _identity_of(table, newer) in identities
    assert _identity_of(table, older) not in identities


@pytest.mark.parametrize("table", _param(TABLES))
def test_unknown_field_is_400_naming_the_field(auth_client, fleet, table):
    body = table.payload(fleet, 0) | {"vibe": "excellent"}
    response = auth_client.put(
        f"{table.url}{table.detail(fleet, 0)}", body, format="json"
    )
    assert response.status_code == 400
    assert "vibe" in str(response.data)


# =============================================================================
# Each shape's own identity claim, asserted directly rather than inferred from
# the parametrized tests passing.
# =============================================================================


def test_origin_guid_is_the_identity_for_vehicles(auth_client, fleet):
    """Shape 1. Two PUTs under one guid are one row; the guid comes back on
    the row so a client can match its own record to the server's."""
    first = auth_client.put(f"{VEHICLES}engine-guid-a/", a_vehicle(0), format="json").data
    again = auth_client.put(f"{VEHICLES}engine-guid-a/", a_vehicle(1), format="json").data
    assert first["origin_guid"] == "engine-guid-a"
    assert again["id"] == first["id"]
    assert again["name"] == "M3 1"


def test_sync_id_is_the_identity_for_drives(auth_client, fleet):
    """Shape 2. The phone mints `sync_id` before it ever talks to a server, so
    the same drive uploaded twice is one row - which is what makes an
    unacknowledged upload safe to retry."""
    body = {
        "vehicle_id": fleet.vehicle,
        "started_at": _iso(1),
        "ended_at": _iso(2),
        "miles": 42.0,
        "gallons": 1.5,
        "end_reason": "ENGINE_OFF",
    }
    first = auth_client.put(f"{FLEET}drives/drive-777/", body, format="json").data
    again = auth_client.put(f"{FLEET}drives/drive-777/", body, format="json").data
    assert first["sync_id"] == "drive-777"
    assert again["id"] == first["id"]
    assert len(auth_client.get(f"{FLEET}drives/").data["results"]) == 1


def test_chassis_quirks_are_household_shared_not_per_vehicle(auth_client, fleet):
    """Shape 3, and the claim in its name.

    `chassis_quirks` has no `vehicle_id` column at all - it is the bundled
    Chassis Quirk Index, reference data about a CHASSIS rather than an
    observation about one car. Three things follow and all three are checked:
    the field is not on the wire, sending one is a 400 rather than a silent
    drop, and the row is visible from the same feed whichever vehicle a client
    happens to care about.
    """
    quirk = TABLES[8]
    assert quirk.name == "chassis_quirks"
    stored = auth_client.put(
        f"{quirk.url}e46-vanos/", quirk.payload(fleet, 0), format="json"
    ).data

    assert "vehicle_id" not in stored
    # `chassis` is a comma-delimited list of chassis CODES, not a vehicle id.
    assert stored["chassis"] == "E46,E46M3"

    refused = auth_client.put(
        f"{quirk.url}e46-vanos/",
        quirk.payload(fleet, 0) | {"vehicle_id": fleet.vehicle},
        format="json",
    )
    assert refused.status_code == 400
    assert "vehicle_id" in str(refused.data)

    # One feed, no vehicle in it anywhere. There is nothing to scope by, which
    # is what "household-shared" means here.
    feed = auth_client.get(f"{quirk.url}?since={EPOCH}").data["results"]
    assert [row["quirk_id"] for row in feed] == ["e46-vanos"]


def test_vehicle_specs_key_on_the_vehicle(auth_client, fleet):
    """Shape 4. One row per vehicle: `vehicle_id` is the primary key AND the
    foreign key, so a second PUT under the same vehicle replaces the spec sheet
    and a PUT under a different vehicle makes a second row."""
    specs = TABLES[9]
    assert specs.name == "vehicle_specs"

    first = auth_client.put(
        f"{specs.url}{fleet.vehicle}/", specs.payload(fleet, 0), format="json"
    ).data
    assert str(first["vehicle_id"]) == fleet.vehicle
    assert "id" not in first

    replaced = auth_client.put(
        f"{specs.url}{fleet.vehicle}/", specs.payload(fleet, 0) | {"engine_hp": 343},
        format="json",
    ).data
    assert replaced["engine_hp"] == 343

    auth_client.put(f"{specs.url}{fleet.other}/", specs.payload(fleet, 1), format="json")

    feed = auth_client.get(f"{specs.url}?since={EPOCH}").data["results"]
    assert sorted(str(row["vehicle_id"]) for row in feed) == sorted(fleet.both)


def test_vehicle_specs_for_an_unknown_vehicle_is_refused(auth_client, fleet):
    """The identity is a foreign key, so an id this server never issued names
    no vehicle and the row cannot exist. Refused with the field named, not a
    500 from the constraint."""
    specs = TABLES[9]
    response = auth_client.put(
        f"{specs.url}{uuid.uuid4()}/", specs.payload(fleet, 0), format="json"
    )
    assert response.status_code == 400
    assert "vehicle_id" in str(response.data)


def test_maintenance_schedules_key_on_the_pair(auth_client, fleet):
    """Shape 5, the composite key, and the one detail route in this API with
    two path segments.

    Same vehicle plus same service name is one row. Same vehicle plus a
    different service name, or the same service name on a different vehicle, is
    a different row - which is exactly what
    `maintenance_schedules_unique_per_vehicle` says.
    """
    schedules = TABLES[10]
    assert schedules.name == "maintenance_schedules"
    body = schedules.payload(fleet, 0)

    first = auth_client.put(f"{schedules.url}{fleet.vehicle}/oil change/", body, format="json")
    assert first.status_code == 200, first.data
    assert first.data["service_name"] == "oil change"
    assert str(first.data["vehicle_id"]) == fleet.vehicle

    again = auth_client.put(
        f"{schedules.url}{fleet.vehicle}/oil change/", body | {"interval_miles": 7500},
        format="json",
    )
    assert again.data["id"] == first.data["id"]
    assert again.data["interval_miles"] == 7500

    # Same name, other car: a different row.
    other_car = auth_client.put(
        f"{schedules.url}{fleet.other}/oil change/", body, format="json"
    )
    assert other_car.data["id"] != first.data["id"]

    # Same car, other name: a different row again.
    other_name = auth_client.put(
        f"{schedules.url}{fleet.vehicle}/brake fluid/", body, format="json"
    )
    assert other_name.data["id"] != first.data["id"]

    feed = auth_client.get(f"{schedules.url}?since={EPOCH}").data["results"]
    assert len(feed) == 3


def test_maintenance_schedule_delete_needs_both_halves(auth_client, fleet):
    schedules = TABLES[10]
    body = schedules.payload(fleet, 0)
    auth_client.put(f"{schedules.url}{fleet.vehicle}/oil change/", body, format="json")

    # A pair that names no row: 404 naming both halves, never a silent 204.
    missing = auth_client.delete(f"{schedules.url}{fleet.vehicle}/tyre rotation/")
    assert missing.status_code == 404
    assert "tyre rotation" in str(missing.data)
    assert str(fleet.vehicle) in str(missing.data)

    assert auth_client.delete(f"{schedules.url}{fleet.vehicle}/oil change/").status_code == 204


# =============================================================================
# The two tables with no `deleted_at` column at all.
# =============================================================================


@pytest.mark.parametrize(
    ("table", "word"),
    [
        pytest.param(TABLES[8], "reference data", id="chassis_quirks"),
        pytest.param(TABLES[9], "spec sheet", id="vehicle_specs"),
    ],
)
def test_a_table_with_no_tombstone_column_refuses_delete_in_words(
    auth_client, fleet, table, word
):
    """There is no `deleted_at` to write, so there is no DELETE route - the
    refusal comes from the URL map rather than from a guard inside a handler.

    The sentence has to be about THIS table: before fleet, the only table with
    `allow_delete = False` was `memory_audit` and the 405 said "it is an audit
    trail", which would be a false statement about a spec sheet. It now comes
    from the viewset's own `no_delete_reason`.
    """
    auth_client.put(f"{table.url}{table.detail(fleet, 0)}", table.payload(fleet, 0), "json")
    response = auth_client.delete(f"{table.url}{table.detail(fleet, 0)}")
    assert response.status_code == 405
    text = str(response.data)
    assert "Nothing was deleted" in text
    assert word in text
    assert "audit trail" not in text


def test_memory_audit_still_says_append_only(auth_client):
    """The wording moved out of `SyncedModelViewSet` and into the viewsets that
    mean it, so this checks the one that was there first did not lose it."""
    response = auth_client.delete("/api/memory/memory_audit/whatever/")
    assert response.status_code == 405
    assert "append-only" in str(response.data)


# =============================================================================
# Values Postgres would refuse, refused here first and in words.
# =============================================================================

REFUSED = [
    pytest.param(TABLES[0], {"year": 1800}, ("year", "1885", "2200"), id="vehicle-year"),
    pytest.param(
        TABLES[0],
        {"odometer_baseline": 120000},
        ("odometer_baseline", "odometer_baseline_at"),
        id="vehicle-unpaired-odometer",
    ),
    pytest.param(
        TABLES[1], {"kind": "RUMOURED"}, ("OBSERVED", "ASSERTED"), id="service-kind"
    ),
    pytest.param(TABLES[2], {"miles": -1.0}, ("miles", "at least 0"), id="drive-miles"),
    pytest.param(
        TABLES[2],
        {"started_at": _iso(9), "ended_at": _iso(1)},
        ("ended_at", "started_at"),
        id="drive-ends-before-start",
    ),
    pytest.param(
        TABLES[3], {"codes": '["P0420"]'}, ("codes", "JSON array"), id="code-event-codes-string"
    ),
    pytest.param(
        TABLES[3],
        {"freeze_frame": ["rpm"]},
        ("freeze_frame", "JSON object"),
        id="code-event-freeze-frame-array",
    ),
    pytest.param(
        TABLES[4], {"outcome": "MAYBE"}, ("CLEARED", "RETURNED", "UNVERIFIED"), id="clear-outcome"
    ),
    pytest.param(
        TABLES[4],
        {"outcome": "UNVERIFIED"},
        ("outcome", "codes_after"),
        id="clear-outcome-disagrees-with-codes-after",
    ),
    pytest.param(TABLES[5], {"iron": -3}, ("iron", "at least 0"), id="oil-negative-iron"),
    pytest.param(
        TABLES[5],
        {"drain_interval_miles": 0},
        ("drain_interval_miles", "greater than 0"),
        id="oil-zero-drain-interval",
    ),
    pytest.param(TABLES[6], {"title": "   "}, ("title", "blank"), id="build-entry-blank-title"),
    pytest.param(
        TABLES[6], {"cost_cents": -1}, ("cost_cents", "at least 0"), id="build-entry-cost"
    ),
    pytest.param(
        TABLES[7],
        {"from_at": _iso(9), "to_at": _iso(1)},
        ("to_at", "from_at"),
        id="reassignment-window",
    ),
    pytest.param(
        TABLES[8],
        {"severity": "ANNOYING"},
        ("MONITOR", "SERVICE_SOON", "CRITICAL"),
        id="quirk-severity",
    ),
    pytest.param(
        TABLES[8], {"cost_low_cents": -1}, ("cost_low_cents", "at least 0"), id="quirk-cost"
    ),
    pytest.param(TABLES[9], {"doors": 0}, ("doors", "greater than 0"), id="spec-doors"),
    pytest.param(
        TABLES[9],
        {"displacement_l": 0.0},
        ("displacement_l", "greater than 0"),
        id="spec-displacement",
    ),
    pytest.param(
        TABLES[10],
        {"interval_miles": None, "interval_months": None},
        ("interval_miles", "interval_months"),
        id="schedule-no-interval",
    ),
    pytest.param(
        TABLES[10],
        {"interval_miles": 0},
        ("interval_miles", "greater than 0"),
        id="schedule-zero-interval",
    ),
]


@pytest.mark.parametrize(("table", "bad", "expected_words"), REFUSED)
def test_a_refused_value_is_400_naming_what_is_wrong(
    auth_client, fleet, table, bad, expected_words
):
    path = f"{table.url}{table.detail(fleet, 0)}"
    # Counted BEFORE, because `vehicles` is not empty at this point - the
    # fixture's own two cars are in its feed. See this module's doc comment.
    before = len(auth_client.get(f"{table.url}?since={EPOCH}").data["results"])

    response = auth_client.put(path, table.payload(fleet, 0) | bad, format="json")
    assert response.status_code == 400, response.data
    text = str(response.data)
    for word in expected_words:
        assert word in text, f"{word!r} missing from {text!r}"

    # Nothing partial was written. The refused PUT would have CREATED a row if
    # it had been accepted, so an unchanged count is the whole claim.
    after = len(auth_client.get(f"{table.url}?since={EPOCH}").data["results"])
    assert after == before


# =============================================================================
# obd_samples. Not a synced table, and none of the above applies to it.
# =============================================================================


def a_sample(vehicle: str, *, pid: str = "0104", day: int = 1, hour: int = 12) -> dict:
    return {
        "vehicle_id": vehicle,
        "pid": pid,
        "value": 41.2,
        "unit": "%",
        "recorded_at": _iso(day, hour),
        "lat": 29.76,
        "lng": -95.36,
    }


def test_obd_batch_inserts_and_a_repost_inserts_nothing(auth_client, fleet):
    """The idempotency claim, checked from both sides.

    `obd_samples_natural_key_idx` is unique on `(vehicle_id, pid, recorded_at)`
    and the insert names it as the `on conflict` target, so a re-post of a
    batch that already landed writes nothing. This is the normal case, not the
    exceptional one: `ObdSampleReconcile` resumes an interrupted upload from a
    cursor it may have failed to advance.
    """
    batch = [a_sample(fleet.vehicle, pid=f"010{i}") for i in range(4)]

    first = auth_client.post(OBD_BATCH, batch, format="json")
    assert first.status_code == 201, first.data
    assert first.data == {
        "received": 4,
        "inserted": 4,
        "already_present": 0,
        "duplicates_in_batch": 0,
    }

    second = auth_client.post(OBD_BATCH, batch, format="json")
    # 200, not 201: nothing was created. Not an error either - a re-post is a
    # legitimate no-op and must never look like a failure to retry differently.
    assert second.status_code == 200, second.data
    assert second.data == {
        "received": 4,
        "inserted": 0,
        "already_present": 4,
        "duplicates_in_batch": 0,
    }

    assert auth_client.get(OBD_COUNT).data["count"] == 4


def test_obd_batch_reports_duplicates_inside_one_body(auth_client, fleet):
    """Two readings claiming one car, one PID and one instant are
    indistinguishable to this table - the unique index means only one can
    exist. The first is kept and the fold is COUNTED, because a silent drop
    would look exactly like a successful write of both."""
    sample = a_sample(fleet.vehicle)
    response = auth_client.post(OBD_BATCH, [sample, sample | {"value": 99.0}], format="json")
    assert response.status_code == 201, response.data
    assert response.data == {
        "received": 2,
        "inserted": 1,
        "already_present": 0,
        "duplicates_in_batch": 1,
    }
    stored = auth_client.get(f"{OBD}?vehicle={fleet.vehicle}").data["results"]
    assert [row["value"] for row in stored] == [41.2]


def test_obd_batch_with_an_unknown_vehicle_writes_nothing_at_all(auth_client, fleet):
    """The whole batch is refused rather than the rows that named it. Nothing
    partial is stored, and the 400 names the id so a client can find its own
    bug without reading the foreign key's message."""
    stranger = uuid.uuid4()
    response = auth_client.post(
        OBD_BATCH,
        [a_sample(fleet.vehicle), a_sample(str(stranger), pid="0105")],
        format="json",
    )
    assert response.status_code == 400
    assert str(stranger) in str(response.data)
    assert auth_client.get(OBD_COUNT).data["count"] == 0


def test_obd_batch_over_the_cap_is_refused_whole(auth_client, fleet):
    from api.fleet import OBD_BATCH_MAX

    batch = [
        a_sample(fleet.vehicle, pid=f"pid-{i}") for i in range(OBD_BATCH_MAX + 1)
    ]
    response = auth_client.post(OBD_BATCH, batch, format="json")
    assert response.status_code == 400
    assert str(OBD_BATCH_MAX) in str(response.data)
    # Refused whole, not truncated: a truncated batch would report success for
    # rows nobody stored.
    assert auth_client.get(OBD_COUNT).data["count"] == 0


def test_obd_batch_body_must_be_an_array(auth_client, fleet):
    response = auth_client.post(OBD_BATCH, {"samples": []}, format="json")
    assert response.status_code == 400
    assert "JSON ARRAY" in str(response.data)


def test_obd_batch_refuses_a_blank_pid(auth_client, fleet):
    response = auth_client.post(OBD_BATCH, [a_sample(fleet.vehicle, pid="  ")], format="json")
    assert response.status_code == 400
    assert "pid" in str(response.data)


def test_obd_empty_batch_is_a_no_op(auth_client, fleet):
    response = auth_client.post(OBD_BATCH, [], format="json")
    assert response.status_code == 200
    assert response.data["received"] == 0
    assert response.data["inserted"] == 0


def test_obd_feed_requires_a_vehicle(auth_client, fleet):
    """The one list route in this API with a mandatory filter.

    It refuses rather than defaulting a window, and the refusal says both why
    and what to do instead. A silent narrowing would hand a client thirty days
    of one car when it asked for a table, with nothing to tell it the
    difference.
    """
    auth_client.post(OBD_BATCH, [a_sample(fleet.vehicle)], format="json")

    response = auth_client.get(OBD)
    assert response.status_code == 400
    detail = response.data["detail"]
    assert "?vehicle=" in detail
    assert "since" in detail
    # It says nothing was returned - not a 200 with a quietly narrowed page.
    assert "Nothing was returned" in detail


def test_obd_feed_refuses_a_vehicle_that_is_not_a_uuid(auth_client, fleet):
    response = auth_client.get(f"{OBD}?vehicle=the-blue-one")
    assert response.status_code == 400
    assert "uuid" in str(response.data)


def test_obd_feed_404s_an_unknown_vehicle_rather_than_returning_an_empty_page(
    auth_client, fleet
):
    """Unreadable and empty are different sentences. An empty page here would
    read as 'this car has no telemetry' when the truth is that there is no such
    car."""
    stranger = uuid.uuid4()
    response = auth_client.get(f"{OBD}?vehicle={stranger}")
    assert response.status_code == 404
    assert str(stranger) in str(response.data)


def test_obd_feed_is_windowed_per_vehicle(auth_client, fleet):
    """`?since=` is matched against `recorded_at` - when the sample was read
    from the car, not when it reached this server - because that is the column
    `FleetBackend.fetchObdSamplesSince` filters on and the only one that means
    anything to a client rebuilding a window."""
    auth_client.post(
        OBD_BATCH,
        [
            a_sample(fleet.vehicle, pid="old", day=1),
            a_sample(fleet.vehicle, pid="new", day=5),
            a_sample(fleet.other, pid="other-car", day=5),
        ],
        format="json",
    )

    everything = auth_client.get(f"{OBD}?vehicle={fleet.vehicle}").data["results"]
    assert sorted(row["pid"] for row in everything) == ["new", "old"]

    windowed = auth_client.get(f"{OBD}?vehicle={fleet.vehicle}&since={_iso(3)}").data
    assert [row["pid"] for row in windowed["results"]] == ["new"]
    assert windowed["next"] is None

    # The other car's sample is never in this car's feed, whatever the window.
    assert all(str(row["vehicle_id"]) == fleet.vehicle for row in everything)


def test_obd_feed_carries_no_id_and_no_tombstone(auth_client, fleet):
    """`RemoteObdSample`'s own shape: no `serverId`, no `deleted_at`. There is
    no detail route to address a sample by, so publishing a uuid would hand a
    client an identity that addresses nothing."""
    auth_client.post(OBD_BATCH, [a_sample(fleet.vehicle)], format="json")
    row = auth_client.get(f"{OBD}?vehicle={fleet.vehicle}").json()["results"][0]
    assert set(row) == {
        "vehicle_id",
        "pid",
        "value",
        "unit",
        "recorded_at",
        "lat",
        "lng",
        "created_at",
    }


def test_obd_samples_have_no_delete_route(auth_client, fleet):
    """Telemetry is append-only and never corrected: an observation is
    superseded by a newer observation, never by editing or removing an old one.
    There is no DELETE anywhere on this table - not on the collection, and not
    on any per-row path, because there is no per-row path at all."""
    auth_client.post(OBD_BATCH, [a_sample(fleet.vehicle)], format="json")

    assert auth_client.delete(OBD).status_code == 405
    assert auth_client.put(OBD, [], format="json").status_code == 405
    assert auth_client.post(OBD, [], format="json").status_code == 405
    # No detail route exists to be deleted through, so this is a 404 from the
    # URL resolver rather than a 405 from a view that declined.
    assert auth_client.delete(f"{OBD}{uuid.uuid4()}/").status_code == 404
    assert auth_client.delete(f"{OBD_BATCH}").status_code == 405

    assert auth_client.get(OBD_COUNT).data["count"] == 1


def test_obd_count_narrows_to_one_vehicle_when_asked(auth_client, fleet):
    """`?vehicle=` is optional here and required on the feed, and the asymmetry
    is not an inconsistency: a count is one aggregate whatever the table's
    size, so the reason the feed refuses an unbounded request does not apply."""
    auth_client.post(
        OBD_BATCH,
        [a_sample(fleet.vehicle), a_sample(fleet.other), a_sample(fleet.other, pid="0105")],
        format="json",
    )
    assert auth_client.get(OBD_COUNT).data["count"] == 3
    assert auth_client.get(f"{OBD_COUNT}?vehicle={fleet.vehicle}").data["count"] == 1
    assert auth_client.get(f"{OBD_COUNT}?vehicle={fleet.other}").data["count"] == 2


# =============================================================================
# The changes feed.
# =============================================================================


def test_changes_accepts_fleet_and_returns_its_eleven_tables(auth_client, fleet):
    from api.fleet import FLEET_VIEWSETS

    response = auth_client.get("/api/changes?aspects=fleet")
    assert response.status_code == 200
    body = response.json()

    expected = {viewset.table for viewset in FLEET_VIEWSETS}
    assert expected == set(body) - {"server_time"}
    assert len(expected) == 11
    # The fixture's two vehicles are in there, so the feed is populated rather
    # than merely present.
    assert len(body["vehicles"]) == 2


def test_changes_never_carries_obd_samples(auth_client, fleet):
    """The exclusion, checked three ways: asking for fleet, asking for
    everything, and asking for `obd_samples` by name.

    This feed is NOT paged and `obd_samples` held 20,796 rows on 2026-09-07, so
    folding it in would make a bare `GET /api/changes` a whole-telemetry-archive
    download for every client, forever. Telemetry has its own paged,
    per-vehicle route instead.
    """
    auth_client.post(OBD_BATCH, [a_sample(fleet.vehicle)], format="json")

    assert "obd_samples" not in auth_client.get("/api/changes?aspects=fleet").json()
    assert "obd_samples" not in auth_client.get("/api/changes").json()

    named = auth_client.get("/api/changes?aspects=obd_samples")
    assert named.status_code == 400
    assert "obd_samples" in str(named.data)
    assert "fleet" in str(named.data)


def test_changes_with_no_aspects_includes_fleet(auth_client, fleet):
    """"Missing means everything" is this API's standing posture for an absent
    parameter, and adding an aspect must not quietly narrow it."""
    body = auth_client.get("/api/changes").json()
    assert len(body["vehicles"]) == 2
    assert "maintenance_schedules" in body
    assert "chassis_quirks" in body
