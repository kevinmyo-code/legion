"""`/api/ledger/*`, `/api/pantry/*` and `/api/ingest/files/` - django-engine
ticket 04's ledger and pantry routes.

Two halves, and the split is the whole point of the ticket:

- **The gated tables** (`statements`, `ledger_transactions`, `receipts`,
  `receipt_line_items`) plus `ingested_files` are READ ONLY. This module
  proves the reads work, that the feed pages, and that a PUT or a DELETE
  comes back 405 with a sentence naming the gate. `test_synced_contract.py`
  cannot cover them: its assertions are built on PUT-creates-a-row, which is
  exactly what these tables refuse.
- **The config tables** (`categories`, `category_rules`, `budget_targets`,
  `grocery_staples`) are ordinary CRUD and ARE in
  `test_synced_contract.py`'s parametrized list, because they are the uniform
  shape with nothing to say about it. Only the money assertions live here.

## Rows here are made by posting to the real gate, never by an ORM insert

`ingest/views.py` is the only writer these four tables have, so a test that
inserted rows with `Model.objects.create` would be reading back something no
production code path can produce - and would keep passing if the commit path
started writing a different shape. The payload factories come from
`tests/test_ingest_api.py` for the same reason: one definition of "a receipt
that ties out", not two that drift.
"""
from __future__ import annotations

import json
import uuid

import pytest
from rest_framework.test import APIClient

from api.sync import PAGE_SIZE
from legacy.enums import IngestState, Provenance
from tests.test_ingest_api import a_receipt, a_statement

pytestmark = pytest.mark.django_db

EPOCH = "1970-01-01T00:00:00Z"

STATEMENTS = "/api/ledger/statements/"
TRANSACTIONS = "/api/ledger/transactions/"
RECEIPTS = "/api/pantry/receipts/"
LINE_ITEMS = "/api/pantry/line-items/"
FILES = "/api/ingest/files/"

CATEGORIES = "/api/ledger/categories/"
CATEGORY_RULES = "/api/ledger/category_rules/"
BUDGET_TARGETS = "/api/ledger/budget_targets/"
GROCERY_STAPLES = "/api/pantry/grocery_staples/"

# Every route this ticket adds that must never accept a write, and the gate
# endpoint each one's 405 has to name. `ingested_files` names both, because a
# file row is a side effect of either commit rather than a thing you post on
# its own.
GATED = [
    pytest.param(STATEMENTS, "/api/ingest/statement", id="statements"),
    pytest.param(TRANSACTIONS, "/api/ingest/statement", id="ledger_transactions"),
    pytest.param(RECEIPTS, "/api/ingest/receipt", id="receipts"),
    pytest.param(LINE_ITEMS, "/api/ingest/receipt", id="receipt_line_items"),
    pytest.param(FILES, "/api/ingest/receipt", id="ingested_files"),
]

ALL_NEW_ROUTES = [
    STATEMENTS,
    TRANSACTIONS,
    RECEIPTS,
    LINE_ITEMS,
    FILES,
    CATEGORIES,
    CATEGORY_RULES,
    BUDGET_TARGETS,
    GROCERY_STAPLES,
]


def rows(response) -> list[dict]:
    """The `results` of a since-feed, read from the rendered JSON rather than
    from `response.data`.

    `response.data` holds Python objects - an `int` stays an `int` whatever
    the renderer would have done with it - so a money assertion made against
    it proves nothing about what a client receives. Everything in this module
    that cares about a WIRE type goes through here.
    """
    return json.loads(response.content)["results"]


def commit_a_statement(client, **overrides):
    response = client.post("/api/ingest/statement", a_statement(**overrides), format="json")
    assert response.status_code == 201, response.data
    return response.data


def commit_a_receipt(client, **overrides):
    response = client.post("/api/ingest/receipt", a_receipt(**overrides), format="json")
    assert response.status_code == 201, response.data
    return response.data


# ---------------------------------------------------------------------------
# Authentication. Everything, including the routes that only ever refuse.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("url", ALL_NEW_ROUTES)
def test_unauthenticated_list_is_401(url):
    assert APIClient().get(url).status_code == 401


@pytest.mark.parametrize(("url", "gate"), GATED)
def test_unauthenticated_put_on_a_gated_table_is_401_not_405(url, gate):
    """Order matters: DRF runs authentication in `initial()` before it picks a
    handler, so an anonymous caller learns it is anonymous and NOT what the
    route would have said to a member. A 405 here would tell a stranger which
    tables exist."""
    assert APIClient().put(f"{url}{uuid.uuid4()}/", {}, format="json").status_code == 401


