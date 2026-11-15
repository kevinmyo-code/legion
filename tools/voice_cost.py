r"""ESTIMATE what each voice conversation cost, from the phone's own log lines and published prices
(Kevin, 2026-11-15: "make sure we can audit the costs of each convo too. the sts pipeline was too
expensive. we need to know for this one too").

    python tools/voice_cost.py live.txt                 one capture (adb logcat -v threadtime, or
                                                         the text tools/phone_audit_engine.py saves)
    python tools/voice_cost.py - --year 2026 < live.txt

**An estimate, not a bill.** It multiplies the token counts the phone logged by the prices in
`tools/voice_prices.json` (per model, token kind and date, each with where and when it was read).
A model with no row prints "no price on file" and is left out of the totals; nothing is guessed.
Money is `Decimal`, never float.

What it reads (counts and model ids only; no conversation text is used):

- `LIVE_AUDIT: session_start model=...` / `session_end`: one conversation. `model=` names the
  engine: `gemini-3.8-live` (Live), `laptop+<model>`, `cloud+<model>` (the cascade).
- `LIVE_LATENCY: gemini_usage kind=<llm|greeting|stt|tts|cache_create> model=...` (cascade, one
  per billed call): priced by kind. `discarded=true` (cut off, nobody heard it) is priced and shown
  on its own. `failed=true` (an error came back) costs nothing. `usage=absent` (no counts came back)
  is priced from the measured quantity where one stands in (STT: audio seconds x 32 tokens/s; TTS:
  audio seconds x 25 tokens/s), marked DERIVED, else left unpriced and said.
- `LIVE_LATENCY: llm_usage ...` (cascade builds before gemini_usage existed, one per turn): priced
  only when the conversation has no per-call lines.
- `LIVE_AUDIT: usage prompt= prompt_text= prompt_audio= reply= reply_audio= thoughts=` (Live): each
  line priced as one billed generation (the 2026-11-10 audit's model; how Live bills is not stated
  by Google), the prompt's unattributed remainder at the text rate.
- `cache_create ... ttl_s=`: the cache's own creation (at the input rate: ASSUMED, unverified) and its
  storage for the whole TTL (tokens x hours x the storage rate).

Stdlib only. Tests: python -m pytest tools/tests/test_voice_cost.py -p no:cacheprovider
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import sys
from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

PRICES_FILE = Path(__file__).resolve().parent / "voice_prices.json"
MILLION = Decimal(1_000_000)
SECONDS_PER_HOUR = Decimal(3600)
STT_TOKENS_PER_SECOND = Decimal(32)  # Gemini audio input, audio-understanding doc
TTS_TOKENS_PER_SECOND = Decimal(25)  # Gemini TTS audio output, as the 2026-11-10 cost audit read it
ESTIMATE = (
    "ESTIMATE from published prices x the token counts the phone logged. Not a bill: the "
    "Gemini billing page is the only authority."
)

LINE = re.compile(
    r"^(?P<md>\d\d-\d\d) (?P<time>\d\d:\d\d:\d\d)\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+(?P<tag>[A-Z_]+)\s*: (?P<msg>.*)$"
)
KV = re.compile(r"(\w+)=(\S+)")


# --------------------------------------------------------------------------- prices


@dataclass(frozen=True)
class Price:
    model: str
    kind: str
    usd_per_mtok: Decimal
    start: datetime.date | None
    end: datetime.date | None
    read_on: str
    source: str


def load_prices(path: Path = PRICES_FILE) -> list[Price]:
    data = json.loads(path.read_text(encoding="utf-8"))
    out = []
    for row in data["prices"]:
        out.append(
            Price(
                model=row["model"],
                kind=row["kind"],
                usd_per_mtok=Decimal(row["usd_per_mtok"]),
                start=_date(row.get("from")),
                end=_date(row.get("to")),
                read_on=row["read_on"],
                source=row["source"],
            )
        )
    return out


def _date(s: str | None) -> datetime.date | None:
    return datetime.date.fromisoformat(s) if s else None


# Token kinds a model may not list on its own fall back to its general rate.
FALLBACK = {
    "input_text": "input",
    "input_audio": "input",
    "output_text": "output",
    "output_audio": "output",
    "cache_create": "input",
}


def rate(prices: list[Price], model: str, kind: str, day: datetime.date) -> Decimal | None:
    """USD per 1M tokens for [model]'s [kind] on [day], or None when nothing is on file."""
    for k in (kind, FALLBACK.get(kind)):
        if k is None:
            continue
        for p in prices:
            if p.model == model and p.kind == k and (p.start is None or day >= p.start) and (
                p.end is None or day <= p.end
            ):
                return p.usd_per_mtok
    return None


def known(prices: list[Price], model: str) -> bool:
    return any(p.model == model for p in prices)


