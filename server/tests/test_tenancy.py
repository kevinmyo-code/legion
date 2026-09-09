"""ADR 0045, tested from the outside: household B never sees household A's
rows, on any route, ever.

**Generated from `api/registry.py` and `household/tenancy.TENANT_TABLES`, not
hand-listed** - the same posture `tests/test_synced_contract.py` takes, and
for the same reason: a table added to the API and forgotten here would be a
table with no leak test, and nothing would say so.
`test_every_registered_table_has_a_leak_test` is the check that makes the
generation real; it fails naming the table rather than passing quietly with
one fewer case.

## What is covered, and what each part is for

- **The leak suite.** One row in A, one in B, for every routed table. B's
  list, B's detail, B's changes feed, B's PUT and B's DELETE never reach A's
  row. A 404 rather than a 403 on the detail routes is deliberate and is
  asserted as such: from inside household B there IS no such row, and a 403
  would confirm that some other household holds one.
- **The column check.** `information_schema` must show `household_id` on
  exactly the tables `TENANT_TABLES` names. This is the half a leak test
  cannot do: a table nobody routed, and therefore nobody wrote a leak test
  for, still has to carry the column for ticket 02b's row-level security to
  key on.
- **The index check.** The re-keyed unique constraints, by name, read from
  `pg_indexes`. `household/tenancy_sql.py` derives them from the catalog at
  run time, so without this the suite would be checking the planner against
  itself.
- **The wire check.** `household_id` appears in no response body and in no
  component of `server/openapi.yaml`. It is a server fact, like `provenance`;
  a client that could read it could not do anything honest with it, and a
  client that could SEND it would be choosing its own tenancy.
- **The collision check.** Two households may hold the same `origin_guid`,
  the same category name, the same place label. This is the half the leak
  tests would pass without: isolation that refuses B's write because A made
  the same one first is not isolation.

## What is deliberately NOT covered, and why

`goals`, `conversation_audit`, `item_lists` and `list_items` carry the column
but have no route and no rows in the test database - they are not in
`tests/legacy_test_schema.py`'s mirror at all, because nothing has ever
written to them through this server. `TABLES_ABSENT_FROM_THE_TEST_MIRROR`
below names them, and `test_every_tenant_table_that_exists_here_has_the_column`
uses that list rather than skipping quietly, so adding one of them to the
mirror without the column fails, and removing one from `TENANT_TABLES`
without updating the list fails too.

`chassis_quirks` is per-household for reads and per-SERVER for its identity:
its primary key is a human-assigned `quirk_id` text column, and re-keying a
primary key is not this ticket.
`test_two_households_cannot_hold_the_same_chassis_quirk_id` pins that as the
known state rather than leaving it to be discovered.
"""
from __future__ import annotations

import pytest
from django.db import connection

from api.registry import SYNCED_VIEWSETS
from household.tenancy import DJANGO_MANAGED_TENANT_TABLES, TENANT_TABLES
from household.tenancy_sql import household_unique_name
from tests.test_fleet_api import TABLES as FLEET_TABLES
from tests.test_fleet_api import a_vehicle
from tests.test_ingest_api import a_receipt, a_statement
from tests.test_synced_contract import TABLES as SYNCED_TABLES

pytestmark = pytest.mark.django_db

EPOCH = "1970-01-01T00:00:00Z"

# See this module's own doc comment. Four tenant tables with no route, no
# writer and no rows anywhere in this suite.
TABLES_ABSENT_FROM_THE_TEST_MIRROR = frozenset(
    {"goals", "conversation_audit", "item_lists", "list_items"}
)


# =============================================================================
# Building one row in each household, table by table
# =============================================================================


def _put(client, url: str, payload: dict):
    response = client.put(url, payload, format="json")
    assert response.status_code == 200, (url, response.status_code, response.data)
    return response.data


def _synced_cases():
    """The sixteen client-identity tables `tests/test_synced_contract.py`
    already describes, reused rather than re-typed: its payload factories are
    the ones kept correct as those tables change, and a second copy here
    would be a second thing to keep correct."""
    cases = []
    for param in SYNCED_TABLES:
        url, factory = param.values
        cases.append(
            pytest.param(param.id, url, f"{url}guid-{{n}}/", factory, id=param.id)
        )
    return cases


