"""`manage.py device_logs --render` and `devicelogs/render.py` (2026-10-28).

FIXTURE is byte-for-byte the batch the head unit's `ConvoAuditTest` builds from its own
`ConvoAudit` and `ConvoLatency` calls, and its lines are the phone's LIVE_AUDIT / LIVE_LATENCY
formats, so this pins the cross-device format from the engine's side.
"""

from __future__ import annotations

import gzip
import pathlib
import uuid

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.core.management import call_command
from django.core.management.base import CommandError

from devicelogs.render import render

URL = "/api/device-logs/"

FIXTURE = """\
10-28 16:20:00.000 1234 5678 I LIVE_AUDIT: session_start model=gemini-3.1-flash-live-preview async_set=none
10-28 16:20:00.900 1234 5678 I LIVE_LATENCY: cold_start_ms=900
10-28 16:20:01.000 1234 5678 I LIVE_AUDIT: mic_open reason=first_open
10-28 16:20:03.900 1234 5679 I LIVE_AUDIT: user final=false text=" Any trouble codes?"
10-28 16:20:04.100 1234 5679 I LIVE_AUDIT: tool_call id=c1 name=get_codes async=false args={}
10-28 16:20:04.300 1234 5680 I LIVE_LATENCY: tool_ms=200 tool=get_codes sent=true async=false
10-28 16:20:04.300 1234 5680 I LIVE_AUDIT: tool_result id=c1 name=get_codes success=true ms=200 scheduling=none sent=true response={"success":true,"codes":[]}
10-28 16:20:04.301 1234 5679 I LIVE_AUDIT: user final=true text="Any trouble codes?"
10-28 16:20:04.302 1234 5679 I LIVE_AUDIT: turn_complete
10-28 16:20:05.000 1234 5679 I LIVE_LATENCY: reply_ms=1100
10-28 16:20:05.000 1234 5679 I LIVE_AUDIT: mic_closed reason=half_duplex
10-28 16:20:05.050 1234 5679 I LIVE_AUDIT: assistant text="No codes stored."
10-28 16:20:06.000 1234 5679 I LIVE_AUDIT: turn_complete
10-28 16:20:20.000 1234 5679 I LIVE_AUDIT: session_end reason="idle"
"""

TIMELINE = [
    "== session 1  model=gemini-3.1-flash-live-preview  async=none",
    "16:20:00.000  session start",
    "16:20:00.900  cold start 900ms",
    "16:20:03.900  USER: Any trouble codes?",
    "16:20:04.100  TOOL -> get_codes({})",
    "16:20:04.300  TOOL <- get_codes ok 200ms",
    '                  = {"success":true,"codes":[]}',
    "16:20:04.302  turn complete",
    "16:20:05.000  reply 1100ms",
    "16:20:05.050  ASSISTANT: No codes stored.",
    "16:20:06.000  turn complete",
    "16:20:20.000  session end (idle)",
]


def test_render_reads_a_head_unit_conversation_batch():
    assert render(FIXTURE) == TIMELINE


def test_render_of_a_batch_with_no_audit_lines_is_empty():
    assert render("10-15 08:00:00.000 1 2 I Breadcrumb: hello\n") == []


def test_the_engine_copy_of_voice_audit_matches_the_repo_tool():
    """The copy must not diverge. `tools/` is absent from the engine image, so skip there."""
    server = pathlib.Path(__file__).resolve().parents[1]
    tool = server.parent / "tools" / "voice_audit.py"
    if not tool.exists():
        pytest.skip("tools/voice_audit.py is not in this checkout (it lands with the phone branch)")
    copy = server / "devicelogs" / "voice_audit.py"
    assert copy.read_bytes().replace(b"\r\n", b"\n") == tool.read_bytes().replace(b"\r\n", b"\n")


@pytest.mark.django_db
def test_the_read_command_renders_a_conversation_batch(token_a, capsys):
    body = {
        "batch_id": str(uuid.uuid4()),
        "device_label": "Head unit",
        "app_version_name": "1.0+abc1234",
        "app_version_code": 1,
        "reason": "conversation",
        "window_start_ms": 1_761_000_000_000,
        "window_end_ms": 1_761_000_020_000,
        "file": SimpleUploadedFile("c.log.gz", gzip.compress(FIXTURE.encode()), content_type="application/gzip"),
    }
    assert token_a.post(URL, body, format="multipart").status_code == 201

    call_command("device_logs", "--last", "1", "--reason", "conversation", "--render")
    out = capsys.readouterr().out.splitlines()
    assert "| conversation |" in out[0]
    assert out[1:] == TIMELINE


@pytest.mark.django_db
def test_render_and_grep_together_are_refused():
    with pytest.raises(CommandError):
        call_command("device_logs", "--render", "--grep", "x")
