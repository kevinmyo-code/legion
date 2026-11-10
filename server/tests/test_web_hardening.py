"""Web hardening (architecture review 2026-11-10, item 5): secure cookies, HSTS, nosniff,
DENY framing, and a CSP that lets the browser reach Gemini Live. Made-up content only."""
from __future__ import annotations

import pytest
from django.conf import settings
from django.http import HttpResponse
from django.test import RequestFactory
from rest_framework.test import APIClient

from legion import csp

pytestmark = pytest.mark.django_db


def test_cookie_and_transport_flags_with_debug_off():
    assert settings.DEBUG is False
    assert settings.SESSION_COOKIE_SECURE is True
    assert settings.CSRF_COOKIE_SECURE is True
    assert settings.SECURE_HSTS_SECONDS == 3600
    assert settings.SECURE_CONTENT_TYPE_NOSNIFF is True
    assert settings.X_FRAME_OPTIONS == "DENY"


def test_responses_carry_nosniff_deny_hsts_and_a_report_only_policy_by_default(monkeypatch):
    monkeypatch.delenv("LEGION_CSP", raising=False)
    response = APIClient().get("/healthz", secure=True)
    assert response["X-Content-Type-Options"] == "nosniff"
    assert response["X-Frame-Options"] == "DENY"
    assert response["Strict-Transport-Security"] == "max-age=3600"
    assert csp.GEMINI_LIVE in response[csp.REPORT_HEADER]
    assert csp.HEADER not in response


def test_on_enforces_and_off_sends_nothing(monkeypatch):
    monkeypatch.setenv("LEGION_CSP", "on")
    assert "frame-ancestors 'none'" in APIClient().get("/healthz")[csp.HEADER]
    monkeypatch.setenv("LEGION_CSP", "off")
    response = APIClient().get("/healthz")
    assert csp.HEADER not in response and csp.REPORT_HEADER not in response


def test_connect_src_allows_the_live_socket_and_plaid():
    connect = next(d for d in csp.policy().split("; ") if d.startswith("connect-src"))
    assert "wss://generativelanguage.googleapis.com" in connect
    assert "https://*.plaid.com" in connect
    assert "'unsafe-eval'" not in csp.policy()


def test_inline_scripts_are_allowed_by_hash_only(monkeypatch):
    monkeypatch.setenv("LEGION_CSP", "on")
    html = b"<html><script>var a=1;</script><script type='module' src='/x.js'></script></html>"

    def view(request):
        return HttpResponse(html, content_type="text/html; charset=utf-8")

    header = csp.ContentSecurityPolicyMiddleware(view)(RequestFactory().get("/"))[csp.HEADER]
    assert csp.inline_script_hashes(html)[0] in header
    assert len(csp.inline_script_hashes(html)) == 1
    assert "'unsafe-inline'" not in next(d for d in header.split("; ") if d.startswith("script-src"))


def test_a_response_with_its_own_policy_keeps_it(monkeypatch):
    monkeypatch.setenv("LEGION_CSP", "on")

    def view(request):
        response = HttpResponse("x", content_type="text/html")
        response[csp.HEADER] = "sandbox allow-scripts"
        return response

    response = csp.ContentSecurityPolicyMiddleware(view)(RequestFactory().get("/"))
    assert response[csp.HEADER] == "sandbox allow-scripts"


def test_the_built_index_theme_script_hash_is_in_the_policy(monkeypatch):
    """The real index.html's inline theme script is hashed from the source file."""
    from pathlib import Path

    html = (Path(settings.BASE_DIR) / "frontend" / "index.html").read_bytes()
    hashes = csp.inline_script_hashes(html)
    assert len(hashes) == 1
