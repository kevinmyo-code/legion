"""Blast-radius tier of a change, from the files it touches (docs/process/blast-radius.md).

    python tools/blast_radius.py                      # this branch vs origin/dev (merge base)
    python tools/blast_radius.py --base <rev> --head <rev>
    git diff --name-only A B | python tools/blast_radius.py --files -
    python tools/blast_radius.py --short              # one line, for a board card or a brief

Each path gets a tier: RED if it matches a RED pattern, else GREEN if it matches a GREEN pattern,
else YELLOW. An unknown path is never GREEN. The change's tier is the highest of its files.
Deleted and renamed-away paths count (a RED file moved out is still a RED change).
A file list cannot prove "no logic": GREEN styling and layout is decided by file type only.
Stdlib only. Exit 0 with the tier printed; exit 2 on no files or a git error.
"""

from __future__ import annotations

import argparse
import fnmatch
import subprocess
import sys

ORDER = {"GREEN": 0, "YELLOW": 1, "RED": 2}

REVIEW = {
    "GREEN": "CI green + one light review (single reviewer pass, no adversarial rounds), then auto-merge",
    "YELLOW": 'CI green + one blind review ending "Confirmed: SHIP", then auto-merge',
    "RED": 'CI green + blind review with adversarial probes until "Confirmed: SHIP", then auto-merge',
}
DEPLOY_NOTE = "Deploys and publishes still need Kevin's word in every tier."

# (reason, patterns). fnmatch: `*` also crosses `/`, so `server/*/migrations/*` matches any app.
RED_RULES: list[tuple[str, tuple[str, ...]]] = [
    ("worker hook and containment", (
        "tools/claude_session/*",
        "server/claude_session/*",
        "deploy/claude_files/*",
        "tools/hooks/*",
        ".claude/*",
    )),
    ("auth and tokens", (
        "*auth*.py", "*Auth.kt", "*/tokens.py",
        "server/assistant/management/commands/mint_assistant_token.py",
        "server/household/*",
        "server/headunit_signin/*",
        "server/id_vault/*",
        "server/legion/settings.py",
        "server/assistant/session.py",
        "server/assistant/surface.py",
        "server/frontend/src/components/auth-frame.tsx",
        "server/frontend/src/routes/_authed.tsx",
        "*/KeyVault.kt", "*/VaultToolbox.kt", "*/DjangoVaultBackend.kt",
    )),
    ("money and figure checks", (
        "server/ingest/*",
        "server/assistant/spoken_figures.py",
        "server/agents/legion/spelled_money.py",
        "server/agents/legion/guards.py",
        "server/agents/legion/sentence_gate.py",
        "server/finance/figure_views.py",
        "server/frontend/src/lib/figures.ts",
        "*/ReconciliationGate.kt", "*/SpokenMoney.kt", "*/MoneyFilter.kt",
    )),
    ("read-through and crisis guards", (
        "server/assistant/read_through.py",
        "server/agents/legion/relay.py",
        "server/agents/voice/crisis.py",
        "*/ReadThroughWriteGuard.kt", "*/CrisisDetector.kt", "*/EmailSendGate.kt",
    )),
    ("migrations and schema", (
        "server/*/migrations/*",
        "app/schemas/*", "headunit/app/schemas/*",
        "*/data/local/Migrations.kt", "*/data/local/CarDatabase.kt",
        "supabase/*",
    )),
    ("deploy and ship tooling", (
        "deploy/*",
        ".github/*",
        "tools/release_apk.py",
        "tools/blast_radius.py",
        "tools/checks/*",
        "config/detekt/*",
        "app/models.sha256", "app/speaker-model.pin",
        "server/Dockerfile", "server/.dockerignore", "server/.gcloudignore",
        "server/requirements.txt", "server/pyproject.toml", "server/uv.lock", "server/entrypoint.sh",
        "server/frontend/package.json", "server/frontend/package-lock.json",
        "server/frontend/vite.config.ts", "server/frontend/index.html", "server/frontend/tsconfig.sw.json",
    )),
    ("voice gate rules", (
        "tools/voice_eval/*", "tools/voice_eval.py", "tools/money_specialist_eval.py",
        "tools/live_tool_eval/*", "tools/live_tool_eval.py",
    )),
    ("public demo honesty guards", (
        "server/demo/shop/guards.py",
        "server/tests/test_recruiter_delivery_frozen.py",
    )),
    ("frozen rules", (
        "CLAUDE.md", "headunit/CLAUDE.md",
    )),
]

