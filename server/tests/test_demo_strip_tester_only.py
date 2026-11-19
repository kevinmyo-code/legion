# ruff: noqa: F811 - fixtures imported from the cascade tests
"""The demo's live strip (tool names with their times, the turn's estimated cost, the "Sounded
robotic" button) is Kevin's own tester visit's, never a recruiter's (Kevin, 2026-11-19: "B hide the
whole strip"). Decided on the server: an ordinary visit's turn carries neither `cost_usd` nor
`tools_ms`, and the robotic route refuses it, so nothing on the page can show or drive them. The
follow-up chips' `offer` is unchanged for both.

Google is never called: the text model is `FakeModel`. Every visitor and word here is invented.
"""

from __future__ import annotations

from datetime import timedelta

import pytest
from django.utils import timezone

from agents.runtime.model import FakeModel, call, say
from demo.models import DemoCode, DemoVisit
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _of,
    _post,
    engine,
    visitor,
    visitor_user,
)

pytestmark = pytest.mark.django_db


def make_visit(user, unmetered: bool) -> None:
    """The visit row `/api/demo/enter` writes, on a tester (unmetered) or an ordinary code."""
    code, _raw = DemoCode.rotate(unmetered=unmetered)
    DemoVisit.objects.create(user=user, code=code, expires_at=timezone.now() + timedelta(hours=1))


def test_an_ordinary_visit_turn_carries_no_cost_and_no_tool_times(visitor, visitor_user, engine):
    make_visit(visitor_user, unmetered=False)
    engine.model = FakeModel([call("next_service", {}), say("Due in May.")])
    events = _events(_post(visitor, text="When is the car due?"))
    done = _of(events, "done")[0]
    assert "cost_usd" not in done
    assert "tools_ms" not in done
    # Nor each tool's own time: the tool event still says what ran (its card), not how long.
    tools = _of(events, "tool")
    assert [t["name"] for t in tools] == ["next_service"] and "ms" not in tools[0]


def test_a_visit_with_no_visit_row_is_ordinary_too(visitor, engine):
    """No `DemoVisit` at all (an old visit) is not the tester's: fail closed."""
    engine.model = FakeModel([say("Hello.")])
    done = _of(_events(_post(visitor, text="Hi.")), "done")[0]
    assert "cost_usd" not in done and "tools_ms" not in done


def test_the_tester_visit_turn_carries_both(visitor, visitor_user, engine):
    make_visit(visitor_user, unmetered=True)
    engine.model = FakeModel([call("next_service", {}), say("Due in May.")])
    events = _events(_post(visitor, text="When is the car due?"))
    done = _of(events, "done")[0]
    assert isinstance(done["cost_usd"], float) and done["cost_usd"] >= 0
    assert [name for name, _ms in done["tools_ms"]] == ["next_service"]
    assert isinstance(_of(events, "tool")[0]["ms"], int)


@pytest.mark.parametrize("unmetered", [False, True])
def test_the_chips_offer_is_the_same_for_both(visitor, visitor_user, engine, unmetered):
    from household.demo_seed import seed_household
    from household.models import HouseholdMember

    seed_household(HouseholdMember.objects.get(user=visitor_user).household, created=True)
    make_visit(visitor_user, unmetered=unmetered)
    engine.model = FakeModel([call("monthly_spend", {}), say("About the usual.")])
    done = _of(_events(_post(visitor, text="What did they spend?")), "done")[0]
    assert isinstance(done.get("offer"), str) and done["offer"]


def test_the_robotic_route_refuses_an_ordinary_visit(visitor, visitor_user, engine):
    make_visit(visitor_user, unmetered=False)
    engine.model = FakeModel([say("Hello.")])
    conversation = _of(_events(_post(visitor, text="Hi.")), "turn")[0]["conversation_id"]
    response = visitor.post(
        "/api/assistant/robotic", {"conversation_id": conversation, "turn": 0}, format="json"
    )
    assert response.status_code == 403
    assert response.json()["code"] == "tester_only"
    assert response.json()["detail"]


