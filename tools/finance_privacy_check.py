"""Does this branch carry anything from the household's PRIVATE finance file?

    python tools/finance_privacy_check.py <private file> [--base dev]

household-finance ticket 12, ADR 0073, layer two of two. The repo is public; the
household's figures, names, account numbers and employer live only in a private
file outside it. Layer one is `server/tests/test_finance_privacy.py`, which holds
every finance file to the synthetic household's values. It cannot know the real
ones. This can: it reads the private file, and searches every file this branch
changed (against `--base`) and every commit message on it for:

- any four-digit account number the file names;
- any money figure the file states (as written, without commas, and as cents);
- any capitalised word in the file's account labels and income lines that is
  not a generic banking word (an employer, a building, a product name).

**It never prints what it found**, only where and what kind, so its own output
is safe to paste. Exit 0 when clean, 1 otherwise. Run it before every push of
finance work.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Words already public in this repo (the map, CLAUDE.md) or that name a kind of
# thing rather than this household. Lower-cased. Everything else capitalised in
# the file's account labels and income lines is searched for.
GENERIC = {
    "account", "accounts", "bank", "card", "checking", "savings", "credit", "joint", "liquid",
    "debt", "investments", "property", "other", "plaid", "statement", "computed", "manual",
    "monthly", "rent", "loan", "flat", "mortgage", "history", "fallback", "income", "usd",
    "sgd", "bofa", "dbs", "citibank", "cpf", "hdb", "ibkr", "etf", "zelle", "kevin", "wife",
    "singapore", "bonds", "brokers", "interactive", "headline", "test", "household",
    "budgets", "lease", "alfred", "every", "layer", "owner", "source", "ccy", "key",
    "only", "rules", "test:",
}


def _is_round(value: float) -> bool:
    """200, 500, 3000: a figure any invented household also uses, so a match on
    it says nothing. A figure like 1234.56 is not round and is searched."""
    return value == int(value) and (value % 1000 == 0 or (value < 1000 and value % 100 == 0))


def _private_tokens(text: str) -> dict[str, set[str]]:
    last4s, figures, words = set(), set(), set()
    number = r"(?<![\d.])\d{1,3}(?:,\d{3})+(?:\.\d+)?|(?<![\d.])\d+(?:\.\d+)?"
    for match in re.finditer(number, text):
        raw = match.group(0)
        plain = raw.replace(",", "")
        value = float(plain)
        if re.fullmatch(r"(19|20)\d\d", plain) or value < 100 or _is_round(value):
            continue
        figures.add(raw)
        figures.add(plain)
        cents = round(value * 100)
        figures.add(str(cents))
        figures.add(f"{cents:_}")
    for line in text.splitlines():
        if line.startswith("|"):
            for token in re.findall(r"(?<!\d)\d{4}(?!\d)", line):
                if not re.fullmatch(r"(19|20)\d\d", token):
                    last4s.add(token)
        if line.startswith("|") or line.lstrip().startswith("-"):
            for word in re.findall(r"[A-Z][A-Za-z'.-]{3,}", line):
                if word.lower().strip(".'") not in GENERIC and word.lower() not in GENERIC:
                    words.add(word.strip(".'"))
    figures -= last4s
    return {"account number": last4s, "figure": figures, "name": words}


def _git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout


def _changed_files(base: str) -> list[Path]:
    names = set(_git("diff", "--name-only", f"{base}...HEAD").split())
    names |= set(_git("diff", "--name-only").split())
    names |= set(_git("ls-files", "--others", "--exclude-standard").split())
    # Generated from the code this already searches; their own numbers are noise.
    generated = {"server/openapi.yaml", "server/frontend/src/api/schema.d.ts"}
    return [ROOT / n for n in sorted(names - generated) if (ROOT / n).is_file()]


def _hits(text: str, tokens: dict[str, set[str]]) -> list[str]:
    found = []
    for kind, values in tokens.items():
        for value in values:
            if kind == "name":
                pattern = rf"\b{re.escape(value)}\b"
            else:
                pattern = rf"(?<![\d.,_]){re.escape(value)}(?![\d_]|[.,]\d)"
            if re.search(pattern, text):
                found.append(kind)
    return sorted(set(found))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("private_file", type=Path)
    parser.add_argument("--base", default="dev")
    args = parser.parse_args()
    tokens = _private_tokens(args.private_file.read_text(encoding="utf-8"))
    problems = []
    for path in _changed_files(args.base):
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for kind in _hits(text, tokens):
            problems.append(f"{path.relative_to(ROOT).as_posix()}: a {kind} from the file")
    for kind in _hits(_git("log", "--format=%B", f"{args.base}..HEAD"), tokens):
        problems.append(f"commit messages on this branch: a {kind} from the file")
    searched = sum(len(v) for v in tokens.values())
    if problems:
        print(f"Possible matches ({len(problems)}), checked against {searched} private tokens:")
        for line in problems:
            print(f"  {line}")
        print("Each is a place to look by hand; none is printed here.")
        return 1
    print(
        f"Clean: no account number, figure or name from the private file in "
        f"{len(_changed_files(args.base))} changed files or this branch's commit messages."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
