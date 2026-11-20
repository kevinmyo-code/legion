"""T3b: a head unit's own engine sign-in, minted by the member's phone while it pairs (DARK).

What is proved here, from the outside where it can be:
- the token: 256 random bits, shown once, only its hash stored, never in a log or any row;
- the five doors (mint, list, rotate, revoke, revoke-all): who may call each, and a
  404 with no database query at all while `LEGION_HEADUNIT_SIGNIN` is off;
- the scope: a walk over EVERY URL pattern in the project, every method, where a head-unit token
  must get exactly what an unknown token gets unless the (route, method) is on
  `headunit_signin.scope.ROUTES`; and inside a turn, only `scope.TOOLS`;
- one answer for every dead key: unknown, expired, revoked, removed member.
Every label, name and value is invented.
"""

from __future__ import annotations

import base64
import gzip
import json
import re
import uuid
from datetime import timedelta

import pytest
from django.core.cache import cache
from django.core.files.uploadedfile import SimpleUploadedFile
from django.db import connection
from django.test.client import RequestFactory
from django.urls import URLPattern, URLResolver, get_resolver
from django.utils import timezone
from rest_framework.request import Request
from rest_framework.test import APIClient

from agents.legion.flags import TURN_ENV
from headunit_signin import scope
from headunit_signin.authentication import HeadUnitTokenAuthentication
from headunit_signin.flags import SIGNIN_ENV
from headunit_signin.models import (
    LIFETIME,
    MAX_AGE,
    REASON_FORGOTTEN,
    REASON_LOST_OR_SOLD,
    HeadUnitCredential,
    hash_token,
)
from household.models import DeviceToken, Household, HouseholdMember, User
from tests.conftest import _client_for, _member

pytestmark = pytest.mark.django_db

LIST = "/api/auth/head-units"
REVOKE_ALL = "/api/auth/head-units/revoke-all"
UNIT = {"name": "Invented car", "kind": "head_unit"}


@pytest.fixture(autouse=True)
def _fresh_throttles():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def on(monkeypatch):
    monkeypatch.setenv(SIGNIN_ENV, "on")


@pytest.fixture
def phone(auth_client):
    """The member's phone: a live device token of `household_user`."""
    return auth_client


def unit_client(raw: str) -> APIClient:
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    return client


def _mint(client, body=None):
    return client.post(LIST, body or UNIT, format="json")


def _paired(phone) -> tuple[str, str]:
    response = _mint(phone)
    assert response.status_code == 201, response.data
    return response.data["id"], response.data["token"]


def _probe(raw: str):
    """A request on an allow-listed route that stops at its body check: 400 for a live token,
    401 for a dead one. Nothing is stored either way."""
    return unit_client(raw).post("/api/device-logs/", {}, format="multipart")


def _rotate(client, cred_id):
    return client.post(f"{LIST}/{cred_id}/rotate")


def _unknown_key() -> str:
    return "hu1_" + base64.urlsafe_b64encode(b"\x07" * 32).decode().rstrip("=")


# =============================================================================
# The token
# =============================================================================


def test_a_minted_token_is_256_random_bits_and_only_its_hash_is_stored(household_a, household_user):
    row, raw = HeadUnitCredential.mint(
        household=household_a, owner_user=household_user, label="Invented unit"
    )
    assert raw.startswith("hu1_")
    body = raw[len("hu1_") :]
    assert len(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4))) == 32
    assert row.key_hash == hash_token(raw)
    stored = HeadUnitCredential.objects.filter(pk=row.pk).values().get()
    assert raw not in repr(stored) and body not in repr(stored)
    _again, other = HeadUnitCredential.mint(
        household=household_a, owner_user=household_user, label="Invented unit"
    )
    assert other != raw
    assert row.expires_at - row.created_at == LIFETIME


def test_rotation_never_reaches_past_max_age_from_pairing(household_a, household_user):
    row, raw = HeadUnitCredential.mint(
        household=household_a, owner_user=household_user, label="Invented unit"
    )
    late = row.created_at + MAX_AGE - timedelta(days=2)
    new = row.rotate(now=late)
    assert new != raw and row.key_hash == hash_token(new)
    assert row.expires_at == row.created_at + MAX_AGE


# =============================================================================
# Switched off: a 404 before any work
# =============================================================================


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("get", LIST),
        ("post", LIST),
        ("delete", f"{LIST}/{uuid.uuid4()}"),
        ("post", REVOKE_ALL),
        ("post", f"{LIST}/{uuid.uuid4()}/rotate"),
    ],
)
def test_every_door_is_a_404_with_no_query_while_switched_off(
    phone, method, path, django_assert_num_queries
):
    with django_assert_num_queries(0):
        response = getattr(phone, method)(path, UNIT if method == "post" else None, format="json")
    assert response.status_code == 404
    assert "LEGION_HEADUNIT_SIGNIN is not on" in response.data["detail"]


