"""`legion/log.py` (architecture review 2026-11-10, item 4): JSON lines for Cloud Logging,
errors in the Error Reporting shape, a request id on every response. Made-up values only."""
from __future__ import annotations

import json
import logging

import pytest
from rest_framework.test import APIClient

from legion.log import ERROR_EVENT_TYPE, TRACE_KEY, JsonFormatter, RequestIdMiddleware

pytestmark = pytest.mark.django_db


def _record(level, msg, exc_info=None):
    return logging.LogRecord("legion.test", level, __file__, 10, msg, None, exc_info)


def test_info_line_is_json_with_severity_and_message():
    entry = json.loads(JsonFormatter().format(_record(logging.INFO, "hello")))
    assert entry["severity"] == "INFO"
    assert entry["message"] == "hello"
    assert "@type" not in entry


def test_an_exception_at_error_is_a_reported_error_event_with_the_stack_in_message(monkeypatch):
    monkeypatch.setenv("K_SERVICE", "legion")
    monkeypatch.setenv("K_REVISION", "legion-00001-abc")
    try:
        raise ValueError("boom")
    except ValueError:
        import sys

        entry = json.loads(JsonFormatter().format(_record(logging.ERROR, "failed", sys.exc_info())))
    assert entry["severity"] == "ERROR"
    assert entry["@type"] == ERROR_EVENT_TYPE
    assert "Traceback" in entry["message"] and "ValueError: boom" in entry["message"]
    assert entry["serviceContext"] == {"service": "legion", "version": "legion-00001-abc"}


def test_middleware_sets_request_id_and_trace_on_lines_logged_inside_the_request(monkeypatch):
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "demo-project")
    seen = {}

    def view(request):
        seen["line"] = json.loads(JsonFormatter().format(_record(logging.WARNING, "inside")))
        from django.http import HttpResponse

        return HttpResponse("ok")

    from django.test import RequestFactory

    request = RequestFactory().get(
        "/x", HTTP_X_CLOUD_TRACE_CONTEXT="0123456789abcdef0123456789abcdef/42;o=1"
    )
    response = RequestIdMiddleware(view)(request)
    line = seen["line"]
    assert line[TRACE_KEY] == "projects/demo-project/traces/0123456789abcdef0123456789abcdef"
    assert line["request_id"] == response["X-Request-ID"]
    assert line["http"] == {"method": "GET", "path": "/x"}


def test_a_safe_incoming_request_id_is_kept_and_an_unsafe_one_replaced():
    client = APIClient()
    kept = client.get("/healthz", HTTP_X_REQUEST_ID="edge-1234abcd")
    assert kept["X-Request-ID"] == "edge-1234abcd"
    replaced = client.get("/healthz", HTTP_X_REQUEST_ID="<script>")
    assert replaced["X-Request-ID"] != "<script>"
    assert len(replaced["X-Request-ID"]) == 32


def test_no_trace_field_without_a_project(monkeypatch):
    monkeypatch.delenv("GOOGLE_CLOUD_PROJECT", raising=False)
    from legion.log import _trace_fields

    assert _trace_fields("0123456789abcdef0123456789abcdef/1;o=1") == {}
