"""Structured logs for Cloud Logging, errors for Cloud Error Reporting, a request id per request.

Architecture review 2026-11-10, item 4. The engine had no `LOGGING` config: eight
`getLogger` calls fell through to Python's last-resort handler, as plain text, with no
severity Cloud Logging could read and nothing Error Reporting would pick up.

**What a line looks like.** One JSON object per line. Cloud Logging lifts these keys out
of a JSON line written by a Cloud Run container on its own (no client library, no
agent): `severity`, `message`, `logging.googleapis.com/trace`. Everything else lands in
`jsonPayload`. Lines below ERROR go to stdout, ERROR and above to stderr.

**Errors.** A record at ERROR or above that carries an exception (Django's
`django.request` logger writes every unhandled 5xx this way) is shaped as a
`ReportedErrorEvent`: `@type` set to Error Reporting's type, the formatted stack trace
in `message`, and `serviceContext` naming the Cloud Run service and revision. Error
Reporting reads that from structured stderr for free: no Sentry, no account, no key.

**What never goes in.** Request headers, bodies, query strings, cookies, tokens. The
only request facts carried are the method, the path, the request id and the trace id.
A log line is readable by anyone with project log access, and the paths and bodies of
this engine are a household's personal data.

**The request id.** `RequestIdMiddleware` takes `X-Request-ID` from the caller when it
is a short token of safe characters (the Cloudflare worker or a phone may set one),
else mints one, keeps it for this request's log lines, and returns it on the response
so a person reading an error screen can quote it.
"""

from __future__ import annotations

import contextvars
import json
import logging
import os
import re
import traceback
import uuid
from datetime import UTC, datetime

ERROR_EVENT_TYPE = (
    "type.googleapis.com/google.devtools.clouderrorreporting.v1beta1.ReportedErrorEvent"
)
TRACE_KEY = "logging.googleapis.com/trace"
SPAN_KEY = "logging.googleapis.com/spanId"
REQUEST_ID_HEADER = "X-Request-ID"

_SAFE_ID = re.compile(r"^[A-Za-z0-9._-]{8,64}$")
# `X-Cloud-Trace-Context: TRACE_ID/SPAN_ID;o=TRACE_TRUE` (Cloud Run sets it on every request).
_TRACE_CONTEXT = re.compile(r"^(?P<trace>[0-9a-fA-F]{32})(?:/(?P<span>\d+))?")

_request: contextvars.ContextVar[dict | None] = contextvars.ContextVar(
    "legion_request_log_context", default=None
)

# Record attributes every LogRecord has; anything else a caller passed via `extra=` is kept.
_STANDARD_ATTRS = frozenset(
    vars(logging.LogRecord("", 0, "", 0, "", None, None)).keys()
) | {"message", "asctime", "request"}


def current_request_id() -> str | None:
    context = _request.get()
    return context.get("request_id") if context else None


def _trace_fields(header: str | None) -> dict:
    project = os.environ.get("GOOGLE_CLOUD_PROJECT", "").strip()
    match = _TRACE_CONTEXT.match(header or "")
    if not project or not match:
        return {}
    fields = {TRACE_KEY: f"projects/{project}/traces/{match.group('trace')}"}
    if match.group("span"):
        fields[SPAN_KEY] = match.group("span")
    return fields


def _service_context() -> dict:
    return {
        "service": os.environ.get("K_SERVICE") or os.environ.get("CLOUD_RUN_JOB") or "legion",
        "version": os.environ.get("K_REVISION") or os.environ.get("CLOUD_RUN_EXECUTION") or "local",
    }


class JsonFormatter(logging.Formatter):
    """One Cloud Logging JSON object per record."""

    def format(self, record: logging.LogRecord) -> str:
        message = record.getMessage()
        entry: dict = {
            "severity": record.levelname if record.levelname != "WARN" else "WARNING",
            "time": datetime.fromtimestamp(record.created, UTC).isoformat(),
            "logger": record.name,
        }
        context = _request.get()
        if context:
            entry.update(context.get("trace") or {})
            entry["request_id"] = context["request_id"]
            entry["http"] = {"method": context["method"], "path": context["path"]}
        if record.exc_info and record.levelno >= logging.ERROR:
            # Error Reporting groups on the stack trace, which must be IN `message`.
            stack = "".join(traceback.format_exception(*record.exc_info))
            entry["message"] = f"{message}\n{stack}"
            entry["@type"] = ERROR_EVENT_TYPE
            entry["serviceContext"] = _service_context()
        elif record.exc_info:
            entry["message"] = f"{message}\n{self.formatException(record.exc_info)}"
        else:
            entry["message"] = message
        if record.levelno >= logging.ERROR and "@type" not in entry:
            # An ERROR with no exception still reaches Error Reporting, with where it was logged.
            entry["@type"] = ERROR_EVENT_TYPE
            entry["serviceContext"] = _service_context()
            entry["context"] = {
                "reportLocation": {
                    "filePath": record.pathname,
                    "lineNumber": record.lineno,
                    "functionName": record.funcName,
                }
            }
        for key, value in vars(record).items():
            if key not in _STANDARD_ATTRS and not key.startswith("_") and key not in entry:
                entry[key] = value
        return json.dumps(entry, default=str, ensure_ascii=False)


class BelowError(logging.Filter):
    """stdout takes everything under ERROR; stderr's handler is levelled at ERROR."""

    def filter(self, record: logging.LogRecord) -> bool:
        return record.levelno < logging.ERROR


class RequestIdMiddleware:
    """First in MIDDLEWARE: every log line of this request carries its id and trace."""

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        offered = request.headers.get(REQUEST_ID_HEADER, "")
        request_id = offered if _SAFE_ID.match(offered) else uuid.uuid4().hex
        request.request_id = request_id
        token = _request.set(
            {
                "request_id": request_id,
                "method": request.method,
                "path": request.path,
                "trace": _trace_fields(request.headers.get("X-Cloud-Trace-Context")),
            }
        )
        try:
            response = self.get_response(request)
        finally:
            _request.reset(token)
        response[REQUEST_ID_HEADER] = request_id
        return response


def logging_config(level: str = "INFO") -> dict:
    """The `LOGGING` dict settings.py installs."""
    return {
        "version": 1,
        "disable_existing_loggers": False,
        "filters": {"below_error": {"()": BelowError}},
        "formatters": {"json": {"()": JsonFormatter}},
        "handlers": {
            "stdout": {
                "class": "logging.StreamHandler",
                "stream": "ext://sys.stdout",
                "formatter": "json",
                "filters": ["below_error"],
            },
            "stderr": {
                "class": "logging.StreamHandler",
                "stream": "ext://sys.stderr",
                "formatter": "json",
                "level": "ERROR",
            },
        },
        "root": {"handlers": ["stdout", "stderr"], "level": level},
        "loggers": {
            # Django's own loggers propagate to root; `django.request` is where unhandled
            # 5xx land with their exception, which is what Error Reporting groups.
            "django": {"level": level, "propagate": True},
            # A 4xx is a caller's mistake, logged by django.request at WARNING: kept, but
            # never an error event.
            "django.server": {"level": "WARNING", "propagate": True},
        },
    }
