# ruff: noqa: F811 - the `real` fixture is imported from the cascade harness
"""A real-browser check of the voice-first demo (ADR 0103). NOT part of the suite; run by name, as
`realrun_browser_cascade.py`, with a key and Node + Playwright, and the SPA built into
`server/static/app`:

    LEGION_GEMINI_KEY_FILE=<path> BROWSER_OUT=<dir> PLAYWRIGHT_DIR=<node_modules dir> \\
        pt.sh <worktree> <port> <db> tests/realrun_browser_voice_first.py -s

Scenarios in `realrun_browser_voice_first.mjs`: the reply heard back alone never barges in; talking
over the reply stops it and cancels the turn; a tap then a typed question at once is answered (the
cancel row is counted on the engine); end of turn with the models against the 1.2 s energy
threshold; screenshots at 360 px and desk width. Invented content only
(`tests/test_recruiter_content.fake_content`); the key is read from its file and never printed.
"""

from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

import pytest

from assistant.speech import GeminiSpeech, Spoken
from assistant.web_greeting import recruiter_hello
from demo.models import DemoCode
from ingest.statements import gemini_key
from tests.realrun_browser_cascade import _silence, _wav, real  # noqa: F401 - fixture
from tests.test_recruiter_content import write_folder

pytestmark = [pytest.mark.django_db(transaction=True), pytest.mark.live_model]

OUT = Path(os.environ.get("BROWSER_OUT", "browser-out"))
HERE = Path(__file__).resolve().parent
RATE = 24_000


def _scaled(pcm: bytes, factor: float) -> bytes:
    import array

    a = array.array("h", pcm)
    return array.array("h", (max(-32767, min(32767, int(v * factor))) for v in a)).tobytes()


def _tts(speech, name: str, text: str) -> bytes:
    path = OUT / f"vf-tts-{name}.pcm"
    if not path.exists():
        path.write_bytes(b"".join(speech.speak(text, "Kore", Spoken())))
    return path.read_bytes()


def _wavs() -> tuple[dict[str, Path], dict]:
    speech = GeminiSpeech(gemini_key, stt_model="gemini-3.5-flash-lite")
    opening = _tts(speech, "opening", recruiter_hello("Alfred"))
    long_q = _tts(speech, "long", "Walk me through how LEGION is built, in detail.")
    barge = _tts(speech, "barge", "Actually, when is the car due for service?")
    spend = _tts(speech, "spend", "What did this household spend last month?")
    # A long answer heard back while it is still being streamed: the reply's own kind of words,
    # at a quarter of its level, starting a few seconds into the answer.
    echo_long = _tts(
        speech,
        "echo-long",
        "So, looking at the layers on your screen, it starts with Django REST Framework on the "
        "API layer, moving down through auth and tenant scoping to the domain apps.",
    )
    lead = 9.0
    files = {
        # The opening heard back at a quarter of its level while it plays (no person speaking).
        "vf-echo": _silence(0.6) + _scaled(opening, 0.25) + _silence(20.0),
        # Words over the answer (ignored), then a question once the answer has ended (answered).
        "vf-barge": _silence(lead)
        + long_q
        + _silence(9.0)
        + barge
        + _silence(45.0)
        + spend
        + _silence(25.0),
        "vf-tap": _silence(lead) + long_q + _silence(40.0),
        "vf-echo-long": _silence(lead)
        + long_q
        + _silence(6.0)
        + _scaled(echo_long, 0.25)
        + _silence(30.0),
        "vf-eot": _silence(lead) + spend + _silence(25.0),
        "vf-eot-energy": _silence(lead) + spend + _silence(25.0),
        "vf-desk": _silence(20.0),
        # No speech at all: the chips are tapped (demo stage 2).
        "vf-chips": _silence(60.0),
    }
    out = {}
    for name, pcm in files.items():
        path = OUT / f"{name}.wav"
        path.write_bytes(_wav(pcm))
        out[name] = path
    return out, {"lead": lead, "speechMs": int(len(spend) / 2 / RATE * 1000)}


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
        ["node", str(HERE / "realrun_browser_voice_first.mjs"), json.dumps(args)],
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


def _first_audio_pairs() -> list[dict]:
    """Item 2d: per spoken turn, the engine's first sound (`first_sound_ms`, from the turn's
    start on the engine) beside the page's (`first_audio_ms`, from its post to the first sound),
    read from the conversation records this run wrote. Local: no Cloud Run or Cloudflare hop."""
    import re

    from devicelogs.models import DeviceLog
    from devicelogs.views import inflate_capped

    out = []
    for row in DeviceLog.objects.order_by("id"):
        text = inflate_capped(bytes(row.body_gz)).decode("utf-8", "replace")
        engine = [int(m) for m in re.findall(r"first_sound_ms=(-?\d+)", text)]
        page = [int(m) for m in re.findall(r"first_audio_ms=(\d+)", text.replace("tts_first", "x"))]
        out.append({"engine_ms": engine, "page_ms": page})
    return out


def test_browser_voice_first(real, live_server, tmp_path):
    from django.core.management import call_command

    from household.demo_seed import seed

    OUT.mkdir(parents=True, exist_ok=True)
    seed()
    # Seven invented sections, so his profile's follow-up chips have more than one to offer.
    from tests.test_profile_verbatim import _content

    folder = write_folder(tmp_path / "content", content=_content())
    call_command("load_recruiter_content", str(folder))
    wavs, timing = _wavs()
    only = {s for s in os.environ.get("BROWSER_SCENARIOS", "").split(",") if s}
    results = []
    from assistant.models import TurnCancel

    for scenario in (
        "vf-echo",
        "vf-echo-long",
        "vf-barge",
        "vf-tap",
        "vf-eot",
        "vf-eot-energy",
        "vf-desk",
        "vf-chips",
    ):
        if only and scenario not in only:
            continue
        _row, code = DemoCode.rotate()
        before = TurnCancel.objects.count()
        result = _run(live_server, scenario, wavs[scenario], {"code": code, **timing})
        # The cancel must reach the engine, not only leave the page.
        result["serverCancelRows"] = TurnCancel.objects.count() - before
        if scenario == "vf-chips":
            result["firstAudio"] = _first_audio_pairs()
        results.append(result)
    (OUT / "vf-results.json").write_text(json.dumps(results, indent=1), encoding="utf-8")
    print("VF RESULTS", json.dumps([r.get("checks") for r in results])[:4000])
