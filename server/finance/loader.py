"""The private-file loader (household-finance ticket 12, ADR 0073).

**The real household's figures never enter this repo.** They live in a private
Markdown file outside it, and `manage.py load_household_finance <file>` reads
that file into Postgres. This module knows the file's SHAPE only.

The file is Markdown for a person to read and edit, with two machine-read parts:

1. **The `## Accounts` table**, read as written. Columns: Key, Account, Owner,
   Ccy, Layer, Source. `Layer` may say `(debt)` (`liquid (debt)` is a card,
   `property (debt)` a loan); `Owner` is a member's name or `joint`; `Source`
   is the first word of the cell (`computed (...)` is `computed`). Anything
   this cannot read is refused with the line named, never skipped (CLAUDE.md
   section 4 rule 6's posture: a line the parser does not recognise is a hard
   failure).
2. **One fenced ```yaml block** for what prose cannot carry: members' payer
   names, per-account overrides, the income plan, the floors and the US top-up
   trigger, the mortgage, and the budgets. `server/finance/example_household.md`
   is the template, with an invented household.

Money in the file is written in major units (`500`, `10000.00`) and becomes int
cents through `Decimal(str(x))`, never float maths; a figure with more than two
decimal places is refused.

**Idempotent.** Members, accounts, income lines and budgets are upserted by
their keys; the policy is one row. An account no longer in the file is marked
inactive (its balance history is kept); an income line no longer in the file is
deleted (the file is the plan). Running it twice changes nothing the second
time.

**Its summary carries no figures**: counts and keys only, because a terminal
transcript is easy to paste somewhere public.
"""

from __future__ import annotations

import datetime as dt
import re
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path

import yaml

from finance.models import (
    COUNTRIES,
    CURRENCIES,
    Cadence,
    Confidence,
    Kind,
    Layer,
    SourceKind,
)


class LoaderRefused(Exception):
    """The file could not be read as a whole. Nothing was written."""


# =============================================================================
# Parsing
# =============================================================================

ACCOUNT_COLUMNS = ("key", "account", "owner", "ccy", "layer", "source")
_SOURCE_WORDS = {
    "plaid": SourceKind.PLAID,
    "statement": SourceKind.STATEMENT,
    "ibkr-flex": SourceKind.IBKR_FLEX,
    "ibkr_flex": SourceKind.IBKR_FLEX,
    "computed": SourceKind.COMPUTED,
    "manual": SourceKind.MANUAL,
}
_YAML_BLOCK = re.compile(r"^```ya?ml[^\n]*\n(?P<body>.*?)^```", re.M | re.S)
_FOUR_DIGITS = re.compile(r"(?<!\d)(\d{4})(?!\d)")
_KEY = re.compile(r"^[a-z0-9][a-z0-9-]*$")


@dataclass
class AccountSpec:
    key: str
    label: str
    owner: str | None
    currency: str
    layer: str
    kind: str
    source: str
    country: str
    institution: str
    last4: str | None
    plaid_account_id: str | None = None
    statement_match: str = ""


@dataclass
class Plan:
    members: dict[str, dict] = field(default_factory=dict)
    accounts: list[AccountSpec] = field(default_factory=list)
    income: list[dict] = field(default_factory=list)
    policy: dict | None = None
    budgets: list[dict] = field(default_factory=list)


def cents(value, where: str) -> int:
    """A major-unit figure from the file as int cents. Refuses anything else."""
    if isinstance(value, bool) or value is None:
        raise LoaderRefused(f"{where}: {value!r} is not an amount.")
    try:
        exact = Decimal(str(value).replace(",", "").strip())
    except InvalidOperation as exc:
        raise LoaderRefused(f"{where}: {value!r} is not an amount.") from exc
    if not exact.is_finite() or exact != exact.quantize(Decimal("0.01")):
        raise LoaderRefused(f"{where}: {value!r} has more than two decimal places.")
    return int(exact * 100)


