"""Builds the FAKE demo household recruiters read (recruiter-demo ticket 03).

`manage.py seed_demo_household` is the only caller. Every name, merchant,
amount, place and note below is invented for this file; none is copied from a
real household.

## Every row goes through the path the API uses

- **Ledger, verified:** monthly checking statements through
  `ingest.views.commit_statement`, the one statement commit path the gate
  (`ingest/gate.check_statement`) sits in. Provenance `DETERMINISTIC`, all three
  anchors stated and persisted on `public.statements` (section 4 rule 8).
- **Ledger, unverified:** the last thirty days of a card through
  `ingest.provisional.commit_provisional`, section 4 rule 7's path. Every row is
  `UNRECONCILED`, and every surface says "unverified" in words.
- **Pantry:** receipts through `ingest.views.commit_receipt`, the same function
  `POST /api/ingest/receipt` calls, so `gate.check_receipt` judges each one.
  Line items carry macro ESTIMATES (section 4 rule 5). One receipt's items do
  not add up to its printed subtotal, and the engine quarantines it - the seed
  never writes a quarantined row itself.
- **Fleet, places, notes:** the `api/` serializers (`VehicleSerializer`,
  `ServiceHistorySerializer`, `MaintenanceScheduleSerializer`,
  `PlaceSerializer`, `VoiceNoteSerializer`) with the household in their
  context, exactly as `SyncedModelViewSet` builds them.
- **Dates:** `api.events.EventSerializer`. It records who added an event, and
  this seed creates no users (tickets 01/02 own those), so it is handed
  `NO_MEMBER`, whose pk is None: `events.created_by_id` is nullable (ON DELETE
  SET NULL, attribution only) and a NULL owner is a shared row (ADR 0052).

## Idempotent, and what `--reset` can and cannot wipe

Gated documents are keyed by `content_sha256`, the hash of their canonical
JSON, and each one's content depends only on its calendar month - so a second
run finds them ALREADY_COMMITTED and writes nothing. Everything else is
upserted on a stable key (`origin_guid`, `label`, `(vehicle, service_name)`,
voice-note title), so a second run updates in place rather than duplicating.

`private.forbid_mutation_of_facts` refuses DELETE on every gated row that is
not `UNRECONCILED` - statements, their transactions, receipts and their line
items. That trigger is the section 4 guarantee and this seed does not go
around it. So `--reset` deletes everything the engine permits deleting in the
demo household and KEEPS the gated rows, which the reseed then finds already
committed. The effect is the same seed, because gated content is a pure
function of its month.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import random
import zoneinfo
from dataclasses import dataclass, field
from types import SimpleNamespace

from django.db import transaction

from household.demo import DemoHouseholdError, demo_household_name, find_demo_household
from household.models import Household

DEMO_TIMEZONE = "America/New_York"
CURRENCY = "USD"

CHECKING_LAST4 = "4821"
CHECKING_NICKNAME = "Demo Everyday Checking"
CARD_LAST4 = "0917"
CARD_NICKNAME = "Demo Rewards Card"

# Statements chain their balances from this month forward, so month N's opening
# balance is month N-1's closing balance whichever month the seed first ran in.
BALANCE_EPOCH = dt.date(2026, 1, 1)
EPOCH_OPENING_CENTS = 412_650

STATEMENT_MONTHS = 3
CARD_DAYS = 30

# The engine has no user to attribute a seeded event to. See the module doc.
NO_MEMBER = SimpleNamespace(pk=None)

BAD_RECEIPT_KEY = "demo-receipt-unreconciled-v1"


# =============================================================================
# Invented reference data
# =============================================================================

GROCERS = ("Greenleaf Market", "Harvest Basket Grocery")

# (name, unit_price_cents, estimates or None). Estimates are kcal, protein g,
# carbs g, fat g for the whole line: a receipt never prints them, so they are
# what a model would guess from the name, and the engine labels them so.
PRODUCTS = (
    ("Rolled oats 42oz", 549, (5700, 190.0, 1030.0, 100.0)),
    ("Bananas 2.1lb", 132, (420, 5.0, 108.0, 1.5)),
    ("Greek yogurt 32oz", 599, (720, 80.0, 36.0, 20.0)),
    ("Large eggs dozen", 389, (840, 72.0, 4.0, 60.0)),
    ("Whole wheat bread", 349, (1600, 80.0, 280.0, 24.0)),
    ("Chicken thighs 2lb", 899, (1960, 176.0, 0.0, 136.0)),
    ("Baby spinach 10oz", 399, (65, 8.0, 10.0, 1.0)),
    ("Cheddar block 8oz", 449, (920, 56.0, 4.0, 76.0)),
    ("Brown rice 2lb", 329, (3200, 68.0, 680.0, 24.0)),
    ("Black beans can", 119, (385, 24.0, 70.0, 1.5)),
    ("Olive oil 500ml", 1099, None),
    ("Dish soap", 379, None),
    ("Paper towels 6pk", 1149, None),
    ("Coffee beans 12oz", 1199, None),
    ("Apples 3lb bag", 549, (950, 4.0, 250.0, 3.0)),
    ("Pasta 1lb", 189, (1600, 56.0, 320.0, 8.0)),
    ("Marinara jar", 429, (350, 10.0, 56.0, 10.0)),
    ("Frozen peas 16oz", 249, (350, 23.0, 63.0, 2.0)),
)

COFFEE = ("Corner Bean Coffee", "Lantern Street Espresso")
DINING = ("Lantern Noodle House", "Old Mill Pizza Co", "Sunny Side Diner", "Taqueria Verde")
FUEL = ("Northside Fuel #12", "Riverbend Gas & Go")
SHOPS = ("Pine Street Pharmacy", "Brightline Hardware", "Paper Crane Books")


@dataclass
class SeedReport:
    """What one run did, for the command to print and the tests to read."""

    household: Household
    created_household: bool = False
    reset_deleted: dict[str, int] = field(default_factory=dict)
    reset_kept: dict[str, int] = field(default_factory=dict)
    statements: dict[str, int] = field(default_factory=dict)
    receipts: dict[str, int] = field(default_factory=dict)
    card: dict[str, int] = field(default_factory=dict)
    bad_receipt: dict = field(default_factory=dict)
    upserts: dict[str, int] = field(default_factory=dict)


# =============================================================================
# Calendar helpers
# =============================================================================


def _month_start(day: dt.date) -> dt.date:
    return day.replace(day=1)


def _add_months(day: dt.date, months: int) -> dt.date:
    total = day.year * 12 + day.month - 1 + months
    year, month = divmod(total, 12)
    return dt.date(year, month + 1, 1)


def _month_end(first: dt.date) -> dt.date:
    return _add_months(first, 1) - dt.timedelta(days=1)


def statement_months(today: dt.date) -> list[dt.date]:
    """The first day of each of the last STATEMENT_MONTHS complete months,
    oldest first. A statement only exists for a month that has ended."""
    this_month = _month_start(today)
    return [_add_months(this_month, -n) for n in range(STATEMENT_MONTHS, 0, -1)]


def _sha(payload: dict) -> str:
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _day_in(rng: random.Random, first: dt.date, low: int = 1, high: int | None = None) -> dt.date:
    last = _month_end(first).day
    return first.replace(day=rng.randint(low, min(high or last, last)))


# =============================================================================
# Pantry: receipts, a pure function of their month
# =============================================================================


def _receipt_items(rng: random.Random, count: int) -> list[dict]:
    items = []
    for name, unit, estimates in rng.sample(PRODUCTS, count):
        quantity = rng.choice((1, 1, 1, 2))
        item = {
            "name": name,
            "quantity": quantity,
            "unit_price_cents": unit,
            "total_price_cents": unit * quantity,
        }
        if estimates is not None:
            kcal, protein, carbs, fat = estimates
            item.update(
                {
                    "estimated_calories_kcal": kcal * quantity,
                    "estimated_protein_g": protein * quantity,
                    "estimated_carbs_g": carbs * quantity,
                    "estimated_fat_g": fat * quantity,
                }
            )
        items.append(item)
    return items


def month_receipts(first: dt.date) -> list[dict]:
    """Three grocery receipts in one month. Each ties out exactly: items sum to
    the printed subtotal, and subtotal plus tax is the printed total."""
    rng = random.Random(f"demo-receipts-{first:%Y-%m}")
    receipts = []
    for index, (low, high) in enumerate(((2, 9), (11, 19), (21, 28))):
        items = _receipt_items(rng, rng.randint(5, 9))
        subtotal = sum(item["total_price_cents"] for item in items)
        tax = round(subtotal * 0.0225)
        store = rng.choice(GROCERS)
        purchase_date = _day_in(rng, first, low, high)
        body = {
            "store": store,
            "purchase_date": purchase_date.isoformat(),
            "currency": CURRENCY,
            "subtotal_cents": subtotal,
            "tax_cents": tax,
            "other_charges_cents": None,
            "total_cents": subtotal + tax,
            "items": items,
            "provenance": "LLM_RECONCILED",
            "display_name": f"demo receipt {store} {purchase_date.isoformat()} #{index + 1}",
        }
        body["content_sha256"] = _sha(body)
        receipts.append(body)
    return receipts


def bad_receipt() -> dict:
    """ONE receipt whose extracted lines do not add up to what it prints.

    Seven items come to $48.17; the receipt prints a subtotal of $52.36 - an
    item the extraction missed, which is exactly what section 4 rule 2 exists
    to catch. Its figures are fixed (not a function of today) so there is
    always exactly one, and its file row is the only trace it leaves.
    """
    items = [
        {"name": name, "quantity": qty, "unit_price_cents": unit, "total_price_cents": qty * unit}
        for name, qty, unit in (
            ("Sourdough loaf", 1, 549),
            ("Oat milk 64oz", 1, 449),
            ("Cherry tomatoes", 2, 349),
            ("Ground turkey 1lb", 1, 699),
            ("Avocados 4ct", 1, 499),
            ("Parmesan wedge", 1, 899),
            ("Laundry pods", 1, 1024),
        )
    ]
    subtotal = 5236
    tax = 118
    body = {
        "store": "Harvest Basket Grocery",
        "currency": CURRENCY,
        "subtotal_cents": subtotal,
        "tax_cents": tax,
        "other_charges_cents": None,
        "total_cents": subtotal + tax,
        "items": items,
        "provenance": "LLM_RECONCILED",
        "display_name": "demo receipt Harvest Basket Grocery (lines do not add up)",
        "source_file_id": BAD_RECEIPT_KEY,
    }
    body["content_sha256"] = hashlib.sha256(BAD_RECEIPT_KEY.encode("utf-8")).hexdigest()
    return body


BAD_RECEIPT_ITEMS_CENTS = 4817
BAD_RECEIPT_SUBTOTAL_CENTS = 5236
BAD_RECEIPT_TOTAL_CENTS = 5354


# =============================================================================
# Ledger: checking statements, chained from BALANCE_EPOCH
# =============================================================================


def _statement_lines(first: dt.date) -> list[dict]:
    """One month of checking activity, in date order. Grocery debits are the
    month's receipts' printed totals, on the receipts' own dates."""
    rng = random.Random(f"demo-checking-{first:%Y-%m}")
    raw: list[tuple[dt.date, str, int, str]] = [
        (first.replace(day=1), "PAYROLL ORBITLY SOFTWARE INC", 318_540, "Income"),
        (first.replace(day=15), "PAYROLL ORBITLY SOFTWARE INC", 318_540, "Income"),
        (first.replace(day=3), "WILLOW COURT APARTMENTS RENT", -172_500, "Housing"),
        (
            _day_in(rng, first, 10, 14),
            "MAPLE HOLLOW ELECTRIC",
            -rng.randint(7_400, 13_900),
            "Utilities",
        ),
        (first.replace(day=18), "BRIGHTWIRE INTERNET", -6_500, "Utilities"),
        (
            first.replace(day=22),
            "TRANSFER TO DEMO REWARDS CARD",
            -rng.randint(55_000, 80_000),
            "Transfer",
        ),
    ]
    for receipt in month_receipts(first):
        raw.append(
            (
                dt.date.fromisoformat(receipt["purchase_date"]),
                receipt["store"].upper(),
                -receipt["total_cents"],
                "Groceries",
            )
        )
    def spend(merchants: tuple[str, ...], low: int, high: int, category: str) -> None:
        merchant = rng.choice(merchants).upper()
        raw.append((_day_in(rng, first), merchant, -rng.randint(low, high), category))

    for _ in range(rng.randint(4, 7)):
        spend(COFFEE, 425, 725, "Dining")
    for _ in range(rng.randint(2, 4)):
        spend(DINING, 1_850, 6_400, "Dining")
    for _ in range(rng.randint(2, 3)):
        spend(FUEL, 3_400, 6_100, "Transport")
    spend(SHOPS, 900, 4_800, "Shopping")

    raw.sort(key=lambda row: (row[0], row[1], row[2]))
    return [
        {
            "txn_date": day.isoformat(),
            "description": description,
            "amount_cents": amount,
            "line_ref": f"demo-chk-{first:%Y%m}-{index:02d}",
            "category": category,
        }
        for index, (day, description, amount, category) in enumerate(raw)
    ]


