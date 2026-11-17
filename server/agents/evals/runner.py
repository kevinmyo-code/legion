"""The eval runner (plan section 16, ADR 0100): suites of invented cases, run through the SAME turn
code the endpoint runs (`agents.legion.turn.run_turn`), several runs per case, graded by code,
with pass-rate bars.

- **Live**: a real `GeminiClient` wrapped in `RecordingModel`; every round's model events are kept
  and written as the suite's cassette (`cassettes/<suite>.json`): model output only, never a URL,
  a header or the key.
- **Replay**: `CassetteModel` serves the recorded rounds; the clock is frozen at the recording's
  `now`, so the fixture, `{today}` and every engine tool see the day it was recorded. A code change
  that makes the graph ask for a round the recording does not have fails as "not in the
  recording". Runs in seconds, no network.
- **Each case-run is rolled back**: the fixture is seeded inside a savepoint and undone after, so
  a write case never leaks into the next.
- **Local databases only** (`fixture.assert_local_database`).
- **A hard spend stop** (`max_cost_micro_usd`, default $2.50 ESTIMATE): a live run stops before
  the next case once the estimated spend passes it, and says so.
- Case-runs are sequential: engine tools share one database connection (the plan's "parallel" is
  changed to this).

A suite passes when its pass rate (case-runs passed / case-runs) is at or above its `bar` and no
`critical` case failed even once (plan section 16.3).
"""

from __future__ import annotations

import contextlib
import json
import math
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from unittest import mock
from zoneinfo import ZoneInfo

import yaml
from django.db import transaction

from agents.evals import fixture
from agents.evals.graders import grade_turn
from agents.legion.turn import open_conversation, run_turn
from agents.runtime.model import CassetteModel, ModelClient, RecordingModel

EVALS = Path(__file__).resolve().parent
CASES = EVALS / "cases"
PRIVATE = EVALS / "private"
CASSETTES = EVALS / "cassettes"
DEFAULT_MAX_COST_MICRO_USD = 2_500_000


class SpendStop(RuntimeError):
    pass


def load_suite(name: str) -> dict:
    """`private` reads every git-ignored `private/*.yaml`; anything else is `cases/<name>.yaml`."""
    if name == "private":
        cases = []
        for path in sorted(PRIVATE.glob("*.yaml")):
            cases.extend((yaml.safe_load(path.read_text(encoding="utf-8")) or {}).get("cases", []))
        return {"suite": "private", "bar": 1.0, "runs": 1, "cases": cases}
    return yaml.safe_load((CASES / f"{name}.yaml").read_text(encoding="utf-8"))


@dataclass
class CaseResult:
    id: str
    critical: bool
    runs: list[dict] = field(default_factory=list)

    @property
    def passed(self) -> int:
        return sum(1 for r in self.runs if not r["reasons"])


@dataclass
class SuiteResult:
    suite: str
    bar: float
    cases: list[CaseResult]
    cost_micro_usd: int = 0
    prompt_tokens: int = 0
    stopped: str = ""

    @property
    def total(self) -> int:
        return sum(len(c.runs) for c in self.cases)

    @property
    def passed(self) -> int:
        return sum(c.passed for c in self.cases)

    @property
    def rate(self) -> float:
        return self.passed / self.total if self.total else 0.0

    @property
    def critical_failed(self) -> list[str]:
        return [c.id for c in self.cases if c.critical and c.passed < len(c.runs)]

    @property
    def ok(self) -> bool:
        return not self.stopped and self.rate >= self.bar and not self.critical_failed

    def interval(self) -> tuple[float, float] | None:
        """A 90 % Wilson interval on the rate, once there are at least 10 case-runs."""
        n = self.total
        if n < 10:
            return None
        z, p = 1.645, self.rate
        centre = (p + z * z / (2 * n)) / (1 + z * z / n)
        half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
        return max(0.0, centre - half), min(1.0, centre + half)

    def lines(self) -> list[str]:
        out = []
        for c in self.cases:
            mark = "PASS" if c.passed == len(c.runs) else "FAIL"
            crit = " critical" if c.critical else ""
            out.append(f"{mark} {c.id}{crit}: {c.passed}/{len(c.runs)}")
            for i, r in enumerate(c.runs):
                for reason in r["reasons"]:
                    out.append(f"    run {i + 1}: {reason}")
        ci = self.interval()
        ci_words = f", 90% interval {ci[0]:.2f}-{ci[1]:.2f}" if ci else ""
        out.append(
            f"suite {self.suite}: {self.passed}/{self.total} = {self.rate:.2f} (bar {self.bar})"
            f"{ci_words}; critical failed: {self.critical_failed or 'none'}; "
            f"est. cost ${self.cost_micro_usd / 1e6:.4f} ESTIMATE, "
            f"{self.prompt_tokens} input tokens"
            + (f"; STOPPED: {self.stopped}" if self.stopped else "")
            + f" -> {'PASS' if self.ok else 'FAIL'}"
        )
        return out


