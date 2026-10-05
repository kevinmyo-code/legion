"""Generate `shared_clauses.py` from `shared_clauses.txt` (web-assistant ticket 03).

    python server/assistant/gen_shared_clauses.py          # write
    python server/assistant/gen_shared_clauses.py --check  # exit 1 if the copy is stale

Stand-alone on purpose: no Django, no third-party import, so it runs from a
bare checkout the way `tools/voice_guide.py` does. `tests/test_assistant_prompt.py`
calls `render()` and compares it to the committed file, so a stale copy fails
the suite as well as this script.

The day the Kotlin side is rewired (Android typed chat, ticket 09's sibling
work) this script grows a second emitter for `AriaBrain.kt`'s constants; the
parse below is already the one both would share.
"""

from __future__ import annotations

import sys
import textwrap
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "shared_clauses.txt"
OUTPUT = HERE / "shared_clauses.py"
CLIENTS = ("android", "web")
WRAP = 88


class SourceError(Exception):
    pass


@dataclass(frozen=True)
class Clause:
    name: str
    clients: tuple[str, ...]
    # (Kotlin val name, "whole" | "within"), or None when there is no twin yet.
    kotlin: tuple[str, str] | None
    text: str


def parse(source: str) -> list[Clause]:
    clauses: list[Clause] = []
    name = None
    meta: dict[str, str] = {}
    body: list[str] = []
    in_body = False

    def close():
        if name is None:
            return
        words = " ".join(line for line in body if line)
        if not words:
            raise SourceError(f"[{name}] has no body")
        if "  " in words:
            raise SourceError(f"[{name}] has a double space; clauses join with exactly one")
        for key in ("clients", "kotlin"):
            if key not in meta:
                raise SourceError(f"[{name}] has no `{key}:` line")
        clients = tuple(c.strip() for c in meta["clients"].split(",") if c.strip())
        unknown = set(clients) - set(CLIENTS)
        if not clients or unknown:
            raise SourceError(f"[{name}] clients must be some of {CLIENTS}, not {clients}")
        kotlin_raw = meta["kotlin"].split()
        if kotlin_raw == ["none"]:
            kotlin = None
        elif len(kotlin_raw) == 2 and kotlin_raw[1] in ("whole", "within"):
            kotlin = (kotlin_raw[0], kotlin_raw[1])
        else:
            raise SourceError(f"[{name}] kotlin must be `VAL whole`, `VAL within` or `none`")
        if any(c.name == name for c in clauses):
            raise SourceError(f"[{name}] appears twice")
        clauses.append(Clause(name, clients, kotlin, words + " "))

    for raw in source.splitlines():
        line = raw.strip()
        if raw.startswith("#"):
            continue
        if raw.startswith("["):
            close()
            if not (line.endswith("]") and line[1:-1].isidentifier() and line[1:-1].isupper()):
                raise SourceError(f"bad clause header {line!r}: use [UPPER_SNAKE]")
            name, meta, body, in_body = line[1:-1], {}, [], False
            continue
        if name is None:
            if line:
                raise SourceError(f"text before the first clause: {line!r}")
            continue
        if not in_body:
            if not line:
                in_body = bool(meta)
                continue
            key, sep, value = line.partition(":")
            if not sep or key not in ("clients", "kotlin"):
                raise SourceError(f"[{name}] expected `clients:` or `kotlin:`, got {line!r}")
            meta[key] = value.strip()
            continue
        body.append(line)
    close()
    if not clauses:
        raise SourceError("no clauses")
    return clauses


def _literal(piece: str) -> str:
    return '"' + piece.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _constant(clause: Clause) -> str:
    pieces = textwrap.wrap(clause.text, WRAP, break_on_hyphens=False, break_long_words=False)
    if " ".join(pieces) + " " != clause.text:
        raise SourceError(f"[{clause.name}] does not survive wrapping; check its spacing")
    lines = [f"    {_literal(p + ' ')}" for p in pieces]
    return f"{clause.name} = (\n" + "\n".join(lines) + "\n)\n"


def render(source: str) -> str:
    clauses = parse(source)
    out = [
        '"""GENERATED from `shared_clauses.txt` by `gen_shared_clauses.py`. Do not edit.',
        "",
        "Edit the .txt and re-run the generator; `tests/test_assistant_prompt.py` fails",
        "on a stale copy, and on a clause whose Kotlin twin in `ai/AriaBrain.kt` drifted.",
        '"""',
        "",
        "# fmt: off",
        "",
    ]
    for clause in clauses:
        out.append(_constant(clause))
    out.append("CLAUSES: dict[str, str] = {")
    out.extend(f"    {_literal(c.name)}: {c.name}," for c in clauses)
    out.append("}")
    out.append("")
    out.append("# Which assistants say each clause.")
    out.append("CLIENTS: dict[str, tuple[str, ...]] = {")
    for c in clauses:
        listed = ", ".join(_literal(x) for x in c.clients)
        out.append(f"    {_literal(c.name)}: ({listed},),")
    out.append("}")
    out.append("")
    out.append("# The Kotlin val each clause must match, and how: (val, 'whole' | 'within').")
    out.append("KOTLIN: dict[str, tuple[str, str] | None] = {")
    for c in clauses:
        twin = "None" if c.kotlin is None else f"({_literal(c.kotlin[0])}, {_literal(c.kotlin[1])})"
        out.append(f"    {_literal(c.name)}: {twin},")
    out.append("}")
    out.append("")
    out.append("# The web assistant's shared clauses, in source order.")
    web = [c.name for c in clauses if "web" in c.clients]
    out.append("WEB_ORDER: tuple[str, ...] = (")
    out.extend(f"    {_literal(name)}," for name in web)
    out.append(")")
    return "\n".join(out) + "\n"


def main(argv: list[str]) -> int:
    rendered = render(SOURCE.read_text(encoding="utf-8"))
    current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else None
    if "--check" in argv:
        if current != rendered:
            print(
                f"{OUTPUT.name} is stale against {SOURCE.name}. "
                f"Run: python server/assistant/gen_shared_clauses.py"
            )
            return 1
        print(f"{OUTPUT.name} matches {SOURCE.name}.")
        return 0
    if current == rendered:
        print(f"{OUTPUT.name} already up to date.")
        return 0
    OUTPUT.write_text(rendered, encoding="utf-8", newline="\n")
    print(f"Wrote {OUTPUT.name}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