def _day(value, where: str) -> dt.date | None:
    if value is None or value == "":
        return None
    if isinstance(value, dt.date):
        return value
    try:
        return dt.date.fromisoformat(str(value))
    except ValueError as exc:
        raise LoaderRefused(f"{where}: {value!r} is not a date (YYYY-MM-DD).") from exc


def _month(value, where: str) -> dt.date:
    text = value.isoformat()[:7] if isinstance(value, dt.date) else str(value)
    try:
        return dt.date.fromisoformat(f"{text}-01")
    except ValueError as exc:
        raise LoaderRefused(f"{where}: {value!r} is not a month (YYYY-MM).") from exc


def _one_of(value, allowed, where: str) -> str:
    text = str(value).strip()
    if text not in allowed:
        raise LoaderRefused(f"{where}: {text!r} is not one of {', '.join(sorted(allowed))}.")
    return text


def _section(text: str, heading: str) -> list[str]:
    lines = text.splitlines()
    out, inside = [], False
    for line in lines:
        if line.startswith("## "):
            inside = line[3:].strip().lower() == heading
            continue
        if inside:
            out.append(line)
    return out


def _cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def _kind_for(layer: str, debt: bool, label: str) -> str:
    if layer == Layer.LIQUID:
        return Kind.CREDIT if debt else Kind.DEPOSITORY
    if layer == Layer.INVESTMENTS:
        return Kind.BOND if "bond" in label.lower() else Kind.BROKERAGE
    if layer == Layer.CPF:
        return Kind.CPF
    if layer == Layer.PROPERTY:
        return Kind.LOAN if debt else Kind.PROPERTY
    return Kind.VEHICLE


def parse_accounts_table(text: str) -> list[AccountSpec]:
    rows = [line for line in _section(text, "accounts") if line.strip().startswith("|")]
    if not rows:
        raise LoaderRefused("The file has no `## Accounts` table. Nothing was loaded.")
    header = [cell.lower() for cell in _cells(rows[0])]
    if tuple(header) != ACCOUNT_COLUMNS:
        raise LoaderRefused(
            f"The Accounts table's columns are {header}, not "
            f"{list(ACCOUNT_COLUMNS)}. Nothing was loaded."
        )
    specs = []
    for number, line in enumerate(rows[1:], start=2):
        cells = _cells(line)
        if all(set(cell) <= set("-: ") for cell in cells):
            continue
        where = f"Accounts table row {number}"
        if len(cells) != len(ACCOUNT_COLUMNS):
            raise LoaderRefused(f"{where} has {len(cells)} cells, not {len(ACCOUNT_COLUMNS)}.")
        key, label, owner, ccy, layer_cell, source_cell = cells
        if not _KEY.match(key):
            raise LoaderRefused(f"{where}: {key!r} is not a key (lower-case, digits, hyphens).")
        layer_match = re.fullmatch(r"(\w+)\s*(\(debt\))?", layer_cell.strip().lower())
        if layer_match is None:
            raise LoaderRefused(f"{where}: the layer {layer_cell!r} is not readable.")
        layer = _one_of(layer_match.group(1), set(Layer.values), f"{where} layer")
        debt = layer_match.group(2) is not None
        source_word = re.split(r"[\s(,]", source_cell.strip().lower(), maxsplit=1)[0]
        if source_word not in _SOURCE_WORDS:
            raise LoaderRefused(f"{where}: the source {source_cell!r} is not one this reads.")
        currency = _one_of(ccy.upper(), set(CURRENCIES), f"{where} currency")
        kind = _kind_for(layer, debt, label)
        last4 = None
        if kind in (Kind.DEPOSITORY, Kind.CREDIT):
            found = _FOUR_DIGITS.findall(f"{key} {label}")
            last4 = found[-1] if found else None
        owner_key = None if owner.strip().lower() in ("joint", "") else owner.strip().lower()
        specs.append(
            AccountSpec(
                key=key,
                label=label,
                owner=owner_key,
                currency=currency,
                layer=layer,
                kind=kind,
                source=_SOURCE_WORDS[source_word],
                country="US" if currency == "USD" else "SG",
                institution=label.split()[0] if label.split() else "",
                last4=last4,
            )
        )
    if not specs:
        raise LoaderRefused("The Accounts table has no rows. Nothing was loaded.")
    keys = [s.key for s in specs]
    if len(set(keys)) != len(keys):
        raise LoaderRefused("Two rows of the Accounts table share a key. Nothing was loaded.")
    return specs


