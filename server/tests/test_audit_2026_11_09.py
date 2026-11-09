"""Engine fixes from the conversation-log audit of 2026-11-09.

1. Web, 11-08: "whats due today" got "Nothing is due today." The model sent
   `list_events({"from":"2026-11-08T00:00:00","to":"2026-11-08T23:59:59"})`, a local date-time
   with no offset; the engine read it as UTC and the window moved back 6 h, cutting off the
   23:59 deadlines. A date-time with no offset is now the household's own clock, in every tool
   that takes one (reads and writes).
2. Web, 11-07: "Remind me to go get my medicine today" saved
   `add_event({"fields":{"kind":"reminder","title":"Get medicine"}})` with no date, so nothing
   would ever come up. A reminder with no day is refused in words, and a day the model does
   pass ("today", "tomorrow", YYYY-MM-DD) is the household's own day, all-day.
"""

from __future__ import annotations

import datetime as dt
from zoneinfo import ZoneInfo

import pytest

from tests.test_cards import _add, chicago  # noqa: F401 - fixture

CHICAGO = ZoneInfo("America/Chicago")

# =============================================================================
# 1. A date-time with no offset is the household's local time
# =============================================================================


@pytest.mark.django_db
def test_whats_due_today_with_naive_local_datetimes_keeps_the_2359_deadline(chicago):  # noqa: F811
    # The exact 11-08 call. A quiz due 23:59 CST Nov 8 is 05:59Z Nov 9.
    _add(chicago, "Quiz 9", "2026-11-09T05:59:00Z")
    # 17:59 CST Nov 7 is 23:59Z Nov 7: the evening before, outside the day.
    _add(chicago, "Yesterday's thing", "2026-11-07T23:59:00Z")
    listed = chicago("list_events", {"from": "2026-11-08T00:00:00", "to": "2026-11-08T23:59:59"})
    assert listed["is_error"] is False, listed["text"]
    # Nov 8 is after the Nov 1 fall-back, so CST, -06:00.
    assert "2026-11-08T00:00:00-06:00 to 2026-11-08T23:59:59-06:00" in listed["text"]
    assert "Quiz 9" in listed["text"]
    assert "Yesterday's thing" not in listed["text"]
    assert [i["title"] for i in listed["card"]["items"]] == ["Quiz 9"]


@pytest.mark.django_db
def test_a_datetime_with_an_offset_is_still_taken_as_given(chicago):  # noqa: F811
    _add(chicago, "Quiz 9", "2026-11-09T05:59:00Z")
    listed = chicago("list_events", {"from": "2026-11-08T00:00:00Z", "to": "2026-11-08T23:59:59Z"})
    assert "Quiz 9" not in listed["text"]


@pytest.mark.django_db
def test_add_event_with_a_naive_start_is_the_households_clock(chicago):  # noqa: F811
    from legacy.models.dates import Event

    _add(chicago, "Dentist", "2026-11-10T15:00:00")
    stored = Event.objects.get(title="Dentist")
    # 15:00 CST is 21:00Z, never 15:00Z (09:00 in Chicago).
    assert stored.starts_at == dt.datetime(2026, 11, 10, 21, 0, tzinfo=dt.UTC)
    assert stored.all_day is False


@pytest.mark.django_db
def test_update_event_with_a_naive_start_is_the_households_clock(chicago):  # noqa: F811
    from legacy.models.dates import Event

    _add(chicago, "Dentist", "2026-11-10T21:00:00Z")
    event = Event.objects.get(title="Dentist")
    moved = chicago(
        "update_event", {"id": str(event.pk), "fields": {"starts_at": "2026-11-11T09:30:00"}}
    )
    assert moved["is_error"] is False, moved["text"]
    event.refresh_from_db()
    assert event.starts_at == dt.datetime(2026, 11, 11, 15, 30, tzinfo=dt.UTC)


# =============================================================================
# 2. A reminder needs a day, and "today" is the household's day
# =============================================================================


@pytest.mark.django_db
def test_the_11_07_medicine_reminder_with_no_day_is_refused_in_words(chicago):  # noqa: F811
    from legacy.models.dates import Event

    made = chicago("add_event", {"fields": {"kind": "reminder", "title": "Get medicine"}})
    assert made["is_error"] is True
    assert made["text"].startswith("Nothing was written.")
    assert "today" in made["text"] and "starts_at" in made["text"]
    assert not Event.objects.filter(title="Get medicine").exists()


@pytest.mark.django_db
def test_a_lead_time_with_no_day_is_refused_too(chicago):  # noqa: F811
    made = chicago("add_event", {"fields": {"title": "Get medicine", "remind_minutes_before": 30}})
    assert made["is_error"] is True


@pytest.mark.django_db
def test_an_undated_item_with_no_kind_still_writes(chicago):  # noqa: F811
    made = chicago("add_event", {"fields": {"title": "MOT"}})
    assert made["is_error"] is False, made["text"]


@pytest.mark.django_db
def test_today_is_the_households_own_day_all_day(chicago, monkeypatch):  # noqa: F811
    from legacy.models.dates import Event

    # 20:44 CST Nov 7 is 02:44Z Nov 8: UTC's "today" would be a day late.
    fixed = dt.datetime(2026, 11, 8, 2, 44, tzinfo=dt.UTC)
    monkeypatch.setattr("engine_mcp.tools._now", lambda: fixed)
    made = chicago(
        "add_event",
        {"fields": {"kind": "reminder", "title": "Get medicine", "starts_at": "today"}},
    )
    assert made["is_error"] is False, made["text"]
    stored = Event.objects.get(title="Get medicine")
    assert stored.all_day is True
    # An all-day row's starts_at is UTC midnight of the date it names.
    assert stored.starts_at == dt.datetime(2026, 11, 7, 0, 0, tzinfo=dt.UTC)
    listed = chicago("list_events", {"from": "2026-11-07", "to": "2026-11-07"})
    assert "Get medicine" in listed["text"]


@pytest.mark.django_db
def test_tomorrow_and_a_bare_date_are_all_day_on_that_date(chicago, monkeypatch):  # noqa: F811
    from legacy.models.dates import Event

    fixed = dt.datetime(2026, 11, 8, 2, 44, tzinfo=dt.UTC)  # Nov 7 in Chicago
    monkeypatch.setattr("engine_mcp.tools._now", lambda: fixed)
    chicago("add_event", {"fields": {"title": "Pharmacy", "starts_at": "Tomorrow"}})
    chicago("add_event", {"fields": {"title": "Rent", "starts_at": "2026-12-01"}})
    pharmacy = Event.objects.get(title="Pharmacy")
    rent = Event.objects.get(title="Rent")
    assert (pharmacy.all_day, pharmacy.starts_at) == (
        True,
        dt.datetime(2026, 11, 8, 0, 0, tzinfo=dt.UTC),
    )
    assert (rent.all_day, rent.starts_at) == (True, dt.datetime(2026, 12, 1, 0, 0, tzinfo=dt.UTC))


@pytest.mark.django_db
def test_list_events_takes_today_on_the_households_clock(chicago, monkeypatch):  # noqa: F811
    fixed = dt.datetime(2026, 11, 8, 17, 19, tzinfo=dt.UTC)  # 11:19 CST Nov 8
    monkeypatch.setattr("engine_mcp.tools._now", lambda: fixed)
    _add(chicago, "Quiz 9", "2026-11-09T05:59:00Z")
    listed = chicago("list_events", {"from": "today", "to": "today"})
    assert listed["is_error"] is False, listed["text"]
    assert "Quiz 9" in listed["text"]