def month_statement(first: dt.date) -> dict:
    """The checking statement for one month, with all three anchors printed:
    stated total, opening balance, closing balance. Opening is the closing of
    the month before it, chained from BALANCE_EPOCH, so it is the same whatever
    day the seed runs."""
    opening = EPOCH_OPENING_CENTS
    month = BALANCE_EPOCH
    while month < first:
        opening += sum(line["amount_cents"] for line in _statement_lines(month))
        month = _add_months(month, 1)
    lines = _statement_lines(first)
    balance = opening
    for line in lines:
        balance += line["amount_cents"]
        line["balance_cents"] = balance
    total = sum(line["amount_cents"] for line in lines)
    body = {
        "account_last4": CHECKING_LAST4,
        "account_nickname": CHECKING_NICKNAME,
        "currency": CURRENCY,
        "period_start": first.isoformat(),
        "period_end": _month_end(first).isoformat(),
        "stated_total_cents": total,
        "opening_balance_cents": opening,
        "closing_balance_cents": opening + total,
        "lines": lines,
        "provenance": "DETERMINISTIC",
        "display_name": f"demo checking statement {first:%Y-%m}",
    }
    body["content_sha256"] = _sha(body)
    return body


# =============================================================================
# Ledger: the card, provisional, the last CARD_DAYS days
# =============================================================================