def _yaml(text: str) -> dict:
    blocks = [m.group("body") for m in _YAML_BLOCK.finditer(text)]
    if not blocks:
        raise LoaderRefused(
            "The file has no ```yaml block, so there are no payer names, income plan, floors "
            "or budgets to load. Copy the block from server/finance/example_household.md "
            "into the file and fill it in. Nothing was loaded."
        )
    if len(blocks) > 1:
        raise LoaderRefused("The file has more than one ```yaml block. Keep one. Nothing loaded.")
    try:
        body = yaml.safe_load(blocks[0]) or {}
    except yaml.YAMLError as exc:
        raise LoaderRefused(f"The yaml block does not parse ({type(exc).__name__}).") from exc
    if not isinstance(body, dict):
        raise LoaderRefused("The yaml block is not a mapping. Nothing was loaded.")
    known = {"members", "accounts", "income", "policy", "budgets"}
    unknown = sorted(set(body) - known)
    if unknown:
        raise LoaderRefused(f"The yaml block has keys this does not read: {', '.join(unknown)}.")
    return body


def _apply_overrides(specs: list[AccountSpec], overrides: dict) -> None:
    by_key = {s.key: s for s in specs}
    for key, fields in (overrides or {}).items():
        if key not in by_key:
            raise LoaderRefused(f"accounts.{key}: no such account in the Accounts table.")
        if not isinstance(fields, dict):
            raise LoaderRefused(f"accounts.{key} is not a mapping.")
        spec = by_key[key]
        for name, value in fields.items():
            where = f"accounts.{key}.{name}"
            if name == "kind":
                spec.kind = _one_of(value, set(Kind.values), where)
            elif name == "country":
                spec.country = _one_of(str(value).upper(), set(COUNTRIES), where)
            elif name == "last4":
                text = "" if value is None else str(value).zfill(4)
                if text and not re.fullmatch(r"\d{4}", text):
                    raise LoaderRefused(f"{where}: not four digits.")
                spec.last4 = text or None
            elif name in ("plaid_account_id", "statement_match", "institution", "label"):
                setattr(spec, name, "" if value is None else str(value))
            else:
                raise LoaderRefused(f"{where}: this loader does not set {name!r}.")
        if spec.plaid_account_id == "":
            spec.plaid_account_id = None


def _members(body: dict, specs: list[AccountSpec]) -> dict[str, dict]:
    members: dict[str, dict] = {}
    for key, fields in (body.get("members") or {}).items():
        key = str(key).lower()
        fields = fields or {}
        names = fields.get("payer_names") or []
        if not isinstance(names, list) or not all(isinstance(n, str) for n in names):
            raise LoaderRefused(f"members.{key}.payer_names is not a list of names.")
        members[key] = {
            "label": str(fields.get("label") or key.title()),
            "payer_names": [n.strip() for n in names if n.strip()],
            "user_email": fields.get("user_email"),
        }
    for spec in specs:
        if spec.owner and spec.owner not in members:
            members[spec.owner] = {"label": spec.owner.title(), "payer_names": [],
                                   "user_email": None}
    return members


