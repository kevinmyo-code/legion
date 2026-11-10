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


def test_the_read_through_list_covers_the_phones():
    """`READ_THROUGH_TOOLS` is a hand copy of `LiveToolbox.EPISODIC_EXCLUDED_TOOLS`; the
    whole-turn drop depends on it staying a superset. Skipped where the phone tree is absent
    (the engine image is built from `server/` alone)."""
    toolbox = (
        Path(__file__).resolve().parents[2]
        / "app/src/main/java/com/kevin/legion/service/LiveToolbox.kt"
    )
    if not toolbox.exists():
        pytest.skip("the phone's source is not in this tree")
    source = toolbox.read_text(encoding="utf-8")
    match = re.search(r"val EPISODIC_EXCLUDED_TOOLS = setOf\((.*?)\)", source, re.DOTALL)
    assert match, "LiveToolbox.EPISODIC_EXCLUDED_TOOLS moved; update this test and the copy"
    phone = set(re.findall(r'"([a-z_]+)"', match.group(1)))
    assert phone and phone <= consolidate.READ_THROUGH_TOOLS, sorted(
        phone - consolidate.READ_THROUGH_TOOLS
    )


# =============================================================================
# Open-loops ticket 07 (2026-11-10): read_through marks and redacted lines drop the turn
# =============================================================================

A = "11-10 08:00:{:02d}.000  1 2 I LIVE_AUDIT: {}"


def _record(*events):
    return "\n".join(A.format(i, e) for i, e in enumerate(events)) + "\n"


MARKED = _record(
    "session_start model=gemini-live async_set=none",
    'user final=true text="I want to repaint the shed blue."',
    'assistant text="Blue it is."',
    "turn_complete",
    # A camera look: the frame went out, the turn is marked after his line.
    'user final=true text="What does this label say?"',
    "read_through source=look",
    'assistant text="[redacted: third-party content]"',
    "turn_complete",
    # The carry turn after the look: still read-through, his words included.
    "read_through source=carry",
    'user final=true text="Remember the bottle says Zephyr Tonic."',
    'assistant text="[redacted: third-party content]"',
    "turn_complete",
    # Live search grounding.
    'user final=true text="Who won the regatta?"',
    "read_through source=grounding",
    'assistant text="[redacted: third-party content]"',
    "turn_complete",
    # A spoken brief raise (proactive) and the turn after it.
    "read_through source=proactive",
    'assistant text="[redacted: third-party content]"',
    "turn_complete",
    "read_through source=carry",
    'user final=true text="Yes, add the Marlow newsletter thing."',
    "turn_complete",
    # ask_engine's GitHub or web text (fleet_web), marked by the phone.
    'user final=true text="Did the build pass?"',
    'tool_call id=e1 name=ask_engine async=false args={"question":"build"}',
    "read_through source=fleet_web tool=ask_engine",
    'tool_result id=e1 name=ask_engine success=true ms=200 scheduling=none sent=true response={"answer":"x"}',
    'assistant text="[redacted: third-party content]"',
    "turn_complete",
    # A clean turn, but after marks in this session: his words stay, the reply goes.
    'user final=true text="My bike is the green one."',
    'assistant text="Got it, the green one."',
    "turn_complete",
    'session_end reason="stopped"',
    # A new session starts clean.
    "session_start model=gemini-live async_set=none",
    'user final=true text="I moved my dentist to Thursdays."',
    'assistant text="Thursdays, noted."',
    "turn_complete",
)


def test_read_through_marks_drop_the_whole_turn_and_later_replies():
    words = consolidate.transcript(MARKED)
    assert words.lines == [
        "User: I want to repaint the shed blue.",
        "Assistant: Blue it is.",
        "User: My bike is the green one.",
        "User: I moved my dentist to Thursdays.",
        "Assistant: Thursdays, noted.",
    ]
    assert words.dropped_turns == 6
    text = "\n".join(words.lines)
    for absent in ("label", "Zephyr", "regatta", "Marlow", "build", "redacted", "Got it"):
        assert absent not in text


def test_a_redacted_line_drops_its_turn_even_with_no_mark():
    raw = _record(
        'user final=true text="Read that to me again."',
        'assistant text="[redacted: third-party content]"',
        "turn_complete",
        'user final=true text="What did the Quillon mail say?"',
        'tool_call id=t1 name=some_new_tool async=false args={}',
        'tool_result id=t1 name=some_new_tool success=true ms=1 scheduling=none sent=true response="[redacted: third-party content]"',
        "turn_complete",
        'user final=true text="I park on level three."',
        "turn_complete",
    )
    words = consolidate.transcript(raw)
    assert words.lines == ["User: I park on level three."]
    assert words.dropped_turns == 2


def test_the_engines_third_party_tools_drop_web_turns():
    from assistant.read_through import THIRD_PARTY_TOOLS

    assert THIRD_PARTY_TOOLS <= consolidate.READ_THROUGH_TOOLS
    raw = _record(
        'user final=true text="Any open pull requests on Frobnitz?"',
        'tool_call id=g1 name=github_status async=false args={}',
        'tool_result id=g1 name=github_status success=true ms=1 scheduling=none sent=true response={"message":"PR: rename the widget"}',
        'assistant text="One, rename the widget."',
        "turn_complete",
        'user final=true text="Thanks."',
        'assistant text="That widget PR is from a stranger."',
        "turn_complete",
    )
    words = consolidate.transcript(raw)
    assert words.lines == ["User: Thanks."]


