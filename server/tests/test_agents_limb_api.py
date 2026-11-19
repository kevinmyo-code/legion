"""engine-brain ticket 04, slice E1: the limb contract's manifest upload, `POST /api/agents/limb`.

DARK like the turn endpoint: 404 "switched off" unless `LEGION_AGENTS_TURN=on`, nothing stored.
The device is the authenticated device token, never a body field. Every device, version and tool
below is invented.
"""

from __future__ import annotations

import pytest
from rest_framework.test import APIClient

from agents.legion.flags import TURN_ENV
from agents.legion.limbs import (
    BRAIN_DEFAULT_ENV,
    BRAIN_FORCE_ENV,
    CONTRACT_VERSION,
    MAX_MANIFEST_BYTES,
)
from agents.models import Limb
from agents.views import OFF_WORDS

pytestmark = pytest.mark.django_db

_TIMER = {
    "name": "set_timer",
    "description": "Start a countdown on the device.",
    "parameters": {"type": "object", "properties": {"seconds": {"type": "integer"}}},
    "writes": True,
    "read_through": False,
    "actuates": False,
}
_CLEAR = {
    "name": "clear_codes",
    "description": "Clear the car's stored fault codes.",
    "writes": True,
    "read_through": False,
    "actuates": True,
    "needs": ["parked"],
    "confirm": "limb",
}


def _manifest(**over) -> dict:
    body = {
        "contract_version": CONTRACT_VERSION,
        "kind": "phone",
        "app_version": "2026-01-01-abc1234",
        "tools": [_TIMER, _CLEAR],
        "streams": ["obd_samples"],
    }
    body.update(over)
    return body


def _token(user, name):
    from household.models import DeviceToken

    token, _raw = DeviceToken.issue(user, name)
    return token


@pytest.fixture
def switched_on(monkeypatch):
    monkeypatch.setenv(TURN_ENV, "on")
    monkeypatch.delenv(BRAIN_DEFAULT_ENV, raising=False)
    monkeypatch.delenv(BRAIN_FORCE_ENV, raising=False)


def test_the_upload_is_off_with_the_turn_and_stores_nothing(auth_client, monkeypatch):
    monkeypatch.delenv(TURN_ENV, raising=False)
    response = auth_client.post("/api/agents/limb", _manifest(), format="json")
    assert response.status_code == 404 and response.data["detail"] == OFF_WORDS
    assert not Limb.objects.exists()


def test_a_stranger_is_refused_before_the_switch(switched_on):
    response = APIClient().post("/api/agents/limb", _manifest(), format="json")
    assert response.status_code in (401, 403)
    assert not Limb.objects.exists()


def test_a_device_attaches_and_the_default_brain_is_the_phone(
    auth_client, switched_on, household_a, household_user
):
    response = auth_client.post("/api/agents/limb", _manifest(), format="json")
    assert response.status_code == 200, response.data
    assert response.data["brain"] == "phone" and response.data["brain_reason"] == "default"
    assert response.data["contract_version"] == CONTRACT_VERSION
    limb = Limb.objects.get()
    assert str(limb.pk) == str(response.data["limb_id"])
    assert (limb.household_id, limb.owner_user_id) == (household_a.id, household_user.pk)
    assert limb.kind == "phone" and limb.contract_version == CONTRACT_VERSION
    tools = {t["name"]: t for t in limb.manifest["tools"]}
    assert tools["clear_codes"]["confirm"] == "limb" and tools["set_timer"]["confirm"] == "engine"
    assert tools["set_timer"]["needs"] == [] and limb.manifest["streams"] == ["obd_samples"]
    assert set(response.data) == {"limb_id", "contract_version", "brain", "brain_reason"}


