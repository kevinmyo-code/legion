# ruff: noqa: E501 - the LIVE_AUDIT fixtures are copied as the phone writes them, one line each
"""Open loops, engine half (ADR 0085, `.scratch/open-loops/`).

Kevin, 2026-11-10: something he said he would do and never closed is PROPOSED, ask first ("Track
it?"); yes makes it a task, no drops it for good. Extraction rides the nightly memory review on
the Claude worker, from his own words only; the engine gates every proposal in code.

**Every example here is invented** (the repo is public): one made-up member, "Sam", a made-up
dentist, car registration and neighbour. No model is ever called: the proposal lists below are
recorded answers, as if the helper had written them.
"""

from __future__ import annotations

import datetime
import uuid

import pytest
from django.db import IntegrityError, transaction
from django.utils import timezone
from rest_framework.test import APIClient

from claude_session.models import SCOPE_FULL, SCOPE_LISTED
from household.models import DeviceToken
from open_loops import gate, words
from open_loops.models import (
    STATUS_DISMISSED,
    STATUS_PENDING,
    STATUS_TRACKED,
    OpenLoop,
    OpenLoopRun,
)
from tests.conftest import _client_for, _member
from tests.test_member_memory_consolidate import _log

pytestmark = pytest.mark.django_db

REVIEW = "/api/member-memories/review/"
CONTEXT = f"{REVIEW}context"
CHANGES = f"{REVIEW}changes"
URL = "/api/open-loops/"
SENTINEL = "Quokkaville"


@pytest.fixture
def loops_on(settings):
    settings.LEGION_MEMORY_REVIEW = "shadow"
    settings.LEGION_OPEN_LOOPS = "on"


def _future(days=30) -> datetime.date:
    return timezone.localdate() + datetime.timedelta(days=days)


def _month_day(day: datetime.date) -> str:
    return f"{day.strftime('%B')} {day.day}"


def _says(*turns: str) -> str:
    """A conversation of clean turns: each his line, Alfred's tool-free reply, turn end."""
    lines = ["11-08 10:00:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none"]
    for i, said in enumerate(turns):
        lines.append(f'11-08 10:00:{i + 1:02d}.000  1 2 I LIVE_AUDIT: user final=true text="{said}"')
        lines.append(f'11-08 10:00:{i + 1:02d}.300  1 2 I LIVE_AUDIT: assistant text="Okay, {SENTINEL}."')
        lines.append(f"11-08 10:00:{i + 1:02d}.500  1 2 I LIVE_AUDIT: turn_complete")
    lines.append('11-08 10:01:00.000  1 2 I LIVE_AUDIT: session_end reason="stopped"')
    return "\n".join(lines) + "\n"


MIXED = """\
11-08 10:00:00.000  1 2 I LIVE_AUDIT: session_start model=gemini-live async_set=none
11-08 10:00:01.000  1 2 I LIVE_AUDIT: user final=true text="I need to call the dentist."
11-08 10:00:01.500  1 2 I LIVE_AUDIT: assistant text="Want me to remind you?"
11-08 10:00:02.000  1 2 I LIVE_AUDIT: turn_complete
11-08 10:00:03.000  1 2 I LIVE_AUDIT: user final=true text="Read my last email."
11-08 10:00:03.100  1 2 I LIVE_AUDIT: tool_call id=m1 name=read_mail async=false args={"index":0}
11-08 10:00:03.400  1 2 I LIVE_AUDIT: tool_result id=m1 name=read_mail success=true ms=300 scheduling=none sent=true response="[redacted: third-party content]"
11-08 10:00:03.600  1 2 I LIVE_AUDIT: assistant text="[redacted: third-party content]"
11-08 10:00:04.000  1 2 I LIVE_AUDIT: turn_complete
11-08 10:00:04.100  1 2 I LIVE_AUDIT: read_through source=carry
11-08 10:00:05.000  1 2 I LIVE_AUDIT: user final=true text="I should email the landlord in Quokkaville."
11-08 10:00:06.000  1 2 I LIVE_AUDIT: turn_complete
11-08 10:00:07.000  1 2 I LIVE_AUDIT: user final=true text="What is on this label?"
11-08 10:00:07.500  1 2 I LIVE_AUDIT: read_through source=look
11-08 10:00:08.000  1 2 I LIVE_AUDIT: turn_complete
11-08 10:00:09.000  1 2 I LIVE_AUDIT: user final=true text="I gotta water the neighbour's plants."
11-08 10:00:09.200  1 2 I LIVE_AUDIT: tool_call id=t1 name=manage_item async=false args={"action":"add"}
11-08 10:00:09.400  1 2 I LIVE_AUDIT: tool_result id=t1 name=manage_item success=true ms=50 scheduling=none sent=true response={}
11-08 10:00:10.000  1 2 I LIVE_AUDIT: turn_complete
11-08 10:00:11.000  1 2 I LIVE_AUDIT: user final=true typed=true text="I should renew the car registration."
11-08 10:00:12.000  1 2 I LIVE_AUDIT: turn_complete
11-08 10:00:19.000  1 2 I LIVE_AUDIT: session_end reason="stopped"
"""


