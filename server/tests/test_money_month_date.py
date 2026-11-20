# ruff: noqa: F811 - `engine` and `member` are fixtures imported from the cascade tests
"""One money lookup, not two (perf/try-faster-first-word fix 2). Recorded on a real /try run
(2026-11-19, invented demo household): asked "What did we spend last month?", the model called
`monthly_spend` with `{"month": "2026-10-01"}` (a full date), the door refused it ("does not match
'^[0-9]{4}-[0-9]{2}$'"), and the model asked again with `{"month": "2026-10"}`: a whole extra
model round, about a second. A full date now names its month, for every tool that takes `month`.
Invented household only."""

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, call, say
from tests.test_assistant import _tool, browser
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _of,
    _post,
    engine,
    member,
)

pytestmark = pytest.mark.django_db

MONTH_TOOLS = ("monthly_spend", "cash_flow", "budgets")


@pytest.mark.parametrize("tool", MONTH_TOOLS)
def test_a_full_date_names_its_month(household_user, tool):
    client = browser(household_user)
    by_date = _tool(client, tool, {"month": "2026-10-01"})
    by_month = _tool(client, tool, {"month": "2026-10"})
    assert by_date.status_code == 200
    assert "does not match" not in by_date.data["text"]
    assert by_date.data["is_error"] is by_month.data["is_error"]
    assert by_date.data["text"] == by_month.data["text"]


@pytest.mark.parametrize("bad", ["2026-1", "Oct 2026", "2026-10-01T00:00", "2026-10-1"])
def test_anything_else_is_still_refused(household_user, bad):
    response = _tool(browser(household_user), "monthly_spend", {"month": bad})
    assert response.data["is_error"] is True


def test_the_recorded_turn_takes_one_lookup(member, engine):
    # The recorded shape: the date-shaped month first. One call is now enough.
    engine.model = FakeModel(
        [call("monthly_spend", {"month": "2026-10-01"}), say("Here is last month.")]
    )
    events = _events(_post(member, text="What did we spend last month?"))
    tools = _of(events, "tool")
    assert [(t["name"], t["ok"]) for t in tools] == [("monthly_spend", True)]


@pytest.mark.parametrize("tool", MONTH_TOOLS)
@pytest.mark.parametrize("impossible", ["2026-02-31", "2026-10-99", "2026-13-01"])
def test_a_date_that_does_not_exist_is_refused_in_words(household_user, tool, impossible):
    response = _tool(browser(household_user), tool, {"month": impossible})
    assert response.data["is_error"] is True
    assert impossible in response.data["text"]
