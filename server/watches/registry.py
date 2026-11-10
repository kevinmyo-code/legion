"""The closed registry of watch kinds (ADR 0083). A watch is one of these, with typed params,
and nothing else: there is no free-text condition and no model judging one per tick
(proactive-mode decisions 8 and 11). A request that fits no kind is refused in words that name the
kinds there are and the nearest one (`unknown_kind_refusal`).

**Kinds only over data the engine verifiably holds** (checked against the schema 2026-11-10):

- `charge_over`: `ledger_transactions` money out (`amount_cents < 0`); one charge at or over the
  amount lands after the watch was armed.
- `deposit_over`: `ledger_transactions` money in, not on a credit account; one deposit at or over
  the amount lands.
- `balance_under`: `plaid_items.accounts` (the bank's own balance and its as-of time); a deposit
  account's balance crosses under the amount.
- `canvas_due`: `events` with Canvas metadata, private to their member; an unsubmitted, unticked
  Canvas item is due within the hours set.
- `grade_posted`: `events.structured_meta.grade` / `score`, written by `canvas_poll`; a grade or
  score appears or changes after the watch was armed.
- `service_due`: `maintenance_schedules` + `service_history` (`brief/maintenance.py`); a service
  item comes due within the days set (date: fact) or the miles set (estimate).
- `mail_reply`: **nothing on the engine**. Evaluated on the phone over Gmail headers; mail from a
  company on his list arrives, and the phone says only the company name from the list.

Weather and river-gauge kinds wait for `feat/conditions` (not merged): `CONDITIONS_SEAM` is where
they join, and nothing is built for them here.
"""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field

from brief.compose import money
from watches.models import (
    KIND_BALANCE_UNDER,
    KIND_CANVAS_DUE,
    KIND_CHARGE_OVER,
    KIND_DEPOSIT_OVER,
    KIND_GRADE_POSTED,
    KIND_MAIL_REPLY,
    KIND_SERVICE_DUE,
    KINDS,
    LABEL_MAX_CHARS,
)

CURRENCIES: tuple[str, ...] = ("USD", "SGD")
AMOUNT_MAX_CENTS = 100_000_000  # $1,000,000.00: a threshold past this is a typo, not a watch
TEXT_PARAM_MAX = 60
COMPANIES_MAX = 40

# Where a kind is evaluated. ENGINE: `manage.py evaluate_watches` over the engine's rows. PHONE:
# the phone evaluates it over data that must never reach the engine (mail, CLAUDE.md section 7);
# the engine stores the watch (his own params) and never evaluates it or holds a firing for it.
ENGINE = "engine"
PHONE = "phone"


