"""The three auth endpoints, exercised over HTTP with DRF's test client."""
from __future__ import annotations

import pytest
from rest_framework.test import APIClient

from household.models import DeviceToken, HouseholdMember, User

pytestmark = pytest.mark.django_db


def _make_member(email: str = "kevin@example.com", password: str = "correct horse battery") -> User:
    user = User.objects.create_user(email=email, password=password)
    HouseholdMember.objects.create(user=user)
    return user


def test_wrong_password_returns_401_and_mints_no_token():
    _make_member()
    client = APIClient()

    response = client.post(
        "/api/auth/login",
        {"email": "kevin@example.com", "password": "wrong", "device_name": "Test phone"},
        format="json",
    )

    assert response.status_code == 401
    assert "token" not in response.data
    assert DeviceToken.objects.count() == 0


def test_right_password_returns_a_token_that_passes_me():
    user = _make_member()
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


def test_revoked_token_gets_401():
    user = _make_member()
    _token, raw_key = DeviceToken.issue(user, "Test phone")
    from django.utils import timezone

    _token.revoked_at = timezone.now()
    _token.save(update_fields=["revoked_at"])

    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")
    response = client.get("/api/auth/me")

    assert response.status_code == 401


def test_logout_revokes_only_the_calling_token():
    user = _make_member()
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
