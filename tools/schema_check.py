"""Fail when the vendored OpenAPI schema no longer matches the server that produces it.

`openapi/legion-schema.yaml` is the contract three limbs generate their models from: this phone,
the head unit (MIDNIGHT_AI repo), and the PWA later. It is a COMMITTED artifact rather than a
build-time fetch, because a build that reaches the network to learn its own types is not
clone-and-run, and because a reviewer should be able to diff the contract in a pull request.

The cost of committing it is that it can go stale, and a stale schema is worse than no schema: the
generated models still compile, so a field the server renamed simply arrives as null forever. That
is the same silent-drift failure `tools/docs_check.py` and `tools/voice_guide.py` already exist to
turn into a hard error, and this is the same posture pointed at the API surface.

    python tools/schema_check.py            # regenerate, compare, fail on drift
    python tools/schema_check.py --write     # regenerate and UPDATE the vendored copy

Exit codes: 0 matches, 1 drifted (or --write updated it), 2 could not check.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
VENDORED = ROOT / "openapi" / "legion-schema.yaml"
SERVER = ROOT / "server"


def _python() -> str | None:
    """The server's own interpreter. A system python almost never has Django installed."""
    for candidate in (
        SERVER / ".venv" / "Scripts" / "python.exe",   # Windows
        SERVER / ".venv" / "bin" / "python",           # POSIX
    ):
        if candidate.exists():
            return str(candidate)
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="update the vendored copy in place")
    args = parser.parse_args()

    if not VENDORED.exists():
        print(f"NOT CHECKED: {VENDORED} does not exist.", file=sys.stderr)
        return 2

    interpreter = _python()
    if interpreter is None:
        # Not a failure. A machine with no server virtualenv (an Android-only checkout) cannot
        # check this, and saying so is honest where claiming a pass would not be.
        print("NOT CHECKED: no server/.venv found - cannot regenerate the schema on this machine.")
        return 0

    env = dict(os.environ)
    # Schema generation imports the models but never opens a connection, so these only have to
    # PARSE. Supplying them keeps the check runnable on a machine with no deploy/.env.
    env.setdefault("SECRET_KEY", "schema-check-not-a-real-secret")
    env.setdefault("DJANGO_DEBUG", "0")
    env.setdefault("ALLOWED_HOSTS", "localhost")
    env.setdefault("DATABASE_URL", "postgres://u:p@localhost:5432/none")
    env.setdefault("MEDIA_ROOT", tempfile.gettempdir())

    with tempfile.TemporaryDirectory() as tmp:
        fresh = pathlib.Path(tmp) / "schema.yaml"
        result = subprocess.run(
            [interpreter, "manage.py", "spectacular", "--file", str(fresh)],
            cwd=SERVER,
            env=env,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            print("NOT CHECKED: the server could not generate its schema.", file=sys.stderr)
            print(result.stderr.strip()[-2000:], file=sys.stderr)
            return 2

        # drf-spectacular prints its summary only when something went wrong, so any stderr at all
        # is a real finding. An untyped view is dropped from the schema SILENTLY, which would
        # otherwise read here as a clean pass with endpoints quietly missing.
        if result.stderr.strip():
            print("SCHEMA WARNINGS/ERRORS from drf-spectacular:", file=sys.stderr)
            print(result.stderr.strip()[-4000:], file=sys.stderr)
            return 1

        new_bytes = fresh.read_bytes()
        old_bytes = VENDORED.read_bytes()

    if new_bytes == old_bytes:
        digest = hashlib.sha256(old_bytes).hexdigest()[:16]
        print(f"OK: openapi/legion-schema.yaml matches the server (sha256 {digest}...).")
        return 0

    if args.write:
        VENDORED.write_bytes(new_bytes)
        digest = hashlib.sha256(new_bytes).hexdigest()[:16]
        print(f"UPDATED: openapi/legion-schema.yaml rewritten (sha256 {digest}...).")
        print("Re-run codegen in every limb, and re-vendor the copy in the MIDNIGHT_AI repo.")
        return 1

    print("DRIFTED: openapi/legion-schema.yaml no longer matches the server.", file=sys.stderr)
    print(f"  vendored: sha256 {hashlib.sha256(old_bytes).hexdigest()}", file=sys.stderr)
    print(f"  server:   sha256 {hashlib.sha256(new_bytes).hexdigest()}", file=sys.stderr)
    print("Run `python tools/schema_check.py --write`, then re-run codegen in every limb.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
