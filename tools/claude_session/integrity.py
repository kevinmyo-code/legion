"""Is the worker's own code the code that was deployed? (review round 4c, 2026-11-22)

    python integrity.py --write     after each deploy of this folder (the orchestrator's step)
    python integrity.py --check     prints one line, exit 1 on a mismatch

The hook trusts this folder's scripts by path (`asklist_hook._is_our_lone_script`). An untainted
helper has a whole shell, and no rule over command text can stop every way it could change a file
here (a path built at run time, `$HOME`, `Join-Path`). So the trust also rests on this check:
every `.py` in this folder hashes to what the manifest says, and there is no `.py` the manifest
does not list. The worker runs it at start and on every heartbeat (a failure is
`code_intact: false` in its health, which the engine turns into a "needs attention" alert), and
the hook runs it before it lets any contained script run: on a mismatch, none runs.

The manifest lives in the worker's own folder (`<root>/code-manifest.json`, beside its config),
outside this code folder and among the worker files no helper may write. With no manifest the
check fails: nothing proves the code is the deployed code, so nothing is trusted.

Stdlib only. Says which file in its result, never the file's contents.
"""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import Paths, read_json, write_json_atomic  # noqa: E402

HERE = Path(__file__).resolve().parent
MANIFEST = "code-manifest.json"


def digests(folder: Path = HERE) -> dict[str, str]:
    """sha256 of every `.py` directly in [folder], by lower-case file name."""
    out = {}
    for f in sorted(folder.glob("*.py")):
        if f.is_file():
            out[f.name.lower()] = hashlib.sha256(f.read_bytes()).hexdigest()
    return out


def manifest_path(paths: Paths) -> Path:
    return paths.root / MANIFEST


def write_manifest(paths: Paths, folder: Path = HERE) -> Path:
    p = manifest_path(paths)
    write_json_atomic(p, {"folder": str(folder), "files": digests(folder)})
    return p


def check(paths: Paths, folder: Path = HERE) -> tuple[bool, str]:
    """(intact, reason in words). Fails closed: a missing or unreadable manifest, or any read
    error, is not intact."""
    try:
        m = read_json(manifest_path(paths), None)
        if not isinstance(m, dict) or not isinstance(m.get("files"), dict):
            return False, "no code manifest; run integrity.py --write after a deploy"
        want = {str(k).lower(): str(v) for k, v in m["files"].items()}
        have = digests(folder)
    except (OSError, ValueError, TypeError):
        return False, "the code or its manifest could not be read"
    extra = sorted(set(have) - set(want))
    gone = sorted(set(want) - set(have))
    changed = sorted(n for n in set(have) & set(want) if have[n] != want[n])
    if extra:
        return False, f"a script that was not deployed: {extra[0]}"
    if gone:
        return False, f"a deployed script is missing: {gone[0]}"
    if changed:
        return False, f"a script changed since the deploy: {changed[0]}"
    return True, "intact"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="integrity.py", allow_abbrev=False)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--write", action="store_true")
    g.add_argument("--check", action="store_true")
    a = ap.parse_args(argv)
    paths = Paths()
    if a.write:
        print(f"Wrote {write_manifest(paths)} for {len(digests())} scripts.")
        return 0
    ok, why = check(paths)
    print("Worker code intact." if ok else f"Worker code NOT intact: {why}.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
