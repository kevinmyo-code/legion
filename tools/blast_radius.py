"""Blast-radius tier of a change, from the files it touches (docs/process/blast-radius.md).

    python tools/blast_radius.py                      # this branch vs origin/dev (merge base)
    python tools/blast_radius.py --base <rev> --head <rev>
    python tools/blast_radius.py --short              # one line, for a board card or a brief
    git diff --name-only A B | python tools/blast_radius.py --files -

The trusted run is the BASE copy, so a change cannot lower its own tier by editing this file:
    git show origin/dev:tools/blast_radius.py | python - --base origin/dev --head <sha>
In git mode the script also loads the base copy itself and reports max(base copy, head copy).

Each path gets a tier. RED if it matches a RED rule (case-insensitive) or case-collides with another
tracked path; else YELLOW if it matches a YELLOW rule; else GREEN if it matches a GREEN rule; else YELLOW.
An unknown path is never GREEN. The change's tier is the highest of its files. Deleted and renamed-away
paths count. A file list cannot prove "no logic": GREEN styling is decided by file type only.
A rendered site file under docs/ (.html, .js, .css, .json, privacy pages, the Pages config, top-level
images) is also flagged PUBLISH: Pages serves docs/ from dev, so merging it changes the public site and
needs Kevin's word (orchestrator default 2026-11-22, pending his confirmation). Plain .md/.txt docs are
not: the repo is public already.
Stdlib only. Exit 0 with the tier printed; exit 2 on no files or a git error.
"""

from __future__ import annotations

import argparse
import fnmatch
import subprocess
import sys

ORDER = {"GREEN": 0, "YELLOW": 1, "RED": 2}
SELF = "tools/blast_radius.py"

REVIEW = {
    "GREEN": 'CI green + one light review (task text + diff, single pass) ending "Confirmed: SHIP at <sha>", then auto-merge',
    "YELLOW": 'CI green + one blind review ending "Confirmed: SHIP at <sha>", then auto-merge',
    "RED": 'CI green + blind review with adversarial probes until "Confirmed: SHIP at <sha>", then auto-merge',
}
DEPLOY_NOTE = "Deploys, publishes, content loads and phone installs still need Kevin's word in every tier."
PUBLISH_NOTE = "PUBLISH: changes a rendered GitHub Pages file (served from dev:docs/); the merge itself needs Kevin's word."