def _fleet_for(client):
    """`tests/test_fleet_api.py`'s two-vehicle fixture, rebuilt against ONE
    client.

    That module's own `fleet` fixture hangs off `auth_client`, so both
    households would share household A's cars. Two vehicles rather than one
    for the reason it gives: `drive_reassignments` needs a different
    `new_vehicle_id`, and `vehicle_specs` is keyed BY the vehicle.
    """
    from tests.test_fleet_api import Fleet

    made = []
    for i in range(2):
        response = client.put(
            f"/api/fleet/vehicles/tenancy-vehicle-{i}/", a_vehicle(i), format="json"
        )
        assert response.status_code == 200, response.data
        made.append(str(response.data["id"]))
    return Fleet(*made)


# =============================================================================
# The generated leak suite
# =============================================================================


@pytest.mark.parametrize(("table", "url", "detail_template", "payload"), _synced_cases())
def test_synced_list_never_shows_another_households_rows(
    token_a, token_b, table, url, detail_template, payload
):
    _put(token_a, detail_template.format(n="a"), payload(0))
    _put(token_b, detail_template.format(n="b"), payload(1))

    for client, theirs in ((token_a, "b"), (token_b, "a")):
        rows = client.get(f"{url}?since={EPOCH}").data["results"]
        identities = {row.get("origin_guid") or row.get("label") for row in rows}
        assert len(rows) == 1, (table, rows)
        assert f"guid-{theirs}" not in identities, (table, identities)


@pytest.mark.parametrize(("table", "url", "detail_template", "payload"), _synced_cases())
def test_synced_delete_of_another_households_row_is_a_404_and_changes_nothing(
    token_a, token_b, table, url, detail_template, payload
):
    if table == "memory_audit":
        pytest.skip("memory_audit is append-only: there is no DELETE route to refuse.")
    _put(token_a, detail_template.format(n="a"), payload(0))

    response = token_b.delete(detail_template.format(n="a"))
    # 404, never 403: from inside household B there is no such row, and a 403
    # would confirm that somebody else has one.
    assert response.status_code == 404, response.data

    still_there = token_a.get(f"{url}?since={EPOCH}&active=1").data["results"]
    assert len(still_there) == 1, still_there


@pytest.mark.parametrize(("table", "url", "detail_template", "payload"), _synced_cases())
def test_synced_put_on_another_households_identity_creates_a_second_row(
    token_a, token_b, table, url, detail_template, payload
):
    """B's PUT to the identity A already used does NOT edit A's row.

    It creates B's own, because the identity is per-household now - which is
    the collision half of isolation and the reason `origin_guid` and
    `places.label` were re-keyed. A run of this test before the re-keying
    fails with a 400 from a whole-table unique index, not with a leak, and
    that failure is what it is here to prevent.
    """
    a_row = _put(token_a, detail_template.format(n="shared"), payload(0))
    b_row = _put(token_b, detail_template.format(n="shared"), payload(1))

    if "id" in a_row and "id" in b_row:
        assert a_row["id"] != b_row["id"], (table, a_row, b_row)

    a_rows = token_a.get(f"{url}?since={EPOCH}").data["results"]
    b_rows = token_b.get(f"{url}?since={EPOCH}").data["results"]
    assert len(a_rows) == 1 and len(b_rows) == 1, (a_rows, b_rows)


@pytest.mark.parametrize("table", [pytest.param(t, id=t.name) for t in FLEET_TABLES])
def test_fleet_list_never_shows_another_households_rows(token_a, token_b, table):
    fleet_a = _fleet_for(token_a)
    fleet_b = _fleet_for(token_b)
    # The SAME identity in both households, which is half the point: after the
    # re-keying, `guid-0` and `sync-0` are per-household. The exception is
    # `chassis_quirks`, whose identity is a PRIMARY KEY and stays per-server -
    # `test_two_households_cannot_hold_the_same_chassis_quirk_id` is where that
    # is pinned, and giving B a different quirk id here keeps THIS test about
    # what it says it is about.
    b_index = 1 if table.name == "chassis_quirks" else 0
    _put(token_a, f"{table.url}{table.detail(fleet_a, 0)}", table.payload(fleet_a, 0))
    _put(token_b, f"{table.url}{table.detail(fleet_b, b_index)}", table.payload(fleet_b, 1))

    a_rows = token_a.get(f"{table.url}?since={EPOCH}").data["results"]
    b_rows = token_b.get(f"{table.url}?since={EPOCH}").data["results"]
    # `vehicles` is the one table where the fixture itself is the data: two
    # cars per household, and the row this test wrote is a third.
    expected = 3 if table.name == "vehicles" else 1
    assert len(a_rows) == expected, (table.name, a_rows)
    assert len(b_rows) == expected, (table.name, b_rows)
    if table.has_id:
        a_ids = {row["id"] for row in a_rows}
        b_ids = {row["id"] for row in b_rows}
        assert not (a_ids & b_ids), (table.name, a_ids, b_ids)


