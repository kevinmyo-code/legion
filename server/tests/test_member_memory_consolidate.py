# ruff: noqa: E501 - the LIVE_AUDIT fixture is copied as the phone writes it, one line each
"""The consolidation job (memory-and-vault ticket 03, ADR 0075).

The model is never reached: every test hands `consolidate.run` its own transport and reads what
the job sent it. CLAUDE.md section 7: mail and other read-through tool output never reaches the
prompt, so it can never become a memory.
"""

from __future__ import annotations

import gzip
import io
import json
import re
import uuid
from datetime import timedelta
from pathlib import Path

import pytest
from django.core.management import call_command
from django.utils import timezone

from devicelogs.models import DeviceLog
from ingest.drive import Response
from member_memory import consolidate
from member_memory.models import (
    MAX_ATTEMPTS,
    SOURCE_CONSOLIDATION,
    MemberMemory,
    MemoryConsolidation,
)
from tests.conftest import _member

CONVERSATION = """\
11-05 10:00:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
11-05 10:00:01.000  1 2 I LIVE_AUDIT: user final=true text="My gym moved to Elm Street."
11-05 10:00:01.500  1 2 I LIVE_AUDIT: assistant text="Noted, Elm Street."
11-05 10:00:02.000  1 2 I LIVE_AUDIT: turn_complete
11-05 10:00:03.000  1 2 I LIVE_AUDIT: user final=true text="Read my last email."
11-05 10:00:03.100  1 2 I LIVE_AUDIT: tool_call id=m1 name=read_mail async=false args={"index":0}
11-05 10:00:03.400  1 2 I LIVE_AUDIT: tool_result id=m1 name=read_mail success=true ms=300 scheduling=none sent=true response="[redacted: third-party content]"
11-05 10:00:03.600  1 2 I LIVE_AUDIT: assistant text="Your landlord says the rent is due."
11-05 10:00:04.000  1 2 I LIVE_AUDIT: turn_complete
11-05 10:00:05.000  1 2 I LIVE_AUDIT: user final=true text="Any trouble codes on the Jeep?"
11-05 10:00:05.100  1 2 I LIVE_AUDIT: tool_call id=c1 name=ask_fleet async=false args={"question":"codes"}
11-05 10:00:05.300  1 2 I LIVE_AUDIT: tool_result id=c1 name=ask_fleet success=true ms=200 scheduling=none sent=true response={"answer":"P0420 stored"}
11-05 10:00:05.600  1 2 I LIVE_AUDIT: assistant text="One code, P0420."
11-05 10:00:06.000  1 2 I LIVE_AUDIT: turn_complete
11-05 10:00:07.000  1 2 I LIVE_AUDIT: user final=true text="Thanks."
11-05 10:00:07.500  1 2 I LIVE_AUDIT: assistant text="Anything else from the mail?"
11-05 10:00:08.000  1 2 I LIVE_AUDIT: turn_complete
11-05 10:00:09.000  1 2 I LIVE_AUDIT: session_end reason="stopped"
"""


def _log(household, user, text=CONVERSATION, *, age=timedelta(hours=1), **fields):
    raw = text.encode()
    row = DeviceLog.objects.create(
        household=household,
        user=user,
        batch_id=uuid.uuid4(),
        device_label="SM-A256E",
        reason=fields.pop("reason", "conversation"),
        line_count=raw.count(b"\n"),
        raw_bytes=len(raw),
        body_gz=gzip.compress(raw),
        **fields,
    )
    DeviceLog.objects.filter(pk=row.pk).update(received_at=timezone.now() - age)
    return row


class FakeModel:
    """Answers with `items` and remembers every prompt it was sent."""

    def __init__(self, items=None, status=200):
        self.items = items or []
        self.status = status
        self.prompts: list[str] = []

    def __call__(self, method, url, headers, body):
        sent = json.loads(body)
        self.prompts.append(sent["contents"][0]["parts"][0]["text"])
        answer = {"memories": self.items}
        payload = {"candidates": [{"content": {"parts": [{"text": json.dumps(answer)}]}}]}
        return Response(self.status, {}, json.dumps(payload).encode())


def test_the_transcript_is_the_persons_words_and_tool_free_replies_only():
    lines = consolidate.transcript(CONVERSATION).lines
    assert lines == [
        "User: My gym moved to Elm Street.",
        "Assistant: Noted, Elm Street.",
        "User: Any trouble codes on the Jeep?",
        "User: Thanks.",
    ]
    text = "\n".join(lines)
    # The mail turn is dropped whole, the person's own words included; no tool result anywhere;
    # and after a read-through tool, no assistant words for the rest of the session.
    for absent in ("Read my last email", "landlord", "redacted", "P0420", "from the mail"):
        assert absent not in text


