#!/usr/bin/env python3
"""PreToolUse hook (Edit|Write): refuse hand edits to generated files.

CLAUDE.md sections 12 and 13 say "never hand-edit" the board, the wiki, the ADR index and the
canvases, and section 5 says the same of the Room schema JSON. Nothing enforced it. A hand edit to
any of these is overwritten by the next generator run (the commit hook runs them), so it never
reaches the page, and in the meantime the file disagrees with its source.

Reads the hook's stdin JSON and looks at `tool_input.file_path`. On a match it prints the reason
and the way around to stderr and exits 2, which Claude Code treats as "block, and show the model
the message". Anything else, including a hook bug, exits 0: this guard fails open, never closed.

README.md is a special case. Only the block between `<!-- VOICE-SURFACE:START -->` and
`<!-- VOICE-SURFACE:END -->` is generated, and a path-level hook cannot see which lines an edit
touches, so README gets a warning (exit 0, additionalContext), not a block.

Idea from everything-claude-code's `scripts/hooks/config-protection.js` (MIT, (c) 2026 Affaan
Mustafa), which blocks edits to linter configs with exit 2. Re-authored in Python; the path list
and every message are LEGION's. See `.claude/skills/ATTRIBUTION.md`.

Try it by hand:
    echo '{"tool_input":{"file_path":"docs/index.html"}}' | python tools/hooks/guard_generated.py
"""

import fnmatch
import json
import os
import sys

# (glob on the repo-relative posix path, generator, what to edit instead)
GENERATED = [
    ("docs/index.html", "python tools/pending_wiki.py",
     "the ticket's YAML frontmatter under .scratch/*/issues/"),
    ("docs/board.json", "python tools/pending_wiki.py",
     "the ticket's YAML frontmatter under .scratch/*/issues/"),
    ("docs/voice.html", "python tools/voice_guide.py",
     "tools/voice_guide_copy.py (user-facing copy) or service/LiveToolbox.kt (the tool itself)"),
    ("docs/devlog.html", "python tools/devlog.py",
     "nothing - it is built from git log; the commit message is the source"),
    ("docs/adr/adr-index.md", "python tools/obsidian_sync.py",
     "the ADR's own frontmatter in docs/adr/*.md"),
    ("vault/Board.md", "python tools/obsidian_sync.py",
     "the ticket's YAML frontmatter under .scratch/*/issues/"),
    ("*.canvas", "python tools/obsidian_sync.py",
     "the ticket's blocked_by frontmatter; the canvas is the dependency graph drawn from it"),
    ("app/schemas/*.json", "kapt, on the next Gradle build after the Room change",
     "the @Entity / @Database Kotlin; then copy the generated createSql VERBATIM into Migrations.kt "
     "(CLAUDE.md section 5). A hand-edited schema JSON lies to the migration test"),
    ("app/src/main/java/com/kevin/legion/ui/help/VoiceGuideData.kt", "python tools/voice_guide.py",
     "tools/voice_guide_copy.py; voice_guide.py --check fails the build if this file drifts"),
]

README_WARNING = (
    "README.md carries a GENERATED block between <!-- VOICE-SURFACE:START --> and "
    "<!-- VOICE-SURFACE:END -->, rewritten by python tools/voice_guide.py. Edit anywhere else "
    "freely. Do not edit inside the markers; change tools/voice_guide_copy.py and run the script."
)


def repo_relative(path: str) -> str:
    """Posix, repo-relative where possible. A path outside the repo comes back as given."""
    p = path.replace("\\", "/")
    root = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    root = os.path.abspath(root).replace("\\", "/").rstrip("/") + "/"
    ap = os.path.abspath(p).replace("\\", "/") if os.path.isabs(p) else p
    if ap.lower().startswith(root.lower()):
        return ap[len(root):]
    return p[2:] if p.startswith("./") else p


def match(rel: str):
    for pattern, generator, source in GENERATED:
        if fnmatch.fnmatch(rel, pattern) or (
            "/" not in pattern and fnmatch.fnmatch(os.path.basename(rel), pattern)
        ):
            return pattern, generator, source
    return None


def main() -> int:
    try:
        payload = json.loads(sys.stdin.read() or "{}")
        file_path = (payload.get("tool_input") or {}).get("file_path") or ""
    except Exception as e:  # hook bug: fail open, say so
        print(json.dumps({"systemMessage": f"guard_generated hook: could not read input ({e}); not enforced"}))
        return 0
    if not file_path:
        return 0

    rel = repo_relative(file_path)
    hit = match(rel)
    if hit:
        pattern, generator, source = hit
        sys.stderr.write(
            f"BLOCKED: {rel} is a GENERATED file (matched {pattern}). Hand edits are overwritten "
            f"by the next generator run and never reach the page.\n"
            f"Generator: {generator}\n"
            f"Way around: edit {source}, then run the generator; the file follows. "
            f"If the generator itself is wrong, fix the generator (tools/*.py) and rerun it. "
            f"CLAUDE.md sections 5, 12, 13.\n"
        )
        return 2

    if rel == "README.md":
        print(json.dumps({
            "hookSpecificOutput": {"hookEventName": "PreToolUse", "additionalContext": README_WARNING},
            "systemMessage": "README.md: the VOICE-SURFACE block is generated (tools/voice_guide.py)",
        }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