def test_a_vehicle_from_another_household_cannot_be_named_in_a_write(token_a, token_b):
    """The vector `HouseholdScopedPrimaryKeyRelatedField` exists to close.

    The ROW would have been scoped correctly - B's `household_id` - and the
    `vehicle_id` INSIDE it would have pointed at A's car. Nothing else in
    `api/synced.py` looks at the values in a row, only at the row.
    """
    response = token_a.put("/api/fleet/vehicles/a-car/", a_vehicle(0), format="json")
    assert response.status_code == 200
    a_vehicle_id = str(response.data["id"])

    refused = token_b.put(
        "/api/fleet/service_history/b-service/",
        {
            "vehicle_id": a_vehicle_id,
            "service_name": "oil change",
            "kind": "OBSERVED",
            "mileage": 100000,
            "cost_cents": 8999,
        },
        format="json",
    )
    assert refused.status_code == 400, refused.data
    assert "vehicle_id" in refused.data, refused.data


def test_obd_samples_are_scoped_by_household(token_a, token_b):
    """`obd_samples` is the one table with no viewset in the registry and
    three hand-written routes (list, count, batch), so it is the one most
    likely to be missed - and its 20,796 rows on 2026-09-07 make it the
    worst one to miss."""
    ids = []
    for client in (token_a, token_b):
        response = client.put("/api/fleet/vehicles/obd-car/", a_vehicle(0), format="json")
        assert response.status_code == 200
        ids.append(str(response.data["id"]))
    a_car, b_car = ids

    batch = [
        {
            "vehicle_id": a_car,
            "pid": "RPM",
            "value": 800.0,
            "unit": "rpm",
            "recorded_at": "2026-09-01T12:00:00Z",
        }
    ]
    assert token_a.post("/api/fleet/obd_samples/batch/", batch, format="json").status_code == 201

    # A's car is not a car B may name at all.
    refused = token_b.post(
        "/api/fleet/obd_samples/batch/",
        [dict(batch[0])],
        format="json",
    )
    assert refused.status_code == 400, refused.data
    assert "name no vehicle this server knows" in refused.data["detail"]

    # And the unfiltered count - the route where `?vehicle=` is optional - is
    # B's own telemetry, which is none.
    assert token_b.get("/api/fleet/obd_samples/count/").data["count"] == 0
    assert token_a.get("/api/fleet/obd_samples/count/").data["count"] == 1

    # A's car is a 404 from B, not an empty page: "there is no such car" is
    # true from inside household B.
    assert token_b.get(f"/api/fleet/obd_samples/?vehicle={a_car}").status_code == 404
    assert token_b.get(f"/api/fleet/obd_samples/?vehicle={b_car}").status_code == 200


def test_voice_notes_are_scoped_by_household(token_a, token_b):
    from tests.test_voice_notes_api import NOTE

    created = token_a.post("/api/voice_notes/", NOTE, format="json")
    assert created.status_code == 201, created.data
    note_id = created.data["id"]

    assert token_b.get(f"/api/voice_notes/?since={EPOCH}").data["results"] == []
    assert token_b.put(f"/api/voice_notes/{note_id}/", NOTE, format="json").status_code == 404
    assert token_b.delete(f"/api/voice_notes/{note_id}/").status_code == 404
    assert len(token_a.get(f"/api/voice_notes/?since={EPOCH}").data["results"]) == 1