def _card_day(day: dt.date) -> list[dict]:
    """A day's card lines, a pure function of the date - so tomorrow's export
    repeats today's rows exactly and `commit_provisional` keeps them once."""
    rng = random.Random(f"demo-card-{day.isoformat()}")
    lines = []
    for index in range(rng.choice((0, 1, 1, 2))):
        kind = rng.random()
        if kind < 0.45:
            description, amount = rng.choice(COFFEE), rng.randint(425, 725)
        elif kind < 0.8:
            description, amount = rng.choice(DINING), rng.randint(1_850, 6_400)
        elif kind < 0.9:
            description, amount = rng.choice(FUEL), rng.randint(3_400, 6_100)
        else:
            description, amount = rng.choice(SHOPS), rng.randint(900, 4_800)
        lines.append(
            {
                "txn_date": day.isoformat(),
                "description": description.upper(),
                "amount_cents": -amount,
                "line_ref": f"demo-card-{day:%Y%m%d}-{index}",
            }
        )
    return lines


def card_export(today: dt.date) -> dict:
    """The card's activity export: the CARD_DAYS days before today. It prints
    no balance and no total, so it can only be stored provisionally."""
    lines = []
    for offset in range(CARD_DAYS, 0, -1):
        lines.extend(_card_day(today - dt.timedelta(days=offset)))
    body = {
        "account_last4": CARD_LAST4,
        "account_nickname": CARD_NICKNAME,
        "currency": CURRENCY,
        "lines": lines,
        "provenance": "UNRECONCILED",
        "display_name": f"demo card activity to {today.isoformat()}",
    }
    body["content_sha256"] = _sha(body)
    return body


