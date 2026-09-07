"""The contract in `api/synced.py`, run against every table that is keyed
by a client-minted identity: places, the eight body tables, the three
memory tables, and the four AUTHORED ledger/pantry config tables. Sixteen
tables, one set of assertions - which is the claim the generic shape makes,
so it is tested as one parametrized module rather than as sixteen
near-identical files.

**This docstring said "twelve tables" before the ledger/pantry ticket added
`categories`, `category_rules`, `budget_targets` and `grocery_staples`.** They
needed no departures at all, which is the point of adding them here rather
than writing them a module: `20260902000400_aspect_ledger_config.sql`'s own
header calls them AUTHORED - "never a document that came through the
reconciliation gate" - so they carry `updated_at`, a `deleted_at` tombstone
and an `origin_guid` identity, exactly like body and memory.

`voice_notes` is NOT here: its identity is the server's own id, so it
creates by POST and its PUT never inserts. `tests/test_voice_notes_api.py`
covers it, and the difference is the point rather than an omission.

**Neither are `statements`, `ledger_transactions`, `receipts`,
`receipt_line_items` or `ingested_files`.** Every assertion below is built on
PUT-creates-a-row, and those five refuse every write verb there is - they are
the section 4 gate's own output, and `tests/test_ledger_pantry_api.py` covers
them, including the 405 that names the gate.

## The clock trap, written down because it cost an hour

`updated_at` is written by two different mechanisms and, inside a test,
they disagree about ordering:

- an INSERT through `SyncedSerializer.create` stamps `Now()`, which Django
  compiles to Postgres's `STATEMENT_TIMESTAMP()` - it advances with every
  statement, so two rows created by two requests get two increasing values;
- an UPDATE fires `private.touch_updated_at`, which stamps `now()`, which
  Postgres defines as the TRANSACTION timestamp - and pytest-django wraps
  each test in one transaction, so it is fixed at the moment the test's
  first statement ran, EARLIER than every row the test then creates.

So inside a test an updated (or tombstoned) row's `updated_at` moves
BACKWARD relative to rows inserted before it. In production, where every
request is its own transaction, the two agree to within a statement and
nothing about this is visible.

The consequence for these tests: a watermark taken from a created row is
only ever compared against OTHER created rows, and a tombstone is looked
for from an epoch watermark. Mixing the two would produce a test that
passes or fails on timing - `test_events_api.py`'s own
`test_since_feed_excludes_rows_before_the_watermark_and_includes_tombstones`
does mix them, and it passes in a full run and fails when run alone
(measured, 2026-09-06; flagged in this ticket's report, not changed here).
"""
from __future__ import annotations

import pytest
from rest_framework.test import APIClient

pytestmark = pytest.mark.django_db

EPOCH = "1970-01-01T00:00:00Z"


def _iso(day: int) -> str:
    return f"2026-09-{day:02d}T12:00:00Z"


def _date(day: int) -> str:
    return f"2026-09-{day:02d}"


