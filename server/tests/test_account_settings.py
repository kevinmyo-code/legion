"""`PATCH /api/auth/me` and `POST /api/auth/password` (web-revamp ticket 05,
server half; spec D12): a member changes their own name and password without
a terminal."""
from __future__ import annotations

import pytest
from django.core.cache import cache
from rest_framework.test import APIClient

from household.views import WRONG_CURRENT_PASSWORD

pytestmark = pytest.mark.django_db

PASSWORD = "correct horse battery"
NEW = "a much longer phrase 42"


@pytest.fixture(autouse=True)
def _reset_throttle():
    cache.clear()
    yield
    cache.clear()


def _session_client(user, password=PASSWORD):
    client = APIClient()
    signed_in = client.post(
        "/api/auth/session/login", {"email": user.email, "password": password}, format="json"
    )
    assert signed_in.status_code == 200, signed_in.data
    cache.clear()  # the sign-in spent one of the `login` scope's five
    return client


def test_a_member_renames_themself(auth_client, household_user):
    renamed = auth_client.patch("/api/auth/me", {"name": "  Kevin  "}, format="json")
    assert renamed.status_code == 200, renamed.data
    assert renamed.data["name"] == "Kevin"
    household_user.refresh_from_db()
    assert household_user.first_name == "Kevin"
    assert auth_client.get("/api/auth/me").data["name"] == "Kevin"
    members = auth_client.get("/api/households/me").data["members"]
    assert "Kevin" in {m["name"] for m in members}


def test_a_blank_name_is_refused_in_words(auth_client, household_user):
    household_user.first_name = "Kevin"
    household_user.save()
    refused = auth_client.patch("/api/auth/me", {"name": "   "}, format="json")
    assert refused.status_code == 400
    assert "Nothing was changed" in str(refused.data["name"][0])
    household_user.refresh_from_db()
    assert household_user.first_name == "Kevin"


def test_a_password_change_needs_the_current_one(auth_client, household_user):
    wrong = auth_client.post(
        "/api/auth/password",
        {"current_password": "not it", "new_password": NEW},
        format="json",
    )
    assert wrong.status_code == 400
    assert wrong.data["detail"] == WRONG_CURRENT_PASSWORD
    household_user.refresh_from_db()
    assert household_user.check_password(PASSWORD)


def test_a_weak_new_password_is_refused_naming_the_rule(auth_client, household_user):
    weak = auth_client.post(
        "/api/auth/password",
        {"current_password": PASSWORD, "new_password": "12345678"},
        format="json",
    )
    assert weak.status_code == 400
    assert weak.data["detail"].startswith("Nothing was changed.")
    assert "common" in weak.data["detail"] or "numeric" in weak.data["detail"]
    household_user.refresh_from_db()
    assert household_user.check_password(PASSWORD)


def test_a_password_change_keeps_the_session_and_the_new_one_signs_in(household_user):
    client = _session_client(household_user)
    changed = client.post(
        "/api/auth/password", {"current_password": PASSWORD, "new_password": NEW}, format="json"
    )
    assert changed.status_code == 200, changed.data
    # The same browser session still answers: update_session_auth_hash kept it.
    assert client.get("/api/auth/me").status_code == 200
    household_user.refresh_from_db()
    assert household_user.check_password(NEW)
    assert not household_user.check_password(PASSWORD)
    fresh = APIClient().post(
        "/api/auth/session/login", {"email": household_user.email, "password": NEW},
        format="json",
    )
    assert fresh.status_code == 200


def test_a_device_token_survives_a_password_change(auth_client, household_user):
    changed = auth_client.post(
        "/api/auth/password", {"current_password": PASSWORD, "new_password": NEW}, format="json"
    )
    assert changed.status_code == 200
    assert auth_client.get("/api/auth/me").status_code == 200


def test_password_guesses_are_throttled(auth_client, household_user):
    statuses = [
        auth_client.post(
            "/api/auth/password",
            {"current_password": f"guess {n}", "new_password": NEW},
            format="json",
        ).status_code
        for n in range(6)
    ]
    assert statuses[:5] == [400] * 5
    assert statuses[5] == 429
    household_user.refresh_from_db()
    assert household_user.check_password(PASSWORD)


def test_both_routes_need_a_credential():
    client = APIClient()
    assert client.patch("/api/auth/me", {"name": "x"}, format="json").status_code == 401
    assert (
        client.post(
            "/api/auth/password", {"current_password": "a", "new_password": "b"}, format="json"
        ).status_code
        == 401
    )


def test_session_writes_need_a_csrf_token(household_user):
    client = APIClient(enforce_csrf_checks=True)
    client.get("/api/auth/csrf")
    signed_in = client.post(
        "/api/auth/session/login",
        {"email": household_user.email, "password": PASSWORD},
        format="json",
    )
    assert signed_in.status_code == 200
    cache.clear()
    assert client.patch("/api/auth/me", {"name": "x"}, format="json").status_code == 403
    token = client.cookies["csrftoken"].value
    renamed = client.patch("/api/auth/me", {"name": "Kevin"}, format="json", HTTP_X_CSRFTOKEN=token)
    assert renamed.status_code == 200, renamed.data