def test_unauthenticated_changes_is_401():
    assert APIClient().get("/api/changes?aspects=ledger,pantry").status_code == 401


# ---------------------------------------------------------------------------
# No write verbs at all. The 405 names the gate.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(("url", "gate"), GATED)
@pytest.mark.parametrize("method", ["put", "delete"])
def test_a_write_to_a_gated_detail_route_is_405_naming_the_gate(auth_client, url, gate, method):
    response = getattr(auth_client, method)(f"{url}{uuid.uuid4()}/")

    assert response.status_code == 405, response.data
    detail = response.data["detail"]
    # It says what did NOT happen (CLAUDE.md section 7)...
    expected = "Nothing was deleted." if method == "delete" else "Nothing was written."
    assert detail.startswith(expected)
    # ...why...
    assert "reconciliation gate" in detail
    # ...and the way in, which is the half a bare 405 leaves out.
    assert gate in detail


@pytest.mark.parametrize(("url", "gate"), GATED)
def test_a_post_to_a_gated_list_route_is_405_naming_the_gate(auth_client, url, gate):
    """The list route refuses too. A client that reasoned "PUT is refused, so
    the create must be a POST to the collection" gets the same answer."""
    response = auth_client.post(url, {}, format="json")

    assert response.status_code == 405, response.data
    assert gate in response.data["detail"]


def test_the_gate_endpoints_themselves_still_route(auth_client):
    """`/api/ingest/files/` is generated in `api/urls.py` while
    `/api/ingest/statement` lives in `ingest/urls.py`, and `legion/urls.py`
    mounts the first include before the second. This is the assertion that
    the resolver still falls through to the gate rather than stopping at the
    new prefix."""
    posted = auth_client.post("/api/ingest/statement", a_statement(), format="json")
    assert posted.status_code == 201, posted.data


# ---------------------------------------------------------------------------
# The reads
# ---------------------------------------------------------------------------


def test_a_committed_statement_and_its_transactions_are_readable(auth_client):
    committed = commit_a_statement(auth_client)

    statements = rows(auth_client.get(f"{STATEMENTS}?since={EPOCH}"))
    assert [s["id"] for s in statements] == [committed["statement_id"]]
    header = statements[0]
    # Section 4 rule 8: the anchors the gate checked against are retrievable,
    # not merely a `provenance` tag asserting a verdict nobody can re-run.
    assert header["stated_total_cents"] == 149550
    assert header["opening_balance_cents"] == 500000
    assert header["closing_balance_cents"] == 649550
    assert header["provenance"] == Provenance.DETERMINISTIC

    transactions = rows(auth_client.get(f"{TRANSACTIONS}?since={EPOCH}"))
    assert len(transactions) == 3
    assert {t["statement_id"] for t in transactions} == {committed["statement_id"]}
    assert sorted(t["amount_cents"] for t in transactions) == [-150000, -450, 300000]


def test_a_committed_receipt_and_its_line_items_are_readable(auth_client):
    committed = commit_a_receipt(auth_client)

    receipts = rows(auth_client.get(f"{RECEIPTS}?since={EPOCH}"))
    assert [r["id"] for r in receipts] == [committed["receipt_id"]]
    header = receipts[0]
    assert header["total_cents"] == 1242
    assert header["subtotal_cents"] == 1150
    assert header["tax_cents"] == 92
    # Null on a receipt that ties out. Rule 7's column, and its null is the
    # healthy case.
    assert header["unaccounted_cents"] is None

    lines = rows(auth_client.get(f"{LINE_ITEMS}?since={EPOCH}"))
    assert len(lines) == 2
    assert {line["receipt_id"] for line in lines} == {committed["receipt_id"]}
    assert sorted(line["total_price_cents"] for line in lines) == [450, 700]