def _context(client):
    response = client.get(CONTEXT)
    assert response.status_code == 200, response.data
    return response.data


def _label(context, phrase) -> str:
    for item in context["open_loops"]["own_words"]:
        if phrase in item["text"]:
            return item["label"]
    raise AssertionError("no W label has that phrase")


def _submit(client, context, loops, **extra):
    response = client.post(
        CHANGES,
        {"context_id": context["context_id"], "changes": [], "open_loops": loops, **extra},
        format="json",
    )
    assert response.status_code == 200, response.data
    return response.data


def _worker(user, name, scope):
    token, raw = DeviceToken.issue(user, f"claude-session-{name}")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    response = client.post(
        "/api/claude/machines/heartbeat/",
        {"name": name, "scope": scope, "state": "idle", "progress": {}},
        format="json",
    )
    assert response.status_code == 200, response.data
    return client


def _propose(client, household, user, said, text, **log_fields):
    _log(household, user, _says(said), **log_fields)
    context = _context(client)
    answer = _submit(client, context, [{"text": text, "evidence": [_label(context, said[:12])],
                                        "reason": "Sam mentioned a task he has not set."}])
    return answer


# =============================================================================
# His own words only
# =============================================================================


def test_own_lines_keep_only_his_words_from_clean_turns():
    lines = words.own_lines(MIXED)
    assert lines == ["I need to call the dentist.", "I should renew the car registration."]
    joined = " ".join(lines)
    assert "Want me" not in joined  # no assistant line, tool-free or not
    assert "email" not in joined  # the mail turn and the carry turn after it
    assert "label" not in joined  # a camera look
    assert "plants" not in joined  # a turn that set the task itself is a closed loop
    assert SENTINEL not in joined


def test_a_mark_logged_after_his_line_still_drops_the_turn():
    raw = _says("I need to fix the gate.").replace(
        "turn_complete", "read_through source=grounding\n11-08 10:00:01.600  1 2 I LIVE_AUDIT: turn_complete", 1
    )
    assert words.own_lines(raw) == []


def test_the_head_units_records_are_left_out(household_a, household_user):
    phone = _log(household_a, household_user, _says("I need to call the dentist."))
    unit = _log(household_a, household_user, _says("I need to call the dentist."),
                app_version_name="1.0+abc1234")
    assert words.own_text(phone) == "I need to call the dentist."
    assert words.own_text(unit) == ""


# =============================================================================
# Switches and doors
# =============================================================================


def test_off_by_default_offers_nothing_and_writes_nothing(settings, auth_client, household_a,
                                                          household_user):
    settings.LEGION_MEMORY_REVIEW = "shadow"
    _log(household_a, household_user, _says("I need to call the dentist."))
    context = _context(auth_client)
    assert context["open_loops"] == {"on": False, "why": "Proposed to-dos are off on this engine."}
    answer = _submit(auth_client, context, [{"text": "call the dentist", "evidence": ["W1"]}])
    assert answer["open_loops"]["refused"] == 1 and not OpenLoop.objects.exists()
    listing = auth_client.get(URL).data
    assert listing["on"] is False and listing["proposals"] == []


