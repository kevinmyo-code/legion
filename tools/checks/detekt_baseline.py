"""Check (f): the detekt baseline only shrinks or is re-keyed (AGENT-RULES: never edit the baseline to pass).

The baseline is YELLOW in the tier script, so a refactor that moves code is not forced to RED. This check
holds the line instead. An entry is `Rule:File.kt$Owner$text`; its FINDING is (rule, text), with the file
and the owner (object or class) stripped, because those are what a move changes.

The text also drops the visibility modifier and names of the objects in the Kotlin files this change
touches, so `private fun f` moved to `internal fun f`, or `LiveToolbox.X` now written `PlacesToolbox.X`,
is the same finding.

Per finding, compare how many baseline entries carry it before and after:
  - not more: passes (a shrink, or a plain re-key: same finding, new file or object, one for one);
  - more (a SPLIT key: one finding now listed under several files, as when one file's code is split into
    new files and detekt lists the same text once per file): passes only for added entries that sit in a
    file this change ADDS, and only when the base listed that finding under a file this change modifies
    or deletes (the file the code moved out of);
  - anything else fails: a finding the base never listed, or a finding listed more often with no new file
    to account for it.
A class-level finding (LargeClass, TooManyFunctions), whose text is the owner's name, becomes "<moved>"
for every touched file; it gets no split allowance, only a one-for-one re-key.
Accepted limits, which the reviewer reads in the moved code: the baseline keeps one entry per text per
file, not a hit count, so the same text in a new file split from a changed one passes (a generic text such
as `MagicNumber 10` most of all); and a text differing only by a touched object's name (`fun f(): Foo`
now `fun f(): Bar`) reads as the same finding.
--all: nothing to compare; reports the entry count.
"""

from __future__ import annotations

import re
from collections import Counter

from common import FAIL, Finding
from gitdiff import Change

NAME = "detekt-baseline"
PATH = "config/detekt/baseline.xml"
ID = re.compile(r"<ID>(.*?)</ID>", re.S)
FILE_PART = re.compile(r"([^:$/]+\.kts?)[:$]")
OWNER_PART = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*\$")
VISIBILITY = re.compile(r"\b(?:private|internal|protected|public)\s+")


def ids(text: str | None) -> Counter:
    return Counter(m.strip() for m in ID.findall(text or ""))


def parse(entry: str) -> tuple[str, str, str]:
    """(rule, file name or '', finding text). `Rule:File.kt$Owner$text`, `Rule:File.kt:text` and
    `Rule:File.kt$text` all parse; the owner is dropped from the text."""
    rule, _, rest = entry.partition(":")
    file = ""
    m = FILE_PART.match(rest)
    if m:
        file, rest = m.group(1), rest[m.end():]
    m = OWNER_PART.match(rest)
    if m:
        rest = rest[m.end():]
    return rule, file, rest


def finding(entry: str, moved: frozenset[str] = frozenset()) -> tuple[str, str]:
    """(rule, text) with what a move changes taken out: the visibility modifier (a moved function often
    goes from private to internal) and any reference to a moved object by name (`LiveToolbox.X` becomes
    `PlacesToolbox.X` when X moves). `moved`: the object names of the Kotlin files this change touches."""
    rule, _, text = parse(entry)
    text = VISIBILITY.sub("", text)
    if moved:
        text = re.sub(r"\b(?:" + "|".join(sorted(map(re.escape, moved))) + r")\b", "<moved>", text)
    return rule, text


def _names(change: Change, letters: str) -> set[str]:
    return {p.rsplit("/", 1)[-1] for p, s in change.status.items() if s in letters and p.endswith((".kt", ".kts"))}


def check(change: Change) -> list[Finding]:
    if PATH not in change.status or change.status[PATH] == "D":
        return []
    before, after = ids(change.read_base(PATH)), ids(change.read(PATH))
    added_files, source_files = _names(change, "A"), _names(change, "MD")
    moved = frozenset(n.rsplit(".", 1)[0] for n in added_files | source_files)

    def key_of(entry: str) -> tuple[str, str]:
        return finding(entry, moved)

    count_before, count_after = Counter(), Counter()
    for entry, n in before.items():
        count_before[key_of(entry)] += n
    for entry, n in after.items():
        count_after[key_of(entry)] += n
    sources = {}  # finding -> files the base listed it under
    for entry in before:
        sources.setdefault(key_of(entry), set()).add(parse(entry)[1])
    added_entries = after - before

    out = []
    for key in sorted(count_after):
        growth = count_after[key] - count_before[key]
        if growth <= 0:
            continue
        rule = key[0]
        if key not in count_before:
            out.append(Finding(FAIL, NAME, PATH, 0, f"baseline gained a new finding ({rule}): fix it instead"))
            continue
        in_new_files = sum(n for e, n in added_entries.items() if key_of(e) == key and parse(e)[1] in added_files)
        moved_from = sources.get(key, set()) & source_files
        # A class-level finding (LargeClass, TooManyFunctions: the text IS the owner name) collapses to
        # "<moved>" for every touched file, so it may only re-key one for one, never split.
        allowed = min(growth, in_new_files) if moved_from and key[1] != "<moved>" else 0
        for _ in range(growth - allowed):
            out.append(Finding(FAIL, NAME, PATH, 0,
                               f"baseline lists a finding ({rule}) more often with no new file split from a"
                               " changed one to account for it: fix it instead"))
    return out


def count(change: Change) -> int:
    return sum(ids(change.read(PATH)).values())
