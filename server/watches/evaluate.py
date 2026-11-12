"""Evaluating watches: rules decide, deterministically, as a function of the rows and the clock
(ADR 0083; proactive-mode decisions 8 and 11). `manage.py evaluate_watches` runs
`evaluate_household` for every household; a test passes `now` and reads the firings back.

Every read is the OWNER's read: a `Viewer` for the member who set the watch goes through
`household.tenancy.scoped()` / `visible()`, so a watch can never fire on a row its owner could not
see (Canvas items are private to their member, ADR 0052).

**What each kind treats as "new"**, so a watch never backfills history it was not asked about:

- charges and deposits: a row stored (`created_at`) after the watch was armed AND dated no more
  than `RECENT_DAYS` before it. A first bank link after the watch was set brings months of rows
  with a fresh `created_at`; the date bound keeps them quiet. A pending row that the bank later
  posts is one fact, by the bank's own link (`pending_transaction_id`): `ingest/plaid_sync` hands
  it to `watches/links.py`, which records the posted id as already said in the same transaction.
- grades: every grade already on file when the watch was armed is recorded as a baseline first.
- balances: edge-triggered. It fires when an account crosses under, and re-arms only once that
  account's balance is back at or over the amount (`Watch.state`).
- Canvas due and service due are states, not events: an item already inside the window when the
  watch is set fires once, keyed by the item and its due date, so a moved due date fires again.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field
from zoneinfo import ZoneInfo

from django.db import IntegrityError, transaction
from django.db.models import Q

from brief.compose import Viewer, clock_words, day_words, money, short_date
from household.tenancy import scoped, visible
from household.timezones import zone_named
from watches.models import (
    KIND_BALANCE_UNDER,
    KIND_BUDGET_PACE,
    KIND_CANVAS_DUE,
    KIND_CHARGE_OVER,
    KIND_DEPOSIT_OVER,
    KIND_GRADE_POSTED,
    KIND_SERVICE_DUE,
    Watch,
    WatchFiring,
)
from watches.registry import DEFAULT_FIRING_LIFE

RECENT_DAYS = 3
LOOKBACK = dt.timedelta(days=7)
# A service item overdue by more than this is old data, not news (vetoable pick).
SERVICE_OVERDUE_DAYS = 30
FIRING_KEEP = dt.timedelta(days=60)
# Kinds whose facts are bounded in time by the evaluator itself (money rows within LOOKBACK, a due
# time in the future, a balance as-of time), so a pruned firing can never fire again.
PRUNABLE_KINDS = (
    KIND_CHARGE_OVER, KIND_DEPOSIT_OVER, KIND_BALANCE_UNDER, KIND_CANVAS_DUE, KIND_BUDGET_PACE
)
CREDIT_TYPES = frozenset({"credit", "loan"})
DEPOSIT_TYPES = frozenset({"depository"})


@dataclass
class Fact:
    key: str
    facts: str
    sources: list[dict]
    detail: dict = field(default_factory=dict)
    expires_at: dt.datetime | None = None
    baseline: bool = False


@dataclass
class Report:
    watches: int = 0
    fired: int = 0
    baselined: int = 0
    pruned: int = 0
    errors: list[str] = field(default_factory=list)


def zone_of(household) -> ZoneInfo:
    return zone_named(getattr(household, "timezone", None)) or ZoneInfo("UTC")


def when_words(moment: dt.datetime, zone) -> str:
    """`Thu Nov 12, 11:59 PM`, in the household's zone. A household with no zone set gets UTC and
    says so in words (`household/timezones.py`: each caller states its fallback), never a UTC time
    passed off as local."""
    local = moment.astimezone(zone)
    utc = " UTC" if getattr(zone, "key", None) == "UTC" else ""
    return f"{day_words(local.date())}, {clock_words(local)}{utc}"


# =============================================================================
# Kinds
# =============================================================================


def _plaid_accounts(viewer) -> list[dict]:
    from ingest.models import PlaidItem

    out: list[dict] = []
    for item in scoped(PlaidItem, viewer):
        for account in item.accounts or []:
            if isinstance(account, dict):
                out.append(account)
    return out


def _account_types(viewer) -> dict[str, str]:
    """`account_last4` -> Plaid's account type, for the accounts a bank link reports."""
    return {
        str(a.get("mask")): str(a.get("type") or "")
        for a in _plaid_accounts(viewer)
        if a.get("mask")
    }


