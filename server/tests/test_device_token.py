"""Token hash round-trip and revocation, independent of the HTTP layer."""
from __future__ import annotations

import pytest

from household.models import DeviceToken, User, hash_device_key

pytestmark = pytest.mark.django_db


def test_issue_returns_raw_key_and_stores_only_its_hash():
    user = User.objects.create_user(email="kevin@example.com", password="correct horse battery")
    token, raw_key = DeviceToken.issue(user, "Kevin's Pixel")

    token.refresh_from_db()
    assert token.key_hash == hash_device_key(raw_key)
    # The raw key itself never lands in a column - only its hash does.
    assert raw_key != token.key_hash
    assert len(raw_key) == 64  # 32 bytes, hex-encoded


def test_revoked_token_is_reported_as_revoked():
    user = User.objects.create_user(email="kevin@example.com", password="correct horse battery")
    token, _raw_key = DeviceToken.issue(user, "Kevin's Pixel")
    assert not token.is_revoked

    from django.utils import timezone

    token.revoked_at = timezone.now()
    token.save(update_fields=["revoked_at"])

    token.refresh_from_db()
    assert token.is_revoked
