"""The phone's own logs on the engine (2026-11-09, Kevin: "Send everything from the phone to the
engine so we can audit without adb").

The `logcat` and `crash` reasons, their decompressed cap, `run_id`, per-reason retention, and the
read command's window / tag / raw / b64 modes. `devicelogs/lines.py` is tested directly too: it is
what puts a phone's local-time stamps on UTC.
"""

from __future__ import annotations

import datetime
import gzip
import os
import uuid

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.management import call_command
from django.core.management.base import CommandError
from django.utils import timezone

from devicelogs import lines as loglines
from devicelogs.management.commands.device_logs import parse_when
from devicelogs.models import (
    MAX_RAW_BYTES_BY_REASON,
    REASON_CRASH,
    REASON_LOGCAT,
    RETENTION_DAYS,
    DeviceLog,
    retention_days,
)

pytestmark = pytest.mark.django_db

URL = "/api/device-logs/"
# 2026-11-09 20:00:00 UTC; the phone is on UTC-6 (Houston, CST), so its stamps read 14:00.
WINDOW_START_MS = 1_794_254_400_000
WINDOW_START = datetime.datetime(2026, 11, 9, 20, 0, tzinfo=datetime.UTC)

LOGCAT = (
    "11-09 14:00:00.000  4242  4242 I MidnightEvents: engine_poll[events] inserted=0\n"
    "11-09 14:00:05.000  4242  4300 I LIVE_AUDIT: session_start model=m async_set=none\n"
    '11-09 14:00:06.000  4242  4300 I LIVE_AUDIT: user final=true text="hello"\n'
    "11-09 14:10:00.000  4242  4242 E AndroidRuntime: FATAL EXCEPTION: main\n"
    "11-09 14:10:00.000  4242  4242 E AndroidRuntime: java.lang.IllegalStateException: boom\n"
)


def _payload(text: str, reason: str = REASON_LOGCAT, **overrides):
    body = {
        "batch_id": str(uuid.uuid4()),
        "device_label": "samsung SM-A256E",
        "app_version_name": "1.0",
        "app_version_code": 7,
        "reason": reason,
        "window_start_ms": WINDOW_START_MS,
        "window_end_ms": WINDOW_START_MS + 600_000,
        "run_id": "run-1",
        "file": SimpleUploadedFile(
            "log.gz", gzip.compress(text.encode()), content_type="application/gzip"
        ),
    }
    body.update(overrides)
    return body


def test_a_logcat_upload_is_accepted_with_its_run_id(token_a):
    response = token_a.post(URL, _payload(LOGCAT), format="multipart")
    assert response.status_code == 201, response.data
    row = DeviceLog.objects.get()
    assert row.reason == "logcat"
    assert row.run_id == "run-1"
    assert row.line_count == 5


def test_a_crash_upload_is_accepted(token_a):
    response = token_a.post(
        URL, _payload("exit reason=CRASH\n", reason=REASON_CRASH), format="multipart"
    )
    assert response.status_code == 201, response.data
    assert DeviceLog.objects.get().reason == "crash"


def test_an_upload_without_run_id_stores_it_blank(token_a):
    body = _payload(LOGCAT)
    del body["run_id"]
    assert token_a.post(URL, body, format="multipart").status_code == 201
    assert DeviceLog.objects.get().run_id == ""


def test_a_logcat_batch_over_its_decompressed_cap_is_refused_413(token_a):
    cap = MAX_RAW_BYTES_BY_REASON[REASON_LOGCAT]
    big = "x" * (cap + 1)
    response = token_a.post(URL, _payload(big), format="multipart")
    assert response.status_code == 413
    assert "2 MB" in response.data["detail"]
    assert DeviceLog.objects.count() == 0


def test_the_same_size_is_accepted_for_a_conversation(token_a):
    big = "x" * (MAX_RAW_BYTES_BY_REASON[REASON_LOGCAT] + 1)
    response = token_a.post(URL, _payload(big, reason="conversation"), format="multipart")
    assert response.status_code == 201