def test_a_record_becomes_private_memories_and_mail_never_reaches_the_model(
    household_user, household_a
):
    log = _log(household_a, household_user)
    model = FakeModel([
        {"text": "Kevin's gym is on Elm Street", "kind": "fact", "importance": 6,
         "durable": True},
    ])
    report = consolidate.run(key="test-key", transport=model)
    assert (report.read, report.written) == (1, 1)
    assert len(model.prompts) == 1
    assert "landlord" not in model.prompts[0] and "read_mail" not in model.prompts[0]
    row = MemberMemory.objects.get()
    assert row.member_id == household_user.pk and row.owner_user_id == household_user.pk
    assert row.source == SOURCE_CONSOLIDATION and row.source_log_id == log.id
    # A second run reads nothing new.
    assert consolidate.run(key="test-key", transport=model).read == 0
    assert len(model.prompts) == 1


def test_a_contradicting_fact_supersedes_the_old_one(household_user, household_a):
    old = MemberMemory.objects.create(
        household=household_a, member=household_user, owner_user=household_user,
        text="Kevin's gym is on Main Street", source="manual",
    )
    _log(household_a, household_user)
    model = FakeModel([
        {"text": "Kevin's gym is on Elm Street", "kind": "fact", "importance": 6,
         "durable": True, "supersedes": str(old.id)},
        {"text": "Kevin's gym is on Main Street", "kind": "fact", "importance": 6,
         "durable": True},
        {"text": "made up", "kind": "fact", "importance": 6, "durable": True,
         "supersedes": str(uuid.uuid4())},
    ])
    consolidate.run(key="test-key", transport=model)
    assert str(old.id) in model.prompts[0]
    old.refresh_from_db()
    assert old.superseded_at is not None
    new = MemberMemory.objects.get(text="Kevin's gym is on Elm Street")
    assert new.supersedes_id == old.id
    # An id the job never sent is ignored, not trusted.
    assert MemberMemory.objects.get(text="made up").supersedes_id is None


def test_another_members_memories_are_never_shown_or_superseded(household_user, household_a):
    mia = _member(household_a, "mia@example.com")
    theirs = MemberMemory.objects.create(
        household=household_a, member=mia, owner_user=mia, text="Mia's secret", source="manual",
    )
    _log(household_a, household_user)
    model = FakeModel([
        {"text": "x y z", "kind": "fact", "importance": 5, "durable": True,
         "supersedes": str(theirs.id)},
    ])
    consolidate.run(key="test-key", transport=model)
    assert "Mia's secret" not in model.prompts[0]
    theirs.refresh_from_db()
    assert theirs.superseded_at is None


def test_vault_identifiers_from_the_model_are_dropped(household_user, household_a):
    _log(household_a, household_user)
    consolidate.run(key="k", transport=FakeModel([
        {"text": "Kevin's SSN is 123-45-6789", "kind": "fact", "importance": 9, "durable": True},
    ]))
    assert MemberMemory.objects.count() == 0
    assert MemoryConsolidation.objects.get().outcome == "done"


def test_no_key_marks_nothing_and_says_so(household_user, household_a, monkeypatch):
    monkeypatch.delenv("LEGION_GEMINI_KEY", raising=False)
    _log(household_a, household_user)
    out = io.StringIO()
    call_command("consolidate_memories", stdout=out)
    assert "LEGION_GEMINI_KEY is not set" in out.getvalue()
    assert MemoryConsolidation.objects.count() == 0


def test_a_failed_call_is_retried_then_set_aside(household_user, household_a):
    _log(household_a, household_user)
    for _ in range(MAX_ATTEMPTS):
        assert consolidate.run(key="k", transport=FakeModel(status=500)).failed == 1
    assert consolidate.run(key="k", transport=FakeModel(status=500)).read == 0
    row = MemoryConsolidation.objects.get()
    assert row.attempts == MAX_ATTEMPTS and "HTTP 500" in row.detail


def test_only_finished_member_conversation_records_are_read(household_user, household_a):
    _log(household_a, household_user, age=timedelta(minutes=2))  # still settling
    _log(household_a, None)  # no member
    _log(household_a, household_user, reason="drive_end")
    _log(household_a, household_user, "11-05 10:00:00.000  1 2 I LIVE_AUDIT: turn_complete\n")
    model = FakeModel()
    report = consolidate.run(key="k", transport=model)
    assert (report.read, report.skipped) == (1, 1) and model.prompts == []


def test_the_crontab_line_parses_with_the_schedulers_pattern():
    root = Path(__file__).resolve().parents[2]
    crontab = (root / "deploy" / "crontab").read_text(encoding="utf-8")
    pattern = re.compile(r"^(?:\S+\s+){4}\S+\s+python manage\.py (?P<task>[a-z_]+)\s*$")
    tasks = [m.group("task") for line in crontab.splitlines() if (m := pattern.match(line))]
    assert "consolidate_memories" in tasks


@pytest.mark.parametrize("bad", ["not json", json.dumps({"other": []})])
def test_an_unreadable_answer_fails_in_words(household_user, household_a, bad):
    _log(household_a, household_user)

    def transport(method, url, headers, body):
        payload = {"candidates": [{"content": {"parts": [{"text": bad}]}}]}
        return Response(200, {}, json.dumps(payload).encode())

    assert consolidate.run(key="k", transport=transport).failed == 1
    assert MemberMemory.objects.count() == 0
