"""`merchant_aliases` (Kevin, 2026-10-07): a merchant name shown in place of a
gated row's bank text. Display only.

The generic synced contract (PUT upsert, tombstone, feed, unknown field) and
the generic leak suite already run over this table from
`tests/test_synced_contract.py` and `tests/test_tenancy.py`. This module holds
what is particular to it:

- **the SQL** refuses a blank substring or display name on its own;
- **the read**: `display_description` on every transaction read, the oldest
  live alias winning, case-insensitive and literal, scoped by household, and
  `description` never changed;
- **the feed** carries the alias rows and the transaction's display name;
- **MCP** reads and writes it through the same routes.
"""
from __future__ import annotations

import uuid
from datetime import timedelta

import pytest
from django.db import DatabaseError, connection, transaction
from django.utils import timezone

from ingest.merchant_aliases import DROP_SQL, TABLE, create_table
from ingest.provisional import commit_provisional
from legacy.enums import Provenance
from legacy.models.ledger import LedgerTransaction, MerchantAlias
from tests.test_ledger_categories_and_paging import a_provisional

pytestmark = pytest.mark.django_db

EPOCH = "1970-01-01T00:00:00Z"
ALIASES = "/api/ledger/merchant_aliases/"
TXNS = "/api/ledger/transactions/"

BANK_TEXT = "JOHN NAUS MD PA COLLEYVILLE TX"


def _iso(day: int) -> str:
    return f"2026-09-{day:02d}T12:00:00Z"


def put_alias(client, substring: str, display_name: str, *, day: int = 1, guid=None):
    guid = guid or uuid.uuid4()
    response = client.put(
        f"{ALIASES}{guid}/",
        {"substring": substring, "display_name": display_name, "created_at_client": _iso(day)},
        format="json",
    )
    assert response.status_code == 200, response.data
    return response.data


def shown(client) -> dict[str, dict]:
    rows = client.get(f"{TXNS}?since={EPOCH}").data["results"]
    return {row["description"]: row for row in rows}


def lines(*descriptions: str) -> list[dict]:
    return [
        {
            "txn_date": f"2026-09-{10 + i:02d}",
            "description": description,
            "amount_cents": -100 * (i + 1),
            "line_ref": f"m:{i}",
        }
        for i, description in enumerate(descriptions)
    ]


@pytest.fixture
def rows(household_a):
    """Card rows in household A whose bank text the aliases below are about."""
    commit_provisional(
        a_provisional(
            lines=lines(
                BANK_TEXT,
                "john naus md pa colleyville tx",
                "UBER *TRIP",
                "SALE 50% OFF STORE",
                "SALE 500 OFF STORE",
                "SHOP A_B 12",
                "SHOP ACB 12",
            )
        ),
        household_a,
    )
    return {r.description: r for r in LedgerTransaction.objects.filter(household=household_a)}


def refused(callable_) -> str:
    with pytest.raises(DatabaseError) as caught:
        with transaction.atomic():
            callable_()
    return str(caught.value)


def an_alias(household, substring: str, display_name: str):
    return MerchantAlias.objects.create(
        id=uuid.uuid4(),
        household=household,
        substring=substring,
        display_name=display_name,
        created_at_client=timezone.now(),
        provenance=Provenance.USER,
        created_at=timezone.now(),
        updated_at=timezone.now(),
        origin_guid=uuid.uuid4(),
    )


# =============================================================================
# The SQL
# =============================================================================


def test_the_migration_function_is_idempotent_and_rebuilds_from_nothing():
    with connection.cursor() as cursor:
        assert create_table(cursor) is None
        assert create_table(cursor) is None
        cursor.execute(DROP_SQL)
        cursor.execute("select to_regclass(%s)", [f"public.{TABLE}"])
        assert cursor.fetchone()[0] is None
        assert create_table(cursor) is None
        cursor.execute("select to_regclass(%s)", [f"public.{TABLE}"])
        assert cursor.fetchone()[0] is not None


def test_sql_refuses_a_blank_substring_and_a_blank_display_name(household_a):
    assert "substring_not_blank" in refused(lambda: an_alias(household_a, "  ", "Walmart"))
    assert "display_name_not_blank" in refused(lambda: an_alias(household_a, "NAUS", " "))


