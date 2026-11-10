"""Totals from JUnit XML, never from a console summary (CLAUDE.md section 6).

    python3 .github/scripts/junit_totals.py '<glob>' [<glob> ...]

Prints files/tests/failures/errors/skipped and every failed case, then exits non-zero when:
  - no XML file matched, or the files report zero tests (nothing ran; that is not a pass), or
  - any test failed or errored.
A missing results directory reads exactly like a pass to anything that only checks an exit code,
which is why the empty case is a failure here.
"""

import glob
import sys
import xml.etree.ElementTree as ET


def main(patterns: list[str]) -> int:
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
    for name in failed:
        print(f"  FAILED {name}")
    if not files or tests == 0:
        print("No test results were written. Nothing ran; that is not a pass.")
        return 1
    if failures or errors:
        print(f"{failures} failures, {errors} errors.")
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1:]))
