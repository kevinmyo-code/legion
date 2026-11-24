# ruff: noqa: E501  (operator-facing messages read better unbroken; as deploy/ship.py)
"""The engine ship's checks that need no credentials, for .github/workflows/ship.yml (cloud-builds step 2,
2026-11-21). The same rules the laptop ship scripts (ship_engine_*_1121.sh, step 1) apply, as pure
functions (tested in server/tests/test_ship_checks.py) plus a small command line that reads git.

    python deploy/ship_checks.py scope --fix <sha> --live-code <sha> --image-files <file> [--migrations <file>]
    python deploy/ship_checks.py gate --summary <engine-summary.txt> --title <run title> --attempt <n>
        --fix <sha> --expected-extras "<a,b>"
        [--add-checks "<unset:NAME frozen:path probe:M:/api/x:404>"] [--unfreeze "<file>=<why>; ..."]
    python deploy/ship_checks.py plan --add-checks "<...>"

Prints what it checked; exits 1 with every problem listed. Stdlib only.
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import subprocess
import sys
from pathlib import Path

# One copy of "is this migration destructive to the database?", shared with the review-finding gate
# (check (c)). Loaded by path under its own module name: this file is also imported by the server's
# pytest, where `tools/checks` on sys.path would shadow modules named `migrations` and `common`.
_OPS = Path(__file__).resolve().parents[1] / "tools" / "checks" / "migration_ops.py"
_ops_spec = importlib.util.spec_from_file_location("legion_migration_ops", _OPS)
migration_ops = importlib.util.module_from_spec(_ops_spec)
_ops_spec.loader.exec_module(migration_ops)

# What the image is built from (deploy.py: gcloud builds submit on server/) and the deploy code.
IMAGE_PATHS = ("server", "deploy")
# The image recipe, the Python and web dependency files and the frozen web build files: never changed by a
# ship this workflow deploys (a dependency change has no gate here).
FROZEN = (
    "server/Dockerfile",
    "server/.dockerignore",
    "server/.gcloudignore",
    "server/requirements.txt",
    "server/pyproject.toml",
    "server/uv.lock",
    "server/entrypoint.sh",
    "server/frontend/tsconfig.sw.json",
    "server/frontend/index.html",
    "server/frontend/package.json",
    "server/frontend/package-lock.json",
    "server/frontend/vite.config.ts",
)
# The dark switches: absent from the .env, the deploy plan, the live service and job, before and after.
# Fixed here; the workflow input can only ADD names (review S5).
SWITCHES = (
    "LEGION_BOARD",
    "LEGION_BOARD_ACTIONS",
    "LEGION_JEEP_ASSISTANT",
    "LEGION_LATE_ANSWERS",
    "LEGION_AGENTS_TURN",
    "LEGION_AGENTS_STREAM_STT",
    "LEGION_AGENTS_STT_HEDGE",
    "LEGION_HEADUNIT_SIGNIN",
)
# Also unset on every ship (not switches: settings this deploy path must never carry).
ALSO_UNSET = ("LEGION_SKIP_MIGRATE", "LEGION_WEB_EARLY_TRANSCRIPTS", "LEGION_WEB_AUDIO_FIRST_ROUND")
# The live surfaces a ship must leave identical to the live code (review D1): the /try demo, the web app,
# the personas and prompt, the frozen demo delivery and its test, settings, households, fixtures, the MCP
# door, the API contract, the agents runtime and graph. The union of the laptop ship scripts'
# FROZEN_LIVE_PATHS (2026-11-21). A path is the file itself or everything under that folder. The
# `add_checks` input may add paths (frozen:<path>); only the `unfreeze` input, naming each changed file
# and why, lets a ship change one, and the run prints it for the approver.
FROZEN_LIVE = (
    "server/demo",
    "server/frontend",
    "server/greeting",
    "server/assistant/tour.py",
    "server/assistant/surface.py",
    "server/assistant/views.py",
    "server/assistant/personas.py",
    "server/assistant/companions.py",
    "server/assistant/prompt.py",
    "server/tests/test_recruiter_delivery_frozen.py",
    "server/tests/fixtures",
    "server/household",
    "server/legion",
    "server/engine_mcp",
    "server/openapi.yaml",
    "server/agents/runtime",
    "server/agents/graph",
)
PROBE_METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE")
PROBE_PATH = re.compile(r"^/api/[A-Za-z0-9_./-]+$")
REPO_PATH = re.compile(r"^(server|deploy)/[A-Za-z0-9_./-]+$")
MIGRATION_PATH = re.compile(r"^server/([^/]+)/migrations/(\d{4}_[A-Za-z0-9_]+)\.py$")
# The Django migrations this ship applies, and nothing else. `(glob)` stops a single `*` crossing
# `/`, so the plain `*/migrations/*` this used to pass collected `supabase/migrations/*.sql` (the
# legacy schema, applied by hand and not in the image) and then refused it as an unexpected file
# under migrations/. `**` still crosses `/`, so a migrations folder at any depth under server/ is
# collected and judged: narrowing to one level would have failed open inside the image.
MIGRATION_PATHSPEC = ":(glob)server/**/migrations/**"
SHA = re.compile(r"^[0-9a-f]{40}$")
BLOB = re.compile(r"^[0-9a-f]{40}$")
NAME = re.compile(r"^[A-Z][A-Z0-9_]*$")


# ---------------------------------------------------------------------------------------- pure rules
def parse_pins(text: str) -> dict[str, str]:
    """`<path> <blob>` per line (blank lines and # comments ignored) -> {path: blob}."""
    out: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) != 2 or not BLOB.match(parts[1]):
            raise ValueError(f"not '<path> <40-hex blob>': {line!r}")
        if parts[0] in out:
            raise ValueError(f"listed twice: {parts[0]}")
        out[parts[0]] = parts[1]
    return out


