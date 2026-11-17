"""`household_basic`: the eval's invented household, ported from the phone's
`voiceeval/VoiceEvalFixture.kt` (plan section 16.1). Every name, course and car is made up.

Dated from `today` on the household's clock, so "due today" means the day the eval runs (or, on
replay, the day the cassette was recorded). Row ids are deterministic (uuid5 of the title), so a
recorded model call naming a row by id still names the same row when the cassette replays.

Shapes match what the engine stores: a Canvas-style deadline is `kind = task`, timed 23:59 local;
an all-day row's `starts_at` is UTC midnight of its date; a reminder is `kind = reminder`; a plain
to-do is a line on the "Todo" checklist.

**Refuses any database that is not local** (`assert_local_database`): seeding is a write, and the
image carries this module, so it must never run against the engine's own database.
"""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from django.db import connection

NAMESPACE = uuid.UUID("6f0d3a52-0d7e-4b8e-9a51-3e2b8f0e5a17")
ZONE = "America/Chicago"
LOCAL_HOSTS = frozenset({"127.0.0.1", "localhost", "::1", ""})


class Facts:
    TASK_TODAY_A = "HIST 101 reading quiz"
    TASK_TODAY_B = "ENGL 102 discussion post"
    TASK_DONE_TODAY = "ENGL 102 essay draft"
    TASK_OVERDUE = "MATH 110 homework 7"
    TASK_LATER = "CHEM 120 lab report"
    EVENT_TODAY = "Dentist cleaning"
    EVENT_TOMORROW = "PHYS 130 lecture"
    ALL_DAY_TOMORROW = "Campus career fair"
    REMINDER_SOON = "Renew the car registration"
    REMINDER_TODAY = "Call the landlord"
    TODO_A = "Buy printer ink"
    TODO_B = "Return library book"
    CAR_A = "Fit"
    CAR_B = "Ranger"


class NotLocal(RuntimeError):
    pass


def assert_local_database() -> None:
    host = (connection.settings_dict.get("HOST") or "").strip()
    if host not in LOCAL_HOSTS:
        raise NotLocal(
            "Refused: the eval seeds an invented household and runs writes, so it runs only "
            "against a local throwaway database, never this one."
        )


def rid(title: str) -> uuid.UUID:
    return uuid.uuid5(NAMESPACE, title)


def seed(household, owner, today: date, zone: ZoneInfo | None = None) -> dict:
    """Writes the invented household's rows into `household`. Returns the ids by title."""
    from checklists.models import Checklist, ChecklistItem
    from legacy.models.dates import Event
    from legacy.models.fleet import Vehicle

    assert_local_database()
    zone = zone or ZoneInfo(ZONE)
    if household.timezone != ZONE:
        household.timezone = ZONE
        household.save(update_fields=["timezone"])
    now = datetime.now(UTC)

    def at(day: date, h: int, m: int) -> datetime:
        return datetime.combine(day, time(h, m), zone)

    def utc_midnight(day: date) -> datetime:
        return datetime.combine(day, time(0, 0), UTC)

    def event(title, kind, starts_at, ends_at=None, all_day=False, done=False, location=None):
        return Event.objects.create(
            id=rid(title),
            household=household,
            title=title,
            kind=kind,
            starts_at=starts_at,
            ends_at=ends_at,
            all_day=all_day,
            location=location,
            source="legion",
            done=done,
            done_at=now if done else None,
            exact=False,
            exact_downgraded=False,
            provenance="DETERMINISTIC",
            created_at=now,
            updated_at=now,
        )

    tomorrow = today + timedelta(days=1)
    event(Facts.TASK_TODAY_A, "task", at(today, 23, 59))
    event(Facts.TASK_TODAY_B, "task", at(today, 23, 59))
    event(Facts.TASK_DONE_TODAY, "task", at(today, 23, 59), done=True)
    event(Facts.TASK_OVERDUE, "task", at(today - timedelta(days=1), 23, 59))
    event(Facts.TASK_LATER, "task", at(today + timedelta(days=3), 23, 59))
    event(
        Facts.EVENT_TODAY, "event", at(today, 15, 0), at(today, 16, 0), location="Testville Dental"
    )
    event(Facts.EVENT_TOMORROW, "event", at(tomorrow, 9, 30), at(tomorrow, 10, 45))
    event(
        Facts.ALL_DAY_TOMORROW,
        "event",
        utc_midnight(tomorrow),
        utc_midnight(tomorrow),
        all_day=True,
    )
    event(Facts.REMINDER_SOON, "reminder", at(today + timedelta(days=2), 9, 0))
    event(Facts.REMINDER_TODAY, "reminder", at(today, 18, 0))

    todo = Checklist.objects.create(id=rid("Todo"), household=household, name="Todo")
    for order, text in enumerate((Facts.TODO_A, Facts.TODO_B)):
        ChecklistItem.objects.create(
            id=rid(text), checklist=todo, household=household, text=text, sort_order=order
        )

    for name, make, model, year in (
        (Facts.CAR_A, "Honda", "Fit", 2012),
        (Facts.CAR_B, "Ford", "Ranger", 2004),
    ):
        Vehicle.objects.create(
            id=rid(name),
            household=household,
            name=name,
            make=make,
            model=model,
            year=year,
            confirmed=True,
            archived=False,
            provenance="DETERMINISTIC",
            created_at=now,
            updated_at=now,
        )
    return {"todo": str(rid("Todo"))}
