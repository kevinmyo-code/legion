"""`legion.settings._parse_database_url` (engine runtime upgrade, architecture review
2026-11-10 item 3): the URL's TLS parameters reach psycopg, and the connection is
kept per thread with a health check. Made-up hosts only."""
from __future__ import annotations

import pytest
from django.conf import settings

from legion.settings import _parse_database_url


def test_sslmode_in_the_url_reaches_the_connection_options():
    parsed = _parse_database_url("postgres://u:p@db.example.test:5432/app?sslmode=require")
    assert parsed["OPTIONS"] == {"sslmode": "require"}
    assert parsed["HOST"] == "db.example.test"
    assert parsed["NAME"] == "app"


def test_a_url_without_a_query_has_no_extra_options():
    assert _parse_database_url("postgres://u:p@db.example.test/app")["OPTIONS"] == {}


def test_unknown_query_keys_are_ignored_and_options_is_refused():
    parsed = _parse_database_url("postgres://u:p@h.test/app?sslmode=verify-full&pgbouncer=true")
    assert parsed["OPTIONS"] == {"sslmode": "verify-full"}
    with pytest.raises(RuntimeError, match="options"):
        _parse_database_url("postgres://u:p@h.test/app?options=-c%20search_path%3Dpublic")


def test_default_connection_keeps_search_path_and_is_persistent_with_health_checks():
    default = settings.DATABASES["default"]
    assert default["OPTIONS"]["options"] == "-c search_path=django,public"
    assert default["CONN_MAX_AGE"] == settings.CONN_MAX_AGE
    assert default["CONN_HEALTH_CHECKS"] is (settings.CONN_MAX_AGE > 0)