def test_a_quarantined_file_is_readable_with_its_reason(auth_client):
    """The one row a refused document DOES write. `ingest.views._quarantine`:
    "a quarantined document leaves a reason and no data". Without this route a
    quarantined statement is indistinguishable from one nobody ever sent."""
    refused = auth_client.post(
        "/api/ingest/statement",
        a_statement(content_sha256="sha-off-by-one", stated_total_cents=149551),
        format="json",
    )
    assert refused.status_code == 200, refused.data
    assert refused.data["outcome"] == "QUARANTINED"

    files = rows(auth_client.get(f"{FILES}?since={EPOCH}"))
    quarantined = [f for f in files if f["content_sha256"] == "sha-off-by-one"]
    assert len(quarantined) == 1
    assert quarantined[0]["state"] == IngestState.QUARANTINED
    assert quarantined[0]["quarantine_reason"]

    # And nothing partial was written, which is the claim the reason stands on.
    assert rows(auth_client.get(f"{STATEMENTS}?since={EPOCH}")) == []
    assert rows(auth_client.get(f"{TRANSACTIONS}?since={EPOCH}")) == []


def test_retrieve_returns_one_gated_row_and_404s_for_an_unknown_id(auth_client):
    committed = commit_a_receipt(auth_client)

    found = auth_client.get(f"{RECEIPTS}{committed['receipt_id']}/")
    assert found.status_code == 200
    assert found.data["id"] == committed["receipt_id"]

    missing = auth_client.get(f"{RECEIPTS}{uuid.uuid4()}/")
    assert missing.status_code == 404
    assert "Nothing was changed" in missing.data["detail"]


# ---------------------------------------------------------------------------
# The feed: keyed on created_at, no tombstones, and it pages
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(("url", "gate"), GATED)
def test_a_gated_feed_carries_no_tombstone_columns(auth_client, url, gate):
    """These five tables have no `updated_at` and no `deleted_at` - live
    schema, 2026-09-07. So "tombstones included" cannot apply to them, and the
    rows say so by not carrying the columns. A client must not look for a
    tombstone that can never arrive."""
    commit_a_statement(auth_client)
    commit_a_receipt(auth_client)

    for row in rows(auth_client.get(f"{url}?since={EPOCH}")):
        assert "deleted_at" not in row
        assert "updated_at" not in row


def test_the_feed_is_keyed_on_created_at(auth_client):
    first = commit_a_statement(auth_client)
    watermark = rows(auth_client.get(f"{STATEMENTS}?since={EPOCH}"))[0]["created_at"]
    second = commit_a_statement(
        auth_client, content_sha256="sha-statement-two", account_last4="9999"
    )

    later = rows(auth_client.get(f"{STATEMENTS}?since={watermark}"))
    ids = [s["id"] for s in later]
    # `since` is inclusive (>=), the same boundary every other feed in this API
    # uses and the one `fetchChangedTransactionsSince` states on the phone side,
    # so the row AT the watermark is legitimately present.
    assert first["statement_id"] in ids
    assert second["statement_id"] in ids

    # A watermark after both returns neither.
    ahead = rows(auth_client.get(f"{STATEMENTS}?since=2099-01-01T00:00:00Z"))
    assert ahead == []


def test_active_is_accepted_and_narrows_nothing_on_a_gated_table(auth_client):
    """`?active=1` cannot narrow a table with no `deleted_at`, because every
    row that exists is live. It is accepted rather than refused - a client
    sending it is not wrong, it is asking for the set it already gets - and it
    is left OUT of the schema for these routes so nobody generates a filter
    that does nothing."""
    commit_a_receipt(auth_client)

    everything = rows(auth_client.get(f"{RECEIPTS}?since={EPOCH}"))
    narrowed = rows(auth_client.get(f"{RECEIPTS}?since={EPOCH}&active=1"))
    assert narrowed == everything
    assert len(everything) == 1


def test_a_full_page_hands_back_a_next_cursor_and_a_short_one_does_not(auth_client):
    """One statement with `PAGE_SIZE + 1` lines, so the transactions feed has
    to page. Cheaper and more honest than 501 separate commits: it is one
    document, gated once, which is how a real statement of that size arrives.
    """
    line_count = PAGE_SIZE + 1
    lines = [
        {
            "txn_date": "2026-07-03",
            "description": f"LINE {n}",
            "amount_cents": -100,
            "line_ref": f"page-{n}",
        }
        for n in range(line_count)
    ]
    total = -100 * line_count
    commit_a_statement(
        auth_client,
        content_sha256="sha-statement-paged",
        stated_total_cents=total,
        opening_balance_cents=100000,
        closing_balance_cents=100000 + total,
        lines=lines,
    )

    first_page = json.loads(auth_client.get(f"{TRANSACTIONS}?since={EPOCH}").content)
    assert len(first_page["results"]) == PAGE_SIZE
    assert first_page["next"] is not None
    # DRF's own rendering, `Z`-suffixed - a `+00:00` would decode to a literal
    # space when handed straight back on a query string.
    assert first_page["next"].endswith("Z")

    short = json.loads(auth_client.get(f"{RECEIPTS}?since={EPOCH}").content)
    assert short["next"] is None


