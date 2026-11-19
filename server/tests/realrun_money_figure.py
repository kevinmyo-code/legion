"""The money-figure guard against the real text model (2026-11-18). NOT part of the suite (pytest
collects only `test_*.py`); it runs when named, with a key:

    LEGION_GEMINI_KEY_FILE=<path> REALRUN_OUT=<dir> pt.sh <worktree> <port> <db> \\
        tests/realrun_money_figure.py -s

A recruiter's visit (the invented demo household) asks the gate's spending question, typed, N
times (`REALRUN_MONEY_RUNS`, default 10; one conversation each). Per reply it records what was said,
every decimal figure in it, the amounts the tools returned, and how many sentences the guard
dropped (`money_dropped` in the turn's log line). Typed: no speech is paid for; what would be spoken
is what was said. Stops at `REALRUN_BUDGET_USD`.
"""

from __future__ import annotations

import json
import os
import re

import pytest

from assistant import cascade
from tests.realrun_web_cascade import OUT, QUESTIONS, _cost, _visitor, real  # noqa: F401
from tests.test_assistant_cascade import _events, _of

pytestmark = [pytest.mark.django_db, pytest.mark.live_model]

RUNS = int(os.environ.get("REALRUN_MONEY_RUNS", "10"))
BUDGET = float(os.environ.get("REALRUN_BUDGET_USD", "0.15"))


def _timing(caplog) -> dict:
    lines = [r.getMessage() for r in caplog.records if "cascade_turn" in r.getMessage()]
    if not lines:
        return {}
    return dict(re.findall(r"(\w+)=(-?\d+)", lines[-1]))


def test_realrun_money_figures(real, caplog, monkeypatch):  # noqa: F811
    from household.demo_seed import seed

    seed()
    # The amounts the guard held each reply's sentences against (the tools' own amounts).
    backed: list[set[int] | None] = []
    real_backed = cascade.backed_cents

    def recording(calls):
        found = real_backed(calls)
        backed.append(found)
        return found

    monkeypatch.setattr(cascade, "backed_cents", recording)
    rows, spent = [], 0.0
    for run in range(RUNS):
        if spent >= BUDGET:
            break
        client = _visitor()
        caplog.clear()
        backed.clear()
        events = _events(
            client.post("/api/assistant/turn", {"text": QUESTIONS["spend"]}, format="multipart")
        )
        timing = _timing(caplog)
        cost = _cost({k: int(v) for k, v in timing.items()})
        spent += cost
        said = " ".join(s["text"] for s in _of(events, "sentence"))
        tools = [t["name"] for t in _of(events, "tool")]
        held = next((b for b in reversed(backed) if b is not None), None)
        figures = cascade.decimal_cents(said)
        row = {
            "run": run,
            "tools": tools,
            "said": said,
            "said_figures": sorted(figures),
            "unbacked_said": sorted(figures - held) if held is not None else sorted(figures),
            "money_dropped": int(timing.get("money_dropped", 0)),
            "cost_usd": round(cost, 5),
        }
        rows.append(row)
        print("MONEY RUN", json.dumps(row)[:600])
    (OUT / "money-figure.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    print(f"MONEY SPENT ${spent:.4f} over {len(rows)} runs")