def test_a_claude_token_may_propose_but_never_answer(loops_on, household_a, household_user):
    worker = _worker(household_user, "Alienware", SCOPE_FULL)
    answer = _propose(worker, household_a, household_user, "I need to call the dentist.",
                      "call the dentist")
    assert answer["open_loops"]["proposed"] == 1
    row = OpenLoop.objects.get()
    for method, path, body in (
        ("get", URL, None),
        ("post", f"{URL}offer", None),
        ("post", f"{URL}{row.id}/track", {}),
        ("post", f"{URL}{row.id}/dismiss", {}),
        ("put", f"{URL}settings", {"proposing": False}),
    ):
        response = getattr(worker, method)(path, body, format="json")
        assert response.status_code == 403, (path, response.status_code)
    row.refresh_from_db()
    assert row.status == STATUS_PENDING


def test_the_work_laptop_and_a_recruiter_are_refused(loops_on, household_user):
    laptop = _worker(household_user, "work laptop", SCOPE_LISTED)
    assert laptop.get(URL).status_code == 403
    from datetime import timedelta as td

    from household.models import Household, HouseholdMember, User
    from household.permissions import DEMO_TOKEN_SESSION_KEY

    visit = Household.objects.create(name="Visit", is_demo_visit=True)
    visitor = User.objects.create_user(email="visitor-ol@demo.invalid")
    HouseholdMember.objects.create(user=visitor, household=visit, role=HouseholdMember.RECRUITER)
    token, _raw = DeviceToken.issue(
        visitor, "Demo visit", DeviceToken.SCOPE_READ, expires_at=timezone.now() + td(hours=2)
    )
    client = APIClient()
    client.force_login(visitor)
    session = client.session
    session[DEMO_TOKEN_SESSION_KEY] = token.pk
    session.save()
    fake = uuid.uuid4()
    for method, path in (("get", URL), ("post", f"{URL}offer"), ("post", f"{URL}{fake}/track"),
                         ("post", f"{URL}{fake}/dismiss"), ("put", f"{URL}settings")):
        response = getattr(client, method)(path, {}, format="json")
        assert response.status_code == 403, (path, response.status_code)


def test_stop_proposing_stops_extraction_and_the_list(loops_on, auth_client, household_a,
                                                      household_user):
    _propose(auth_client, household_a, household_user, "I need to call the dentist.",
             "call the dentist")
    off = auth_client.put(f"{URL}settings", {"proposing": False}, format="json")
    assert off.status_code == 200 and off.data["proposing"] is False
    listing = auth_client.get(URL).data
    assert listing["proposing"] is False and listing["proposals"] == []
    _log(household_a, household_user, _says("I need to book the vet."))
    context = _context(auth_client)
    assert context["open_loops"]["on"] is False  # his words are not even offered
    on = auth_client.put(f"{URL}settings", {"proposing": True}, format="json")
    assert on.data["proposing"] is True and len(auth_client.get(URL).data["proposals"]) == 1


# =============================================================================
# The context and the gate
# =============================================================================


def test_the_context_offers_his_clean_words_under_w_labels(loops_on, auth_client, household_a,
                                                          household_user):
    _log(household_a, household_user, MIXED)
    block = _context(auth_client)["open_loops"]
    assert block["on"] is True and block["cap"] == 5
    assert [w["label"] for w in block["own_words"]] == ["W1"]
    text = block["own_words"][0]["text"]
    assert "dentist" in text and "registration" in text
    assert "email" not in text and SENTINEL not in text and "plants" not in text
    assert set(block) == {"on", "cap", "own_words", "already_proposed"}
    assert set(block["own_words"][0]) == {"label", "text"}  # no date: nothing to say "days ago" with


def test_a_proposal_must_be_his_own_words(loops_on, auth_client, household_a, household_user):
    _log(household_a, household_user, _says("I need to call the dentist."))
    context = _context(auth_client)
    w1 = _label(context, "dentist")
    answer = _submit(auth_client, context, [
        {"text": "Book a dentist cleaning", "evidence": [w1]},
        {"text": "call the dentist", "evidence": ["W9"]},
        {"text": "call the dentist", "evidence": []},
    ])
    gates = [v["gate"] for v in answer["open_loops"]["verdicts"]]
    assert answer["open_loops"]["refused"] == 3
    assert "did not say" in gates[0] and "none of his own words" in gates[1]
    assert not OpenLoop.objects.exists()


