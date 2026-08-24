"""The shape every suite module conforms to. Not a base class - duck-typed on purpose so a
new suite is just a module with these four names, matching the flat style of the rest of
tools/. harness.py calls these by attribute lookup, not by inheritance.

    NAME: str                  suite id, used on the CLI (--suites clerk_crud,...) and in reports
    DESCRIPTION: str           one line, printed in --dry-run and the README's suite list
    CASES: list[Case]          the fixed case set; N runs happen per case, not per suite
    run_case(client, model, case, run_index) -> RunResult
    score(case, result) -> Verdict

A suite that needs no live call at all (there are none today, but nothing forbids it) can
still define run_case/score; harness.py never special-cases "suite has zero cost".
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Optional


@dataclass
class Case:
    """One fixed scenario a suite grades, run N times. calls_estimate is the number of
    Gemini REST calls ONE run of this case is expected to spend - used only for the
    pre-flight spend estimate printed before any network call happens."""

    key: str
    calls_estimate: int = 1
    data: dict = field(default_factory=dict)


@dataclass
class RunResult:
    """What one run of one case produced. transcript is whatever JSON-serializable trace
    the suite wants saved to disk under tools/evals/runs/<timestamp>/ - full REST
    request/response pairs, not just the final text, so a failure is diagnosable without
    re-running against the network."""

    transcript: Any
    final_text: Optional[str]
    calls_used: int
    error: Optional[str] = None


@dataclass
class Verdict:
    passed: bool
    detail: str


RunCaseFn = Callable[..., RunResult]
ScoreFn = Callable[[Case, RunResult], Verdict]
