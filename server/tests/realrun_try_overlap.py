"""The /try page's layout in headless Chrome (2026-11-18, Kevin on the live demo: "the orb and the
top section scrolls with it so it overlaps on the other content below"). NOT part of the suite: run
by name, with Node + Playwright and the SPA built into `server/static/app`:

    OVERLAP_OUT=<dir> PLAYWRIGHT_DIR=<node_modules dir> OVERLAP_TAG=before|after \\
        pt.sh <worktree> <port> <db> tests/realrun_try_overlap.py -s

A demo visit is entered through the public route with a fresh code (no model, no speech, no cost),
the page is scrolled, and for each width (390 x 700, 1280 x 800) it records where the voice stage
sits and which readable elements lie under it, then the docked orb at the page's foot, with the
typed box focused and while he speaks (screenshots `overlap-<tag>-<width>-<name>.png`).
Invented content only (`tests/test_recruiter_content.fake_content`).
"""

from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

import pytest

from demo.models import DemoCode
from tests.realrun_web_cascade import real  # noqa: F401 - fixture (key, real speech)
from tests.test_recruiter_content import write_folder

pytestmark = pytest.mark.django_db(transaction=True)

HERE = Path(__file__).resolve().parent


def test_try_overlap(live_server, tmp_path, monkeypatch, real):  # noqa: F811
    from django.core.management import call_command

    # The suite pins Live (conftest); the voice-first page is the cascade's (the code default).
    monkeypatch.delenv("LEGION_WEB_VOICE", raising=False)
    monkeypatch.delenv("LEGION_WEB_VOICE_RECRUITER", raising=False)

    from household.demo_seed import seed

    out = Path(os.environ.get("OVERLAP_OUT", "overlap-out"))
    out.mkdir(parents=True, exist_ok=True)
    seed()
    call_command("load_recruiter_content", str(write_folder(tmp_path / "content")))
    results = []
    for width, height in ((390, 700), (1280, 800)):
        _row, code = DemoCode.rotate()
        args = {
            "base": live_server.url,
            "code": code,
            "width": width,
            "height": height,
            "out": str(out),
            "tag": os.environ.get("OVERLAP_TAG", "run"),
        }
        done = subprocess.run(
            ["node", str(HERE / "realrun_try_overlap.mjs"), json.dumps(args)],
            env={**os.environ, "NODE_PATH": os.environ["PLAYWRIGHT_DIR"]},
            capture_output=True,
            text=True,
            timeout=120,
        )
        (out / f"overlap-{args['tag']}-{width}.node.log").write_text(
            done.stdout + "\n" + done.stderr, encoding="utf-8"
        )
        results.append(json.loads(done.stdout.strip().splitlines()[-1]) if done.stdout else {})
    (out / f"overlap-{os.environ.get('OVERLAP_TAG', 'run')}.json").write_text(
        json.dumps(results, indent=1), encoding="utf-8"
    )
    print("OVERLAP", json.dumps(results))
