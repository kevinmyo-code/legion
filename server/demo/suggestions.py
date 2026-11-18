"""Suggestion chips on the demo's voice stage (demo stage 2, item 1; the approved plan, 2026-11-18).

A visitor who cannot or will not talk taps a question. The rule that governs every part of this:
**a chip is never written by the model, and never shown unless it answers for THIS visitor.**

- `EXAMPLES`: the questions offered with the greeting, before the first turn. Data, here, not
  page code.
- `follow_ups`: two to four after an answer, built by CODE from the tools that ran: after
  `about_kevin`, his other sections; after `show_diagram`, the project's other views; after
  `legion_docs`, the ADRs the answer said; after a money tool, its siblings. None after a refusal,
  a failed tool, small talk or a fixed line (`turn_facts` returns nothing for those).
- The turn itself does no work for chips beyond noting what ran (`turn_facts`, no query). The page
  then asks `POST /api/demo/chips` with the turn's `offer`, after the answer has gone out; that
  request builds the chips and checks each against the visitor's own rows (`answerable`), never
  through the turn's door (no audit row, no record line, no tool cap, no prepay).
- Every offer and every chip is a token signed for the visitor it was made for (`TOKEN_SALT`,
  stateless, so any instance can read it and no table is needed). The turn route takes "this was
  a chip" only from such a token, for that visitor, with the question word for word (`chip_for`):
  then the chip's own checked lookup runs, never the model's pick. A chip whose tool ends the turn
  in code (`about_kevin`) is answered with no model call at all (`fast`).

Every catalog tool reads; none writes and none costs money (`tests/test_demo_suggestions.py`).
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field

from django.core import signing

TOKEN_SALT = "demo.suggestions"
# An offer or a chip is good for the length of a visit's conversation and a little more.
TOKEN_MAX_AGE_S = 45 * 60
EXAMPLES_MAX = 4
FOLLOW_UPS_MAX = 4


@dataclass(frozen=True)
class Chip:
    # Stable per entry: "about", "section:<id>", "view:<project>:<view>", "adr:<number>",
    # "tool:<name>".
    key: str
    label: str
    # The whole question a tap asks: it must make sense with no history.
    question: str
    tool: str
    args: dict = field(default_factory=dict)
    # Answered by code with no model call (the tool's result ends the turn in code's words).
    fast: bool = False

    def public(self) -> dict:
        return {"label": self.label, "question": self.question}


# Before the first turn: about him, how LEGION is built, his résumé, one demo-household question.
# The one-minute tour keeps its own control on the stage (it is not a question).
EXAMPLES: tuple[Chip, ...] = (
    Chip(
        "about", "About Kevin", "Tell me about Kevin.", "about_kevin", {"sections": ["overview"]},
        fast=True,
    ),
    Chip(
        "view:legion:containers",
        "How LEGION is built",
        "Show me how LEGION is built.",
        "show_diagram",
        {"project": "legion", "view": "containers"},
    ),
    Chip("resume", "His résumé", "Show me Kevin's résumé.", "show_resume", {}),
    Chip(
        "tool:next_service",
        "Car service",
        "When is this household's car next due for service?",
        "next_service",
        {},
    ),
)  # fmt: skip

# Money tools and the question each answers, whole. After one ran, the others are offered.
MONEY: dict[str, Chip] = {
    "monthly_spend": Chip(
        "tool:monthly_spend", "Spending this month", "What has this household spent this month?",
        "monthly_spend", {},
    ),
    "grocery_spend": Chip(
        "tool:grocery_spend", "Groceries", "How much has this household spent on groceries?",
        "grocery_spend", {},
    ),
    "budgets": Chip(
        "tool:budgets", "Budgets", "How is this household doing against its budgets this month?",
        "budgets", {},
    ),
    "account_balances": Chip(
        "tool:account_balances", "Balances", "What are this household's account balances?",
        "account_balances", {},
    ),
}  # fmt: skip

# The tools a chip may name. Reads only, none paid (web_search is not here, on purpose).
CHIP_TOOLS = frozenset(
    {"about_kevin", "show_diagram", "show_resume", "legion_docs", "next_service", *MONEY}
)

_ADR = re.compile(r"\bADR[\s-]*0*(\d{1,4})\b", re.IGNORECASE)


# =============================================================================
# The turn's part: what ran, noted with no query (`turn_facts`), carried in the offer
# =============================================================================


def turn_facts(calls, said: str, status: str) -> list[dict]:
    """What the follow-ups are built from, or [] when this turn gets none: not a plain answer
    (`status`), no tool, any call refused or failed, or his profile answered with a fixed line
    ("not shared", "which one"). Plain data only (tool, args, titles, numbers), never a sentence."""
    if status != "done" or not calls:
        return []
    if any(not c.ok for c in calls):
        return []
    facts: list[dict] = []
    for c in calls:
        data = c.data or {}
        if c.name == "about_kevin":
            read = [t for t in data.get("sections") or [] if isinstance(t, str)]
            if not read:
                return []
            facts.append({"tool": c.name, "read": read})
        elif c.name == "show_diagram":
            facts.append({"tool": c.name, "project": data.get("project"), "view": data.get("view")})
        elif c.name == "legion_docs":
            # Only an ADR the answer named (Midcon review 1: never a chip about something the
            # answer did not say).
            cited = {
                str(int(p["adr"]))
                for p in data.get("passages") or []
                if isinstance(p, dict) and str(p.get("adr") or "").isdigit()
            }
            spoken = {str(int(n)) for n in _ADR.findall(said or "")}
            facts.append({"tool": c.name, "adrs": sorted(cited & spoken, key=int)})
        elif c.name in MONEY:
            facts.append({"tool": c.name})
    return facts


def offer(user, chips_or_facts: dict) -> str:
    return signing.dumps({"u": str(user.pk), **chips_or_facts}, salt=TOKEN_SALT, compress=True)


def turn_offer(user, calls, said: str, status: str) -> str | None:
    facts = turn_facts(calls, said, status)
    return offer(user, {"f": facts}) if facts else None


def examples_offer(user) -> str:
    return offer(user, {"x": 1})


def _load(token: str, user) -> dict | None:
    try:
        body = signing.loads(token, salt=TOKEN_SALT, max_age=TOKEN_MAX_AGE_S)
    except (signing.BadSignature, TypeError, ValueError):
        return None
    if not isinstance(body, dict) or user is None or body.get("u") != str(user.pk):
        return None
    return body


# =============================================================================
# The after-request: build, check, sign (`chips_for_offer`)
# =============================================================================


def _sections(user):
    from demo.tools import _row_and_sections

    row, sections, ordered = _row_and_sections(user)
    return row, sections, ordered


def follow_ups(user, facts: list[dict]) -> list[Chip]:
    """The follow-up candidates for a turn's facts, in order, before any check."""
    from diagrams.catalogue import PROJECT_NAMES, VIEWS, available, find

    out: list[Chip] = []
    ran = {f["tool"] for f in facts}
    for fact in facts:
        tool = fact["tool"]
        if tool == "about_kevin":
            _row, sections, _ordered = _sections(user)
            read = set(fact.get("read") or [])
            for s in sections:
                if s.title in read:
                    continue
                out.append(
                    Chip(
                        f"section:{s.id}",
                        s.title,
                        f"What has Kevin written about {s.title.lower()}?",
                        "about_kevin",
                        {"sections": [s.id]},
                        fast=True,
                    )
                )
        elif tool == "show_diagram":
            project, shown = fact.get("project"), fact.get("view")
            if project not in VIEWS:
                continue
            for view in available(user, project):
                if view == shown:
                    continue
                found = find(user, project, view)
                if found is None:
                    continue
                out.append(
                    Chip(
                        f"view:{project}:{view}",
                        found["title"],
                        f"Show me the {found['title']} diagram of {PROJECT_NAMES[project]}.",
                        "show_diagram",
                        {"project": project, "view": view},
                    )
                )
        elif tool == "legion_docs":
            for number in fact.get("adrs") or []:
                padded = f"{int(number):04d}"
                out.append(
                    Chip(
                        f"adr:{padded}",
                        f"ADR {int(number)}",
                        f"What does ADR {padded} decide?",
                        "legion_docs",
                        {"query": f"ADR {padded}"},
                    )
                )
        elif tool in MONEY:
            out += [chip for name, chip in MONEY.items() if name not in ran]
    seen: set[str] = set()
    unique = []
    for chip in out:
        if chip.key not in seen:
            seen.add(chip.key)
            unique.append(chip)
    return unique


