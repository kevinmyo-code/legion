"""backend-etl ticket 02: the session vault (`ingest/vault.py`) and its routes
(`ingest/sessions.py`).

The household-isolation half lives in `tests/test_tenancy.py` with every other
leak test (`test_sessions_are_scoped_by_household`).
"""
from __future__ import annotations

import datetime
import json

import pytest
from cryptography.fernet import Fernet
from django.db import IntegrityError, connection, transaction

from api.management.commands.write_openapi import schema_path
from ingest import vault
from ingest.jobs import JobCommand, NeedsLogin, run_job
from ingest.models import CredentialKind, IngestRun, Outcome, Source, SourceCredential
from tests.test_accounts import client_for, make_member

pytestmark = pytest.mark.django_db

COOKIE_VALUE = "canvas-session-cookie-value-9f2c1e"
REFRESH_TOKEN = "1//refresh-token-value-4b7d0a"
CLIENT_SECRET = "GOCSPX-client-secret-value-77aa"


@pytest.fixture
def vault_key(monkeypatch):
    key = Fernet.generate_key().decode()
    monkeypatch.setenv(vault.VAULT_KEY_ENV, key)
    return key


@pytest.fixture
def no_vault_key(monkeypatch):
    monkeypatch.delenv(vault.VAULT_KEY_ENV, raising=False)


def a_cookie_jar(value: str = COOKIE_VALUE) -> dict:
    return {
        "cookies": [
            {
                "name": "canvas_session",
                "value": value,
                "domain": "canvas.example.edu",
                "path": "/",
                "expires": 1_900_000_000,
                "secure": True,
                "httpOnly": True,
                "sameSite": "None",
            }
        ]
    }


def a_drive_token() -> dict:
    return {
        "refresh_token": REFRESH_TOKEN,
        "client_id": "123-abc.apps.googleusercontent.com",
        "client_secret": CLIENT_SECRET,
        "token_uri": "https://oauth2.googleapis.com/token",
        "scopes": ["https://www.googleapis.com/auth/drive.readonly"],
    }


def _row_text(table: str = "source_credentials") -> str:
    """Every column of every row, as Postgres renders it - bytea as hex and
    as escaped text both, so a plaintext secret cannot hide in either."""
    with connection.cursor() as cursor:
        cursor.execute(f"select row_to_json(t)::text from public.{table} t")
        rows = [r[0] for r in cursor.fetchall()]
        cursor.execute(f"select encode(ciphertext, 'escape') from public.{table}")
        rows += [r[0] for r in cursor.fetchall()]
    return "\n".join(rows)


@pytest.fixture
def member_client(household_a):
    return client_for(make_member(household_a, "member@example.com"))


# =============================================================================
# Ciphertext
# =============================================================================


def test_seal_round_trips_and_the_ciphertext_is_not_the_plaintext(vault_key):
    sealed = vault.seal(a_cookie_jar())
    assert COOKIE_VALUE.encode() not in sealed
    assert vault.unseal(sealed) == a_cookie_jar()


def test_stored_bytes_never_contain_the_plaintext(vault_key, household_a):
    vault.store(household_a, "canvas", a_cookie_jar(), config={"base_url": "https://c.edu"})
    vault.store(household_a, "drive", a_drive_token())

    stored = _row_text()
    for secret in (COOKIE_VALUE, REFRESH_TOKEN, CLIENT_SECRET):
        assert secret not in stored
    assert "https://c.edu" in stored  # config is deliberately in the clear

    # And the raw column round-trips back through the vault.
    credential = SourceCredential.objects.get(household=household_a, source="drive")
    assert credential.kind == CredentialKind.OAUTH_REFRESH
    assert vault.unseal(credential.ciphertext) == a_drive_token()


def test_a_different_key_cannot_open_a_stored_session(vault_key, household_a, monkeypatch):
    vault.store(household_a, "canvas", a_cookie_jar())
    monkeypatch.setenv(vault.VAULT_KEY_ENV, Fernet.generate_key().decode())

    with pytest.raises(NeedsLogin, match="key has changed"):
        vault.session_for(household_a, "canvas")


