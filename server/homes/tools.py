"""The agent tools over the engine homes (engine-brain ticket 03): engine twins of the phone's
goal, pending-transaction, advisor and `why_did_you_say_that` tools.

Like `agents/legion/twins.py`, these are `EngineTool`s kept OUT of `engine_mcp.tools.TOOLS`: the
registry feeds `/mcp`, the web assistant's Live token and the recruiter classification, and this
stage changes none of them. The agent gateway runs them through `run_engine_tool`, so every write
here gets the write-scope fence and the ADR 0090 read-through refusal, and every read goes
through `visible()` or `scoped()`.

Each tool makes few round trips (stage 0 measured about 35 ms per Postgres query from the
engine): a read is one query, a write at most three.

| Tool | Phone tool it twins | Reads or writes |
|---|---|---|
| `list_goals` | `goal list_goals` | reads `goals` |
| `set_goal` | `goal set_goal` | writes a goal, or a new revision of one |
| `close_goal` | `goal close_goal` | marks a goal achieved or abandoned |
| `list_pending_transactions` | `money list_pending_transactions` | reads `pending_transactions` |
| `log_pending_transaction` | `money log_pending_transaction` | writes one, unverified |
| `clear_pending_transaction` | `money clear_pending_transaction` | tombstones one |
| `recent_advice` | the advisor's own log (`advisor_advice`) | reads |
| `why_did_you_say_that` | `day why_did_you_say_that` | reads `proactive_raises` |
"""

from __future__ import annotations

import json
import random
import uuid
from datetime import datetime
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation

from engine_mcp import tools as reg
from engine_mcp.tools import EngineTool, ok, refused
from homes.models import AdvisorAdvice, PendingTransaction, ProactiveRaise
from household.tenancy import household_of, scoped, visible

PENDING_AMOUNT_MAX_CENTS = 100_000_000
ADVICE_LIMIT = 10
PENDING_LIMIT = 50
CLOSED_STATUSES = ("achieved", "abandoned")
# The phone's goal vocabulary (`goals/GoalController.ASPECTS`, `advisor/AdvisorAspect.kt`): the
# stored key, and what it is called aloud. A goal the engine writes must carry the phone's key, or
# the phone's own goal panel (which reads by key) would never show it.
GOAL_AREAS = {"bio": "body", "log": "notes", "fleet": "fleet", "cred": "money"}
_AREA_ARG = {
    "type": "string",
    "enum": list(GOAL_AREAS),
    "description": "bio = body (weight, food, sleep, training); log = notes, reading, anything "
    "personal; fleet = the cars; cred = money.",
}
_WRITE = " Report it done only when this tool's result says it was committed."


def _dumps(value) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def _zone(request):
    return reg._household_zone(request)


def _local_day(at, zone) -> str | None:
    return at.astimezone(zone).date().isoformat() if at is not None else None


# -- goals ---------------------------------------------------------------------------------------


def _goal_model():
    from legacy.models.body import Goal

    return Goal


def _current_goals(request, include_closed: bool, aspect: str | None):
    """The current row of each lineage (its newest revision), one query."""
    rows = scoped(_goal_model(), request).filter(deleted_at__isnull=True)
    if aspect:
        rows = rows.filter(aspect__iexact=aspect)
    current: dict[int, object] = {}
    revisions: dict[int, int] = {}
    for row in rows.order_by("lineage_id", "created_at_client", "created_at"):
        current[row.lineage_id] = row
        revisions[row.lineage_id] = revisions.get(row.lineage_id, 0) + 1
    out = [g for g in current.values() if include_closed or g.status == "active"]
    out.sort(key=lambda g: g.created_at_client, reverse=True)
    return out, revisions


