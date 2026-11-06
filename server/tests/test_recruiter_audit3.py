"""Fixes from the scripted recruiter run of 2026-11-03 (the third audit).

Each test names the answer that went wrong on the live demo.
"""

from __future__ import annotations

import pytest

from assistant import docs_search
from assistant.prompt import RECRUITER_FRAME
from procedures import runner
from tests.test_assistant import _text, _tool, browser


@pytest.fixture
def kevin_web(household_user):
    return browser(household_user)


@pytest.mark.parametrize(
    ("query", "first_path"),
    [
        # "The current documentation doesn't give a full overview of LEGION's architecture."
        ("Walk me through LEGION's architecture.", "README.md"),
        # "The documentation doesn't cover the specifics of the voice pipeline itself."
        ("How does the voice pipeline work?", "docs/architecture/c3-voice-loop.md"),
        # "The documentation doesn't specifically detail how data accuracy is maintained."
        ("How does he keep the AI from making up numbers?", "docs/architecture/c3-ingestion.md"),
        (
            "How does LEGION ensure data accuracy and prevent the AI from fabricating figures?",
            "docs/architecture/c3-ingestion.md",
        ),
    ],
)
def test_recruiter_questions_get_the_passage_that_answers_them(query, first_path):
    assert docs_search.search(query)[0].path == first_path


def test_a_narrow_question_keeps_plain_ranking():
    assert docs_search.topic_picks("How does LEGION handle music?") == ()
    assert not docs_search.is_overview("How does LEGION handle music?")


def test_a_procedure_that_reads_nhtsa_gets_time_for_it():
    # car_check stopped at 3.0 s, before its answer, while the recall lookup ran.
    plain = {"steps": [{"kind": "tool", "tool": "next_service"}]}
    online = {"steps": [{"kind": "tool", "tool": "next_service"}, {"kind": "tool", "tool": "vehicle_recalls"}]}
    assert runner._deadline(plain) == runner.BUDGET_MS * runner.DEADLINE_FACTOR
    assert runner._deadline(online) == runner._deadline(plain) + runner.NETWORK_MS
    assert runner._deadline(online) >= 9000


def test_the_recruiter_frame_covers_the_five_answers():
    assert '"Report samples"' in RECRUITER_FRAME  # "I don't have a tool for that report."
    # demo-modals (Kevin, 2026-11-05): the resume opens over the page instead of "it is on the
    # page"; `tests/test_demo_modals.py` pins the rule.
    assert "call show_resume" in RECRUITER_FRAME
    assert "last month's figure" in RECRUITER_FRAME  # November had no categorised spend yet
    assert '"unverified" included' in RECRUITER_FRAME  # the caveat it dropped
    assert "no medical disclaimer" in RECRUITER_FRAME  # "Not medical advice or diagnosis."


def test_event_times_come_back_in_the_household_zone(kevin_web, household_a):
    # "Pick up dry cleaning is on for Wednesday at ten thirty at night": 5:30 pm New York,
    # read out as 22:30 UTC.
    household_a.timezone = "America/New_York"
    household_a.save(update_fields=["timezone"])
    _text(
        _tool(
            kevin_web,
            "add_event",
            {"fields": {"title": "Pick up dry cleaning", "starts_at": "2026-11-04T22:30:00Z"}},
        )
    )
    listed = _text(_tool(kevin_web, "list_events", {"from": "2026-11-01", "to": "2026-11-10"}))
    assert "2026-11-04T17:30:00-05:00" in listed
    assert "22:30" not in listed
    assert "household's local time" in listed
