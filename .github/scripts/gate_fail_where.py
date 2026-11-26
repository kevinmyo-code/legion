#!/usr/bin/env python3
"""gate_fail_where.py <pytest output> <test file, as the gate ran it> <server dir>  (v2 slice 3)

WHERE an engine gate step broke, in two lines a public artifact may carry:

    node: tests/realrun_web_cascade.py::test_realrun_gate
    assert_line: 214

and nothing else. Never the assert's text, never the model's words, never a value. A reason-free
`RESULT: FAIL` cost a whole paid run to diagnose, which is what this fixes.

The pytest output is written by the GATED commit's tests, with `-s`, so every line in it is text that
commit chose. So nothing is echoed from it. Only two things are printed, and each is backed by code
that is already public at the gated commit:

  * the test file is the one this gate invoked, passed in by the trusted script, not read out of the
    output;
  * the function name is printed only when `def <name>(` really exists in that file;
  * the line is digits only and no greater than that file's own length.

Anything short of that prints `node: unclassified`. Line numbers are public code; the words on them
are not.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

NAME = re.compile(r"^[a-z][a-z0-9_]{0,60}$")
# pytest's failure header, and the short summary line when one is printed.
HEADER = re.compile(r"^_+ ([A-Za-z0-9_\[\]:.-]+) _+$")
SUMMARY = re.compile(r"^FAILED [^\s:]+::([A-Za-z0-9_\[\]:.-]+)")
AT_LINE = re.compile(r"^([^\s:]+\.py):(\d{1,6}): ")


def where(output: str, test_file: str, source: str) -> list[str]:
    """The `node:` and `assert_line:` lines for this failure, as strict as the docstring says."""
    base = Path(test_file).name
    names = set(re.findall(r"^\s*def ([a-z][a-z0-9_]*)\s*\(", source, re.M))
    found = None
    for line in output.splitlines():
        m = HEADER.match(line.strip()) or SUMMARY.match(line.strip())
        if not m:
            continue
        name = m.group(1).split("[")[0]
        if NAME.match(name) and name in names:
            found = name
            break
    if not found:
        return ["node: unclassified"]
    out = [f"node: {test_file}::{found}"]
    length = len(source.splitlines())
    hit = None
    for line in output.splitlines():
        m = AT_LINE.match(line.strip())
        if m and Path(m.group(1)).name == base and 1 <= int(m.group(2)) <= length:
            hit = int(m.group(2))
    if hit:
        out.append(f"assert_line: {hit}")
    return out


def main(argv: list[str]) -> int:
    out_file, test_file, server = argv[1], argv[2], Path(argv[3])
    output = Path(out_file).read_text(encoding="utf-8", errors="replace")
    source = (server / test_file).read_text(encoding="utf-8", errors="replace")
    print("\n".join(where(output, test_file, source)))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as e:  # noqa: BLE001 - a gate failure is never made worse by this script
        print(f"node: unclassified ({type(e).__name__})")
        sys.exit(0)
