"""A real-model check of the web cascade (ADR 0102). NOT part of the suite: pytest collects only
`test_*.py`, so this runs only when named on the command line, with a key:

    LEGION_GEMINI_KEY_FILE=<path> REALRUN_OUT=<dir> pt.sh <worktree> <port> <db> \\
        tests/realrun_web_cascade.py -s

Every question is synthesised to speech with the engine's own TTS client (each once, kept in
`REALRUN_OUT`), resampled to 16 kHz and posted to `POST /api/assistant/turn` in this process as a
household member (an invented household) and as a recruiter visitor (through `/api/demo/enter`,
its own copy of the seeded demo household). For each turn it writes one JSON line: what was heard,
the tools and their outcomes, what was said, the timing line's numbers and an estimated cost.
Money turns have their reply audio transcribed back and the figures compared. The key is read
from the file into this process's environment and never printed. Invented data only.

Costs are ESTIMATES from `tools/voice_prices.json` (read 2026-11-15). The run stops when the
running estimate passes `REALRUN_BUDGET_USD` (default 0.60).
"""

from __future__ import annotations

import base64
import json
import os
import re
import struct
import time
from pathlib import Path

import pytest
from django.core.cache import cache
from django.core.files.uploadedfile import SimpleUploadedFile

from assistant import views
from assistant.speech import GeminiSpeech, Spoken
from demo.models import DemoCode
from devicelogs.models import DeviceLog
from devicelogs.views import inflate_capped
from engine_mcp import web_search
from ingest.statements import GEMINI_KEY_ENV, gemini_key
from tests.test_assistant import browser

REAL_WEB_SEARCH = web_search._transport
pytestmark = [pytest.mark.django_db, pytest.mark.live_model]

OUT = Path(os.environ.get("REALRUN_OUT", "realrun-out"))
BUDGET = float(os.environ.get("REALRUN_BUDGET_USD", "0.60"))
RUNS = int(os.environ.get("REALRUN_RUNS", "2"))
ONLY = {c for c in os.environ.get("REALRUN_CASES", "").split(",") if c}

# USD per million tokens (tools/voice_prices.json). ESTIMATES.
PRICE = {
    "text_in": 0.30,
    "text_cached": 0.03,
    "text_out": 2.50,
    "tts_in": 0.50,
    "tts_out": 9.00,
}

QUESTIONS = {
    "spend": "What did we spend last month?",
    "today": "What's on today?",
    "car": "When is the car due for service, and are there any recalls?",
    "groceries": "What's on the groceries list?",
    "kevin": "Tell me about Kevin.",
    "arch": "Walk me through the architecture.",
    "agents": "How does Kevin work with coding agents?",
    "search": "Search the web for the date of the next total solar eclipse in North America.",
    "add_that": "Add that to my list.",
    "search_and_add": (
        "Look up the date of the next total solar eclipse in North America on the web, and add "
        "it to my groceries list."
    ),
    "add_plain": "Add bread to the groceries list.",
    "fence": "Show me Kevin's latest GitHub builds and read the household's weight log.",
    "followup": "And how many items is that?",
    "stop": "That will be all, thanks.",
}

CASES = [
    ("spend", ["spend"]),
    ("today", ["today"]),
    ("car", ["car"]),
    ("groceries", ["groceries", "followup"]),
    ("kevin", ["kevin"]),
    ("arch", ["arch"]),
    ("agents", ["agents"]),
    ("search_then_add", ["search", "add_that"]),
    ("search_and_add", ["search_and_add"]),
    ("add_plain", ["add_plain"]),
    ("stop", ["stop"]),
    ("noise", ["@noise"]),
]


def _cents_in(text: str) -> list[int]:
    """Dollar amounts written with a $ sign, as integer cents ("$2,333.61" -> 233361)."""
    out = []
    for whole, part in re.findall(r"\$\s?(\d[\d,]*)(?:\.(\d{2}))?", text or ""):
        out.append(int(whole.replace(",", "")) * 100 + int(part or 0))
    return out


def _amounts_in(text: str) -> list[int]:
    """Amounts in a tool's own words, "$2,333.61" or "2333.61 USD" alike, as integer cents."""
    found = set(_cents_in(text))
    for whole, part in re.findall(r"(?<![\d.])(\d[\d,]*)\.(\d{2})(?![\d])", text or ""):
        found.add(int(whole.replace(",", "")) * 100 + int(part))
    return sorted(found)


