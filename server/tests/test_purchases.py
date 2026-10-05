"""The household bought log (purchase-log ticket 06): the table, the REST
routes, ticket 02's matching, the Groceries tick hook (ADR 0055), the backfill
(ticket 03) and the `/mcp` tools.

Household isolation lives in `tests/test_tenancy.py` and member privacy in
`tests/test_visibility.py`, beside every other table's; this file holds what
is particular to purchases.
"""

from __future__ import annotations

import importlib

import pytest
from django.apps import apps as django_apps
from django.db import IntegrityError, transaction
from rest_framework.test import APIClient

from checklists.models import Checklist, ChecklistItem, ChecklistTick
from household.models import DeviceToken
from purchases import groceries
from purchases.matching import fold, match_entries, matches, normalise, say_last_bought
from purchases.models import Purchase
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

DAY = 20365  # 2025-10-04
URL = "/api/purchases/"


@pytest.fixture
def mia(household_a):
    user = _member(household_a, "mia@example.com")
    user.first_name = "Mia"
    user.save(update_fields=["first_name"])
    return user


@pytest.fixture
def mia_client(mia):
    return _client_for(mia)


def _log(client, item, day=DAY, **extra):
    response = client.post(URL, {"item": item, "bought_on": day, **extra}, format="json")
    assert response.status_code == 201, response.data
    return response.data


def _list(client, name="Groceries", **extra):
    response = client.post("/api/checklists/", {"name": name, **extra}, format="json")
    assert response.status_code == 201, response.data
    return response.data["id"]


def _item(client, checklist_id, text):
    response = client.post(
        f"/api/checklists/{checklist_id}/items", {"text": text}, format="json"
    )
    assert response.status_code == 201, response.data
    return response.data["id"]


def _tick(client, checklist_id, item_id, day=DAY):
    response = client.post(
        f"/api/checklists/{checklist_id}/items/{item_id}/tick", {"day": day}, format="json"
    )
    assert response.status_code in (200, 201), response.data
    return response


def _untick(client, checklist_id, item_id, day=DAY, today=None):
    suffix = f"?today={today}" if today is not None else ""
    return client.delete(f"/api/checklists/{checklist_id}/items/{item_id}/tick/{day}{suffix}")


# =============================================================================
# Matching (ticket 02), pure
# =============================================================================


class _Entry:
    def __init__(self, item, bought_on, logged_at=0, source="MANUAL", created_by=None):
        self.item = item
        self.bought_on = bought_on
        self.logged_at = logged_at
        self.source = source
        self.created_by = created_by


def test_the_floor_is_tickmatch_normalisation():
    assert normalise("  Head   &  Shoulders\tShampoo ") == "head & shoulders shampoo"
    assert matches("Shampoo ", "shampoo") == (True, True)


def test_the_loose_layer_is_every_query_word_with_plurals_folded():
    assert matches("shampoo", "Head & Shoulders shampoo") == (True, False)
    assert matches("shampoos", "shampoo") == (True, False)
    assert matches("batteries", "AA battery") == (True, False)
    assert matches("box", "tissue boxes") == (True, False)
    # Words, not substrings, and every word, not any.
    assert matches("oat", "goat milk") == (False, False)
    assert matches("oat milk", "oat bran") == (False, False)
    assert fold("gas") == "gas" and fold("glass") == "glass"
    # Any script's letters make a word; an accent does not split one.
    assert matches("jalapeño", "pickled jalapeños") == (True, False)


def test_every_distinct_match_newest_first_never_one_picked():
    entries = [
        _Entry("shampoo", 100),
        _Entry("Shampoo", 90),
        _Entry("Head & Shoulders shampoo", 120),
        _Entry("conditioner", 130),
    ]
    found = match_entries("shampoo", entries)
    assert [(m.entry.item, m.exact, m.times_logged) for m in found] == [
        ("Head & Shoulders shampoo", False, 1),
        ("shampoo", True, 2),
    ]
    text = say_last_bought("shampoo", found)
    assert text.startswith("2 different entries match 'shampoo'")
    assert "Head & Shoulders shampoo on May 1, 1970" in text


