"""Worker workflows, engine half (map worker-workflows, tickets 03 and 04; ADR 0067 amended
2026-11-10).

A workflow is a named job one of Kevin's laptops runs: a private worker skill plus a
`workflow.json` contract, both on the laptop (ruling R1). The engine never authors one. The
worker publishes its catalog (names, one-liners, aliases, param names, ask lines, outputs, scope,
schedule; no paths, no skill text) on the heartbeat when its hash changes, and the engine keeps it
per machine (`ClaudeMachine.workflows`).

- `clean_catalog` is the intake: the worker's own lint, again, because the engine is the side that
  must never hold a path or an address. A failing entry is dropped and counted, never stored.
- `match` is the port of `tools/claude_session/workflows.py`'s matcher. Both run the one phrase
  table, `tools/claude_session/test_fixtures/workflows/match_cases.json`.
- `due` / `maybe_mint_workflow` are the catch-up rule (ticket 04): computed when read, minted
  inside a claim, at most one per claim.

**Nothing here logs.** Names and aliases are household data like a request.
"""

from __future__ import annotations

import datetime
import json
import re
from dataclasses import dataclass

from django.conf import settings

# The worker's limits (tools/claude_session/workflows.py), held again at intake.
CATALOG_MAX = 50
ENTRY_MAX_BYTES = 2 * 1024
HASH_MAX = 64
NAME_RE = re.compile(r"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
PARAM_RE = re.compile(r"^[a-z][a-z0-9_]*$")
AT_RE = re.compile(r"^[a-z][a-z0-9-]*$")
NAME_MAX = 40
SAYS_MAX = 120
ALIAS_MAX = 60
LIST_MAX = 8
OUTPUTS = ("summary", "report", "page")
SCOPES = ("full", "listed")
# Run history is the task rows, kept 30 days (ADR 0067): a longer schedule could never tell.
EVERY_MAX_HOURS = 30 * 24
PRIVATE_RES = (
    re.compile(r"[A-Za-z]:[\\/]"),
    re.compile(r"\\"),
    re.compile(r"@"),
    re.compile(r"%[A-Za-z_]+%|\$env:|~[\\/]", re.I),
    re.compile(r"[$€£¥]\s*\d|\d[\d,.]*\s*(?:usd|sgd|eur|gbp|dollars?|bucks)\b", re.I),
)


class Bad(Exception):
    pass


def _text(value, limit: int) -> str:
    if not isinstance(value, str) or not value.strip():
        raise Bad("text")
    value = " ".join(value.split())
    if len(value) > limit or any(p.search(value) for p in PRIVATE_RES):
        raise Bad("text")
    return value


def _int(value, lo: int, hi: int) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or not lo <= value <= hi:
        raise Bad("number")
    return value


def clean_entry(raw) -> dict:
    """One catalog entry in the engine's shape, or Bad. Strict: every key, nothing extra."""
    keys = {"name", "says", "aliases", "params", "asks", "outputs", "deliver", "scope", "every"}
    if not isinstance(raw, dict) or set(raw) != keys:
        raise Bad("shape")
    if len(json.dumps(raw, separators=(",", ":")).encode("utf-8")) > ENTRY_MAX_BYTES:
        raise Bad("size")
    name = raw["name"]
    if not isinstance(name, str) or not NAME_RE.match(name) or len(name) > NAME_MAX:
        raise Bad("name")
    aliases = raw["aliases"]
    if not isinstance(aliases, list) or len(aliases) > LIST_MAX:
        raise Bad("aliases")
    aliases = [normalize(_text(a, ALIAS_MAX)) for a in aliases]
    params = raw["params"]
    if not isinstance(params, list) or len(params) > LIST_MAX:
        raise Bad("params")
    clean_params = []
    for p in params:
        if not isinstance(p, dict) or set(p) - {"name", "says", "required"}:
            raise Bad("param")
        if not isinstance(p.get("name"), str) or not PARAM_RE.match(p["name"]) or len(p["name"]) > 30:
            raise Bad("param")
        clean_params.append({"name": p["name"], "says": _text(p.get("says"), SAYS_MAX),
                             "required": p.get("required") is True})
    asks = raw["asks"]
    if not isinstance(asks, list) or len(asks) > LIST_MAX:
        raise Bad("asks")
    clean_asks = []
    for a in asks:
        if not isinstance(a, dict) or set(a) != {"at", "says"}:
            raise Bad("ask")
        if not isinstance(a["at"], str) or not AT_RE.match(a["at"]) or len(a["at"]) > 30:
            raise Bad("ask")
        clean_asks.append({"at": a["at"], "says": _text(a["says"], SAYS_MAX)})
    outputs = raw["outputs"]
    if not isinstance(outputs, list) or any(o not in OUTPUTS for o in outputs):
        raise Bad("outputs")
    if raw["deliver"] not in (None, "email_self"):
        raise Bad("deliver")
    scope = raw["scope"]
    if not isinstance(scope, list) or not scope or any(s not in SCOPES for s in scope):
        raise Bad("scope")
    if "listed" in scope and "page" in outputs:
        raise Bad("scope")
    every = raw["every"]
    if every is not None:
        if not isinstance(every, dict) or set(every) != {
            "hours", "idle_minutes", "force_after_hours", "retry_after_hours"
        }:
            raise Bad("every")
        hours = _int(every["hours"], 1, EVERY_MAX_HOURS)
        every = {
            "hours": hours,
            "idle_minutes": _int(every["idle_minutes"], 0, 24 * 60),
            "force_after_hours": _int(every["force_after_hours"], hours, 2 * EVERY_MAX_HOURS),
            "retry_after_hours": _int(every["retry_after_hours"], 0, EVERY_MAX_HOURS),
        }
    return {
        "name": name,
        "says": _text(raw["says"], SAYS_MAX),
        "aliases": [a for a in aliases if a],
        "params": clean_params,
        "asks": clean_asks,
        "outputs": [o for o in OUTPUTS if o in outputs or o == "summary"],
        "deliver": raw["deliver"],
        "scope": [s for s in SCOPES if s in scope],
        "every": every,
    }


def clean_catalog(raw, previous: list | None, now) -> tuple[list[dict], int]:
    """The entries worth keeping and how many were dropped. A name or phrase already taken by an
    earlier entry is dropped too. Each kept entry carries `seen_at`, the first time this machine
    published that name (carried over from `previous`), the clock a never-run schedule counts from."""
    seen_before = {
        e.get("name"): e.get("seen_at") for e in previous or [] if isinstance(e, dict)
    }
    kept: list[dict] = []
    dropped = 0
    names: set[str] = set()
    phrases: set[str] = set()
    for item in raw[:CATALOG_MAX] if isinstance(raw, list) else []:
        try:
            entry = clean_entry(item)
        except Bad:
            dropped += 1
            continue
        mine = set(_phrases(entry))
        if entry["name"] in names or mine & phrases:
            dropped += 1
            continue
        names.add(entry["name"])
        phrases |= mine
        entry["seen_at"] = seen_before.get(entry["name"]) or now.isoformat()
        kept.append(entry)
    if isinstance(raw, list) and len(raw) > CATALOG_MAX:
        dropped += len(raw) - CATALOG_MAX
    return kept, dropped


# ---------------------------------------------------------------- matching (the worker's, ported)

_NON_WORD = re.compile(r"[^a-z0-9]+")
LEAD_PHRASES = ("please", "hey claude", "have claude", "ask claude to", "get claude to",
                "tell claude to", "use claude to", "can claude", "claude please", "claude")


def normalize(text: str) -> str:
    return " ".join(
        _NON_WORD.sub(" ", (text or "").lower().replace("'", "").replace("’", "")).split()
    )


def _phrases(entry: dict) -> list[str]:
    out = [normalize(entry["name"].replace("-", " "))]
    out += [normalize(a) for a in entry.get("aliases") or []]
    return [p for i, p in enumerate(out) if p and p not in out[:i]]


def strip_phrase(phrase: str, machines=()) -> list[str]:
    words = normalize(phrase)
    for m in sorted((normalize(m) for m in machines if m), key=len, reverse=True):
        if m:
            words = re.sub(rf"(?:^|\s)on (?:my |the )?{re.escape(m)}(?=\s|$)", " ", words)
    words = " ".join(words.split())
    changed = True
    while changed and words:
        changed = False
        for lead in LEAD_PHRASES:
            if words == lead or words.startswith(lead + " "):
                words = words[len(lead):].strip()
                changed = True
                break
    return words.split()


def match(phrase: str, catalog, machines=()) -> tuple[dict, str] | None:
    """The workflow Kevin's phrase names and the words after the matched alias (the params text).
    Whole words; the longest phrase wins (more words, more letters, earliest, then the name)."""
    tokens = strip_phrase(phrase, machines)
    if not tokens:
        return None
    best = None
    for entry in catalog or []:
        for p in _phrases(entry):
            pt = p.split()
            n = len(pt)
            for i in range(len(tokens) - n + 1):
                if tokens[i:i + n] == pt:
                    key = (-n, -len(p), i, entry["name"])
                    if best is None or key < best[0]:
                        best = (key, entry, " ".join(tokens[i + n:]))
                    break
    if best is None:
        return None
    return best[1], best[2]


def spoken(name: str) -> str:
    """"log-audit" as Alfred says it: "log audit"."""
    return name.replace("-", " ")


def entry_named(machine, name: str) -> dict | None:
    for e in machine.workflows or []:
        if isinstance(e, dict) and e.get("name") == name:
            return e
    return None


# ---------------------------------------------------------------- the catch-up rule (ticket 04)

SCHEDULES_OFF = "off"


def schedules_on() -> bool:
    value = str(getattr(settings, "LEGION_WORKFLOW_SCHEDULES", "on") or "on").strip().lower()
    return value != SCHEDULES_OFF


def schedules_off_for() -> set[str]:
    raw = str(getattr(settings, "LEGION_WORKFLOW_SCHEDULES_OFF", "") or "")
    return {n.strip().lower() for n in raw.split(",") if n.strip()}


# An extra due predicate per workflow name, registered by whatever owns that workflow:
# `EXTRA[name](machine, now) -> bool`. None registered today; the memory review keeps its own kind
# and minting (`member_memory.review.maybe_mint`) until ticket 12 folds it in.
EXTRA: dict = {}


@dataclass(frozen=True)
class Due:
    due: bool
    why: str = ""
    # How long past due, for "longest-overdue first". Never-run counts from first published.
    overdue_s: float = 0.0
    forced: bool = False


def _parse_time(value):
    if isinstance(value, datetime.datetime):
        return value
    try:
        return datetime.datetime.fromisoformat(str(value))
    except (TypeError, ValueError):
        return None


def runs(machine, name: str):
    from claude_session.models import KIND_WORKFLOW, ClaudeTask

    return ClaudeTask.objects.filter(
        household_id=machine.household_id, machine=machine, kind=KIND_WORKFLOW, workflow=name
    )


def waiting_on_kevin(machine, name: str, now) -> bool:
    """A run of `name` is queued, running or asking Kevin a question nobody answered yet. A question
    that has a follow-up is answered: the follow-up is the run now."""
    from claude_session.models import (
        CANCEL_REQUESTED,
        NEEDS_CONFIRMATION,
        NEEDS_MACHINE,
        QUEUED,
        RUNNING,
        ClaudeTask,
    )
    from claude_session.state import effective_status

    live = (QUEUED, NEEDS_MACHINE, RUNNING, CANCEL_REQUESTED)
    for task in runs(machine, name).filter(status__in=live + (NEEDS_CONFIRMATION,)):
        status = effective_status(task, now)
        if status in live:
            return True
        if status == NEEDS_CONFIRMATION and not ClaudeTask.objects.filter(parent=task).exists():
            return True
    return False


def last_run(machine, name: str):
    return runs(machine, name).order_by("-created_at", "-id").first()


def due(machine, entry: dict, now) -> Due:
    """Is workflow `entry` on `machine` due? Computed when read, never written by a job."""
    from claude_session.models import CANCELLED, DONE, FAILED
    from claude_session.state import effective_status

    every = entry.get("every")
    if not every:
        return Due(False, "no schedule")
    if not schedules_on():
        return Due(False, "schedules off")
    if entry["name"] in schedules_off_for():
        return Due(False, "this schedule is off")
    if machine.scope not in (entry.get("scope") or ()):
        return Due(False, "not for this machine")
    if waiting_on_kevin(machine, entry["name"], now):
        return Due(False, "one is queued, running or waiting on Kevin")
    history = list(runs(machine, entry["name"]).order_by("-created_at", "-id")[:50])
    last_done = None
    last_bad = None
    for task in history:
        status = effective_status(task, now)
        when = task.finished_at or task.created_at
        if status == DONE and last_done is None:
            last_done = when
        if status in (FAILED, CANCELLED) and last_bad is None:
            last_bad = when
    hours = datetime.timedelta(hours=every["hours"])
    if last_done is not None and now - last_done < hours:
        return Due(False, "ran recently")
    retry = datetime.timedelta(hours=every["retry_after_hours"])
    if last_bad is not None and now - last_bad < retry:
        return Due(False, "failed recently")
    extra = EXTRA.get(entry["name"])
    if extra is not None and not extra(machine, now):
        return Due(False, "its own rule says not yet")
    since = last_done or _parse_time(entry.get("seen_at")) or now
    elapsed = (now - since).total_seconds()
    forced = elapsed >= every["force_after_hours"] * 3600
    return Due(True, overdue_s=elapsed, forced=forced)


def _machine_busy(machine, now) -> bool:
    from claude_session.models import IN_FLIGHT, ClaudeTask
    from claude_session.state import failure_reason

    return any(
        not failure_reason(task, now)
        for task in ClaudeTask.objects.filter(machine=machine, status__in=IN_FLIGHT)
    )


def scheduled_deliver(entry: dict) -> str | None:
    """R3: the one standing yes a scheduled run may carry is mail to Kevin. A page or files to
    the phone are asked for per run, so an unattended run never takes one."""
    return "email_self" if entry.get("deliver") == "email_self" else None


def maybe_mint_workflow(request, machine, now):
    """Inside a claim's transaction, the machine row locked: mint at most one scheduled workflow
    task for this machine, the longest overdue first. Returns the task or None.

    The gates are the memory review's: the claiming token is the one bound to this machine, never
    a recruiter or a demo visit, the worker sends an idle reading, and nothing is running. Then the
    workflow's own idle minutes, or its force-after hours."""
    from claude_session.models import (
        KIND_WORKFLOW,
        ORIGIN_SCHEDULE,
        QUEUED,
        ClaudeTask,
    )
    from household.models import DeviceToken, is_recruiter

    if not schedules_on() or not machine.workflows:
        return None
    token = getattr(request, "auth", None)
    member = getattr(request, "user", None)
    if not isinstance(token, DeviceToken) or machine.token_id != token.pk:
        return None
    if is_recruiter(member) or getattr(machine.household, "is_demo_visit", False):
        return None
    if machine.input_idle_s is None or _machine_busy(machine, now):
        return None
    # Something Kevin sent is about to run here: it goes first; the schedule catches up later.
    if ClaudeTask.objects.filter(machine=machine, status=QUEUED).exists():
        return None
    candidates = []
    for entry in machine.workflows:
        if not isinstance(entry, dict) or not entry.get("every"):
            continue
        verdict = due(machine, entry, now)
        if not verdict.due:
            continue
        idle = machine.input_idle_s >= entry["every"]["idle_minutes"] * 60
        if idle or verdict.forced:
            candidates.append((-verdict.overdue_s, entry["name"], entry))
    if not candidates:
        return None
    candidates.sort(key=lambda c: (c[0], c[1]))
    entry = candidates[0][2]
    return ClaudeTask.objects.create(
        household=machine.household,
        user=member,
        machine=machine,
        origin=ORIGIN_SCHEDULE,
        kind=KIND_WORKFLOW,
        workflow=entry["name"],
        params=None,
        deliver=scheduled_deliver(entry),
        request=f"Scheduled run of {spoken(entry['name'])}.",
        status=QUEUED,
    )