# ---------------------------------------------------------------------------
# Money is integer cents (CLAUDE.md section 4 rule 3)
# ---------------------------------------------------------------------------

# Every `*_cents` field this ticket puts on the wire, and the route that
# carries it. Read from `information_schema.columns` on 2026-09-07: all of them
# are `bigint` in Postgres, so all of them must be JSON integers here. A float
# or a string in this list is the bug - a `Decimal` rendered as a string reads
# as a number to a person and decodes to one in a client that is not careful,
# and the gate's exact-equality arithmetic has no tolerance for either.
CENTS_FIELDS = [
    pytest.param(STATEMENTS, "stated_total_cents", id="statements-stated_total"),
    pytest.param(STATEMENTS, "opening_balance_cents", id="statements-opening"),
    pytest.param(STATEMENTS, "closing_balance_cents", id="statements-closing"),
    pytest.param(TRANSACTIONS, "amount_cents", id="transactions-amount"),
    pytest.param(RECEIPTS, "total_cents", id="receipts-total"),
    pytest.param(RECEIPTS, "subtotal_cents", id="receipts-subtotal"),
    pytest.param(RECEIPTS, "tax_cents", id="receipts-tax"),
    pytest.param(LINE_ITEMS, "unit_price_cents", id="line-items-unit_price"),
    pytest.param(LINE_ITEMS, "total_price_cents", id="line-items-total_price"),
]


@pytest.mark.parametrize(("url", "field"), CENTS_FIELDS)
def test_a_cents_field_is_a_json_integer(auth_client, url, field):
    commit_a_statement(auth_client)
    commit_a_receipt(auth_client)

    values = [row[field] for row in rows(auth_client.get(f"{url}?since={EPOCH}"))]
    assert values, f"no rows carried {field}"
    stated = [value for value in values if value is not None]
    assert stated, f"every row had a null {field}; the type was never exercised"
    for value in stated:
        # `bool` is an `int` in Python and would slip through a bare isinstance.
        assert type(value) is int, f"{field} came back as {type(value).__name__}: {value!r}"


def test_amount_cents_round_trips_as_an_integer(auth_client):
    """`budget_targets` is the only writable `*_cents` column this ticket adds,
    so it is where the WRITE half of rule 3 can be checked at all. The value is
    larger than a 32-bit int on purpose: the column is `bigint`."""
    response = auth_client.put(
        f"{BUDGET_TARGETS}guid-1/",
        {
            "category": "groceries",
            "currency": "SGD",
            "amount_cents": 9_876_543_210,
            "effective_from_month": "2026-09-01",
        },
        format="json",
    )

    assert response.status_code == 200, response.data
    stored = json.loads(response.content)["amount_cents"]
    assert type(stored) is int
    assert stored == 9_876_543_210


def test_a_fractional_amount_cents_is_refused(auth_client):
    """Half a cent is not a thing this app can store, and rounding it silently
    would be the start of a figure that no longer ties out. 400, not a quiet
    truncation."""
    response = auth_client.put(
        f"{BUDGET_TARGETS}guid-1/",
        {
            "category": "groceries",
            "currency": "SGD",
            "amount_cents": 12.5,
            "effective_from_month": "2026-09-01",
        },
        format="json",
    )

    assert response.status_code == 400, response.data
    assert "amount_cents" in str(response.data)
    # Nothing partial was written.
    assert rows(auth_client.get(BUDGET_TARGETS)) == []


# ---------------------------------------------------------------------------
# The config tables. CRUD itself is `test_synced_contract.py`'s; this is the
# part that is specific to these four.
# ---------------------------------------------------------------------------