# =============================================================================
# Fleet, places, notes, dates: authored rows, relative to today
# =============================================================================

VEHICLE_GUID = "demo-vehicle-wagon"


def vehicle_fields(today: dt.date, zone) -> dict:
    return {
        "origin_guid": VEHICLE_GUID,
        "name": "The Wagon",
        "make": "Subaru",
        "model": "Outback",
        "year": 2019,
        "trim": "Premium",
        "engine": "2.5L flat-four",
        "confirmed": True,
        "odometer_baseline": 61_240,
        "odometer_baseline_at": dt.datetime.combine(
            today - dt.timedelta(days=9), dt.time(18, 30), tzinfo=zone
        ).isoformat(),
    }


def service_rows(today: dt.date) -> list[dict]:
    """Service history. The oil change is 170 days old against a six-month
    schedule, so it comes due inside the brief's 30-day window."""
    return [
        {
            "origin_guid": guid,
            "service_name": name,
            "mileage": mileage,
            "service_date": (today - dt.timedelta(days=days_ago)).isoformat(),
            "cost_cents": cost,
            "kind": "OBSERVED",
        }
        for guid, name, mileage, days_ago, cost in (
            ("demo-svc-oil-1", "Oil change", 51_880, 350, 6_499),
            ("demo-svc-oil-2", "Oil change", 56_910, 170, 6_899),
            ("demo-svc-rotate-1", "Tire rotation", 59_020, 95, 2_500),
            ("demo-svc-brakes-1", "Front brake pads", 48_300, 420, 28_950),
            ("demo-svc-cabin-1", "Cabin air filter", 56_910, 170, 3_499),
        )
    ]


