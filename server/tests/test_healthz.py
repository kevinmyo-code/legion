"""`GET /healthz` shape. Real network/db calls, so it runs against the
compose Postgres like everything else in this suite."""
from __future__ import annotations

import pytest
from rest_framework.test import APIClient

pytestmark = pytest.mark.django_db


def test_healthz_reports_db_ok():
    client = APIClient()
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.data == {"db": "ok"}


def test_healthz_error_never_echoes_the_database_message(monkeypatch, caplog):
    """Architecture review 2026-11-10: a connection error's text can name the host and
    user. The public body names the class only; the message goes to the log."""
    from django.db import connection
    from django.db.utils import OperationalError

    leak = 'connection to server at "db.example.test" failed: password for user "someone"'

    def broken_cursor(*args, **kwargs):
        raise OperationalError(leak)

    monkeypatch.setattr(connection, "cursor", broken_cursor)
    response = APIClient().get("/healthz")
    assert response.status_code == 503
    assert response.data["db"] == "error"
    assert "db.example.test" not in str(response.data)
    assert "someone" not in str(response.data)
    assert "OperationalError" in response.data["detail"]
    assert leak in caplog.text
