# ruff: noqa: F811 - the `real` fixture is imported from the cascade harness
"""The /try tour, a diagram opening and the chips in headless Chrome (Kevin, 2026-11-19: "the 1
minute tour and diagrams opening still have bugs, the diagrams are flickering hard"). NOT part of
the suite: run by name, with a key, Node + Playwright, and the SPA built into `server/static/app`:

    LEGION_GEMINI_KEY_FILE=<path> ORBFLOW_OUT=<dir> PLAYWRIGHT_DIR=<node_modules dir> \\
        ORBFLOW_TAG=before|after pt.sh <worktree> <port> <db> tests/realrun_try_orb_flow.py -s

For each width (390 x 700, 1280 x 800) a fresh visit runs the whole one-minute tour,
closes its card, asks for a diagram by a typed question with the orb docked, closes it, then looks
at the chips under the orb. `realrun_try_orb_flow.mjs` records the dock and the diagram's
mounts and unmounts, the page's state per animation frame, layout shifts and the compositor's frames
(`orbflow-<tag>-<width>-*.json|png`, `frames/`). The spend is the demo budget's reserved cents (an
upper bound on what the run cost). Invented content only (`tests.test_profile_verbatim._content`);
the key is read from its file by the `real` fixture and never printed.
"""

from __future__ import annotations

import json
import os
import struct
import subprocess
from pathlib import Path

import pytest

from demo.models import DemoCode
from tests.realrun_web_cascade import real  # noqa: F401 - fixture (key, real speech)
from tests.test_recruiter_content import write_folder

pytestmark = [pytest.mark.django_db(transaction=True), pytest.mark.live_model]

HERE = Path(__file__).resolve().parent
QUESTION = "Show me the diagram of how LEGION is built."


def _silent_wav(path: Path, seconds: float = 200.0, rate: int = 16_000) -> Path:
    """Chrome's fake microphone hears this: silence, so nothing it hears is ever a turn."""
    data = b"\x00\x00" * int(seconds * rate)
    header = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt "
    header += struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) + b"data"
    path.write_bytes(header + struct.pack("<I", len(data)) + data)
    return path


def test_try_orb_flow(real, live_server, tmp_path):
    from django.core.management import call_command
    from django.db.models import Sum

    from demo.models import DemoBudgetDay
    from household.demo_seed import seed
    from tests.test_profile_verbatim import _content

    out = Path(os.environ.get("ORBFLOW_OUT", "orbflow-out"))
    out.mkdir(parents=True, exist_ok=True)
    tag = os.environ.get("ORBFLOW_TAG", "run")
    seed()
    call_command(
        "load_recruiter_content", str(write_folder(tmp_path / "content", content=_content()))
    )
    wav = _silent_wav(tmp_path / "silence.wav")
    sizes = [(390, 700), (1280, 800)]
    only = os.environ.get("ORBFLOW_WIDTHS")
    if only:
        sizes = [s for s in sizes if str(s[0]) in only.split(",")]
    results = []
    for width, height in sizes:
        _row, code = DemoCode.rotate()
        args = {
            "base": live_server.url,
            "code": code,
            "width": width,
            "height": height,
            "out": str(out),
            "tag": tag,
            "wav": str(wav),
            "question": QUESTION,
        }
        done = subprocess.run(
            ["node", str(HERE / "realrun_try_orb_flow.mjs"), json.dumps(args)],
            env={**os.environ, "NODE_PATH": os.environ["PLAYWRIGHT_DIR"]},
            capture_output=True,
            text=True,
            timeout=420,
        )
        (out / f"orbflow-{tag}-{width}.node.log").write_text(
            done.stdout + "\n" + done.stderr, encoding="utf-8"
        )
        lines = done.stdout.strip().splitlines()
        results.append(
            json.loads(lines[-1]) if lines else {"width": width, "error": done.stderr[-1500:]}
        )
    reserved = DemoBudgetDay.objects.aggregate(c=Sum("reserved_cents"))["c"] or 0
    summary = {"tag": tag, "reservedCents": reserved, "results": results}
    (out / f"orbflow-{tag}.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    print("ORBFLOW", json.dumps(summary)[:6000])
    failed = {
        r.get("width"): [k for k, ok in (r.get("checks") or {"ran": False}).items() if not ok]
        for r in results
    }
    assert not any(failed.values()), f"browser checks failed: {failed}"
