"""Worker workflows: the `workflow.json` contract beside a worker skill (map worker-workflows,
ticket 02, ADR 0067 amended 2026-11-10).

A workflow is one of Kevin's private worker skills (`work\\.claude\\skills\\<name>\\SKILL.md`, the
how) that also holds `workflow.json` (the contract: name, aliases, one line, params, asks, outputs,
delivery, scope, schedule). A skill folder with no `workflow.json` is a skill, not a workflow, and
nothing here touches it. The skill bodies never leave the laptop; `catalog_for_engine` is the only
shape that does, and it holds no path, no body and nothing lint calls private.

Stdlib only, like the rest of the worker.

    python workflows.py --lint <skills dir>     # every sidecar, its problems in words; exit 1 if any
    python workflows.py --catalog <skills dir>  # what the engine would receive, and its hash

The engine ports `match` (`server/claude_session/workflows.py`); both run the one phrase table in
`test_fixtures/workflows/match_cases.json`.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

FORMAT = 1
SIDECAR = "workflow.json"
SKILL = "SKILL.md"

NAME_RE = re.compile(r"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
PARAM_RE = re.compile(r"^[a-z][a-z0-9_]*$")
AT_RE = re.compile(r"^[a-z][a-z0-9-]*$")
NAME_MAX = 40
SAYS_MAX = 120
ALIAS_MAX = 60
ALIASES_MAX = 8
PARAMS_MAX = 8
ASKS_MAX = 8
AT_MAX = 30
ENTRY_MAX_BYTES = 2 * 1024
CATALOG_MAX = 50
OUTPUTS = ("summary", "report", "page")
DELIVERS = (None, "email_self")
SCOPES = ("full", "listed")
# The run history the engine keeps is 30 days (ADR 0067), so a longer schedule could never tell
# whether it ran.
EVERY_MAX_HOURS = 30 * 24
EVERY_DEFAULTS = {"idle_minutes": 10, "retry_after_hours": 6}
KEYS = ("format", "name", "says", "aliases", "params", "asks", "outputs", "deliver", "scope", "every")
EVERY_KEYS = ("hours", "idle_minutes", "force_after_hours", "retry_after_hours")

# What may never reach the engine: a path, an address, a figure (map rule: "Nothing in
# workflow.json may hold a path, an address, a figure or a person's name"). Names cannot be told
# from words by code; that half is Kevin's, and the lint says so in --lint's footer.
PRIVATE_RES = (
    (re.compile(r"[A-Za-z]:[\\/]"), "a drive path"),
    (re.compile(r"\\"), "a backslash"),
    (re.compile(r"@"), "an @ (an address)"),
    (re.compile(r"%[A-Za-z_]+%|\$env:|~[\\/]", re.I), "a path variable"),
    (re.compile(r"[$€£¥]\s*\d|\d[\d,.]*\s*(?:usd|sgd|eur|gbp|dollars?|bucks)\b",
                re.I), "an amount of money"),
)
# A skill that names these steps must declare where it stops for Kevin (the ask is descriptive;
# the hook, ship.py and send_mail.py enforce). send_mail.py is fine without an ask when the
# workflow's one standing yes is mail to Kevin (R3).
SHIP_RE = re.compile(r"\bship\.py\b", re.I)
MAIL_RE = re.compile(r"\bsend_mail\.py\b", re.I)


class Problem(Exception):
    pass


def _private(text: str) -> str | None:
    for pat, what in PRIVATE_RES:
        if pat.search(text or ""):
            return what
    return None


def _text(value, where: str, limit: int, *, required: bool = True) -> str:
    if value is None and not required:
        return ""
    if not isinstance(value, str) or not value.strip():
        raise Problem(f"{where} must be a non-empty string")
    value = " ".join(value.split())
    if len(value) > limit:
        raise Problem(f"{where} is over {limit} characters")
    what = _private(value)
    if what:
        raise Problem(f"{where} holds {what}; workflow.json goes to the engine, keep that in SKILL.md")
    return value


def _int(value, where: str, lo: int, hi: int) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or not lo <= value <= hi:
        raise Problem(f"{where} must be a whole number from {lo} to {hi}")
    return value


def _every(raw) -> dict | None:
    if raw is None:
        return None
    if not isinstance(raw, dict):
        raise Problem("every must be null or an object")
    extra = sorted(set(raw) - set(EVERY_KEYS))
    if extra:
        raise Problem(f"every has unknown keys: {', '.join(extra)}")
    hours = _int(raw.get("hours"), "every.hours", 1, EVERY_MAX_HOURS)
    out = {"hours": hours}
    out["idle_minutes"] = _int(raw.get("idle_minutes", EVERY_DEFAULTS["idle_minutes"]),
                               "every.idle_minutes", 0, 24 * 60)
    out["force_after_hours"] = _int(raw.get("force_after_hours", hours + hours // 2),
                                    "every.force_after_hours", hours, 2 * EVERY_MAX_HOURS)
    out["retry_after_hours"] = _int(raw.get("retry_after_hours", EVERY_DEFAULTS["retry_after_hours"]),
                                    "every.retry_after_hours", 0, EVERY_MAX_HOURS)
    return out


def parse(raw, folder: str, skill_text: str = "") -> dict:
    """One sidecar, validated and normalised to the engine's shape. Raises Problem in words."""
    if not isinstance(raw, dict):
        raise Problem("workflow.json must be one JSON object")
    extra = sorted(set(raw) - set(KEYS))
    if extra:
        raise Problem(f"unknown keys: {', '.join(extra)}")
    if raw.get("format") != FORMAT:
        raise Problem(f"format must be {FORMAT}")
    name = raw.get("name")
    if not isinstance(name, str) or not NAME_RE.match(name) or len(name) > NAME_MAX:
        raise Problem(f"name must be kebab-case, at most {NAME_MAX} characters")
    if name != folder:
        raise Problem("name must equal the skill folder's name")
    says = _text(raw.get("says"), "says", SAYS_MAX)

    aliases_raw = raw.get("aliases") or []
    if not isinstance(aliases_raw, list) or len(aliases_raw) > ALIASES_MAX:
        raise Problem(f"aliases must be a list of at most {ALIASES_MAX}")
    aliases: list[str] = []
    for i, a in enumerate(aliases_raw):
        a = normalize(_text(a, f"aliases[{i}]", ALIAS_MAX))
        if not a:
            raise Problem(f"aliases[{i}] has no words")
        if a not in aliases:
            aliases.append(a)

    params_raw = raw.get("params") or []
    if not isinstance(params_raw, list) or len(params_raw) > PARAMS_MAX:
        raise Problem(f"params must be a list of at most {PARAMS_MAX}")
    params, seen = [], set()
    for i, p in enumerate(params_raw):
        if not isinstance(p, dict) or set(p) - {"name", "says", "default", "required"}:
            raise Problem(f"params[{i}] must be an object with name, says, default, required")
        pname = p.get("name")
        if not isinstance(pname, str) or not PARAM_RE.match(pname) or len(pname) > 30 or pname in seen:
            raise Problem(f"params[{i}].name must be a unique snake_case word")
        seen.add(pname)
        param = {"name": pname, "says": _text(p.get("says"), f"params[{i}].says", SAYS_MAX),
                 "required": bool(p.get("required", False))}
        if not isinstance(p.get("required", False), bool):
            raise Problem(f"params[{i}].required must be true or false")
        if p.get("default") is not None:
            param["default"] = _text(p.get("default"), f"params[{i}].default", SAYS_MAX)
        params.append(param)

    asks_raw = raw.get("asks") or []
    if not isinstance(asks_raw, list) or len(asks_raw) > ASKS_MAX:
        raise Problem(f"asks must be a list of at most {ASKS_MAX}")
    asks = []
    for i, a in enumerate(asks_raw):
        if not isinstance(a, dict) or set(a) != {"at", "says"}:
            raise Problem(f"asks[{i}] must be an object with exactly at and says")
        at = a.get("at")
        if not isinstance(at, str) or not AT_RE.match(at) or len(at) > AT_MAX:
            raise Problem(f"asks[{i}].at must be a short kebab-case key")
        asks.append({"at": at, "says": _text(a.get("says"), f"asks[{i}].says", SAYS_MAX)})

    outputs_raw = raw.get("outputs") if raw.get("outputs") is not None else ["summary"]
    if not isinstance(outputs_raw, list) or any(o not in OUTPUTS for o in outputs_raw):
        raise Problem(f"outputs must be a list drawn from {', '.join(OUTPUTS)}")
    outputs = [o for o in OUTPUTS if o in outputs_raw or o == "summary"]  # summary always implied

    deliver = raw.get("deliver")
    if deliver not in DELIVERS:
        raise Problem("deliver must be null or \"email_self\" (the one standing yes)")

    scope_raw = raw.get("scope") if raw.get("scope") is not None else ["full"]
    if (not isinstance(scope_raw, list) or not scope_raw
            or any(s not in SCOPES for s in scope_raw)):
        raise Problem("scope must be a non-empty list drawn from full, listed")
    scope = [s for s in SCOPES if s in scope_raw]
    if "listed" in scope and "page" in outputs:
        raise Problem("a workflow that runs on a listed machine returns summaries only: no page")

    every = _every(raw.get("every"))

    if SHIP_RE.search(skill_text) and not asks:
        raise Problem("SKILL.md runs ship.py but asks declares no stop for Kevin's yes")
    if MAIL_RE.search(skill_text) and not asks and deliver != "email_self":
        raise Problem("SKILL.md runs send_mail.py but asks declares no stop and deliver is not "
                      "email_self")

    entry = {"name": name, "says": says, "aliases": aliases,
             "params": [{k: v for k, v in p.items() if k != "default"} for p in params],
             "asks": asks, "outputs": outputs, "deliver": deliver, "scope": scope, "every": every}
    if len(json.dumps(entry, separators=(",", ":")).encode("utf-8")) > ENTRY_MAX_BYTES:
        raise Problem(f"the engine's copy is over {ENTRY_MAX_BYTES // 1024} KB")
    entry["defaults"] = {p["name"]: p["default"] for p in params if "default" in p}
    return entry


