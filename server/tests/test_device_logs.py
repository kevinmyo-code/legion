"""`POST /api/device-logs/` and `manage.py device_logs` (Kevin, 2026-10-15).

Auth, household scoping, the gzip round trip, both size caps, idempotent retry,
retention, and the read command. The cross-household leak test lives in
`tests/test_tenancy.py` beside the other named ones.
"""

from __future__ import annotations

import datetime
import gzip
import os
import uuid

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.management import call_command
from django.utils import timezone
from rest_framework.test import APIClient

from devicelogs.models import MAX_UPLOAD_BYTES, RETENTION_DAYS, DeviceLog

pytestmark = pytest.mark.django_db

URL = "/api/device-logs/"


def _payload(text: str | bytes = "10-15 08:00:00.000 I/Tag(1): hello\n", **overrides):
    raw = text.encode() if isinstance(text, str) else text
    body = {
        "batch_id": str(uuid.uuid4()),
        "device_label": "Head unit",
        "app_version_name": "1.2.3",
        "app_version_code": 42,
        "reason": "drive_end",
        "window_start_ms": 1_760_000_000_000,
        "window_end_ms": 1_760_000_060_000,
        "file": SimpleUploadedFile("log.gz", gzip.compress(raw), content_type="application/gzip"),
    }
    body.update(overrides)
    return body


def test_an_unauthenticated_upload_is_refused_and_stores_nothing(db):
    response = APIClient().post(URL, _payload(), format="multipart")
    assert response.status_code == 401
    assert DeviceLog.objects.count() == 0


def test_a_read_scoped_token_cannot_upload(household_user):
    from household.models import DeviceToken

    _row, raw_key = DeviceToken.issue(household_user, "reader", scope=DeviceToken.SCOPE_READ)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")
    assert client.post(URL, _payload(), format="multipart").status_code == 403
    assert DeviceLog.objects.count() == 0


def test_the_gzip_round_trip_through_the_read_command(token_a, household_a, capsys):
    text = "10-15 08:00:00.000 I/Tag(1): first\n10-15 08:00:01.000 W/Tag(1): second\n"
    response = token_a.post(URL, _payload(text), format="multipart")
    assert response.status_code == 201, response.data
    assert "household" not in response.data
    row = DeviceLog.objects.get()
    assert row.household_id == household_a.id
    assert row.line_count == 2
    assert row.raw_bytes == len(text.encode())
    assert gzip.decompress(bytes(row.body_gz)).decode() == text
    assert row.window_start == datetime.datetime.fromtimestamp(1_760_000_000, tz=datetime.UTC)

    call_command("device_logs", "--last", "1")
    out = capsys.readouterr().out
    assert "I/Tag(1): first" in out and "W/Tag(1): second" in out
    assert "drive_end" in out and "v1.2.3(42)" in out


def test_the_read_command_greps_lines(token_a, capsys):
    token_a.post(URL, _payload("keep spotify here\ndrop this\n"), format="multipart")
    call_command("device_logs", "--last", "5", "--grep", "SPOTIFY")
    out = capsys.readouterr().out
    assert "keep spotify here" in out and "drop this" not in out


def test_a_retry_of_the_same_batch_is_answered_once_and_stores_one_row(token_a):
    first = _payload()
    again = _payload(batch_id=first["batch_id"])
    assert token_a.post(URL, first, format="multipart").status_code == 201
    second = token_a.post(URL, again, format="multipart")
    assert second.status_code == 200
    assert "nothing new" in second.data["detail"]
    assert DeviceLog.objects.count() == 1


def test_an_upload_over_the_compressed_cap_is_refused_413(token_a, monkeypatch):
    monkeypatch.setattr("devicelogs.views.MAX_UPLOAD_BYTES", 1024)
    noise = os.urandom(4096)  # incompressible, so it exceeds 1 KiB gzipped
    response = token_a.post(URL, _payload(noise), format="multipart")
    assert response.status_code == 413
    assert "Nothing was stored" in response.data["detail"]
    assert DeviceLog.objects.count() == 0


def test_a_gzip_bomb_is_refused_on_decompressed_size(token_a, monkeypatch):
    monkeypatch.setattr("devicelogs.views.MAX_UPLOAD_BYTES", 64 * 1024)
    bomb = b"A" * (256 * 1024)  # compresses to a few hundred bytes
    assert len(gzip.compress(bomb)) < 1024
    response = token_a.post(URL, _payload(bomb), format="multipart")
    assert response.status_code == 413
    assert DeviceLog.objects.count() == 0


def test_a_file_that_is_not_gzip_is_refused_400(token_a):
    body = _payload()
    body["file"] = SimpleUploadedFile("log.gz", b"plain text, not gzip", content_type="application/gzip")
    response = token_a.post(URL, body, format="multipart")
    assert response.status_code == 400
    assert "not valid gzip" in response.data["detail"]
    assert DeviceLog.objects.count() == 0


def test_an_unknown_reason_is_refused_400(token_a):
    response = token_a.post(URL, _payload(reason="whenever"), format="multipart")
    assert response.status_code == 400
    assert DeviceLog.objects.count() == 0


def test_a_periodic_upload_is_accepted(token_a):
    response = token_a.post(URL, _payload(reason="periodic"), format="multipart")
    assert response.status_code == 201, response.data
    assert DeviceLog.objects.get().reason == "periodic"


def test_a_conversation_upload_is_accepted(token_a):
    response = token_a.post(URL, _payload(reason="conversation"), format="multipart")
    assert response.status_code == 201, response.data
    assert DeviceLog.objects.get().reason == "conversation"


def test_an_upload_prunes_this_households_old_batches_only(token_a, token_b, household_a, household_b):
    old = timezone.now() - datetime.timedelta(days=RETENTION_DAYS + 1)
    stale_a = DeviceLog.objects.create(
        household=household_a, batch_id=uuid.uuid4(), device_label="x", reason="manual",
        raw_bytes=1, body_gz=gzip.compress(b"x"),
    )
    stale_b = DeviceLog.objects.create(
        household=household_b, batch_id=uuid.uuid4(), device_label="x", reason="manual",
        raw_bytes=1, body_gz=gzip.compress(b"x"),
    )
    DeviceLog.objects.filter(pk__in=[stale_a.pk, stale_b.pk]).update(received_at=old)
    assert token_a.post(URL, _payload(), format="multipart").status_code == 201
    assert not DeviceLog.objects.filter(pk=stale_a.pk).exists()
    # B's own retention runs when B uploads, never as a side effect of A's.
    assert DeviceLog.objects.filter(pk=stale_b.pk).exists()


def test_the_cap_constant_is_five_megabytes():
    assert MAX_UPLOAD_BYTES == 5 * 1024 * 1024