GREEN_RULES: list[tuple[str, tuple[str, ...]]] = [
    # Not "*.md" or "*.txt" anywhere: prompt text, agent specs and app assets are .md/.txt read at runtime.
    ("docs", (
        "docs/*", "vault/*", ".scratch/*", "memory/*", "headunit/memory/*", "*README.md", "LICENSE*",
    )),
    ("tests only", (
        "server/tests/*", "*/src/test/*", "*/src/androidTest/*", "tools/tests/*",
        "*.test.ts", "*.test.tsx", "*.spec.ts", "*.spec.tsx", "*/test_*.py", "test_*.py", "*/__tests__/*",
        "server/frontend/src/test/*",
    )),
    ("copy and wording", (
        "*/res/values/strings.xml", "*/res/values-*/strings.xml",
        "tools/voice_guide_copy.py", "tools/screen_map_copy.py",
    )),
    ("styling and layout (by file type)", (
        "*.css", "*/res/drawable*", "*/res/mipmap*", "*/res/font/*",
        "*.png", "*.jpg", "*.jpeg", "*.webp", "*.svg", "*.gif", "*.ico", "*.mp4", "*.webm",
    )),
    ("scripts outside the worker", (
        "tools/*",
    )),
]


def classify(path: str) -> tuple[str, str]:
    """(tier, reason) for one repo-relative path with forward slashes."""
    p = path.strip().replace("\\", "/")
    if p.startswith("./"):
        p = p[2:]
    for reason, pats in RED_RULES:
        if any(fnmatch.fnmatchcase(p, pat) for pat in pats):
            return "RED", reason
    if "/" not in p and p.endswith(".md"):
        return "GREEN", "docs"
    for reason, pats in GREEN_RULES:
        if any(fnmatch.fnmatchcase(p, pat) for pat in pats):
            return "GREEN", reason
    return "YELLOW", "app or engine logic"


def tier_of(paths: list[str]) -> tuple[str, list[tuple[str, str, str]]]:
    """(overall tier, [(tier, reason, path)] sorted highest first). Raises on an empty list."""
    paths = sorted({p.strip() for p in paths if p.strip()})
    if not paths:
        raise ValueError("no files: an empty change has no tier")
    rows = [(*classify(p), p) for p in paths]
    rows.sort(key=lambda r: (-ORDER[r[0]], r[1], r[2]))
    return rows[0][0], rows


def summary_line(tier: str, rows: list[tuple[str, str, str]]) -> str:
    """One line for a board card or a brief: `RED (worker hook and containment; deploy...): 12 files`."""
    reasons = []
    for t, reason, _ in rows:
        if t == tier and reason not in reasons:
            reasons.append(reason)
    return f"{tier} ({'; '.join(reasons)}): {len(rows)} files"


def git_files(base: str, head: str) -> list[str]:
    """Every path touched base...head (merge base), deleted ones and both sides of a rename included."""
    out = subprocess.run(
        ["git", "diff", "--name-status", "--no-renames", f"{base}...{head}"],
        capture_output=True, text=True, check=True,
    ).stdout
    return [line.split("\t", 1)[1] for line in out.splitlines() if "\t" in line]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", default="origin/dev")
    ap.add_argument("--head", default="HEAD")
    ap.add_argument("--files", help="'-' to read one path per line from stdin instead of git")
    ap.add_argument("--short", action="store_true", help="print only the one-line summary")
    a = ap.parse_args(argv)
    try:
        paths = sys.stdin.read().splitlines() if a.files == "-" else git_files(a.base, a.head)
        tier, rows = tier_of(paths)
    except subprocess.CalledProcessError as e:
        print(f"blast_radius: git failed: {e.stderr.strip()}", file=sys.stderr)
        return 2
    except ValueError as e:
        print(f"blast_radius: {e}", file=sys.stderr)
        return 2
    line = summary_line(tier, rows)
    if a.short:
        print(line)
        return 0
    print(f"tier: {line}")
    print(f"review: {REVIEW[tier]}")
    print(DEPLOY_NOTE)
    shown = 0
    for t, reason, p in rows:
        if t == "GREEN" and tier != "GREEN":
            continue
        print(f"  {t:<6} {p}  ({reason})")
        shown += 1
    hidden = len(rows) - shown
    if hidden:
        print(f"  ... and {hidden} GREEN files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