def test_money_and_vault_numbers_are_never_proposed(loops_on, auth_client, household_a,
                                                    household_user):
    _log(household_a, household_user, _says(
        "I need to pay the plumber 40 dollars.", "I should renew passport E1234567 soon.",
    ))
    context = _context(auth_client)
    w1 = _label(context, "plumber")
    answer = _submit(auth_client, context, [
        {"text": "pay the plumber", "evidence": [w1]},
        {"text": "renew passport E1234567", "evidence": [w1]},
    ])
    gates = [v["gate"] for v in answer["open_loops"]["verdicts"]]
    assert "money" in gates[0] and "identity or bank number" in gates[1]
    assert not OpenLoop.objects.exists()


def test_a_date_is_kept_only_when_his_words_print_it(loops_on, auth_client, household_a,
                                                     household_user):
    day = _future(20)
    said = f"I need to renew the registration by {_month_day(day)}. I should call the dentist on Friday."
    _log(household_a, household_user, _says(said))
    context = _context(auth_client)
    w1 = _label(context, "registration")
    _submit(auth_client, context, [
        {"text": f"renew the registration by {_month_day(day)}", "evidence": [w1]},
        {"text": "call the dentist on Friday", "evidence": [w1]},
    ])
    dated = OpenLoop.objects.get(title__startswith="Renew")
    relative = OpenLoop.objects.get(title__startswith="Call")
    assert dated.due_date == day
    assert relative.due_date is None  # a relative day is never turned into a date


def test_a_reason_that_quotes_him_is_dropped_and_the_proposal_kept(loops_on, auth_client,
                                                                   household_a, household_user):
    said = "I really need to call the dentist about the loose crown this week."
    _log(household_a, household_user, _says(said))
    context = _context(auth_client)
    answer = _submit(auth_client, context, [{
        "text": "call the dentist", "evidence": [_label(context, "dentist")],
        "reason": "He said: call the dentist about the loose crown this week.",
    }])
    row = OpenLoop.objects.get()
    assert row.reason == "" and "quoted" in answer["open_loops"]["verdicts"][0]["gate"]
    assert len(row.evidence_logs) == 1


def test_the_cap_is_per_night_across_submits_and_waiting_words_are_read_again(
    loops_on, settings, auth_client, household_a, household_user
):
    settings.LEGION_OPEN_LOOPS_CAP = 2
    chores = ["call the dentist", "book the vet", "fix the gate"]
    logs = [_log(household_a, household_user, _says(f"I need to {c}.")) for c in chores]
    context = _context(auth_client)
    loops = [{"text": c, "evidence": [_label(context, c.split()[-1])]} for c in chores]
    answer = _submit(auth_client, context, loops)["open_loops"]
    assert (answer["proposed"], answer["deferred"]) == (2, 1)
    run = OpenLoopRun.objects.get(context_id=context["context_id"])
    assert str(logs[2].pk) not in run.read_logs  # the one that waited is offered again
    again = _context(auth_client)
    assert again["open_loops"]["cap"] == 0
    assert [w["text"] for w in again["open_loops"]["own_words"]] == ["I need to fix the gate."]


def test_a_dry_run_writes_nothing_and_reads_nothing(loops_on, auth_client, household_a,
                                                    household_user):
    _log(household_a, household_user, _says("I need to call the dentist."))
    context = _context(auth_client)
    answer = _submit(auth_client, context, [{"text": "call the dentist", "evidence": ["W1"]}],
                     dry_run=True)
    assert answer["open_loops"]["proposed"] == 1
    assert not OpenLoop.objects.exists()
    assert OpenLoopRun.objects.get(context_id=context["context_id"]).submitted_at is None


def test_the_summary_words_carry_counts_and_never_text(loops_on, auth_client, household_a,
                                                       household_user):
    answer = _propose(auth_client, household_a, household_user, "I need to call the dentist.",
                      "call the dentist")
    assert answer["words"].endswith("Open loops: 1 to-do proposed.")
    assert "dentist" not in str(answer["open_loops"]) and "dentist" not in answer["words"]


# =============================================================================
# Answered once, answered for good
# =============================================================================