def test_nothing_this_table_needs_lives_in_the_private_schema():
    """The live engine role has no USAGE on `private`: a trigger function
    there passes every test here and fails on deploy."""
    with connection.cursor() as cursor:
        cursor.execute(
            "select p.proname, n.nspname from pg_trigger t "
            "join pg_proc p on p.oid = t.tgfoid join pg_namespace n on n.oid = p.pronamespace "
            "where t.tgrelid = 'public.merchant_aliases'::regclass and not t.tgisinternal"
        )
        triggers = cursor.fetchall()
    assert triggers == [("merchant_alias_touch_updated_at", "public")], triggers


def test_an_update_moves_updated_at(household_a):
    alias = an_alias(household_a, "NAUS", "Walmart")
    MerchantAlias.objects.filter(pk=alias.pk).update(
        display_name="Walmart Supercenter", updated_at=timezone.now() - timedelta(days=9)
    )
    alias.refresh_from_db()
    assert timezone.now() - alias.updated_at < timedelta(days=1)


# =============================================================================
# The API
# =============================================================================


@pytest.mark.parametrize(
    ("bad", "field"),
    [({"substring": "   "}, "substring"), ({"display_name": ""}, "display_name")],
)
def test_a_blank_substring_or_display_name_is_refused_in_words(auth_client, bad, field):
    body = {"substring": "NAUS", "display_name": "Walmart", "created_at_client": _iso(1)} | bad
    response = auth_client.put(f"{ALIASES}{uuid.uuid4()}/", body, format="json")
    assert response.status_code == 400, response.data
    # DRF trims and refuses an empty string before `validate_<field>` runs
    # ("This field may not be blank."), keyed by the field - the same words
    # `category_rules` answers with; `blank_error` is the backstop behind it.
    assert field in response.data and "blank" in str(response.data[field]), response.data
    assert auth_client.get(ALIASES).data["results"] == []


def test_the_row_is_the_contract_shape(auth_client):
    guid = uuid.uuid4()
    row = put_alias(auth_client, "JOHN NAUS", "Walmart", guid=guid)
    assert set(row) == {
        "id",
        "substring",
        "display_name",
        "created_at_client",
        "provenance",
        "created_at",
        "updated_at",
        "deleted_at",
        "origin_guid",
    }
    assert row["origin_guid"] == str(guid)
    assert row["provenance"] == "USER"
    assert row["deleted_at"] is None


def test_created_at_client_defaults_to_the_server_clock_and_a_put_keeps_it(auth_client):
    guid = uuid.uuid4()
    created = auth_client.put(
        f"{ALIASES}{guid}/", {"substring": "NAUS", "display_name": "Walmart"}, format="json"
    )
    assert created.status_code == 200, created.data
    assert created.data["created_at_client"] is not None

    renamed = auth_client.put(
        f"{ALIASES}{guid}/", {"substring": "NAUS", "display_name": "Wal-Mart"}, format="json"
    )
    assert renamed.status_code == 200, renamed.data
    assert renamed.data["created_at_client"] == created.data["created_at_client"]
    assert renamed.data["id"] == created.data["id"]


def test_an_identity_that_is_not_a_uuid_is_not_a_route(auth_client):
    response = auth_client.put(
        f"{ALIASES}not-a-uuid/", {"substring": "NAUS", "display_name": "Walmart"}, format="json"
    )
    assert response.status_code == 404


# =============================================================================
# The read: display_description
# =============================================================================


def test_display_description_is_null_with_no_alias(auth_client, rows):
    row = shown(auth_client)[BANK_TEXT]
    assert row["display_description"] is None
    assert row["description"] == BANK_TEXT


def test_a_matching_alias_names_the_row_case_insensitively(auth_client, rows):
    put_alias(auth_client, "John Naus", "Walmart")
    listed = shown(auth_client)
    assert listed[BANK_TEXT]["display_description"] == "Walmart"
    assert listed["john naus md pa colleyville tx"]["display_description"] == "Walmart"
    assert listed["UBER *TRIP"]["display_description"] is None


def test_description_stays_the_bank_text(auth_client, rows):
    put_alias(auth_client, "JOHN NAUS", "Walmart")
    row = shown(auth_client)[BANK_TEXT]
    assert row["description"] == BANK_TEXT
    # And on the stored row, which the gate's trigger would refuse to change anyway.
    assert LedgerTransaction.objects.get(pk=rows[BANK_TEXT].pk).description == BANK_TEXT