# (reason, patterns). Matched casefolded; fnmatch `*` also crosses `/`.
RED_RULES: list[tuple[str, tuple[str, ...]]] = [
    ("agent rules, hooks and MCP config", (
        ".claude/*", "*/.claude/*",
        ".mcp.json", "*.mcp.json", "*/.mcp.json",
    )),
    ("worker hook and containment", (
        "tools/claude_session/*",
        "server/claude_session/*",
        "deploy/claude_files/*",
        "tools/hooks/*",
        "tools/mcp/*",
        "tools/phone/rails.py",
    )),
    ("scripts the commit hooks run", (
        "tools/obsidian_sync.py", "tools/pending_wiki.py", "tools/voice_guide.py", "tools/voice_guide_copy.py",
        "tools/screen_map.py", "tools/screen_map_copy.py", "tools/decision_debt.py", "tools/devlog.py",
    )),
    ("auth and tokens", (
        "*auth*.py", "*auth.kt",
        "*/tokens.py",
        "server/assistant/management/commands/mint_assistant_token.py",
        "tools/connect_session.py",
        "server/household/*",
        "server/headunit_signin/*",
        "server/id_vault/*",
        "server/legion/settings.py",
        "server/assistant/session.py",
        "server/assistant/surface.py",
        "server/frontend/src/components/auth-frame.tsx",
        "server/frontend/src/routes/_authed.tsx",
        "*/keyvault.kt", "*/vaulttoolbox.kt", "*/djangovaultbackend.kt",
    )),
    ("money, figure and privacy checks", (
        "server/ingest/*",
        "server/assistant/spoken_figures.py",
        "server/agents/legion/spelled_money.py",
        "server/agents/legion/guards.py",
        "server/agents/legion/sentence_gate.py",
        "server/finance/figure_views.py",
        "server/frontend/src/lib/figures.ts",
        "*/reconciliationgate.kt", "*/spokenmoney.kt", "*/moneyfilter.kt",
        "tools/finance_privacy_check.py",
        "server/tests/finance_synthetic.py",  # the figure allowlist of the public-repo privacy guard
        "server/tests/bofa_fixtures/*",  # statements the reconciliation gate's tests read
        "app/src/test/resources/gate-corpus.json",
    )),
    ("read-through and crisis guards", (
        "server/assistant/read_through.py",
        "server/member_memory/turns.py",  # the per-turn save's own read-through gate (ticket 450)
        "server/agents/legion/relay.py",
        "server/agents/voice/crisis.py",
        "*/readthroughwriteguard.kt", "*/crisisdetector.kt", "*/emailsendgate.kt",
    )),
    ("migrations and schema", (
        "server/*/migrations/*",
        "app/schemas/*", "headunit/app/schemas/*",
        "*/data/local/migrations.kt", "*/data/local/cardatabase.kt",
        "supabase/*",
    )),
    ("deploy, ship and CI tooling", (
        "deploy/*",
        ".github/*",
        "scripts/ship_*",
        "tools/release_apk.py",
        SELF,
        "tools/checks/*",
        "app/models.sha256", "app/speaker-model.pin",
        "config/detekt/detekt.yml",
        "server/dockerfile", "server/.dockerignore", "server/.gcloudignore",
        "server/requirements.txt", "server/pyproject.toml", "server/uv.lock", "server/entrypoint.sh",
        "server/frontend/package.json", "server/frontend/package-lock.json",
        "server/frontend/vite.config.ts", "server/frontend/index.html", "server/frontend/tsconfig.sw.json",
        "server/tests/conftest.py", "*/conftest.py", "conftest.py",
    )),
    ("build scripts and dependencies (run by gates.yml with the gates key present)", (
        "*build.gradle.kts", "settings.gradle.kts", "*/settings.gradle.kts", "gradle.properties",
        "gradle/*", "gradlew", "gradlew.bat", "*/gradle/*", "*/gradlew", "*/gradlew.bat",
    )),
    ("voice gate rules and gate code", (
        "tools/voice_eval/*", "tools/voice_eval.py", "tools/money_specialist_eval.py",
        "tools/live_tool_eval/*", "tools/live_tool_eval.py",
        "server/tests/realrun_*",
        "*/moneyspecialistevalcasestest.kt",
        # tools/voice_eval.py runs :app:testDebugUnitTest --tests ...voiceeval.VoiceEvalRun with the key.
        "*/voiceeval/*",
        # Imported (transitively) by server/tests/realrun_*, which the keyed engine gate runs. Kept current
        # by tools/tests/test_blast_radius.py::test_everything_realrun_imports_is_red.
        "server/tests/legacy_test_schema.py",
        "server/tests/test_assistant.py",
        "server/tests/test_assistant_cascade.py",
        "server/tests/test_no_speech.py",
        "server/tests/test_profile_verbatim.py",
        "server/tests/test_recruiter.py",
        "server/tests/test_recruiter_content.py",
    )),
    ("public demo honesty guards", (
        "server/demo/shop/guards.py",
        "server/tests/test_recruiter_delivery_frozen.py",
    )),
]

# Tests and test data whose SUBJECT is RED are RED (a guard's test weakened is the guard weakened).
# Applied only to paths under a test root (TEST_ROOTS), matched on the casefolded file name. A rough rule:
# a name list misses some tests; a reviewer who spots one raises its tier and adds the name here.
RED_TEST_NAMES = (
    "*gate*", "*readthrough*", "*read_through*", "*crisis*", "*money*", "*auth*", "*vault*", "*tenan*",
    "*figure*", "*privacy*", "*containment*", "*asklist*", "*migration*", "*schema*", "*token*",
    "test_ship*", "test_release_apk*", "test_voice_eval*", "test_blast_radius*", "test_checks*",
    "realrun_*", "*recruiter_delivery*", "*specialisteval*",
    "*relay*", "*claude_session*", "*claude_worker*", "*worker_scope*", "*ingest*", "*signin*",
    "*connect_session*", "*household*", "finance_synthetic*",
    "*bofa*", "*plaid*", "*dedup*", "*merchant_alias*", "*drive_statements*", "*ledger_categor*", "*spend_api*",
    "*isolation*", "*visibility*", "*write_guard*", "*spoken*",
    # The re-wake carry is what one conversation leaves in front of the model for the next one, and
    # its only safety argument is ADR 0090's read-through write taint: weakening its tests weakens
    # that guard (raised by the blind review of feat/rewake-carry, 2026-11-24).
    "*rewakecarry*",
    # The per-turn conversation save (ticket 450): its tests ARE the read-through, tenancy and
    # recruiter guards of that path, and LiveAudit is where the phone redacts before it saves (raised
    # by the blind review of up/turn-save-450, 2026-11-26).
    "*conversation_turns*", "*liveaudit*", "*turnoutbox*", "*turnsaver*",
)