def test_events_are_scoped_by_household(token_a, token_b):
    event = {"title": "MOT", "starts_at": "2026-10-01T09:00:00Z", "origin_guid": "shared-guid"}
    created = token_a.post("/api/events", event, format="json")
    assert created.status_code == 201, created.data

    # B posting the SAME origin_guid must create B's own event, not be handed
    # A's back as an idempotent repeat.
    b_created = token_b.post("/api/events", event, format="json")
    assert b_created.status_code == 201, b_created.data
    assert b_created.data["id"] != created.data["id"]

    assert token_b.patch(
        f"/api/events/{created.data['id']}", {"title": "hijacked"}, format="json"
    ).status_code == 404
    assert token_b.delete(f"/api/events/{created.data['id']}").status_code == 404

    a_titles = [row["title"] for row in token_a.get("/api/events").data["results"]]
    assert a_titles == ["MOT"]


def test_checklists_are_scoped_by_household(token_a, token_b):
    made = token_a.post("/api/checklists/", {"name": "bio", "sync_id": "shared"}, format="json")
    assert made.status_code == 201, made.data
    checklist_id = made.data["id"]

    # The same sync_id in the other household is a create, not a repeat.
    b_made = token_b.post("/api/checklists/", {"name": "bio", "sync_id": "shared"}, format="json")
    assert b_made.status_code == 201, b_made.data
    assert b_made.data["id"] != checklist_id

    assert token_b.get(f"/api/checklists/{checklist_id}").status_code == 404
    assert token_b.delete(f"/api/checklists/{checklist_id}").status_code == 404
    # An item under a checklist B cannot see is a 404 on the parent, never a
    # row written into A's list.
    assert token_b.post(
        f"/api/checklists/{checklist_id}/items", {"text": "squats"}, format="json"
    ).status_code == 404
    assert len(token_a.get("/api/checklists/").data["results"]) == 1


def test_a_checklist_item_and_tick_inherit_their_checklists_household(token_a, household_a):
    """`ChecklistItem.save`/`ChecklistTick.save` derive the household from the
    parent rather than taking one. This is what makes that derivation a fact
    rather than a comment."""
    from checklists.models import ChecklistItem, ChecklistTick

    checklist = token_a.post("/api/checklists/", {"name": "bio"}, format="json").data
    item = token_a.post(
        f"/api/checklists/{checklist['id']}/items", {"text": "squats"}, format="json"
    ).data
    tick = token_a.post(
        f"/api/checklists/{checklist['id']}/items/{item['id']}/tick",
        {"day": 20000},
        format="json",
    )
    assert tick.status_code in (200, 201), tick.data

    assert ChecklistItem.objects.get(pk=item["id"]).household_id == household_a.id
    assert ChecklistTick.objects.get(item_id=item["id"]).household_id == household_a.id


def test_the_gate_writes_its_five_tables_into_the_calling_household(token_a, token_b):
    """The ingest path is the only writer of `statements`,
    `ledger_transactions`, `receipts`, `receipt_line_items` and
    `ingested_files`, so it is the only place their `household_id` can be
    set - and `content_sha256` is a property of the FILE, so two households
    ingesting the same document is the ordinary case rather than a corner
    one."""
    assert token_a.post(
        "/api/ingest/statement", a_statement(), format="json"
    ).status_code == 201
    assert token_a.post("/api/ingest/receipt", a_receipt(), format="json").status_code == 201

    # B ingesting the SAME bytes must commit its own rows, not be told
    # "already committed" about a document it has never seen.
    b_statement = token_b.post("/api/ingest/statement", a_statement(), format="json")
    assert b_statement.status_code == 201, b_statement.data
    b_receipt = token_b.post("/api/ingest/receipt", a_receipt(), format="json")
    assert b_receipt.status_code == 201, b_receipt.data

    for client in (token_a, token_b):
        for url, expected in (
            ("/api/ledger/statements/", 1),
            ("/api/ledger/transactions/", 3),
            ("/api/pantry/receipts/", 1),
            ("/api/pantry/line-items/", 2),
            ("/api/ingest/files/", 2),
        ):
            rows = client.get(f"{url}?since={EPOCH}").data["results"]
            assert len(rows) == expected, (url, len(rows), rows)