def _income(body: dict, members: dict, account_keys: set[str]) -> list[dict]:
    out = []
    for index, line in enumerate(body.get("income") or [], start=1):
        where = f"income[{index}]"
        if not isinstance(line, dict):
            raise LoaderRefused(f"{where} is not a mapping.")
        key = str(line.get("key") or "")
        if not _KEY.match(key):
            raise LoaderRefused(f"{where}: needs a key (lower-case, digits, hyphens).")
        member = line.get("member")
        if member is not None and str(member).lower() not in members:
            raise LoaderRefused(f"{where}: no member called {member!r}.")
        account = line.get("account")
        if account is not None and account not in account_keys:
            raise LoaderRefused(f"{where}: no account called {account!r}.")
        out.append(
            {
                "key": key,
                "member": str(member).lower() if member is not None else None,
                "label": str(line.get("label") or key),
                "amount_cents": cents(line.get("amount"), f"{where}.amount"),
                "currency": _one_of(str(line.get("currency", "")).upper(), set(CURRENCIES),
                                    f"{where}.currency"),
                "cadence": _one_of(line.get("cadence", ""), set(Cadence.values),
                                   f"{where}.cadence"),
                "start_on": _day(line.get("start"), f"{where}.start"),
                "end_on": _day(line.get("end"), f"{where}.end"),
                "confidence": _one_of(line.get("confidence", ""), set(Confidence.values),
                                      f"{where}.confidence"),
                "account": account,
            }
        )
    keys = [line["key"] for line in out]
    if len(set(keys)) != len(keys):
        raise LoaderRefused("Two income lines share a key. Nothing was loaded.")
    return out


def _money(entry, where: str, default_currency: str) -> tuple[int, str]:
    if isinstance(entry, dict):
        return (
            cents(entry.get("amount"), f"{where}.amount"),
            _one_of(str(entry.get("currency", default_currency)).upper(), set(CURRENCIES),
                    f"{where}.currency"),
        )
    return cents(entry, where), default_currency


def _policy(body: dict, account_keys: set[str]) -> dict | None:
    raw = body.get("policy")
    if raw is None:
        return None
    if not isinstance(raw, dict):
        raise LoaderRefused("policy is not a mapping.")
    currency = _one_of(str(raw.get("currency", "USD")).upper(), set(CURRENCIES), "policy.currency")
    policy: dict = {"currency": currency}
    if raw.get("liquid_floor") is not None:
        amount, floor_currency = _money(raw["liquid_floor"], "policy.liquid_floor", currency)
        if floor_currency != currency:
            raise LoaderRefused("policy.liquid_floor must be in the policy's currency.")
        policy["liquid_floor_cents"] = amount
    mortgage = raw.get("mortgage")
    if mortgage is not None:
        amount, mortgage_currency = _money(mortgage, "policy.mortgage", "SGD")
        policy["mortgage_monthly_cents"] = amount
        policy["mortgage_currency"] = mortgage_currency
        policy["mortgage_in_ledger"] = bool(
            mortgage.get("in_ledger", False) if isinstance(mortgage, dict) else False
        )
    topup = raw.get("us_topup")
    if topup is not None:
        if not isinstance(topup, dict):
            raise LoaderRefused("policy.us_topup is not a mapping.")
        for name in ("checking", "card"):
            key = topup.get(name)
            if key is not None and key not in account_keys:
                raise LoaderRefused(f"policy.us_topup.{name}: no account called {key!r}.")
            policy[f"topup_{name}"] = key
        if topup.get("bills_30d") is not None:
            policy["us_bills_30d_cents"] = cents(topup["bills_30d"], "policy.us_topup.bills_30d")
        if topup.get("warn_days") is not None:
            policy["topup_warn_days"] = int(topup["warn_days"])
        due = topup.get("card_due_day")
        if due is not None and not 1 <= int(due) <= 31:
            raise LoaderRefused("policy.us_topup.card_due_day must be a day of the month.")
        policy["card_due_day"] = int(due) if due is not None else None
    if raw.get("transfer_lead_days") is not None:
        policy["transfer_lead_days"] = int(raw["transfer_lead_days"])
    fixed = raw.get("fixed_categories") or []
    if not isinstance(fixed, list):
        raise LoaderRefused("policy.fixed_categories is not a list.")
    policy["fixed_categories"] = [str(c) for c in fixed]
    return policy


