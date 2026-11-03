"""What car, money and morning asks cost today, measured from recorded conversations
(procedures ticket 10, `.scratch/procedures/issues/10-baseline-round-trips.md`).

Reads the same `reason=conversation` batches `manage.py device_logs --render` reads (phone, head
unit and web all write LIVE_AUDIT / LIVE_LATENCY lines, `devicelogs/voice_audit.py`), and prints
**counts, medians and p95s only. No transcript text, no tool arguments, no tool results** leave
this module: the output is pasted into the public repo. Question words are read in memory to
classify an `ask_engine` call (its audit line does not name the engine tools behind it), and
then dropped.

Definitions, printed with the table so a reader need not open this file:

- **Ask**: from one user utterance to the next (or the session's end). A Live tool round trip
  spans several `turn_complete`s (a holding line, then the answer); they are one ask. A tool
  result that arrives after the next utterance still belongs to the ask that called it.
- **Live turns**: 1 + tool waves. A wave is a run of `tool_call` lines with nothing but other
  calls between them; each wave's results need one more model generation.
- **Sub-agent POSTs**: one per `route_ms` line (the phone's router) plus `rounds=` of each
  `investigate_ms` line (`ask_engine` and the fleet agents on the phone). The head unit logs no
  sub-agent line, so its `ask_engine` calls are counted as "POSTs unlogged".
- **Round trips**: Live turns + sub-agent POSTs.
- **First audio**: the user's last words (the last user line before any reply) to the first
  assistant line. **Answer**: the same start to the first assistant line after the ask's last
  tool result (the first assistant line when no tool ran). From line timestamps on the device's
  own clock, so phone, head unit and web are comparable; `reply_ms` is not (the head unit's
  spans the whole tool round trip by design, `ConvoLatency.kt`).
- **Tool errors**: `success=false` (`refused` when the web record's outcome says so), a timeout
  named in the result, `sent=false`, `tool_cancelled`, a call with no result by the session's
  end, and a sub-agent line whose `result=` is not Success.
- **Class**, by the tools called (router picks included): car (`next_service`,
  `vehicle_recalls`, `get_next_service`, `check_recalls`, `get_codes`, `list_vehicles`, an
  `ask_fleet` with no route line), money (`monthly_spend`, `account_balances`, `grocery_spend`,
  `get_monthly_spend`, `get_balance`, `get_grocery_spend`), morning (`get_sitrep`, or
  `list_events` with `list_checklists`); an `ask_engine` with none of those by its question's
  words; more than one class is `mixed`; tools but no class `other`; no tool `no tool`.
"""

from __future__ import annotations

import json
import math
import re
from collections import Counter, defaultdict
from dataclasses import dataclass, field

from devicelogs import voice_audit

AUDIT = "LIVE_AUDIT"
LATENCY = "LIVE_LATENCY"

CAR_TOOLS = frozenset(
    {"next_service", "vehicle_recalls", "get_next_service", "check_recalls", "get_codes",
     "list_vehicles"}
)
MONEY_TOOLS = frozenset(
    {"monthly_spend", "account_balances", "grocery_spend", "get_monthly_spend", "get_balance",
     "get_grocery_spend"}
)
MORNING_TOOLS = frozenset({"get_sitrep"})
ENGINE_ROUTER = "ask_engine"
CLASSES = ("car", "money", "morning", "mixed", "other", "no tool")
ERROR_KINDS = ("failed", "refused", "timeout", "not sent", "cancelled", "no result",
               "sub-agent not Success")
P95_MIN_N = 20

_WORDS = {
    "car": re.compile(r"\b(car|cars|vehicle|service|oil|recall|recalls|maintenance|tire|tires|"
                      r"mileage|miles|due)\b", re.I),
    "money": re.compile(r"\b(spend|spent|spending|money|balance|balances|budget|bank|account|"
                        r"accounts|grocer\w*|card)\b", re.I),
    "morning": re.compile(r"\b(brief|briefing|morning|agenda|my day|today)\b", re.I),
}
_PAIR = re.compile(r"(\w+)=(\S+)")