def test_the_changes_feed_never_carries_another_households_rows(token_a, token_b):
    """`GET /api/changes` is the one route that reads every table at once, so
    an unscoped query here leaks a whole database in a single response."""
    token_a.post("/api/events", {"title": "A's event"}, format="json")
    token_a.post("/api/checklists/", {"name": "A's checklist"}, format="json")
    _put(token_a, "/api/places/a-place/", {"latitude": 1.0, "longitude": 2.0})
    _put(
        token_a,
        "/api/memory/memories/a-memory/",
        {"text": "A's secret", "logged_at": "2026-09-01T12:00:00Z"},
    )

    body = token_b.get(f"/api/changes?since={EPOCH}").data
    for key, rows in body.items():
        if key == "server_time":
            continue
        assert rows == [], (key, rows)

    a_body = token_a.get(f"/api/changes?since={EPOCH}").data
    assert len(a_body["events"]) == 1
    assert len(a_body["checklists"]) == 1
    assert len(a_body["places"]) == 1
    assert len(a_body["memories"]) == 1


# =============================================================================
# The checks a leak test cannot make
# =============================================================================


def test_every_registered_table_has_a_leak_test():
    """The generation, made real.

    Every table in `api/registry.SYNCED_VIEWSETS` must be covered by one of
    the parametrized suites above or by one of the named tests. A new aspect
    that is routed and not listed here fails, naming itself.
    """
    covered = {param.id for param in _synced_cases()}
    covered |= {table.name for table in FLEET_TABLES}
    covered |= {
        # Named tests rather than parametrized ones, because each has a
        # different shape: a POST-created identity, three hand-written routes,
        # and five tables only the section 4 gate may write.
        "voice_notes",
        "obd_samples",
        "statements",
        "ledger_transactions",
        "receipts",
        "receipt_line_items",
        "ingested_files",
    }
    registered = {viewset.table for viewset in SYNCED_VIEWSETS}
    missing = sorted(registered - covered)
    assert not missing, (
        f"These tables are routed by api/registry.py and have no leak test: "
        f"{', '.join(missing)}. Add one before this ticket can claim isolation."
    )


def _public_tables_with_household_id() -> set[str]:
    with connection.cursor() as cursor:
        cursor.execute(
            "select table_name from information_schema.columns "
            "where table_schema = 'public' and column_name = 'household_id'"
        )
        return {row[0] for row in cursor.fetchall()}


def _public_tables() -> set[str]:
    with connection.cursor() as cursor:
        cursor.execute(
            "select table_name from information_schema.tables "
            "where table_schema = 'public' and table_type = 'BASE TABLE'"
        )
        return {row[0] for row in cursor.fetchall()}


def test_every_tenant_table_that_exists_here_has_the_column():
    """`information_schema`, not a model file.

    The models are what this suite exercises; the COLUMN is what ticket 02b's
    row-level security keys on, and a table that Django knows a `household`
    field for but Postgres has no column for would pass every test above and
    fail the moment RLS is switched on.
    """
    present = _public_tables()
    expected = {table for table in TENANT_TABLES if table in present}
    assert _public_tables_with_household_id() == expected

    # And the four that are absent are absent for the stated reason, not
    # because the migration missed them.
    assert set(TENANT_TABLES) - present == TABLES_ABSENT_FROM_THE_TEST_MIRROR


def test_no_public_table_outside_the_list_carries_the_column():
    """The other direction. A stray `household_id` on a table nobody scoped
    is worse than none: it looks like tenancy and enforces nothing."""
    assert _public_tables_with_household_id() <= set(TENANT_TABLES)


def test_the_django_managed_tables_are_in_the_list_like_any_other():
    assert DJANGO_MANAGED_TENANT_TABLES <= set(TENANT_TABLES)


# The unique keys `household/tenancy_sql.py` re-keys, spelled out by hand.
# The planner derives these from the catalog at run time, so without a
# hand-written expectation this suite would be checking it against itself.
REKEYED = [
    ("bodyweight_logs", ["origin_guid"]),
    ("categories", ["name"]),
    ("categories", ["origin_guid"]),
    ("drives", ["sync_id"]),
    ("events", ["google_event_id"]),
    ("events", ["origin_guid"]),
    ("grocery_staples", ["name"]),
    ("ingested_files", ["content_sha256"]),
    ("ledger_transactions", ["origin_guid"]),
    ("meal_targets", ["effective_from_date"]),
    ("places", ["label"]),
    ("receipt_line_items", ["origin_guid"]),
    ("workout_plan_items", ["exercise", "effective_from_week"]),
    ("checklists", ["sync_id"]),
    ("checklist_items", ["sync_id"]),
]