def load_catalog(skills_dir) -> tuple[list[dict], list[tuple[str, str]]]:
    """Every valid workflow under `skills_dir` (a dict per workflow, with its local `dir`), and
    every problem as (folder, words). A folder without a sidecar is a skill, never a problem."""
    root = Path(skills_dir)
    entries: list[dict] = []
    problems: list[tuple[str, str]] = []
    if not root.is_dir():
        return entries, problems
    phrases: dict[str, str] = {}
    for d in sorted(p for p in root.iterdir() if p.is_dir()):
        side = d / SIDECAR
        if not side.is_file():
            continue
        try:
            raw = json.loads(side.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            problems.append((d.name, "workflow.json is not readable JSON"))
            continue
        skill = d / SKILL
        if not skill.is_file():
            problems.append((d.name, "there is no SKILL.md beside workflow.json"))
            continue
        try:
            entry = parse(raw, d.name, skill.read_text(encoding="utf-8-sig", errors="replace"))
        except Problem as e:
            problems.append((d.name, str(e)))
            continue
        clash = next((p for p in _phrases(entry) if p in phrases), None)
        if clash is not None:
            problems.append((d.name, f"the phrase \"{clash}\" is already {phrases[clash]}'s"))
            continue
        if len(entries) >= CATALOG_MAX:
            problems.append((d.name, f"more than {CATALOG_MAX} workflows; this one is left out"))
            continue
        for p in _phrases(entry):
            phrases[p] = entry["name"]
        entry["dir"] = str(d)
        entries.append(entry)
    return entries, problems


def lint(skills_dir) -> list[tuple[str, str]]:
    return load_catalog(skills_dir)[1]


ENGINE_KEYS = ("name", "says", "aliases", "params", "asks", "outputs", "deliver", "scope", "every")


def catalog_for_engine(entries) -> list[dict]:
    """The only shape that leaves the laptop: no `dir`, no defaults, no SKILL.md."""
    return [{k: e[k] for k in ENGINE_KEYS} for e in sorted(entries, key=lambda e: e["name"])]


def catalog_hash(entries) -> str:
    """Stable over key order and folder order: sha256 of the engine shape."""
    blob = json.dumps(catalog_for_engine(entries), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------- matching (ported by the engine)

_NON_WORD = re.compile(r"[^a-z0-9]+")
# Leading words that name who should do it, not what: stripped before matching.
LEAD_PHRASES = ("please", "hey claude", "have claude", "ask claude to", "get claude to",
                "tell claude to", "use claude to", "can claude", "claude please", "claude")


def normalize(text: str) -> str:
    """Lowercase words separated by one space; apostrophes vanish ("Claude's" -> "claudes")."""
    return " ".join(_NON_WORD.sub(" ", (text or "").lower().replace("'", "").replace("’", ""))
                    .split())


def _phrases(entry: dict) -> list[str]:
    out = [normalize(entry["name"].replace("-", " "))]
    out += [normalize(a) for a in entry.get("aliases") or []]
    return [p for i, p in enumerate(out) if p and p not in out[:i]]


def strip_phrase(phrase: str, machines=()) -> list[str]:
    """Kevin's words as tokens, without "have Claude", "ask Claude to", "on my Alienware"."""
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
    """The workflow Kevin's phrase names, and the words after the matched alias (the params
    text). Whole words only; the longest phrase wins (more words, then more letters, then the
    earliest, then the name). None when nothing matches."""
    tokens = strip_phrase(phrase, machines)
    if not tokens:
        return None
    best = None
    for entry in catalog:
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


# ---------------------------------------------------------------- command line

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Lint worker workflow sidecars (workflow.json).")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--lint", metavar="SKILLS_DIR")
    g.add_argument("--catalog", metavar="SKILLS_DIR")
    a = ap.parse_args(argv)
    entries, problems = load_catalog(a.lint or a.catalog)
    if a.catalog:
        print(json.dumps(catalog_for_engine(entries), indent=1))
        print("hash:", catalog_hash(entries))
        return 0
    for folder, words in problems:
        print(f"{folder}: {words}")
    print(f"{len(entries)} workflow(s) pass, {len(problems)} problem(s). "
          "Names of people cannot be checked by code: keep them out of says, aliases and params.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
