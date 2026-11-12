"""alfred-ota ticket 04, the engine half: `/api/releases/*` and the signed-URL builder.

The ticket's verification list: the release endpoints; publishing is owner-only and reading is
open to members; the signed-URL builder, with a fake signer; versionCode must increase. The real
`GcsUrlSigner` is exercised against stand-in Google modules (no network, no bucket), which pins
the call shape the IAM signBlob path needs; whether Google accepts it is Kevin's first real
download after `setup_bucket.sh` and a deploy.
"""

from __future__ import annotations

import sys
import types
import uuid
from datetime import timedelta

import pytest
from rest_framework.test import APIClient

from household.models import DeviceToken, HouseholdMember, User
from household.recruiter_routes import RECRUITER_DENIED, denied_refusal
from releases import signing
from releases.models import AppRelease
from releases.signing import DOWNLOAD_TTL, GcsUrlSigner, SigningError
from tests.conftest import _client_for

pytestmark = pytest.mark.django_db

SHA = "ab" * 32
SIGNER = "fd819ada7fdbf98f4b072b511d2dff33cb3dc68b720b8a9a0ff862ebd03aac01"


def _body(**overrides) -> dict:
    body = {
        "app": "phone",
        "version_code": 263091200,
        "version_name": "2026-11-05-abc1234",
        "sha256": SHA,
        "size_bytes": 200_000_000,
        "object_name": "phone/263091200-abababababab.apk",
        "notes": "fixes the double announce",
        "signer_sha256": SIGNER,
    }
    body.update(overrides)
    return body


@pytest.fixture
def member_client(household_a):
    user = User.objects.create_user(email="member@example.com", password="correct horse battery")
    HouseholdMember.objects.create(user=user, household=household_a, role=HouseholdMember.MEMBER)
    return _client_for(user)


@pytest.fixture
def owner_client(auth_client):
    """`household_user` is the household's owner (`conftest._member`)."""
    return auth_client


class FakeSigner:
    def __init__(self):
        self.calls = []

    def sign_get(self, object_name, expires):
        self.calls.append((object_name, expires))
        return f"https://storage.example/{object_name}?X-Goog-Expires={int(expires.total_seconds())}"


@pytest.fixture
def fake_signer(monkeypatch):
    signer = FakeSigner()
    monkeypatch.setattr(signing, "get_signer", lambda: signer)
    return signer


# =============================================================================
# Publishing
# =============================================================================


def test_an_owner_publishes_and_the_object_name_never_comes_back(owner_client, household_user):
    response = owner_client.post("/api/releases/", _body(), format="json")
    assert response.status_code == 201, response.data
    assert response.data["version_code"] == 263091200
    assert "object_name" not in response.data and "published_by" not in response.data
    stored = AppRelease.objects.get()
    assert stored.object_name == "phone/263091200-abababababab.apk"
    assert stored.published_by == household_user


def test_a_member_who_is_not_an_owner_cannot_publish(member_client):
    response = member_client.post("/api/releases/", _body(), format="json")
    assert response.status_code == 403
    assert response.data["detail"].startswith("Nothing was published.")
    assert not AppRelease.objects.exists()


def test_an_owner_of_another_household_cannot_publish_an_engine_wide_release(token_b):
    """`user_b` owns household B, not the household that runs the engine. Releases serve every
    household, so publishing is the engine's owners' call, never any owner's."""
    refused = token_b.post("/api/releases/", _body(version_code=2147483647), format="json")
    assert refused.status_code == 403
    assert refused.data["detail"].startswith("Nothing was published.")
    assert not AppRelease.objects.exists()


def test_a_read_scoped_token_cannot_publish(household_user):
    _token, raw = DeviceToken.issue(household_user, "Read only", DeviceToken.SCOPE_READ)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    assert client.post("/api/releases/", _body(), format="json").status_code == 403
    assert not AppRelease.objects.exists()


def test_the_version_code_must_increase(owner_client):
    assert owner_client.post("/api/releases/", _body(), format="json").status_code == 201
    for code in (263091200, 263091159):
        response = owner_client.post(
            "/api/releases/", _body(version_code=code, sha256="cd" * 32), format="json"
        )
        assert response.status_code == 409, code
        assert "not higher than the newest phone release, 263091200" in response.data["detail"]
    higher = owner_client.post("/api/releases/", _body(version_code=263091201), format="json")
    assert higher.status_code == 201
    assert AppRelease.objects.count() == 2