YELLOW_RULES: list[tuple[str, tuple[str, ...]]] = [
    # FROZEN_LIVE at ship (deploy/ship_checks.py) and read by the engine's tests as truth: never GREEN.
    ("test fixtures the ship freezes", ("server/tests/fixtures/*",)),
    # The detekt baseline: YELLOW, and tools/checks/detekt_baseline.py fails it unless it only shrinks.
    ("detekt baseline", ("config/detekt/*",)),
]

TEST_ROOTS = (
    "server/tests/*", "*/src/test/*", "*/src/androidtest/*", "tools/tests/*",
    "server/frontend/src/test/*", "server/frontend/e2e/*",
)
TEST_FILES_IN_SOURCE = ("server/frontend/src/*.test.ts", "server/frontend/src/*.test.tsx")

DOC_EXT = (".md", ".txt", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".canvas", ".base")
DOC_ROOTS = ("docs/", "vault/", ".scratch/", "memory/", "headunit/memory/")

GREEN_RULES: list[tuple[str, tuple[str, ...]]] = [
    ("copy and wording", ("*/res/values/strings.xml", "*/res/values-*/strings.xml")),
    ("styling and layout (by file type)", (
        "*.css", "*/res/drawable*", "*/res/mipmap*", "*/res/font/*",
        "*.png", "*.jpg", "*.jpeg", "*.webp", "*.svg", "*.gif", "*.ico", "*.mp4", "*.webm",
    )),
]


def _norm(path: str) -> str:
    p = path.strip().replace("\\", "/")
    return p[2:] if p.startswith("./") else p


def _any(p: str, pats) -> bool:
    return any(fnmatch.fnmatchcase(p, pat.casefold()) for pat in pats)


def is_test(path: str) -> bool:
    p = _norm(path).casefold()
    return _any(p, TEST_ROOTS) or _any(p, TEST_FILES_IN_SOURCE)


def is_doc(path: str) -> bool:
    p = _norm(path).casefold()
    if not p.endswith(DOC_EXT):
        return False
    if p.startswith(DOC_ROOTS) or p.endswith("readme.md"):
        return True
    return "/" not in p and p.endswith(".md")


def classify(path: str) -> tuple[str, str]:
    """(tier, reason) for one repo-relative path."""
    p = _norm(path).casefold()
    name = p.rsplit("/", 1)[-1]
    # CLAUDE.md, CLAUDE.local.md at any depth; AGENTS.md and TEAM.md (the roster agents read) at the root.
    if (name.startswith("claude") and name.endswith(".md")) or p in ("agents.md", "team.md"):
        return "RED", "agent rules, hooks and MCP config"
    for reason, pats in RED_RULES:
        if _any(p, pats):
            return "RED", reason
    if is_test(p) and _any(p.rsplit("/", 1)[-1], RED_TEST_NAMES):
        return "RED", "test or test data of a RED subject"
    for reason, pats in YELLOW_RULES:
        if _any(p, pats):
            return "YELLOW", reason
    if is_test(p):
        return "GREEN", "tests only"
    if is_doc(p):
        return "GREEN", "docs"
    for reason, pats in GREEN_RULES:
        if _any(p, pats):
            return "GREEN", reason
    return "YELLOW", "app, engine or tool logic"


def collisions(paths: list[str], tracked: list[str]) -> set[str]:
    """Changed paths that differ only in case from another tracked or changed path (they collide on a
    Windows checkout, and checkout order decides which text is read)."""
    by_fold: dict[str, set[str]] = {}
    for p in set(map(_norm, tracked)) | set(map(_norm, paths)):
        by_fold.setdefault(p.casefold(), set()).add(p)
    return {_norm(p) for p in paths if len(by_fold.get(_norm(p).casefold(), ())) > 1}


def tier_of(paths: list[str], tracked: list[str] | None = None) -> tuple[str, list[tuple[str, str, str]]]:
    """(overall tier, [(tier, reason, path)] sorted highest first). Raises on an empty list."""
    paths = sorted({_norm(p) for p in paths if p.strip()})
    if not paths:
        raise ValueError("no files: an empty change has no tier")
    clash = collisions(paths, tracked or [])
    rows = [("RED", "case-collides with another path", p) if p in clash else (*classify(p), p) for p in paths]
    rows.sort(key=lambda r: (-ORDER[r[0]], r[1], r[2]))
    return rows[0][0], rows