class WatchRefused(Exception):
    """A watch that cannot be set or changed, with the sentence that says why (and that nothing
    was written)."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.message = message
        self.status = status


@dataclass(frozen=True)
class Param:
    name: str
    type: type
    required: bool = False
    default: object = None
    minimum: int | None = None
    maximum: int | None = None
    choices: tuple[str, ...] | None = None
    words: str = ""


@dataclass(frozen=True)
class Kind:
    key: str
    title: str
    # One line a person (or the model, in a refusal) reads to know what it does.
    says: str
    params: tuple[Param, ...] = field(default_factory=tuple)
    # Words that point a spoken request at this kind (for "nearest kinds").
    hints: tuple[str, ...] = ()
    # What has to be linked for it to ever fire, in words; checked at create time.
    needs: str = ""
    evaluated_on: str = ENGINE


_AMOUNT = Param(
    "amount_cents",
    int,
    required=True,
    minimum=1,
    maximum=AMOUNT_MAX_CENTS,
    words="the amount, in integer cents",
)
_CURRENCY = Param("currency", str, default="USD", choices=CURRENCIES, words="USD or SGD")

REGISTRY: dict[str, Kind] = {
    k.key: k
    for k in (
        Kind(
            KIND_CHARGE_OVER,
            "A charge over an amount",
            "one bank or card charge at or over an amount, optionally at a merchant",
            (_AMOUNT, _CURRENCY, Param("merchant", str, words="part of the merchant's name")),
            ("charge", "spend", "spent", "purchase", "bought", "pay ", "paid", "bill", "card"),
            "a linked bank",
        ),
        Kind(
            KIND_DEPOSIT_OVER,
            "A deposit over an amount",
            "money landing in a bank account at or over an amount, optionally from a payer "
            "(a paycheck)",
            (_AMOUNT, _CURRENCY, Param("payer", str, words="part of the payer's name")),
            ("paycheck", "payday", "salary", "deposit", "land", "refund", "transfer in", "paid me"),
            "a linked bank",
        ),
        Kind(
            KIND_BALANCE_UNDER,
            "A balance under an amount",
            "a bank account's balance dropping under an amount",
            (_AMOUNT, _CURRENCY, Param("account", str, words="the account's last four digits")),
            ("balance", "low", "under", "below", "overdraft", "running out"),
            "a linked bank",
        ),
        Kind(
            KIND_CANVAS_DUE,
            "Canvas work coming due",
            "a Canvas assignment not yet submitted, due within some hours",
            (Param("hours", int, default=24, minimum=1, maximum=168, words="hours ahead"),),
            ("due", "assignment", "homework", "canvas", "deadline", "quiz", "exam", "class"),
            "Canvas connected",
        ),
        Kind(
            KIND_GRADE_POSTED,
            "A grade posted",
            "a grade or score appearing on a Canvas assignment, optionally in one course",
            (Param("course", str, words="part of the course name"),),
            ("grade", "graded", "score", "marks", "result"),
            "Canvas connected",
        ),
        Kind(
            KIND_SERVICE_DUE,
            "Car service coming due",
            "a car's service item coming due within some days, or some miles by the last "
            "odometer reading on file (miles are an estimate)",
            (
                Param("days", int, default=14, minimum=1, maximum=90, words="days ahead"),
                Param("miles", int, default=500, minimum=0, maximum=5000, words="miles ahead"),
            ),
            ("service", "oil", "maintenance", "tire", "tyre", "inspection", "car", "brake"),
            "a car with a maintenance schedule",
        ),
        Kind(
            KIND_MAIL_REPLY,
            "A reply from a company",
            "an email from a company on your list (companies you applied to), checked on the "
            "phone; only the company name from your list is ever said",
            (
                Param(
                    "companies",
                    list,
                    required=True,
                    words="the company names (an optional domain each, like acme.example)",
                ),
            ),
            (
                "applied",
                "application",
                "recruiter",
                "emails me",
                "email me",
                "hear back",
                "reply",
                "replies",
                "writes back",
                "gets back",
            ),
            "Gmail allowed on the phone",
            PHONE,
        ),
    )
}

assert tuple(REGISTRY) == KINDS, "the registry and models.KINDS must name the same kinds"

# Requests the registry can never take, said in words rather than mapped to a near miss.
NEVER = {
    ("text message", "sms", "whatsapp", "text from"): (
        "Text messages are never read or stored, so nothing can watch them."
    ),
}
# Where weather and river-gauge kinds join once `feat/conditions` lands. Nothing is built.
CONDITIONS_SEAM = {
    ("weather", "rain", "storm", "river", "flood", "gauge", "temperature", "wind"): (
        "Weather and river watches are not built yet; they wait on the conditions work."
    ),
}


def is_phone_kind(kind: str) -> bool:
    spec = REGISTRY.get(kind)
    return spec is not None and spec.evaluated_on == PHONE


def kinds_words() -> str:
    return "; ".join(f"{k.key} ({k.says})" for k in REGISTRY.values())


def nearest(text: str) -> list[str]:
    """The kinds whose hint words appear in [text], best first. Deterministic keyword overlap."""
    low = f" {text.lower()} "
    scored = [(sum(1 for h in k.hints if h in low), k.key) for k in REGISTRY.values()]
    return [key for score, key in sorted(scored, key=lambda s: -s[0]) if score > 0][:2]


def unknown_kind_refusal(kind: str, request_words: str = "") -> str:
    """No watch was set; here is what can be watched, and the nearest kinds to what was asked."""
    said = f"{kind} {request_words}".lower()
    for words, sentence in {**NEVER, **CONDITIONS_SEAM}.items():
        if any(w in said for w in words):
            return f"No watch was set. {sentence} LEGION can watch: {kinds_words()}."
    near = nearest(said)
    tail = f" Nearest: {', '.join(near)}." if near else ""
    return (
        f"No watch was set: {kind!r} is not a kind of watch. LEGION can watch: {kinds_words()}."
        f"{tail}"
    )


def clean_params(kind: str, raw) -> dict:
    """The params for [kind], typed and defaulted, or `WatchRefused` naming what is wrong."""
    spec = REGISTRY.get(kind)
    if spec is None:
        raise WatchRefused(unknown_kind_refusal(kind))
    raw = raw or {}
    if not isinstance(raw, dict):
        raise WatchRefused("No watch was set. params must be an object.")
    known = {p.name for p in spec.params}
    extra = sorted(set(raw) - known)
    if extra:
        allowed = ", ".join(f"{p.name} ({p.words})" for p in spec.params) or "none"
        raise WatchRefused(f"No watch was set. {kind} takes {allowed}; not {', '.join(extra)}.")
    out: dict = {}
    for p in spec.params:
        value = raw.get(p.name)
        if value is None or value == "":
            if p.required:
                raise WatchRefused(f"No watch was set. {kind} needs {p.name} ({p.words}).")
            if p.default is not None:
                out[p.name] = p.default
            continue
        if p.type is list:
            out[p.name] = _clean_companies(value)
            continue
        if p.type is int:
            if isinstance(value, bool) or not isinstance(value, int):
                raise WatchRefused(
                    f"No watch was set. {p.name} must be a whole number ({p.words}); got {value!r}."
                )
            if (
                p.minimum is not None
                and value < p.minimum
                or (p.maximum is not None and value > p.maximum)
            ):
                raise WatchRefused(
                    f"No watch was set. {p.name} must be from {p.minimum} to {p.maximum}; "
                    f"got {value}."
                )
        else:
            if not isinstance(value, str):
                raise WatchRefused(f"No watch was set. {p.name} must be text; got {value!r}.")
            value = " ".join(value.split())
            if p.choices is not None:
                value = value.upper()
                if value not in p.choices:
                    raise WatchRefused(
                        f"No watch was set. {p.name} must be one of {', '.join(p.choices)}."
                    )
            elif len(value) > TEXT_PARAM_MAX:
                raise WatchRefused(
                    f"No watch was set. {p.name} is at most {TEXT_PARAM_MAX} characters."
                )
            if p.name == "account" and not (len(value) == 4 and value.isdigit()):
                raise WatchRefused("No watch was set. account is the account's last four digits.")
        out[p.name] = value
    return out


def _clean_companies(value) -> list[str]:
    """His list of companies, as he gave it: each entry a name or a domain (the phone matches
    either against the sender's domain, display name and subject). A comma-separated string is
    split. Blank entries dropped, case-insensitive duplicates dropped, order kept."""
    if isinstance(value, str):
        value = [v for v in value.replace(";", ",").split(",")]
    if not isinstance(value, list):
        raise WatchRefused("No watch was set. companies must be a list of names.")
    out: list[str] = []
    for item in value:
        if not isinstance(item, str):
            raise WatchRefused("No watch was set. Each company must be a name.")
        name = " ".join(item.split())
        if len(name) < 2:
            continue
        if len(name) > TEXT_PARAM_MAX:
            raise WatchRefused(
                f"No watch was set. A company name is at most {TEXT_PARAM_MAX} characters."
            )
        if name.lower() not in {o.lower() for o in out}:
            out.append(name)
    if not out:
        raise WatchRefused("No watch was set. mail_reply needs at least one company name.")
    if len(out) > COMPANIES_MAX:
        raise WatchRefused(f"No watch was set. At most {COMPANIES_MAX} companies.")
    return out


def label_for(kind: str, params: dict) -> str:
    """The watch's name on the screen and in "why did you say that?", from its params."""
    amount = params.get("amount_cents")
    cur = params.get("currency", "USD")
    if kind == KIND_CHARGE_OVER:
        at = f" at {params['merchant']}" if params.get("merchant") else ""
        text = f"A charge of {money(amount, cur)} or more{at}"
    elif kind == KIND_DEPOSIT_OVER:
        frm = f" from {params['payer']}" if params.get("payer") else ""
        text = f"A deposit of {money(amount, cur)} or more{frm}"
    elif kind == KIND_BALANCE_UNDER:
        acct = f" on the account ending {params['account']}" if params.get("account") else ""
        text = f"A balance under {money(amount, cur)}{acct}"
    elif kind == KIND_CANVAS_DUE:
        text = f"Canvas work due within {params.get('hours', 24)} hours"
    elif kind == KIND_MAIL_REPLY:
        names = params.get("companies") or []
        more = f" and {len(names) - 3} more" if len(names) > 3 else ""
        text = f"A reply from {', '.join(names[:3])}{more}"
    elif kind == KIND_GRADE_POSTED:
        course = f" in {params['course']}" if params.get("course") else ""
        text = f"A grade posted{course}"
    else:
        text = (
            f"Car service due within {params.get('days', 14)} days or "
            f"{params.get('miles', 500):,} miles"
        )
    return text[:LABEL_MAX_CHARS]


def clean_label(label, kind: str, params: dict) -> str:
    if label is None or (isinstance(label, str) and not label.strip()):
        return label_for(kind, params)
    if not isinstance(label, str):
        raise WatchRefused("No watch was set. label must be text.")
    label = " ".join(label.split())
    if len(label) > LABEL_MAX_CHARS:
        raise WatchRefused(f"No watch was set. label is at most {LABEL_MAX_CHARS} characters.")
    return label


# =============================================================================
# Built-ins: the "engine signals" (Kevin, 2026-11-10, ruling 2). Seeded once per member, on
# their first phone read, and editable like any watch. Removing one disables it for good.
# =============================================================================


@dataclass(frozen=True)
class Builtin:
    key: str
    kind: str
    params: dict


BUILTINS: tuple[Builtin, ...] = (
    Builtin("big_charge", KIND_CHARGE_OVER, {"amount_cents": 50_000, "currency": "USD"}),
    Builtin("canvas_due_24h", KIND_CANVAS_DUE, {"hours": 24}),
    Builtin("service_due", KIND_SERVICE_DUE, {"days": 14, "miles": 500}),
)

# How long an unsaid firing stays sayable, when its fact has no time of its own.
DEFAULT_FIRING_LIFE = dt.timedelta(days=3)