def test_the_robotic_route_takes_the_tester_visit(visitor, visitor_user, engine):
    make_visit(visitor_user, unmetered=True)
    engine.model = FakeModel([say("Hello.")])
    conversation = _of(_events(_post(visitor, text="Hi.")), "turn")[0]["conversation_id"]
    response = visitor.post(
        "/api/assistant/robotic", {"conversation_id": conversation, "turn": 0}, format="json"
    )
    assert response.status_code == 204


# ---- review 2026-11-19: the rest of the strip's numbers and its spoken marker -------------------


def _tour_events(client) -> list[tuple[str, dict]]:
    """Every beat of the tour, as the page plays it."""
    out, beat, conversation = [], 0, None
    for _ in range(10):
        fields = {"tour": beat}
        if conversation:
            fields["conversation_id"] = conversation
        events = _events(_post(client, **fields))
        out += events
        conversation = _of(events, "turn")[0]["conversation_id"]
        done = _of(events, "done")[0]
        if done["tour"]["next"] is None:
            break
        beat = done["tour"]["next"]
    return out


def test_tour_beats_on_an_ordinary_visit_carry_no_cost_and_no_tool_times(
    visitor, visitor_user, engine, tmp_path
):
    from tests.test_recruiter_content import load_fake

    load_fake(tmp_path)
    make_visit(visitor_user, unmetered=False)
    events = _tour_events(visitor)
    tools = _of(events, "tool")
    assert tools, "the tour ran no tool: nothing was checked"
    assert all("ms" not in t for t in tools)
    assert all("cost_usd" not in d and "tools_ms" not in d for d in _of(events, "done"))


def test_tour_beats_on_the_tester_visit_keep_each_tool_time(visitor, visitor_user, engine, tmp_path):
    from tests.test_recruiter_content import load_fake

    load_fake(tmp_path)
    make_visit(visitor_user, unmetered=True)
    tools = _of(_tour_events(visitor), "tool")
    assert tools and all(isinstance(t["ms"], int) for t in tools)


def test_saying_it_sounded_robotic_on_an_ordinary_visit_marks_nothing(visitor, visitor_user, engine):
    from assistant import cascade
    from tests.test_assistant_cascade import _record

    make_visit(visitor_user, unmetered=False)
    engine.model = FakeModel([say("Hello.")])
    conversation = _of(_events(_post(visitor, text="Hi.")), "turn")[0]["conversation_id"]
    engine.model = FakeModel([say("Sorry to hear that.")])
    events = _events(_post(visitor, text="That sounded robotic.", conversation_id=conversation))
    # Just words: answered like any other, no marker, no fixed acknowledgement.
    assert _of(events, "done")[0]["status"] != "robotic"
    assert cascade.ROBOTIC_ACK not in [s["text"] for s in _of(events, "sentence")]
    assert "robotic turn=" not in _record(conversation)


def test_the_transcribe_route_gives_an_ordinary_visit_the_words_without_their_time(
    visitor, visitor_user, engine, monkeypatch
):
    from assistant import voice_engine
    from django.core.files.uploadedfile import SimpleUploadedFile

    from tests.test_assistant_cascade import PCM

    monkeypatch.setenv(voice_engine.EARLY_TRANSCRIPTS_ENV, "on")
    engine.speech.heard = "How is it built"

    def transcribe():
        return visitor.post(
            "/api/assistant/transcribe",
            {"audio": SimpleUploadedFile("s.pcm", PCM, "application/octet-stream")},
            format="multipart",
        )

    make_visit(visitor_user, unmetered=False)
    response = transcribe()
    assert response.status_code == 200, response.data
    assert response.data == {"text": "How is it built"}


def test_a_revoked_tester_code_stops_showing_the_strip_on_a_visit_already_open(
    visitor, visitor_user, engine
):
    from django.utils import timezone as tz

    make_visit(visitor_user, unmetered=True)
    DemoCode.objects.filter(unmetered=True).update(revoked_at=tz.now())
    engine.model = FakeModel([say("Hello.")])
    done = _of(_events(_post(visitor, text="Hi.")), "done")[0]
    assert "cost_usd" not in done