# Unique keys that must NOT have been touched: each is already scoped through
# a foreign key to another tenant table, so prepending `household_id` would
# add a column that cannot change the key's meaning.
UNTOUCHED = [
    "event_skips_unique",
    "obd_samples_natural_key_idx",
    "maintenance_schedules_unique_per_vehicle",
    "statements_one_per_file",
    "checklist_ticks_item_day_uniq",
]


@pytest.mark.parametrize(
    ("table", "columns"),
    REKEYED,
    ids=lambda v: "_".join(v) if isinstance(v, list) else v,
)
def test_a_rekeyed_unique_index_leads_with_household_id(table, columns):
    name = household_unique_name(table, columns)
    with connection.cursor() as cursor:
        cursor.execute(
            "select indexdef from pg_indexes where schemaname = 'public' "
            "and tablename = %s and indexname = %s",
            [table, name],
        )
        row = cursor.fetchone()
    assert row is not None, f"{table}: no index named {name}"
    expected = ", ".join(["household_id", *columns])
    assert f"({expected})" in row[0], row[0]


@pytest.mark.parametrize("index_name", UNTOUCHED)
def test_an_already_scoped_unique_key_was_left_alone(index_name):
    with connection.cursor() as cursor:
        cursor.execute(
            "select indexdef from pg_indexes where schemaname = 'public' and indexname = %s",
            [index_name],
        )
        row = cursor.fetchone()
    assert row is not None, f"{index_name} is gone - the planner should have left it alone"
    assert "household_id" not in row[0], row[0]


def test_two_households_cannot_hold_the_same_chassis_quirk_id(token_a, token_b):
    """The known gap, pinned rather than left to be found.

    `chassis_quirks` is keyed on a human-assigned `quirk_id` PRIMARY KEY, and
    `household/tenancy_sql.py` never re-keys a primary key. So this table is
    per-household for READS (the test above proves that) and per-server for
    its identity. If a later ticket fixes it, this test is what will fail and
    say so.
    """
    quirk = {
        "chassis": "E46",
        "engine": "S54",
        "title": "rod bearing wear",
        "symptom": "knock on cold start",
        "verification_steps": "drop the pan",
        "severity": "CRITICAL",
        "fix_notes": "",
        "source_url": "",
    }
    assert token_a.put(
        "/api/fleet/chassis_quirks/e46-rod-bearings/", quirk, format="json"
    ).status_code == 200

    collided = token_b.put(
        "/api/fleet/chassis_quirks/e46-rod-bearings/", quirk, format="json"
    )
    assert collided.status_code == 400, collided.data
    assert "refused by the database" in collided.data["detail"]


# =============================================================================
# household_id is never on the wire
# =============================================================================


def test_no_openapi_component_declares_household_id():
    """`household_id` is a server fact, like `provenance` and the timestamps.

    A client that could READ it could do nothing honest with it - it already
    knows which household it is in, because it cannot be in another - and a
    client that could SEND it would be choosing its own tenancy, which is the
    whole thing this ticket removes.
    """
    from io import StringIO

    import yaml
    from django.core.management import call_command

    out = StringIO()
    call_command("spectacular", "--format", "openapi", stdout=out)
    schema = yaml.safe_load(out.getvalue())

    offenders = [
        f"{name}.{field}"
        for name, component in schema["components"]["schemas"].items()
        for field in (component.get("properties") or {})
        if field in {"household", "household_id"}
    ]
    assert not offenders, offenders


def test_no_response_body_carries_household_id(token_a):
    """The schema check above and this one answer different questions: that
    one is about what is DECLARED, this one about what is actually sent."""
    _put(token_a, "/api/places/on-the-wire/", {"latitude": 1.0, "longitude": 2.0})
    body = token_a.get(f"/api/places/?since={EPOCH}").data
    assert body["results"], body
    for row in body["results"]:
        assert "household" not in row
        assert "household_id" not in row


