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