# One entry per table: where it lives, and a payload factory that varies
# whatever the table declares unique so two rows never collide (meal and
# sleep targets are unique on their effective date, workout plans on their
# week, workout plan items on exercise+week).
TABLES = [
    pytest.param(
        "/api/places/",
        # No `label` in the payload: places is keyed BY its label, so the
        # URL carries it and the viewset overwrites anything a body claims.
        # These factories are also used to assert the response echoes every
        # field the caller stated, and a field the URL legitimately
        # overrides would fail that check for the wrong reason.
        lambda i: {"latitude": 1.5 + i, "longitude": -95.0},
        id="places",
    ),
    pytest.param(
        "/api/body/bodyweight_logs/",
        lambda i: {
            "weight_value": 180.0 + i,
            "weight_unit": "lbs",
            "logged_at": _iso(1 + i),
            "trust_tier": "REPORTED",
        },
        id="bodyweight_logs",
    ),
    pytest.param(
        "/api/body/meal_logs/",
        lambda i: {
            "description": f"oatmeal {i}",
            "calories_kcal": 300 + i,
            "logged_at": _iso(1 + i),
            "trust_tier": "REPORTED",
        },
        id="meal_logs",
    ),
    pytest.param(
        "/api/body/meal_targets/",
        lambda i: {
            "calories_kcal": 2200 + i,
            "protein_g": 160.0,
            "carbs_g": 200.0,
            "fat_g": 70.0,
            "effective_from_date": _date(1 + i),
        },
        id="meal_targets",
    ),
    pytest.param(
        "/api/body/sleep_logs/",
        lambda i: {
            "sleep_date": _date(1 + i),
            "duration_minutes": 420,
            "quality": 4,
            "logged_at": _iso(1 + i),
            "trust_tier": "REPORTED",
        },
        id="sleep_logs",
    ),
    pytest.param(
        "/api/body/sleep_targets/",
        lambda i: {"target_minutes": 450 + i, "effective_from_date": _date(1 + i)},
        id="sleep_targets",
    ),
    pytest.param(
        "/api/body/workout_plans/",
        lambda i: {"sessions_per_week": 4, "effective_from_week": _date(1 + i)},
        id="workout_plans",
    ),
    pytest.param(
        "/api/body/workout_plan_items/",
        lambda i: {
            "exercise": f"bench press {i}",
            "target_sets_per_week": 12,
            "effective_from_week": _date(1 + i),
            "reps_per_set": 8,
        },
        id="workout_plan_items",
    ),
    pytest.param(
        "/api/body/workout_set_logs/",
        lambda i: {
            "exercise": "squat",
            "sets": 3,
            "reps": 5,
            "weight_value": 225.0 + i,
            "weight_unit": "lbs",
            "logged_at": _iso(1 + i),
            "trust_tier": "PROVEN",
        },
        id="workout_set_logs",
    ),
    pytest.param(
        "/api/memory/memories/",
        lambda i: {"text": f"the garage code is {i}", "logged_at": _iso(1 + i)},
        id="memories",
    ),
    pytest.param(
        "/api/memory/companion_memories/",
        lambda i: {
            "vehicle_id": "AA:BB:CC:DD:EE:FF",
            "text": f"he prefers the window down {i}",
            "category": "driver",
            "source": "stated",
            "importance": 6,
            "logged_at": _iso(1 + i),
        },
        id="companion_memories",
    ),
    pytest.param(
        "/api/memory/memory_audit/",
        lambda i: {
            "event": "written",
            "store": "memories",
            "detail": f"remembered something {i}",
            "ref_id": 41 + i,
            "logged_at": _iso(1 + i),
        },
        id="memory_audit",
    ),
    # The four AUTHORED ledger/pantry tables. `categories` and
    # `grocery_staples` are unique on `name`, `budget_targets` on
    # (category, currency, effective_from_month), so each factory varies the
    # column that would otherwise collide when the same test writes two rows.
    pytest.param(
        "/api/ledger/categories/",
        lambda i: {"name": f"groceries {i}", "is_food_category": True},
        id="categories",
    ),
    pytest.param(
        "/api/ledger/category_rules/",
        lambda i: {
            "category": "groceries",
            "substring": f"COLD STORAGE {i}",
            "created_at_client": _iso(1 + i),
        },
        id="category_rules",
    ),
    pytest.param(
        "/api/ledger/budget_targets/",
        lambda i: {
            "category": "groceries",
            "currency": "SGD",
            "amount_cents": 50000 + i,
            "effective_from_month": _date(1 + i),
        },
        id="budget_targets",
    ),
    pytest.param(
        "/api/pantry/grocery_staples/",
        lambda i: {
            "name": f"milk {i}",
            "display_name": f"Milk {i}",
            "times_bought": 3 + i,
            "last_bought_at": _iso(1 + i),
        },
        id="grocery_staples",
    ),
]

# The tables that carry a DELETE route. `memory_audit` is append-only -
# `tests/test_memory_api.py` asserts the 405 and the words it comes with.
DELETABLE = [param for param in TABLES if param.id != "memory_audit"]


@pytest.mark.parametrize(("url", "payload"), TABLES)
def test_unauthenticated_list_is_401(url, payload):
    assert APIClient().get(url).status_code == 401


@pytest.mark.parametrize(("url", "payload"), TABLES)
def test_unauthenticated_put_is_401(url, payload):
    assert APIClient().put(f"{url}guid-1/", payload(0), format="json").status_code == 401


@pytest.mark.parametrize(("url", "payload"), TABLES)
def test_put_creates_and_returns_the_row_as_stored(auth_client, url, payload):
    response = auth_client.put(f"{url}guid-1/", payload(0), format="json")
    assert response.status_code == 200, response.data
    body = response.data
    # Server facts, all four filled in by the server and none of them
    # accepted from the caller.
    assert body["id"]
    assert body["created_at"] is not None
    assert body["updated_at"] is not None
    assert body["deleted_at"] is None
    # Every field the caller stated came back with the value it stated -
    # "the response body is the row AS STORED", so a value the server
    # silently altered would show up here.
    for field, value in payload(0).items():
        assert body[field] == value, field


@pytest.mark.parametrize(("url", "payload"), TABLES)
def test_repeated_put_is_idempotent(auth_client, url, payload):
    """The phone retries. A retry under the same identity updates the one
    row and never makes a second."""
    first = auth_client.put(f"{url}guid-1/", payload(0), format="json")
    assert first.status_code == 200, first.data

    second = auth_client.put(f"{url}guid-1/", payload(0), format="json")
    assert second.status_code == 200, second.data
    assert second.data["id"] == first.data["id"]

    listed = auth_client.get(url)
    assert len(listed.data["results"]) == 1


