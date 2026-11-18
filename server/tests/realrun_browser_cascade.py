"""A real-browser check of the web cascade (ADR 0102). NOT part of the suite (pytest collects only
`test_*.py`); it runs when named, with a key and Node + Playwright:

    LEGION_GEMINI_KEY_FILE=<path> BROWSER_OUT=<dir> PLAYWRIGHT_DIR=<node_modules dir> \\
        pt.sh <worktree> <port> <db> tests/realrun_browser_cascade.py -s

It starts the engine on 127.0.0.1 (pytest-django's live server, against the throwaway test
database), seeds the demo household, makes an invented member household with a login made for
this run only, mints a demo code, synthesises the spoken WAVs with the engine's own TTS client,
and drives a real headless Chromium with Chrome's fake microphone through
`tests/realrun_browser_cascade.mjs`, one scenario per launch. The built SPA must be in
`server/static/app` (`npm run build`). Results and screenshots go to `BROWSER_OUT`.
The key is read from its file into this process and never printed.
"""

from __future__ import annotations

import json
import os
import secrets
import struct
import subprocess
from pathlib import Path

import pytest
from django.core.cache import cache

from assistant import views
from assistant.speech import GeminiSpeech, Spoken
from demo.models import DemoCode
from devicelogs.models import DeviceLog
from devicelogs.views import inflate_capped
from engine_mcp import web_search
from household.models import Household, HouseholdMember, User
from ingest.statements import GEMINI_KEY_ENV, gemini_key

REAL_WEB_SEARCH = web_search._transport
pytestmark = [pytest.mark.django_db(transaction=True), pytest.mark.live_model]

OUT = Path(os.environ.get("BROWSER_OUT", "browser-out"))
HERE = Path(__file__).resolve().parent
RATE = 24_000


def _wav(pcm: bytes, rate: int = RATE) -> bytes:
    header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
    return header + b"data" + struct.pack("<I", len(pcm)) + pcm


def _silence(seconds: float) -> bytes:
    return b"\x00\x00" * int(RATE * seconds)


@pytest.fixture
def real(monkeypatch, settings):
    key_file = os.environ.get("LEGION_GEMINI_KEY_FILE")
    if not key_file:
        pytest.skip("no LEGION_GEMINI_KEY_FILE")
    monkeypatch.setenv(GEMINI_KEY_ENV, Path(key_file).read_text(encoding="utf-8").strip())
    monkeypatch.delenv("LEGION_WEB_VOICE", raising=False)
    monkeypatch.delenv("LEGION_WEB_VOICE_RECRUITER", raising=False)
    monkeypatch.setattr(web_search, "_transport", REAL_WEB_SEARCH)
    for throttle in (views.AssistantTurnThrottle, views.AssistantTurnIpThrottle):
        rates = {**throttle.THROTTLE_RATES, "assistant_turn": "1000/min"}
        rates["assistant_turn_ip"] = "1000/min"
        monkeypatch.setattr(throttle, "THROTTLE_RATES", rates)
    settings.LEGION_DEMO_DAILY_BUDGET_USD = "5"
    cache.clear()
    OUT.mkdir(parents=True, exist_ok=True)


