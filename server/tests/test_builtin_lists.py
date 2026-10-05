"""The built-in Groceries list (Kevin, 2026-10-05): every household has one,
shared and plain, and it cannot be deleted, archived, renamed or made private.
The purchase-log hook that keys on it is covered in `test_purchases.py`."""
from __future__ import annotations

import importlib

import pytest
from django.apps import apps as django_apps
from django.db import IntegrityError, transaction

from checklists.models import Checklist
from household.models import Household

pytestmark = pytest.mark.django_db

LIST_URL = "/api/checklists/"


def _builtin(client):
    rows = client.get(LIST_URL).data["results"]
    return next(row for row in rows if row["system_key"] == "groceries")


def test_a_new_household_gets_one_empty_shared_plain_groceries_list(db):
    household = Household.objects.create(name="New family")
    lists = Checklist.objects.filter(household=household)
    assert lists.count() == 1
    groceries = lists.get()
    assert (groceries.name, groceries.system_key) == ("Groceries", "groceries")
    assert groceries.owner_user_id is None
    assert groceries.schedule_kind is None
    assert groceries.archived is False
    assert not groceries.items.exists()


def test_each_household_has_its_own_and_only_one(household_a, household_b):
    for household in (household_a, household_b):
        assert (
            Checklist.objects.filter(household=household, system_key="groceries").count() == 1
        )


def test_the_database_allows_one_live_built_in_list_per_household(household_a):
    with pytest.raises(IntegrityError), transaction.atomic():
        Checklist.objects.create(household=household_a, name="Again", system_key="groceries")


def test_the_data_migration_is_idempotent_and_adds_missing_lists_only(household_a, household_b):
    Checklist.objects.filter(household=household_b, system_key="groceries").delete()
    module = importlib.import_module("checklists.migrations.0006_create_builtin_groceries")
    module.create(django_apps, None)
    module.create(django_apps, None)
    for household in (household_a, household_b):
        assert (
            Checklist.objects.filter(household=household, system_key="groceries").count() == 1
        )


def test_the_wire_shape_and_the_changes_feed_carry_system_key(auth_client):
    assert _builtin(auth_client)["name"] == "Groceries"
    feed = auth_client.get("/api/changes?aspects=checklists").data["checklists"]
    assert [row["system_key"] for row in feed if row["system_key"]] == ["groceries"]
    mine = auth_client.post(LIST_URL, {"name": "Hardware"}, format="json")
    assert mine.data["system_key"] is None


def test_a_client_cannot_set_a_system_key(auth_client):
    response = auth_client.post(
        LIST_URL, {"name": "Sneaky", "system_key": "groceries"}, format="json"
    )
    assert response.status_code == 201
    assert response.data["system_key"] is None


def test_the_built_in_list_cannot_be_deleted(auth_client):
    list_id = _builtin(auth_client)["id"]
    response = auth_client.delete(f"{LIST_URL}{list_id}")
    assert response.status_code == 403
    assert response.data["detail"] == (
        "The Groceries list is built in, so it can't be deleted. Nothing was changed."
    )
    assert Checklist.objects.get(pk=list_id).deleted_at is None


@pytest.mark.parametrize(
    ("body", "sentence"),
    [
        ({"name": "Shopping"}, "renamed"),
        ({"archived": True}, "archived"),
        ({"visibility": "private"}, "made private"),
    ],
)
def test_the_built_in_list_cannot_be_renamed_archived_or_made_private(
    auth_client, body, sentence
):
    list_id = _builtin(auth_client)["id"]
    response = auth_client.patch(f"{LIST_URL}{list_id}", body, format="json")
    assert response.status_code == 403
    assert response.data["detail"] == (
        f"The Groceries list is built in, so it can't be {sentence}. Nothing was changed."
    )
    row = Checklist.objects.get(pk=list_id)
    assert (row.name, row.archived, row.owner_user_id) == ("Groceries", False, None)


def test_a_patch_that_changes_nothing_on_the_built_in_list_passes(auth_client):
    """The phone's outbox pushes the whole row, so re-sending the list's own
    name, an unarchived flag and `shared` must not be refused."""
    list_id = _builtin(auth_client)["id"]
    response = auth_client.patch(
        f"{LIST_URL}{list_id}",
        {"name": "Groceries", "archived": False, "visibility": "shared", "sort_order": 3},
        format="json",
    )
    assert response.status_code == 200
    assert Checklist.objects.get(pk=list_id).sort_order == 3


def test_items_and_ticks_on_the_built_in_list_behave_like_any_lists(auth_client):
    list_id = _builtin(auth_client)["id"]
    item = auth_client.post(f"{LIST_URL}{list_id}/items", {"text": "eggs"}, format="json")
    assert item.status_code == 201
    tick = auth_client.post(
        f"{LIST_URL}{list_id}/items/{item.data['id']}/tick", {"day": 20365}, format="json"
    )
    assert tick.status_code == 201
    gone = auth_client.delete(f"{LIST_URL}{list_id}/items/{item.data['id']}")
    assert gone.status_code == 204


def test_an_ordinary_list_is_still_deletable_and_renamable(auth_client):
    made = auth_client.post(LIST_URL, {"name": "Hardware"}, format="json").data["id"]
    patched = auth_client.patch(f"{LIST_URL}{made}", {"name": "Tools"}, format="json")
    assert patched.status_code == 200
    assert auth_client.delete(f"{LIST_URL}{made}").status_code == 204