SCHEDULES = (
    {"service_name": "Oil change", "interval_miles": 5_000, "interval_months": 6},
    {"service_name": "Tire rotation", "interval_miles": 6_000, "interval_months": 6},
    {"service_name": "Front brake pads", "interval_miles": 30_000, "interval_months": None},
    {"service_name": "Cabin air filter", "interval_miles": 15_000, "interval_months": 12},
    {
        "service_name": "Brake fluid",
        "interval_miles": None,
        "interval_months": 24,
        "never_done": True,
    },
)

PLACES = (
    {"label": "Home", "latitude": 39.9851, "longitude": -83.0062,
     "address": "18 Willow Court, Maple Hollow"},
    {"label": "Office", "latitude": 39.9652, "longitude": -82.9988,
     "address": "400 Orbitly Plaza, Maple Hollow"},
    {"label": "Gym", "latitude": 39.9790, "longitude": -83.0185,
     "address": "Ironleaf Fitness, 2 Mill Road, Maple Hollow"},
    {"label": "Greenleaf Market", "latitude": 39.9921, "longitude": -83.0110,
     "address": "77 Orchard Avenue, Maple Hollow"},
)


def voice_notes(now: dt.datetime) -> list[dict]:
    def at(days_ago: int, hour: int, minutes: int) -> tuple[str, str]:
        start = (now - dt.timedelta(days=days_ago)).replace(
            hour=hour, minute=0, second=0, microsecond=0
        )
        return start.isoformat(), (start + dt.timedelta(minutes=minutes)).isoformat()

    notes = [
        (
            "Weekend plan",
            2, 9, 3,
            "Saturday: farmers market in the morning, then fix the squeaky back gate. "
            "Sunday: meal prep, oats and the chicken thigh recipe, and call about the "
            "car registration.",
            "Saturday market and gate repair; Sunday meal prep and the registration call.",
        ),
        (
            "Book club picks",
            6, 20, 2,
            "Next three for book club: a short mystery, the lighthouse novel Priya "
            "mentioned, and something nonfiction about bridges. Meeting is the last "
            "Thursday of the month.",
            "Three candidate books for book club; meets the last Thursday.",
        ),
        (
            "Grocery ideas",
            11, 18, 1,
            "Out of olive oil and coffee beans. Try the black bean soup again, it was good. "
            "Remember the paper towels.",
            "Need olive oil, coffee beans, paper towels; repeat the black bean soup.",
        ),
    ]
    rows = []
    for title, days_ago, hour, minutes, transcript, summary in notes:
        started, ended = at(days_ago, hour, minutes)
        rows.append(
            {
                "title": title,
                "kind": "SOLO",
                "started_at": started,
                "ended_at": ended,
                "transcript": transcript,
                "summary": summary,
            }
        )
    return rows


