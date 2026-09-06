"""Shared plumbing for the three Cloud Run scripts in this folder.

Modelled on the Windows-quoting and .env-reading shape in
`midconerpdash/deploy.py` (a reference Kevin already operates on Cloud Run),
rewritten for LEGION rather than copied: that project reads secret VALUES
out of a local `.env` and ships them inline; this one never does that for a
secret (CLAUDE.md section 7, and the ticket's own "never --set-env-vars for
a secret"). Secrets here are Secret Manager references by NAME only - the
scripts in this folder never hold a secret's value in memory.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SERVER_DIR = HERE.parent.parent / "server"

DEFAULT_SERVICE = "legion"
DEFAULT_REGION = "us-south1"

# Secret Manager secret NAMES. The scripts assume a secret of this exact
# name exists with a "latest" version - `README.md` in this folder gives
# the `gcloud secrets create` command. Never read from `.env`, never
# printed, never passed through `--set-env-vars`.
SECRET_ENV_VARS = ("SECRET_KEY", "DATABASE_URL")

# Non-secret configuration, read from `deploy/cloudrun/.env` (copy
# `.env.example`). Deliberately a SEPARATE file from `deploy/.env`: that one
# is the local dev/compose config (ALLOWED_HOSTS=localhost, a
# postgres-in-a-container DATABASE_URL) and reusing it here would ship a
# Cloud Run service that answers to "localhost" and does not know its own
# Supabase URL is a secret. Values may contain commas (ALLOWED_HOSTS is
# itself a comma list), so they are written to a temporary env-vars YAML
# file for `gcloud run deploy --env-vars-file`, never to
# `--set-env-vars` inline, which breaks on the first comma in a value.
PLAIN_ENV_VARS = ("ALLOWED_HOSTS", "CSRF_TRUSTED_ORIGINS", "MEDIA_ROOT", "DJANGO_DEBUG")


def die(message: str) -> None:
    print(f"\n{message}", file=sys.stderr)
    raise SystemExit(1)


def require_project(explicit: str | None) -> str:
    """CLAUDE.md section 7: every script here refuses to run, in words,
    without a project to act on - there is no Kevin-hosted default project
    a script could quietly fall back to."""
    project = explicit or os.environ.get("GOOGLE_CLOUD_PROJECT", "").strip()
    if not project:
        die(
            "GOOGLE_CLOUD_PROJECT is not set (and --project was not given). "
            "Refusing to run: this script would otherwise have no project to "
            "act against, and there is no Kevin-hosted default to fall back "
            "to (CLAUDE.md section 7). Run:\n"
            "    export GOOGLE_CLOUD_PROJECT=your-project-id\n"
            "or pass --project your-project-id."
        )
    return project


def find_gcloud() -> str:
    """A freshly installed SDK is not on PATH until a new shell starts,
    which reads as "this script is broken" rather than "open a new window"
    - same trap the reference's find_gcloud exists to catch."""
    found = shutil.which("gcloud")
    if found:
        return found
    candidates = [
        Path(os.environ.get("LOCALAPPDATA", "")) / "Google/Cloud SDK/google-cloud-sdk/bin",
        Path(os.environ.get("ProgramFiles", "")) / "Google/Cloud SDK/google-cloud-sdk/bin",
        Path(os.environ.get("ProgramFiles(x86)", "")) / "Google/Cloud SDK/google-cloud-sdk/bin",
    ]
    for directory in candidates:
        for name in ("gcloud.cmd", "gcloud"):
            if (directory / name).exists():
                return str(directory / name)
    die("gcloud not found. Install it: https://cloud.google.com/sdk/docs/install")
    raise AssertionError("unreachable")  # die() never returns; satisfies type checkers


def as_command(command: list[str]):
    """Make an argument list safe to hand to Windows' cmd.exe.

    Verbatim reasoning from the reference: gcloud ships as a .cmd shim,
    which must go through cmd.exe, and cmd.exe strips a leading/trailing
    quote pair the moment any argument also needs quoting (a YAML file path
    with spaces, say). Wrapping the whole line in one more pair of quotes
    is the documented way round it: cmd strips that outer pair and leaves
    the real ones alone.
    """
    if os.name == "nt" and str(command[0]).lower().endswith(".cmd"):
        return 'cmd /c "' + subprocess.list2cmdline(command) + '"'
    return command


def render(command: list[str]) -> str:
    """How a command would read if typed at a shell, for --dry-run output
    and for logging what a real run is about to do."""
    return " ".join(
        f'"{part}"' if " " in part or "," in part else part for part in command
    )


def run(command: list[str], dry_run: bool, label: str = "") -> int:
    """Print, then either run for real or stop short, depending on
    --dry-run. Every script in this folder routes its gcloud calls through
    this so dry-run output and real behaviour can never drift apart into
    two code paths that quietly disagree."""
    prefix = f"[{label}] " if label else ""
    print(f"{prefix}$ {render(command)}")
    if dry_run:
        return 0
    return subprocess.run(as_command(command), cwd=HERE, check=False).returncode


def capture(command: list[str]) -> subprocess.CompletedProcess:
    """Run a command for its stdout, never in --dry-run mode - callers that
    need a real answer (does this resource exist yet?) skip straight past
    the dry-run branch, since a --dry-run invocation is never expected to
    reach Google at all."""
    return subprocess.run(
        as_command(command), cwd=HERE, capture_output=True, text=True, check=False
    )


def read_cloud_env() -> dict[str, str]:
    """`deploy/cloudrun/.env`: non-secret Cloud Run configuration only.
    Missing entirely is fine - every field has a sane default computed by
    the caller - but a value that IS present always wins over the default,
    same rule as `deploy/.env` for compose."""
    values: dict[str, str] = {}
    path = HERE / ".env"
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, _, value = line.partition("=")
        values[name.strip()] = value.strip().strip("'\"")
    return values


def write_env_vars_file(plain_env: dict[str, str], prefix: str) -> Path:
    """A temp YAML file for `gcloud run deploy --env-vars-file` /
    `gcloud run jobs deploy --env-vars-file`. Values may contain commas
    (ALLOWED_HOSTS is itself a comma-separated list) which the inline
    `--set-env-vars KEY=VAL,KEY=VAL` syntax cannot represent without a
    custom delimiter that Windows' cmd.exe then eats - the same trap the
    reference's deploy.py sidesteps for its API key. Secrets never pass
    through this file; they go through `--set-secrets` instead."""
    env_file = Path(tempfile.gettempdir()) / f"{prefix}-env-vars.yaml"
    env_file.write_text(
        "".join(
            f"{k}: '{v.replace(chr(39), chr(39) * 2)}'\n" for k, v in plain_env.items()
        ),
        encoding="utf-8",
    )
    return env_file


def secrets_flag_value(secret_names: tuple[str, ...] = SECRET_ENV_VARS) -> str:
    """`--set-secrets` value: ENV_NAME=SECRET_NAME:VERSION,... - env var
    name and Secret Manager secret name are the same string on purpose, so
    there is exactly one name to keep straight per secret, not two."""
    return ",".join(f"{name}={name}:latest" for name in secret_names)