@dataclass
class Ask:
    device: str
    user_end_ms: int | None = None
    replied: bool = False
    first_audio_ms: int | None = None
    answer_ms: int | None = None
    awaiting_answer: bool = False
    tools: list[str] = field(default_factory=list)
    inner_tools: set[str] = field(default_factory=set)
    waves: int = 0
    last_was_call: bool = False
    router_posts: int = 0
    subagent_rounds: int = 0
    investigate_lines: int = 0
    engine_words: set[str] = field(default_factory=set)
    by_words: bool = False
    errors: Counter = field(default_factory=Counter)
    tool_ms: list[tuple[str, int]] = field(default_factory=list)

    @property
    def live_turns(self) -> int:
        return 1 + self.waves

    @property
    def unlogged_engine_calls(self) -> int:
        return max(self.tools.count(ENGINE_ROUTER) - self.investigate_lines, 0)

    @property
    def subagent_posts(self) -> int:
        return self.router_posts + self.subagent_rounds

    @property
    def round_trips(self) -> int:
        return self.live_turns + self.subagent_posts

    @property
    def first_audio(self) -> int | None:
        return _gap(self.user_end_ms, self.first_audio_ms)

    @property
    def answer(self) -> int | None:
        return _gap(self.user_end_ms, self.answer_ms)

    def klass(self) -> str:
        if not self.tools:
            return "no tool"
        names = set(self.tools) | self.inner_tools
        found = set()
        if names & CAR_TOOLS or "ask_fleet" in names:
            found.add("car")
        if names & MONEY_TOOLS:
            found.add("money")
        if names & MORNING_TOOLS or {"list_events", "list_checklists"} <= names:
            found.add("morning")
        if not found and ENGINE_ROUTER in names and self.engine_words:
            found = set(self.engine_words)
            self.by_words = True
        if len(found) > 1:
            return "mixed"
        return found.pop() if found else "other"


def _gap(start: int | None, end: int | None) -> int | None:
    if start is None or end is None:
        return None
    gap = end - start
    if gap < 0:  # past midnight on the device clock
        gap += 24 * 3600 * 1000
    return gap


def _ms(time: str) -> int:
    hh, mm, rest = time.split(":")
    ss, _, milli = rest.partition(".")
    return ((int(hh) * 60 + int(mm)) * 60 + int(ss)) * 1000 + int(milli or 0)


def _question_classes(args: str) -> set[str]:
    try:
        parsed = json.loads(args)
        text = parsed.get("question", "") if isinstance(parsed, dict) else str(parsed)
    except (ValueError, TypeError):
        text = args
    return {name for name, rx in _WORDS.items() if rx.search(str(text))}


def asks_in(lines: list[str], device: str) -> list[Ask]:
    """Every ask in one stream's lines (any number of sessions)."""
    out: list[Ask] = []
    for session in voice_audit._sessions(lines):
        out.extend(_session_asks(session["events"], device))
    return out