@pytest.mark.parametrize(("url", "payload"), TABLES)
def test_put_updates_an_existing_row_in_place(auth_client, url, payload):
    created = auth_client.put(f"{url}guid-1/", payload(0), format="json").data
    updated = auth_client.put(f"{url}guid-1/", payload(1), format="json")
    assert updated.status_code == 200, updated.data
    assert updated.data["id"] == created["id"]
    for field, value in payload(1).items():
        assert updated.data[field] == value, field


@pytest.mark.parametrize(("url", "payload"), DELETABLE)
def test_delete_tombstones_rather_than_deleting(auth_client, url, payload):
    created = auth_client.put(f"{url}guid-1/", payload(0), format="json").data

    first = auth_client.delete(f"{url}guid-1/")
    assert first.status_code == 204

    # The row is still there, carrying a tombstone - a phone that has not
    # synced since still has to learn it is gone.
    feed = auth_client.get(f"{url}?since={EPOCH}")
    rows = {row["id"]: row for row in feed.data["results"]}
    assert created["id"] in rows
    assert rows[created["id"]]["deleted_at"] is not None

    # Idempotent: a second delete is still a 204.
    assert auth_client.delete(f"{url}guid-1/").status_code == 204


@pytest.mark.parametrize(("url", "payload"), DELETABLE)
def test_active_omits_tombstones(auth_client, url, payload):
    auth_client.put(f"{url}gone/", payload(0), format="json")
    kept = auth_client.put(f"{url}kept/", payload(1), format="json").data
    auth_client.delete(f"{url}gone/")

    active = auth_client.get(f"{url}?active=1")
    ids = [row["id"] for row in active.data["results"]]
    assert ids == [kept["id"]]


@pytest.mark.parametrize(("url", "payload"), TABLES)
def test_since_feed_omits_rows_changed_before_the_watermark(auth_client, url, payload):
    """Both timestamps in this comparison come from the DATABASE clock -
    see this module's own doc comment for why that matters and what
    happens when they do not."""
    auth_client.put(f"{url}older/", payload(0), format="json")
    newer = auth_client.put(f"{url}newer/", payload(1), format="json").data
    assert newer["updated_at"] is not None

    feed = auth_client.get(f"{url}?since={newer['updated_at']}")
    ids = [row["id"] for row in feed.data["results"]]
    # `since` is inclusive (>=), matching the phone's own fetchChangedSince
    # contract, so the row created AT the watermark is legitimately present
    # and the one before it is not.
    assert ids == [newer["id"]]


@pytest.mark.parametrize(("url", "payload"), TABLES)
def test_missing_since_returns_everything(auth_client, url, payload):
    auth_client.put(f"{url}a/", payload(0), format="json")
    auth_client.put(f"{url}b/", payload(1), format="json")

    feed = auth_client.get(url)
    assert feed.status_code == 200
    assert len(feed.data["results"]) == 2
    # One page held it, so there is nothing to page to.
    assert feed.data["next"] is None


@pytest.mark.parametrize(("url", "payload"), TABLES)
def test_unknown_field_is_400_naming_the_field(auth_client, url, payload):
    """A limb sending a column this server does not know is a version skew,
    and it is told - never a silent drop that reads like a successful
    write."""
    body = payload(0) | {"vibe": "excellent"}
    response = auth_client.put(f"{url}guid-1/", body, format="json")
    assert response.status_code == 400
    assert "vibe" in str(response.data)


