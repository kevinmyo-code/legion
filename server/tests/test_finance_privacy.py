"""The repo is public: no real household figure in finance code or tests
(household-finance ticket 12, ADR 0073). Layer one of two.

This test cannot know the real figures; it holds every finance file to the
synthetic household's (`tests/finance_synthetic.py`). Every integer literal,
every four-or-more-digit run in a string, and every figure in the template
file must be one the synthetic household declares, a year, or small. So a
figure typed in from anywhere else fails here, naming the file and line, and
the fix is to use a synthetic one.

Layer two is `tools/finance_privacy_check.py <private file>`, run by hand before
a push: it reads the private file and searches the branch for its actual
figures, names and account numbers. Adding a real figure to `FIGURES` below
would pass this layer and fail that one.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

from tests.finance_synthetic import FIGURES, LAST4S, RATE_A, RATE_B

SERVER = Path(__file__).resolve().parents[1]

# Constants that are not money: micros, rates, HTTP statuses, day counts.
NOT_MONEY = {1_000_000, RATE_A, RATE_B, 200, 400, 404, 365, 366}


def _files() -> list[Path]:
    finance = [
        p for p in (SERVER / "finance").rglob("*")
        if p.suffix in {".py", ".md"} and "migrations" not in p.parts and p.is_file()
    ]
    tests = [SERVER / "tests" / "finance_synthetic.py"]
    # This file is left out: it holds deliberate non-synthetic examples below.
    tests += sorted(
        p for p in (SERVER / "tests").glob("test_finance*.py") if p.name != Path(__file__).name
    )
    return sorted(finance) + tests


def _allowed_int(value: int) -> bool:
    value = abs(value)
    return (
        value < 100
        or value in FIGURES
        or value in NOT_MONEY
        or 2020 <= value <= 2030
        or f"{value:04d}" in LAST4S
    )


def _allowed_digits(run: str) -> bool:
    """A run of 4+ digits inside a string: a year, a synthetic account number,
    or a synthetic figure in cents or in major units."""
    # A year, an ADR number (`ADR 0073`), or a synthetic account number.
    if re.fullmatch(r"20[23]\d|0\d{3}", run) or run in LAST4S:
        return True
    value = int(run)
    return value in FIGURES or value * 100 in FIGURES or value in NOT_MONEY


def _python_problems(path: Path) -> list[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    out = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Constant):
            continue
        if isinstance(node.value, int) and not isinstance(node.value, bool):
            if not _allowed_int(node.value):
                out.append(f"{path.name}:{node.lineno}: the number {node.value}")
        elif isinstance(node.value, str):
            for run in re.findall(r"\d[\d,_]{3,}\d|\d{4,}", node.value):
                digits = re.sub(r"[,_]", "", run)
                if len(digits) >= 4 and not _allowed_digits(digits):
                    out.append(f"{path.name}:{node.lineno}: the digits {run!r} in a string")
    return out


def _markdown_problems(path: Path) -> list[str]:
    out = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        for raw in re.findall(r"\d+(?:\.\d+)?", line):
            if "." in raw:
                value_cents = round(float(raw) * 100)
                if value_cents not in FIGURES:
                    out.append(f"{path.name}:{lineno}: the figure {raw}")
                continue
            value = int(raw)
            if value <= 31 or re.fullmatch(r"20[23]\d|0\d{3}", raw) or raw in LAST4S:
                continue
            if value * 100 not in FIGURES:
                out.append(f"{path.name}:{lineno}: the figure {raw}")
    return out


def test_every_finance_file_uses_only_the_synthetic_households_figures():
    problems = []
    for path in _files():
        if path.suffix == ".py":
            problems += _python_problems(path)
        else:
            problems += _markdown_problems(path)
    assert not problems, (
        "These are not the synthetic household's figures (tests/finance_synthetic.py). "
        "Use one of its values; never a real one:\n" + "\n".join(problems)
    )


def test_the_guard_covers_the_template_and_the_tests():
    names = {p.name for p in _files()}
    assert {"example_household.md", "loader.py", "finance_synthetic.py"} <= names
    assert any(n.startswith("test_finance") for n in names)


def test_the_guard_would_catch_a_stray_figure():
    assert not _allowed_int(123_456)
    assert not _allowed_digits("4321")
    assert _allowed_int(50_000) and _allowed_digits("2026") and _allowed_digits("7823")
