"""The auth endpoints, exercised over HTTP with DRF's test client: the
original three (login, logout, me), plus (web-and-households ticket 03
narrow slice, 2026-09-10) the browser's session pair and the csrf-cookie
primer."""
from __future__ import annotations

import pytest
from django.core.cache import cache
from rest_framework.test import APIClient

from household.models import DeviceToken, HouseholdMember, User

pytestmark = pytest.mark.django_db


@pytest.fixture(autouse=True)
def _reset_login_throttle():
    """`ScopedRateThrottle` counts requests in Django's cache, which this
    suite's LocMemCache never clears between tests on its own. Both login
    endpoints share the `login` scope (5/min) by design, and this file now
    calls one or the other several times per test - without this, a later
    test in the file starts pre-throttled by an earlier one's calls and
    fails with an unrelated 429, not the status the test is actually
    checking."""
    cache.clear()
    yield
    cache.clear()


def _make_member(
    household, email: str = "kevin@example.com", password: str = "correct horse battery"
) -> User:
    """ADR 0045: a `HouseholdMember` names a household now, so every caller
    below takes the `household_a` fixture and hands it in. That is the ONLY
    change in this file - the four tests themselves are untouched."""
    user = User.objects.create_user(email=email, password=password)
    HouseholdMember.objects.create(user=user, household=household)
    return user


def test_wrong_password_returns_401_and_mints_no_token(household_a):
    _make_member(household_a)
    client = APIClient()

    response = client.post(
        "/api/auth/login",
        {"email": "kevin@example.com", "password": "wrong", "device_name": "Test phone"},
        format="json",
    )

    assert response.status_code == 401
    assert "token" not in response.data
    assert DeviceToken.objects.count() == 0


def test_right_password_returns_a_token_that_passes_me(household_a):
    user = _make_member(household_a)
    client = APIClient()

    login = client.post(
        "/api/auth/login",
        {
            "email": "kevin@example.com",
            "password": "correct horse battery",
            "device_name": "Test phone",
        },
        format="json",
    )
    assert login.status_code == 200
    token = login.data["token"]
    assert login.data["user_id"] == str(user.id)

    client.credentials(HTTP_AUTHORIZATION=f"Token {token}")
    me = client.get("/api/auth/me")
    assert me.status_code == 200
    assert me.data["email"] == "kevin@example.com"
    assert me.data["device_name"] == "Test phone"


def test_revoked_token_gets_401(household_a):
    user = _make_member(household_a)
    _token, raw_key = DeviceToken.issue(user, "Test phone")
    from django.utils import timezone

    _token.revoked_at = timezone.now()
    _token.save(update_fields=["revoked_at"])

    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")
    response = client.get("/api/auth/me")

    assert response.status_code == 401


def test_logout_revokes_only_the_calling_token(household_a):
    user = _make_member(household_a)
    _first, first_key = DeviceToken.issue(user, "Phone A")
    _second, second_key = DeviceToken.issue(user, "Phone B")

    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {first_key}")
    logout = client.post("/api/auth/logout")
    assert logout.status_code == 204

    client.credentials(HTTP_AUTHORIZATION=f"Token {first_key}")
    assert client.get("/api/auth/me").status_code == 401

    client.credentials(HTTP_AUTHORIZATION=f"Token {second_key}")
    assert client.get("/api/auth/me").status_code == 200


def _member(household, email: str, password: str = "correct horse battery") -> User:
    user = User.objects.create_user(email=email, password=password)
    HouseholdMember.objects.create(user=user, household=household)
    return user


def test_session_login_returns_me_shape_and_sets_a_session_cookie(household_a):
    _member(household_a, "wife@example.com")
    client = APIClient()

    response = client.post(
        "/api/auth/session/login",
        {"email": "wife@example.com", "password": "correct horse battery"},
        format="json",
    )

    assert response.status_code == 200
    assert response.data["email"] == "wife@example.com"
    assert response.data["device_name"] == ""
    assert response.data["household"]["id"] == str(household_a.id)
    assert "token" not in response.data
    assert "sessionid" in response.cookies
    # A session login must never mint a device token - ADR 0044 rule 3.
    assert DeviceToken.objects.count() == 0


def test_session_login_wrong_password_returns_401_and_no_session(household_a):
    _member(household_a, "wife@example.com")
    client = APIClient()

    response = client.post(
        "/api/auth/session/login",
        {"email": "wife@example.com", "password": "wrong"},
        format="json",
    )

    assert response.status_code == 401
    assert "sessionid" not in response.cookies