def _txn_rows(viewer, watch: Watch, now: dt.datetime, outgoing: bool):
    from legacy.models.ledger import LedgerTransaction

    p = watch.params
    floor = (watch.armed_at - dt.timedelta(days=RECENT_DAYS)).date()
    rows = (
        scoped(LedgerTransaction, viewer)
        .filter(
            currency=p.get("currency", "USD"),
            # Rows stored before arming never fire; rows stored over a week ago were already seen.
            created_at__gte=max(watch.armed_at, now - LOOKBACK),
            # Dated near arming AND near now: a re-link re-inserting old rows never reads them out.
            txn_date__gte=max(floor, (now - LOOKBACK).date()),
            txn_date__lte=(now + dt.timedelta(days=1)).date(),
        )
        .exclude(provenance="UNRECONCILED")
    )
    if outgoing:
        rows = rows.filter(amount_cents__lte=-p["amount_cents"])
        name = p.get("merchant")
    else:
        rows = rows.filter(amount_cents__gte=p["amount_cents"])
        name = p.get("payer")
    if name:
        rows = rows.filter(description__icontains=name)
    return rows.order_by("created_at", "id")


def _money_fact(viewer, watch: Watch, now: dt.datetime, outgoing: bool) -> list[Fact]:
    types = _account_types(viewer) if not outgoing else {}
    seen = set(
        WatchFiring.objects.filter(
            household=watch.household, owner_user=watch.owner_user, fact_key__startswith="txn:"
        ).values_list("fact_key", flat=True)
    )
    out = []
    for row in _txn_rows(viewer, watch, now, outgoing):
        if not outgoing and types.get(row.account_last4, "") in CREDIT_TYPES:
            # A card payment or a refund on a card is not money landing in an account.
            continue
        key_id = row.bank_transaction_id or str(row.id)
        key = f"txn:{key_id}"
        if key in seen:
            continue
        what = "charge" if outgoing else "deposit"
        prep = "at" if outgoing else "from"
        into = "on" if outgoing else "into"
        pending = " The bank still shows it pending." if row.bank_pending else ""
        text = (
            f"A {what} of {money(abs(row.amount_cents), row.currency)} {prep} "
            f"{row.description}, {into} the account ending {row.account_last4}, dated "
            f"{day_words(row.txn_date)}.{pending}"
        )
        out.append(
            Fact(
                key=key,
                facts=text,
                sources=[{"table": "ledger_transactions", "id": str(row.id)}],
                detail={
                    "amount_cents": row.amount_cents,
                    "currency": row.currency,
                    "account": row.account_last4,
                    "pending": bool(row.bank_pending),
                    "date": row.txn_date.isoformat(),
                },
            )
        )
    return out


def charge_over(viewer, watch, now):
    return _money_fact(viewer, watch, now, outgoing=True)


def deposit_over(viewer, watch, now):
    return _money_fact(viewer, watch, now, outgoing=False)


