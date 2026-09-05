#!/usr/bin/env python3
"""PreToolUse hook (Bash, `git commit*`): list every STAGED `.kt` over the line ceiling.

Warns, never blocks. Kevin ruled 2026-09-05: the ceiling is 1000 lines, and detekt enforces it in
the build. This hook is the early word, at commit time, so the number is seen before Gradle says it.
The build is the gate; this is the courtesy.

Reads `git diff --cached --name-only --diff-filter=ACMR -- '*.kt'` and counts lines in the working
copy of each (the staged content is what the build will see once committed; the two differ only
mid-edit, and the warning names the file either way). Exit 0 always. The list goes to the model as
`additionalContext` and to the user as `systemMessage`. Nothing staged, or nothing over: silent.

The ceiling is one number in one place - CEILING below - and `thermo-review` flags the same
threshold in review. If detekt's `MaxLineLength`/`TooManyFunctions`-style config in the build
names a different number, that config wins and this constant is wrong.

Idea from everything-claude-code's `rules/common/coding-style.md` (MIT, (c) 2026 Affaan Mustafa),
which sets a file-size ceiling (800 lines there) as a rule in prose. Here it is a hook plus a
detekt rule, with LEGION's number. See `.claude/skills/ATTRIBUTION.md`.
"""

import json
import os
import subprocess
import sys

CEILING = 1000


def staged_kotlin() -> list:
    out = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR", "--", "*.kt"],
        capture_output=True, text=True, check=False,
    )
    if out.returncode != 0:
        return []
    return [line.strip() for line in out.stdout.splitlines() if line.strip()]


def count_lines(path: str) -> int:
    try:
        with open(path, "rb") as f:
            return sum(1 for _ in f)
    except OSError:
        return 0


def main() -> int:
    root = os.environ.get("CLAUDE_PROJECT_DIR")
    if root:
        os.chdir(root)
    try:
        sys.stdin.read()  # consume; this hook does not inspect the command beyond the matcher
    except Exception:
        pass
    over = []
    for rel in staged_kotlin():
        n = count_lines(rel)
        if n > CEILING:
            over.append((n, rel))
    if not over:
        return 0
    over.sort(reverse=True)
    lines = "\n".join(f"  {n:>5}  {rel}" for n, rel in over)
    msg = (
        f"LONG KOTLIN staged ({len(over)} file{'s' if len(over) != 1 else ''} over the {CEILING}-line "
        f"ceiling; detekt enforces this in the build, so the commit lands but the build will say so):\n"
        f"{lines}\n"
        "Way around: split by responsibility before the build does it for you, or if the length is "
        "deliberate say so in the commit message and the detekt baseline, never with a bare @Suppress."
    )
    print(json.dumps({
        "hookSpecificOutput": {"hookEventName": "PreToolUse", "additionalContext": msg},
        "systemMessage": f"long kotlin: {len(over)} staged .kt over {CEILING} lines - " + ", ".join(
            f"{rel.rsplit('/', 1)[-1]} ({n})" for n, rel in over),
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