def test_session_login_then_me_works_over_the_session_cookie(household_a):
    _member(household_a, "wife@example.com")
    client = APIClient()

    login = client.post(
        "/api/auth/session/login",
        {"email": "wife@example.com", "password": "correct horse battery"},
        format="json",
    )
    assert login.status_code == 200

    # No Authorization header at all - the session cookie the test client
    # carries forward is the only credential on this second request.
    me = client.get("/api/auth/me")
    assert me.status_code == 200
    assert me.data["email"] == "wife@example.com"
    assert me.data["household"]["id"] == str(household_a.id)


def test_me_still_works_over_a_device_token(household_a):
    """DeviceTokenAuthentication is listed first in
    DEFAULT_AUTHENTICATION_CLASSES; adding SessionAuthentication after it
    must not change this path at all."""
    user = _member(household_a, "kevin@example.com")
    _token, raw_key = DeviceToken.issue(user, "Test phone")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")

    response = client.get("/api/auth/me")

    assert response.status_code == 200
    assert response.data["device_name"] == "Test phone"
    assert response.data["household"]["id"] == str(household_a.id)


def test_session_logout_ends_the_session(household_a):
    _member(household_a, "wife@example.com")
    client = APIClient(enforce_csrf_checks=True)

    client.get("/api/auth/csrf")
    login = client.post(
        "/api/auth/session/login",
        {"email": "wife@example.com", "password": "correct horse battery"},
        format="json",
    )
    assert login.status_code == 200

    # Read AFTER login, not before: Django's `login()` rotates the CSRF
    # token as a fixation defence, so the cookie the pre-login GET set is
    # already stale by the time this POST goes out.
    csrf_token = client.cookies["csrftoken"].value
    logout = client.post(
        "/api/auth/session/logout", HTTP_X_CSRFTOKEN=csrf_token
    )
    assert logout.status_code == 204

    me = client.get("/api/auth/me")
    assert me.status_code in (401, 403)


def test_session_logout_never_revokes_a_device_token(household_a):
    """A device-token bearer cannot even reach this endpoint -
    `authentication_classes = [SessionAuthentication]` on
    `SessionLogoutView` means the token is simply not looked at."""
    user = _member(household_a, "kevin@example.com")
    _token, raw_key = DeviceToken.issue(user, "Test phone")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")

    response = client.post("/api/auth/session/logout")

    assert response.status_code == 403
    _token.refresh_from_db()
    assert not _token.is_revoked


def test_csrf_endpoint_sets_the_cookie():
    client = APIClient()

    response = client.get("/api/auth/csrf")

    assert response.status_code == 200
    assert "csrftoken" in response.cookies


def test_session_logout_requires_a_csrf_token(household_a):
    """The load-bearing CSRF pair: a session-authenticated unsafe request is
    refused with no `X-CSRFToken`, and succeeds with the right one. Proven
    with `enforce_csrf_checks=True` - the default `APIClient()` used
    elsewhere in this file turns CSRF enforcement off entirely, which would
    prove nothing here."""
    _member(household_a, "wife@example.com")
    client = APIClient(enforce_csrf_checks=True)

    client.get("/api/auth/csrf")
    login = client.post(
        "/api/auth/session/login",
        {"email": "wife@example.com", "password": "correct horse battery"},
        format="json",
    )
    assert login.status_code == 200

    no_token = client.post("/api/auth/session/logout")
    assert no_token.status_code == 403

    csrf_token = client.cookies["csrftoken"].value
    with_token = client.post("/api/auth/session/logout", HTTP_X_CSRFTOKEN=csrf_token)
    assert with_token.status_code == 204


def test_device_token_requests_never_need_a_csrf_token(household_a):
    """The other half of the same pair: a device-token-authenticated unsafe
    request keeps working under CSRF enforcement, because
    DeviceTokenAuthentication never calls `enforce_csrf`. Proven against
    `LogoutView` (token path) under the same `enforce_csrf_checks=True`
    client the session test above uses."""
    user = _member(household_a, "kevin@example.com")
    _token, raw_key = DeviceToken.issue(user, "Test phone")
    client = APIClient(enforce_csrf_checks=True)
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")

    response = client.post("/api/auth/logout")

    assert response.status_code == 204


def test_non_member_gets_403_under_a_device_token(household_a):
    user = User.objects.create_user(email="stranger@example.com", password="correct horse battery")
    _token, raw_key = DeviceToken.issue(user, "Test phone")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")

    response = client.get("/api/auth/me")

    assert response.status_code == 403


def test_non_member_gets_403_under_a_session(household_a):
    User.objects.create_user(email="stranger@example.com", password="correct horse battery")
    client = APIClient()

    login = client.post(
        "/api/auth/session/login",
        {"email": "stranger@example.com", "password": "correct horse battery"},
        format="json",
    )
    # Login itself only checks the password, not membership - it is
    # AllowAny, the same as the device-token LoginView.
    assert login.status_code == 200
    assert login.data["household"] is None

    me = client.get("/api/auth/me")
    assert me.status_code == 403