def test_the_device_comes_from_the_token_never_from_the_body(
    auth_client, switched_on, household_user
):
    from household.models import DeviceToken

    other = _token(household_user, "Invented second phone")
    body = _manifest(device=other.pk, device_id=other.pk, owner_user=999, brain_choice="engine")
    assert auth_client.post("/api/agents/limb", body, format="json").status_code == 200
    limb = Limb.objects.get()
    assert limb.device_id != other.pk and limb.brain_choice is None
    assert DeviceToken.objects.get(pk=limb.device_id).name == "Test client"


def test_a_second_upload_updates_the_same_row_and_keeps_the_servers_override(
    auth_client, switched_on
):
    assert auth_client.post("/api/agents/limb", _manifest(), format="json").status_code == 200
    Limb.objects.update(brain_choice="engine")
    again = auth_client.post(
        "/api/agents/limb",
        _manifest(app_version="2026-01-02-def5678", tools=[_TIMER], brain_asked="phone"),
        format="json",
    )
    assert again.status_code == 200
    limb = Limb.objects.get()
    assert limb.app_version == "2026-01-02-def5678" and len(limb.manifest["tools"]) == 1
    assert limb.brain_choice == "engine"
    assert (again.data["brain"], again.data["brain_reason"]) == ("engine", "override")


def test_the_members_own_choice_then_the_server_default(auth_client, switched_on, monkeypatch):
    asked = auth_client.post("/api/agents/limb", _manifest(brain_asked="engine"), format="json")
    assert (asked.data["brain"], asked.data["brain_reason"]) == ("engine", "asked")
    monkeypatch.setenv(BRAIN_DEFAULT_ENV, "engine")
    default = auth_client.post("/api/agents/limb", _manifest(), format="json")
    assert (default.data["brain"], default.data["brain_reason"]) == ("engine", "default")
    monkeypatch.setenv(BRAIN_DEFAULT_ENV, "nonsense")
    assert auth_client.post("/api/agents/limb", _manifest(), format="json").data["brain"] == "phone"


def test_the_force_switch_beats_the_override_and_the_member(auth_client, switched_on, monkeypatch):
    assert auth_client.post("/api/agents/limb", _manifest(), format="json").status_code == 200
    Limb.objects.update(brain_choice="engine")
    monkeypatch.setenv(BRAIN_FORCE_ENV, "phone")
    response = auth_client.post("/api/agents/limb", _manifest(brain_asked="engine"), format="json")
    assert (response.data["brain"], response.data["brain_reason"]) == ("phone", "forced")


def test_another_contract_version_is_refused_in_words_and_nothing_is_stored(
    auth_client, switched_on
):
    response = auth_client.post(
        "/api/agents/limb", _manifest(contract_version=CONTRACT_VERSION + 1), format="json"
    )
    assert response.status_code == 409 and response.data["code"] == "contract_unsupported"
    assert f"contract {CONTRACT_VERSION}" in response.data["detail"]
    assert response.data["contract_version"] == CONTRACT_VERSION
    assert not Limb.objects.exists()


_BIG = [{**_TIMER, "description": "x" * 3999, "name": f"t{i}"} for i in range(80)]
_BAD = {
    "kind": {"kind": "toaster"},
    "name": {"tools": [{**_TIMER, "name": "Set Timer"}]},
    "writes": {"tools": [{**_TIMER, "writes": None}]},
    "read_through_missing": {
        "tools": [{key: value for key, value in _TIMER.items() if key != "read_through"}]
    },
    "twice": {"tools": [_TIMER, _TIMER]},
    "needs": {"tools": [{**_TIMER, "needs": ["teleport"]}]},
    "confirm": {"tools": [{**_TIMER, "confirm": "nobody"}]},
    "brain": {"brain_asked": "cloud"},
    "description": {"tools": [{**_TIMER, "description": "x" * 4001}]},
    "too_big": {"tools": _BIG},
}


