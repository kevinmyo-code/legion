"""Is the worker's own code the code that was deployed? (review rounds 4c and 4d, 2026-11-22)

    python integrity.py --write     the DEPLOY step, after each update of this folder; no helper
                                    may run it (the hook refuses any command naming it with --write)
    python integrity.py --check     prints one line, exit 1 on a mismatch

The hook trusts this folder's scripts by path (`asklist_hook._is_our_lone_script`). An untainted
helper has a whole shell, and no rule over command text can stop every way it could change a file
here. So the trust also rests on this check: every FILE under this folder, any extension, at any
depth, hashes to what the manifest says; there is no file the manifest does not list; and there is
no subfolder except `test_fixtures` (committed test data, hashed like the rest), `__pycache__`,
which nothing reads any more (every entry script sets `sys.pycache_prefix` to a folder under the
worker's root before it imports a sibling, round 4d), and folders whose names Python cannot import
(`.ruff_cache`).
A planted package folder, `.pyw`, `.pyc` or `.pth` beside the scripts therefore fails the check.

The worker runs it at start and on every heartbeat (a failure is `code_intact: false` in its
health, which the engine turns into a "needs attention" alert). The hook runs it before it imports
anything from this folder; on a mismatch it refuses every tool call (`asklist_hook._deployed`).

The manifest lives in the worker's own folder (`<root>/code-manifest.json`, beside its config),
outside this code folder and among the worker files no helper may write. With no manifest the
check fails: nothing proves the code is the deployed code, so nothing is trusted.

STDLIB ONLY, and it imports nothing from this folder: the hook loads it by path, after checking
this file's own hash, before it trusts anything else here. Says which file, never its contents.
"""
from __future__ import annotations

# ruff: noqa: E402
# Review round 4d: these lines run before any other import. Python puts this script's own folder
# first on the path, so a file planted here (`json.py`, a `json/` package, `json.pyc`) would
# shadow the standard library: the folder is taken off the front and added back at the END, so
# only this folder's own modules come from it. Bytecode is never read from or written to a
# `__pycache__` here (`sys.pycache_prefix`, a folder under the worker's own root that no helper
# may write), so a forged cached .pyc beside the source is never loaded.
import sys

sys.path[:] = [_p for _p in sys.path if _p.replace("\\", "/").rstrip("/").lower()
               != __file__.replace("\\", "/").rsplit("/", 1)[0].lower()]
import os

sys.pycache_prefix = os.path.join(os.environ.get("LEGION_CLAUDE_HOME") or os.path.join(
    os.environ.get("APPDATA") or os.path.expanduser("~/AppData/Roaming"), "legion-claude"),
    "pycache")
sys.path.append(os.path.dirname(os.path.abspath(__file__)))  # back at the END: never ahead of stdlib

import argparse
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
MANIFEST = "code-manifest.json"
CACHE_DIR = "__pycache__"
FIXTURES = "test_fixtures"  # the one committed subfolder (test data only)


def worker_root() -> Path:
    """The worker's root, as `common.home()` finds it (kept here so this file needs no sibling)."""
    env = os.environ.get("LEGION_CLAUDE_HOME")
    if env:
        return Path(env)
    appdata = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
    return Path(appdata) / "legion-claude"


def scan(folder: Path = HERE) -> tuple[dict[str, str], list[str]]:
    """(sha256 of every file under [folder] by relative path, every unexpected subfolder).
    `__pycache__` folders are skipped: nothing reads them (see the module docstring)."""
    files: dict[str, str] = {}
    odd: list[str] = []
    for dirpath, dirnames, filenames in os.walk(folder):
        rel_dir = Path(dirpath).relative_to(folder)
        keep = []
        for d in sorted(dirnames):
            rel = (rel_dir / d).as_posix()
            if d == CACHE_DIR or not d.isidentifier():
                # Python imports a folder only as a package by an identifier name, so a tool cache
                # such as `.ruff_cache` can hold nothing an import reaches.
                continue
            if rel == FIXTURES or rel.startswith(FIXTURES + "/"):
                keep.append(d)  # the committed test data, hashed file by file like the rest
            else:
                odd.append(rel)
        dirnames[:] = keep  # an unexpected subfolder is already a failure; nothing below is read
        for f in sorted(filenames):
            p = Path(dirpath) / f
            files[(rel_dir / f).as_posix()] = hashlib.sha256(p.read_bytes()).hexdigest()
    return files, odd


def manifest_path(paths=None) -> Path:
    """`<root>/code-manifest.json`; [paths] is a `common.Paths` (anything with `.root`)."""
    return (Path(paths.root) if paths is not None else worker_root()) / MANIFEST


def write_manifest(paths=None, folder: Path = HERE) -> Path:
    files, odd = scan(folder)
    if odd:
        raise ValueError(f"the code folder has a subfolder: {odd[0]}")
    p = manifest_path(paths)
    p.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=p.parent, prefix=".manifest-")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump({"folder": str(folder), "files": files}, f, indent=1)
    os.replace(tmp, p)
    return p


def check(paths=None, folder: Path = HERE) -> tuple[bool, str]:
    """(intact, reason in words). Fails closed: a missing or unreadable manifest, or any read
    error, is not intact."""
    try:
        with open(manifest_path(paths), encoding="utf-8") as f:
            m = json.load(f)
        if not isinstance(m, dict) or not isinstance(m.get("files"), dict):
            return False, "no code manifest; run integrity.py --write in the deploy step"
        want = {str(k): str(v) for k, v in m["files"].items()}
        have, odd = scan(folder)
    except FileNotFoundError:
        return False, "no code manifest; run integrity.py --write in the deploy step"
    except (OSError, ValueError, TypeError):
        return False, "the code or its manifest could not be read"
    if odd:
        return False, f"a folder that was not deployed: {odd[0]}"
    extra = sorted(set(have) - set(want))
    gone = sorted(set(want) - set(have))
    changed = sorted(n for n in set(have) & set(want) if have[n] != want[n])
    if extra:
        return False, f"a file that was not deployed: {extra[0]}"
    if gone:
        return False, f"a deployed file is missing: {gone[0]}"
    if changed:
        return False, f"a file changed since the deploy: {changed[0]}"
    return True, "intact"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="integrity.py", allow_abbrev=False)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--write", action="store_true")
    g.add_argument("--check", action="store_true")
    a = ap.parse_args(argv)
    if a.write:
        try:
            p = write_manifest()
        except ValueError as e:
            print(f"Not written: {e}. Remove it, then write again.")
            return 1
        print(f"Wrote {p} for {len(scan()[0])} files.")
        return 0
    ok, why = check()
    print("Worker code intact." if ok else f"Worker code NOT intact: {why}.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