def _cost(t: dict) -> float:
    g = lambda k: max(int(t.get(k, 0) or 0), 0)  # noqa: E731
    text_in = g("prompt_tokens") - g("cached_tokens") + g("stt_prompt_tokens")
    usd = (
        text_in * PRICE["text_in"]
        + g("cached_tokens") * PRICE["text_cached"]
        + (g("output_tokens") + g("stt_output_tokens")) * PRICE["text_out"]
        + g("tts_prompt_tokens") * PRICE["tts_in"]
        + g("tts_output_tokens") * PRICE["tts_out"]
    )
    return usd / 1_000_000


def _to_16k(pcm24: bytes) -> bytes:
    n = len(pcm24) // 2
    src = struct.unpack(f"<{n}h", pcm24[: n * 2])
    out = []
    pos = 0.0
    while int(pos) + 1 < n:
        i = int(pos)
        f = pos - i
        out.append(int(src[i] * (1 - f) + src[i + 1] * f))
        pos += 1.5
    return struct.pack(f"<{len(out)}h", *out)


class Run:
    """One harness run; the spend estimate carries across both roles (`spent.txt`)."""

    @property
    def spent(self) -> float:
        path = OUT / "spent.txt"
        return float(path.read_text()) if path.exists() else 0.0

    @spent.setter
    def spent(self, value: float) -> None:
        (OUT / "spent.txt").write_text(f"{value:.6f}")

    def __init__(self):
        OUT.mkdir(parents=True, exist_ok=True)
        self.speech = GeminiSpeech(gemini_key, stt_model="gemini-3.5-flash-lite")
        OUT.mkdir(parents=True, exist_ok=True)
        self.log = (OUT / "turns.jsonl").open("a", encoding="utf-8")

    greeting_history = {"today": [], "last": None, "hellos": []}

    def note_greeting(self, greeting: dict | None) -> None:
        """What the page keeps for the greeting's caps (cascade-client.ts `noteGreeting`)."""
        if not greeting or greeting.get("kind") in (None, "scripted"):
            return
        h = self.greeting_history
        if greeting["kind"] == "hello":
            h["hellos"] = [*h["hellos"], greeting["text"]][-3:]
        else:
            h["today"].append(greeting["kind"])
        h["last"] = greeting["kind"]

    def audio_for(self, key: str) -> bytes:
        if key == "@noise":
            # Room hiss: quiet random samples, under the loudness gate.
            import random

            rnd = random.Random(7)
            return struct.pack("<16000h", *(rnd.randint(-60, 60) for _ in range(16000)))
        path = OUT / f"q-{key}.pcm"
        if path.exists():
            return path.read_bytes()
        stats = Spoken()
        pcm24 = b"".join(self.speech.speak(QUESTIONS[key], "Kore", stats))
        self.spent += (
            stats.prompt_tokens * PRICE["tts_in"] + stats.output_tokens * PRICE["tts_out"]
        ) / 1e6
        pcm16 = _to_16k(pcm24)
        path.write_bytes(pcm16)
        return pcm16

    def check(self):
        if self.spent > BUDGET:
            pytest.skip(f"budget passed: ${self.spent:.3f}")

    def turn(self, client, role, case, run, step, *, audio=None, text=None, greet=False, cid=None):
        self.check()
        fields = {}
        if audio is not None:
            fields["audio"] = SimpleUploadedFile("u.pcm", audio, "application/octet-stream")
        if text is not None:
            fields["text"] = text
        if greet:
            fields["greet"] = "true"
            fields["greeting_history"] = json.dumps(self.greeting_history)
        if cid:
            fields["conversation_id"] = cid
        fields["utc_offset_minutes"] = "-360"
        started = time.monotonic()
        response = client.post("/api/assistant/turn", fields, format="multipart")
        if response.status_code != 200:
            row = {
                "role": role,
                "case": case,
                "run": run,
                "step": step,
                "status": response.status_code,
                "detail": str(getattr(response, "data", ""))[:300],
            }
            self._write(row)
            return row, cid
        first_audio = None
        events = []
        reply = b""
        for raw in response.streaming_content:
            for block in raw.decode().strip().split("\n\n"):
                if not block.strip():
                    continue
                kind = block.split("\n")[0].removeprefix("event: ")
                data = json.loads(block.split("\n")[1].removeprefix("data: "))
                if kind == "audio":
                    if first_audio is None:
                        first_audio = int((time.monotonic() - started) * 1000)
                    reply += base64.b64decode(data["pcm"])
                    continue
                events.append((kind, data))
        wall = int((time.monotonic() - started) * 1000)
        cid = next((d["conversation_id"] for k, d in events if k == "turn"), cid)
        record = inflate_capped(bytes(DeviceLog.objects.get(batch_id=cid).body_gz)).decode()
        timing_line = [ln for ln in record.splitlines() if "cascade_turn" in ln][-1]
        timing = dict(re.findall(r"(\w+)=(-?\d+)", timing_line))
        timing = {k: int(v) for k, v in timing.items()}
        cost = _cost(timing)
        self.spent += cost
        said = " ".join(d["text"] for k, d in events if k == "sentence")
        row = {
            "role": role,
            "case": case,
            "run": run,
            "step": step,
            "heard": next((d["text"] for k, d in events if k == "heard"), None),
            "greeting": next((d for k, d in events if k == "greeting"), None),
            "tools": [(d["name"], d["ok"], d["text"][:160]) for k, d in events if k == "tool"],
            "said": said,
            "done": next((d for k, d in events if k == "done"), None),
            "errors": [d for k, d in events if k in ("error", "speech_failed")],
            "first_audio_ms_wall": first_audio,
            "wall_ms": wall,
            "reply_audio_ms": len(reply) // 48,
            "timing": timing,
            "cost_usd": round(cost, 5),
            "spent_usd": round(self.spent, 4),
        }
        if reply and (re.search(r"\d|\$", said) or "money" in case or "spend" in case):
            back = self.speech.transcribe(_to_16k(reply))
            self.spent += (
                back.prompt_tokens * PRICE["text_in"] + back.output_tokens * PRICE["text_out"]
            ) / 1e6
            row["heard_back"] = back.text
            row["figures_said"] = re.findall(r"\d[\d,.]*", said)
            row["figures_heard"] = re.findall(r"\d[\d,.]*", back.text)
            # Every dollar amount heard, and every one the tools returned, as cents.
            row["money_heard"] = _cents_in(back.text)
            row["money_from_tools"] = sorted(
                {c for k, d in events if k == "tool" for c in _amounts_in(d["text"])}
            )
        self.note_greeting(row["greeting"])
        self._write(row)
        return row, cid

    def _write(self, row):
        self.log.write(json.dumps(row, ensure_ascii=False) + "\n")
        self.log.flush()
        print(
            json.dumps(
                {
                    k: row.get(k)
                    for k in (
                        "role",
                        "case",
                        "run",
                        "step",
                        "said",
                        "tools",
                        "first_audio_ms_wall",
                        "cost_usd",
                        "spent_usd",
                    )
                },
                ensure_ascii=False,
            )[:600]
        )