def test_each_app_counts_its_own_version_codes(owner_client):
    assert owner_client.post("/api/releases/", _body(), format="json").status_code == 201
    headunit = owner_client.post(
        "/api/releases/", _body(app="headunit", version_code=5), format="json"
    )
    assert headunit.status_code == 201


@pytest.mark.parametrize(
    "overrides",
    [
        {"app": "watch"},
        {"sha256": "AB" * 32},
        {"sha256": "ab" * 31},
        {"signer_sha256": "FD:81:9A"},
        {"version_code": 0},
        {"size_bytes": 0},
        {"object_name": "/phone/x.apk"},
        {"object_name": "phone/../secrets"},
    ],
)
def test_a_malformed_release_is_refused(owner_client, overrides):
    response = owner_client.post("/api/releases/", _body(**overrides), format="json")
    assert response.status_code == 400, response.data
    assert not AppRelease.objects.exists()


# =============================================================================
# Reading
# =============================================================================


def test_latest_is_the_highest_version_code_and_open_to_members(owner_client, member_client):
    assert member_client.get("/api/releases/latest?app=phone").status_code == 404
    owner_client.post("/api/releases/", _body(version_code=10), format="json")
    owner_client.post("/api/releases/", _body(version_code=12, notes="newest"), format="json")
    owner_client.post("/api/releases/", _body(app="headunit", version_code=99), format="json")
    response = member_client.get("/api/releases/latest?app=phone")
    assert response.status_code == 200
    assert response.data["version_code"] == 12 and response.data["notes"] == "newest"
    assert "object_name" not in response.data
    assert member_client.get("/api/releases/latest").data["version_code"] == 12
    assert member_client.get("/api/releases/latest?app=headunit").data["version_code"] == 99
    assert member_client.get("/api/releases/latest?app=toaster").status_code == 400


def test_releases_are_engine_wide_so_another_household_reads_them(owner_client, token_b):
    """Not household-scoped (`releases/models.py`, `tenancy.ENGINE_WIDE_TABLES`)."""
    owner_client.post("/api/releases/", _body(), format="json")
    assert token_b.get("/api/releases/latest").data["version_code"] == 263091200


def test_download_is_a_signed_link_for_fifteen_minutes(owner_client, member_client, fake_signer):
    release_id = owner_client.post("/api/releases/", _body(), format="json").data["id"]
    response = member_client.get(f"/api/releases/{release_id}/download")
    assert response.status_code == 200, response.data
    assert fake_signer.calls == [("phone/263091200-abababababab.apk", timedelta(minutes=15))]
    assert response.data["url"].endswith("X-Goog-Expires=900")
    assert response.data["sha256"] == SHA
    assert response.data["size_bytes"] == 200_000_000
    assert response.data["version_code"] == 263091200
    assert response.data["expires_at"]


def test_download_of_an_unknown_release_is_a_404(member_client, fake_signer):
    response = member_client.get(f"/api/releases/{uuid.uuid4()}/download")
    assert response.status_code == 404
    assert fake_signer.calls == []


def test_download_with_no_bucket_refuses_in_words(owner_client, member_client, settings):
    settings.LEGION_APK_BUCKET = ""
    release_id = owner_client.post("/api/releases/", _body(), format="json").data["id"]
    response = member_client.get(f"/api/releases/{release_id}/download")
    assert response.status_code == 503
    assert "LEGION_APK_BUCKET" in response.data["detail"]


def test_a_signing_failure_refuses_in_words(owner_client, member_client, monkeypatch):
    class Refusing:
        def sign_get(self, object_name, expires):
            raise SigningError("Google refused to sign it (Forbidden: no signBlob).")

    monkeypatch.setattr(signing, "get_signer", lambda: Refusing())
    release_id = owner_client.post("/api/releases/", _body(), format="json").data["id"]
    response = member_client.get(f"/api/releases/{release_id}/download")
    assert response.status_code == 503
    assert response.data["detail"].startswith("No download link was made")
    assert "signBlob" in response.data["detail"]