def balance_under(viewer, watch, now) -> list[Fact]:
    p = watch.params
    threshold = p["amount_cents"]
    under = dict((watch.state or {}).get("under") or {})
    zone = zone_of(watch.household)
    out = []
    seen = set()
    for a in _plaid_accounts(viewer):
        if str(a.get("type") or "") not in DEPOSIT_TYPES:
            continue
        if p.get("account") and str(a.get("mask")) != p["account"]:
            continue
        if a.get("balance_currency") and a.get("balance_currency") != p.get("currency", "USD"):
            continue
        cents = a.get("balance_available_cents")
        which = "available balance"
        if cents is None:
            cents = a.get("balance_current_cents")
            which = "balance"
        account_id = str(a.get("account_id") or a.get("mask") or "")
        if cents is None or not account_id:
            continue
        seen.add(account_id)
        if cents >= threshold:
            under.pop(account_id, None)
            continue
        as_of = a.get("balance_as_of") or ""
        if account_id in under:
            continue
        under[account_id] = as_of
        try:
            when = when_words(dt.datetime.fromisoformat(as_of), zone) if as_of else None
        except ValueError:
            when = None
        as_of_words = f" as of {when}" if when else ""
        name = a.get("name") or "the account"
        out.append(
            Fact(
                key=f"balance:{account_id}:{as_of}",
                facts=(
                    f"The {which} on {name} ending {a.get('mask')} was "
                    f"{money(cents, p.get('currency', 'USD'))}{as_of_words}, under the "
                    f"{money(threshold, p.get('currency', 'USD'))} the watch is set at."
                ),
                sources=[{"table": "plaid_items", "account": account_id}],
                detail={"balance_cents": cents, "account": str(a.get("mask")), "as_of": as_of},
            )
        )
    watch.state = {**(watch.state or {}), "under": {k: v for k, v in under.items()}}
    return out


def _canvas_items(viewer):
    from legacy.models.dates import Event

    return (
        visible(Event, viewer)
        .filter(deleted_at__isnull=True)
        .filter(
            Q(structured_meta__has_key="canvas_assignment_id")
            | Q(structured_meta__has_key="parent_canvas_assignment_id")
        )
    )


def canvas_due(viewer, watch, now) -> list[Fact]:
    hours = watch.params.get("hours", 24)
    zone = zone_of(watch.household)
    rows = _canvas_items(viewer).filter(
        done=False, starts_at__gt=now, starts_at__lte=now + dt.timedelta(hours=hours)
    )
    out = []
    for row in rows.order_by("starts_at", "title"):
        meta = row.structured_meta or {}
        if meta.get("canvas_submitted") is True:
            continue
        said = " Canvas says not submitted." if meta.get("canvas_submitted") is False else ""
        out.append(
            Fact(
                key=f"due:{row.id}:{row.starts_at.isoformat()}",
                # Absolute time only: "in N hours" would be wrong by the time a late poll says it.
                facts=f"{row.title} is due {when_words(row.starts_at, zone)}.{said}",
                sources=[{"table": "events", "id": str(row.id)}],
                detail={"due_at": row.starts_at.isoformat()},
                expires_at=row.starts_at,
            )
        )
    return out


def _grade_words(meta: dict) -> str | None:
    grade, score, possible = meta.get("grade"), meta.get("score"), meta.get("points_possible")
    if grade in (None, "") and score is None:
        return None
    parts = []
    if score is not None:
        parts.append(
            f"{score:g} out of {possible:g}"
            if isinstance(possible, (int, float)) and isinstance(score, (int, float))
            else f"score {score}"
        )
    if grade not in (None, "") and str(grade) != str(score):
        parts.append(f"grade {grade}")
    return ", ".join(parts)


def grade_posted(viewer, watch, now) -> list[Fact]:
    course = (watch.params.get("course") or "").lower()
    baseline = watch.baselined_at is None
    out = []
    for row in _canvas_items(viewer).order_by("title"):
        meta = row.structured_meta or {}
        if "parent_canvas_assignment_id" in meta:
            continue
        words = _grade_words(meta)
        if words is None:
            continue
        if course and course not in row.title.lower():
            continue
        out.append(
            Fact(
                key=f"grade:{row.id}:{meta.get('grade')}:{meta.get('score')}",
                facts=f"A grade is on Canvas for {row.title}: {words}.",
                sources=[{"table": "events", "id": str(row.id)}],
                detail={"grade": meta.get("grade"), "score": meta.get("score")},
                baseline=baseline,
            )
        )
    if baseline:
        watch.baselined_at = now
    return out