def test_a_dismissed_loop_never_comes_back_however_it_is_worded(loops_on, auth_client,
                                                                household_a, household_user):
    _propose(auth_client, household_a, household_user, "I need to call the dentist.",
             "call the dentist")
    row = OpenLoop.objects.get()
    assert auth_client.post(f"{URL}{row.id}/dismiss").status_code == 200
    # Night 2: the same conversation is not offered again (the ledger)...
    night2 = _context(auth_client)
    assert night2["open_loops"]["own_words"] == []
    assert "Call the dentist" in night2["open_loops"]["already_proposed"]
    # ...and when he says it again, reworded by the helper, the key still holds.
    _log(household_a, household_user, _says("Dentist, I need to call the dentist."))
    night3 = _context(auth_client)
    answer = _submit(auth_client, night3, [
        {"text": "Dentist call", "evidence": [_label(night3, "Dentist")]},
    ])
    assert answer["open_loops"]["refused"] == 1 and "dismissed" in answer["open_loops"]["verdicts"][0]["gate"]
    assert OpenLoop.objects.count() == 1 and OpenLoop.objects.get().status == STATUS_DISMISSED


def test_something_already_on_his_list_is_not_proposed(loops_on, auth_client, household_a,
                                                       household_user):
    made = auth_client.post("/api/events", {"title": "Call the dentist", "kind": "reminder"},
                            format="json")
    assert made.status_code == 201, made.data
    answer = _propose(auth_client, household_a, household_user, "I need to call the dentist.",
                      "call the dentist")
    assert answer["open_loops"]["duplicate"] == 1 and not OpenLoop.objects.exists()


def test_a_pending_one_he_then_tracks_himself_closes_with_no_new_task(loops_on, auth_client,
                                                                      household_a, household_user):
    _propose(auth_client, household_a, household_user, "I need to call the dentist.",
             "call the dentist")
    auth_client.post("/api/events", {"title": "call dentist", "kind": "reminder"}, format="json")
    assert auth_client.get(URL).data["proposals"] == []
    row = OpenLoop.objects.get()
    assert (row.status, row.task_kind, row.task_id) == (STATUS_TRACKED, "existing", None)


def test_an_expired_one_is_gone_quietly_and_can_come_back_from_new_words(
    loops_on, auth_client, household_a, household_user
):
    _propose(auth_client, household_a, household_user, "I need to call the dentist.",
             "call the dentist")
    OpenLoop.objects.update(expires_at=timezone.now() - datetime.timedelta(minutes=1))
    assert auth_client.get(URL).data["proposals"] == []
    row = OpenLoop.objects.get()
    assert auth_client.post(f"{URL}{row.id}/track").status_code == 404
    answer = _propose(auth_client, household_a, household_user, "I still need to call the dentist.",
                      "call the dentist")
    assert answer["open_loops"]["proposed"] == 1
    assert OpenLoop.objects.count() == 1 and len(auth_client.get(URL).data["proposals"]) == 1


# =============================================================================
# His answers
# =============================================================================


def test_the_wire_never_says_when(loops_on, auth_client, household_a, household_user):
    _propose(auth_client, household_a, household_user, "I need to call the dentist.",
             "call the dentist")
    listing = auth_client.get(URL).data
    assert listing["on"] and listing["proposing"]
    assert set(listing["proposals"][0]) == {"id", "title", "due_date", "status"}
    assert listing["proposals"][0]["title"] == "Call the dentist"


def test_yes_with_no_date_lands_on_his_todo_list_once(loops_on, auth_client, household_a,
                                                      household_user):
    from checklists.models import Checklist, ChecklistItem

    _propose(auth_client, household_a, household_user, "I need to call the dentist.",
             "call the dentist")
    row = OpenLoop.objects.get()
    first = auth_client.post(f"{URL}{row.id}/track", {}, format="json")
    assert first.status_code == 200, first.data
    assert first.data["task_kind"] == "todo" and "Todo list" in first.data["words"]
    again = auth_client.post(f"{URL}{row.id}/track", {}, format="json")
    assert again.status_code == 200 and again.data["task_id"] == first.data["task_id"]
    todo = Checklist.objects.get(name="Todo")
    assert list(ChecklistItem.objects.filter(checklist=todo).values_list("text", flat=True)) == [
        "Call the dentist"
    ]
    assert auth_client.get(URL).data["proposals"] == []