def image_problems(changed: dict[str, str | None], pinned: dict[str, str]) -> list[str]:
    """`changed`: every image-path file that differs LIVE_CODE..FIX -> its blob at FIX (None = deleted).
    It must be exactly the pinned set, file for file and blob for blob."""
    problems = []
    for path in sorted(set(changed) - set(pinned)):
        problems.append(f"changed but not pinned: {path}")
    for path in sorted(set(pinned) - set(changed)):
        problems.append(f"pinned but not changed: {path}")
    for path in sorted(set(changed) & set(pinned)):
        if changed[path] != pinned[path]:
            problems.append(f"{path} is blob {changed[path] or 'deleted'}, pinned {pinned[path]}")
    return problems


def frozen_problems(changed_paths: list[str]) -> list[str]:
    return [f"frozen file changed: {p}" for p in changed_paths if p in FROZEN]


def migration_problems(
    status: list[tuple[str, str]],
    pinned: dict[str, str],
    new_apps: set[str],
    sources: dict[str, str],
) -> list[str]:
    """`status`: (letter, path) for every */migrations/* change LIVE_CODE..FIX. Allowed: exactly the pinned
    files, each ADDED, at its pinned blob (checked by the caller through `pinned` vs the blobs), plus an
    empty __init__.py for an app new since LIVE_CODE; nothing that changes the database destructively
    (`sources` path -> text, judged by tools/checks/migration_ops.py: a state-only
    `SeparateDatabaseAndState` runs no SQL and ships, a real DROP does not)."""
    problems = []
    for letter, path in status:
        if letter != "A":
            problems.append(f"schema file {path} is {letter}, not added")
            continue
        m = MIGRATION_PATH.match(path)
        if m:
            if path not in pinned:
                problems.append(f"schema file added but not pinned: {path}")
            elif reason := migration_ops.database_destructive(sources.get(path, "")):
                problems.append(f"{path} holds a destructive database operation ({reason})")
            continue
        app_init = re.match(r"^server/([^/]+)/migrations/__init__\.py$", path)
        if not (app_init and app_init.group(1) in new_apps):
            problems.append(f"unexpected file under migrations/: {path}")
    added = {p for letter, p in status if letter == "A"}
    for path in sorted(set(pinned) - added):
        problems.append(f"pinned schema file not added by this ship: {path}")
    return problems


def switch_names(extra: str) -> list[str]:
    """The fixed switches plus the ones a ship adds (never fewer)."""
    names = list(SWITCHES)
    for n in extra.replace(",", " ").split():
        if not NAME.match(n):
            raise ValueError(f"not an env name: {n!r}")
        if n not in names:
            names.append(n)
    return names