def test_no_match_is_no_record_never_never_bought():
    assert say_last_bought("shampoo", []) == "I have no record of buying shampoo."


# =============================================================================
# REST
# =============================================================================


def test_create_is_manual_shared_and_names_who(auth_client):
    row = _log(auth_client, "  shampoo ", store="Target", price_cents=499, quantity_note="2 pack")
    assert row["item"] == "shampoo"
    assert row["source"] == "MANUAL"
    assert row["visibility"] == "shared"
    assert row["logged_by"] == "kevin"
    assert row["logged_by_me"] is True
    assert row["bought_on_date"] == "2025-10-04"
    assert row["price_cents"] == 499
    assert row["price_note"].startswith("entered by hand")
    assert row["tick"] is None
    assert row["logged_at"] is not None
    for server_only in ("household", "household_id", "created_by", "owner_user"):
        assert server_only not in row


def test_a_retried_create_with_the_same_sync_id_is_a_200_and_one_row(auth_client):
    _log(auth_client, "soap", sync_id="s-1")
    again = auth_client.post(
        URL, {"item": "soap", "bought_on": DAY, "sync_id": "s-1"}, format="json"
    )
    assert again.status_code == 200
    assert Purchase.objects.filter(sync_id="s-1").count() == 1


@pytest.mark.parametrize("price", [-1, 4.99, "499", True, 499.0])
def test_price_must_be_non_negative_whole_cents(auth_client, price):
    response = auth_client.post(
        URL, {"item": "soap", "bought_on": DAY, "price_cents": price}, format="json"
    )
    assert response.status_code == 400, response.data
    assert "Nothing was saved" in str(response.data)
    assert not Purchase.objects.exists()


def test_a_blank_item_is_refused(auth_client):
    response = auth_client.post(URL, {"item": "   ", "bought_on": DAY}, format="json")
    assert response.status_code == 400
    assert not Purchase.objects.exists()


def test_the_database_refuses_a_negative_price_and_a_manual_tick(household_a):
    with pytest.raises(IntegrityError), transaction.atomic():
        Purchase.objects.create(household=household_a, item="x", bought_on=DAY, price_cents=-5)
    checklist = Checklist.objects.create(household=household_a, name="Groceries")
    item = ChecklistItem.objects.create(checklist=checklist, text="x")
    tick = ChecklistTick.objects.create(item=item, day=DAY)
    with pytest.raises(IntegrityError), transaction.atomic():
        Purchase.objects.create(
            household=household_a, item="x", bought_on=DAY, source="MANUAL", tick=tick
        )
    with pytest.raises(IntegrityError), transaction.atomic():
        Purchase.objects.create(household=household_a, item="x", bought_on=DAY, source="BOUGHT")


def test_list_filters_by_text_dates_and_source(auth_client):
    _log(auth_client, "Head & Shoulders shampoo", DAY)
    _log(auth_client, "toothpaste", DAY + 1)
    _log(auth_client, "shampoo", DAY + 2)
    body = auth_client.get(URL).data
    assert [r["item"] for r in body["results"]] == [
        "shampoo",
        "toothpaste",
        "Head & Shoulders shampoo",
    ]
    assert body["message"] is None and body["truncated"] is False
    assert [r["item"] for r in auth_client.get(f"{URL}?q=shampoos").data["results"]] == [
        "shampoo",
        "Head & Shoulders shampoo",
    ]
    ranged = auth_client.get(f"{URL}?from={DAY + 1}&to={DAY + 1}").data["results"]
    assert [r["item"] for r in ranged] == ["toothpaste"]
    assert auth_client.get(f"{URL}?source=GROCERIES_TICK").data["results"] == []
    none = auth_client.get(f"{URL}?q=razor").data
    assert none["results"] == [] and none["message"] == "I have no record of buying razor."
    limited = auth_client.get(f"{URL}?limit=1").data
    assert len(limited["results"]) == 1 and limited["truncated"] is True
    assert auth_client.get(f"{URL}?from=soon").status_code == 400
    assert auth_client.get(f"{URL}?source=BOUGHT").status_code == 400


