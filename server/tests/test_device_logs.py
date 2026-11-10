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

from devicelogs.models import (
    MAX_UPLOAD_BYTES,
    NAV_AUDIT_RETENTION_DAYS,
    RETENTION_DAYS,
    DeviceLog,
    retention_days,
)

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
    body["file"] = SimpleUploadedFile(
        "log.gz", b"plain text, not gzip", content_type="application/gzip"
    )
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


def test_an_upload_prunes_this_households_old_batches_only(
    token_a, token_b, household_a, household_b
):
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


_NAV = (
    "11-06 08:00:00.000  101  202 I NAV_AUDIT: search id=s1 origin=voice verdict=resolved"
    " query=\"a cafe\"\n"
    "11-06 08:00:01.000  101  202 I LIVE_AUDIT: transcript_in text=\"take me to a cafe\"\n"
    "11-06 08:00:02.000  101  202 I NAV_AUDIT: trip_end trip=t1 reason=arrived\n"
)


def _aged(household, text: str, days: int, nav: bool, reason: str = "conversation") -> DeviceLog:
    raw = text.encode()
    row = DeviceLog.objects.create(
        household=household, batch_id=uuid.uuid4(), device_label="Phone", reason=reason,
        raw_bytes=len(raw), line_count=raw.count(b"\n"), body_gz=gzip.compress(raw),
        nav_audit_lines=2 if nav else 0,
    )
    DeviceLog.objects.filter(pk=row.pk).update(
        received_at=timezone.now() - datetime.timedelta(days=days)
    )
    return row


def test_an_upload_counts_its_nav_audit_lines(token_a):
    assert (
        token_a.post(URL, _payload(_NAV, reason="conversation"), format="multipart").status_code
        == 201
    )
    row = DeviceLog.objects.get()
    assert (row.line_count, row.nav_audit_lines) == (3, 2)


def test_nav_audit_is_kept_ninety_days_and_the_rest_of_its_batch_goes_at_sixty(
    token_a, household_a
):
    """Nav-quality decision D7 (Kevin, 2026-11-06): NAV_AUDIT 90 days, conversation lines
    still 60."""
    assert NAV_AUDIT_RETENTION_DAYS == 90
    young_nav = _aged(household_a, _NAV, RETENTION_DAYS - 1, nav=True)
    old_nav = _aged(household_a, _NAV, RETENTION_DAYS + 1, nav=True)
    expired_nav = _aged(household_a, _NAV, NAV_AUDIT_RETENTION_DAYS + 1, nav=True)
    old_plain = _aged(
        household_a, "11-06 08:00:00.000  1  2 I LIVE_AUDIT: x\n", RETENTION_DAYS + 1, nav=False
    )

    assert token_a.post(URL, _payload(), format="multipart").status_code == 201

    assert not DeviceLog.objects.filter(pk__in=[expired_nav.pk, old_plain.pk]).exists()
    young_nav.refresh_from_db()
    assert gzip.decompress(bytes(young_nav.body_gz)).decode() == _NAV, "under 60 days, untouched"
    old_nav.refresh_from_db()
    body = gzip.decompress(bytes(old_nav.body_gz)).decode()
    assert "LIVE_AUDIT" not in body and "take me to" not in body, (
        "the conversation lines went at 60"
    )
    assert body.count("NAV_AUDIT") == 2
    assert (old_nav.line_count, old_nav.nav_audit_lines, old_nav.raw_bytes) == (
        2,
        2,
        len(body.encode()),
    )

    # A second upload leaves the cut row alone: cut once.
    cut_bytes = bytes(old_nav.body_gz)
    assert token_a.post(URL, _payload(), format="multipart").status_code == 201
    old_nav.refresh_from_db()
    assert bytes(old_nav.body_gz) == cut_bytes


def test_a_logcat_batch_with_nav_audit_is_cut_at_thirty_days_and_gone_at_ninety(
    token_a, household_a
):
    """Round 4 merge of nav-audit (D7) with per-reason retention (logcat 30): a raw logcat batch
    holding NAV_AUDIT lines is cut to them at its own 30 days, not deleted; it goes at 90."""
    assert retention_days("logcat") == 30
    young = _aged(household_a, _NAV, 29, nav=True, reason="logcat")
    cut = _aged(household_a, _NAV, 31, nav=True, reason="logcat")
    gone = _aged(household_a, _NAV, NAV_AUDIT_RETENTION_DAYS + 1, nav=True, reason="logcat")
    plain = _aged(
        household_a, "11-06 08:00:00.000  1  2 I Tag: x\n", 31, nav=False, reason="logcat"
    )
    conv = _aged(household_a, _NAV, 31, nav=True)

    assert token_a.post(URL, _payload(), format="multipart").status_code == 201

    assert not DeviceLog.objects.filter(pk__in=[gone.pk, plain.pk]).exists()
    for row in (young, conv):
        row.refresh_from_db()
        assert gzip.decompress(bytes(row.body_gz)).decode() == _NAV, "inside its reason's retention"
    cut.refresh_from_db()
    body = gzip.decompress(bytes(cut.body_gz)).decode()
    assert "LIVE_AUDIT" not in body and body.count("NAV_AUDIT") == 2
    assert (cut.line_count, cut.nav_audit_lines) == (2, 2)


def test_the_cap_constant_is_five_megabytes():
    assert MAX_UPLOAD_BYTES == 5 * 1024 * 1024
