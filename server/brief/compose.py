"""The brief's deterministic sections: Canvas, Money and Car (ai-upgrade ticket
02, ruling 4: "Deterministic sections are composed by code. Only News reaches
an LLM").

Every section is a dict of the one shape the API serves:

    {"key": "canvas", "title": "Canvas", "status": "ok" | "empty" | "failed",
     "lines": [{"text": str, "unverified": bool, "links": [{"title", "url"}]}],
     "note": str | None}

Every sentence is composed HERE, so the phone and the web render it as it is
and never build a phrase of their own from numbers.

**Every read goes through the tenancy choke point** with a request-shaped
`Viewer` for the member: `scoped()` for household-wide tables, `visible()` for
`events`, so a Canvas task private to one member reaches only that member's
brief (ADR 0052).

**A source that has nothing says so in words, never 0** (ruling 3).
"""

from __future__ import annotations

import datetime as dt
from collections import defaultdict
from dataclasses import dataclass

from household.tenancy import scoped, visible
from ingest.category_overrides import with_effective_category
from ingest.freshness import freshness_for
from ingest.models import IngestRun, Outcome, Source

CANVAS_DAYS = 7
MAINTENANCE_DAYS = 30
# A mileage-based item is listed when it comes due within this many miles of
# the last recorded odometer reading.
MAINTENANCE_MILES = 500
TOP_MERCHANTS = 3

NO_RECURRING_SOURCE = (
    "Bills due in the next 7 days: LEGION has no source of recurring charges yet, so no bills "
    "are listed. This is not a sign that none are due."
)
UNVERIFIED_SUFFIX = " Unverified: includes bank rows no statement gate has checked."


@dataclass(frozen=True)
class Viewer:
    """What `household.tenancy.scoped()` and `visible()` read off a request:
    `.user`, whose `.household` is the scope and whose `.pk` decides private
    rows. Built for one member by the job, or taken from the real request."""

    user: object


def line(text: str, *, unverified: bool = False, links: list[dict] | None = None) -> dict:
    return {"text": text, "unverified": unverified, "links": links or []}


def section(key: str, title: str, status: str, lines: list[dict], note: str | None = None) -> dict:
    return {"key": key, "title": title, "status": status, "lines": lines, "note": note}


# =============================================================================
# Wording helpers
# =============================================================================

_DAYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")


def day_words(day: dt.date) -> str:
    """`Thu Oct 23`."""
    return f"{_DAYS[day.weekday()]} {_MONTHS[day.month - 1]} {day.day}"


def short_date(day: dt.date) -> str:
    """`Oct 23`."""
    return f"{_MONTHS[day.month - 1]} {day.day}"


def clock_words(moment: dt.datetime) -> str:
    """`11:59 PM`."""
    hour = moment.hour % 12 or 12
    return f"{hour}:{moment.minute:02d} {'AM' if moment.hour < 12 else 'PM'}"


def money(cents: int, currency: str = "USD") -> str:
    """Integer cents to words, with no float on the way (section 4 rule 3)."""
    sign = "-" if cents < 0 else ""
    whole, part = divmod(abs(cents), 100)
    symbol = "$" if currency == "USD" else f"{currency} "
    return f"{sign}{symbol}{whole:,}.{part:02d}"


def add_months(day: dt.date, months: int) -> dt.date:
    import calendar

    total = day.year * 12 + day.month - 1 + months
    year, month = divmod(total, 12)
    month += 1
    return dt.date(year, month, min(day.day, calendar.monthrange(year, month)[1]))


# =============================================================================
# Canvas
# =============================================================================


def canvas_freshness_note(household, now: dt.datetime) -> str | None:
    """The freshness sentence, in its own words, unless Canvas last synced
    fine and recently (ruling 3: a stale feed or one that needs a login says
    so)."""
    runs = IngestRun.objects.filter(household=household)
    entry = freshness_for(runs, Source.CANVAS, now)
    if not entry["stale"] and entry["last_outcome"] == Outcome.OK:
        return None
    return entry["sentence"]