def service_due(viewer, watch, now) -> list[Fact]:
    from brief.maintenance import latest_odometer, schedule_status
    from legacy.models.fleet import Vehicle

    days = watch.params.get("days", 14)
    miles = watch.params.get("miles", 500)
    zone = zone_of(watch.household)
    today = now.astimezone(zone).date()
    out = []
    vehicles = scoped(Vehicle, viewer).filter(deleted_at__isnull=True, archived=False)
    for vehicle in vehicles.order_by("name"):
        odometer = latest_odometer(viewer, vehicle)
        for item in schedule_status(viewer, vehicle):
            if item.never_logged:
                continue
            by_date = item.due_on is not None and today - dt.timedelta(
                days=SERVICE_OVERDUE_DAYS
            ) <= item.due_on <= today + dt.timedelta(days=days)
            by_miles = (
                miles > 0
                and item.due_at_miles is not None
                and odometer is not None
                and item.due_at_miles - odometer.miles <= miles
                and odometer.miles - item.due_at_miles <= miles * 4
            )
            if not (by_date or by_miles):
                continue
            parts = []
            if by_date:
                verb = "was due" if item.due_on < today else "is due"
                parts.append(
                    f"{verb} {short_date(item.due_on)} by the dates on file (every "
                    f"{item.interval_months} months, last done {short_date(item.last_done_on)})"
                )
            if by_miles:
                left = item.due_at_miles - odometer.miles
                where = f"about {left:,} miles to go" if left > 0 else f"about {-left:,} miles past"
                parts.append(
                    f"comes due at {item.due_at_miles:,} miles, {where} by the last odometer "
                    f"reading on file ({odometer.miles:,} on {short_date(odometer.read_on)}), "
                    f"an estimate"
                )
            out.append(
                Fact(
                    key=(
                        f"service:{vehicle.id}:{item.service_name.strip().lower()}:"
                        f"{item.due_on or ''}:{item.due_at_miles or ''}"
                    ),
                    facts=f"{vehicle.name}: {item.service_name} {' and '.join(parts)}.",
                    sources=[
                        {
                            "table": "maintenance_schedules",
                            "vehicle": str(vehicle.id),
                            "service": item.service_name,
                        }
                    ],
                    detail={
                        "due_on": item.due_on.isoformat() if item.due_on else None,
                        "due_at_miles": item.due_at_miles,
                        "miles_estimate": by_miles,
                    },
                )
            )
    return out


def _pace_line(
    line: dict, over_percent: int, elapsed: int, days: int, month_name: str
) -> str | None:
    """The sentence for one category, or None when it is on pace. Integer cents throughout."""
    spent, cap, projection = line["spent_cents"], line["cap_cents"], line["projection_cents"]
    if not cap:
        return None
    unverified = " That figure includes rows not yet verified." if line.get("unverified") else ""
    head = (
        f"{line['category']}: {money(spent)} spent of a {money(cap)} budget "
        f"by day {elapsed} of {days}."
    )
    if spent >= cap:
        return f"{head} It is already over.{unverified}"
    if projection is None or projection * 100 < cap * (100 + over_percent):
        return None
    return (
        f"{head} At this pace it reaches about {money(projection)} by the end of {month_name}, "
        f"an estimate.{unverified}"
    )


def budget_pace(viewer, watch, now) -> list[Fact]:
    from api.spend import Month
    from finance.budgets import budgets

    household = watch.household
    zone = zone_of(household)
    today = now.astimezone(zone).date()
    month = Month.of(today)
    body = budgets(viewer, household, month, today)
    wanted = (watch.params.get("category") or "").lower()
    over = watch.params.get("over_percent", 10)
    month_name = today.strftime("%B")
    month_end = dt.datetime.combine(
        month.last + dt.timedelta(days=1), dt.time.min, tzinfo=zone
    )
    out = []
    for line in body["categories"]:
        if wanted and wanted not in line["category"].lower():
            continue
        words = _pace_line(line, over, body["days_elapsed"], body["days_in_month"], month_name)
        if words is None:
            continue
        out.append(
            Fact(
                # Once per category per month, whatever the figures do after.
                key=f"pace:{month}:{line['category']}",
                facts=words,
                sources=[
                    {"table": "budget_targets", "category": line["category"], "month": str(month)}
                ],
                detail={
                    "category": line["category"],
                    "spent_cents": line["spent_cents"],
                    "cap_cents": line["cap_cents"],
                    "projection_cents": line["projection_cents"],
                    "projection_is_estimate": True,
                },
                expires_at=month_end,
            )
        )
    return out