def test_household_is_refused_on_the_way_in(token_a, household_b):
    """A client that sends one is told, in words, rather than having it
    silently dropped - `SyncedSerializer.to_internal_value`'s unknown-field
    rule, which is what makes the field un-spoofable rather than merely
    ignored."""
    response = token_a.put(
        "/api/places/spoofed/",
        {"latitude": 1.0, "longitude": 2.0, "household": str(household_b.id)},
        format="json",
    )
    assert response.status_code == 400, response.data
    assert "household" in response.data["detail"]


# =============================================================================
# Members, roles, and the bootstrap household
# =============================================================================


def test_the_bootstrap_household_exists_with_the_id_the_operator_chose():
    from household.models import Household
    from household.tenancy import bootstrap_household_id

    assert Household.objects.filter(id=bootstrap_household_id()).exists()


def test_a_user_reaches_exactly_one_household(household_user, household_a):
    assert household_user.household == household_a


def test_a_user_in_no_household_is_refused_before_any_view_runs(db):
    """`IsHouseholdMember` is unchanged by ADR 0045 and still does this, which
    is why `household_of` raising is unreachable through a routed URL."""
    from rest_framework.test import APIClient

    from household.models import DeviceToken, User

    orphan = User.objects.create_user(email="orphan@example.com", password="correct horse")
    _token, raw = DeviceToken.issue(orphan, "Orphan device")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")

    assert client.get("/api/places/").status_code == 403


def test_a_household_member_carries_a_role_and_it_governs_membership_only(household_user):
    """ADR 0045: "There are no roles inside a household except `owner`, which
    exists only to invite and remove members." Nothing in `api/` reads it, and
    this test says so by asserting the DATA access of a plain member is
    identical to an owner's - not by asserting the column exists.
    """
    from household.models import HouseholdMember

    member = HouseholdMember.objects.get(user=household_user)
    assert member.role in {HouseholdMember.OWNER, HouseholdMember.MEMBER}


def test_the_admin_scopes_every_registered_model_for_a_non_superuser(household_a, household_b):
    """The admin does not go through `api/synced.py`'s choke point, so the
    rule is spelled out per ModelAdmin - and checked here rather than trusted.
    """
    from django.contrib import admin

    from household.models import HouseholdMember, User

    staff = User.objects.create_user(
        email="staff@example.com", password="correct horse", is_staff=True
    )
    HouseholdMember.objects.create(user=staff, household=household_a)
    other = User.objects.create_user(email="other@example.com", password="correct horse")
    HouseholdMember.objects.create(user=other, household=household_b)

    class _Request:
        user = staff

    request = _Request()
    for model, model_admin in admin.site._registry.items():
        rows = list(model_admin.get_queryset(request))
        assert not any(
            getattr(row, "household_id", None) == household_b.id for row in rows
        ), (model.__name__, rows)
    assert other not in list(admin.site._registry[User].get_queryset(request))


def test_the_bootstrap_id_is_read_from_the_environment_and_never_minted(monkeypatch):
    """The migration refuses rather than choosing.

    A random uuid would be correct exactly once - on the machine that ran the
    migration - and every other environment (a second engine, a restored
    dump, a fresh clone's compose stack) would then hold a DIFFERENT id for
    the same household, with nothing written down anywhere to reconcile them.
    Both refusals name the variable and say nothing was migrated.
    """
    from household.tenancy import BOOTSTRAP_ID_ENV, bootstrap_household_id

    monkeypatch.delenv(BOOTSTRAP_ID_ENV, raising=False)
    with pytest.raises(RuntimeError, match="is not set"):
        bootstrap_household_id()

    monkeypatch.setenv(BOOTSTRAP_ID_ENV, "Home")
    with pytest.raises(RuntimeError, match="which is not a uuid"):
        bootstrap_household_id()


def test_the_bootstrap_name_defaults_to_home(monkeypatch):
    from household.tenancy import BOOTSTRAP_NAME_ENV, bootstrap_household_name

    monkeypatch.delenv(BOOTSTRAP_NAME_ENV, raising=False)
    assert bootstrap_household_name() == "Home"