def test_patch_delete_and_a_deleted_entry_answers_nothing(auth_client):
    row = _log(auth_client, "shampoo")
    patched = auth_client.patch(f"{URL}{row['id']}", {"price_cents": 650}, format="json")
    assert patched.status_code == 200 and patched.data["price_cents"] == 650
    assert auth_client.patch(
        f"{URL}{row['id']}", {"price_cents": -1}, format="json"
    ).status_code == 400
    assert auth_client.delete(f"{URL}{row['id']}").status_code == 204
    assert auth_client.delete(f"{URL}{row['id']}").status_code == 204
    assert Purchase.objects.get(pk=row["id"]).deleted_at is not None
    assert auth_client.get(URL).data["results"] == []
    assert auth_client.get(f"{URL}last-bought?q=shampoo").data["matches"] == []
    assert auth_client.patch(f"{URL}{row['id']}", {"item": "x"}, format="json").status_code == 404


def test_last_bought_names_each_entry_and_who(auth_client, mia_client):
    _log(auth_client, "shampoo", DAY)
    _log(mia_client, "Head & Shoulders shampoo", DAY + 5)
    body = auth_client.get(f"{URL}last-bought?q=shampoo").data
    assert [(m["entry"]["item"], m["exact"]) for m in body["matches"]] == [
        ("Head & Shoulders shampoo", False),
        ("shampoo", True),
    ]
    assert body["matches"][0]["entry"]["logged_by"] == "Mia"
    assert body["matches"][0]["entry"]["logged_by_me"] is False
    assert "Head & Shoulders shampoo on Oct 9, 2025 (Mia)" in body["message"]
    single = auth_client.get(f"{URL}last-bought?q=head shoulders").data
    assert single["message"] == "You logged Head & Shoulders shampoo on Oct 9, 2025 (Mia)."
    none = auth_client.get(f"{URL}last-bought?q=razor").data
    assert none["matches"] == [] and none["message"] == "I have no record of buying razor."
    assert auth_client.get(f"{URL}last-bought?q=%20&").status_code == 400


def test_session_auth_reaches_the_log_like_the_web_does(household_user):
    client = APIClient()
    client.force_login(household_user)
    assert client.post(URL, {"item": "soap", "bought_on": DAY}, format="json").status_code == 201
    assert [r["item"] for r in client.get(URL).data["results"]] == ["soap"]
    assert APIClient().get(URL).status_code in (401, 403)


def test_private_is_set_on_create_and_changed_by_the_rule(auth_client, mia_client):
    mine = _log(auth_client, "razor", visibility="private")
    assert mine["visibility"] == "private"
    assert auth_client.patch(
        f"{URL}{mine['id']}", {"visibility": "shared"}, format="json"
    ).data["visibility"] == "shared"
    # Mia did not log it, so she may not make it private.
    refused = mia_client.patch(f"{URL}{mine['id']}", {"visibility": "private"}, format="json")
    assert refused.status_code == 403
    assert Purchase.objects.get(pk=mine["id"]).owner_user_id is None


# =============================================================================
# The Groceries hook (ADR 0055)
# =============================================================================


def test_a_groceries_tick_logs_one_shared_entry_by_the_ticker(auth_client, household_user):
    groceries_id = _list(auth_client, "Groceries")
    milk = _item(auth_client, groceries_id, "Oat milk")
    tick = _tick(auth_client, groceries_id, milk)
    assert tick.status_code == 201
    entry = Purchase.objects.get()
    assert entry.item == "Oat milk"
    assert entry.bought_on == DAY
    assert entry.created_by_id == household_user.pk
    assert entry.owner_user_id is None
    assert entry.source == "GROCERIES_TICK"
    assert str(entry.tick_id) == tick.data["id"]
    # A double-tap changes nothing, and logs nothing twice.
    assert _tick(auth_client, groceries_id, milk).status_code == 200
    assert Purchase.objects.count() == 1
    # Editing the line later does not rewrite what was bought.
    auth_client.patch(
        f"/api/checklists/{groceries_id}/items/{milk}", {"text": "Almond milk"}, format="json"
    )
    assert Purchase.objects.get().item == "Oat milk"


