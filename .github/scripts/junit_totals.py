"""Totals from JUnit XML, never from a console summary (CLAUDE.md section 6).

    python3 .github/scripts/junit_totals.py [--known-failures FILE] '<glob>' [<glob> ...]

Prints files/tests/failures/errors/skipped and every failed case, then exits non-zero when:
  - no XML file matched, or the files report zero tests (nothing ran; that is not a pass), or
  - any test failed or errored that is not listed in the known-failures file, or
  - a listed known failure PASSED or vanished (so the list can only shrink, never rot).

A known-failures file holds one `classname::name` per line (`#` comments allowed). It is for
failures that are red on clean dev for a reason outside CI's control, each with its reason and
owner written beside it. The tests still RUN and are still printed; they just do not hide every
other verdict behind one permanent red.

A missing results directory reads exactly like a pass to anything that only checks an exit code,
which is why the empty case is a failure here.
"""

import glob
import sys
import xml.etree.ElementTree as ET


def read_known(path: str) -> set[str]:
    known = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip()
            if line:
                known.add(line)
    return known


def main(patterns: list[str], known: set[str]) -> int:
    files = sorted({p for pat in patterns for p in glob.glob(pat, recursive=True)})
    tests = failures = errors = skipped = 0
    seconds = 0.0
    failed: list[str] = []
    for path in files:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            tests += int(suite.get("tests", 0))
            failures += int(suite.get("failures", 0))
            errors += int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
            seconds += float(suite.get("time", 0) or 0)
            for case in suite.iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failed.append(f"{case.get('classname')}::{case.get('name')}")
    print(
        f"files={len(files)} tests={tests} failures={failures} errors={errors} "
        f"skipped={skipped} test_time_s={seconds:.0f}"
    )
    unexpected = [n for n in failed if n not in known]
    for name in failed:
        print(f"  FAILED {name}" + ("  (known failure, listed)" if name in known else ""))
    if not files or tests == 0:
        print("No test results were written. Nothing ran; that is not a pass.")
        return 1
    stale = sorted(known - set(failed))
    for name in stale:
        print(f"  KNOWN FAILURE DID NOT FAIL: {name} - remove it from the known-failures file.")
    if unexpected:
        print(f"{len(unexpected)} unexpected failure(s) ({failures} failures, {errors} errors in all).")
        return 1
    if stale:
        return 1
    if failed:
        print(f"Only the {len(failed)} listed known failure(s) failed.")
    return 0


if __name__ == "__main__":
    args = sys.argv[1:]
    known: set[str] = set()
    if args[:1] == ["--known-failures"]:
        known = read_known(args[1])
        args = args[2:]
    if not args:
        sys.exit(__doc__)
    sys.exit(main(args, known))