def test_no_key_refuses_to_store_and_writes_nothing(no_vault_key, household_a):
    with pytest.raises(vault.VaultUnavailable, match="LEGION_VAULT_KEY is not set"):
        vault.store(household_a, "canvas", a_cookie_jar())
    assert not SourceCredential.objects.exists()


def test_a_malformed_key_refuses_in_words(monkeypatch, household_a):
    monkeypatch.setenv(vault.VAULT_KEY_ENV, "not-a-fernet-key")
    with pytest.raises(vault.VaultUnavailable, match="not a Fernet key"):
        vault.store(household_a, "canvas", a_cookie_jar())


def test_no_key_makes_a_job_record_needs_login(vault_key, household_a, monkeypatch):
    vault.store(household_a, "canvas", a_cookie_jar())
    monkeypatch.delenv(vault.VAULT_KEY_ENV)

    run = run_job(
        Source.CANVAS, household_a, lambda run: vault.session_for(run.household, "canvas")
    )

    assert run.outcome == Outcome.NEEDS_LOGIN
    assert "LEGION_VAULT_KEY is not set" in run.error


# =============================================================================
# The routes
# =============================================================================


def test_owner_put_stores_and_never_echoes_the_secret(vault_key, token_a, household_a):
    response = token_a.put(
        "/api/ingest/sessions/canvas",
        {"secret": a_cookie_jar(), "config": {"base_url": "https://canvas.example.edu"}},
        format="json",
    )

    assert response.status_code == 200, response.data
    body = json.dumps(response.data, default=str)
    assert COOKIE_VALUE not in body
    assert "secret" not in response.data and "ciphertext" not in response.data
    assert response.data["source"] == "canvas"
    assert response.data["kind"] == "cookie_jar"
    assert response.data["config"] == {"base_url": "https://canvas.example.edu"}
    assert response.data["invalid_since"] is None
    assert SourceCredential.objects.filter(household=household_a, source="canvas").count() == 1


def test_get_returns_metadata_and_never_the_secret(vault_key, token_a, member_client):
    token_a.put("/api/ingest/sessions/canvas", {"secret": a_cookie_jar()}, format="json")
    token_a.put("/api/ingest/sessions/drive", {"secret": a_drive_token()}, format="json")

    # Any member reads the metadata; the owner role gates only the PUT.
    for client in (token_a, member_client):
        listed = client.get("/api/ingest/sessions")
        one = client.get("/api/ingest/sessions/drive")
        assert listed.status_code == 200 and one.status_code == 200
        text = json.dumps([listed.data, one.data], default=str)
        for secret in (COOKIE_VALUE, REFRESH_TOKEN, CLIENT_SECRET):
            assert secret not in text
        assert [s["source"] for s in listed.data["sessions"]] == ["canvas", "drive"]
        assert set(one.data) == {
            "source",
            "kind",
            "captured_at",
            "expires_hint",
            "invalid_since",
            "config",
        }

    assert token_a.get("/api/ingest/sessions/webassign").status_code == 404


def test_a_non_owner_put_is_refused_and_stores_nothing(vault_key, member_client):
    response = member_client.put(
        "/api/ingest/sessions/canvas", {"secret": a_cookie_jar()}, format="json"
    )

    assert response.status_code == 403
    assert "Only an owner" in str(response.data["detail"])
    assert not SourceCredential.objects.exists()


def test_an_anonymous_put_is_refused(vault_key, client):
    response = client.put(
        "/api/ingest/sessions/canvas",
        json.dumps({"secret": a_cookie_jar()}),
        content_type="application/json",
    )
    assert response.status_code in (401, 403)
    assert not SourceCredential.objects.exists()


