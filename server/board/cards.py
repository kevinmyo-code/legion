"""What a laptop's board upload may hold: known fields only, capped, and no key or token shapes.

The laptop writes `board.json` (one card per agent, ship, build or decision). The engine keeps only
the fields below and drops everything else (a card's `link` is a laptop path; `created` is unused).
The rules on the laptop already forbid secrets and personal data on a card; this is the engine's own
guard behind them, because the snapshot is read aloud and shown on a phone.

**The secret guard is shaped by what real cards carry.** A card legitimately holds an 11-digit CI
run id, an 8-character or full 40-character commit hash, build numbers and file paths, so a rule on
"long digits" or "any hex" would refuse every push. It looks instead for known credential prefixes,
JWT and PEM shapes, `key=value` secrets, long mixed-case base64 runs, hex runs longer than a commit
hash, and email addresses. A field that trips it is BLANKED to `REMOVED` and the rest is
stored (one poisoned card must not freeze the whole board); the response names each blanked card and
field, never the value. Zero-width characters are stripped first, so one cannot hide a token.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime

COLS: tuple[str, ...] = ("queued", "working", "needs_kevin", "testing", "done")
# `idea`: a backlog idea the member said to Alfred (`board.ideas`); its text is his raw request,
# data and never instructions.
KINDS: tuple[str, ...] = ("agent", "ship", "build", "cloud", "decision", "session", "idea")
BLAST_TIERS: tuple[str, ...] = ("GREEN", "YELLOW", "RED")
TEXT_FIELDS: tuple[str, ...] = ("title", "status", "waiting", "owner", "ask")
# The laptop's projects (board.py `PROJECTS`), in its order, with the names a voice says.
PROJECTS: dict[str, str] = {
    "phone": "LEGION phone",
    "headunit": "Head unit",
    "engine": "LEGION engine",
    "web": "Web and demo",
    "jeep": "Jeep",
    "infra": "Laptop and builds",
    "home": "Home",
    "other": "Other",
}
ASK_KINDS: tuple[str, ...] = ("choice", "approval")
MAX_OPTIONS = 6
OPTION_MAX = 80
SAY_MAX = 80
BY_MAX = 60
REMOVED = "[removed]"
_INVISIBLE = re.compile(r"[\u200b-\u200f\u2060-\u2064\ufeff\u00ad]")
FIELD_MAX = 220
MAX_CARDS = 80
MAX_BYTES = 64 * 1024
# Control room C2: the whole backlog rides beside the cards (`clean_backlog`), inside the same
# 64 KB body cap (Kevin's ruling 2026-11-22: within the size limits). The laptop trims it to fit and
# sends `backlog_total`, so a cut list says how many more there are.
MAX_BACKLOG = 300
BACKLOG_TITLE_MAX = 120
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


def clean_plain(value, limit: int) -> str:
    """One line of plain text: invisible characters stripped, whitespace collapsed, capped."""
    raw = value if isinstance(value, str) else ""
    return re.sub(r"\s+", " ", _INVISIBLE.sub("", raw)).strip()[:limit]


def looks_secret(text: str) -> bool:
    if any(p.search(text) for p in _SECRET_SHAPES):
        return True
    for run in _LONG_RUN.findall(text):
        if re.search(r"[a-z]", run) and re.search(r"[A-Z]", run) and re.search(r"\d", run):
            return True
    return False


class Refused(ValueError):
    """The upload's SHAPE is wrong (not a board, a bad id or column); the message says which,
    never a value. A bad field value is never this: it is blanked (`CleanBoard.removed`)."""


@dataclass(frozen=True)
class CleanBoard:
    cards: list[dict]
    build_slot: str
    generated_at: datetime | None
    # The compact backlog (`clean_backlog`) and how many ideas the laptop had in all.
    backlog: list[dict] = field(default_factory=list)
    backlog_total: int = 0
    # Each blanked field as {"card": id, "field": name}: never the value.
    removed: list[dict] = field(default_factory=list)


class _Cleaner:
    def __init__(self):
        self.removed: list[dict] = []

    def text(self, value, card: str, name: str, limit: int = FIELD_MAX) -> str:
        raw = value if isinstance(value, (str, int, float)) and not isinstance(value, bool) else ""
        text = re.sub(r"\s+", " ", _INVISIBLE.sub("", str(raw))).strip()[:limit]
        if text and looks_secret(text):
            self.removed.append({"card": card, "field": name})
            return REMOVED
        return text

    def options(self, value, card: str) -> list[str]:
        if not isinstance(value, list):
            return []
        out = []
        for i, option in enumerate(value[:MAX_OPTIONS]):
            text = self.text(option, card, f"options[{i}]", OPTION_MAX)
            if text:
                out.append(text)
        return out

    def rice(self, value) -> dict | None:
        """An idea's score: the rubric's own names and the number code computed, nothing else."""
        from board import rubric

        if not isinstance(value, dict):
            return None
        picks = {
            "r": value.get("r") if value.get("r") in rubric.REACH else None,
            "i": value.get("i") if value.get("i") in rubric.IMPACT else None,
            "c": value.get("c") if value.get("c") in rubric.CONFIDENCE else None,
            "e": value.get("e") if value.get("e") in rubric.EFFORT else None,
        }
        score = value.get("score")
        if any(v is None for v in picks.values()) or isinstance(score, bool):
            return None
        if not isinstance(score, (int, float)) or not 0 <= score <= 1000:
            return None
        return {**picks, "score": round(float(score), 1)}

    def answer(self, value, card: str) -> dict | None:
        if not isinstance(value, dict):
            return None
        out = {
            "choice": self.text(value.get("choice"), card, "answer.choice", OPTION_MAX),
            "text": self.text(value.get("text"), card, "answer.text"),
            "at": _iso(value.get("at")),
            "by": self.text(value.get("by"), card, "answer.by", BY_MAX),
        }
        return out if out["choice"] or out["text"] else None


def _iso(value) -> str:
    when = _generated(value)
    return when.isoformat() if when is not None else ""


def _generated(value) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        when = datetime.fromisoformat(value[:40])
    except ValueError:
        return None
    return when if when.tzinfo is not None else None


def clean_board(body) -> CleanBoard:
    """The upload reduced to the fields the engine keeps, with any secret-shaped field blanked;
    `Refused` only for a wrong shape."""
    if not isinstance(body, dict) or not isinstance(body.get("cards"), list):
        raise Refused("Nothing was stored. The body must be the board's JSON, with a cards list.")
    raw_cards = body["cards"]
    if len(raw_cards) > MAX_CARDS:
        raise Refused(f"Nothing was stored. A board holds at most {MAX_CARDS} cards.")
    clean = _Cleaner()
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
        card["project"] = raw.get("project") if raw.get("project") in PROJECTS else "other"
        for name in TEXT_FIELDS:
            card[name] = clean.text(raw.get(name), card_id, name)
        if not card["title"]:
            card["title"] = card_id
        card["options"] = clean.options(raw.get("options"), card_id)
        card["free_text"] = raw.get("free_text") is not False
        card["ask_kind"] = raw.get("ask_kind") if raw.get("ask_kind") in ASK_KINDS else ""
        card["say"] = clean.text(raw.get("say"), card_id, "say", SAY_MAX)
        card["answer"] = clean.answer(raw.get("answer"), card_id)
        age = raw.get("age_min")
        card["age_min"] = age if isinstance(age, int) and 0 <= age <= MAX_AGE_MIN else 0
        card["stale"] = raw.get("stale") is True
        card["rice"] = clean.rice(raw.get("rice"))
        card["blast"] = raw.get("blast") if raw.get("blast") in BLAST_TIERS else ""
        card["scored_by"] = clean.text(raw.get("scored_by"), card_id, "scored_by", BY_MAX)
        cards.append(card)
    slot = clean.text(body.get("build_slot"), "board", "build_slot")
    backlog = clean_backlog(body.get("backlog"), clean)
    total = body.get("backlog_total")
    if not isinstance(total, int) or isinstance(total, bool) or total < len(backlog):
        total = len(backlog)
    return CleanBoard(
        cards=cards,
        build_slot=slot,
        generated_at=_generated(body.get("generated")),
        removed=clean.removed,
        backlog=backlog,
        backlog_total=min(total, 100_000),
    )


def clean_backlog(value, clean: _Cleaner | None = None) -> list[dict]:
    """The whole backlog, compact: id, title, RICE, blast tier, age. At most MAX_BACKLOG ideas,
    in the laptop's order; a bad item is skipped, never a refusal (the cards already stored)."""
    clean = clean or _Cleaner()
    if not isinstance(value, list):
        return []
    out: list[dict] = []
    seen: set[str] = set()
    for raw in value[:MAX_BACKLOG]:
        if not isinstance(raw, dict):
            continue
        idea_id = raw.get("id")
        if not isinstance(idea_id, str) or not _ID.match(idea_id) or idea_id in seen:
            continue
        seen.add(idea_id)
        age = raw.get("age_min")
        out.append(
            {
                "id": idea_id,
                "title": clean.text(raw.get("title"), idea_id, "title", BACKLOG_TITLE_MAX)
                or idea_id,
                "rice": clean.rice(raw.get("rice")),
                "blast": raw.get("blast") if raw.get("blast") in BLAST_TIERS else "",
                "age_min": age if isinstance(age, int) and 0 <= age <= MAX_AGE_MIN else 0,
            }
        )
    return out


def needs_kevin(cards: list[dict]) -> list[dict]:
    """Cards waiting on the member: in that column, or carrying a question (as the laptop says)."""
    return [c for c in cards if c["col"] == "needs_kevin" or c.get("ask")]


def answered_waiting(cards: list[dict]) -> list[dict]:
    """Questions he has answered whose owner has not picked the answer up yet."""
    return [c for c in cards if c.get("answer")]


def counts(cards: list[dict]) -> dict[str, int]:
    return {col: sum(1 for c in cards if c["col"] == col) for col in COLS}


def projects(cards: list[dict]) -> list[dict]:
    """Per project with any card, in the laptop's order: open cards and how many wait on him."""
    waiting = {c["id"] for c in needs_kevin(cards)}
    out = []
    for pid, title in PROJECTS.items():
        mine = [c for c in cards if c.get("project", "other") == pid]
        if mine:
            out.append(
                {
                    "id": pid,
                    "title": title,
                    "open": sum(1 for c in mine if c["col"] != "done"),
                    "needs": sum(1 for c in mine if c["id"] in waiting),
                }
            )
    return out
