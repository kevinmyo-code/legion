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


def test_saying_it_sounded_robotic_on_an_ordinary_visit_marks_nothing(
    visitor, visitor_user, engine
):
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
    from django.core.files.uploadedfile import SimpleUploadedFile

    from assistant import voice_engine
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


# ---- ship/try-speed-media-1119: the strip rule beside perf/try-first-word's lookup lines ---------
# That branch added a delayed lookup line (a `filler` event after its tool's own event), the
# `waiting` moments that time it, and `first_sound_ms` / `filler_ms` on the timing line. None of
# them may carry a time or a cost to an ordinary visit.

STRIP_KEYS = {"ms", "cost_usd", "tools_ms", "first_sound_ms", "filler_ms", "tts_first_audio_ms"}


def _slow_lookup_turn(client, engine, monkeypatch, user):
    """A later spoken turn (the first question's acknowledgement behind it) whose lookup returns
    at once and whose answer round is silent for about a second: the delayed line falls due."""
    from household.demo_seed import seed_household
    from household.models import HouseholdMember
    from tests.test_addressed import _warm
    from tests.test_assistant_cascade import PCM
    from tests.test_filler_every_turn import SlowModel

    # A seeded visit household, so the lookup comes back ok (a failed lookup gets no line).
    seed_household(HouseholdMember.objects.get(user=user).household, created=True)
    _warm(monkeypatch, engine, user)
    engine.speech.heard = "Hello there."
    engine.model = FakeModel([say("Hello.")])
    first = _events(_post(client, audio=PCM))
    conversation = _of(first, "turn")[0]["conversation_id"]
    engine.speech.heard = "When is the car due?"
    engine.model = SlowModel([call("next_service", {}), say("Due in May.")], [0.0, 1.0])
    return _events(_post(client, audio=PCM, conversation_id=conversation))


def test_an_ordinary_visit_with_a_delayed_lookup_line_gets_no_time_or_cost_anywhere(
    visitor, visitor_user, engine, monkeypatch
):
    make_visit(visitor_user, unmetered=False)
    events = _slow_lookup_turn(visitor, engine, monkeypatch, visitor_user)
    kinds = [k for k, _ in events]
    # The line itself is still said (the speed fix is for every visitor) ...
    assert [f["tool"] for f in _of(events, "filler")] == ["next_service"]
    assert kinds.index("tool") < kinds.index("filler")
    assert "waiting" not in kinds
    # ... but no event of any kind carries a time or the turn's cost.
    for kind, data in events:
        leaked = STRIP_KEYS & set(data)
        assert not leaked, (kind, leaked)


def test_the_tester_visit_with_a_delayed_lookup_line_keeps_its_tool_time(
    visitor, visitor_user, engine, monkeypatch
):
    make_visit(visitor_user, unmetered=True)
    events = _slow_lookup_turn(visitor, engine, monkeypatch, visitor_user)
    assert [f["tool"] for f in _of(events, "filler")] == ["next_service"]
    assert isinstance(_of(events, "tool")[0]["ms"], int)
    done = _of(events, "done")[0]
    assert [name for name, _ms in done["tools_ms"]] == ["next_service"]
    # The timing line's fields stay on the engine's own log, never on the page.
    assert all("filler_ms" not in d and "first_sound_ms" not in d for _k, d in events)
