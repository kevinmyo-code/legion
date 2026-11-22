"""Check (f): the detekt baseline only shrinks (AGENT-RULES: never edit the baseline to pass).

The baseline is YELLOW in the tier script, so a refactor that moves code is not forced to RED. This check
holds the line instead: every `<ID>` the change adds must be a re-key of one it removes, one for one.
A re-key keeps the rule name and the code signature and changes only the file part (a moved or renamed
file): `LongMethod:Old.kt$Foo$fun bar()` -> `LongMethod:New.kt$Foo$fun bar()`.

FAIL: any added ID that is not such a re-key (new debt hidden in the baseline). Shrinking always passes.
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


def ids(text: str | None) -> Counter:
    return Counter(m.strip() for m in ID.findall(text or ""))


def rekey(entry: str) -> tuple[str, str]:
    """(rule, signature without the file part). `Rule:File.kt$sig` or `Rule:File.kt:sig`."""
    rule, _, rest = entry.partition(":")
    m = re.match(r"[^:$]+\.kts?[:$]", rest)
    return rule, rest[m.end():] if m else rest


def check(change: Change) -> list[Finding]:
    if PATH not in change.status or change.status[PATH] == "D":
        return []
    before, after = ids(change.read_base(PATH)), ids(change.read(PATH))
    added, removed = after - before, before - after
    pool = Counter()
    for entry, n in removed.items():
        pool[rekey(entry)] += n
    out = []
    for entry, n in sorted(added.items()):
        for _ in range(n):
            key = rekey(entry)
            if pool[key] > 0:
                pool[key] -= 1
                continue
            out.append(Finding(FAIL, NAME, PATH, 0, f"baseline gained an entry ({key[0]}): fix the finding instead"))
    return out


def count(change: Change) -> int:
    return sum(ids(change.read(PATH)).values())