def _goal_line(goal, revisions: int, zone) -> dict:
    line = {
        "goal_id": goal.origin_guid,
        "aspect": goal.aspect,
        "area": GOAL_AREAS.get(goal.aspect, goal.aspect),
        "statement": goal.statement,
        "status": goal.status,
    }
    if goal.target_value is not None:
        line["target"] = f"{goal.target_value:g}{(' ' + goal.unit) if goal.unit else ''}"
    if goal.deadline_epoch is not None:
        line["deadline"] = _local_day(goal.deadline_epoch, zone)
    if goal.closed_at is not None:
        line["closed_on"] = _local_day(goal.closed_at, zone)
    if revisions > 1:
        line["revisions"] = revisions
    return line


def _list_goals(request, args):
    include_closed = bool(args.get("include_closed"))
    goals, revisions = _current_goals(request, include_closed, (args.get("aspect") or "").strip())
    zone = _zone(request)
    lines = [_goal_line(g, revisions[g.lineage_id], zone) for g in goals]
    if not lines:
        which = "goals" if include_closed else "active goals"
        return ok(f"No {which} on file. That is a real empty result.", structured={"goals": []})
    return ok(_dumps({"goals": lines}), structured={"goals": lines})


def _find_goal(request, goal_id: str):
    """The current row of the lineage `goal_id` names (any revision's id names its lineage)."""
    Goal = _goal_model()
    named = scoped(Goal, request).filter(origin_guid=goal_id, deleted_at__isnull=True).first()
    if named is None:
        return None
    return (
        scoped(Goal, request)
        .filter(lineage_id=named.lineage_id, deleted_at__isnull=True)
        .order_by("-created_at_client", "-created_at")
        .first()
    )


def _deadline(raw, zone):
    if raw in (None, ""):
        return None
    day = reg._named_day(str(raw), zone)
    if day is None:
        raise reg.BadArgument("`deadline` must be a day, YYYY-MM-DD")
    return datetime(day.year, day.month, day.day, 23, 59, tzinfo=zone)


def _set_goal(request, args):
    from legacy.enums import Provenance

    Goal = _goal_model()
    zone = _zone(request)
    statement = (args.get("statement") or "").strip()
    if not statement:
        return refused("Nothing was written. A goal needs his own words in `statement`.")
    try:
        deadline = _deadline(args.get("deadline"), zone)
    except reg.BadArgument as exc:
        return refused(f"Nothing was written. {exc}.")
    revises = (args.get("revises_goal_id") or "").strip()
    prior = None
    if revises:
        prior = _find_goal(request, revises)
        if prior is None:
            return refused(
                f"Nothing was written. No goal has id {revises!r}; list_goals gives the ids."
            )
    aspect = (args.get("aspect") or (prior.aspect if prior else "")).strip()
    if aspect not in GOAL_AREAS:
        return refused(
            "Nothing was written. `aspect` is one of bio (body), log (notes), fleet or cred "
            "(money)."
        )
    now = reg._now()
    row = Goal(
        id=uuid.uuid4(),
        household=household_of(request),
        lineage_id=prior.lineage_id if prior else random.getrandbits(63),
        aspect=aspect,
        statement=statement,
        target_value=args.get("target_value"),
        unit=(args.get("unit") or None),
        metric_key=(args.get("metric_key") or (prior.metric_key if prior else None)),
        deadline_epoch=deadline,
        status="active",
        supersedes_guid=prior.origin_guid if prior else None,
        closed_at=None,
        created_at_client=now,
        provenance=Provenance.USER,
        created_at=now,
        updated_at=now,
        origin_guid=str(uuid.uuid4()),
    )
    row.save(force_insert=True)
    verb = "Revised" if prior else "Set"
    line = _goal_line(row, 2 if prior else 1, zone)
    return ok(
        f"{verb} and committed: {_dumps(line)}",
        structured={"goal": line, "revised": bool(prior)},
    )