def test_a_bofa_session_is_refused_by_name_everywhere(vault_key, token_a, household_a):
    """Map ruling 7 / ticket 09: a BofA session never reaches the server."""
    response = token_a.put(
        "/api/ingest/sessions/bofa", {"secret": a_cookie_jar()}, format="json"
    )
    assert response.status_code == 400
    assert "never leaves the laptop" in response.data["detail"]
    assert not SourceCredential.objects.exists()

    with pytest.raises(vault.SecretRejected, match="never leaves the laptop"):
        vault.store(household_a, "bofa", a_cookie_jar())

    # And in SQL, so a bug in both of the above still cannot store one.
    with pytest.raises(IntegrityError), transaction.atomic():
        SourceCredential.objects.create(
            household=household_a,
            source="bofa",
            kind=CredentialKind.COOKIE_JAR,
            ciphertext=b"x",
            captured_at=datetime.datetime.now(datetime.UTC),
        )
    assert not SourceCredential.objects.exists()


def test_the_kind_must_match_the_source_in_sql(household_a):
    with pytest.raises(IntegrityError), transaction.atomic():
        SourceCredential.objects.create(
            household=household_a,
            source="drive",
            kind=CredentialKind.COOKIE_JAR,
            ciphertext=b"x",
            captured_at=datetime.datetime.now(datetime.UTC),
        )


def test_an_unknown_source_is_refused(vault_key, token_a):
    response = token_a.put(
        "/api/ingest/sessions/gmail", {"secret": a_cookie_jar()}, format="json"
    )
    assert response.status_code == 400
    assert "not a source this vault holds" in response.data["detail"]


@pytest.mark.parametrize(
    ("source", "secret", "words"),
    [
        ("canvas", {"cookies": []}, "at least one cookie"),
        ("canvas", {"cookies": [{"name": "a", "domain": "x"}]}, "name, a value and a domain"),
        ("canvas", a_drive_token(), "at least one cookie"),
        ("drive", a_cookie_jar(), "refresh_token, client_id"),
        ("drive", {**a_drive_token(), "refresh_token": ""}, "refresh_token, client_id"),
        ("canvas", {**a_cookie_jar(), "password": "hunter2"}, "names a password"),
        ("drive", {**a_drive_token(), "extra": {"Passwd": "x"}}, "names a password"),
    ],
)
def test_a_secret_of_the_wrong_shape_is_refused(vault_key, token_a, source, secret, words):
    response = token_a.put(f"/api/ingest/sessions/{source}", {"secret": secret}, format="json")
    assert response.status_code == 400, response.data
    assert words in response.data["detail"]
    assert "hunter2" not in str(response.data)
    assert not SourceCredential.objects.exists()


def test_a_config_key_naming_a_secret_is_refused(vault_key, token_a):
    response = token_a.put(
        "/api/ingest/sessions/canvas",
        {"secret": a_cookie_jar(), "config": {"base_url": "https://c", "api_token": "x"}},
        format="json",
    )
    assert response.status_code == 400
    assert "api_token" in response.data["detail"]
    assert not SourceCredential.objects.exists()


def test_put_without_a_key_is_a_503_in_words(no_vault_key, token_a):
    response = token_a.put(
        "/api/ingest/sessions/canvas", {"secret": a_cookie_jar()}, format="json"
    )
    assert response.status_code == 503
    assert "LEGION_VAULT_KEY is not set" in response.data["detail"]
    assert not SourceCredential.objects.exists()


def test_a_fresh_login_replaces_the_secret_merges_config_and_clears_invalid(
    vault_key, token_a, household_a
):
    token_a.put(
        "/api/ingest/sessions/drive",
        {"secret": a_drive_token(), "config": {"folder_id": "abc"}},
        format="json",
    )
    credential = SourceCredential.objects.get(household=household_a, source="drive")
    vault.refuse_session(credential, "HTTP 401")

    fresh = {**a_drive_token(), "refresh_token": "1//a-newer-token"}
    response = token_a.put(
        "/api/ingest/sessions/drive",
        {"secret": fresh, "config": {"scopes": ["drive.readonly"]}},
        format="json",
    )

    assert response.status_code == 200
    assert response.data["invalid_since"] is None
    assert response.data["config"] == {"folder_id": "abc", "scopes": ["drive.readonly"]}
    assert SourceCredential.objects.filter(household=household_a).count() == 1
    _credential, secret = vault.session_for(household_a, "drive")
    assert secret["refresh_token"] == "1//a-newer-token"