def _budgets(body: dict) -> list[dict]:
    raw = body.get("budgets")
    if raw is None:
        return []
    if not isinstance(raw, dict) or not isinstance(raw.get("caps"), dict):
        raise LoaderRefused("budgets needs `currency`, `from` (YYYY-MM) and `caps`.")
    currency = _one_of(str(raw.get("currency", "USD")).upper(), set(CURRENCIES), "budgets.currency")
    month = _month(raw.get("from"), "budgets.from")
    return [
        {
            "category": str(category),
            "currency": currency,
            "amount_cents": cents(amount, f"budgets.caps.{category}"),
            "effective_from_month": month,
        }
        for category, amount in raw["caps"].items()
    ]


def parse(text: str) -> Plan:
    text = text.replace("\r\n", "\n")
    specs = parse_accounts_table(text)
    body = _yaml(text)
    _apply_overrides(specs, body.get("accounts") or {})
    members = _members(body, specs)
    keys = {s.key for s in specs}
    return Plan(
        members=members,
        accounts=specs,
        income=_income(body, members, keys),
        policy=_policy(body, keys),
        budgets=_budgets(body),
    )


def read_private_file(path: Path, repo_root: Path) -> str:
    """The file's text, refused if it sits inside the public repo."""
    resolved = path.resolve()
    if resolved.is_relative_to(repo_root.resolve()):
        raise LoaderRefused(
            "That file is inside the repo, which is public. The household's figures belong in "
            "a private file outside it. Nothing was loaded."
        )
    if not resolved.is_file():
        raise LoaderRefused(f"There is no file at {path}. Nothing was loaded.")
    return resolved.read_text(encoding="utf-8")


# =============================================================================
# Writing
# =============================================================================


@dataclass
class Summary:
    """Counts and keys only. Never a figure, a name or an account number."""

    members: int = 0
    accounts: int = 0
    accounts_deactivated: list[str] = field(default_factory=list)
    income_lines: int = 0
    income_removed: int = 0
    budgets: int = 0
    policy: bool = False
    changed: int = 0

    def lines(self) -> list[str]:
        out = [
            f"Members: {self.members}.",
            f"Accounts: {self.accounts}.",
            f"Income plan lines: {self.income_lines}"
            + (f" ({self.income_removed} removed)." if self.income_removed else "."),
            f"Budget caps: {self.budgets}.",
            "Floors and top-up trigger: " + ("loaded." if self.policy else "none in the file."),
        ]
        if self.accounts_deactivated:
            out.append(
                "Marked inactive (no longer in the file): "
                + ", ".join(sorted(self.accounts_deactivated))
                + "."
            )
        out.append(
            f"Rows changed: {self.changed}." if self.changed else "Nothing changed: already loaded."
        )
        return out


def _upsert(model, lookup: dict, values: dict) -> bool:
    """Create or update; True when anything was written."""
    row = model.objects.filter(**lookup).first()
    if row is None:
        model.objects.create(**lookup, **values)
        return True
    dirty = [name for name, value in values.items() if getattr(row, name) != value]
    if dirty:
        for name in dirty:
            setattr(row, name, values[name])
        if hasattr(row, "updated_at"):
            from django.utils import timezone

            row.updated_at = timezone.now()
            dirty.append("updated_at")
        row.save(update_fields=dirty)
        return True
    return False