def parse_add_checks(text: str) -> dict[str, list]:
    """The add-only input: space-separated `unset:<ENV_NAME>`, `frozen:<path>`,
    `probe:<METHOD>:<path>:<codes>` (codes 4xx only, comma separated: a dark route must refuse)."""
    out: dict[str, list] = {"unset": [], "frozen": [], "probes": []}
    for tok in text.split():
        kind, _, rest = tok.partition(":")
        if kind == "unset" and NAME.match(rest):
            out["unset"].append(rest)
        elif kind == "frozen" and REPO_PATH.match(rest) and ".." not in rest:
            out["frozen"].append(rest.rstrip("/"))
        elif kind == "probe":
            parts = rest.split(":")
            if len(parts) != 3:
                raise ValueError(f"probe must be probe:<METHOD>:<path>:<codes>: {tok!r}")
            method, path, codes = parts
            code_list = codes.split(",")
            if (
                method not in PROBE_METHODS
                or not PROBE_PATH.match(path)
                or ".." in path
                or not all(re.fullmatch(r"4\d\d", c) for c in code_list)
            ):
                raise ValueError(f"probe needs a method, an /api/ path and 4xx codes only: {tok!r}")
            out["probes"].append((method, path, code_list))
        else:
            raise ValueError(
                f"not unset:<NAME>, frozen:<path> or probe:<M>:<path>:<codes>: {tok!r}"
            )
    return out


def parse_unfreeze(text: str) -> dict[str, str]:
    """`<file>=<why>; <file>=<why>`: each frozen file this ship changes, by exact path, with a reason."""
    out: dict[str, str] = {}
    for part in text.split(";"):
        if not part.strip():
            continue
        path, sep, why = part.partition("=")
        path, why = path.strip(), why.strip()
        if not sep or not REPO_PATH.match(path) or ".." in path:
            raise ValueError(f"unfreeze entries are '<file>=<why>': {part.strip()!r}")
        if len(why) < 8:
            raise ValueError(f"unfreeze {path} needs a reason in words")
        if path in out:
            raise ValueError(f"unfreeze lists {path} twice")
        out[path] = why
    return out


def is_frozen(path: str, frozen: tuple[str, ...] | list[str]) -> bool:
    return any(path == f or path.startswith(f + "/") for f in frozen)


def frozen_live_problems(
    changed_paths: list[str], frozen: list[str], unfreeze: dict[str, str]
) -> list[str]:
    """Every changed file under a frozen path must be named in `unfreeze`; every unfreeze entry must be
    a frozen file this ship really changes (no blanket or stale entries)."""
    problems = []
    changed = set(changed_paths)
    for p in sorted(changed):
        if is_frozen(p, frozen) and p not in unfreeze:
            problems.append(
                f"frozen live path changed: {p} (name it in unfreeze with a reason, or leave it)"
            )
    for p in sorted(unfreeze):
        if not is_frozen(p, frozen):
            problems.append(f"unfreeze names {p}, which is not frozen")
        elif p not in changed:
            problems.append(f"unfreeze names {p}, which this ship does not change")
    return problems


def gate_problems(
    summary: str, title: str, attempt: int, fix: str, expected_extras: list[str]
) -> list[str]:
    """The engine's cloud gate run (gates.yml) for this sha: title, first attempt, the scorecard's verdicts.
    The extras are a free input of gates.yml, so the ship names the ones it expects, and each must PASS."""
    problems = []
    if title not in (f"Gates engine {fix}", f"Gates all {fix}", f"Gates all partial {fix}"):
        problems.append(f"the gates run is titled {title!r}, not the engine for {fix}")
    if attempt != 1:
        problems.append(f"the gates run passed on attempt {attempt}; only a first attempt counts")
    lines = [line.strip() for line in summary.splitlines()]
    if "RESULT: PASS" not in lines:
        problems.append("the engine scorecard has no 'RESULT: PASS'")
    if not any(line.startswith("gate: PASS") for line in lines):
        problems.append("the engine scorecard has no 'gate: PASS' line")
    if "chip turn: PASS" not in lines:
        problems.append("the engine scorecard has no 'chip turn: PASS'")
    ran = {
        m.group(1)
        for line in lines
        if (m := re.match(r"^extra (realrun_[a-z0-9_]+): PASS\b", line))
    }
    for name in expected_extras:
        if name not in ran:
            problems.append(f"expected extra check {name} did not PASS in that run")
    for name in sorted(ran - set(expected_extras)):
        problems.append(f"extra check {name} ran but this ship did not name it")
    return problems


# ---------------------------------------------------------------------------------------- git plumbing
def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def blob_at(sha: str, path: str) -> str | None:
    r = subprocess.run(
        ["git", "rev-parse", "-q", "--verify", f"{sha}:{path}"], capture_output=True, text=True
    )
    return r.stdout.strip() if r.returncode == 0 else None


def schema_status(live_code: str, fix: str) -> list[tuple[str, str]]:
    """(letter, path) for every Django migration file this ship changes, LIVE_CODE..FIX.

    `MIGRATION_PATHSPEC` says why it is not every path with `migrations/` in it."""
    return [
        tuple(line.split("\t", 1))
        for line in git(
            "diff", "--no-renames", "--name-status", live_code, fix, "--", MIGRATION_PATHSPEC
        ).splitlines()
        if line
    ]