def events(today: dt.date, zone) -> list[dict]:
    def at(days: int, hour: int, minute: int = 0) -> str:
        return dt.datetime.combine(
            today + dt.timedelta(days=days), dt.time(hour, minute), tzinfo=zone
        ).isoformat()

    return [
        {
            "origin_guid": "demo-event-dentist",
            "title": "Dentist cleaning",
            "kind": "event",
            "starts_at": at(6, 9, 30),
            "ends_at": at(6, 10, 30),
            "location": "Bright Smile Dental, 12 Elm Street, Maple Hollow",
            "remind_minutes_before": 60,
        },
        {
            "origin_guid": "demo-event-registration",
            "title": "Renew the Wagon's registration",
            "kind": "task",
            "starts_at": at(12, 17),
            "notes": "Online renewal; needs the insurance card number.",
        },
        {
            "origin_guid": "demo-event-drycleaning",
            "title": "Pick up dry cleaning",
            "kind": "reminder",
            "starts_at": at(1, 17, 30),
        },
        {
            "origin_guid": "demo-event-bookclub",
            "title": "Book club",
            "kind": "event",
            "starts_at": at(17, 19),
            "ends_at": at(17, 21),
            "location": "Paper Crane Books",
        },
        {
            "origin_guid": "demo-event-market",
            "title": "Maple Hollow farmers market",
            "kind": "suggestion",
            "starts_at": at(5, 8),
            "structured_meta": {
                "city": "Maple Hollow",
                "venue": "Orchard Avenue lot",
                "address": "Orchard Avenue, Maple Hollow",
                "url": None,
                "price": "Free",
            },
        },
    ]


# =============================================================================
# The run
# =============================================================================


def _ensure_household() -> tuple[Household, bool]:
    existing = find_demo_household()
    if existing is not None:
        return existing, False
    household = Household.objects.create(name=demo_household_name(), timezone=DEMO_TIMEZONE)
    return household, True


def _save(serializer_class, context: dict, data: dict, instance=None):
    serializer = serializer_class(instance, data=data, context=context)
    serializer.is_valid(raise_exception=True)
    return serializer.save()


def reset(household: Household, report: SeedReport) -> None:
    """Deletes every row of the demo household the engine permits deleting, and
    counts the gated rows `forbid_mutation_of_facts` keeps. See the module doc."""
    from legacy.models.dates import Event
    from legacy.models.fleet import MaintenanceSchedule, ServiceHistory, Vehicle
    from legacy.models.ingest import IngestedFile
    from legacy.models.ledger import LedgerTransaction, Statement
    from legacy.models.notes import VoiceNote
    from legacy.models.pantry import Receipt, ReceiptLineItem
    from legacy.models.places import Place

    def wipe(name: str, queryset) -> None:
        deleted, _ = queryset.delete()
        report.reset_deleted[name] = deleted

    wipe("maintenance_schedules", MaintenanceSchedule.objects.filter(household=household))
    wipe("service_history", ServiceHistory.objects.filter(household=household))
    wipe("vehicles", Vehicle.objects.filter(household=household))
    wipe("events", Event.objects.filter(household=household))
    wipe("places", Place.objects.filter(household=household))
    wipe("voice_notes", VoiceNote.objects.filter(household=household))
    wipe(
        "ledger_transactions (UNRECONCILED)",
        LedgerTransaction.objects.filter(household=household, provenance="UNRECONCILED"),
    )
    referenced = set(
        Statement.objects.filter(household=household).values_list("ingested_file_id", flat=True)
    ) | set(Receipt.objects.filter(household=household).values_list("ingested_file_id", flat=True))
    wipe(
        "ingested_files (not behind a gated row)",
        IngestedFile.objects.filter(household=household).exclude(id__in=referenced - {None}),
    )

    report.reset_kept = {
        "statements": Statement.objects.filter(household=household).count(),
        "ledger_transactions (gated)": LedgerTransaction.objects.filter(household=household)
        .exclude(provenance="UNRECONCILED")
        .count(),
        "receipts": Receipt.objects.filter(household=household).count(),
        "receipt_line_items": ReceiptLineItem.objects.filter(household=household).count(),
    }