# A value each table's own CHECK constraint would refuse, and the words the
# 400 has to contain. Read from `legacy/CONSTRAINTS.md`, which was read from
# the live schema.
REFUSED = [
    pytest.param(
        "/api/places/",
        lambda i: {"latitude": 1.5, "longitude": -95.0},
        {"latitude": 999.0},
        ("-90", "90"),
        id="places-latitude",
    ),
    pytest.param(
        "/api/body/bodyweight_logs/",
        lambda i: {
            "weight_value": 180.0,
            "weight_unit": "lbs",
            "logged_at": _iso(1),
            "trust_tier": "REPORTED",
        },
        {"trust_tier": "MAYBE"},
        ("PROVEN", "REPORTED"),
        id="bodyweight-trust-tier",
    ),
    pytest.param(
        "/api/body/bodyweight_logs/",
        lambda i: {
            "weight_value": 180.0,
            "weight_unit": "lbs",
            "logged_at": _iso(1),
            "trust_tier": "REPORTED",
        },
        {"weight_unit": "stone"},
        ("lbs", "kg"),
        id="bodyweight-weight-unit",
    ),
    pytest.param(
        "/api/body/meal_logs/",
        lambda i: {"description": "oatmeal", "logged_at": _iso(1), "trust_tier": "REPORTED"},
        {"description": "   "},
        ("description", "blank"),
        id="meal-log-blank-description",
    ),
    pytest.param(
        "/api/body/meal_targets/",
        lambda i: {
            "calories_kcal": 2200,
            "protein_g": 160.0,
            "carbs_g": 200.0,
            "fat_g": 70.0,
            "effective_from_date": _date(1),
        },
        {"calories_kcal": 0},
        ("calories_kcal", "greater than 0"),
        id="meal-target-calories",
    ),
    pytest.param(
        "/api/body/sleep_logs/",
        lambda i: {
            "sleep_date": _date(1),
            "duration_minutes": 420,
            "logged_at": _iso(1),
            "trust_tier": "REPORTED",
        },
        {"quality": 9},
        ("quality", "1", "5"),
        id="sleep-log-quality",
    ),
    pytest.param(
        "/api/body/sleep_targets/",
        lambda i: {"target_minutes": 450, "effective_from_date": _date(1)},
        {"target_minutes": 5000},
        ("target_minutes", "0", "1440"),
        id="sleep-target-minutes",
    ),
    pytest.param(
        "/api/body/workout_plans/",
        lambda i: {"sessions_per_week": 4, "effective_from_week": _date(1)},
        {"sessions_per_week": -1},
        ("sessions_per_week", "at least 0"),
        id="workout-plan-sessions",
    ),
    pytest.param(
        "/api/body/workout_plan_items/",
        lambda i: {
            "exercise": "bench press",
            "target_sets_per_week": 12,
            "effective_from_week": _date(1),
        },
        {"target_sets_per_week": 0},
        ("target_sets_per_week", "greater than 0"),
        id="workout-plan-item-sets",
    ),
    pytest.param(
        "/api/body/workout_set_logs/",
        lambda i: {
            "exercise": "squat",
            "sets": 3,
            "logged_at": _iso(1),
            "trust_tier": "PROVEN",
        },
        {"weight_unit": "stone"},
        ("lbs", "kg"),
        id="workout-set-log-weight-unit",
    ),
    pytest.param(
        "/api/memory/memories/",
        lambda i: {"text": "something", "logged_at": _iso(1)},
        {"text": "   "},
        ("text", "blank"),
        id="memories-blank-text",
    ),
    pytest.param(
        "/api/memory/companion_memories/",
        lambda i: {
            "vehicle_id": "AA:BB",
            "text": "something",
            "category": "driver",
            "source": "stated",
            "logged_at": _iso(1),
        },
        {"category": "friend"},
        ("car_anchored", "driver", "relationship"),
        id="companion-memory-category",
    ),
    pytest.param(
        "/api/memory/memory_audit/",
        lambda i: {
            "event": "written",
            "store": "memories",
            "detail": "wrote one",
            "logged_at": _iso(1),
        },
        {"store": "diary"},
        ("memories", "companion_memories", "speech"),
        id="memory-audit-store",
    ),
    pytest.param(
        "/api/ledger/categories/",
        lambda i: {"name": "groceries", "is_food_category": True},
        {"name": "   "},
        ("name", "blank"),
        id="categories-blank-name",
    ),
    pytest.param(
        "/api/ledger/category_rules/",
        lambda i: {
            "category": "groceries",
            "substring": "COLD STORAGE",
            "created_at_client": _iso(1),
        },
        # A blank substring matches every description, so this one rule would
        # categorise the whole ledger as groceries.
        {"substring": "  "},
        ("substring", "blank"),
        id="category-rules-blank-substring",
    ),
    pytest.param(
        "/api/ledger/budget_targets/",
        lambda i: {
            "category": "groceries",
            "currency": "SGD",
            "amount_cents": 50000,
            "effective_from_month": _date(1),
        },
        {"currency": "EUR"},
        ("SGD", "USD"),
        id="budget-targets-currency",
    ),
    pytest.param(
        "/api/pantry/grocery_staples/",
        lambda i: {
            "name": "milk",
            "display_name": "Milk",
            "last_bought_at": _iso(1),
        },
        {"times_bought": 0},
        ("times_bought", "at least 1"),
        id="grocery-staples-times-bought",
    ),
]


@pytest.mark.parametrize(("url", "payload", "bad", "expected_words"), REFUSED)
def test_a_check_refused_value_is_400_naming_the_allowed_set(
    auth_client, url, payload, bad, expected_words
):
    response = auth_client.put(f"{url}guid-1/", payload(0) | bad, format="json")
    assert response.status_code == 400, response.data
    text = str(response.data)
    for word in expected_words:
        assert word in text, f"{word!r} missing from {text!r}"
    # Nothing partial was written.
    assert auth_client.get(url).data["results"] == []