def test_an_anonymous_caller_gets_nothing():
    client = APIClient()
    assert client.get("/api/releases/latest").status_code in (401, 403)
    assert client.post("/api/releases/", _body(), format="json").status_code in (401, 403)


def test_a_recruiter_is_refused_every_release_route(owner_client):
    from household.models import Household
    from tests.test_recruiter import _recruiter_browser, _visitor_token

    release_id = owner_client.post("/api/releases/", _body(), format="json").data["id"]
    household = Household.objects.create(name="Demo visit releases", is_demo_visit=True)
    visitor = User.objects.create_user(email="visitor-releases@demo.invalid")
    HouseholdMember.objects.create(
        user=visitor, household=household, role=HouseholdMember.RECRUITER
    )
    token, _raw = _visitor_token(visitor)
    browser = _recruiter_browser(visitor, token)
    expected = denied_refusal(RECRUITER_DENIED["releases.views.ReleaseLatestView"])
    for method, path in (
        ("get", "/api/releases/latest"),
        ("get", f"/api/releases/{release_id}/download"),
        ("post", "/api/releases/"),
    ):
        response = getattr(browser, method)(path, _body(), format="json")
        assert response.status_code == 403, path
        assert response.data["detail"] == expected


# =============================================================================
# The signed-URL builder
# =============================================================================


def test_get_signer_follows_the_bucket_setting(settings):
    settings.LEGION_APK_BUCKET = ""
    assert signing.get_signer() is None
    settings.LEGION_APK_BUCKET = "legion-apk-test"
    signer = signing.get_signer()
    assert isinstance(signer, GcsUrlSigner) and signer.bucket == "legion-apk-test"


def _fake_google(monkeypatch, *, email="engine@test.iam.gserviceaccount.com", token="tok"):
    """Stand-ins for `google.auth` and `google.cloud.storage`, recording what is asked of them."""
    seen = {}

    class Credentials:
        def __init__(self):
            self.token = None
            self.service_account_email = email

        def refresh(self, request):
            seen["refreshed"] = True
            self.token = token

    class Blob:
        def __init__(self, name):
            seen["object_name"] = name

        def generate_signed_url(self, **kwargs):
            seen["sign"] = kwargs
            return "https://storage.googleapis.com/signed"

    class Bucket:
        def __init__(self, name):
            seen["bucket"] = name

        def blob(self, name):
            return Blob(name)

    class Client:
        def __init__(self, project, credentials):
            seen["project"] = project

        def bucket(self, name):
            return Bucket(name)

    google = types.ModuleType("google")
    auth = types.ModuleType("google.auth")
    auth.default = lambda scopes: (Credentials(), "legion-engine-test")
    transport = types.ModuleType("google.auth.transport")
    requests = types.ModuleType("google.auth.transport.requests")
    requests.Request = lambda: object()
    cloud = types.ModuleType("google.cloud")
    storage = types.ModuleType("google.cloud.storage")
    storage.Client = Client
    google.auth, google.cloud, auth.transport, transport.requests = auth, cloud, transport, requests
    cloud.storage = storage
    for name, module in {
        "google": google,
        "google.auth": auth,
        "google.auth.transport": transport,
        "google.auth.transport.requests": requests,
        "google.cloud": cloud,
        "google.cloud.storage": storage,
    }.items():
        monkeypatch.setitem(sys.modules, name, module)
    return seen


def test_the_gcs_signer_asks_for_a_v4_get_signed_through_iam(monkeypatch):
    seen = _fake_google(monkeypatch)
    url = GcsUrlSigner("legion-apk-test").sign_get("phone/x.apk", DOWNLOAD_TTL)
    assert url == "https://storage.googleapis.com/signed"
    assert seen["bucket"] == "legion-apk-test" and seen["object_name"] == "phone/x.apk"
    assert seen["refreshed"]
    assert seen["sign"] == {
        "version": "v4",
        "expiration": timedelta(minutes=15),
        "method": "GET",
        "service_account_email": "engine@test.iam.gserviceaccount.com",
        "access_token": "tok",
    }


def test_the_gcs_signer_refuses_credentials_that_are_not_a_service_account(monkeypatch):
    _fake_google(monkeypatch, email=None)
    with pytest.raises(SigningError, match="not a service account"):
        GcsUrlSigner("legion-apk-test").sign_get("phone/x.apk", DOWNLOAD_TTL)
