"""Turn a CSV in the task's work folder into an Excel file beside it (containment, 2026-11-10).

    python to_xlsx.py --task 42 --csv rates.csv [--out rates.xlsx]

After a task reads the web it may not run Python of its own (`containment.py`), but Kevin may
still have asked for Excel (prompt rule 8). The helper writes a CSV with the Write tool and this
script, this folder's own copy, makes the .xlsx. Both files stay in `work/task-<N>/`; nothing is
evaluated (cells are text or numbers, never formulas). Needs openpyxl; without it, it says so and
the CSV stands.

Prints one line in words.
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import containment as C  # noqa: E402
from common import Paths, is_under  # noqa: E402

MAX_ROWS = 100_000


def _cell(v: str):
    s = v.strip()
    if s.startswith(("=", "+", "-", "@")) and not _num(s):
        return "'" + s  # never a formula
    n = _num(s)
    return n if n is not None else v


def _num(s: str):
    try:
        return int(s) if s.lstrip("-").isdigit() else float(s)
    except ValueError:
        return None


def convert(paths: Paths, task: int, src: str, out: str | None) -> Path:
    work = paths.task_work(task)
    s = (work / src) if not Path(src).is_absolute() else Path(src)
    o = Path(out) if out and Path(out).is_absolute() else work / (out or s.with_suffix(".xlsx").name)
    for p in (s, o):
        if not is_under(p, work) or p.parent.resolve() != work.resolve():
            raise ValueError("both files must sit directly in the task's work folder")
    if s.suffix.lower() != ".csv" or o.suffix.lower() != ".xlsx":
        raise ValueError("the input must be a .csv and the output an .xlsx")
    if o.exists():
        raise ValueError("an .xlsx with that name is already there")
    import openpyxl  # noqa: PLC0415 (optional dependency)

    wb = openpyxl.Workbook()
    ws = wb.active
    with open(s, newline="", encoding="utf-8-sig") as f:
        for i, row in enumerate(csv.reader(f)):
            if i >= MAX_ROWS:
                raise ValueError("the CSV has more than 100,000 rows")
            ws.append([_cell(v) for v in row])
    wb.save(o)
    C.record_written(paths, str(o))
    return o


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="to_xlsx.py")
    ap.add_argument("--task", type=int, required=True)
    ap.add_argument("--csv", required=True)
    ap.add_argument("--out")
    a = ap.parse_args(argv)
    try:
        o = convert(Paths(), a.task, a.csv, a.out)
    except ImportError:
        print("Not converted: openpyxl is not installed here, so the CSV is the sheet.")
        return 1
    except (ValueError, OSError) as e:
        print(f"Not converted: {e}.")
        return 1
    print(f"Saved {o.name} in the work folder: {o}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