def _gives(chip: Chip, result) -> bool:
    """Whether a lookup's result answers the chip (an empty month or no rows is no answer)."""
    if result is None or result.is_error:
        return False
    data = result.structured or {}
    if chip.tool == "monthly_spend":
        return bool(data.get("accounts"))
    if chip.tool == "account_balances":
        return bool(data.get("accounts"))
    if chip.tool == "grocery_spend":
        return bool(data.get("currencies"))
    if chip.tool == "budgets":
        return any(c.get("cap_cents") for c in data.get("categories") or [])
    if chip.tool == "next_service":
        return any(
            v.get("next_by_date") or v.get("next_by_miles") or v.get("overdue")
            for v in data.get("vehicles") or []
        )
    return True


def answerable(request, chip: Chip) -> bool:
    """Whether `chip` can be answered for this visitor now. Cheap facts where they exist (his
    sections, the diagram rows, the résumé file, the docs index); the read itself for a household
    question. Never through the turn's door, and never a write (`CHIP_TOOLS`)."""
    from assistant.surface import refusal_for

    user = getattr(request, "user", None)
    if chip.tool not in CHIP_TOOLS or refusal_for(chip.tool, user, chip.args) is not None:
        return False
    if chip.tool == "about_kevin":
        _row, sections, ordered = _sections(user)
        wanted = chip.args.get("sections") or []
        if "overview" in wanted:
            return bool(sections) and ordered
        ids = {s.id for s in sections}
        return bool(wanted) and all(w in ids for w in wanted)
    if chip.tool == "show_diagram":
        from diagrams.catalogue import find

        return find(user, chip.args["project"], chip.args["view"]) is not None
    if chip.tool == "show_resume":
        from demo.models import RecruiterContent
        from demo.tools import resume_card

        return resume_card(RecruiterContent.for_user(user)) is not None
    if chip.tool == "legion_docs":
        from assistant.docs_search import search

        number = chip.args["query"].split()[-1]
        return any(p.adr and p.adr.get("number") == number for p in search(chip.args["query"]))
    from engine_mcp.tools import run_tool

    try:
        return _gives(chip, run_tool(request, chip.tool, dict(chip.args)))
    except Exception:  # noqa: BLE001 - a chip that cannot be checked is not shown
        return False