@contextlib.contextmanager
def frozen_clock(now: datetime):
    """The engine's two clock seams pinned to `now` (replay)."""
    from household import timezones

    real_local_date = timezones.local_date

    def local_date(zone, at=None):
        return real_local_date(zone, at or now)

    with (
        mock.patch("engine_mcp.tools._now", lambda: now),
        mock.patch.object(timezones, "local_date", local_date),
    ):
        yield


def member_request(user):
    """A DRF request authenticated as `user` (a session-style member: no device token), the shape
    `engine_mcp.dispatch.call_route` hands an inner view."""
    from django.test.client import RequestFactory
    from rest_framework.request import Request

    raw = RequestFactory().post("/api/agents/turn", data={}, content_type="application/json")
    raw._force_auth_user = user
    raw._force_auth_token = None
    return Request(raw)


def _run_once(case: dict, user, household, model: ModelClient, now: datetime) -> dict:
    zone = ZoneInfo(fixture.ZONE)
    today = now.astimezone(zone).date()
    turns_out = []
    reasons: list[str] = []
    cost = 0
    tokens = 0
    with transaction.atomic():
        fixture.seed(household, user, today, zone)
        request = member_request(user)
        conversation = open_conversation(request, None, "eval")
        for n, turn in enumerate(case["turns"]):
            calls: list[dict] = []
            gen = run_turn(
                request,
                turn["say"],
                model,
                conversation=conversation,
                now=now,
                do_prune=False,
                observe=calls.append,
            )
            try:
                while True:
                    next(gen)
            except StopIteration as stop:
                state = stop.value
            record = {"say": turn["say"], "final_text": state.final_text, "calls": calls}
            got = grade_turn(record, turn.get("expect") or {}, today)
            reasons.extend(f"turn {n + 1}: {r}" for r in got)
            cost += state.budget.cost_micro_usd
            tokens += state.budget.prompt_tokens
            turns_out.append(
                {
                    "say": turn["say"],
                    "reply": state.final_text or state.words,
                    "calls": [{"name": c["name"], "args": c["args"], "ok": c["ok"]} for c in calls],
                }
            )
        transaction.set_rollback(True)
    return {"reasons": reasons, "turns": turns_out, "cost": cost, "tokens": tokens}


def run_suite(
    suite: dict,
    user,
    household,
    *,
    live_model: ModelClient | None = None,
    cassette: dict | None = None,
    runs: int | None = None,
    only: set[str] | None = None,
    max_cost_micro_usd: int = DEFAULT_MAX_COST_MICRO_USD,
) -> tuple[SuiteResult, dict | None]:
    """Live when `live_model` is given (returns the new cassette too); else replays `cassette`."""
    fixture.assert_local_database()
    if (live_model is None) == (cassette is None):
        raise ValueError("pass exactly one of live_model or cassette")
    result = SuiteResult(suite["suite"], float(suite.get("bar", 1.0)), [])
    if live_model is not None:
        now = datetime.now(UTC)
        recorded: dict = {
            "suite": suite["suite"],
            "recorded_at": now.isoformat(),
            "zone": fixture.ZONE,
            "runs": {},
        }
        clock = contextlib.nullcontext()
    else:
        now = datetime.fromisoformat(cassette["recorded_at"])
        recorded = None
        clock = frozen_clock(now)
    n_runs = runs or int(suite.get("runs", 1))
    with clock:
        for case in suite["cases"]:
            if only and case["id"] not in only:
                continue
            out = CaseResult(case["id"], bool(case.get("critical")))
            result.cases.append(out)
            if live_model is not None:
                kept = recorded["runs"].setdefault(case["id"], [])
                for _ in range(n_runs):
                    if result.cost_micro_usd >= max_cost_micro_usd:
                        result.stopped = (
                            f"spend stop at ${result.cost_micro_usd / 1e6:.2f} (ESTIMATE) before "
                            f"{case['id']}"
                        )
                        return result, recorded
                    model = RecordingModel(live_model)
                    run = _run_once(case, user, household, model, now)
                    kept.append(model.rounds)
                    out.runs.append(run)
                    result.cost_micro_usd += run["cost"]
                    result.prompt_tokens += run["tokens"]
            else:
                for rounds in (cassette["runs"].get(case["id"]) or [])[: runs or None]:
                    run = _run_once(case, user, household, CassetteModel(rounds), now)
                    out.runs.append(run)
                if not out.runs:
                    out.runs.append({"reasons": ["not in the cassette"], "turns": []})
    return result, recorded


def write_cassette(recorded: dict, path: Path | None = None) -> Path:
    path = path or CASSETTES / f"{recorded['suite']}.json"
    path.write_text(json.dumps(recorded, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return path


def read_cassette(suite: str) -> dict:
    return json.loads((CASSETTES / f"{suite}.json").read_text(encoding="utf-8"))