def _wavs() -> dict[str, Path]:
    """The fake microphone's files. The capture starts at the opening tap and the page ignores the
    mic while the greeting plays, so each file waits out the greeting (`lead`) before the person
    speaks: about 4 s for a member's short greeting, about 20 s for the demo's long hello. A second
    question, one with a long answer to interrupt, follows once the first reply has played and the
    page has re-opened the mic on its own."""
    speech = GeminiSpeech(gemini_key, stt_model="gemini-3.5-flash-lite")
    said = {}
    for name, text in (
        ("question", "What's on the grocery list?"),
        ("long", "Walk me through how LEGION is built, in detail."),
        ("stop", "That will be all, thanks."),
    ):
        path = OUT / f"tts-{name}.pcm"
        if not path.exists():
            path.write_bytes(b"".join(speech.speak(text, "Kore", Spoken())))
        said[name] = path.read_bytes()
    out = {}
    for who, lead, gap in (("mem", 6.5, 14.0), ("rec", 22.0, 16.0)):
        q = _silence(lead) + said["question"] + _silence(gap) + said["long"] + _silence(45.0)
        stop = _silence(lead) + said["stop"] + _silence(30.0)
        for name, pcm in ((f"{who}-question", q), (f"{who}-stop", stop)):
            path = OUT / f"{name}.wav"
            path.write_bytes(_wav(pcm))
            out[name] = path
    path = OUT / "silence.wav"
    path.write_bytes(_wav(_silence(60.0)))
    out["silence"] = path
    return out


def _member() -> tuple[str, str]:
    import re

    from tests.test_assistant import _tool, browser

    household = Household.objects.create(name="Cedar Lane", timezone="America/Chicago")
    email = "sam.cedar@example.invalid"
    password = secrets.token_urlsafe(18)
    user = User.objects.create_user(email=email, password=password, first_name="Sam")
    HouseholdMember.objects.create(user=user, household=household, role=HouseholdMember.OWNER)
    client = browser(user)
    made = _tool(client, "create_checklist", {"name": "Groceries"}).data
    cid = re.search(r"[0-9a-f-]{36}", made["text"]).group(0)
    for item in ("oat milk", "eggs", "spinach"):
        _tool(client, "add_checklist_item", {"checklist_id": cid, "text": item})
    return email, password


def _records() -> list[dict]:
    rows = []
    for row in DeviceLog.objects.filter(device_label="web").order_by("received_at"):
        body = inflate_capped(bytes(row.body_gz)).decode()
        rows.append(
            {
                "visitor": row.demo_visitor,
                "lines": [ln.split(": ", 1)[-1] for ln in body.splitlines()],
            }
        )
    return rows


def _run(live_server, scenario: str, wav: Path, extra: dict) -> dict:
    env = {**os.environ, "NODE_PATH": os.environ["PLAYWRIGHT_DIR"]}
    args = {
        "base": live_server.url,
        "scenario": scenario,
        "wav": str(wav),
        "out": str(OUT),
        **extra,
    }
    done = subprocess.run(
        ["node", str(HERE / "realrun_browser_cascade.mjs"), json.dumps(args)],
        env=env,
        capture_output=True,
        text=True,
        timeout=400,
    )
    (OUT / f"{scenario}.node.log").write_text(done.stdout + "\n" + done.stderr, encoding="utf-8")
    try:
        return json.loads((OUT / f"{scenario}.json").read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {"scenario": scenario, "error": done.stderr[-2000:]}


def test_browser(real, live_server, settings):
    from household.demo_seed import seed

    seed()
    wavs = _wavs()
    email, password = _member()
    results = []
    only = {s for s in os.environ.get("BROWSER_SCENARIOS", "").split(",") if s}

    def run(scenario, wav, **extra):
        if only and scenario not in only:
            return
        if scenario.startswith("rec"):
            _row, code = DemoCode.rotate()
            extra["code"] = code
        else:
            extra["email"], extra["password"] = email, password
        results.append(_run(live_server, scenario, wavs[wav], extra))

    run("rec-silence", "silence")
    run("rec-question", "rec-question")
    run("rec-stop", "rec-stop")
    run("mem-silence", "silence")
    run("mem-question", "mem-question")
    run("mem-stop", "mem-stop")
    settings.LEGION_DEMO_MAX_SESSION_MINUTES = "1"
    run("rec-limit", "silence")
    (OUT / "results.json").write_text(json.dumps(results, indent=1), encoding="utf-8")
    (OUT / "records.json").write_text(json.dumps(_records(), indent=1), encoding="utf-8")
    print("BROWSER RESULTS", json.dumps(results)[:4000])