# --------------------------------------------------------------------------- records


@dataclass
class Call:
    kind: str  # llm, greeting, stt, tts, cache_create, live, llm_turn
    model: str
    counts: dict[str, int]
    present: bool = True
    discarded: bool = False
    failed: bool = False
    audio_s: Decimal = Decimal(0)
    ttl_s: int = 0


@dataclass
class Conversation:
    day: datetime.date
    start: str
    engine: str
    model: str
    calls: list[Call] = field(default_factory=list)
    turn_lines: list[Call] = field(default_factory=list)  # old llm_usage, per turn
    summary: str | None = None
    turns: int | None = None


def _ints(fields: dict[str, str]) -> dict[str, int]:
    out = {}
    for k, v in fields.items():
        if re.fullmatch(r"-?\d+", v):
            out[k] = int(v)
    return out


def engine_of(model_field: str) -> tuple[str, str]:
    """(`live`|`laptop`|`cloud`|..., the model id) from session_start's model=."""
    if "+" in model_field:
        engine, model = model_field.split("+", 1)
        return engine, model
    return ("live" if "live" in model_field else "other"), model_field


def parse(lines: list[str], year: int) -> list[Conversation]:
    convs: list[Conversation] = []
    current: Conversation | None = None
    for raw in lines:
        m = LINE.match(raw.rstrip("\n"))
        if not m:
            continue
        tag, msg = m["tag"], m["msg"]
        day = datetime.date(year, int(m["md"][:2]), int(m["md"][3:]))
        fields = dict(KV.findall(msg))
        if tag == "LIVE_AUDIT" and msg.startswith("session_start "):
            engine, model = engine_of(fields.get("model", "unknown"))
            current = Conversation(day=day, start=f"{m['md']} {m['time']}", engine=engine, model=model)
            convs.append(current)
            continue
        if current is None:
            continue
        if tag == "LIVE_AUDIT" and msg.startswith("session_end"):
            current = None
            continue
        if tag == "LIVE_AUDIT" and msg.startswith("usage "):
            current.calls.append(Call("live", current.model, _ints(fields)))
        elif tag == "LIVE_LATENCY" and msg.startswith("gemini_usage "):
            current.calls.append(
                Call(
                    kind=fields.get("kind", "?"),
                    model=fields.get("model", "?"),
                    counts=_ints(fields),
                    present=fields.get("usage") != "absent",
                    discarded=fields.get("discarded") == "true",
                    failed=fields.get("failed") == "true",
                    audio_s=Decimal(fields.get("audio_s", "0")),
                    ttl_s=int(fields.get("ttl_s", "0")),
                )
            )
        elif tag == "LIVE_LATENCY" and msg.startswith("llm_usage "):
            current.turn_lines.append(Call("llm_turn", fields.get("model", "?"), _ints(fields)))
        elif tag == "LIVE_LATENCY" and msg.startswith("cascade_usage_summary "):
            current.summary = msg
            # The engines the conversation ran on, in order (laptop>cloud after a fallback).
            current.engine = fields.get("engine", current.engine)
            turns = fields.get("turns")
            current.turns = int(turns) if turns and turns.isdigit() else None
    return convs


# --------------------------------------------------------------------------- pricing


@dataclass
class Priced:
    usd: Decimal = Decimal(0)
    notes: list[str] = field(default_factory=list)
    derived: bool = False


def _cost(tokens: int | Decimal, usd_per_mtok: Decimal) -> Decimal:
    return Decimal(tokens) * usd_per_mtok / MILLION