def _close_goal(request, args):
    goal_id = (args.get("goal_id") or "").strip()
    status = args.get("status")
    if status not in CLOSED_STATUSES:
        return refused(f"Nothing was written. `status` is one of {', '.join(CLOSED_STATUSES)}.")
    current = _find_goal(request, goal_id)
    if current is None:
        return refused(
            f"Nothing was written. No goal has id {goal_id!r}; list_goals gives the ids."
        )
    if current.status != "active":
        return refused(f"Nothing was written. That goal is already {current.status}, not active.")
    current.status = status
    current.closed_at = reg._now()
    current.save(update_fields=["status", "closed_at"])
    return ok(
        f'Committed: the goal "{current.statement}" is now {status}.',
        structured={"goal_id": current.origin_guid, "status": status},
    )


# -- pending transactions ------------------------------------------------------------------------


def _accounts(request) -> list[tuple[str, str]]:
    from legacy.models.ledger import LedgerTransaction

    pairs = (
        scoped(LedgerTransaction, request).values_list("account_nickname", "currency").distinct()
    )
    return sorted({(a, c) for a, c in pairs if a})


def _same_card(a: str, b: str) -> bool:
    return a == b or (len(a) >= 4 and len(b) >= 4 and a[-4:] == b[-4:])


def _resolve_account(accounts, spoken: str):
    """`ledger/LedgerPendingLog.resolveAccountForPending`, verbatim rules."""
    spoken = spoken.strip()
    if not spoken:
        matched = accounts
    else:
        matched = [
            (a, c) for a, c in accounts if spoken.lower() in a.lower() or _same_card(a, spoken)
        ]
    return matched