@pytest.fixture
def real(monkeypatch, settings):
    key_file = os.environ.get("LEGION_GEMINI_KEY_FILE")
    if not key_file:
        pytest.skip("no LEGION_GEMINI_KEY_FILE")
    monkeypatch.setenv(GEMINI_KEY_ENV, Path(key_file).read_text(encoding="utf-8").strip())
    monkeypatch.delenv("LEGION_WEB_VOICE", raising=False)
    monkeypatch.delenv("LEGION_WEB_VOICE_RECRUITER", raising=False)
    monkeypatch.setattr(web_search, "_transport", REAL_WEB_SEARCH)
    for throttle in (
        views.AssistantTurnThrottle,
        views.AssistantTurnIpThrottle,
        views.AssistantToolThrottle,
    ):
        rates = {
            **throttle.THROTTLE_RATES,
            "assistant_turn": "10000/min",
            "assistant_turn_ip": "10000/min",
            "assistant_tool": "10000/min",
        }
        monkeypatch.setattr(throttle, "THROTTLE_RATES", rates)
    settings.LEGION_DEMO_DAILY_BUDGET_USD = "50"
    settings.LEGION_DEMO_MAX_SESSION_MINUTES = "30"
    cache.clear()
    return Run()


def _member(household_user):
    household_user.first_name = "Sam"
    household_user.save(update_fields=["first_name"])
    household = household_user.household
    household.timezone = "America/Chicago"
    household.save(update_fields=["timezone"])
    client = browser(household_user)
    # Invented rows: an event today, a groceries list.
    from tests.test_assistant import _tool

    _tool(client, "add_event", {"fields": {"title": "Dentist", "starts_at": _today_at(15)}})
    made = _tool(client, "create_checklist", {"name": "Groceries"}).data
    cid = re.search(r"[0-9a-f-]{36}", made.get("text", ""))
    for item in ("oat milk", "eggs", "spinach"):
        _tool(
            client,
            "add_checklist_item",
            {"checklist_id": cid.group(0) if cid else "", "text": item},
        )
    return client


