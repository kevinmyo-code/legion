"""`tools/phone_audit_engine.py` without gcloud: the window, the Job's arguments, BLOB reassembly
(byte-compatible with `server/devicelogs/lines.py`), and the report sections. Run from the repo root:

    python -m pytest tools/tests/test_phone_audit_engine.py -p no:cacheprovider
"""

from __future__ import annotations

import base64
import datetime
import gzip
import hashlib
import importlib.util
import os
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "phone_audit_engine_under_test", TOOLS / "phone_audit_engine.py"
)
pa = importlib.util.module_from_spec(spec)
sys.modules["phone_audit_engine_under_test"] = pa
spec.loader.exec_module(pa)

LINES = [
    "11-09 14:00:00.000  4242  4300 I LIVE_AUDIT: session_start model=m async_set=none",
    '11-09 14:00:01.000  4242  4300 I LIVE_AUDIT: user final=true text="what is the weather"',
    "11-09 14:00:02.000  4242  4300 I LIVE_LATENCY: tool_ms=4200 tool=get_weather",
    "11-09 14:00:02.500  4242  4300 I LIVE_LATENCY: tool_ms=120 tool=get_time",
    '11-09 14:00:03.000  4242  4300 I LIVE_AUDIT: assistant text="Sunny."',
    '11-09 14:00:04.000  4242  4300 I LIVE_AUDIT: session_end reason="user"',
    "11-09 14:10:00.000  4242  4242 E AndroidRuntime: FATAL EXCEPTION: main",
    "11-09 14:10:05.000  4242  4242 I ExitInfo: exit reason=USER_REQUESTED status=0",
    "11-09 14:10:06.000  4242  4242 E ExitInfo: exit reason=LOW_MEMORY status=0",
]


def _blobs(text: str, chunk: int = 50) -> list[str]:
    """What `devicelogs.lines.blob_lines` prints, with a small chunk so the test has several."""
    encoded = base64.b64encode(gzip.compress(text.encode())).decode()
    chunks = [encoded[i : i + chunk] for i in range(0, len(encoded), chunk)]
    out = [f"BLOB {i + 1}/{len(chunks)} {c}" for i, c in enumerate(chunks)]
    return out + [f"BLOB_END {len(chunks)} {hashlib.sha256(text.encode()).hexdigest()}"]


def test_the_window_defaults_to_the_last_hour_in_utc():
    now = datetime.datetime(2026, 11, 9, 21, 0, tzinfo=datetime.UTC)
    assert pa.window(60, None, None, now=now) == (
        "2026-11-09T20:00:00+00:00",
        "2026-11-09T21:00:00+00:00",
    )


def test_the_window_takes_iso_times_and_converts_zones():
    since, until = pa.window(None, "2026-11-09T14:00-06:00", "2026-11-09T15:30-06:00")
    assert (since, until) == ("2026-11-09T20:00:00+00:00", "2026-11-09T21:30:00+00:00")
    with pytest.raises(pa.Refused):
        pa.window(None, "2026-11-09T15:00", "2026-11-09T14:00")
    with pytest.raises(pa.Refused):
        pa.window(None, "yesterday", None)


def test_the_job_arguments():
    args = pa.engine_args("A", "B", ["logcat", "crash"], "samsung SM-A256E")
    assert args == [
        "manage.py",
        "device_logs",
        "--since",
        "A",
        "--until",
        "B",
        "--raw",
        "--b64",
        "--reason",
        "logcat",
        "--reason",
        "crash",
        "--device",
        "samsung SM-A256E",
    ]
    with pytest.raises(pa.Refused):
        pa.engine_args("A", "B", ["logcat"], "a,b")


def test_blobs_reassemble_in_any_order_with_duplicates():
    text = "\n".join(LINES * 20) + "\n"
    blobs = _blobs(text)
    assert len(blobs) > 3
    shuffled = blobs[::-1] + blobs[:2]
    assert pa.complete(shuffled)
    assert pa.read_blobs(shuffled) == text


def test_a_missing_chunk_is_said_and_incomplete():
    blobs = _blobs("\n".join(LINES))
    partial = blobs[1:]
    assert not pa.complete(partial)
    with pytest.raises(pa.Refused, match="never reached"):
        pa.read_blobs(partial)


def test_no_blob_lines_names_the_likely_cause():
    with pytest.raises(pa.Refused, match="deployed"):
        pa.read_blobs(["No device logs stored."])


def test_the_engine_and_this_tool_agree_on_the_blob_format():
    server = TOOLS.parent / "server" / "devicelogs" / "lines.py"
    if not server.exists():
        pytest.skip("server/ not present")
    spec2 = importlib.util.spec_from_file_location("devicelogs_lines_for_test", server)
    lines_mod = importlib.util.module_from_spec(spec2)
    spec2.loader.exec_module(lines_mod)
    text = "".join(f"{i} {os.urandom(60).hex()}\n" for i in range(4000))
    assert pa.read_blobs(lines_mod.blob_lines(text)) == text


def test_execution_name_from_either_output():
    assert pa.execution_name("legion-worker-abc12\n") == "legion-worker-abc12"
    assert (
        pa.execution_name("Execution [legion-worker-x9z8q] has successfully completed.")
        == "legion-worker-x9z8q"
    )
    assert pa.execution_name("nothing useful") is None


def test_crashes_and_slow_tools():
    assert pa.crashes(LINES) == [LINES[6], LINES[8]]
    assert pa.slow_tools(LINES) == [LINES[2]]


def test_the_report_has_every_section(tmp_path):
    out = pa.report(LINES, summary=False, verbose=False, out_path=tmp_path / "x.txt")
    text = "\n".join(out)
    for section in (
        "-- Conversation",
        "-- Slow tools",
        "-- Crashes and exit records",
        "-- Busiest tags",
    ):
        assert section in text
    assert "what is the weather" in text
    assert "FATAL EXCEPTION" in text


def test_main_rereads_a_saved_pull_without_gcloud(tmp_path, capsys):
    saved = tmp_path / "pull.txt"
    saved.write_text("\n".join(LINES) + "\n", encoding="utf-8")
    assert pa.main(["--file", str(saved)]) == 0
    assert "Sunny." in capsys.readouterr().out
