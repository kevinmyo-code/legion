"""`POST /api/internal/tick/<task>` (architecture review 2026-11-10, item 12). The OIDC
check is faked at its one seam; made-up accounts only."""
from __future__ import annotations

import pytest
from django.core.management import call_command  # noqa: F401  (patched below)
from django.test import Client

from legion import tick

pytestmark = pytest.mark.django_db

SA = "scheduler@demo-project.iam.gserviceaccount.com"
AUD = "https://legion-demo.a.run.app/api/internal/tick"


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setenv("LEGION_SCHEDULER_SA", SA)
    monkeypatch.setenv("LEGION_TICK_AUDIENCE", AUD)


def _claims(email=SA, verified=True):
    return {"iss": "https://accounts.google.com", "email": email, "email_verified": verified}


def test_unconfigured_is_a_404(monkeypatch):
    monkeypatch.delenv("LEGION_SCHEDULER_SA", raising=False)
    monkeypatch.delenv("LEGION_TICK_AUDIENCE", raising=False)
    assert Client().post("/api/internal/tick/push_dispatch").status_code == 404


def test_unknown_task_is_a_404(configured):
    assert Client().post("/api/internal/tick/backup_nightly").status_code == 404


def test_get_is_refused(configured):
    assert Client().get("/api/internal/tick/push_dispatch").status_code == 405


def test_no_token_or_wrong_account_is_a_403_and_nothing_runs(configured, monkeypatch):
    ran = []
    monkeypatch.setattr(tick, "call_command", lambda *a, **k: ran.append(a))
    assert Client().post("/api/internal/tick/push_dispatch").status_code == 403
    monkeypatch.setattr(tick, "verify_google_id_token", lambda t, a: _claims(email="x@y.test"))
    response = Client().post(
        "/api/internal/tick/push_dispatch", HTTP_AUTHORIZATION="Bearer t"
    )
    assert response.status_code == 403
    monkeypatch.setattr(tick, "verify_google_id_token", lambda t, a: _claims(verified=False))
    assert (
        Client().post("/api/internal/tick/push_dispatch", HTTP_AUTHORIZATION="Bearer t").status_code
        == 403
    )
    assert ran == []


def test_a_bad_signature_is_a_403(configured, monkeypatch):
    def refuse(token, audience):
        raise ValueError("Token used too late")

    monkeypatch.setattr(tick, "verify_google_id_token", refuse)
    response = Client().post("/api/internal/tick/evaluate_watches", HTTP_AUTHORIZATION="Bearer t")
    assert response.status_code == 403


def test_the_scheduler_runs_the_real_command(configured, monkeypatch):
    seen = {}

    def verify(token, audience):
        seen["audience"] = audience
        return _claims()

    monkeypatch.setattr(tick, "verify_google_id_token", verify)
    response = Client().post("/api/internal/tick/evaluate_watches", HTTP_AUTHORIZATION="Bearer t")
    assert response.status_code == 200
    assert response.json() == {"task": "evaluate_watches", "ok": True}
    assert seen["audience"] == AUD


def test_push_dispatch_runs_with_push_off(configured, monkeypatch):
    monkeypatch.setattr(tick, "verify_google_id_token", lambda t, a: _claims())
    response = Client().post("/api/internal/tick/push_dispatch", HTTP_AUTHORIZATION="Bearer t")
    assert response.status_code == 200


def test_a_failing_command_is_a_500(configured, monkeypatch):
    monkeypatch.setattr(tick, "verify_google_id_token", lambda t, a: _claims())

    def boom(*args, **kwargs):
        raise RuntimeError("x")

    monkeypatch.setattr(tick, "call_command", boom)
    response = Client().post("/api/internal/tick/push_dispatch", HTTP_AUTHORIZATION="Bearer t")
    assert response.status_code == 500
    assert response.json()["ok"] is False
