# ruff: noqa: E501  (operator-facing messages read better unbroken; as deploy/ship.py)
"""The engine ship's checks that need no credentials, for .github/workflows/ship.yml (cloud-builds step 2,
2026-11-21). The same rules the laptop ship scripts (ship_engine_*_1121.sh, step 1) apply, as pure
functions (tested in server/tests/test_ship_checks.py) plus a small command line that reads git.

    python deploy/ship_checks.py scope --fix <sha> --live-code <sha> --image-files <file> [--migrations <file>]
    python deploy/ship_checks.py gate --summary <engine-summary.txt> --title <run title> --attempt <n>
        --fix <sha> --expected-extras "<a,b>"
    python deploy/ship_checks.py switches --extra "<NAMES>"

Prints what it checked; exits 1 with every problem listed. Stdlib only.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys

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
    "LEGION_JEEP_ASSISTANT",
    "LEGION_LATE_ANSWERS",
    "LEGION_AGENTS_TURN",
    "LEGION_AGENTS_STREAM_STT",
    "LEGION_AGENTS_STT_HEDGE",
    "LEGION_HEADUNIT_SIGNIN",
)
# Also unset on every ship (not switches: settings this deploy path must never carry).
ALSO_UNSET = ("LEGION_SKIP_MIGRATE", "LEGION_WEB_EARLY_TRANSCRIPTS", "LEGION_WEB_AUDIO_FIRST_ROUND")
DESTRUCTIVE = re.compile(r"RemoveField|DeleteModel|AlterField|RenameField|RenameModel")
MIGRATION_PATH = re.compile(r"^server/([^/]+)/migrations/(\d{4}_[A-Za-z0-9_]+)\.py$")
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
    empty __init__.py for an app new since LIVE_CODE; nothing destructive inside (`sources` path -> text)."""
    problems = []
    for letter, path in status:
        if letter != "A":
            problems.append(f"schema file {path} is {letter}, not added")
            continue
        m = MIGRATION_PATH.match(path)
        if m:
            if path not in pinned:
                problems.append(f"schema file added but not pinned: {path}")
            elif DESTRUCTIVE.search(sources.get(path, "")):
                problems.append(f"{path} holds a destructive operation")
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
    problems += frozen_problems(
        git("diff", "--no-renames", "--name-only", a.live_code, a.fix).split()
    )
    mig_pins = parse_pins(open(a.migrations, encoding="utf-8").read()) if a.migrations else {}
    status = [
        tuple(line.split("\t", 1))
        for line in git(
            "diff", "--no-renames", "--name-status", a.live_code, a.fix, "--", "*/migrations/*"
        ).splitlines()
        if line
    ]
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
    w = sub.add_parser("switches")
    w.add_argument("--extra", default="")
    a = ap.parse_args(argv)
    try:
        if a.cmd == "switches":
            print(" ".join(switch_names(a.extra) + list(ALSO_UNSET)))
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
