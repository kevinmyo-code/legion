"""Check (d): no em dashes in user-facing copy (Kevin's style rule: no em dashes, ever).

Matches the character itself, `&#8212;`, `&#x2014;`, `&mdash;` and the escape `\\u2014`. Messages name
the dash in words: printing the character crashes a cp1252 Windows console.

Areas, and what each does in gate mode (--base, added lines only):
  FAIL  phone and head unit strings.xml (pure copy; XML comments skipped);
  FAIL  copy modules (COPY_FILES: the wording files the web, push and docs generators read);
  WARN  web app source (server/frontend/src, tests skipped): the code part of a line, so a code string
        and user copy cannot be told apart; a dash here is almost always copy, but not provably;
  WARN  phone and head unit Kotlin (app/src/main/java, headunit/app/src/main/java): same reason;
  WARN  the /try demo's engine code (server/demo), which carries spoken and shown wording.
Not checkable here: the /try page content itself lives outside git (loaded into the engine database).
--all counts every area over the whole tree, never fails.
"""

from __future__ import annotations

import re

from common import FAIL, WARN, Finding, matches, split_comment
from gitdiff import Change

NAME = "em-dashes"
DASH = re.compile("—|&#8212;|&#x2014;|&mdash;|\\\\u2014", re.IGNORECASE)

STRINGS_XML = ("*/res/values/strings.xml", "*/res/values-*/strings.xml")
COPY_FILES = (
    "server/frontend/src/lib/home-copy.ts",
    "server/push/copy.py",
    "tools/voice_guide_copy.py",
    "tools/screen_map_copy.py",
)
TESTS = ("*.test.ts", "*.test.tsx", "*/test/*", "*/tests/*", "*/__tests__/*")
# (area name, level, path globs). First match wins.
AREAS: list[tuple[str, str, tuple[str, ...]]] = [
    ("strings.xml", FAIL, STRINGS_XML),
    ("copy modules", FAIL, COPY_FILES),
    ("web app source", WARN, ("server/frontend/src/*.ts", "server/frontend/src/*.tsx")),
    ("phone and head unit Kotlin", WARN, ("app/src/main/java/*.kt", "headunit/app/src/main/java/*.kt")),
    ("/try demo engine code", WARN, ("server/demo/*.py",)),
]
XML_COMMENT = re.compile(r"<!--.*?(-->|$)")


def area_of(path: str) -> tuple[str, str] | None:
    if matches(path, TESTS):
        return None
    for name, level, globs in AREAS:
        if matches(path, globs):
            return name, level
    return None


def copy_part(path: str, line: str) -> str:
    """The part of a line that can be copy: XML minus comments; code minus its comment."""
    if path.endswith(".xml"):
        return XML_COMMENT.sub("", line)
    return split_comment(path, line)[0]


def check(change: Change) -> list[Finding]:
    out = []
    for path, letter in sorted(change.status.items()):
        if letter == "D":
            continue
        area = area_of(path)
        if area is None:
            continue
        name, level = area
        for no, text in change.added.get(path, []):
            n = len(DASH.findall(copy_part(path, text)))
            if n:
                out.append(Finding(level, NAME, path, no, f"em dash in user-facing copy ({name}); use a colon, comma or full stop"))
    return out
