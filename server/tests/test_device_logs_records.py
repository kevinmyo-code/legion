"""Conversation records the Claude worker can read (alfred-builds ticket 02).

`GET /api/device-logs/?reason=conversation&since=` and `GET /api/device-logs/<id>/render`:
household scoping, a recruiter visit's record unreadable by a member, mail redaction preserved,
and the render identical to `manage.py device_logs --render`.
"""

from __future__ import annotations

import datetime
import gzip
import uuid

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.management import call_command
from django.utils import timezone

from devicelogs.models import DeviceLog
from tests.test_device_logs_render import FIXTURE, TIMELINE

pytestmark = pytest.mark.django_db

URL = "/api/device-logs/"

MAIL_SECRET = "Your order 112-998 ships Tuesday"
REDACTED_BATCH = """\
11-05 09:00:00.000 1 2 I LIVE_AUDIT: session_start model=gemini-3.1-flash-live-preview async_set=none
11-05 09:00:01.000 1 2 I LIVE_AUDIT: user final=true text="Read my mail"
11-05 09:00:01.100 1 2 I LIVE_AUDIT: tool_call id=m1 name=read_mail async=false args={}
11-05 09:00:01.400 1 2 I LIVE_AUDIT: tool_result id=m1 name=read_mail success=true ms=300 scheduling=none sent=true response="[redacted: third-party content]"
11-05 09:00:01.600 1 2 I LIVE_AUDIT: assistant text="[redacted: third-party content]"
11-05 09:00:02.000 1 2 I LIVE_AUDIT: turn_complete
"""  # noqa: E501 (pinned log lines)


def _upload(client, text: str, reason: str = "conversation", label: str = "Phone"):
    body = {
        "batch_id": str(uuid.uuid4()),
        "device_label": label,
        "app_version_name": "1.0",
        "app_version_code": 7,
        "reason": reason,
        "file": SimpleUploadedFile(
            "c.log.gz", gzip.compress(text.encode()), content_type="application/gzip"
        ),
    }
    response = client.post(URL, body, format="multipart")
    assert response.status_code == 201, response.data
    return str(response.data["id"])


def _visit_row(text: str) -> DeviceLog:
    raw = text.encode()
    return DeviceLog.objects.create(
        household=None,
        demo_visitor=True,
        visit_ref="acme",
        batch_id=uuid.uuid4(),
        device_label="web",
        reason="conversation",
        line_count=raw.count(b"\n"),
        raw_bytes=len(raw),
        body_gz=gzip.compress(raw),
    )


def test_unauthenticated_reads_are_refused(db):
    from rest_framework.test import APIClient

    assert APIClient().get(URL).status_code == 401
    assert APIClient().get(f"{URL}{uuid.uuid4()}/render").status_code == 401


def test_list_is_newest_first_and_filters_by_reason_since_and_device(token_a):
    drive = _upload(token_a, "a drive log line\n", reason="drive_end", label="Head unit")
    first = _upload(token_a, FIXTURE)
    second = _upload(token_a, FIXTURE, label="web")

    rows = token_a.get(URL, {"reason": "conversation"}).data["results"]
    assert [str(r["id"]) for r in rows] == [second, first]
    assert rows[0]["line_count"] == FIXTURE.count("\n")
    assert "body_gz" not in rows[0] and "household" not in rows[0]

    everything = token_a.get(URL).data["results"]
    assert drive in [str(r["id"]) for r in everything]

    web = token_a.get(URL, {"device": "WEB"}).data["results"]
    assert [str(r["id"]) for r in web] == [second]

    later = (timezone.now() + datetime.timedelta(minutes=5)).isoformat()
    assert token_a.get(URL, {"since": later}).data["results"] == []
    earlier = (timezone.now() - datetime.timedelta(minutes=5)).isoformat()
    assert len(token_a.get(URL, {"since": earlier, "reason": "conversation"}).data["results"]) == 2


def test_list_refuses_malformed_filters_in_words(token_a):
    response = token_a.get(URL, {"since": "yesterday"})
    assert response.status_code == 400 and "ISO 8601" in response.data["detail"]
    response = token_a.get(URL, {"reason": "gossip"})
    assert response.status_code == 400 and "reason must be" in response.data["detail"]
    assert token_a.get(URL, {"limit": "lots"}).status_code == 400


def test_render_matches_the_operator_command_byte_for_byte(token_a, capsys):
    log_id = _upload(token_a, FIXTURE)
    response = token_a.get(f"{URL}{log_id}/render")
    assert response.status_code == 200, response.data
    assert response.data["lines"] == TIMELINE

    call_command("device_logs", "--last", "1", "--reason", "conversation", "--render")
    printed = capsys.readouterr().out.splitlines()
    assert [response.data["header"], *response.data["lines"]] == printed


def test_render_keeps_mail_redacted(token_a):
    log_id = _upload(token_a, REDACTED_BATCH)
    lines = token_a.get(f"{URL}{log_id}/render").data["lines"]
    joined = "\n".join(lines)
    assert "TOOL <- read_mail ok 300ms [redacted]" in joined
    assert "ASSISTANT: [redacted]" in joined
    assert MAIL_SECRET not in joined


def test_another_households_records_are_invisible(token_a, token_b):
    log_id = _upload(token_a, FIXTURE)
    assert token_b.get(URL).data["results"] == []
    response = token_b.get(f"{URL}{log_id}/render")
    assert response.status_code == 404
    assert "household" in response.data["detail"]


def test_a_recruiter_visits_record_is_never_readable_by_a_member(token_a):
    visit = _visit_row(FIXTURE)
    assert token_a.get(URL).data["results"] == []
    assert token_a.get(f"{URL}{visit.id}/render").status_code == 404


def test_an_unreadable_body_is_said_in_words(token_a, household_a):
    row = DeviceLog.objects.create(
        household=household_a,
        batch_id=uuid.uuid4(),
        device_label="Phone",
        reason="conversation",
        raw_bytes=4,
        body_gz=b"nope",
    )
    response = token_a.get(f"{URL}{row.id}/render")
    assert response.status_code == 422
    assert "cannot be read" in response.data["detail"]
