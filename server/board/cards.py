"""What a laptop's board upload may hold: known fields only, capped, and no key or token shapes.

The laptop writes `board.json` (one card per agent, ship, build or decision). The engine keeps only
the fields below and drops everything else (a card's `link` is a laptop path; `created` is unused).
The rules on the laptop already forbid secrets and personal data on a card; this is the engine's own
guard behind them, because the snapshot is read aloud and shown on a phone.

**The secret guard is shaped by what real cards carry.** A card legitimately holds an 11-digit CI
run id, an 8-character or full 40-character commit hash, build numbers and file paths, so a rule on
"long digits" or "any hex" would refuse every push. It looks instead for known credential prefixes,
JWT and PEM shapes, `key=value` secrets, long mixed-case base64 runs, hex runs longer than a commit
hash, and email addresses. A refusal names the card and field, never the value.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime

COLS: tuple[str, ...] = ("queued", "working", "needs_kevin", "testing", "done")
KINDS: tuple[str, ...] = ("agent", "ship", "build", "cloud", "decision", "session")
TEXT_FIELDS: tuple[str, ...] = ("title", "status", "waiting", "owner", "ask")
FIELD_MAX = 220
MAX_CARDS = 80
MAX_BYTES = 64 * 1024
MAX_AGE_MIN = 60 * 24 * 365
_ID = re.compile(r"^[a-z0-9_-]{1,60}$")

_SECRET_SHAPES: tuple[re.Pattern, ...] = (
    # Known credential prefixes: GitHub, OpenAI/Anthropic-style, Slack, Google, AWS, GitLab, and
    # this engine's own head-unit and board-writer tokens.
    re.compile(
        r"(?<![A-Za-z0-9])(ghp_|gho_|ghu_|ghs_|ghr_|github_pat_|sk-|sk_live_|rk_live_|xox[abprs]-|"
        r"AIza|AKIA|ASIA|ya29\.|glpat-|hu1_|bw1_)[A-Za-z0-9_\-]{8,}"
    ),
    re.compile(r"eyJ[A-Za-z0-9_\-]{8,}\.eyJ[A-Za-z0-9_\-]{8,}"),
    re.compile(r"-----BEGIN [A-Z ]*(PRIVATE KEY|CERTIFICATE)"),
    re.compile(
        r"\b(api[_-]?key|secret|password|passwd|token|bearer)\s*[:=]\s*[\"']?[A-Za-z0-9_\-+/=.]{12,}",
        re.I,
    ),
    re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"),
    re.compile(r"(?<![0-9a-fA-F])[0-9a-fA-F]{48,}(?![0-9a-fA-F])"),
)
# A long run of base64/base64url characters: a secret only when it mixes upper, lower and digits
# (a key does, almost surely; a path or a snake_case script name does not).
_LONG_RUN = re.compile(r"[A-Za-z0-9+_\-=]{32,}")


def looks_secret(text: str) -> bool:
    if any(p.search(text) for p in _SECRET_SHAPES):
        return True
    for run in _LONG_RUN.findall(text):
        if re.search(r"[a-z]", run) and re.search(r"[A-Z]", run) and re.search(r"\d", run):
            return True
    return False


class Refused(ValueError):
    """The upload breaks a rule; the message says which, never the value."""


@dataclass(frozen=True)
class CleanBoard:
    cards: list[dict]
    build_slot: str
    generated_at: datetime | None


def _text(value, where: str) -> str:
    text = re.sub(r"\s+", " ", str(value if value is not None else "")).strip()[:FIELD_MAX]
    if text and looks_secret(text):
        raise Refused(
            f"Nothing was stored. {where} looks like it holds a key, a token or an email address."
        )
    return text


def _generated(value) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        when = datetime.fromisoformat(value[:40])
    except ValueError:
        return None
    return when if when.tzinfo is not None else None


def clean_board(body) -> CleanBoard:
    """The upload reduced to the fields the engine keeps, or `Refused`."""
    if not isinstance(body, dict) or not isinstance(body.get("cards"), list):
        raise Refused("Nothing was stored. The body must be the board's JSON, with a cards list.")
    raw_cards = body["cards"]
    if len(raw_cards) > MAX_CARDS:
        raise Refused(f"Nothing was stored. A board holds at most {MAX_CARDS} cards.")
    cards: list[dict] = []
    seen: set[str] = set()
    for i, raw in enumerate(raw_cards):
        if not isinstance(raw, dict):
            raise Refused(f"Nothing was stored. Card {i} is not an object.")
        card_id = raw.get("id")
        if not isinstance(card_id, str) or not _ID.match(card_id) or card_id in seen:
            raise Refused(f"Nothing was stored. Card {i} has no valid, unique id.")
        seen.add(card_id)
        if raw.get("col") not in COLS:
            raise Refused(f"Nothing was stored. Card {card_id} has an unknown column.")
        card = {"id": card_id, "col": raw["col"]}
        card["kind"] = raw.get("kind") if raw.get("kind") in KINDS else "agent"
        for name in TEXT_FIELDS:
            card[name] = _text(raw.get(name), f"Card {card_id}, field {name},")
        if not card["title"]:
            card["title"] = card_id
        age = raw.get("age_min")
        card["age_min"] = age if isinstance(age, int) and 0 <= age <= MAX_AGE_MIN else 0
        card["stale"] = raw.get("stale") is True
        cards.append(card)
    return CleanBoard(
        cards=cards,
        build_slot=_text(body.get("build_slot"), "The build slot line"),
        generated_at=_generated(body.get("generated")),
    )


def needs_kevin(cards: list[dict]) -> list[dict]:
    """Cards waiting on the member: in that column, or carrying a question (as the laptop says)."""
    return [c for c in cards if c["col"] == "needs_kevin" or c.get("ask")]


def counts(cards: list[dict]) -> dict[str, int]:
    return {col: sum(1 for c in cards if c["col"] == col) for col in COLS}