def test_the_ticker_is_whoever_made_the_request(auth_client, mia_client, mia):
    groceries_id = _list(auth_client, "Groceries")
    eggs = _item(auth_client, groceries_id, "eggs")
    _tick(mia_client, groceries_id, eggs)
    assert Purchase.objects.get().created_by_id == mia.pk
    assert auth_client.get(f"{URL}last-bought?q=eggs").data["message"] == (
        "You logged eggs on Oct 4, 2025 (Mia), ticked off the Groceries list."
    )


def test_a_tick_on_another_list_logs_nothing(auth_client):
    other = _list(auth_client, "Hardware")
    nails = _item(auth_client, other, "nails")
    _tick(auth_client, other, nails)
    assert not Purchase.objects.exists()


def test_a_private_groceries_list_is_not_the_households(auth_client):
    mine = _list(auth_client, "Groceries", visibility="private")
    gum = _item(auth_client, mine, "gum")
    _tick(auth_client, mine, gum)
    assert not Purchase.objects.exists()


def test_the_name_is_trimmed_and_case_free_and_the_oldest_wins(auth_client):
    first = _list(auth_client, "  GROCERIES ")
    second = _list(auth_client, "groceries")
    # One test is one transaction, so both rows carry the same `Now()`. Give
    # the first an earlier birth, as two real requests would.
    Checklist.objects.filter(pk=first).update(created_at="2025-01-01T00:00:00Z")
    on_second = _item(auth_client, second, "bread")
    _tick(auth_client, second, on_second)
    assert not Purchase.objects.exists()
    on_first = _item(auth_client, first, "bread")
    _tick(auth_client, first, on_first)
    assert Purchase.objects.count() == 1
    # Delete the oldest, and the next one becomes the household's list.
    auth_client.delete(f"/api/checklists/{first}")
    _tick(auth_client, second, on_second, DAY + 1)
    assert Purchase.objects.count() == 2


def test_a_same_day_untick_takes_the_entry_back_and_a_retick_revives_it(auth_client):
    groceries_id = _list(auth_client, "Groceries")
    soap = _item(auth_client, groceries_id, "soap")
    _tick(auth_client, groceries_id, soap)
    entry_id = Purchase.objects.get().pk
    assert _untick(auth_client, groceries_id, soap, today=DAY).status_code == 204
    assert Purchase.objects.get().deleted_at is not None
    assert auth_client.get(f"{URL}last-bought?q=soap").data["matches"] == []

    _tick(auth_client, groceries_id, soap)
    revived = Purchase.objects.get()
    assert revived.pk == entry_id and revived.deleted_at is None


def test_a_later_untick_leaves_the_purchase(auth_client):
    groceries_id = _list(auth_client, "Groceries")
    soap = _item(auth_client, groceries_id, "soap")
    _tick(auth_client, groceries_id, soap)
    assert _untick(auth_client, groceries_id, soap, today=DAY + 1).status_code == 204
    assert ChecklistTick.objects.get().deleted_at is not None
    assert Purchase.objects.get().deleted_at is None


def test_without_today_the_untick_falls_back_to_the_utc_date(auth_client, monkeypatch):
    groceries_id = _list(auth_client, "Groceries")
    soap = _item(auth_client, groceries_id, "soap")
    rice = _item(auth_client, groceries_id, "rice")
    _tick(auth_client, groceries_id, soap)
    _tick(auth_client, groceries_id, rice)
    monkeypatch.setattr(groceries, "utc_today", lambda: DAY + 1)
    _untick(auth_client, groceries_id, soap)
    assert Purchase.objects.get(item="soap").deleted_at is None
    monkeypatch.setattr(groceries, "utc_today", lambda: DAY)
    _untick(auth_client, groceries_id, rice)
    assert Purchase.objects.get(item="rice").deleted_at is not None


def test_a_garbled_today_unticks_nothing(auth_client):
    groceries_id = _list(auth_client, "Groceries")
    soap = _item(auth_client, groceries_id, "soap")
    _tick(auth_client, groceries_id, soap)
    response = auth_client.delete(
        f"/api/checklists/{groceries_id}/items/{soap}/tick/{DAY}?today=tuesday"
    )
    assert response.status_code == 400
    assert response.data["detail"].startswith("Nothing was unticked.")
    assert ChecklistTick.objects.get().deleted_at is None
    assert Purchase.objects.get().deleted_at is None


