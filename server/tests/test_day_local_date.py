"""The engine's day words and "due" on the household's LOCAL day.

On an invented Tuesday evening, 21:40 Central (already Wednesday in UTC), "today" and "tonight" are
the Tuesday; just after local midnight they are the new day; in a zone ahead of UTC the local day
leads. A first-posted discussion due later that evening is still due: Canvas marks a discussion
submitted at its first post, but its row's due date is the replies'. Titles and times are invented.
"""

from __future__ import annotations

import datetime as dt
import uuid
from types import SimpleNamespace
from zoneinfo import ZoneInfo

import pytest

from agents.legion import twins
from engine_mcp import tools as reg
from ingest.canvas_meta import first_post_only, handed_in

CHICAGO = ZoneInfo("America/Chicago")
TUESDAY = dt.date(2027, 3, 9)
EVENING = dt.datetime(2027, 3, 10, 3, 40, tzinfo=dt.UTC)  # 21:40 Central on the Tuesday
FIRST_POST_IN = {
    "canvas_assignment_id": 7,
    "submission_types": "discussion_topic",
    "canvas_submitted": True,
    "submitted_at": "2027-03-07T09:30:00Z",
}


def _row(meta, starts_at, *, all_day=False, title="Invented course - Week 9 discussion"):
    return SimpleNamespace(
        id=uuid.uuid4(),
        title=title,
        starts_at=starts_at,
        deleted_at=None,
        kind="task",
        all_day=all_day,
        done=False,
        structured_meta=meta,
        repeat_kind=None,
    )


@pytest.mark.parametrize(
    ("utc", "zone", "today"),
    [
        ("2027-03-10T03:40:00+00:00", CHICAGO, TUESDAY),  # 21:40 Central
        ("2027-03-10T06:15:00+00:00", CHICAGO, TUESDAY + dt.timedelta(days=1)),  # 00:15 Central
        ("2027-03-09T23:30:00+00:00", ZoneInfo("Asia/Singapore"), TUESDAY + dt.timedelta(days=1)),
    ],
)
def test_today_and_tonight_are_the_household_day(monkeypatch, utc, zone, today):
    monkeypatch.setattr(reg, "_now", lambda: dt.datetime.fromisoformat(utc))
    assert reg._named_day("today", zone) == today
    assert reg._named_day(" Tonight ", zone) == today
    assert reg._named_day("tomorrow", zone) == today + dt.timedelta(days=1)


def test_a_discussion_with_only_its_first_post_in_is_not_handed_in():
    assert not handed_in(FIRST_POST_IN)
    both = {**FIRST_POST_IN, "submission_types": "online_text_entry,discussion_topic"}
    assert not handed_in(both)
    assert first_post_only(FIRST_POST_IN) and first_post_only(both)


def test_the_first_post_row_an_assignment_and_an_excused_discussion_keep_canvas_word():
    sub_row = {
        "parent_canvas_assignment_id": 7,
        "sub_deadline": "first_post",
        "canvas_submitted": True,
    }
    assert handed_in(sub_row) and not first_post_only(sub_row)
    assert handed_in({"submission_types": "online_upload", "canvas_submitted": True})
    assert handed_in({"submission_types": "online_upload", "submitted_at": "2027-03-07T09:30:00Z"})
    assert handed_in({"submission_types": "discussion_topic", "excused": True})
    assert not handed_in({"submission_types": "online_upload", "canvas_submitted": False})
    assert not handed_in(None)
    assert not first_post_only({"submission_types": "online_upload", "canvas_submitted": True})
    assert not first_post_only({"submission_types": "discussion_topic", "canvas_submitted": False})


def test_the_twin_counts_this_evenings_discussion_open_today():
    due = dt.datetime(2027, 3, 9, 22, 30, tzinfo=CHICAGO)
    item = twins._input(_row(FIRST_POST_IN, due), CHICAGO, EVENING)
    assert item.date == TUESDAY
    assert item.done is False
    summary = twins.summarize([item], TUESDAY, TUESDAY, TUESDAY, EVENING)
    assert [e.item.title for e in summary.open] == ["Invented course - Week 9 discussion"]
    assert summary.done_count == 0
    wednesday = TUESDAY + dt.timedelta(days=1)
    assert twins.summarize([item], wednesday, wednesday, TUESDAY, EVENING).open == []


def test_an_all_day_discussion_is_due_until_the_end_of_its_own_day():
    # An all-day row is stored at UTC midnight of its date.
    row = _row(FIRST_POST_IN, dt.datetime(2027, 3, 9, tzinfo=dt.UTC), all_day=True)
    assert twins._due_at(row, CHICAGO) == dt.datetime(
        2027, 3, 9, 23, 59, 59, 999000, tzinfo=CHICAGO
    )
    assert twins._input(row, CHICAGO, EVENING).done is False


def test_a_discussion_past_its_due_time_with_its_post_in_is_settled_not_overdue():
    last_week = dt.datetime(2027, 3, 2, 22, 30, tzinfo=CHICAGO)
    posted = twins._input(_row(FIRST_POST_IN, last_week), CHICAGO, EVENING)
    assert posted.done is True
    assert twins.summarize([posted], TUESDAY, TUESDAY, TUESDAY, EVENING).open == []
    not_in = {"submission_types": "discussion_topic", "canvas_submitted": False}
    late_item = twins._input(_row(not_in, last_week), CHICAGO, EVENING)
    late = twins.summarize([late_item], TUESDAY, TUESDAY, TUESDAY, EVENING)
    assert [e.overdue for e in late.open] == [True]
    assert handed_in(FIRST_POST_IN, past_due=True)