def seed(*, reset_first: bool = False, today: dt.date | None = None) -> SeedReport:
    """Builds (or refreshes) the demo household. One transaction for the
    authored rows; each gated document commits in its own, as it would arriving
    through the API."""
    from api.events import EventSerializer
    from api.fleet import (
        MaintenanceScheduleSerializer,
        ServiceHistorySerializer,
        VehicleSerializer,
    )
    from api.places import PlaceSerializer
    from api.voice_notes import VoiceNoteSerializer
    from ingest.provisional import commit_provisional
    from ingest.views import commit_receipt, commit_statement
    from legacy.models.dates import Event
    from legacy.models.fleet import MaintenanceSchedule, ServiceHistory, Vehicle
    from legacy.models.notes import VoiceNote
    from legacy.models.places import Place

    with transaction.atomic():
        household, created = _ensure_household()
        report = SeedReport(household=household, created_household=created)
        if reset_first and not created:
            reset(household, report)

    zone = zoneinfo.ZoneInfo(household.timezone or DEMO_TIMEZONE)
    now = dt.datetime.now(zone)
    today = today or now.date()
    if today != now.date():
        now = dt.datetime.combine(today, dt.time(12, 0), tzinfo=zone)

    # -- ledger, verified, and pantry: the gate decides ------------------------
    for first in statement_months(today):
        for receipt in month_receipts(first):
            outcome = commit_receipt(receipt, household).outcome
            report.receipts[outcome] = report.receipts.get(outcome, 0) + 1
        outcome = commit_statement(month_statement(first), household).outcome
        report.statements[outcome] = report.statements.get(outcome, 0) + 1

    bad = commit_receipt(bad_receipt(), household)
    report.bad_receipt = dict(bad.body)

    # -- ledger, unverified -----------------------------------------------------
    card = commit_provisional(card_export(today), household)
    report.card = {
        "outcome": card.outcome,
        "inserted": card.body.get("inserted", 0),
    }

    # -- authored rows, through the API serializers ----------------------------
    context = {"household": household, "revive_tombstone": False}
    with transaction.atomic():
        vehicle = Vehicle.objects.filter(household=household, origin_guid=VEHICLE_GUID).first()
        vehicle = _save(VehicleSerializer, context, vehicle_fields(today, zone), vehicle)
        report.upserts["vehicles"] = 1

        for row in service_rows(today):
            existing = ServiceHistory.objects.filter(
                household=household, origin_guid=row["origin_guid"]
            ).first()
            data = {**row, "vehicle_id": str(vehicle.id)}
            _save(ServiceHistorySerializer, context, data, existing)
        report.upserts["service_history"] = len(service_rows(today))

        for row in SCHEDULES:
            existing = MaintenanceSchedule.objects.filter(
                household=household, vehicle=vehicle, service_name=row["service_name"]
            ).first()
            data = {
                "vehicle_id": str(vehicle.id),
                "interval_source": "SEEDED",
                "never_done": row.get("never_done", False),
                "service_name": row["service_name"],
                "interval_miles": row["interval_miles"],
                "interval_months": row["interval_months"],
            }
            _save(MaintenanceScheduleSerializer, context, data, existing)
        report.upserts["maintenance_schedules"] = len(SCHEDULES)

        place_context = {**context, "revive_tombstone": True}
        for row in PLACES:
            existing = Place.objects.filter(household=household, label=row["label"]).first()
            _save(PlaceSerializer, place_context, row, existing)
        report.upserts["places"] = len(PLACES)

        notes = voice_notes(now)
        for row in notes:
            existing = VoiceNote.objects.filter(household=household, title=row["title"]).first()
            _save(VoiceNoteSerializer, context, row, existing)
        report.upserts["voice_notes"] = len(notes)

        event_context = {"household": household, "user": NO_MEMBER}
        planned = events(today, zone)
        for row in planned:
            existing = Event.objects.filter(
                household=household, origin_guid=row["origin_guid"]
            ).first()
            _save(EventSerializer, event_context, row, existing)
        report.upserts["events"] = len(planned)

    return report


__all__ = [
    "BAD_RECEIPT_ITEMS_CENTS",
    "BAD_RECEIPT_SUBTOTAL_CENTS",
    "BAD_RECEIPT_TOTAL_CENTS",
    "DemoHouseholdError",
    "SeedReport",
    "bad_receipt",
    "seed",
]