def canvas_section(viewer: Viewer, now: dt.datetime, zone: dt.tzinfo) -> dict:
    """Tasks due in the next 7 days, from `events` (kind task), with what
    Canvas said about submission. A task Kevin ticked says so; Canvas never
    ticks one (Kevin, 2026-09-28)."""
    from legacy.models.dates import Event

    household = viewer.user.household
    tasks = list(
        visible(Event, viewer)
        .filter(
            kind="task",
            deleted_at__isnull=True,
            starts_at__gte=now,
            starts_at__lt=now + dt.timedelta(days=CANVAS_DAYS),
        )
        .order_by("starts_at", "title")
    )
    lines = []
    for task in tasks:
        due = task.starts_at.astimezone(zone)
        text = f"{task.title}: due {day_words(due.date())}, {clock_words(due)}."
        meta = task.structured_meta or {}
        if task.done:
            text += " Ticked done."
        elif meta.get("canvas_submitted") is True:
            text += " Canvas says submitted."
        elif meta.get("canvas_submitted") is False:
            text += " Canvas says not submitted."
        lines.append(line(text))
    if not lines:
        lines.append(line(f"No tasks due in the next {CANVAS_DAYS} days."))
    return section(
        "canvas",
        "Canvas",
        "ok" if tasks else "empty",
        lines,
        canvas_freshness_note(household, now),
    )


# =============================================================================
# Money
# =============================================================================


def _txns(viewer: Viewer, since: dt.date):
    """The household's ledger rows from `since`, as `api.spend.Txn`, ordered
    and numbered exactly as `api.spend.household_spend` does so transfer
    pairing breaks ties the same way."""
    from api.ledger_spend import UNVERIFIED_PROVENANCE
    from api.spend import Txn
    from legacy.models.ledger import LedgerTransaction

    rows = (
        with_effective_category(scoped(LedgerTransaction, viewer))
        .filter(txn_date__gte=since)
        .values(
            "id",
            "created_at",
            "account_last4",
            "currency",
            "txn_date",
            "description",
            "amount_cents",
            "effective_category",
            "provenance",
        )
    )
    ordered = sorted(rows, key=lambda r: (r["created_at"], str(r["id"])))
    return [
        Txn(
            id=index,
            account_id=row["account_last4"],
            amount_cents=row["amount_cents"],
            txn_date=row["txn_date"],
            description=row["description"],
            category=row["effective_category"],
            currency=row["currency"],
            unreconciled=row["provenance"] in UNVERIFIED_PROVENANCE,
        )
        for index, row in enumerate(ordered)
    ]


def _expenses(txns, currency: str, start: dt.date, end: dt.date, not_spending: set[str]):
    """`api.spend.operating_expenses` over `[start, end]`, with the same
    5-day pairing window either side, so a transfer is never spend here
    either. Calendar dates, not the budget month: a brief says what left the
    accounts on those days."""
    from api.spend import MAX_DAYS_APART, PAIRING_WINDOW_DAYS, operating_expenses

    pad = dt.timedelta(days=PAIRING_WINDOW_DAYS)
    own = {t.account_id for t in txns if t.currency == currency}
    in_period = [t for t in txns if t.currency == currency and start <= t.txn_date <= end]
    window = [t for t in txns if t.currency == currency and start - pad <= t.txn_date <= end + pad]
    return operating_expenses(currency, in_period, window, MAX_DAYS_APART, own, None, not_spending)


def _split(expenses) -> tuple[int, int, bool]:
    """(categorised spend, uncategorised, any unverified row) in positive
    cents. Spend is the categorised lines only; uncategorised money is its
    own figure beside it (Kevin, 2026-08-15, `api/spend.py`)."""
    categorised = -sum(t.amount_cents for t in expenses if t.category is not None)
    uncategorised = -sum(t.amount_cents for t in expenses if t.category is None)
    return categorised, uncategorised, any(t.unreconciled for t in expenses)