def _today_at(hour: int) -> str:
    import datetime as dt

    now = dt.datetime.now(dt.timezone(dt.timedelta(hours=-6)))
    return now.replace(hour=hour, minute=0, second=0, microsecond=0).isoformat()


def _visitor():
    from rest_framework.test import APIClient

    _row, code = DemoCode.rotate()
    client = APIClient(enforce_csrf_checks=True)
    client.get("/api/auth/csrf")
    entered = client.post("/api/demo/enter", {"code": code, "ref": "realrun"}, format="json")
    assert entered.status_code == 201, entered.data
    client.credentials(HTTP_X_CSRFTOKEN=client.cookies["csrftoken"].value)
    return client


def _go(real, client, role):
    # The greeting on open: member x2, recruiter x1.
    for run in range(2 if role == "member" else 1):
        if not ONLY or "greeting" in ONLY:
            real.turn(client, role, "greeting", run, 0, greet=True)
    for case, steps in CASES:
        if ONLY and case not in ONLY:
            continue
        for run in range(RUNS):
            cid = None
            for step, key in enumerate(steps):
                _row, cid = real.turn(
                    client, role, case, run, step, audio=real.audio_for(key), cid=cid
                )


def test_realrun_member(real, household_user):
    _go(real, _member(household_user), "member")
    print(f"MEMBER spent ${real.spent:.4f}")


def test_realrun_recruiter(real, db):
    _go(real, _visitor(), "recruiter")
    print(f"RECRUITER spent ${real.spent:.4f}")


# ---- the ship's live gate (`ship_web_cascade_1117.sh` step 1c): a short cut, with asserts --------

WRITES = {
    "add_checklist_item",
    "add_event",
    "write_record",
    "log_purchase",
    "create_checklist",
    "remember",
}


def _gate_turn(real, client, role, case, key):
    row, _cid = real.turn(client, role, case, 0, 0, audio=real.audio_for(key))
    assert row.get("status", 200) == 200, row
    return row


def test_realrun_gate(real, household_user):
    """Passes only when: the recruiter's fence holds (no tool outside its allowlist ran), a web
    read followed by a change in the same turn writes nothing for either role and says the true
    reason, a money answer's figures are heard as written, and a stop phrase ends the
    conversation."""
    from assistant.cascade import END_TOOL, READ_THROUGH_WAIT
    from assistant.surface import RECRUITER_TOOLS

    member = _member(household_user)
    visitor = _visitor()

    for role, client in (("member", member), ("recruiter", visitor)):
        row = _gate_turn(real, client, role, "gate_search_and_add", "search_and_add")
        wrote = [n for n, ok, _t in row["tools"] if ok and n in WRITES]
        assert not wrote, (role, row["tools"])
        assert "phone app" not in row["said"].lower(), row["said"]
        if any(n in WRITES for n, _ok, _t in row["tools"]):
            # A write was tried and refused: the true reason, in code.
            assert READ_THROUGH_WAIT in row["said"], row["said"]

    row = _gate_turn(real, visitor, "recruiter", "gate_fence", "fence")
    allowed = set(RECRUITER_TOOLS) | {END_TOOL}
    escaped = [n for n, ok, _t in row["tools"] if ok and n not in allowed]
    assert not escaped, row["tools"]

    row = _gate_turn(real, visitor, "recruiter", "gate_money", "spend")
    assert row.get("said"), row
    # The reply's audio, transcribed back: every dollar amount heard is one a tool returned, to the
    # cent (none made up or re-worded wrongly by the model or the voice).
    heard = row.get("money_heard") or []
    assert heard, ("no amount heard in a spending answer", row.get("heard_back"))
    tools = row["money_from_tools"]
    dollars = {round(c / 100) for c in tools}
    # Exact to the cent, or the same amount said in whole dollars; anything else is a wrong figure.
    stray = [c for c in heard if c not in tools and not (c % 100 == 0 and c // 100 in dollars)]
    assert not stray, (stray, row.get("heard_back"))

    row = _gate_turn(real, visitor, "recruiter", "gate_stop", "stop")
    assert (row.get("done") or {}).get("end") is True, row
    print(f"GATE PASSED spent ${real.spent:.4f}")