@pytest.mark.parametrize("case", list(_BAD))
def test_a_malformed_manifest_is_refused_and_nothing_is_stored(auth_client, switched_on, case):
    assert len(str(_BIG)) > MAX_MANIFEST_BYTES
    response = auth_client.post("/api/agents/limb", _manifest(**_BAD[case]), format="json")
    assert response.status_code == 400, response.data
    assert response.data["detail"].startswith("Nothing was stored.")
    assert response.data["code"] == "manifest_invalid"
    assert not Limb.objects.exists()


_NUL = "\u0000"


@pytest.mark.parametrize(
    "where",
    ["parameters_key", "parameters_value", "parameters_deep", "app_version", "description"],
)
def test_a_nul_character_anywhere_is_a_400_in_words(auth_client, switched_on, where):
    """Postgres jsonb refuses U+0000: without a check it surfaced as a 500 after validation."""
    tool = dict(_TIMER)
    over = {}
    if where == "parameters_key":
        tool["parameters"] = {"type": "object", "properties": {f"a{_NUL}b": {"type": "string"}}}
    elif where == "parameters_value":
        tool["parameters"] = {"type": "object", "description": f"x{_NUL}"}
    elif where == "parameters_deep":
        tool["parameters"] = {"type": "object", "properties": {"a": {"enum": ["ok", f"n{_NUL}"]}}}
    elif where == "app_version":
        over["app_version"] = f"1{_NUL}"
    else:
        tool["description"] = f"d{_NUL}"
    response = auth_client.post("/api/agents/limb", _manifest(tools=[tool], **over), format="json")
    assert response.status_code == 400, response.data
    assert response.data["code"] == "manifest_invalid"
    assert response.data["detail"].startswith("Nothing was stored.")
    assert not Limb.objects.exists()


def test_a_browser_session_is_not_a_limb(household_user, switched_on):
    browser = APIClient()
    browser.force_login(household_user)
    response = browser.post("/api/agents/limb", _manifest(), format="json")
    assert response.status_code == 403 and "device token" in response.data["detail"]
    assert response.data["code"] == "not_a_device"
    assert not Limb.objects.exists()


def test_a_read_only_token_cannot_attach(household_user, switched_on):
    from household.models import DeviceToken

    _row, raw = DeviceToken.issue(household_user, "Invented read token", scope="read")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    assert client.post("/api/agents/limb", _manifest(), format="json").status_code == 403
    assert not Limb.objects.exists()


def test_a_recruiter_is_refused_whatever_the_switch_says(switched_on):
    from demo.models import DemoCode

    _row, code = DemoCode.rotate()
    browser = APIClient(enforce_csrf_checks=True)
    browser.get("/api/auth/csrf")
    entered = browser.post("/api/demo/enter", {"code": code, "ref": "limb"}, format="json")
    assert entered.status_code == 201, entered.data
    browser.credentials(HTTP_X_CSRFTOKEN=browser.cookies["csrftoken"].value)
    assert browser.post("/api/agents/limb", _manifest(), format="json").status_code == 403
    assert browser.head("/api/agents/warm").status_code == 403
    assert not Limb.objects.exists()


# ---- HEAD /api/agents/warm ----------------------------------------------------------------------


def test_warm_answers_a_member_with_an_empty_204_and_touches_nothing(
    auth_client, switched_on, django_assert_max_num_queries
):
    # Authentication only: the token and its user, its last_seen_at, the household fence.
    with django_assert_max_num_queries(4):
        response = auth_client.head("/api/agents/warm")
    assert response.status_code == 204 and not response.content
    assert not Limb.objects.exists()


def test_warm_is_off_with_the_turn(auth_client, monkeypatch):
    monkeypatch.delenv(TURN_ENV, raising=False)
    assert auth_client.head("/api/agents/warm").status_code == 404


def test_warm_needs_a_member_and_is_head_only(auth_client, switched_on):
    assert APIClient().head("/api/agents/warm").status_code in (401, 403)
    assert auth_client.get("/api/agents/warm").status_code == 405
    assert auth_client.post("/api/agents/warm").status_code == 405
