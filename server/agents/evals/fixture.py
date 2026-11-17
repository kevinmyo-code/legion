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
    # engine-brain ticket 03: the homes (goals, pending, advice, raises) and one ledger account.
    ACCOUNT = "Everyday card 4242"
    GOAL_RUN = "Run a 10k before spring"
    GOAL_SAVE = "Save 2000 dollars for a bike"
    GOAL_DONE = "Finish the first aid course"
    PENDING = "Coffee at the campus cafe"
    RAISE_REASON = "a severe thunderstorm warning covers Testville until 9 pm"
    ADVICE_GIST = "Keep long runs easy, one a week"
    # engine-brain ticket 15: trackers he set up himself. "Guitar practice" sits in no domain that
    # has built-in tools; "Lifting" sits next to the body aspect's workouts on purpose (reachable
    # when he says "tracker").
    TRACKER = "Guitar practice"
    TRACKER_TYPE = "Session"
    SESSION_A = "Blackbird"
    SESSION_B = "Major scales warmup"
    NEAR_TRACKER = "Lifting"
    NEAR_TYPE = "Lift"
    NEAR_ENTRY = "Deadlift"


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
    _seed_homes(household, owner, today, now)
    _seed_records(household, today, zone, now)
    return {"todo": str(rid("Todo"))}


def _seed_homes(household, owner, today: date, now: datetime) -> None:
    """Ticket 03's rows: two active goals and a closed one, a ledger account, a pending charge,
    a piece of advice and the last thing Alfred raised on its own. All invented."""
    from homes.models import AdvisorAdvice, PendingTransaction, ProactiveRaise
    from legacy.enums import Provenance
    from legacy.models.body import Goal
    from legacy.models.ledger import LedgerTransaction

    for n, (statement, aspect, target, unit, status) in enumerate(
        (
            (Facts.GOAL_RUN, "bio", 10, "km", "active"),
            (Facts.GOAL_SAVE, "cred", 2000, "USD", "active"),
            (Facts.GOAL_DONE, "bio", None, None, "achieved"),
        )
    ):
        Goal.objects.create(
            id=rid(statement),
            household=household,
            lineage_id=1000 + n,
            aspect=aspect,
            statement=statement,
            target_value=target,
            unit=unit,
            status=status,
            closed_at=now if status != "active" else None,
            created_at_client=now - timedelta(days=30 - n),
            provenance=Provenance.USER,
            created_at=now,
            updated_at=now,
            origin_guid=str(rid(statement)),
        )
    LedgerTransaction.objects.create(
        id=rid("ledger row"),
        household=household,
        statement=None,
        account_last4="4242",
        account_nickname=Facts.ACCOUNT,
        currency="USD",
        txn_date=today - timedelta(days=3),
        description="Testville Grocer",
        amount_cents=-2312,
        balance_cents=None,
        line_ref="fixture:1",
        category=None,
        category_pending=False,
        provenance=Provenance.BANK_API,
        created_at=now,
        bank_transaction_id="fixture-txn-1",
        bank_pending=False,
    )
    PendingTransaction.objects.create(
        id=rid(Facts.PENDING),
        household=household,
        origin_guid=str(rid(Facts.PENDING)),
        account_id=Facts.ACCOUNT,
        currency="USD",
        txn_date=today,
        description=Facts.PENDING,
        amount_cents=-650,
        logged_at=now - timedelta(hours=2),
    )
    AdvisorAdvice.objects.create(
        id=rid(Facts.ADVICE_GIST),
        household=household,
        owner_user=owner,
        origin_guid=str(rid(Facts.ADVICE_GIST)),
        aspect="bio",
        question_text="How should I train for the 10k?",
        gist=Facts.ADVICE_GIST,
        advice_text="Build up slowly: three short runs and one easy long run each week.",
        outcome="pending",
        given_at=now - timedelta(days=2),
    )
    ProactiveRaise.objects.create(
        id=rid(Facts.RAISE_REASON),
        household=household,
        owner_user=owner,
        origin_guid=str(rid(Facts.RAISE_REASON)),
        rule_id="rough_weather_driving",
        category="safety",
        reason=Facts.RAISE_REASON,
        spoken_at=now - timedelta(minutes=5),
        delivery="spoken",
    )


def _seed_records(household, today: date, zone: ZoneInfo, now: datetime) -> None:
    """Ticket 15's rows: the "Guitar practice" tracker (one record type, four fields) with two
    sessions, and a "Lifting" tracker with one entry, stored in the phone's shapes (epoch-ms day,
    guid-keyed payload). All invented."""
    from homes.models import UserAspect, UserFieldDef, UserRecord, UserRecordType

    def day_ms(day: date) -> int:
        return int(datetime.combine(day, time(0, 0), zone).timestamp() * 1000)

    def tracker(name: str, type_name: str, fields: tuple) -> UserRecordType:
        aspect = UserAspect.objects.create(
            id=rid(name),
            household=household,
            origin_guid=str(rid(name)),
            name=name,
            created_at_client=now - timedelta(days=20),
            updated_at_client=now - timedelta(days=20),
        )
        record_type = UserRecordType.objects.create(
            id=rid(f"{name}:{type_name}"),
            household=household,
            origin_guid=str(rid(f"{name}:{type_name}")),
            aspect=aspect,
            name=type_name,
            created_at_client=now - timedelta(days=20),
            updated_at_client=now - timedelta(days=20),
        )
        for position, (field, kind, required, config) in enumerate(fields):
            UserFieldDef.objects.create(
                id=rid(f"{name}:field:{field}"),
                household=household,
                origin_guid=str(rid(f"{name}:field:{field}")),
                record_type=record_type,
                name=field,
                type=kind,
                required=required,
                position=position,
                config=config,
                created_at_client=now - timedelta(days=20),
                updated_at_client=now - timedelta(days=20),
            )
        return record_type

    def entry(record_type, name: str, values: dict, text: str, ago: int, n: int) -> None:
        payload = {str(rid(f"{record_type.aspect.name}:field:{k}")): v for k, v in values.items()}
        UserRecord.objects.create(
            id=rid(f"entry:{name}"),
            household=household,
            origin_guid=str(rid(f"entry:{name}")),
            record_type=record_type,
            search_text=text,
            provenance="USER",
            payload=payload,
            created_at_client=now - timedelta(days=ago, minutes=n),
            updated_at_client=now - timedelta(days=ago, minutes=n),
        )

    guitar = tracker(
        Facts.TRACKER,
        Facts.TRACKER_TYPE,
        (
            ("piece", "TEXT", True, None),
            ("minutes", "NUMBER", False, None),
            ("date", "DATE", False, None),
            ("focus", "CHOICE", False, '{"options": ["chords", "scales"]}'),
        ),
    )
    for n, (piece, minutes, focus, ago) in enumerate(
        ((Facts.SESSION_A, 30.0, "chords", 2), (Facts.SESSION_B, 15.0, "scales", 1))
    ):
        values = {"piece": piece, "minutes": minutes, "date": day_ms(today - timedelta(days=ago))}
        entry(guitar, piece, {**values, "focus": focus}, f"{piece} {focus}", ago, n)
    lifting = tracker(
        Facts.NEAR_TRACKER,
        Facts.NEAR_TYPE,
        (("exercise", "TEXT", True, None), ("weight", "NUMBER", False, None)),
    )
    entry(
        lifting, Facts.NEAR_ENTRY, {"exercise": Facts.NEAR_ENTRY, "weight": 225.0}, "Deadlift", 3, 0
    )