def test_the_database_check_constraint_accepts_the_new_reasons(household_a):
    for reason in (REASON_LOGCAT, REASON_CRASH):
        DeviceLog.objects.create(
            household=household_a,
            batch_id=uuid.uuid4(),
            device_label="x",
            reason=reason,
            raw_bytes=1,
            body_gz=gzip.compress(b"x"),
        )
    assert DeviceLog.objects.count() == 2


def test_retention_is_per_reason():
    assert retention_days(REASON_LOGCAT) == 30
    assert retention_days(REASON_CRASH) == RETENTION_DAYS == 60
    assert retention_days("conversation") == 60
    assert retention_days("drive_end") == 60


def _stale(household, reason: str, days: int) -> DeviceLog:
    row = DeviceLog.objects.create(
        household=household,
        batch_id=uuid.uuid4(),
        device_label="x",
        reason=reason,
        raw_bytes=1,
        body_gz=gzip.compress(b"x"),
    )
    DeviceLog.objects.filter(pk=row.pk).update(
        received_at=timezone.now() - datetime.timedelta(days=days)
    )
    return row


def test_prune_drops_logcat_at_30_days_and_keeps_conversation_until_60(token_a, household_a):
    logcat_31 = _stale(household_a, REASON_LOGCAT, 31)
    logcat_29 = _stale(household_a, REASON_LOGCAT, 29)
    convo_31 = _stale(household_a, "conversation", 31)
    convo_61 = _stale(household_a, "conversation", 61)
    crash_31 = _stale(household_a, REASON_CRASH, 31)
    assert token_a.post(URL, _payload(LOGCAT), format="multipart").status_code == 201
    left = set(DeviceLog.objects.values_list("pk", flat=True))
    assert logcat_31.pk not in left
    assert convo_61.pk not in left
    assert {logcat_29.pk, convo_31.pk, crash_31.pk} <= left


# --- devicelogs/lines.py


def test_utc_offset_reads_the_phones_zone_from_the_batch_window():
    lines = LOGCAT.splitlines()
    assert loglines.utc_offset(lines, WINDOW_START) == datetime.timedelta(hours=-6)
    when = loglines.line_utc(lines[3], WINDOW_START, datetime.timedelta(hours=-6))
    assert when == datetime.datetime(2026, 11, 9, 20, 10, tzinfo=datetime.UTC)


def test_utc_offset_is_zero_without_a_window_or_a_stamp():
    assert loglines.utc_offset(LOGCAT.splitlines(), None) == datetime.timedelta(0)
    assert loglines.utc_offset(["no stamp here"], WINDOW_START) == datetime.timedelta(0)


def test_utc_offset_survives_new_year():
    start = datetime.datetime(2027, 1, 1, 3, 0, tzinfo=datetime.UTC)  # Dec 31 21:00 in Houston
    line = "12-31 21:00:00.000  1  1 I Tag: x"
    assert loglines.utc_offset([line], start) == datetime.timedelta(hours=-6)


def test_parse_splits_a_padded_tag():
    fields = loglines.parse("11-09 14:00:00.000  4242  4242 I View    : setRequestedFrameRate x")
    assert fields["tag"] == "View"
    assert fields["msg"] == "setRequestedFrameRate x"
    assert loglines.parse("=== batch header") is None


def test_merged_orders_lines_across_batches_and_keeps_continuations_in_place():
    t = datetime.datetime(2026, 11, 9, 20, 0, tzinfo=datetime.UTC)
    a = [(t, "a1"), (None, "a1-continued"), (t + datetime.timedelta(seconds=10), "a2")]
    b = [(t + datetime.timedelta(seconds=5), "b1")]
    assert loglines.merged([a, b]) == ["a1", "a1-continued", "b1", "a2"]


def test_blobs_round_trip_in_any_order():
    text = "".join(f"line {i} {os.urandom(100).hex()}\n" for i in range(3000))
    blobs = loglines.blob_lines(text)
    assert len(blobs) > 2
    shuffled = list(reversed(blobs)) + blobs[:1]  # out of order, with a duplicate
    assert loglines.read_blobs(shuffled) == text