def test_yes_with_a_date_he_said_is_a_dated_reminder_once(loops_on, auth_client, household_a,
                                                          household_user):
    from legacy.models import Event

    day = _future(20)
    said = f"I need to renew the registration by {_month_day(day)}."
    _propose(auth_client, household_a, household_user, said,
             f"renew the registration by {_month_day(day)}")
    row = OpenLoop.objects.get()
    assert row.due_date == day
    first = auth_client.post(f"{URL}{row.id}/track", {}, format="json")
    assert first.status_code == 200 and first.data["task_kind"] == "reminder"
    auth_client.post(f"{URL}{row.id}/track", {}, format="json")
    events = Event.objects.filter(origin_guid=f"open-loop:{row.id}")
    assert events.count() == 1
    event = events.get()
    assert event.kind == "reminder" and event.all_day and event.done is False
    assert timezone.localtime(event.starts_at).date() == day


def test_a_date_from_his_answer_is_used_and_a_past_one_refused(loops_on, auth_client, household_a,
                                                               household_user):
    _propose(auth_client, household_a, household_user, "I need to call the dentist.",
             "call the dentist")
    row = OpenLoop.objects.get()
    past = (timezone.localdate() - datetime.timedelta(days=3)).isoformat()
    refused = auth_client.post(f"{URL}{row.id}/track", {"due_date": past}, format="json")
    assert refused.status_code == 400 and "passed" in refused.data["detail"]
    day = _future(3)
    ok = auth_client.post(f"{URL}{row.id}/track", {"due_date": day.isoformat()}, format="json")
    assert ok.status_code == 200 and ok.data["task_kind"] == "reminder"
    assert ok.data["proposal"]["due_date"] == day.isoformat()


def test_no_is_for_good_and_a_dismissed_one_cannot_be_tracked(loops_on, auth_client, household_a,
                                                              household_user):
    _propose(auth_client, household_a, household_user, "I need to call the dentist.",
             "call the dentist")
    row = OpenLoop.objects.get()
    no = auth_client.post(f"{URL}{row.id}/dismiss")
    assert no.status_code == 200 and "won't be proposed again" in no.data["words"]
    late = auth_client.post(f"{URL}{row.id}/track", {}, format="json")
    assert late.status_code == 409 and "Nothing was tracked" in late.data["detail"]


def test_a_spoken_brief_carries_one_at_most_twice(loops_on, auth_client, household_a,
                                                  household_user):
    _propose(auth_client, household_a, household_user, "I need to call the dentist.",
             "call the dentist")
    counts = [len(auth_client.post(f"{URL}offer").data["proposals"]) for _ in range(3)]
    assert counts == [1, 1, 0]
    assert len(auth_client.get(URL).data["proposals"]) == 1  # still on the list


# =============================================================================
# Tenancy and privacy (ADR 0045, 0052)
# =============================================================================


def test_another_member_and_another_household_never_see_or_answer_it(
    loops_on, auth_client, household_a, household_user, household_b
):
    _propose(auth_client, household_a, household_user, "I need to call the dentist.",
             "call the dentist")
    row = OpenLoop.objects.get()
    mia = _client_for(_member(household_a, "mia@example.com"))
    lee = _client_for(_member(household_b, "lee@example.com"))
    for other in (mia, lee):
        assert other.get(URL).data["proposals"] == []
        assert other.post(f"{URL}{row.id}/track", {}, format="json").status_code == 404
        assert other.post(f"{URL}{row.id}/dismiss").status_code == 404
    row.refresh_from_db()
    assert row.status == STATUS_PENDING


def test_the_database_refuses_an_owner_from_outside_the_household(household_a, user_b):
    with pytest.raises(IntegrityError), transaction.atomic():
        OpenLoop.objects.create(
            household=household_a, owner_user=user_b, title="x", dedupe_key="x",
            expires_at=timezone.now(),
        )


def test_the_key_ignores_function_words_and_order():
    assert gate.dedupe_key("Call the dentist") == gate.dedupe_key("dentist call")
    assert gate.dedupe_key("Call the dentist") != gate.dedupe_key("call the vet")
    assert gate.names_money("transfer $40") and not gate.names_money("call the dentist")