def cmd_scope(a) -> list[str]:
    for v in (a.fix, a.live_code):
        if not SHA.match(v):
            return [f"not a full sha: {v}"]
    if subprocess.run(["git", "merge-base", "--is-ancestor", a.live_code, a.fix]).returncode != 0:
        return [f"the live code {a.live_code[:8]} is not an ancestor of {a.fix[:8]}"]
    pinned = parse_pins(open(a.image_files, encoding="utf-8").read())
    paths = git(
        "diff", "--no-renames", "--name-only", a.live_code, a.fix, "--", *IMAGE_PATHS
    ).split()
    changed = {p: blob_at(a.fix, p) for p in paths}
    problems = image_problems(changed, pinned)
    all_changed = git("diff", "--no-renames", "--name-only", a.live_code, a.fix).split()
    problems += frozen_problems(all_changed)
    frozen = list(FROZEN_LIVE) + [
        f for f in parse_add_checks(a.add_checks)["frozen"] if f not in FROZEN_LIVE
    ]
    unfreeze = parse_unfreeze(a.unfreeze)
    problems += frozen_live_problems(all_changed, frozen, unfreeze)
    print(f"frozen live paths ({len(frozen)}): {' '.join(frozen)}")
    for p, why in sorted(unfreeze.items()):
        print(f"UNFROZEN by this ship: {p}: {why}")
    mig_pins = parse_pins(open(a.migrations, encoding="utf-8").read()) if a.migrations else {}
    status = schema_status(a.live_code, a.fix)
    new_apps = {
        m.group(1)
        for _, p in status
        if (m := MIGRATION_PATH.match(p))
        and blob_at(a.live_code, f"server/{m.group(1)}/migrations/__init__.py") is None
    }
    sources = {
        p: git("show", f"{a.fix}:{p}")
        for _, p in status
        if MIGRATION_PATH.match(p) and blob_at(a.fix, p)
    }
    problems += migration_problems(status, mig_pins, new_apps, sources)
    for path, blob in mig_pins.items():
        if blob_at(a.fix, path) != blob:
            problems.append(f"{path} at FIX is not the pinned blob {blob[:8]}")
    print(
        f"image change {a.live_code[:8]}..{a.fix[:8]}: {len(changed)} files (pinned {len(pinned)})"
    )
    print(f"schema files: {', '.join(p for _, p in status) or 'none'}")
    other = [
        p
        for p in git("diff", "--no-renames", "--name-only", a.live_code, a.fix).split()
        if not p.startswith(("server/", "deploy/"))
    ]
    print(f"outside the image (not deployed by this ship): {len(other)} files")
    return problems


def cmd_gate(a) -> list[str]:
    extras = [x for x in a.expected_extras.replace(",", " ").split() if x]
    return gate_problems(
        open(a.summary, encoding="utf-8").read(), a.title, a.attempt, a.fix, extras
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("scope")
    s.add_argument("--fix", required=True)
    s.add_argument("--live-code", required=True)
    s.add_argument("--image-files", required=True, help="file of '<path> <blob>' lines")
    s.add_argument(
        "--migrations", default="", help="file of '<path> <blob>' lines for the added schema files"
    )
    g = sub.add_parser("gate")
    g.add_argument("--summary", required=True)
    g.add_argument("--title", required=True)
    g.add_argument("--attempt", type=int, required=True)
    g.add_argument("--fix", required=True)
    g.add_argument("--expected-extras", default="")
    s.add_argument(
        "--add-checks", default="", help="the add-only input (frozen:<path> entries used here)"
    )
    s.add_argument(
        "--unfreeze", default="", help="'<file>=<why>; ...' frozen files this ship changes"
    )
    w = sub.add_parser("plan", help="print switches= and probes= lines for $GITHUB_OUTPUT")
    w.add_argument("--add-checks", default="")
    a = ap.parse_args(argv)
    try:
        if a.cmd == "plan":
            add = parse_add_checks(a.add_checks)
            print("switches=" + " ".join(switch_names(" ".join(add["unset"])) + list(ALSO_UNSET)))
            print("probes=" + ";".join(f"{m} {p} {','.join(c)}" for m, p, c in add["probes"]))
            return 0
        problems = cmd_scope(a) if a.cmd == "scope" else cmd_gate(a)
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        problems = [str(exc)]
    for p in problems:
        print(f"STOP {p}")
    print(f"{a.cmd}: {'all checks passed' if not problems else f'{len(problems)} problem(s)'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