def test_an_alias_changes_no_category(auth_client, rows):
    before = shown(auth_client)[BANK_TEXT]
    put_alias(auth_client, "JOHN NAUS", "Walmart")
    after = shown(auth_client)[BANK_TEXT]
    for field in ("category", "category_source", "stored_category", "category_pending"):
        assert after[field] == before[field], field


def test_the_oldest_live_alias_wins_and_a_tombstone_steps_aside(auth_client, rows):
    # Written second, but its client instant is older: it governs.
    newer = put_alias(auth_client, "JOHN NAUS", "Walmart", day=5)
    older = put_alias(auth_client, "NAUS MD", "Dr Naus", day=2)
    assert shown(auth_client)[BANK_TEXT]["display_description"] == "Dr Naus"

    assert auth_client.delete(f"{ALIASES}{older['origin_guid']}/").status_code == 204
    assert shown(auth_client)[BANK_TEXT]["display_description"] == "Walmart"

    assert auth_client.delete(f"{ALIASES}{newer['origin_guid']}/").status_code == 204
    assert shown(auth_client)[BANK_TEXT]["display_description"] is None


def test_another_households_alias_never_applies(auth_client, token_b, rows):
    put_alias(token_b, "JOHN NAUS", "Household B's name")
    assert shown(auth_client)[BANK_TEXT]["display_description"] is None
    assert auth_client.get(ALIASES).data["results"] == []


def test_percent_and_underscore_in_a_substring_match_literally(auth_client, rows):
    put_alias(auth_client, "50%", "Half-price shop")
    put_alias(auth_client, "a_b", "A-and-B")
    listed = shown(auth_client)
    assert listed["SALE 50% OFF STORE"]["display_description"] == "Half-price shop"
    assert listed["SALE 500 OFF STORE"]["display_description"] is None
    assert listed["SHOP A_B 12"]["display_description"] == "A-and-B"
    assert listed["SHOP ACB 12"]["display_description"] is None


def test_the_detail_route_carries_it_too(auth_client, rows):
    put_alias(auth_client, "JOHN NAUS", "Walmart")
    detail = auth_client.get(f"{TXNS}{rows[BANK_TEXT].pk}/").data
    assert detail["display_description"] == "Walmart"
    assert detail["description"] == BANK_TEXT


def test_the_changes_feed_carries_the_alias_and_the_display_name(auth_client, rows):
    put_alias(auth_client, "JOHN NAUS", "Walmart")
    body = auth_client.get(f"/api/changes?since={EPOCH}&aspects=ledger").data
    assert [row["display_name"] for row in body["merchant_aliases"]] == ["Walmart"]
    fed = {row["description"]: row for row in body["ledger_transactions"]}
    assert fed[BANK_TEXT]["display_description"] == "Walmart"
    assert fed["UBER *TRIP"]["display_description"] is None


# =============================================================================
# MCP
# =============================================================================


def test_mcp_writes_an_alias_and_reads_the_display_name_with_its_words(
    settings, household_user, rows
):
    from engine_mcp.tools import DELETABLE, WRITABLE
    from tests.test_engine_mcp import call, client_with

    settings.LEGION_MCP = True
    assert "merchant_aliases" in WRITABLE and "merchant_aliases" in DELETABLE
    mcp = client_with(household_user)

    is_error, text, _ = call(
        mcp,
        "write_record",
        {
            "table": "merchant_aliases",
            "identity": str(uuid.uuid4()),
            "fields": {"substring": "JOHN NAUS", "display_name": "Walmart"},
        },
    )
    assert not is_error, text

    is_error, text, structured = call(
        mcp, "read_records", {"table": "ledger_transactions", "limit": 200}
    )
    assert not is_error, text
    row = next(r for r in structured["rows"] if r["description"] == BANK_TEXT)
    assert row["display_description"] == "Walmart"
    assert "bank's own text" in row["_engine_says"]

    is_error, text, _ = call(
        mcp,
        "write_record",
        {"table": "merchant_aliases", "identity": "not-a-uuid", "fields": {}},
    )
    assert is_error and "Nothing was written" in text