def money_section(viewer: Viewer, today: dt.date) -> dict:
    from legacy.models.ledger import Category

    yesterday = today - dt.timedelta(days=1)
    month_start = yesterday.replace(day=1)
    previous_start = add_months(month_start, -1)
    txns = _txns(viewer, previous_start - dt.timedelta(days=10))
    lines: list[dict] = []
    if not txns:
        lines.append(line("No bank rows on file for the last two months."))
        lines.append(line(NO_RECURRING_SOURCE))
        return section("money", "Money", "empty", lines)

    not_spending = set(
        scoped(Category, viewer)
        .filter(deleted_at__isnull=True, excluded_from_spend=True)
        .values_list("name", flat=True)
    )
    currencies = sorted({t.currency for t in txns}, key=lambda c: (c != "USD", c))
    for currency in currencies:
        tag = "" if len(currencies) == 1 else f" ({currency})"

        # Yesterday.
        spent_y = _expenses(txns, currency, yesterday, yesterday, not_spending)
        cat, uncat, unverified = _split(spent_y)
        if not spent_y:
            text = f"Yesterday{tag}: no spending on file."
        else:
            if cat and uncat:
                text = (
                    f"Yesterday{tag}: {money(cat, currency)} spent, plus "
                    f"{money(uncat, currency)} not yet categorised."
                )
            elif cat:
                text = f"Yesterday{tag}: {money(cat, currency)} spent."
            else:
                text = f"Yesterday{tag}: {money(uncat, currency)} went out, not yet categorised."
            by_merchant: dict[str, int] = defaultdict(int)
            for t in spent_y:
                by_merchant[t.description.strip()] += -t.amount_cents
            top = sorted(by_merchant.items(), key=lambda kv: (-kv[1], kv[0]))[:TOP_MERCHANTS]
            text += " Top: " + ", ".join(f"{name} {money(c, currency)}" for name, c in top) + "."
        if unverified:
            text += UNVERIFIED_SUFFIX
        lines.append(line(text, unverified=unverified))

        # Month to date against the same day last month.
        this_month = _expenses(txns, currency, month_start, yesterday, not_spending)
        previous_end = min(
            previous_start + dt.timedelta(days=yesterday.day - 1),
            month_start - dt.timedelta(days=1),
        )
        last_month = _expenses(txns, currency, previous_start, previous_end, not_spending)
        cat_now, uncat_now, unv_now = _split(this_month)
        cat_then, _uncat_then, unv_then = _split(last_month)
        text = (
            f"Month to date{tag} ({short_date(month_start)} to {short_date(yesterday)}): "
            f"{money(cat_now, currency)} spent, against {money(cat_then, currency)} by "
            f"{short_date(previous_end)} last month."
        )
        difference = cat_now - cat_then
        if difference:
            text += f" {'Up' if difference > 0 else 'Down'} {money(abs(difference), currency)}."
        if uncat_now:
            text += f" Another {money(uncat_now, currency)} this month is not yet categorised."
        if unv_now or unv_then:
            text += UNVERIFIED_SUFFIX
        lines.append(line(text, unverified=unv_now or unv_then))

    latest = max(t.txn_date for t in txns)
    lines.append(
        line(
            f"Latest bank row on file is dated {short_date(latest)}, so anything after it is "
            f"not counted yet."
        )
    )
    lines.append(line(NO_RECURRING_SOURCE))
    return section("money", "Money", "ok", lines)


# =============================================================================
# Car
# =============================================================================


def _latest_odometer(viewer: Viewer, vehicle) -> tuple[int, dt.date] | None:
    """The highest mileage any fleet record carries for this vehicle, with
    the date it was recorded. A fact on file, never an estimate."""
    from legacy.models.fleet import CodeClearEvent, CodeEvent, ServiceHistory

    readings: list[tuple[int, dt.date]] = []
    for row in scoped(ServiceHistory, viewer).filter(
        vehicle=vehicle, deleted_at__isnull=True, mileage__isnull=False
    ):
        readings.append((row.mileage, row.service_date or row.created_at.date()))
    for model in (CodeEvent, CodeClearEvent):
        for row in scoped(model, viewer).filter(
            vehicle=vehicle, deleted_at__isnull=True, mileage__isnull=False
        ):
            readings.append((row.mileage, row.occurred_at.date()))
    return max(readings) if readings else None


