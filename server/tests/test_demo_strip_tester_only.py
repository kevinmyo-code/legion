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