def test_a_unit_token_is_an_unknown_token_with_no_query_while_switched_off(
    phone, on, monkeypatch, django_assert_num_queries
):
    _id, raw = _paired(phone)
    monkeypatch.delenv(SIGNIN_ENV)
    unit = unit_client(raw)
    with django_assert_num_queries(0):
        response = unit.post("/api/device-logs/", {}, format="multipart")
    assert response.status_code == 401
    assert response.data["detail"] == "Invalid token."


# =============================================================================
# Mint: the member's phone only
# =============================================================================


def test_the_phone_mints_a_token_shown_once_and_lists_it_without_the_token(phone, on):
    before = timezone.now()
    response = _mint(phone)
    assert response.status_code == 201, response.data
    assert set(response.data) == {"id", "token", "expires"}
    raw = response.data["token"]
    expires = response.data["expires"]
    assert before + LIFETIME <= expires <= timezone.now() + LIFETIME
    listed = phone.get(LIST)
    assert listed.status_code == 200
    [row] = listed.data
    assert row["label"] == "Invented car" and str(row["id"]) == str(response.data["id"])
    assert set(row) == {
        "id",
        "label",
        "created_at",
        "last_used_at",
        "expires_at",
        "vehicle_id",
        "rotated_at",
        "stale_use_count",
        "stale_used_at",
    }
    assert raw not in repr(listed.content)


def test_a_mint_names_its_kind(phone, on):
    response = _mint(phone, {"name": "Invented car", "kind": "phone"})
    assert response.status_code == 400
    assert not HeadUnitCredential.objects.exists()


def test_a_browser_session_cannot_mint(household_user, on):
    browser = APIClient()
    browser.force_login(household_user)
    response = _mint(browser)
    assert response.status_code == 403
    assert "phone" in response.data["detail"]
    assert not HeadUnitCredential.objects.exists()
    assert browser.get(LIST).status_code == 200  # but may list (and revoke)


@pytest.mark.parametrize("scope_name", [DeviceToken.SCOPE_READ, DeviceToken.SCOPE_WRITE])
def test_a_recruiter_cannot_mint(on, scope_name):
    visit = Household.objects.create(name="Invented visit", is_demo_visit=True)
    visitor = User.objects.create_user(email=f"visitor-{uuid.uuid4().hex[:8]}@demo.invalid")
    HouseholdMember.objects.create(user=visitor, household=visit, role=HouseholdMember.RECRUITER)
    _token, key = DeviceToken.issue(
        visitor, "Demo visit", scope_name, expires_at=timezone.now() + timedelta(hours=2)
    )
    response = _mint(unit_client(key))
    assert response.status_code == 403, response.data
    assert not HeadUnitCredential.objects.exists()


def test_a_read_scoped_token_cannot_mint(household_user, on):
    _token, key = DeviceToken.issue(household_user, "Invented tool", DeviceToken.SCOPE_READ)
    assert _mint(unit_client(key)).status_code == 403
    assert not HeadUnitCredential.objects.exists()


def test_minting_is_five_an_hour_per_member(phone, on):
    for _ in range(5):
        assert _mint(phone).status_code == 201
    assert _mint(phone).status_code == 429
    assert HeadUnitCredential.objects.count() == 5