def _session_asks(events, device: str) -> list[Ask]:
    asks: list[Ask] = []
    current: Ask | None = None
    owner: dict[str, Ask] = {}  # call id -> ask
    open_calls: dict[str, str] = {}  # call id -> tool name, until its result
    user_turn_open = False  # non-final user chunks seen since the last turn_complete

    for time, tag, event, fields in events:
        at = _ms(time)
        if tag == LATENCY:
            p = dict(_PAIR.findall(fields))
            target = _owner_of(p.get("tool"), open_calls, owner) or current
            if target is None:
                continue
            if "route_ms" in p:
                target.router_posts += 1
                tools = p.get("route_tools", "none")
                if tools != "none":
                    target.inner_tools.update(t for t in tools.split(",") if t)
                if p.get("result", "Success") != "Success":
                    target.errors["sub-agent not Success"] += 1
            elif "investigate_ms" in p:
                target.investigate_lines += 1
                try:
                    target.subagent_rounds += int(p.get("rounds", "0"))
                except ValueError:
                    pass
                if p.get("result", "Success") != "Success":
                    target.errors["sub-agent not Success"] += 1
            continue
        if tag != AUDIT:
            continue
        if event == "user":
            final = fields.get("final") == "true"
            typed = fields.get("typed") == "true"
            echo = final and not typed and user_turn_open
            if echo:
                continue
            if current is None or current.replied or typed:
                current = Ask(device=device)
                asks.append(current)
            current.user_end_ms = at
            if not final:
                user_turn_open = True
            continue
        if current is None:
            continue
        if event == "assistant":
            current.replied = True
            current.last_was_call = False
            if current.first_audio_ms is None:
                current.first_audio_ms = at
            if current.answer_ms is None and (current.awaiting_answer or not current.tools):
                current.answer_ms = at
                current.awaiting_answer = False
        elif event == "tool_call":
            current.replied = True
            name = fields.get("name", "?")
            call_id = fields.get("id", "")
            if not current.last_was_call:
                current.waves += 1
            current.last_was_call = True
            current.tools.append(name)
            owner[call_id] = current
            open_calls[call_id] = name
            if name == ENGINE_ROUTER:
                current.engine_words |= _question_classes(fields.get("args", ""))
            # A tool call after the answer started means the answer is not final yet.
            current.answer_ms = None
            current.awaiting_answer = False
        elif event == "tool_result":
            call_id = fields.get("id", "")
            ask = owner.get(call_id, current)
            open_calls.pop(call_id, None)
            name = fields.get("name", "?")
            ask.last_was_call = False
            response = fields.get("response", "")
            if fields.get("success") != "true":
                ask.errors["refused" if _outcome(response) == "refused" else "failed"] += 1
            if re.search(r"timed out|timeout", response, re.I):
                ask.errors["timeout"] += 1
            if fields.get("sent") == "false":
                ask.errors["not sent"] += 1
            try:
                ms = int(fields.get("ms", "-1"))
            except ValueError:
                ms = -1
            if ms >= 0:
                ask.tool_ms.append((name, ms))
            ask.answer_ms = None
            ask.awaiting_answer = True
        elif event == "tool_cancelled":
            call_id = fields.get("id", "")
            owner.get(call_id, current).errors["cancelled"] += 1
            open_calls.pop(call_id, None)
        elif event == "turn_complete":
            user_turn_open = False
    for call_id in open_calls:
        owner[call_id].errors["no result"] += 1
    return asks


def _owner_of(tool: str | None, open_calls: dict, owner: dict) -> Ask | None:
    """The ask with the oldest outstanding call of `tool`: a sub-agent line is written just
    before its tool's result, which may come after the next utterance."""
    if not tool:
        return None
    for call_id, name in open_calls.items():
        if name == tool:
            return owner[call_id]
    return None


def _outcome(response: str) -> str | None:
    try:
        parsed = json.loads(response)
    except (ValueError, TypeError):
        return None
    return parsed.get("outcome") if isinstance(parsed, dict) else None


# ---- the table ----------------------------------------------------------------------------


def median(values: list[int]) -> str:
    if not values:
        return "-"
    s = sorted(values)
    mid = len(s) // 2
    value = s[mid] if len(s) % 2 else (s[mid - 1] + s[mid]) / 2
    return f"{value:g}"


def p95(values: list[int]) -> str:
    """Nearest-rank p95, only when there are enough values for it to mean something."""
    if len(values) < P95_MIN_N:
        return "-"
    s = sorted(values)
    return f"{s[max(math.ceil(0.95 * len(s)) - 1, 0)]:g}"


def _pair(values: list[int]) -> str:
    return f"{median(values)} / {p95(values)}"