def test_a_config_row_tombstones_and_the_feed_still_carries_it(auth_client):
    """The contrast that makes the gated half legible. These four tables DO
    have `deleted_at`, so a delete here leaves a tombstone a phone can learn
    from - the thing the gated tables structurally cannot do."""
    created = auth_client.put(
        f"{GROCERY_STAPLES}guid-1/",
        {"name": "milk", "display_name": "Milk", "last_bought_at": "2026-09-01T12:00:00Z"},
        format="json",
    ).data
    assert auth_client.delete(f"{GROCERY_STAPLES}guid-1/").status_code == 204

    feed = {row["id"]: row for row in rows(auth_client.get(f"{GROCERY_STAPLES}?since={EPOCH}"))}
    assert feed[created["id"]]["deleted_at"] is not None
    assert rows(auth_client.get(f"{GROCERY_STAPLES}?active=1")) == []


def test_a_second_budget_target_for_one_category_month_is_refused(auth_client):
    """`budget_targets_category_currency_month_unique`, and the refusal has to
    reach the caller as a 400 rather than a 500 - `api/sync.save_or_400`'s job.
    Two different `origin_guid`s, one slot."""
    fields = {
        "category": "groceries",
        "currency": "SGD",
        "amount_cents": 50000,
        "effective_from_month": "2026-09-01",
    }
    assert auth_client.put(f"{BUDGET_TARGETS}guid-1/", fields, format="json").status_code == 200

    clash = auth_client.put(f"{BUDGET_TARGETS}guid-2/", fields, format="json")
    assert clash.status_code == 400, clash.data
    assert len(rows(auth_client.get(BUDGET_TARGETS))) == 1


def test_a_category_rule_keeps_the_phones_own_write_instant(auth_client):
    """`created_at_client` is not `created_at`. The phone orders rules by the
    first, oldest first, so a server that overwrote it with its own insert
    clock would silently reorder every rule around whenever it happened to
    sync."""
    response = auth_client.put(
        f"{CATEGORY_RULES}guid-1/",
        {"category": "groceries", "substring": "COLD STORAGE",
         "created_at_client": "2026-01-02T03:04:05Z"},
        format="json",
    )

    assert response.status_code == 200, response.data
    assert response.data["created_at_client"] == "2026-01-02T03:04:05Z"
    assert response.data["created_at"] != response.data["created_at_client"]


# ---------------------------------------------------------------------------
# The changes feed
# ---------------------------------------------------------------------------


def test_changes_returns_the_ledger_and_pantry_tables(auth_client):
    commit_a_statement(auth_client)
    commit_a_receipt(auth_client)
    auth_client.put(
        f"{CATEGORIES}guid-1/", {"name": "groceries", "is_food_category": True}, format="json"
    )

    body = auth_client.get("/api/changes?aspects=ledger,pantry").data

    # One key per TABLE, named for the table - `ledger_transactions`, not
    # `transactions`, even though the route reads `/api/ledger/transactions/`.
    assert set(body) >= {
        "categories",
        "category_rules",
        "budget_targets",
        "statements",
        "ledger_transactions",
        "receipts",
        "receipt_line_items",
    }
    assert len(body["statements"]) == 1
    assert len(body["ledger_transactions"]) == 3
    assert len(body["receipts"]) == 1
    assert len(body["receipt_line_items"]) == 2
    assert len(body["categories"]) == 1
    # Not asked for, so not populated.
    assert "places" not in body
    assert "ingested_files" not in body


def test_changes_accepts_ingest_and_returns_the_file_ledger(auth_client):
    commit_a_receipt(auth_client)

    body = auth_client.get("/api/changes?aspects=ingest").data

    assert len(body["ingested_files"]) == 1
    assert body["ingested_files"][0]["state"] == IngestState.INGESTED


def test_changes_with_no_aspects_includes_the_new_tables(auth_client):
    """Blank means every known aspect - `api/changes.py`'s own "absence is
    never evidence of wanting less". A table routed but missing from the
    default response would be one a client never learns exists."""
    commit_a_receipt(auth_client)

    body = auth_client.get("/api/changes").data

    for key in ("statements", "ledger_transactions", "receipts", "receipt_line_items",
                "ingested_files", "categories", "category_rules", "budget_targets",
                "grocery_staples"):
        assert key in body, key
    assert len(body["receipts"]) == 1


# The "an unknown aspect is a 400 that names every KNOWN one" assertion is
# `test_changes_api.py`'s `test_an_unknown_aspect_names_the_new_ones_too`,
# extended by this ticket with `ledger`, `pantry` and `ingest` rather than
# copied here. One list of every aspect that exists, in the module that owns
# the feed - a second copy would be free to fall behind it.