def price_call(c: Call, prices: list[Price], day: datetime.date) -> Priced:
    out = Priced()
    if c.failed:
        out.notes.append(f"{c.kind} failed call: not billed (reasoned)")
        return out
    if not known(prices, c.model):
        out.notes.append(f"{c.model}: no price on file")
        return out
    n = c.counts

    def add(kind: str, tokens: int | Decimal) -> None:
        if not tokens:
            return
        r = rate(prices, c.model, kind, day)
        if r is None:
            out.notes.append(f"{c.model} {kind}: no price on file")
            return
        out.usd += _cost(tokens, r)

    if c.kind in ("llm", "greeting", "llm_turn"):
        if not c.present:
            out.notes.append(f"{c.kind} with no usage counts: not priced")
            return out
        cached = n.get("cached", 0)
        add("input", max(n.get("prompt", 0) - cached, 0))
        add("cached_input", cached)
        add("output", n.get("out", 0) + n.get("thoughts", 0))
    elif c.kind == "stt":
        if c.present:
            text = n.get("prompt_text", 0) or max(n.get("prompt", 0) - n.get("prompt_audio", 0), 0)
            add("input_text", text)
            add("input_audio", n.get("prompt_audio", 0))
            add("output", n.get("out", 0) + n.get("thoughts", 0))
        else:
            out.derived = True
            add("input_audio", (c.audio_s * STT_TOKENS_PER_SECOND).to_integral_value())
    elif c.kind == "tts":
        if c.present:
            add("input", n.get("prompt", 0))
            add("output_audio", n.get("out_audio", 0))
            add("output", n.get("out_text", 0))
        else:
            out.derived = True
            add("output_audio", (c.audio_s * TTS_TOKENS_PER_SECOND).to_integral_value())
    elif c.kind == "cache_create":
        tokens = n.get("prompt", 0)
        if not c.present or not tokens:
            out.notes.append("cache create with no token count: not priced")
            return out
        add("cache_create", tokens)
        storage = rate(prices, c.model, "cache_storage_per_hour", day)
        if storage is None:
            out.notes.append(f"{c.model} cache storage: no price on file")
        else:
            out.usd += _cost(tokens, storage) * Decimal(c.ttl_s) / SECONDS_PER_HOUR
        out.notes.append("cache creation priced at the input rate (assumed)")
    elif c.kind == "live":
        prompt, text, audio = n.get("prompt", 0), n.get("prompt_text", 0), n.get("prompt_audio", 0)
        add("input_text", text + max(prompt - text - audio, 0))
        add("input_audio", audio)
        reply, reply_audio = n.get("reply", 0), n.get("reply_audio", 0)
        add("output_text", max(reply - reply_audio, 0) + n.get("thoughts", 0))
        add("output_audio", reply_audio)
    else:
        out.notes.append(f"unknown kind {c.kind}: not priced")
    return out


@dataclass
class Bill:
    conv: Conversation
    by_kind: dict[str, Decimal]
    total: Decimal
    notes: list[str]
    derived: bool


def price_conversation(conv: Conversation, prices: list[Price]) -> Bill:
    by_kind: dict[str, Decimal] = {}
    notes: list[str] = []
    derived = False
    calls = conv.calls
    # Old builds logged one llm_usage per turn and no per-call lines: price those instead.
    if not any(c.kind in ("llm", "greeting") for c in calls):
        calls = calls + conv.turn_lines
    for c in calls:
        p = price_call(c, prices, conv.day)
        bucket = ("discarded " + c.kind) if c.discarded else ("llm" if c.kind == "llm_turn" else c.kind)
        by_kind[bucket] = by_kind.get(bucket, Decimal(0)) + p.usd
        derived = derived or p.derived
        for note in p.notes:
            if note not in notes:
                notes.append(note)
    return Bill(conv, by_kind, sum(by_kind.values(), Decimal(0)), notes, derived)


def usd(d: Decimal) -> str:
    return "$" + str(d.quantize(Decimal("0.00001"), rounding=ROUND_HALF_UP))


def report(lines: list[str], year: int, prices: list[Price] | None = None) -> list[str]:
    prices = prices if prices is not None else load_prices()
    bills = [price_conversation(c, prices) for c in parse(lines, year)]
    out = [ESTIMATE]
    if not bills:
        return out + ["no conversations (no LIVE_AUDIT session_start lines) in this window"]
    days: dict[datetime.date, Decimal] = {}
    for b in bills:
        kinds = " ".join(f"{k}={usd(v)}" for k, v in sorted(b.by_kind.items()) if v)
        turns = f" turns={b.conv.turns}" if b.conv.turns is not None else ""
        flag = " (part DERIVED from audio seconds)" if b.derived else ""
        out.append(f"{b.conv.start} engine={b.conv.engine} model={b.conv.model}{turns} total={usd(b.total)}{flag}")
        if kinds:
            out.append(f"    {kinds}")
        for note in b.notes:
            out.append(f"    note: {note}")
        days[b.conv.day] = days.get(b.conv.day, Decimal(0)) + b.total
    out.append("")
    for day, total in sorted(days.items()):
        n = sum(1 for b in bills if b.conv.day == day)
        out.append(f"day {day.isoformat()}: {n} conversations, {usd(total)}")
    out.append(f"window: {len(bills)} conversations, {usd(sum(days.values(), Decimal(0)))}")
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="ESTIMATE voice conversation costs from phone logs.")
    ap.add_argument("logfile", help="adb logcat -v threadtime capture or phone_audit_engine text, or -")
    ap.add_argument("--year", type=int, default=datetime.date.today().year, help="logcat lines carry no year")
    ap.add_argument("--prices", default=str(PRICES_FILE))
    args = ap.parse_args(argv)
    text = sys.stdin.read() if args.logfile == "-" else Path(args.logfile).read_text(encoding="utf-8", errors="replace")
    print("\n".join(report(text.splitlines(), args.year, load_prices(Path(args.prices)))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