def chips_for_offer(request, token: str) -> list[dict] | None:
    """The checked chips for an offer, each with its own token; None when the offer is not this
    visitor's (or has expired)."""
    user = getattr(request, "user", None)
    body = _load(token, user)
    if body is None:
        return None
    if body.get("x"):
        candidates, cap = list(EXAMPLES), EXAMPLES_MAX
    else:
        facts = body.get("f")
        if not isinstance(facts, list):
            return None
        candidates, cap = follow_ups(user, facts), FOLLOW_UPS_MAX
    out = []
    for chip in candidates:
        if len(out) >= cap:
            break
        if answerable(request, chip):
            out.append({**chip.public(), "token": offer(user, {"c": asdict(chip)})})
    # One chip alone is not a choice: follow-ups are two to four, or none.
    if not body.get("x") and len(out) < 2:
        return []
    return out


@dataclass(frozen=True)
class ChipCall:
    """A tapped chip, as the turn runs it: the checked lookup, never the model's pick."""

    tool: str
    args: dict
    fast: bool
    question: str


def chip_for(user, token: str | None, text: str | None) -> ChipCall | None:
    """The chip a turn's `chip` token names, when it was offered to this visitor and the turn's
    words are its question word for word; else None (the turn is then a plain typed one)."""
    if not token or not text:
        return None
    body = _load(token, user)
    if body is None or not isinstance(body.get("c"), dict):
        return None
    c = body["c"]
    if c.get("question") != text.strip() or c.get("tool") not in CHIP_TOOLS:
        return None
    return ChipCall(c["tool"], dict(c.get("args") or {}), bool(c.get("fast")), c["question"])