def test_a_backfilled_entry_is_not_taken_back_by_an_untick(auth_client, household_a):
    groceries_id = _list(auth_client, "Groceries")
    tea = _item(auth_client, groceries_id, "tea")
    tick = ChecklistTick.objects.create(item_id=tea, day=DAY)
    Purchase.objects.create(
        household=household_a, item="tea", bought_on=DAY, source="GROCERIES_BACKFILL", tick=tick
    )
    _untick(auth_client, groceries_id, tea, today=DAY)
    assert Purchase.objects.get().deleted_at is None


# =============================================================================
# The backfill (ticket 03)
# =============================================================================


def _backfill():
    module = importlib.import_module("purchases.migrations.0002_backfill_groceries_ticks")
    module.backfill(django_apps, None)


def test_the_backfill_imports_live_groceries_ticks_once(household_a, household_user):
    groceries_list = Checklist.objects.create(household=household_a, name="Groceries")
    other = Checklist.objects.create(household=household_a, name="Garage")
    private = Checklist.objects.create(
        household=household_a, name="Groceries", owner_user=household_user
    )
    milk = ChecklistItem.objects.create(checklist=groceries_list, text="milk")
    bread = ChecklistItem.objects.create(checklist=groceries_list, text="bread")
    gone = ChecklistItem.objects.create(checklist=groceries_list, text="jam")
    oil = ChecklistItem.objects.create(checklist=other, text="oil")
    gum = ChecklistItem.objects.create(checklist=private, text="gum")
    live = ChecklistTick.objects.create(item=milk, day=DAY)
    ChecklistTick.objects.create(item=milk, day=DAY + 3)
    ChecklistTick.objects.create(item=bread, day=DAY, deleted_at="2025-10-05T00:00:00Z")
    ChecklistTick.objects.create(item=gone, day=DAY - 1)
    ChecklistItem.objects.filter(pk=gone.pk).update(deleted_at="2025-10-06T00:00:00Z")
    ChecklistTick.objects.create(item=oil, day=DAY)
    ChecklistTick.objects.create(item=gum, day=DAY)
    # An item renamed after its tick imports under its current text (the
    # migration's own stated caveat).
    ChecklistItem.objects.filter(pk=milk.pk).update(text="oat milk")

    _backfill()
    rows = list(Purchase.objects.order_by("bought_on", "item"))
    assert [(r.item, r.bought_on) for r in rows] == [
        ("jam", DAY - 1),
        ("oat milk", DAY),
        ("oat milk", DAY + 3),
    ]
    assert {r.source for r in rows} == {"GROCERIES_BACKFILL"}
    assert {r.created_by_id for r in rows} == {None}
    assert {r.owner_user_id for r in rows} == {None}
    first = Purchase.objects.get(tick=live)
    live.refresh_from_db()
    assert first.logged_at == live.ticked_at

    _backfill()
    assert Purchase.objects.count() == 3


def test_a_backfilled_entry_reads_as_not_recorded(auth_client, household_a):
    groceries_list = Checklist.objects.create(household=household_a, name="Groceries")
    tea = ChecklistItem.objects.create(checklist=groceries_list, text="tea")
    ChecklistTick.objects.create(item=tea, day=DAY)
    _backfill()
    body = auth_client.get(f"{URL}last-bought?q=tea").data
    assert body["matches"][0]["entry"]["logged_by"] is None
    assert body["message"] == (
        "You logged tea on Oct 4, 2025 (who logged it was not recorded), ticked off the "
        "Groceries list."
    )


# =============================================================================
# /mcp
# =============================================================================


@pytest.fixture
def mcp(settings, household_user):
    from tests.test_engine_mcp import client_with

    settings.LEGION_MCP = True
    return client_with(household_user)