def _cents(amount) -> int:
    try:
        value = Decimal(str(amount))
    except (InvalidOperation, ValueError) as exc:
        raise reg.BadArgument("`amount` must be a number") from exc
    if value <= 0:
        raise reg.BadArgument("`amount` is the size of the charge, above zero; say the direction")
    return int((value * 100).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def _pending_line(row, zone) -> dict:
    return {
        "pending_id": row.origin_guid,
        "account": row.account_id,
        "date": row.txn_date.isoformat(),
        "description": row.description,
        "amount_cents": row.amount_cents,
        "currency": row.currency,
        "logged_on": _local_day(row.logged_at, zone),
    }


PENDING_SAYS = (
    "These are his own spoken words, not verified by any bank. Say so when you read them out; "
    "the bank's own row replaces each one when it arrives."
)


def _list_pending(request, args):
    rows = list(
        scoped(PendingTransaction, request)
        .filter(deleted_at__isnull=True)
        .order_by("-logged_at")[:PENDING_LIMIT]
    )
    zone = _zone(request)
    if not rows:
        return ok(
            "No pending transactions are logged. That is a real empty result.",
            structured={"pending": []},
        )
    lines = [_pending_line(r, zone) for r in rows]
    return ok(
        _dumps({"pending": lines, "says": PENDING_SAYS}),
        structured={"pending": lines, "says": PENDING_SAYS},
    )


def _log_pending(request, args):
    zone = _zone(request)
    direction = args.get("direction")
    if direction not in ("debit", "credit"):
        return refused(
            "Nothing was written. `direction` is debit (money out) or credit (money in)."
        )
    description = (args.get("description") or "").strip()
    if not description:
        return refused("Nothing was written. Say what it was in `description`.")
    try:
        cents = _cents(args.get("amount"))
        day = reg._named_day(str(args.get("date") or "today"), zone)
        if day is None:
            raise reg.BadArgument("`date` must be a day, YYYY-MM-DD (or today)")
    except reg.BadArgument as exc:
        return refused(f"Nothing was written. {exc}.")
    if cents > PENDING_AMOUNT_MAX_CENTS:
        return refused(
            "Nothing was written. That amount is beyond what a spoken charge can be; "
            "it is likely a misheard number. Ask him to say it again."
        )
    accounts = _accounts(request)
    if not accounts:
        return refused(
            "Nothing was written. No account is on file yet, so there is nothing to log a "
            "pending charge against."
        )
    matched = _resolve_account(accounts, args.get("account") or "")
    if not matched:
        names = ", ".join(a for a, _ in accounts)
        return refused(f"Nothing was written. No account matches that; the accounts are: {names}.")
    if len(matched) > 1:
        names = ", ".join(a for a, _ in matched)
        return refused(f"Nothing was written. More than one account matches; ask which: {names}.")
    account, currency = matched[0]
    signed = -cents if direction == "debit" else cents
    row = PendingTransaction.objects.create(
        household=household_of(request),
        origin_guid=str(uuid.uuid4()),
        account_id=account,
        currency=currency,
        txn_date=day,
        description=description,
        amount_cents=signed,
        logged_at=reg._now(),
    )
    row.refresh_from_db()
    line = _pending_line(row, zone)
    return ok(
        f"Logged and committed, unverified: {_dumps(line)}",
        structured={"pending": line},
    )


def _clear_pending(request, args):
    pending_id = (args.get("pending_id") or "").strip()
    row = (
        scoped(PendingTransaction, request)
        .filter(origin_guid=pending_id, deleted_at__isnull=True)
        .first()
    )
    if row is None:
        return refused(
            f"Nothing was cleared. No pending transaction has id {pending_id!r}; "
            f"list_pending_transactions gives the ids."
        )
    now = reg._now()
    scoped(PendingTransaction, request).filter(pk=row.pk).update(deleted_at=now, updated_at=now)
    return ok(
        f"Cleared and committed: {row.description}, {row.amount_cents} cents {row.currency}.",
        structured={"pending_id": pending_id, "cleared": True},
    )


# -- advice and raises ---------------------------------------------------------------------------


def _recent_advice(request, args):
    aspect = (args.get("aspect") or "").strip()
    rows = visible(AdvisorAdvice, request).filter(deleted_at__isnull=True)
    if aspect:
        rows = rows.filter(aspect__iexact=aspect)
    rows = list(rows.order_by("-given_at")[:ADVICE_LIMIT])
    zone = _zone(request)
    if not rows:
        return ok("No advice on file. That is a real empty result.", structured={"advice": []})
    lines = [
        {
            "aspect": r.aspect,
            "asked": r.question_text,
            "gist": r.gist,
            "outcome": r.outcome,
            "given_on": _local_day(r.given_at, zone),
            "has_proposal": bool(r.proposal_json),
        }
        for r in rows
    ]
    return ok(_dumps({"advice": lines}), structured={"advice": lines})


DELIVERY_WORDS = {
    "spoken": "said aloud",
    "rung": "rung as an emergency alarm",
    "notified": "posted as a notification",
}


def _why(request, args):
    row = visible(ProactiveRaise, request).order_by("-spoken_at").first()
    if row is None:
        return ok(
            "You have not raised anything on your own recently. That is a real empty result.",
            structured={"raise": None},
        )
    zone = _zone(request)
    when = row.spoken_at.astimezone(zone)
    delivery = DELIVERY_WORDS.get(row.delivery, "posted as a notification")
    return ok(
        f'Rule "{row.rule_id}" ({row.category}) fired at {when:%H:%M} on {when:%Y-%m-%d} and was '
        f"{delivery}. The fact behind it: {row.reason}. Say the rule and the fact plainly; do "
        f"not justify it.",
        structured={
            "rule_id": row.rule_id,
            "category": row.category,
            "reason": row.reason,
            "delivery": row.delivery,
            "declined": row.declined,
        },
    )


def _obj(props: dict, required: tuple[str, ...] = ()) -> dict:
    return {
        "type": "object",
        "properties": props,
        "required": list(required),
        "additionalProperties": False,
    }


LIST_GOALS = EngineTool(
    name="list_goals",
    title="His goals",
    description=(
        "Read his goals: each one's current wording, target, deadline and status, newest first, "
        "with its `goal_id`. Active only unless include_closed. Read-only."
    ),
    input_schema=_obj(
        {
            "aspect": _AREA_ARG,
            "include_closed": {"type": "boolean"},
        }
    ),
    handler=_list_goals,
    writes=False,
)

SET_GOAL = EngineTool(
    name="set_goal",
    title="Set or revise a goal",
    description=(
        "Set a goal he states, in his own words (`statement`), with an optional number, unit "
        "and deadline, under the `aspect` it belongs to. To change one, pass its "
        "`revises_goal_id`: the old wording is kept as history and this becomes the current one. "
        "Then say the goal back in his words." + _WRITE
    ),
    input_schema=_obj(
        {
            "statement": {"type": "string", "minLength": 1},
            "aspect": _AREA_ARG,
            "target_value": {"type": "number"},
            "unit": {"type": "string"},
            "metric_key": {"type": "string"},
            "deadline": {"type": "string", "description": "YYYY-MM-DD"},
            "revises_goal_id": {"type": "string"},
        },
        ("statement",),
    ),
    handler=_set_goal,
    writes=True,
)

CLOSE_GOAL = EngineTool(
    name="close_goal",
    title="Close a goal",
    description=(
        "Mark one of his goals achieved or abandoned, by its `goal_id` from list_goals." + _WRITE
    ),
    input_schema=_obj(
        {
            "goal_id": {"type": "string", "minLength": 1},
            "status": {"type": "string", "enum": list(CLOSED_STATUSES)},
        },
        ("goal_id", "status"),
    ),
    handler=_close_goal,
    writes=True,
)

LIST_PENDING = EngineTool(
    name="list_pending_transactions",
    title="Pending charges he logged",
    description=(
        "Read the charges and credits he logged by voice that the bank has not shown yet, each "
        "with its `pending_id`. They are unverified: say so. Read-only."
    ),
    input_schema=_obj({}),
    handler=_list_pending,
    writes=False,
)

LOG_PENDING = EngineTool(
    name="log_pending_transaction",
    title="Log a pending charge",
    description=(
        "Log a charge (debit) or credit he states, before the bank shows it. `amount` is the "
        "size, above zero, in the account's currency; `direction` says which way. `account` is "
        "matched to an account on file; if none or several match, ask. Stored as unverified."
        + _WRITE
    ),
    input_schema=_obj(
        {
            "account": {"type": "string"},
            "description": {"type": "string", "minLength": 1},
            "amount": {"type": "number", "exclusiveMinimum": 0},
            "direction": {"type": "string", "enum": ["debit", "credit"]},
            "date": {"type": "string", "description": "YYYY-MM-DD; default today."},
        },
        ("description", "amount", "direction"),
    ),
    handler=_log_pending,
    writes=True,
)

CLEAR_PENDING = EngineTool(
    name="clear_pending_transaction",
    title="Clear a pending charge",
    description=(
        "Clear one pending charge he logged, by its `pending_id`, when the bank's row has "
        "arrived or it was a mistake." + _WRITE
    ),
    input_schema=_obj({"pending_id": {"type": "string", "minLength": 1}}, ("pending_id",)),
    handler=_clear_pending,
    writes=True,
)

RECENT_ADVICE = EngineTool(
    name="recent_advice",
    title="Advice already given",
    description=(
        "Read the advice the advisor gave him lately, per aspect, with what became of each "
        "proposal. Read-only."
    ),
    input_schema=_obj({"aspect": {"type": "string"}}),
    handler=_recent_advice,
    writes=False,
)

WHY_DID_YOU_SAY_THAT = EngineTool(
    name="why_did_you_say_that",
    title="Why that was said",
    description=(
        "When he asks why you said or posted something on your own: the rule that fired and the "
        "fact behind it, from the most recent raise. Read-only."
    ),
    input_schema=_obj({}),
    handler=_why,
    writes=False,
)

HOME_TOOLS: tuple[EngineTool, ...] = (
    LIST_GOALS,
    SET_GOAL,
    CLOSE_GOAL,
    LIST_PENDING,
    LOG_PENDING,
    CLEAR_PENDING,
    RECENT_ADVICE,
    WHY_DID_YOU_SAY_THAT,
)