def test_a_source_that_cannot_mark_turns_gives_his_words_only(household_user, household_a):
    """The head unit logs no read_through event (its Live socket grounds on Google Search), so a
    tool-free reply there may be a search answer: only his own lines reach the model."""
    raw = _record(
        'user final=true text="My plates renew in March."',
        'assistant text="The Harbourfest is on Saturday, says the web."',
        "turn_complete",
    )
    head_unit = _log(household_a, household_user, raw, app_version_name="1.0+abc1234")
    phone = _log(household_a, household_user, raw, run_id="run-1")
    assert consolidate.log_transcript(head_unit).lines == ["User: My plates renew in March."]
    assert consolidate.log_transcript(phone).lines == [
        "User: My plates renew in March.",
        "Assistant: The Harbourfest is on Saturday, says the web.",
    ]
    model = FakeModel()
    consolidate.run(key="k", transport=model)
    assert len(model.prompts) == 2
    assert sum("Harbourfest" in prompt for prompt in model.prompts) == 1


def test_a_marked_turn_never_reaches_the_model(household_user, household_a):
    _log(household_a, household_user, MARKED, run_id="run-2")
    model = FakeModel()
    consolidate.run(key="k", transport=model)
    assert len(model.prompts) == 1
    for absent in ("Zephyr", "Marlow", "regatta", "label"):
        assert absent not in model.prompts[0]
    assert "shed blue" in model.prompts[0]


# =============================================================================
# Health (2026-11-09): an `ingest_runs` row per household, give-ups counted
# =============================================================================


def _command(monkeypatch, transport, key="k"):
    if key:
        monkeypatch.setenv("LEGION_GEMINI_KEY", key)
    else:
        monkeypatch.delenv("LEGION_GEMINI_KEY", raising=False)
    monkeypatch.setattr(consolidate, "_transport", transport)
    out = io.StringIO()
    call_command("consolidate_memories", stdout=out)
    return out.getvalue()


def _memory_runs(household):
    from ingest.models import IngestRun, Source

    return list(IngestRun.objects.filter(household=household, source=Source.MEMORY))


def _freshness(household):
    from ingest.freshness import freshness_for
    from ingest.models import IngestRun, Source

    return freshness_for(
        IngestRun.objects.filter(household=household), Source.MEMORY, timezone.now()
    )


def test_a_run_records_an_ok_row_and_freshness_says_when(
    household_user, household_a, monkeypatch
):
    _log(household_a, household_user)
    model = FakeModel([{"text": "Kevin's gym is on Elm Street", "kind": "fact",
                        "importance": 6, "durable": True}])
    out = _command(monkeypatch, model)
    assert "1 memories written" in out
    [run] = _memory_runs(household_a)
    assert (run.outcome, run.rows_written) == ("ok", 1)
    fresh = _freshness(household_a)
    assert fresh["stale"] is False
    assert fresh["sentence"].startswith("Memory from conversations was last updated")


def test_no_key_records_skipped_with_the_reason(household_user, household_a, monkeypatch):
    _log(household_a, household_user)
    _command(monkeypatch, FakeModel(), key=None)
    [run] = _memory_runs(household_a)
    assert run.outcome == "skipped" and "LEGION_GEMINI_KEY is not set" in run.error
    sentence = _freshness(household_a)["sentence"]
    assert sentence.startswith("Memory from conversations is not being read.")
    assert "LEGION_GEMINI_KEY is not set" in sentence


def test_every_record_failing_records_a_failed_run(household_user, household_a, monkeypatch):
    _log(household_a, household_user)
    _command(monkeypatch, FakeModel(status=500))
    [run] = _memory_runs(household_a)
    assert run.outcome == "failed"
    fresh = _freshness(household_a)
    assert fresh["stale"] is True and "The latest attempt failed." in fresh["sentence"]


def test_a_quiet_hour_is_an_ok_run_not_a_failure(household_a, monkeypatch):
    _command(monkeypatch, FakeModel())
    [run] = _memory_runs(household_a)
    assert (run.outcome, run.rows_written) == ("ok", 0)


def test_records_that_gave_up_are_counted_in_the_report(household_user, household_a):
    _log(household_a, household_user)
    for _ in range(MAX_ATTEMPTS - 1):
        assert consolidate.run(key="k", transport=FakeModel(status=500)).gave_up == 0
    report = consolidate.run(key="k", transport=FakeModel(status=500))
    assert report.gave_up == 1
    assert f"1 record has failed {MAX_ATTEMPTS} times and will not be tried again" in (
        report.sentence()
    )
    # Still said on a later run that reads nothing.
    later = consolidate.run(key="k", transport=FakeModel())
    assert (later.read, later.gave_up) == (0, 1) and "will not be tried again" in later.sentence()


def test_one_households_run_reads_only_its_own_records(
    household_user, household_a, household_b
):
    other = _member(household_b, "mia@example.com")
    _log(household_a, household_user)
    _log(household_b, other)
    report = consolidate.run(key="k", transport=FakeModel(), household=household_b)
    assert report.read == 1
    assert consolidate.pending(household=household_a).count() == 1
    assert consolidate.pending(household=household_b).count() == 0


def test_the_nightly_reviews_conversations_drop_marked_turns_too(household_user, household_a):
    from member_memory import review

    log = _log(household_a, household_user, MARKED, run_id="run-3")
    text = review._filtered_text(log)
    assert "shed blue" in text
    for absent in ("Zephyr", "Marlow", "regatta"):
        assert absent not in text