@dataclass
class Totals:
    batches: int = 0
    unreadable: int = 0
    streams: int = 0
    sessions: int = 0


def render(asks: list[Ask], totals: Totals, *, window: str) -> list[str]:
    by: dict[tuple[str, str], list[Ask]] = defaultdict(list)
    for ask in asks:
        by[(ask.device, ask.klass())].append(ask)
    devices = sorted({a.device for a in asks})
    out = [
        "# Conversation baseline (procedures ticket 10)",
        "",
        f"Window: {window}. Counts, medians and p95s only; no transcript text, arguments or "
        "results.",
        f"Batches read: {totals.batches} (unreadable: {totals.unreadable}). Streams: "
        f"{totals.streams}. Sessions: {totals.sessions}. Asks: {len(asks)}.",
        f"p95 is shown only for n >= {P95_MIN_N}; '-' otherwise. Times in ms on the device's "
        "clock. Definitions: `server/devicelogs/baseline.py`.",
        "",
        "## Asks by device and class",
        "",
        "| device | class | asks | by question words | tools med | Live turns med | "
        "sub-agent POSTs med | POSTs unlogged (asks) | round trips med / p95 | "
        "first audio med / p95 | answer med / p95 | asks with a tool error |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for device in devices:
        for klass in CLASSES:
            group = by.get((device, klass))
            if not group:
                continue
            out.append(
                f"| {device} | {klass} | {len(group)} | {sum(a.by_words for a in group)} | "
                f"{median([len(a.tools) for a in group])} | "
                f"{median([a.live_turns for a in group])} | "
                f"{median([a.subagent_posts for a in group])} | "
                f"{sum(1 for a in group if a.unlogged_engine_calls)} | "
                f"{_pair([a.round_trips for a in group])} | "
                f"{_pair([a.first_audio for a in group if a.first_audio is not None])} | "
                f"{_pair([a.answer for a in group if a.answer is not None])} | "
                f"{sum(1 for a in group if a.errors)} |"
            )
    out += [
        "",
        "## Tool errors by device",
        "",
        "| device | asks | " + " | ".join(ERROR_KINDS) + " |",
        "|---|---|" + "---|" * len(ERROR_KINDS),
    ]
    for device in devices:
        mine = [a for a in asks if a.device == device]
        errors = sum((a.errors for a in mine), Counter())
        out.append(
            f"| {device} | {len(mine)} | " + " | ".join(str(errors[k]) for k in ERROR_KINDS) + " |"
        )
    out += [
        "",
        "## Tool latency by device (tool call to result, ms)",
        "",
        "| device | tool | calls | med | p95 |",
        "|---|---|---|---|---|",
    ]
    for device in devices:
        per_tool: dict[str, list[int]] = defaultdict(list)
        for ask in asks:
            if ask.device == device:
                for name, ms in ask.tool_ms:
                    per_tool[name].append(ms)
        for name in sorted(per_tool, key=lambda n: (-len(per_tool[n]), n)):
            values = per_tool[name]
            out.append(f"| {device} | {name} | {len(values)} | {median(values)} | {p95(values)} |")
    out += [
        "",
        "## Engine-side budget check (ticket 04: under 1.5 s p95 with no decide step)",
        "",
        "Web asks only: the engine ran each tool and measured it. The sum of an ask's car or "
        "money tool times is an ESTIMATE of a procedure running the same tools in sequence "
        "(no parallel steps, no composition).",
        "",
        "| class | asks | engine ms med / p95 |",
        "|---|---|---|",
    ]
    for klass, tools in (("car", CAR_TOOLS), ("money", MONEY_TOOLS)):
        sums = [
            sum(ms for name, ms in a.tool_ms if name in tools)
            for a in by.get(("web", klass), [])
            if any(name in tools for name, _ in a.tool_ms)
        ]
        out.append(f"| {klass} | {len(sums)} | {_pair(sums)} |")
    return out