# =============================================================================
# Jobs: needs_login, and the freshness sentence it produces
# =============================================================================


def test_session_for_with_nothing_stored_raises_needs_login(vault_key, household_a):
    with pytest.raises(NeedsLogin, match="run tools/connect_session.py canvas"):
        vault.session_for(household_a, "canvas")


def test_a_refused_session_records_needs_login_and_freshness_says_what_to_run(
    vault_key, token_a, household_a
):
    vault.store(household_a, "canvas", a_cookie_jar())

    def canvas_job(run):
        credential, secret = vault.session_for(run.household, "canvas")
        assert secret["cookies"][0]["value"] == COOKIE_VALUE
        # What ticket 04 will see from Canvas when the session has expired.
        if vault.is_login_refusal(302, "https://canvas.example.edu/login/saml"):
            raise vault.refuse_session(credential, "redirected to the login page")

    run = run_job(Source.CANVAS, household_a, canvas_job)

    assert run.outcome == Outcome.NEEDS_LOGIN
    assert COOKIE_VALUE not in run.error
    assert "refused the saved login (redirected to the login page)" in run.error
    # Inside this test's transaction `run_job` gives the job a savepoint, and
    # the raise rolls back the stamp `refuse_session` wrote. It must survive
    # anyway (first found failing exactly here, 2026-09-27).
    credential = SourceCredential.objects.get(household=household_a, source="canvas")
    first_refusal = credential.invalid_since
    assert first_refusal is not None

    canvas = {e["source"]: e for e in token_a.get("/api/freshness").data["sources"]}["canvas"]
    assert canvas["sentence"] == (
        "Canvas needs you to log in again: run tools/connect_session.py canvas"
    )
    assert canvas["stale"] is True

    # The next run stops at the stamp without replaying the dead session,
    # and the stamp keeps the FIRST refusal.
    touched = []
    again = run_job(
        Source.CANVAS,
        household_a,
        lambda run: touched.append(vault.session_for(run.household, "canvas")),
    )
    assert again.outcome == Outcome.NEEDS_LOGIN
    assert touched == []
    credential.refresh_from_db()
    assert credential.invalid_since == first_refusal


def test_the_drive_pipelines_point_at_the_drive_login(token_a, household_a):
    IngestRun.objects.create(
        household=household_a,
        source=Source.BACKUP,
        outcome=Outcome.NEEDS_LOGIN,
        finished_at=datetime.datetime.now(datetime.UTC),
    )
    backup = {e["source"]: e for e in token_a.get("/api/freshness").data["sources"]}["backup"]
    assert backup["sentence"] == (
        "The backup needs you to log in again: run tools/connect_session.py drive"
    )


class _CanvasLikeCommand(JobCommand):
    source = Source.CANVAS
    session_source = "canvas"

    def job(self, run):
        vault.session_for(run.household, "canvas")


def test_a_household_with_no_session_records_skipped_not_needs_login(
    vault_key, household_a, household_b
):
    vault.store(household_b, "canvas", a_cookie_jar())

    _CanvasLikeCommand().handle()

    assert IngestRun.objects.get(household=household_a).outcome == Outcome.SKIPPED
    assert IngestRun.objects.get(household=household_b).outcome == Outcome.OK


@pytest.mark.parametrize(
    ("status", "url", "refused"),
    [
        (401, None, True),
        (403, "https://canvas.example.edu/api/v1/courses", True),
        (200, "https://canvas.example.edu/login/canvas", True),
        (200, "https://sso.example.edu/cas/login?service=x", True),
        (200, "https://canvas.example.edu/api/v1/courses", False),
        (500, None, False),
    ],
)
def test_is_login_refusal(status, url, refused):
    assert vault.is_login_refusal(status, url) is refused


def test_the_openapi_file_describes_the_session_routes():
    """The staleness check in `test_openapi_schema.py` is the real gate; this
    only makes the ticket's routes visible by name in a failure here."""
    text = schema_path().read_text(encoding="utf-8")
    assert "/api/ingest/sessions/{source}:" in text
    assert "/api/ingest/sessions:" in text