def test_a_missing_blob_chunk_is_said_in_words():
    text = "".join(f"{i} {os.urandom(100).hex()}\n" for i in range(3000))
    blobs = loglines.blob_lines(text)
    with pytest.raises(ValueError, match="missing"):
        loglines.read_blobs(blobs[1:])


# --- manage.py device_logs


def _upload(token_a, text, **overrides):
    response = token_a.post(URL, _payload(text, **overrides), format="multipart")
    assert response.status_code == 201, response.data


def test_parse_when_takes_spans_and_iso_times():
    now = datetime.datetime(2026, 11, 9, 21, 0, tzinfo=datetime.UTC)
    assert parse_when("90m", now=now) == now - datetime.timedelta(minutes=90)
    assert parse_when("2h", now=now) == now - datetime.timedelta(hours=2)
    assert parse_when("2026-11-09T20:05", now=now) == datetime.datetime(
        2026, 11, 9, 20, 5, tzinfo=datetime.UTC
    )
    with pytest.raises(CommandError):
        parse_when("yesterday-ish", now=now)


def test_the_command_filters_by_reason_and_tag(token_a, capsys):
    _upload(token_a, LOGCAT)
    _upload(
        token_a, "11-09 14:00:01.000  1  1 I LIVE_AUDIT: turn_complete\n", reason="conversation"
    )
    call_command("device_logs", "--reason", "logcat", "--tag", "androidruntime")
    out = capsys.readouterr().out
    assert "FATAL EXCEPTION" in out
    assert "MidnightEvents" not in out and "LIVE_AUDIT" not in out
    assert "| logcat |" in out


def test_the_raw_window_cuts_lines_by_their_own_stamps(token_a, capsys):
    _upload(token_a, LOGCAT)
    call_command(
        "device_logs",
        "--reason",
        "logcat",
        "--since",
        "2026-11-09T20:00:04",
        "--until",
        "2026-11-09T20:01:00",
        "--raw",
    )
    out = capsys.readouterr().out.splitlines()
    assert out == [LOGCAT.splitlines()[1], LOGCAT.splitlines()[2]]


def test_the_raw_dump_merges_batches_in_time_order(token_a, capsys):
    late = "11-09 14:05:00.000  4242  4242 I Later: second batch\n"
    _upload(token_a, late, window_start_ms=WINDOW_START_MS + 300_000)
    _upload(token_a, LOGCAT)
    call_command("device_logs", "--reason", "logcat", "--since", "2026-11-09T19:00", "--raw")
    out = capsys.readouterr().out.splitlines()
    assert out.index(late.strip()) == 3  # after the three 14:00 lines, before the 14:10 crash


def test_the_window_skips_batches_that_do_not_overlap(token_a, capsys):
    _upload(token_a, LOGCAT)
    call_command("device_logs", "--reason", "logcat", "--since", "2026-11-10T00:00", "--raw")
    assert capsys.readouterr().out.strip() == ""


def test_the_run_filter(token_a, capsys):
    _upload(token_a, LOGCAT, run_id="run-a")
    _upload(token_a, "11-09 14:00:00.000  9  9 I Other: run b\n", run_id="run-b")
    call_command("device_logs", "--run", "run-b", "--raw")
    assert capsys.readouterr().out.strip() == "11-09 14:00:00.000  9  9 I Other: run b"


def test_b64_output_reassembles_to_the_raw_text(token_a, capsys):
    _upload(token_a, LOGCAT)
    call_command("device_logs", "--reason", "logcat", "--since", "2026-11-09T19:00", "--raw")
    raw = capsys.readouterr().out
    call_command(
        "device_logs", "--reason", "logcat", "--since", "2026-11-09T19:00", "--raw", "--b64"
    )
    blobs = capsys.readouterr().out.splitlines()
    assert all(line.startswith("BLOB") for line in blobs)
    assert loglines.read_blobs(blobs) == raw


def test_b64_needs_raw():
    with pytest.raises(CommandError):
        call_command("device_logs", "--b64")


def test_the_reason_flag_still_takes_one_value(token_a, capsys):
    _upload(token_a, LOGCAT)
    call_command("device_logs", "--last", "1", "--reason", "logcat")
    assert "FATAL EXCEPTION" in capsys.readouterr().out