def test_never_over_mcp(phone, on, settings):
    """`/mcp` is not a door to minting: no tool mints, and a head-unit token is refused there."""
    from engine_mcp.tools import TOOLS_BY_NAME

    assert not any("head" in name and "unit" in name for name in TOOLS_BY_NAME)
    settings.LEGION_MCP = True
    _id, raw = _paired(phone)
    response = unit_client(raw).post("/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert response.status_code == 401


# =============================================================================
# Refresh: the unit rotates its own token
# =============================================================================


def test_the_unit_cannot_refresh_itself(phone, on):
    """Review B1: a unit holding its own token cannot rotate it (a thief would lock the real unit
    out). Rotation is the member's, over the phone."""
    _id, raw = _paired(phone)
    assert unit_client(raw).post("/api/auth/head-units/refresh").status_code == 404
    assert not any(name == "headunit-refresh" for name, _m in scope.ROUTES)
    assert _probe(raw).status_code == 400


def test_the_member_rotates_a_unit_and_the_old_token_dies(phone, on):
    cred_id, old = _paired(phone)
    response = _rotate(phone, cred_id)
    assert response.status_code == 200, response.data
    assert set(response.data) == {"id", "token", "expires"}
    new = response.data["token"]
    assert new != old and str(response.data["id"]) == str(cred_id)
    assert _probe(old).status_code == 401
    assert _probe(new).status_code == 400


def test_an_old_token_after_rotation_is_refused_and_recorded_not_revoked(phone, on):
    cred_id, old = _paired(phone)
    new = _rotate(phone, cred_id).data["token"]
    for _ in range(2):
        response = _probe(old)
        assert (response.status_code, response.data) == (401, {"detail": "Invalid token."})
    row = HeadUnitCredential.objects.get(pk=cred_id)
    assert row.stale_use_count == 2 and row.stale_used_at is not None
    assert row.revoked_at is None
    assert _probe(new).status_code == 400
    [listed] = phone.get(LIST).data
    assert listed["stale_use_count"] == 2
    assert listed["rotated_at"] is not None and listed["stale_used_at"] is not None


def test_only_the_pairing_members_phone_may_rotate(phone, on, household_a, token_b, household_user):
    cred_id, raw = _paired(phone)
    mia = _client_for(_member(household_a, "mia@example.com"))
    assert _rotate(mia, cred_id).status_code == 404
    assert _rotate(token_b, cred_id).status_code == 404
    browser = APIClient()
    browser.force_login(household_user)
    assert _rotate(browser, cred_id).status_code == 403
    assert _rotate(unit_client(raw), cred_id).status_code == 401
    _token, key = DeviceToken.issue(household_user, "Invented tool", DeviceToken.SCOPE_READ)
    assert _rotate(unit_client(key), cred_id).status_code == 403
    assert _probe(raw).status_code == 400


def test_a_revoked_unit_cannot_be_rotated_back_to_life(phone, on):
    cred_id, _raw = _paired(phone)
    phone.delete(f"{LIST}/{cred_id}")
    assert _rotate(phone, cred_id).status_code == 404


def test_rotation_is_throttled_per_unit(phone, on):
    cred_id, _raw = _paired(phone)
    for _ in range(6):
        assert _rotate(phone, cred_id).status_code == 200
    assert _rotate(phone, cred_id).status_code == 429
    other_id, _other = _paired(phone)
    assert _rotate(phone, other_id).status_code == 200


# =============================================================================
# One answer for every dead key
# =============================================================================


def _dead(response):
    return response.status_code, response.data


def test_expired_revoked_and_removed_are_answered_as_unknown(phone, on, household_user):
    unknown = _dead(_probe(_unknown_key()))
    assert unknown == (401, {"detail": "Invalid token."})

    cred_id, raw = _paired(phone)
    HeadUnitCredential.objects.filter(pk=cred_id).update(expires_at=timezone.now())
    assert _dead(_probe(raw)) == unknown

    cred_id, raw = _paired(phone)
    assert phone.delete(f"{LIST}/{cred_id}").status_code == 204
    assert _dead(_probe(raw)) == unknown

    _cred_id, raw = _paired(phone)
    User.objects.filter(pk=household_user.pk).update(is_active=False)
    assert _dead(_probe(raw)) == unknown


def test_a_member_removed_from_the_household_signs_their_units_out(phone, on, household_user):
    _cred_id, raw = _paired(phone)
    HouseholdMember.objects.filter(user=household_user).delete()
    assert _dead(_probe(raw)) == (401, {"detail": "Invalid token."})


# =============================================================================
# Revoke: the member who paired, at once
# =============================================================================


def test_revoke_is_immediate(phone, on):
    cred_id, raw = _paired(phone)
    assert phone.delete(f"{LIST}/{cred_id}").status_code == 204
    row = HeadUnitCredential.objects.get(pk=cred_id)
    assert row.revoked_reason == REASON_FORGOTTEN and row.revoked_at is not None
    assert unit_client(raw).post("/api/device-logs/", {}, format="multipart").status_code == 401
    assert phone.get(LIST).data == []
    assert phone.delete(f"{LIST}/{cred_id}").status_code == 204  # already revoked: same answer


def test_another_member_of_the_household_cannot_list_or_revoke(phone, on, household_a):
    cred_id, raw = _paired(phone)
    mia = _client_for(_member(household_a, "mia@example.com"))
    assert mia.get(LIST).data == []
    assert mia.delete(f"{LIST}/{cred_id}").status_code == 404
    assert mia.post(REVOKE_ALL).data["revoked"] == 0
    assert _probe(raw).status_code == 400


def test_a_member_of_another_household_cannot_list_or_revoke(phone, on, token_b):
    cred_id, raw = _paired(phone)
    assert token_b.get(LIST).data == []
    assert token_b.delete(f"{LIST}/{cred_id}").status_code == 404
    assert token_b.post(REVOKE_ALL).data["revoked"] == 0
    assert _probe(raw).status_code == 400


def test_revoke_all_signs_out_every_unit_of_the_member_and_no_one_elses(phone, on, household_a):
    _a, raw_a = _paired(phone)
    _b, raw_b = _paired(phone)
    mia = _client_for(_member(household_a, "mia@example.com"))
    _m, raw_m = _paired(mia)
    response = phone.post(REVOKE_ALL)
    assert response.status_code == 200 and response.data["revoked"] == 2
    for raw in (raw_a, raw_b):
        assert _probe(raw).status_code == 401
    assert _probe(raw_m).status_code == 400
    reasons = set(
        HeadUnitCredential.objects.filter(owner_user__email="kevin@example.com").values_list(
            "revoked_reason", flat=True
        )
    )
    assert reasons == {REASON_LOST_OR_SOLD}


def test_a_unit_token_cannot_mint_list_or_revoke(phone, on):
    cred_id, raw = _paired(phone)
    unit = unit_client(raw)
    assert _mint(unit).status_code == 401
    assert unit.get(LIST).status_code == 401
    assert unit.delete(f"{LIST}/{cred_id}").status_code == 401
    assert unit.post(REVOKE_ALL).status_code == 401
    assert HeadUnitCredential.objects.count() == 1


# =============================================================================
# Hygiene
# =============================================================================


def test_last_used_is_written_at_most_every_five_minutes(phone, on):
    cred_id, raw = _paired(phone)
    unit = unit_client(raw)
    recent = timezone.now() - timedelta(minutes=1)
    HeadUnitCredential.objects.filter(pk=cred_id).update(last_used_at=recent)
    unit.post("/api/device-logs/", {}, format="multipart")
    assert HeadUnitCredential.objects.get(pk=cred_id).last_used_at == recent
    stale = timezone.now() - timedelta(minutes=6)
    HeadUnitCredential.objects.filter(pk=cred_id).update(last_used_at=stale)
    unit.post("/api/device-logs/", {}, format="multipart")
    assert HeadUnitCredential.objects.get(pk=cred_id).last_used_at > stale


def _log_upload():
    blob = gzip.compress(b"invented log line\n")
    return {
        "batch_id": str(uuid.uuid4()),
        "device_label": "Invented unit",
        "reason": "manual",
        "file": SimpleUploadedFile("log.gz", blob, content_type="application/gzip"),
    }


def _rows_holding(text: str) -> list[str]:
    """Every table, in every schema this engine owns, with a row whose text holds `text`."""
    found = []
    with connection.cursor() as cursor:
        cursor.execute(
            "select table_schema, table_name from information_schema.tables "
            "where table_schema in ('public', 'django') and table_type = 'BASE TABLE'"
        )
        for schema, table in cursor.fetchall():
            cursor.execute(
                f'select count(*) from "{schema}"."{table}" t where t::text like %s',
                [f"%{text}%"],
            )
            if cursor.fetchone()[0]:
                found.append(f"{schema}.{table}")
    return found


def test_the_token_is_never_logged_and_never_stored(phone, on, caplog, monkeypatch):
    caplog.set_level("DEBUG")
    monkeypatch.setenv(TURN_ENV, "on")
    _id, raw = _paired(phone)
    unit = unit_client(raw)
    assert unit.post("/api/device-logs/", _log_upload(), format="multipart").status_code == 201
    unit.post("/api/agents/turn", {"transcript": "invented question"}, format="json")
    refreshed = _rotate(phone, _id).data["token"]
    unit_client(refreshed).post("/api/device-logs/", _log_upload(), format="multipart")
    for secret in (raw, refreshed):
        body = secret[len("hu1_") :]
        assert body not in caplog.text
        assert _rows_holding(body) == []


def test_a_unit_may_not_upload_a_conversation_or_any_reason_the_unit_does_not_send(phone, on):
    """Review B2: `conversation` (and the phone's logcat and crash) are what the Claude worker and
    the conversation spool read. A unit token uploads drive logs only."""
    from devicelogs.models import DeviceLog

    _id, raw = _paired(phone)
    for reason in ("conversation", "logcat", "crash"):
        response = unit_client(raw).post(
            "/api/device-logs/", {**_log_upload(), "reason": reason}, format="multipart"
        )
        assert response.status_code == 403, (reason, response.data)
    assert not DeviceLog.objects.exists()
    for reason in ("drive_end", "startup", "manual", "periodic"):
        response = unit_client(raw).post(
            "/api/device-logs/", {**_log_upload(), "reason": reason}, format="multipart"
        )
        assert response.status_code == 201, (reason, response.data)


def test_a_unit_upload_is_marked_and_the_worker_facing_reader_never_returns_it(phone, on):
    from devicelogs.models import DeviceLog

    _id, raw = _paired(phone)
    unit_up = unit_client(raw).post("/api/device-logs/", _log_upload(), format="multipart")
    phone_up = phone.post("/api/device-logs/", _log_upload(), format="multipart")
    assert unit_up.status_code == phone_up.status_code == 201
    unit_row = DeviceLog.objects.get(pk=unit_up.data["id"])
    phone_row = DeviceLog.objects.get(pk=phone_up.data["id"])
    assert (unit_row.uploader_kind, phone_row.uploader_kind) == ("head_unit", "member")
    listed = {str(r["id"]) for r in phone.get("/api/device-logs/").data["results"]}
    assert listed == {str(phone_row.pk)}
    assert phone.get(f"/api/device-logs/{unit_row.pk}/render").status_code == 404
    assert phone.get(f"/api/device-logs/{phone_row.pk}/render").status_code == 200


def test_a_unit_cannot_probe_another_devices_batch_id(phone, on):
    _id, raw = _paired(phone)
    upload = _log_upload()
    batch = upload["batch_id"]
    mine = phone.post("/api/device-logs/", upload, format="multipart")
    assert mine.status_code == 201
    response = unit_client(raw).post(
        "/api/device-logs/", {**_log_upload(), "batch_id": batch}, format="multipart"
    )
    assert response.status_code == 409
    assert str(mine.data["id"]) not in str(response.data)


def test_unit_uploads_are_throttled_per_unit(phone, on, monkeypatch):
    from headunit_signin.views import UploadThrottle

    monkeypatch.setattr(UploadThrottle, "rate", "2/hour", raising=False)
    _a, raw_a = _paired(phone)
    _b, raw_b = _paired(phone)
    for _ in range(2):
        assert unit_client(raw_a).post(
            "/api/device-logs/", _log_upload(), format="multipart"
        ).status_code == 201
    assert unit_client(raw_a).post(
        "/api/device-logs/", _log_upload(), format="multipart"
    ).status_code == 429
    assert unit_client(raw_b).post(
        "/api/device-logs/", _log_upload(), format="multipart"
    ).status_code == 201
    assert phone.post("/api/device-logs/", _log_upload(), format="multipart").status_code == 201


def test_a_unit_upload_has_its_own_size_cap(phone, on, monkeypatch):
    from headunit_signin import scope as unit_scope

    monkeypatch.setattr(unit_scope, "LOG_MAX_RAW_BYTES", 8)
    _id, raw = _paired(phone)
    response = unit_client(raw).post("/api/device-logs/", _log_upload(), format="multipart")
    assert response.status_code == 413
    assert phone.post("/api/device-logs/", _log_upload(), format="multipart").status_code == 201


def _vehicle(client, i=0) -> str:
    from tests.test_fleet_api import a_vehicle

    response = client.put(f"/api/fleet/vehicles/hu-vehicle-{i}/", a_vehicle(i), format="json")
    assert response.status_code == 200, response.data
    return str(response.data["id"])


def _sample(vehicle_id: str, second: int = 0) -> dict:
    return {
        "vehicle_id": vehicle_id,
        "pid": "010C",
        "value": 800.0,
        "unit": "rpm",
        "recorded_at": f"2026-01-02T03:04:{second:02d}Z",
    }


def test_a_unit_is_bound_at_mint_to_one_vehicle_of_its_household(phone, on, token_b):
    mine = _vehicle(phone, 0)
    theirs = _vehicle(token_b, 5)
    response = _mint(phone, {**UNIT, "vehicle_id": mine})
    assert response.status_code == 201, response.data
    assert str(HeadUnitCredential.objects.get(pk=response.data["id"]).vehicle_id) == mine
    for bad in (theirs, str(uuid.uuid4())):
        refused = _mint(phone, {**UNIT, "vehicle_id": bad})
        assert refused.status_code == 400, refused.data
    assert HeadUnitCredential.objects.count() == 1
    [listed] = phone.get(LIST).data
    assert str(listed["vehicle_id"]) == mine


def test_a_unit_writes_obd_samples_only_for_its_own_vehicle(phone, on, token_b):
    from legacy.models.fleet import ObdSample

    bound, other = _vehicle(phone, 0), _vehicle(phone, 1)
    theirs = _vehicle(token_b, 5)
    raw = _mint(phone, {**UNIT, "vehicle_id": bound}).data["token"]
    unit = unit_client(raw)
    ok = unit.post("/api/fleet/obd_samples/batch/", [_sample(bound)], format="json")
    assert ok.status_code == 201, ok.data
    for wrong in (other, theirs):
        response = unit.post(
            "/api/fleet/obd_samples/batch/", [_sample(bound, 1), _sample(wrong, 2)], format="json"
        )
        assert response.status_code == 403, response.data
    assert ObdSample.objects.count() == 1


def test_a_unit_bound_to_no_vehicle_writes_no_obd_samples(phone, on):
    from legacy.models.fleet import ObdSample

    vehicle = _vehicle(phone, 0)
    _id, raw = _paired(phone)
    response = unit_client(raw).post(
        "/api/fleet/obd_samples/batch/", [_sample(vehicle)], format="json"
    )
    assert response.status_code == 403
    assert not ObdSample.objects.exists()


def test_a_unit_upload_lands_in_its_members_household(phone, on, household_user, household_a):
    from devicelogs.models import DeviceLog

    _id, raw = _paired(phone)
    response = unit_client(raw).post("/api/device-logs/", _log_upload(), format="multipart")
    assert response.status_code == 201, response.data
    row = DeviceLog.objects.get(pk=response.data["id"])
    assert (row.household_id, row.user_id) == (household_a.id, household_user.pk)


# =============================================================================
# Scope: every URL pattern, walked
# =============================================================================

METHODS = ("get", "post", "put", "patch", "delete", "head", "options")
# Regex patterns the walker cannot fill from converters, filled by hand. A new one fails the walk.
REGEX_SAMPLES = {
    "spa": "/walk-spa-page",
}


def _patterns(resolver=None, prefix=""):
    """Every routed pattern, less Django's admin: a session-only site of plain Django views that
    never reads `Authorization`, and whose pages need the collected static manifest to render."""
    resolver = resolver or get_resolver()
    for entry in resolver.url_patterns:
        if isinstance(entry, URLResolver):
            if str(entry.pattern) == "admin/":
                continue
            yield from _patterns(entry, prefix + str(entry.pattern))
        elif isinstance(entry, URLPattern):
            yield prefix + str(entry.pattern), entry


def _fill(route: str, entry) -> str:
    if route.startswith("^") or "(?" in route:
        if entry.name in REGEX_SAMPLES:
            return REGEX_SAMPLES[entry.name]
        raise AssertionError(f"the walk cannot fill {route!r} ({entry.name}); add a sample")
    filled = re.sub(
        r"<(?:(\w+):)?\w+>",
        lambda m: {"int": "1", "uuid": str(uuid.uuid4())}.get(m.group(1) or "str", "x"),
        route,
    )
    return "/" + filled


def _view_class(entry):
    return getattr(entry.callback, "cls", None) or getattr(entry.callback, "view_class", None)


def test_the_unit_class_is_listed_only_on_allow_listed_views_and_never_by_default(settings):
    allowed_names = {name for name, _method in scope.ROUTES}
    listed = {
        entry.name
        for _route, entry in _patterns()
        if HeadUnitTokenAuthentication
        in (getattr(_view_class(entry), "authentication_classes", None) or [])
    }
    assert listed == allowed_names
    defaults = settings.REST_FRAMEWORK["DEFAULT_AUTHENTICATION_CLASSES"]
    assert not any("headunit" in name.lower() for name in defaults)


def test_every_url_refuses_a_unit_token_unless_its_route_is_on_the_list(
    phone, on, monkeypatch, settings
):
    """The walk. Every switch this suite knows is on, so a dark door cannot hide an acceptance
    behind its 404. A head-unit token must get exactly what an unknown key gets (401 or 403 where
    that is refused; the same answer on a public page), and the class may hand back a user only
    on the list."""
    monkeypatch.setenv(TURN_ENV, "on")
    settings.LEGION_MCP = True
    _id, raw = _paired(phone)
    live, unknown = unit_client(raw), unit_client(_unknown_key())

    accepted = set()
    original = HeadUnitTokenAuthentication.authenticate

    def _spy(self, request):
        result = original(self, request)
        if result is not None:
            accepted.add((scope.route_name(request), request.method))
        return result

    monkeypatch.setattr(HeadUnitTokenAuthentication, "authenticate", _spy)

    walked, failures = 0, []
    for route, entry in _patterns():
        path = _fill(route, entry)
        for method in METHODS:
            if (entry.name, method.upper()) in scope.ROUTES:
                continue
            cache.clear()
            mine = getattr(live, method)(path).status_code
            cache.clear()
            theirs = getattr(unknown, method)(path).status_code
            walked += 1
            refused = {401, 403}
            leaked = mine not in refused if theirs in refused else mine != theirs
            if leaked:
                failures.append(f"{method.upper()} {path} ({entry.name}): {mine} vs {theirs}")
    assert not failures, failures
    assert walked > 1000, walked
    assert accepted <= scope.ROUTES, accepted - scope.ROUTES
    assert _probe(raw).status_code == 400  # the token was live all along


def test_each_allow_listed_route_accepts_a_unit_token(phone, on, monkeypatch):
    """The positive control: each listed route lets the token through authentication and the
    household fence, and stops at its own body check, before any model or write."""
    monkeypatch.setenv(TURN_ENV, "on")
    _id, raw = _paired(phone)
    unit = unit_client(raw)
    turn = unit.post("/api/agents/turn", {"kind": "nope"}, format="json")
    assert turn.status_code == 400, turn.data
    cancel = unit.post(f"/api/agents/turn/{uuid.uuid4()}/cancel")
    assert cancel.status_code == 404 and "No turn of yours" in cancel.data["detail"]
    log = unit.post("/api/device-logs/", {}, format="multipart")
    assert log.status_code == 400 and log.data["detail"].startswith("Nothing was stored")
    obd = unit.post("/api/fleet/obd_samples/batch/", {}, format="json")
    assert obd.status_code == 400, obd.data
    assert {name for name, _m in scope.ROUTES} == {
        "agents-turn",
        "agents-turn-cancel",
        "device-logs-upload",
        "fleet-obd-samples-batch",
    }


def test_the_household_fence_refuses_a_unit_credential_a_tool_dispatches(
    household_a, household_user
):
    """Inside a turn a write tool reaches its route through `call_route`, which forces the
    caller's identity on and never authenticates. `IsHouseholdMember` refuses the credential
    there too."""
    from engine_mcp.dispatch import call_route

    row, _raw = HeadUnitCredential.mint(
        household=household_a, owner_user=household_user, label="Invented unit"
    )
    response = call_route(_unit_request(household_user, row), "GET", "/api/places/")
    assert response.status_code == 403
    assert "head unit" in str(response.data["detail"])


# =============================================================================
# Inside a turn: the unit's tools and the unit's conversations only
# =============================================================================


def _unit_request(user, credential):
    raw = RequestFactory().post("/api/agents/turn", data={}, content_type="application/json")
    raw._force_auth_user = user
    raw._force_auth_token = credential
    return Request(raw)


def test_a_unit_turn_is_offered_and_may_call_only_the_units_tools(household_a, household_user):
    from agents.evals.runner import member_request
    from agents.legion.gateway import LegionGateway

    row, _raw = HeadUnitCredential.mint(
        household=household_a, owner_user=household_user, label="Invented unit"
    )
    member_tools = set(LegionGateway(member_request(household_user)).tools())
    unit_tools = set(LegionGateway(_unit_request(household_user, row)).tools())
    assert {"monthly_spend", "recall_memory", "read_calendar"} <= member_tools
    assert unit_tools == scope.TOOLS & member_tools
    assert unit_tools == scope.TOOLS
    for tool in unit_tools:
        assert not LegionGateway(member_request(household_user)).tools()[tool].writes


def test_a_unit_turn_refuses_a_tool_outside_its_list(household_a, household_user):
    from agents.graph.state import TurnState
    from agents.legion.gateway import LegionGateway
    from agents.legion.specs import spec as spec_named
    from agents.runtime.tools import ToolCall

    row, _raw = HeadUnitCredential.mint(
        household=household_a, owner_user=household_user, label="Invented unit"
    )
    gateway = LegionGateway(_unit_request(household_user, row))
    state = TurnState(
        turn_id="t",
        conversation_id="c",
        seq=1,
        household_id=str(household_a.id),
        member_id=str(household_user.pk),
        client=scope.TURN_CLIENT,
        transcript="invented",
    )
    outcome = gateway.call(
        state, spec_named("generalist"), ToolCall("t1", "monthly_spend", {}), 5.0
    )
    assert outcome.ok is False and outcome.guard == "not_allowed"
    assert "not one of this agent's tools" in outcome.response["message"]


def test_a_real_turn_through_the_door_keeps_to_the_units_tools_and_stores_no_token(
    phone, on, monkeypatch
):
    """End to end: `POST /api/agents/turn` with a head-unit token. The model is offered exactly
    the unit's tools, a money tool it asks for anyway is refused, the conversation is the unit's,
    and the token is in no row of any table after the turn's traces and metering are written."""
    from agents.models import AgentConversation, AgentToolCall
    from agents.runtime import model as m

    monkeypatch.setenv(TURN_ENV, "on")
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key-not-real")
    holder = {}

    def factory(key):
        holder["model"] = m.FakeModel(
            [m.call("monthly_spend", {}), m.say("I cannot see that from the car.")]
        )
        return holder["model"]

    monkeypatch.setattr("agents.views.GeminiClient", factory)
    _id, raw = _paired(phone)
    response = unit_client(raw).post(
        "/api/agents/turn", {"transcript": "invented question", "client": "phone"}, format="json"
    )
    assert response.status_code == 200
    body = b"".join(response.streaming_content).decode()
    assert "event: done" in body
    offered = {d["name"] for d in holder["model"].requests[0].declarations}
    assert offered == scope.TOOLS
    [tool_call] = AgentToolCall.objects.all()
    assert (tool_call.tool, tool_call.outcome, tool_call.guard) == (
        "monthly_spend",
        "refused",
        "not_allowed",
    )
    assert set(AgentConversation.objects.values_list("client", flat=True)) == {scope.TURN_CLIENT}
    assert _rows_holding(raw[len("hu1_") :]) == []


def _unit_call(user, credential, household, name, args):
    from agents.graph.state import TurnState
    from agents.legion.gateway import LegionGateway
    from agents.legion.specs import spec as spec_named
    from agents.runtime.tools import ToolCall

    state = TurnState(
        turn_id="t",
        conversation_id="c",
        seq=1,
        household_id=str(household.id),
        member_id=str(user.pk),
        client=scope.TURN_CLIENT,
        transcript="invented",
    )
    gateway = LegionGateway(_unit_request(user, credential))
    return gateway.call(state, spec_named("generalist"), ToolCall("t1", name, args), 5.0)


def test_a_unit_turn_checks_conditions_at_coordinates_only(household_a, household_user):
    """Review B4: by name ("home", any saved place) would tell whoever holds the unit roughly where
    the member lives."""
    row, _raw = HeadUnitCredential.mint(
        household=household_a, owner_user=household_user, label="Invented unit"
    )
    for args in ({}, {"place": "home"}, {"place": "Invented gym"}, {"lat": 29.7}):
        outcome = _unit_call(household_user, row, household_a, "check_conditions", args)
        assert (outcome.ok, outcome.guard) == (False, "headunit"), args
    point = {"lat": 29.7, "lon": -95.3}
    outcome = _unit_call(household_user, row, household_a, "check_conditions", point)
    assert outcome.guard != "headunit"


def test_a_unit_turn_reads_its_own_vehicle_only(phone, on, household_a, household_user):
    bound, other = _vehicle(phone, 0), _vehicle(phone, 1)
    row, _raw = HeadUnitCredential.mint(
        household=household_a, owner_user=household_user, label="Invented unit"
    )
    HeadUnitCredential.objects.filter(pk=row.pk).update(vehicle_id=bound)
    row.refresh_from_db()
    for name in ("next_service", "vehicle_recalls"):
        outcome = _unit_call(household_user, row, household_a, name, {"vehicle": other})
        text = outcome.response["message"]
        assert other not in text and "M3 1" not in text, (name, text)
    unbound, _raw = HeadUnitCredential.mint(
        household=household_a, owner_user=household_user, label="Invented unit"
    )
    for name in ("next_service", "vehicle_recalls"):
        outcome = _unit_call(household_user, unbound, household_a, name, {})
        assert (outcome.ok, outcome.guard) == (False, "headunit"), name


def test_a_unit_turns_prompt_carries_the_clock_and_not_the_members_name(
    phone, on, monkeypatch, household_user
):
    from agents.runtime import model as m

    User.objects.filter(pk=household_user.pk).update(first_name="Invented")
    monkeypatch.setenv(TURN_ENV, "on")
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key-not-real")
    holder = {}

    def factory(key):
        holder["model"] = m.FakeModel([m.say("Noted.")])
        return holder["model"]

    monkeypatch.setattr("agents.views.GeminiClient", factory)
    _id, raw = _paired(phone)
    response = unit_client(raw).post("/api/agents/turn", {"transcript": "hi"}, format="json")
    b"".join(response.streaming_content)
    request = holder["model"].requests[0]
    sent = json.dumps(request.contents) + request.system
    assert "Today is" in sent
    assert "Invented" not in sent


def _turn_ready(monkeypatch, rounds):
    from agents.runtime import model as m

    monkeypatch.setenv(TURN_ENV, "on")
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key-not-real")
    monkeypatch.setattr(
        "agents.views.GeminiClient", lambda key: m.FakeModel([list(r) for r in rounds])
    )


def test_a_unit_cannot_make_the_engine_say_a_line(phone, on, monkeypatch):
    """Review B6: `kind=say` speaks any line at the member's cost; a unit token never may."""
    _turn_ready(monkeypatch, [])
    _id, raw = _paired(phone)
    response = unit_client(raw).post(
        "/api/agents/turn", {"kind": "say", "text": "invented line"}, format="json"
    )
    assert response.status_code == 403, response.data


def test_a_unit_has_its_own_daily_cap_inside_the_members(phone, on, monkeypatch):
    from agents.runtime import model as m
    from headunit_signin import spend

    assert spend.DEFAULT_DAILY_USD == "0.25"
    monkeypatch.setenv(spend.CAP_ENV, "0.000001")
    _turn_ready(monkeypatch, [m.say("First.", prompt_tokens=5000, output_tokens=500)])
    cred_id, raw = _paired(phone)
    first = unit_client(raw).post("/api/agents/turn", {"transcript": "one"}, format="json")
    assert first.status_code == 200
    b"".join(first.streaming_content)
    assert HeadUnitCredential.objects.get(pk=cred_id).spent_micro_usd > 0
    second = unit_client(raw).post("/api/agents/turn", {"transcript": "two"}, format="json")
    assert second.status_code == 429, second.data
    # The member's own phone is not held to the unit's cap.
    _turn_ready(monkeypatch, [m.say("Phone.")])
    assert phone.post("/api/agents/turn", {"transcript": "x"}, format="json").status_code == 200
    # A second unit of the same member has its own.
    _turn_ready(monkeypatch, [m.say("Other unit.")])
    _other, raw_b = _paired(phone)
    assert unit_client(raw_b).post(
        "/api/agents/turn", {"transcript": "x"}, format="json"
    ).status_code == 200


def test_a_unit_turn_never_reaches_the_members_other_conversations(household_a, household_user):
    from agents.evals.runner import member_request
    from agents.legion.turn import cancel, open_conversation
    from agents.models import AgentTurn

    phone_conversation = open_conversation(member_request(household_user), None, "phone")
    phone_turn = AgentTurn.objects.create(
        household=household_a,
        owner_user=household_user,
        conversation=phone_conversation,
        seq=1,
        transcript="invented",
    )
    row, _raw = HeadUnitCredential.mint(
        household=household_a, owner_user=household_user, label="Invented unit"
    )
    unit = _unit_request(household_user, row)
    opened = open_conversation(unit, phone_conversation.pk, "phone")
    assert opened.pk != phone_conversation.pk and opened.client == scope.TURN_CLIENT
    assert open_conversation(unit, opened.pk, "phone").pk == opened.pk
    assert cancel(unit, phone_turn.pk) is False
    assert cancel(member_request(household_user), phone_turn.pk) is True
