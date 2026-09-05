#!/usr/bin/env python3
"""PreToolUse hook (Bash): refuse the commands that have destroyed data here, or would.

Every rule below was paid for or is one keystroke from being paid for. The device agent's file
records the uninstall that took the Keystore-held Gemini key, the receipt photos and the SAF grants
with it; `pm clear` is the same wipe by another door; a forced push rewrites a public repo whose
Pages site is served from `dev`; `git reset --hard` discards other agents' half-saved edits in a
tree that routinely has two agents in it; `supabase db reset` empties the system of record.

Reads the hook's stdin JSON and looks at `tool_input.command`. On a match it prints why and the way
around to stderr and exits 2 (Claude Code: block, show the model the message). Anything else,
including a hook bug, exits 0 - fails open, never closed.

Deliberately NOT blocked: `git stash` (legitimate, and a memory rule covers it socially),
`git checkout --`, `git clean`, `--force-with-lease`, and `rm -rf` inside `$CLAUDE_JOB_DIR` or any
`tmp` directory. The list is the brief's; widening it is a ruling.

Idea from everything-claude-code's `scripts/hooks/gateguard-fact-force.js` (MIT, (c) 2026 Affaan
Mustafa), whose destructive-command branch recognises `rm -rf`, `git reset --hard` and
`git push --force` and denies with a fact-forcing prompt. Re-authored in Python: LEGION's list is
different (adb/pm/supabase added; git checkout/clean/amend dropped), the deny is a plain block with
a named way around, and there is no per-session state file. See `.claude/skills/ATTRIBUTION.md`.

Try it by hand:
    echo '{"tool_input":{"command":"adb uninstall com.kevin.legion"}}' | python tools/hooks/guard_destructive.py
"""

import json
import os
import re
import shlex
import sys

WAY_AROUND_UNINSTALL = (
    "An uninstall destroys files/, the receipt photos, the Keystore-held Gemini key and every SAF "
    "grant (.claude/agents/device.md, 'Rules paid for in lost data'). Use `adb install -r <apk>` to "
    "reinstall and keep the data. If an uninstall is genuinely wanted, Kevin does it by hand."
)

RULES = [
    (re.compile(r"\bpm\s+uninstall\b"), "pm uninstall", WAY_AROUND_UNINSTALL),
    (re.compile(r"\badb\b[^;&|\n]*\buninstall\b"), "adb uninstall", WAY_AROUND_UNINSTALL),
    (re.compile(r"\bpm\s+clear\b"), "pm clear",
     "`pm clear` is the wipe path: Room, the Keystore-held Gemini key, every permission and SAF grant, "
     "all gone. The wipe-and-restore test exists but it needs Kevin present and a backup verified "
     "first. Ask; do not run it. To reset one table, use the app's own delete path or a targeted "
     "SQL against a pulled copy."),
    (re.compile(r"\bgit\b[^;&|\n]*\breset\b[^;&|\n]*--hard\b"), "git reset --hard",
     "Discards every uncommitted change in the tree, including another agent's half-saved edits - "
     "this tree routinely has two agents in it (memory: one Gradle writer). For one file: "
     "`git checkout -- <file>`. To set aside your own work: `git stash`. To start clean: a worktree."),
    (re.compile(r"\bsupabase\s+db\s+reset\b"), "supabase db reset",
     "Resets the linked database. Supabase is the system of record (CLAUDE.md, backend-erp pivot); "
     "the phone is a cache of it, not the other way round. Write a forward migration under "
     "supabase/migrations/ and `supabase db push` it."),
]


def _tokens(segment: str):
    try:
        return shlex.split(segment)
    except ValueError:
        return segment.split()


def forced_push(segment: str) -> bool:
    toks = _tokens(segment)
    if "git" not in toks:
        return False
    rest = toks[toks.index("git") + 1:]
    j = 0
    while j < len(rest) and rest[j].startswith("-"):  # global opts: git -C dir push ...
        j += 2 if rest[j] in ("-C", "-c") else 1
    if j >= len(rest) or rest[j] != "push":
        return False
    for t in rest[j + 1:]:
        if t == "--force" or t.startswith("--force="):
            return True
        if t.startswith("-") and not t.startswith("--") and "f" in t[1:]:
            return True
    return False


def rm_rf_targets_outside_tmp(segment: str):
    """Return the offending targets of an `rm -rf` in this segment, or []."""
    toks = _tokens(segment)
    if "rm" not in toks:
        return []
    after = toks[toks.index("rm") + 1:]
    flags = [t for t in after if t.startswith("-")]
    targets = [t for t in after if not t.startswith("-")]
    recursive = any(f == "--recursive" or (not f.startswith("--") and ("r" in f or "R" in f)) for f in flags)
    force = any(f == "--force" or (not f.startswith("--") and "f" in f) for f in flags)
    if not (recursive and force):
        return []
    job = (os.environ.get("CLAUDE_JOB_DIR") or "").replace("\\", "/").rstrip("/").lower()
    bad = []
    for t in targets:
        low = t.replace("\\", "/").lower()
        if "$claude_job_dir" in low or "${claude_job_dir" in low:
            continue
        if job and os.path.abspath(t).replace("\\", "/").lower().startswith(job):
            continue
        parts = [s for s in low.split("/") if s]
        if any(s in ("tmp", "temp", ".tmp") for s in parts) or low.startswith(("$tmpdir", "$temp", "$tmp")):
            continue
        bad.append(t)
    return bad


def main() -> int:
    try:
        payload = json.loads(sys.stdin.read() or "{}")
        command = (payload.get("tool_input") or {}).get("command") or ""
    except Exception as e:
        print(json.dumps({"systemMessage": f"guard_destructive hook: could not read input ({e}); not enforced"}))
        return 0
    if not command:
        return 0

    for rx, name, why in RULES:
        if rx.search(command):
            sys.stderr.write(f"BLOCKED: `{name}` in this command.\nWhy: {why}\n")
            return 2

    for seg in re.split(r"(?:&&|\|\||[;|\n])", command):
        if forced_push(seg):
            sys.stderr.write(
                "BLOCKED: forced `git push` (--force / -f).\n"
                "Why: rewrites history on a public repo; GitHub Pages serves docs/ from `dev`, and "
                "`main` mirrors it. Whatever was pushed is what someone may have pulled. Push a new "
                "commit instead. `--force-with-lease` is not blocked, but only run it when Kevin asked "
                "for a rewrite.\n"
            )
            return 2
        bad = rm_rf_targets_outside_tmp(seg)
        if bad:
            sys.stderr.write(
                f"BLOCKED: `rm -rf` on {', '.join(bad)} - outside $CLAUDE_JOB_DIR and not a tmp directory.\n"
                "Why: untracked tickets under .scratch/, memory/ and another agent's in-flight files are "
                "not recoverable from git, and app/build belongs to whichever agent is running Gradle "
                "right now. Way around: `rm <one file>`; `./gradlew clean` for build output; or move the "
                "thing into $CLAUDE_JOB_DIR/tmp first and delete it there.\n"
            )
            return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