def _maintenance_lines(viewer: Viewer, vehicle, today: dt.date) -> list[str]:
    from legacy.models.fleet import MaintenanceSchedule, ServiceHistory

    schedules = list(
        scoped(MaintenanceSchedule, viewer)
        .filter(vehicle=vehicle, deleted_at__isnull=True)
        .order_by("service_name")
    )
    if not schedules:
        return ["Maintenance: no schedule logged."]
    history = list(scoped(ServiceHistory, viewer).filter(vehicle=vehicle, deleted_at__isnull=True))
    odometer = _latest_odometer(viewer, vehicle)
    due: list[str] = []
    never: list[str] = []
    unchecked_miles = False
    horizon = today + dt.timedelta(days=MAINTENANCE_DAYS)
    for schedule in schedules:
        done = [
            h
            for h in history
            if h.service_name.strip().lower() == schedule.service_name.strip().lower()
        ]
        if not done:
            never.append(schedule.service_name)
            continue
        if schedule.interval_months:
            dated = [h.service_date for h in done if h.service_date]
            if dated:
                last = max(dated)
                when = add_months(last, schedule.interval_months)
                if when <= horizon:
                    verb = "overdue since" if when < today else "due"
                    due.append(
                        f"{schedule.service_name} {verb} {short_date(when)} (every "
                        f"{schedule.interval_months} months, last done {short_date(last)})."
                    )
                    continue
        if schedule.interval_miles:
            miles = [h.mileage for h in done if h.mileage is not None]
            if not miles:
                continue
            at = max(miles) + schedule.interval_miles
            if odometer is None:
                unchecked_miles = True
                continue
            reading, read_on = odometer
            if at - reading <= MAINTENANCE_MILES:
                verb = "overdue at" if reading >= at else "due at"
                due.append(
                    f"{schedule.service_name} {verb} {at:,} mi (last recorded odometer "
                    f"{reading:,} mi on {short_date(read_on)})."
                )
    lines = due or [
        f"Maintenance: nothing comes due in the next {MAINTENANCE_DAYS} days by the records "
        f"on file."
    ]
    if unchecked_miles:
        lines.append("Mileage-based items cannot be checked: no odometer reading on file.")
    if never:
        lines.append("Never logged as done: " + ", ".join(never) + ".")
    return lines


def car_section(viewer: Viewer, zone: dt.tzinfo, today: dt.date) -> dict:
    """Per vehicle: last drive, last OBD reading, trouble codes since the last
    clear, and maintenance coming due."""
    from legacy.models.fleet import CodeClearEvent, CodeEvent, Drive, ObdSample, Vehicle

    vehicles = list(
        scoped(Vehicle, viewer)
        .filter(deleted_at__isnull=True, archived=False)
        .order_by("name", "id")
    )
    if not vehicles:
        return section("car", "Car", "empty", [line("No vehicle on file.")])
    lines: list[dict] = []
    for vehicle in vehicles:
        name = vehicle.name or f"{vehicle.year} {vehicle.make} {vehicle.model}"
        drive = (
            scoped(Drive, viewer)
            .filter(vehicle=vehicle, deleted_at__isnull=True)
            .order_by("-ended_at")
            .first()
        )
        if drive is None:
            lines.append(line(f"{name}: last drive not logged."))
        else:
            ended = drive.ended_at.astimezone(zone)
            lines.append(
                line(
                    f"{name}: last drive {day_words(ended.date())}, {clock_words(ended)}, "
                    f"{drive.miles:.1f} mi."
                )
            )
        sample = scoped(ObdSample, viewer).filter(vehicle=vehicle).order_by("-recorded_at").first()
        if sample is None:
            lines.append(line(f"{name}: OBD readings not logged."))
        else:
            read = sample.recorded_at.astimezone(zone)
            lines.append(
                line(f"{name}: last OBD reading {day_words(read.date())}, {clock_words(read)}.")
            )
        cleared = (
            scoped(CodeClearEvent, viewer)
            .filter(vehicle=vehicle, deleted_at__isnull=True)
            .order_by("-occurred_at")
            .first()
        )
        events = scoped(CodeEvent, viewer).filter(vehicle=vehicle, deleted_at__isnull=True)
        if not events.exists():
            lines.append(line(f"{name}: trouble codes not logged."))
        else:
            if cleared is not None:
                events = events.filter(occurred_at__gt=cleared.occurred_at)
            codes = sorted({str(code) for event in events for code in (event.codes or []) if code})
            since = "since the last clear" if cleared is not None else "on record"
            lines.append(
                line(
                    f"{name}: trouble codes {since}: {', '.join(codes)}."
                    if codes
                    else f"{name}: no trouble codes {since}."
                )
            )
        for text in _maintenance_lines(viewer, vehicle, today):
            lines.append(line(f"{name}: {text}"))
    return section("car", "Car", "ok", lines)
