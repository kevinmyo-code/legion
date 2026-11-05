"""The ID vault (ADR 0076, memory-and-vault ticket 05).

Kevin, 2026-11-05: phone only, behind fingerprint; never on the web, never readable by Claude,
never spoken aloud, never sent to the AI model, never logged. These tests hold the engine's half:
the value never leaves except through a member's own reveal, and it is nowhere else.
"""

from __future__ import annotations

import json
import logging
from datetime import timedelta
from pathlib import Path

import pytest
from cryptography.fernet import Fernet
from django.core.cache import cache
from django.utils import timezone
from rest_framework.test import APIClient

from claude_session.models import SCOPE_FULL
from household.models import DeviceToken, Household, HouseholdMember, User
from id_vault.models import VaultItem, VaultReveal
from tests.conftest import _client_for, _member

URL = "/api/vault/items"
# Not a real number of anyone's: a shape only, used to grep for leaks.
VALUE = "E9876543Z-TESTVALUE"
UNLOCKED = {"HTTP_X_LEGION_UNLOCKED": "biometric"}


@pytest.fixture(autouse=True)
def vault_key(monkeypatch):
    monkeypatch.setenv("LEGION_VAULT_KEY", Fernet.generate_key().decode())
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def mia(household_a):
    return _member(household_a, "mia@example.com")


def _add(client, label="Passport", value=VALUE, kind="passport"):
    response = client.post(URL, {"label": label, "kind": kind, "value": value}, format="json")
    assert response.status_code == 201, response.data
    return response.data


def _browser(user):
    client = APIClient()
    client.force_login(user)
    return client


def test_create_and_list_never_answer_with_the_value(auth_client):
    created = _add(auth_client)
    assert VALUE not in json.dumps(created)
    assert set(created) == {"id", "label", "kind", "created_at", "updated_at", "last_revealed_at"}
    listed = auth_client.get(URL)
    assert listed.status_code == 200 and VALUE not in json.dumps(listed.data)
    assert [row["label"] for row in listed.data["results"]] == ["Passport"]
    item = VaultItem.objects.get()
    assert VALUE.encode() not in bytes(item.ciphertext)


def test_reveal_needs_the_fingerprint_header_and_is_audited_without_the_value(auth_client):
    item = _add(auth_client)
    refused = auth_client.post(f"{URL}/{item['id']}/reveal")
    assert refused.status_code == 403 and "fingerprint" in refused.data["detail"]
    assert VaultReveal.objects.count() == 0
    shown = auth_client.post(f"{URL}/{item['id']}/reveal", **UNLOCKED)
    assert shown.status_code == 200
    assert shown.data["value"] == VALUE
    assert shown["Cache-Control"] == "no-store"
    reveal = VaultReveal.objects.get()
    assert reveal.label == "Passport" and reveal.device_name == "Test client"
    row = {field.name: getattr(reveal, field.name) for field in VaultReveal._meta.fields}
    assert VALUE not in str(row)
    assert VaultItem.objects.get().last_revealed_at is not None


def test_update_changes_the_value_and_still_never_answers_with_it(auth_client):
    item = _add(auth_client)
    updated = auth_client.patch(
        f"{URL}/{item['id']}", {"value": "NEW-TESTVALUE-1", "label": "Old passport"}, format="json"
    )
    assert updated.status_code == 200 and "NEW-TESTVALUE-1" not in json.dumps(updated.data)
    shown = auth_client.post(f"{URL}/{item['id']}/reveal", **UNLOCKED)
    assert shown.data["value"] == "NEW-TESTVALUE-1" and shown.data["label"] == "Old passport"


def test_the_web_may_enter_a_value_but_never_reveal_or_delete_one(household_user):
    """Ticket 15 (KIV): write-only web entry needs no engine change."""
    web = _browser(household_user)
    created = web.post(URL, {"label": "NRIC", "kind": "nric", "value": VALUE}, format="json")
    assert created.status_code == 201 and VALUE not in json.dumps(created.data)
    assert web.patch(
        f"{URL}/{created.data['id']}", {"value": "OTHER-TESTVALUE"}, format="json"
    ).status_code == 200
    assert web.get(URL).status_code == 200
    reveal = web.post(f"{URL}/{created.data['id']}/reveal", **UNLOCKED)
    assert reveal.status_code == 403 and "never opens on the web" in reveal.data["detail"]
    delete = web.delete(f"{URL}/{created.data['id']}")
    assert delete.status_code == 403
    assert VaultItem.objects.count() == 1


def test_delete_is_a_hard_delete_from_the_phone(auth_client):
    item = _add(auth_client)
    auth_client.post(f"{URL}/{item['id']}/reveal", **UNLOCKED)
    assert auth_client.delete(f"{URL}/{item['id']}").status_code == 204
    assert VaultItem.objects.count() == 0
    # The audit row outlives the item, label only.
    assert VaultReveal.objects.get().item_id is None


def test_another_member_never_sees_reveals_or_deletes_an_item(auth_client, mia):
    item = _add(auth_client)
    other = _client_for(mia)
    assert other.get(URL).data["results"] == []
    assert other.post(f"{URL}/{item['id']}/reveal", **UNLOCKED).status_code == 404
    assert other.patch(f"{URL}/{item['id']}", {"label": "x"}, format="json").status_code == 404
    assert other.delete(f"{URL}/{item['id']}").status_code == 404
    assert VaultItem.objects.count() == 1


