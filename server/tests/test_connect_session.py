"""backend-etl ticket 02: `tools/connect_session.py`, everything testable
without a browser.

The script runs on the laptop, not the server, and imports Playwright,
google-auth-oauthlib and requests lazily, so it loads here with none of them
installed. What cannot run here - a real headed login - is owed on Kevin's
login (the ticket's third verification item).

The last tests feed the script's own PUT body into the real route, so the
script and the server cannot drift apart on the payload's shape.
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
from cryptography.fernet import Fernet

SCRIPT = Path(__file__).resolve().parents[2] / "tools" / "connect_session.py"


def _load():
    spec = importlib.util.spec_from_file_location("connect_session", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules["connect_session"] = module
    spec.loader.exec_module(module)
    return module


cs = _load()

SERVER = ["--server", "https://legion.example.com/", "--token", "tok"]


@pytest.fixture(autouse=True)
def _no_env(monkeypatch):
    for name in (
        "LEGION_SERVER",
        "LEGION_TOKEN",
        "LEGION_CANVAS_URL",
        "LEGION_GOOGLE_CLIENT_ID",
        "LEGION_GOOGLE_CLIENT_SECRET",
    ):
        monkeypatch.delenv(name, raising=False)


# =============================================================================
# Arguments
# =============================================================================


def test_canvas_arguments_are_parsed_and_normalised():
    args = cs.parse_args(["canvas", *SERVER, "--base-url", "canvas.school.edu/courses"])
    assert args.source == "canvas"
    assert args.server == "https://legion.example.com"
    assert args.base_url == "https://canvas.school.edu"
    assert args.timeout == cs.LOGIN_TIMEOUT_SECONDS


def test_arguments_fall_back_to_the_environment(monkeypatch):
    monkeypatch.setenv("LEGION_SERVER", "https://env.example.com")
    monkeypatch.setenv("LEGION_TOKEN", "env-token")
    monkeypatch.setenv("LEGION_GOOGLE_CLIENT_ID", "id")
    monkeypatch.setenv("LEGION_GOOGLE_CLIENT_SECRET", "secret")

    args = cs.parse_args(["drive"])

    assert (args.server, args.token) == ("https://env.example.com", "env-token")
    assert (args.client_id, args.client_secret) == ("id", "secret")


@pytest.mark.parametrize(
    ("argv", "named"),
    [
        (["canvas", "--token", "t", "--base-url", "https://c.edu"], "--server"),
        (["canvas", *SERVER], "--base-url"),
        (["drive", *SERVER, "--client-id", "id"], "--client-secret"),
        (["webassign", "--server", "https://s"], "--token"),
    ],
)
def test_a_missing_required_option_is_named(argv, named, capsys):
    with pytest.raises(SystemExit) as exited:
        cs.parse_args(argv)
    assert exited.value.code == 2
    assert named in capsys.readouterr().err


def test_bofa_is_not_a_subcommand_yet(capsys):
    """Ticket 09 adds it. Until then the script refuses the name outright."""
    with pytest.raises(SystemExit):
        cs.parse_args(["bofa", *SERVER])


# =============================================================================
# Cookie jars
# =============================================================================


def _cookie(name, domain, **extra):
    return {
        "name": name,
        "value": f"{name}-value",
        "domain": domain,
        "path": "/",
        "expires": -1,
        "httpOnly": True,
        "secure": True,
        "sameSite": "Lax",
        **extra,
    }


def test_the_jar_keeps_only_the_sites_own_cookies_and_fields():
    cookies = [
        _cookie("canvas_session", "canvas.school.edu", partitionKey="x"),
        _cookie("_csrf_token", ".canvas.school.edu"),
        _cookie("campus", ".school.edu"),  # a parent domain: sent to Canvas too
        _cookie("ESTSAUTH", ".login.microsoftonline.com"),  # the SSO provider
        _cookie("idp", "sso.school.edu"),  # a sibling host
        _cookie("child", "files.canvas.school.edu"),  # a child host
    ]

    jar = cs.shape_cookie_jar(cookies, ["canvas.school.edu"])

    assert [c["name"] for c in jar["cookies"]] == ["canvas_session", "_csrf_token", "campus"]
    assert set(jar["cookies"][0]) == set(cs.COOKIE_FIELDS)


def test_expires_hint_is_the_earliest_declared_expiry():
    jar = {
        "cookies": [
            _cookie("a", "c.edu", expires=1_900_000_000),
            _cookie("b", "c.edu", expires=1_800_000_000.5),
            _cookie("session", "c.edu"),  # -1: a session cookie, no expiry
        ]
    }
    assert cs.expires_hint(jar) == "2027-01-15T08:00:00.500000+00:00"
    assert cs.expires_hint({"cookies": [_cookie("s", "c.edu")]}) is None


# =============================================================================
# The PUT
# =============================================================================


def test_build_put_shapes_the_request():
    jar = {"cookies": [_cookie("s", "c.edu")]}
    url, headers, body = cs.build_put(
        "https://legion.example.com/",
        "tok",
        "canvas",
        jar,
        config={"base_url": "https://c.edu"},
        expires="2027-01-01T00:00:00+00:00",
    )
    assert url == "https://legion.example.com/api/ingest/sessions/canvas"
    assert headers["Authorization"] == "Token tok"
    assert body == {
        "secret": jar,
        "config": {"base_url": "https://c.edu"},
        "expires_hint": "2027-01-01T00:00:00+00:00",
    }


def test_build_put_refuses_bofa_before_anything_is_built():
    with pytest.raises(cs.ConnectError, match="never sent to the server"):
        cs.build_put("https://s", "tok", "bofa", {"cookies": []})

    sent = []
    with pytest.raises(cs.ConnectError):
        cs.put_session(
            "https://s", "tok", "bofa", {"cookies": []}, http_put=lambda *a, **k: sent.append(a)
        )
    assert sent == []


class _Response:
    def __init__(self, status_code, payload):
        self.status_code = status_code
        self._payload = payload

    def json(self):
        return self._payload


def test_put_session_returns_the_metadata_and_raises_on_a_refusal():
    calls = []

    def ok(url, json, headers, timeout):
        calls.append((url, json, headers))
        return _Response(200, {"source": "canvas", "kind": "cookie_jar", "captured_at": "t"})

    metadata = cs.put_session("https://s", "tok", "canvas", {"cookies": []}, http_put=ok)
    assert metadata["kind"] == "cookie_jar"
    assert calls[0][0] == "https://s/api/ingest/sessions/canvas"
    assert cs.describe_stored(metadata) == "Stored: canvas (cookie_jar), captured t."

    def refused(url, json, headers, timeout):
        return _Response(403, {"detail": "Only an owner of this household may hand over"})

    with pytest.raises(cs.ConnectError, match=r"HTTP 403\): Only an owner"):
        cs.put_session("https://s", "tok", "canvas", {"cookies": []}, http_put=refused)


# =============================================================================
# Drive
# =============================================================================


def test_drive_secret_needs_a_refresh_token():
    with pytest.raises(cs.ConnectError, match="no refresh token"):
        cs.drive_secret(SimpleNamespace(refresh_token=None), "id", "secret")

    secret = cs.drive_secret(
        SimpleNamespace(refresh_token="1//r", token_uri=None, scopes=None), "id", "secret"
    )
    assert secret == {
        "refresh_token": "1//r",
        "client_id": "id",
        "client_secret": "secret",
        "token_uri": cs.GOOGLE_TOKEN_URI,
        "scopes": list(cs.DRIVE_SCOPES),
    }


def test_the_drive_client_config_is_an_installed_app():
    config = cs.drive_client_config("id", "secret")
    assert set(config) == {"installed"}
    assert config["installed"]["client_id"] == "id"


def test_the_production_warning_names_the_seven_days():
    assert "In production" in cs.PRODUCTION_WARNING
    assert "7 days" in cs.PRODUCTION_WARNING


def test_main_prints_a_refusal_and_exits_1(monkeypatch, capsys):
    def fails(args):
        raise cs.ConnectError("No login seen within 1 seconds. Nothing was sent.")

    monkeypatch.setitem(cs.RUNNERS, "webassign", fails)

    assert cs.main(["webassign", *SERVER]) == 1
    assert "Nothing was sent" in capsys.readouterr().err


# =============================================================================
# The script's payload against the real route
# =============================================================================


@pytest.mark.django_db
@pytest.mark.parametrize("source", ["canvas", "drive"])
def test_the_scripts_body_is_accepted_by_the_server(source, token_a, monkeypatch):
    from ingest import vault

    monkeypatch.setenv(vault.VAULT_KEY_ENV, Fernet.generate_key().decode())
    if source == "canvas":
        jar = cs.shape_cookie_jar(
            [_cookie("canvas_session", "canvas.school.edu", expires=1_900_000_000)],
            ["canvas.school.edu"],
        )
        secret = jar
        config = {"base_url": "https://canvas.school.edu"}
        expires = cs.expires_hint(jar)
    else:
        secret = cs.drive_secret(
            SimpleNamespace(refresh_token="1//r", token_uri=None, scopes=None), "id", "s"
        )
        config, expires = {"scopes": secret["scopes"]}, None

    url, _headers, body = cs.build_put(
        "http://testserver", "unused", source, secret, config=config, expires=expires
    )
    response = token_a.put(url.replace("http://testserver", ""), body, format="json")

    assert response.status_code == 200, response.data
    assert response.data["source"] == source
    assert response.data["config"] == config


def test_drive_backup_switch_is_sent_only_when_given():
    """backend-etl ticket 03: the server merges config, so leaving the flag
    out keeps the last setting rather than switching backups off."""
    scopes = list(cs.DRIVE_SCOPES)
    assert cs.drive_config(scopes, None) == {"scopes": scopes}
    assert cs.drive_config(scopes, True) == {"scopes": scopes, "backup": True}
    assert cs.drive_config(scopes, False) == {"scopes": scopes, "backup": False}
    args = cs.parse_args(["drive", *SERVER, "--client-id", "i", "--client-secret", "s", "--backup"])
    assert args.backup is True
    args = cs.parse_args(["drive", *SERVER, "--client-id", "i", "--client-secret", "s"])
    assert args.backup is None