def test_log_purchase_says_what_it_committed(mcp):
    from tests.test_engine_mcp import call

    is_error, text, structured = call(
        mcp,
        "log_purchase",
        {"item": "shampoo", "date": "2026-10-04", "price_cents": 899, "sync_id": "m-1"},
    )
    assert not is_error, text
    assert text.startswith("Logged shampoo as bought on Oct 4, 2026, shared with the household.")
    assert "committed" in text
    assert structured["row"]["price_cents"] == 899
    is_error, text, _ = call(
        mcp, "log_purchase", {"item": "shampoo", "date": "2026-10-04", "sync_id": "m-1"}
    )
    assert not is_error and text.startswith("Nothing was logged")
    assert Purchase.objects.count() == 1

    is_error, text, _ = call(mcp, "log_purchase", {"item": "soap", "date": "Tuesday"})
    assert is_error and text.startswith("Nothing was logged.")
    is_error, text, _ = call(mcp, "log_purchase", {"item": "   ", "date": "2026-10-04"})
    assert is_error and text.startswith("Nothing was logged.")
    assert Purchase.objects.count() == 1


def test_last_bought_and_list_purchases_read_in_words(mcp, auth_client):
    from tests.test_engine_mcp import call

    is_error, text, _ = call(mcp, "last_bought", {"item": "shampoo"})
    assert not is_error
    assert text.startswith("I have no record of buying shampoo.")
    assert "not proof it was never bought" in text

    _log(auth_client, "Head & Shoulders shampoo", DAY, price_cents=650, store="HEB")
    is_error, text, structured = call(mcp, "last_bought", {"item": "shampoo"})
    assert not is_error
    assert text.startswith("You logged Head & Shoulders shampoo on Oct 4, 2025 (kevin).")
    assert "loose" in text
    assert structured["matches"][0]["exact"] is False

    is_error, text, structured = call(mcp, "list_purchases", {"query": "shampoo"})
    assert not is_error
    assert "price 6.50 (entered by hand, never checked)" in text
    assert "at HEB" in text
    is_error, text, _ = call(mcp, "list_purchases", {"from": "2026-01-01"})
    assert not is_error and "real empty result" in text
    is_error, text, _ = call(mcp, "list_purchases", {"from": "last week"})
    assert is_error and text.startswith("Nothing was read.")


def test_delete_purchase_reports_only_what_happened(mcp, auth_client):
    from tests.test_engine_mcp import call

    row = _log(auth_client, "soap")
    is_error, text, _ = call(mcp, "delete_purchase", {"id": row["id"]})
    assert not is_error
    assert text.startswith("Deleted the bought entry for soap on Oct 4, 2025.")
    is_error, text, _ = call(mcp, "delete_purchase", {"id": row["id"]})
    assert not is_error and text.startswith("Nothing was deleted: the entry for soap")
    is_error, text, _ = call(mcp, "delete_purchase", {"id": "not-a-uuid"})
    assert is_error and text.startswith("Nothing was deleted.")
    is_error, text, _ = call(
        mcp, "delete_purchase", {"id": "00000000-0000-0000-0000-000000000000"}
    )
    assert is_error and text.startswith("Nothing was deleted.")


def test_an_mcp_tick_on_groceries_logs_it_for_the_token_user(mcp, auth_client, household_user):
    from tests.test_engine_mcp import call

    groceries_id = _list(auth_client, "Groceries")
    eggs = _item(auth_client, groceries_id, "eggs")
    is_error, text, _ = call(
        mcp,
        "tick_checklist_item",
        {"checklist_id": groceries_id, "item_id": eggs, "date": "2025-10-04"},
    )
    assert not is_error, text
    entry = Purchase.objects.get()
    assert entry.created_by_id == household_user.pk and entry.source == "GROCERIES_TICK"


def test_a_read_token_logs_nothing(settings, household_user):
    from tests.test_engine_mcp import call, client_with

    settings.LEGION_MCP = True
    reader = client_with(household_user, DeviceToken.SCOPE_READ)
    is_error, text, _ = call(reader, "log_purchase", {"item": "soap", "date": "2026-10-04"})
    assert is_error and text.startswith("Nothing was written.")
    assert not Purchase.objects.exists()