EVALUATORS = {
    KIND_CHARGE_OVER: charge_over,
    KIND_DEPOSIT_OVER: deposit_over,
    KIND_BALANCE_UNDER: balance_under,
    KIND_CANVAS_DUE: canvas_due,
    KIND_GRADE_POSTED: grade_posted,
    KIND_SERVICE_DUE: service_due,
    KIND_BUDGET_PACE: budget_pace,
}


# =============================================================================
# The job
# =============================================================================


def live_watches(household, now: dt.datetime):
    """The watches that may fire now: not removed, paused, spent or expired, set by a member
    who may use watches (never a recruiter visit)."""
    from household.models import HouseholdMember

    members = set(
        HouseholdMember.objects.filter(household=household)
        .exclude(role=HouseholdMember.RECRUITER)
        .values_list("user_id", flat=True)
    )
    # A phone-evaluated kind (mail_reply) is never evaluated here: its data never reaches the
    # engine, and the engine holds no firing for it.
    rows = (
        Watch.objects.filter(
            household=household,
            kind__in=list(EVALUATORS),
            deleted_at__isnull=True,
            paused=False,
            spent_at__isnull=True,
        )
        .filter(Q(expires_at__isnull=True) | Q(expires_at__gt=now))
        .select_related("owner_user")
    )
    return [w for w in rows.order_by("created_at", "id") if w.owner_user_id in members]


def record(watch: Watch, fact: Fact, now: dt.datetime) -> WatchFiring | None:
    """Inserts the firing unless this member already has one for the same fact."""
    if WatchFiring.objects.filter(
        household=watch.household, owner_user=watch.owner_user, fact_key=fact.key
    ).exists():
        return None
    try:
        with transaction.atomic():
            return WatchFiring.objects.create(
                household=watch.household,
                owner_user=watch.owner_user,
                watch=watch,
                kind=watch.kind,
                fact_key=fact.key,
                facts=fact.facts,
                detail=fact.detail,
                sources=fact.sources,
                baseline=fact.baseline,
                fired_at=now,
                expires_at=fact.expires_at or now + DEFAULT_FIRING_LIFE,
            )
    except IntegrityError:
        return None


def evaluate_watch(watch: Watch, now: dt.datetime, report: Report) -> None:
    # One transaction per watch: its firings and its state (balance_under's "already under",
    # grade_posted's baseline) commit together, so a crash between them can never make the next
    # run fire the same fact again.
    with transaction.atomic():
        _evaluate_watch(watch, now, report)


def _evaluate_watch(watch: Watch, now: dt.datetime, report: Report) -> None:
    viewer = Viewer(watch.owner_user)
    facts = EVALUATORS[watch.kind](viewer, watch, now)
    fired = False
    for fact in facts:
        made = record(watch, fact, now)
        if made is None:
            continue
        if made.baseline:
            report.baselined += 1
        else:
            report.fired += 1
            fired = True
    fields = ["state", "baselined_at"]
    if fired:
        watch.last_fired_at = now
        fields.append("last_fired_at")
        if not watch.repeat:
            watch.spent_at = now
            fields.append("spent_at")
    Watch.objects.filter(pk=watch.pk).update(**{f: getattr(watch, f) for f in fields})


def evaluate_household(household, now: dt.datetime) -> Report:
    report = Report()
    if getattr(household, "is_demo_visit", False):
        return report
    for watch in live_watches(household, now):
        report.watches += 1
        try:
            evaluate_watch(watch, now, report)
        except Exception as exc:  # noqa: BLE001 - one bad watch never stops the others
            report.errors.append(f"{watch.id}: {type(exc).__name__}")
    # Only facts that cannot come back are pruned. A grade or a service item stays recorded
    # (baseline rows included), or it would be read out again once its row was gone.
    report.pruned, _ = WatchFiring.objects.filter(
        household=household, kind__in=PRUNABLE_KINDS, fired_at__lt=now - FIRING_KEEP
    ).delete()
    return report