def _budget_targets(household, budgets: list[dict], summary: Summary) -> None:
    from api.ledger import BudgetTargetSerializer
    from legacy.models.ledger import BudgetTarget

    context = {"household": household, "revive_tombstone": False}
    for target in budgets:
        month = target["effective_from_month"]
        existing = (
            BudgetTarget.objects.filter(
                household=household,
                category=target["category"],
                currency=target["currency"],
                effective_from_month=month,
                deleted_at__isnull=True,
            ).first()
        )
        if existing is not None and existing.amount_cents == target["amount_cents"]:
            continue
        guid = existing.origin_guid if existing is not None else (
            f"finance-loader-{target['category'].lower()}-{target['currency'].lower()}-"
            f"{month.isoformat()[:7]}"
        )
        data = {
            "origin_guid": guid,
            "category": target["category"],
            "currency": target["currency"],
            "amount_cents": target["amount_cents"],
            "effective_from_month": month.isoformat(),
        }
        serializer = BudgetTargetSerializer(existing, data=data, context=context)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        summary.changed += 1


def apply(plan: Plan, household) -> Summary:
    from django.db import transaction

    from finance.models import FinanceAccount, FinanceMember, FinancePolicy, IncomePlan
    from household.models import HouseholdMember

    summary = Summary()
    with transaction.atomic():
        members = {}
        for key, fields in plan.members.items():
            user = None
            if fields.get("user_email"):
                link = HouseholdMember.objects.filter(
                    household=household, user__email__iexact=fields["user_email"]
                ).select_related("user").first()
                if link is None:
                    raise LoaderRefused(
                        f"members.{key}.user_email names nobody in this household. Nothing "
                        f"was loaded."
                    )
                user = link.user
            summary.changed += _upsert(
                FinanceMember,
                {"household": household, "key": key},
                {"label": fields["label"], "payer_names": fields["payer_names"], "user": user},
            )
            members[key] = FinanceMember.objects.get(household=household, key=key)
        summary.members = len(members)

        for spec in plan.accounts:
            summary.changed += _upsert(
                FinanceAccount,
                {"household": household, "key": spec.key},
                {
                    "label": spec.label,
                    "owner": members[spec.owner] if spec.owner else None,
                    "institution": spec.institution,
                    "country": spec.country,
                    "currency": spec.currency,
                    "kind": spec.kind,
                    "layer": spec.layer,
                    "source": spec.source,
                    "last4": spec.last4,
                    "plaid_account_id": spec.plaid_account_id,
                    "statement_match": spec.statement_match,
                    "active": True,
                },
            )
        summary.accounts = len(plan.accounts)
        gone = FinanceAccount.objects.filter(household=household, active=True).exclude(
            key__in=[s.key for s in plan.accounts]
        )
        summary.accounts_deactivated = list(gone.values_list("key", flat=True))
        summary.changed += gone.update(active=False)
        accounts = {a.key: a for a in FinanceAccount.objects.filter(household=household)}

        for line in plan.income:
            summary.changed += _upsert(
                IncomePlan,
                {"household": household, "key": line["key"]},
                {
                    "member": members[line["member"]] if line["member"] else None,
                    "label": line["label"],
                    "amount_cents": line["amount_cents"],
                    "currency": line["currency"],
                    "cadence": line["cadence"],
                    "start_on": line["start_on"],
                    "end_on": line["end_on"],
                    "confidence": line["confidence"],
                    "account": accounts[line["account"]] if line["account"] else None,
                },
            )
        summary.income_lines = len(plan.income)
        removed, _ = IncomePlan.objects.filter(household=household).exclude(
            key__in=[line["key"] for line in plan.income]
        ).delete()
        summary.income_removed = removed
        summary.changed += removed

        if plan.policy is not None:
            values = dict(plan.policy)
            for name in ("topup_checking", "topup_card"):
                key = values.pop(name, None)
                values[name] = accounts[key] if key else None
            summary.changed += _upsert(FinancePolicy, {"household": household}, values)
            summary.policy = True

        _budget_targets(household, plan.budgets, summary)
        summary.budgets = len(plan.budgets)
    return summary
