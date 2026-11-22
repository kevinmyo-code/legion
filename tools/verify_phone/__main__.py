"""`python tools/verify_phone <list|run|stale>`: see __init__.py."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from verify_phone import runner, stale  # noqa: E402
from verify_phone.suites import SUITES  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python tools/verify_phone", description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="suites and their checks")
    r = sub.add_parser("run", help="run a suite on the connected phone")
    r.add_argument("suite", choices=sorted(SUITES))
    r.add_argument("--dry-run", action="store_true", help="against the in-repo fake phone, no adb")
    r.add_argument("--fake-bugs", default="", help="dry run only: comma list of fake regressions (fake.py)")
    r.add_argument("--only", default="", help="comma list of check ids (version and no_crash always run)")
    s = sub.add_parser("stale", help="checks a diff may have made stale (lists only, exit 0)")
    s.add_argument("--base", default="origin/dev")
    s.add_argument("--head", default="HEAD")
    s.add_argument("--summary", help="also append the listing to this file (CI step summary)")
    a = p.parse_args(argv)

    if a.cmd == "list":
        for name, suite in SUITES.items():
            print(f"{name}: {suite.title} (versionCode {suite.version_code})")
            for c in suite.checks:
                kind = "manual" if c.manual else ("door" if c.door else "phone")
                print(f"  {c.id:<22} {kind:<6} {c.title}")
        return 0

    if a.cmd == "stale":
        checks = [c for suite in SUITES.values() for c in suite.checks]
        try:
            diff = stale.git_diff(a.base, a.head)
        except Exception as e:  # noqa: BLE001 - a listing never fails the build
            print(f"stale: could not diff {a.base}...{a.head}: {e}")
            return 0
        found = stale.flags(checks, diff)
        rot = stale.missing(checks)
        lines = [f"Device checks that may be stale ({a.base}...{a.head}): {len(found)}"]
        lines += [f"- {cid}: {why}" for cid, why in found] or ["- none"]
        if rot:
            lines += ["Map entries that no longer match the tree:"] + [f"- {x}" for x in rot]
        text = "\n".join(lines)
        print(text)
        if a.summary:
            with open(a.summary, "a", encoding="utf-8") as fh:
                fh.write(text + "\n")
        return 0

    suite = SUITES[a.suite]
    if a.dry_run:
        from verify_phone.fake import FakeDevice
        device = FakeDevice(version_code=suite.version_code,
                            bugs={b.strip() for b in a.fake_bugs.split(",") if b.strip()})
    else:
        from verify_phone.device import RealDevice
        try:
            device = RealDevice()
        except runner.Abort as e:
            print(f"cannot start: {e}")
            return 2
    only = {x.strip() for x in a.only.split(",") if x.strip()} or None
    run = runner.run_suite(suite, device, only)
    print(runner.table(run))
    path = runner.write_log(run)
    print(f"log (local only): {path}")
    t = run["totals"]
    return 0 if not (t["fail"] or t["blocked"] or run.get("stopped")) else 1


if __name__ == "__main__":
    sys.exit(main())