SITE_EXT = (".html", ".htm", ".js", ".mjs", ".css", ".json", ".webmanifest")
SITE_NAMES = ("cname", ".nojekyll", "_config.yml", "_config.yaml")
IMAGE_EXT = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico")


def is_site_file(path: str) -> bool:
    """A rendered GitHub Pages file under docs/: pages, scripts, styles, data they load, the Pages config,
    privacy pages, and images at the top of docs/ (where a page loads them). Not .md/.txt docs or the
    diagrams beside them: the repo is public, so those appearing on Pages adds no exposure."""
    p = _norm(path).casefold()
    if not p.startswith("docs/"):
        return False
    name = p.rsplit("/", 1)[-1]
    if p.endswith(SITE_EXT) or name in SITE_NAMES or "privacy" in name:
        return True
    return p.count("/") == 1 and p.endswith(IMAGE_EXT)


def publishes(paths: list[str]) -> list[str]:
    return sorted(p for p in map(_norm, paths) if is_site_file(p))


def summary_line(tier: str, rows: list[tuple[str, str, str]]) -> str:
    """One line for a board card or a brief: `RED (deploy...; worker...): 12 files`."""
    reasons = []
    for t, reason, _ in rows:
        if t == tier and reason not in reasons:
            reasons.append(reason)
    line = f"{tier} ({'; '.join(reasons)}): {len(rows)} files"
    if publishes([r[2] for r in rows]):
        line += " + PUBLISH (Pages site file: needs Kevin's word)"
    return line


def git(*args: str) -> str:
    return subprocess.run(["git", "-c", "core.quotePath=false", *args],
                          capture_output=True, text=True, check=True, encoding="utf-8").stdout


def git_files(base: str, head: str) -> list[str]:
    """Every path touched base...head (merge base), deleted ones and both sides of a rename included."""
    out = git("diff", "--name-status", "--no-renames", f"{base}...{head}")
    return [line.split("\t", 1)[1] for line in out.splitlines() if "\t" in line]


def base_copy_tier(base: str, head: str, paths: list[str]) -> str | None:
    """The tier this change gets from the BASE's copy of this script (the base tip, else the merge base),
    or None if neither has one. Loaded from git, so edits to the head copy cannot change it."""
    src = rev = None
    candidates = [base]
    try:
        candidates.append(git("merge-base", base, head).strip())
    except subprocess.CalledProcessError:
        pass
    for rev in candidates:  # the base TIP first (a stale branch still meets today's rules), then the fork point
        try:
            src = git("show", f"{rev}:{SELF}")
            break
        except subprocess.CalledProcessError:
            continue
    if src is None:
        return None
    ns: dict = {"__name__": "blast_radius_base_copy"}
    exec(compile(src, f"{rev}:{SELF}", "exec"), ns)  # noqa: S102 (our own file, from git)
    return ns["tier_of"](paths)[0]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", default="origin/dev")
    ap.add_argument("--head", default="HEAD")
    ap.add_argument("--files", help="'-' to read one path per line from stdin instead of git (no base copy)")
    ap.add_argument("--short", action="store_true", help="print only the one-line summary")
    a = ap.parse_args(argv)
    base_tier = None
    try:
        if a.files == "-":
            paths, tracked = sys.stdin.read().splitlines(), []
        else:
            paths = git_files(a.base, a.head)
            tracked = git("ls-tree", "-r", "--name-only", a.head).splitlines()
        tier, rows = tier_of(paths, tracked)
        if a.files != "-":
            base_tier = base_copy_tier(a.base, a.head, paths)
    except subprocess.CalledProcessError as e:
        print(f"blast_radius: git failed: {(e.stderr or '').strip()}", file=sys.stderr)
        return 2
    except ValueError as e:
        print(f"blast_radius: {e}", file=sys.stderr)
        return 2
    final = max(tier, base_tier or "GREEN", key=ORDER.__getitem__)
    line = summary_line(tier, rows)
    if final != tier:
        line = f"{final} (base copy of the script says {base_tier}; head copy: {line})"
    if a.short:
        print(line)
        return 0
    print(f"tier: {line}")
    if a.files != "-":
        print(f"base copy of {SELF}: {base_tier or 'none at the merge base'}; head copy: {tier}")
    print(f"review: {REVIEW[final]}")
    print(DEPLOY_NOTE)
    if publishes(paths):
        print(PUBLISH_NOTE)
    shown = 0
    for t, reason, p in rows:
        if t == "GREEN" and tier != "GREEN":
            continue
        print(f"  {t:<6} {p}  ({reason})")
        shown += 1
    if len(rows) - shown:
        print(f"  ... and {len(rows) - shown} GREEN files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
