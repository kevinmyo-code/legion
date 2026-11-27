"""`GET /api/device-logs/conversations/` (conversation-records ticket 05, #160).

The household's own conversation batches, rendered, newest first, grouped by device: the page
that replaces `manage.py device_logs --reason conversation --render` for a person at a browser.
Scoping is the list route's: the caller's own rows in their household, never a head unit's
upload, never a recruiter visit's record.
"""

from __future__ import annotations

import datetime
import gzip
import uuid

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.utils import timezone

from devicelogs.models import DeviceLog
from tests.test_device_logs_render import FIXTURE, TIMELINE

pytestmark = pytest.mark.django_db

UPLOAD = "/api/device-logs/"
URL = "/api/device-logs/conversations/"


def _upload(client, text: str = FIXTURE, reason: str = "conversation", label: str = "Phone"):
    response = client.post(
        UPLOAD,
        {
            "batch_id": str(uuid.uuid4()),
            "device_label": label,
            "app_version_name": "1.0",
            "app_version_code": 7,
            "reason": reason,
            "run_id": "run-1",
            "file": SimpleUploadedFile(
                "c.log.gz", gzip.compress(text.encode()), content_type="application/gzip"
            ),
        },
        format="multipart",
    )
    assert response.status_code == 201, response.data
    return str(response.data["id"])


def _age(log_id: str, minutes: int) -> None:
    DeviceLog.objects.filter(id=log_id).update(
        received_at=timezone.now() - datetime.timedelta(minutes=minutes)
    )


def test_unauthenticated_is_refused(db):
    from rest_framework.test import APIClient

    assert APIClient().get(URL).status_code == 401


def test_no_conversations_is_an_empty_list_not_an_error(token_a):
    response = token_a.get(URL)
    assert response.status_code == 200
    assert response.data == {"devices": []}


def test_batches_are_grouped_per_device_newest_first_and_rendered(token_a):
    old_phone = _upload(token_a, label="Phone")
    new_phone = _upload(token_a, label="Phone")
    web = _upload(token_a, label="web")
    _age(old_phone, 30)
    _age(new_phone, 20)
    _age(web, 10)

    devices = token_a.get(URL).data["devices"]
    # The device with the newest batch comes first.
    assert [d["device_label"] for d in devices] == ["web", "Phone"]
    phone = devices[1]["batches"]
    assert [str(b["id"]) for b in phone] == [new_phone, old_phone]
    first = phone[0]
    assert first["readable"] is True and first["problem"] is None
    assert first["lines"] == TIMELINE
    assert first["header"].startswith(f"=== batch {new_phone} ")
    assert first["run_id"] == "run-1"
    assert "body_gz" not in first and "household" not in first


def test_only_conversation_batches_are_shown(token_a):
    _upload(token_a, "a drive log line\n", reason="drive_end", label="Head unit")
    _upload(token_a, "plain logcat line\n", reason="logcat")
    kept = _upload(token_a)
    devices = token_a.get(URL).data["devices"]
    assert [str(b["id"]) for d in devices for b in d["batches"]] == [kept]


def test_limit_applies_per_device_and_is_bounded(token_a):
    ids = [_upload(token_a, label="Phone") for _ in range(3)]
    for age, log_id in enumerate(ids):
        _age(log_id, age + 1)
    other = _upload(token_a, label="web")
    _age(other, 50)

    devices = {d["device_label"]: d for d in token_a.get(URL, {"limit": 2}).data["devices"]}
    assert [str(b["id"]) for b in devices["Phone"]["batches"]] == [ids[0], ids[1]]
    assert len(devices["web"]["batches"]) == 1
    assert devices["Phone"]["total"] == 3 and devices["web"]["total"] == 1

    assert token_a.get(URL, {"limit": "lots"}).status_code == 400
    # Over the cap is clamped, not refused.
    assert token_a.get(URL, {"limit": 9999}).status_code == 200


def test_device_filter_keeps_one_device(token_a):
    _upload(token_a, label="Phone")
    web = _upload(token_a, label="web")
    devices = token_a.get(URL, {"device": "WEB"}).data["devices"]
    assert [str(b["id"]) for d in devices for b in d["batches"]] == [web]


def test_mail_stays_redacted(token_a):
    from tests.test_device_logs_records import MAIL_SECRET, REDACTED_BATCH

    _upload(token_a, REDACTED_BATCH)
    batch = token_a.get(URL).data["devices"][0]["batches"][0]
    joined = "\n".join(batch["lines"])
    assert "ASSISTANT: [redacted]" in joined
    assert MAIL_SECRET not in joined


def test_a_batch_with_no_audit_lines_says_so(token_a):
    _upload(token_a, "nothing a conversation wrote\n")
    batch = token_a.get(URL).data["devices"][0]["batches"][0]
    assert batch["readable"] is True
    assert batch["lines"] == ["(no LIVE_AUDIT or LIVE_LATENCY lines in this batch)"]


def test_an_unreadable_batch_is_named_not_dropped(token_a, household_a, household_user):
    row = DeviceLog.objects.create(
        household=household_a,
        user=household_user,
        batch_id=uuid.uuid4(),
        device_label="Phone",
        reason="conversation",
        raw_bytes=4,
        body_gz=b"nope",
    )
    good = _upload(token_a)
    batches = token_a.get(URL).data["devices"][0]["batches"]
    by_id = {str(b["id"]): b for b in batches}
    assert by_id[good]["readable"] is True
    bad = by_id[str(row.id)]
    assert bad["readable"] is False and bad["lines"] == []
    assert "cannot be read" in bad["problem"]


def test_another_household_and_another_member_see_nothing(token_a, token_b, household_a):
    from tests.conftest import _client_for, _member

    _upload(token_a)
    assert token_b.get(URL).data == {"devices": []}
    mia = _client_for(_member(household_a, "mia@example.com"))
    assert mia.get(URL).data == {"devices": []}


def test_a_recruiter_visits_record_is_never_listed(token_a):
    from tests.test_device_logs_records import _visit_row

    _visit_row(FIXTURE)
    assert token_a.get(URL).data == {"devices": []}