@pytest.mark.parametrize("name", ["claude-session-Alienware", "claude-session-work laptop"])
def test_a_claude_worker_token_is_refused_on_every_route(household_user, auth_client, name):
    item = _add(auth_client)
    _token, raw = DeviceToken.issue(household_user, name)
    worker = APIClient()
    worker.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    for method, path, extra in (
        ("get", URL, {}),
        ("post", URL, {"data": {"label": "x", "kind": "other", "value": "y"}, "format": "json"}),
        ("patch", f"{URL}/{item['id']}", {"data": {"label": "x"}, "format": "json"}),
        ("delete", f"{URL}/{item['id']}", {}),
        ("post", f"{URL}/{item['id']}/reveal", UNLOCKED),
    ):
        response = getattr(worker, method)(path, **extra)
        assert response.status_code == 403, (method, path, response.status_code)
        assert "never readable by Claude" in response.data["detail"]


def test_a_token_bound_to_a_claude_machine_is_refused_whatever_its_name(
    household_user, auth_client
):
    item = _add(auth_client)
    _token, raw = DeviceToken.issue(household_user, "renamed by hand")
    worker = APIClient()
    worker.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    worker.post(
        "/api/claude/machines/heartbeat/",
        {"name": "Alienware", "scope": SCOPE_FULL, "state": "idle"},
        format="json",
    )
    assert worker.post(f"{URL}/{item['id']}/reveal", **UNLOCKED).status_code == 403


def test_a_recruiter_is_refused(db):
    from household.permissions import DEMO_TOKEN_SESSION_KEY

    visit = Household.objects.create(name="Visit", is_demo_visit=True)
    visitor = User.objects.create_user(email="visitor-v@demo.invalid")
    HouseholdMember.objects.create(user=visitor, household=visit, role=HouseholdMember.RECRUITER)
    token, _raw = DeviceToken.issue(
        visitor,
        "Demo visit",
        DeviceToken.SCOPE_READ,
        expires_at=timezone.now() + timedelta(hours=2),
    )
    client = _browser(visitor)
    session = client.session
    session[DEMO_TOKEN_SESSION_KEY] = token.pk
    session.save()
    response = client.get(URL)
    assert response.status_code == 403 and "ID vault" in str(response.data)


def test_reveals_are_rate_limited(auth_client):
    item = _add(auth_client)
    statuses = [
        auth_client.post(f"{URL}/{item['id']}/reveal", **UNLOCKED).status_code for _ in range(11)
    ]
    assert statuses[:10] == [200] * 10 and statuses[10] == 429
    assert VaultReveal.objects.count() == 10


def test_no_key_stores_nothing_and_says_so(auth_client, monkeypatch):
    monkeypatch.delenv("LEGION_VAULT_KEY")
    response = auth_client.post(URL, {"label": "SSN", "kind": "ssn", "value": VALUE}, format="json")
    assert response.status_code == 503 and "LEGION_VAULT_KEY" in response.data["detail"]
    assert VaultItem.objects.count() == 0


def test_a_duplicate_label_is_refused(auth_client):
    _add(auth_client)
    response = auth_client.post(
        URL, {"label": "passport", "kind": "passport", "value": "x"}, format="json"
    )
    assert response.status_code == 409


# =============================================================================
# The value is nowhere else
# =============================================================================


def test_the_value_never_reaches_a_log_record(auth_client, mia, caplog, capsys):
    caplog.set_level(logging.DEBUG)
    item = _add(auth_client)
    auth_client.patch(f"{URL}/{item['id']}", {"value": VALUE + "2"}, format="json")
    auth_client.post(f"{URL}/{item['id']}/reveal", **UNLOCKED)
    auth_client.post(f"{URL}/{item['id']}/reveal")  # refused: no header
    _client_for(mia).post(f"{URL}/{item['id']}/reveal", **UNLOCKED)  # 404
    auth_client.post(URL, {"label": "x" * 200, "kind": "ssn", "value": VALUE}, format="json")
    logged = "\n".join(
        f"{record.getMessage()} {record.args!r} {getattr(record, 'request', '')!r}"
        for record in caplog.records
    )
    captured = capsys.readouterr()
    for text in (logged, captured.out, captured.err):
        assert VALUE not in text


def test_no_engine_tool_and_no_assistant_surface_reaches_the_vault(household_user, settings):
    from assistant.surface import RECRUITER_TOOLS, WEB_TOOL_NAMES
    from engine_mcp.tools import READABLE, TOOLS

    for tool in TOOLS:
        assert "vault" not in tool.name
        assert "vault_items" not in json.dumps(tool.input_schema)
    assert "vault_items" not in READABLE and "vault_reveals" not in READABLE
    assert not [name for name in (*WEB_TOOL_NAMES, *RECRUITER_TOOLS) if "vault" in name]


def test_the_openapi_contract_carries_no_value_and_no_example(settings):
    text = (Path(settings.BASE_DIR) / "openapi.yaml").read_text(encoding="utf-8")
    assert VALUE not in text
    start = text.index("/api/vault/items:")
    vault_paths = text[start : start + 6000]
    assert "example" not in vault_paths.lower()


def test_the_vault_is_not_in_the_admin():
    from django.contrib import admin

    assert VaultItem not in admin.site._registry and VaultReveal not in admin.site._registry
