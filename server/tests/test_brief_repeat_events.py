"""The members' morning briefing said a task twice (parked 2026-11-04: "members' morning
briefing repeating events").

`morning_briefing` says the member's stored brief and then today's events. The brief's Canvas
section lists every task due in the next 7 days (`brief/compose.py`), and `day_events` lists
today's tasks again from `list_events`, so a task due later today was said in both. A task the
brief already said, the same row at the same start, is now said once; a different row with the
same title is still said, and so is a task the brief could not have said (made after it was
built, or moved since).
"""

from __future__ import annotations

import datetime as dt
from zoneinfo import ZoneInfo

import pytest

from brief.build import build_for_household
from brief.news import NewsResult
from procedures import steps
from tests.conftest import _member
from tests.test_engine_mcp import call, client_with
from tests.test_parity_tools import _ok

pytestmark = pytest.mark.django_db
ZONE = "America/Chicago"


def _news(_day):
    return NewsResult(
        section={"key": "news", "title": "News", "status": "ok", "lines": []}, ok=True
    )


def _today() -> dt.date:
    return dt.datetime.now(ZoneInfo(ZONE)).date()


def _at(hour: int, minute: int = 0) -> str:
    return dt.datetime.combine(_today(), dt.time(hour, minute), tzinfo=ZoneInfo(ZONE)).isoformat()


@pytest.fixture
def household(settings, household_a, household_user, auth_client):
    """Two members, a one-off event, a weekly series that falls today, and a task due late
    today, all shared, with the 05:00 brief built for both members."""
    settings.LEGION_MCP = True
    household_a.timezone = ZONE
    household_a.save(update_fields=["timezone"])
    mia = _member(household_a, "mia@example.com")
    weekday = _today().strftime("%A").upper()
    for body in (
        {"title": "Dentist", "starts_at": _at(9, 30), "kind": "event"},
        {"title": "MATH 2414 homework", "starts_at": _at(23, 59), "kind": "task"},
        {
            "title": "Run club",
            "starts_at": (
                dt.datetime.fromisoformat(_at(18, 30)) - dt.timedelta(days=21)
            ).isoformat(),
            "repeat_kind": "WEEKLY",
            "repeat_every": 1,
            "repeat_days_of_week": weekday,
            "repeat_end_kind": "NEVER",
        },
    ):
        _ok(auth_client.post("/api/events", body, format="json"))
    # Built at 05:00 today, as the job does, so a task due later today is in its Canvas section.
    now = dt.datetime.fromisoformat(_at(5, 0))
    build_for_household(household_a, "scheduled", now=now, news_builder=_news)
    return {"kevin": household_user, "mia": mia, "api": auth_client}


def _briefing(user) -> str:
    is_error, text, _ = call(client_with(user), "run_procedure", {"name": "morning_briefing"})
    assert not is_error, text
    return text


@pytest.mark.parametrize("who", ["kevin", "mia"])
def test_each_member_hears_a_task_due_today_once(household, who):
    text = _briefing(household[who])
    assert "Canvas: MATH 2414 homework: due " in text  # the brief says it
    assert text.count("MATH 2414 homework") == 1, text
    assert text.count("Dentist") == 1, text
    assert text.count("Run club") == 1, text


def test_a_different_task_with_the_same_title_is_still_said(household):
    # Made after the brief was built, so the brief never said it: a different row, its own
    # start, the same title.
    _ok(
        household["api"].post(
            "/api/events",
            {"title": "MATH 2414 homework", "starts_at": _at(23, 30), "kind": "task"},
            format="json",
        )
    )
    text = _briefing(household["kevin"])
    assert text.count("MATH 2414 homework") == 2, text
    assert "Tasks dated today: MATH 2414 homework at 11:30 PM." in text


def test_a_task_moved_since_the_brief_is_said_at_its_new_time(household):
    from legacy.models.dates import Event

    task = Event.objects.get(title="MATH 2414 homework")
    task.starts_at = dt.datetime.fromisoformat(_at(22, 0))
    task.save(update_fields=["starts_at"])
    text = _briefing(household["kevin"])
    assert "Tasks dated today: MATH 2414 homework at 10:00 PM." in text


def test_a_brief_from_an_earlier_day_drops_nothing(household):
    from brief.models import Brief

    Brief.objects.update(brief_date=_today() - dt.timedelta(days=1))
    text = _briefing(household["kevin"])
    assert "Tasks dated today: MATH 2414 homework at 11:59 PM." in text


def test_day_events_skips_only_the_same_row_at_the_same_start():
    row = {"id": "a", "title": "Quiz", "kind": "task", "starts_at": "2026-11-05T23:59:00-06:00"}
    same_title = {**row, "id": "b"}
    said = [{"event_id": "a", "starts_at": "2026-11-06T05:59:00+00:00"}]
    text = steps.day_events({"in_window": [row, same_title]}, -360, said=said)
    assert "Tasks dated today: Quiz at 11:59 PM." in text
    assert text.count("Quiz") == 1
    assert steps.day_events({"in_window": [row]}, -360).count("Quiz") == 1
