r"""Audit the phone from the engine, no adb: one command pulls a time window of the phone's own logs
from prod and runs the audits on it (2026-11-09, Kevin: "Send everything from the phone to the
engine so we can audit without adb").

    python tools/phone_audit_engine.py                       last 60 minutes
    python tools/phone_audit_engine.py --minutes 180 --summary
    python tools/phone_audit_engine.py --since 2026-11-09T14:00 --until 2026-11-09T15:30
    python tools/phone_audit_engine.py --file phone_audit_engine.txt   re-read a saved pull, no gcloud

What it does:

1. Runs `manage.py device_logs --reason logcat --reason crash --since .. --until .. --raw --b64`
   on the `legion-worker` Cloud Run Job (`gcloud run jobs execute --wait`). The phone uploads every
   line its process logged (`reason=logcat`, redacted on the phone) and its crash records
   (`reason=crash`); the command merges them into one time order.
2. Reads that execution's output back with `gcloud logging read` and reassembles the numbered
   `BLOB` lines (`server/devicelogs/lines.py`): a Job's stdout reaches Cloud Logging one entry per
   line with no ordering promise, so the text travels gzipped and numbered and is checked against
   its sha256.
3. Saves the text (threadtime lines, the same shape as an adb capture) and prints: the conversation
   timeline (`tools/voice_audit.py`), slow tools, crashes and exit records, and the nav report when
   `tools/nav_audit_report.py` exists (it lives on feat/nav-audit until that merges).

If the logcat batches hold no LIVE_AUDIT lines (the phone's capture was off), it pulls
`reason=conversation` instead for the timeline and says so.

Times are UTC unless they name a zone. Expect a minute or so for the Job to start. This reads prod
through the operator command; it writes nothing anywhere but the output file. `gcloud` must be
signed in (`gcloud auth login`). It is a tool for Kevin and the main session: Claude helpers on
the Alienware are denied gcloud (`tools/claude_session/asklist_hook.py`).

Stdlib only. Tests: python -m pytest tools/tests/test_phone_audit_engine.py -p no:cacheprovider
"""

from __future__ import annotations

import argparse
import base64
import datetime
import gzip
import hashlib
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DEFAULT_PROJECT = "legion-engine-260906"
DEFAULT_REGION = "us-south1"
DEFAULT_JOB = "legion-worker"
LOG_WAIT_S = 120
_BLOB = re.compile(r"BLOB (\d+)/(\d+) (\S*)")
_BLOB_END = re.compile(r"BLOB_END (\d+) ([0-9a-f]{64})")
_EXECUTION = re.compile(r"Execution \[([a-z0-9-]+)\]")
_THREADTIME = re.compile(
    r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+\s+([VDIWEFA])\s+(.*?)\s*: (.*)$"
)
_TOOL_MS = re.compile(r"tool_ms=(\d+)")


class Refused(Exception):
    """Something the operator must fix, said in words."""


# --------------------------------------------------------------------------- pure parts


def window(
    minutes: int | None, since: str | None, until: str | None, now: datetime.datetime | None = None
) -> tuple[str, str]:
    """(since, until) as ISO UTC strings for the engine command."""
    now = now or datetime.datetime.now(datetime.UTC)
    end = _parse(until) if until else now
    start = _parse(since) if since else end - datetime.timedelta(minutes=minutes or 60)
    if start >= end:
        raise Refused("--since must be before --until.")
    return _iso(start), _iso(end)


def _parse(value: str) -> datetime.datetime:
    try:
        parsed = datetime.datetime.fromisoformat(value)
    except ValueError as exc:
        raise Refused(f"Not an ISO time: {value!r} (e.g. 2026-11-09T14:00).") from exc
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=datetime.UTC)


def _iso(value: datetime.datetime) -> str:
    return value.astimezone(datetime.UTC).strftime("%Y-%m-%dT%H:%M:%S+00:00")


def engine_args(since: str, until: str, reasons: list[str], device: str | None) -> list[str]:
    """The `manage.py device_logs` argument list the Job runs."""
    args = ["manage.py", "device_logs", "--since", since, "--until", until, "--raw", "--b64"]
    for reason in reasons:
        args += ["--reason", reason]
    if device:
        args += ["--device", device]
    for arg in args:
        if "," in arg:
            raise Refused(f"{arg!r} has a comma, which gcloud's --args cannot carry.")
    return args


def read_blobs(lines: list[str]) -> str:
    """The text the engine's `--b64` output carried. Same format as `devicelogs/lines.py`."""
    chunks: dict[int, str] = {}
    total = digest = None
    for line in lines:
        m = _BLOB.search(line)
        if m:
            chunks[int(m.group(1))] = m.group(3)
            total = int(m.group(2))
            continue
        m = _BLOB_END.search(line)
        if m:
            total, digest = int(m.group(1)), m.group(2)
    if total is None:
        raise Refused(
            "The Job printed no BLOB lines. Is the engine deployed with device_logs --b64?"
        )
    missing = [i for i in range(1, total + 1) if i not in chunks]
    if missing:
        raise Refused(
            f"{len(missing)} of {total} chunks never reached Cloud Logging; run it again."
        )
    text = gzip.decompress(
        base64.b64decode("".join(chunks[i] for i in range(1, total + 1)))
    ).decode("utf-8")
    if digest and hashlib.sha256(text.encode("utf-8")).hexdigest() != digest:
        raise Refused("The reassembled log does not match its checksum; run it again.")
    return text


def complete(lines: list[str]) -> bool:
    """True once every chunk the BLOB_END line promises has arrived."""
    total = None
    seen = set()
    for line in lines:
        m = _BLOB_END.search(line)
        if m:
            total = int(m.group(1))
        m = _BLOB.search(line)
        if m:
            seen.add(int(m.group(1)))
    return total is not None and seen >= set(range(1, total + 1))


def execution_name(output: str) -> str | None:
    """The execution's name from `gcloud run jobs execute` output (stdout or stderr)."""
    for line in output.splitlines():
        line = line.strip()
        if re.fullmatch(r"[a-z0-9-]+-[a-z0-9]{5}", line):
            return line
    m = _EXECUTION.search(output)
    return m.group(1) if m else None


def crashes(lines: list[str]) -> list[str]:
    """Fatal exceptions, native crash dumps and failure exit records."""
    out = []
    for line in lines:
        m = _THREADTIME.match(line)
        if not m:
            continue
        level, tag, msg = m.groups()
        if (
            (tag == "AndroidRuntime" and level == "E")
            or (tag == "ExitInfo" and level == "E")
            or tag == "DEBUG"
        ):
            out.append(line)
        elif "FATAL EXCEPTION" in msg or "ANR in " in msg:
            out.append(line)
    return out


def slow_tools(lines: list[str], over_ms: int = 3000) -> list[str]:
    out = []
    for line in lines:
        m = _THREADTIME.match(line)
        if m and m.group(2) == "LIVE_LATENCY":
            ms = _TOOL_MS.search(m.group(3))
            if ms and int(ms.group(1)) >= over_ms:
                out.append(line)
    return out


def tag_counts(lines: list[str], top: int = 12) -> list[tuple[str, int]]:
    counts: dict[str, int] = {}
    for line in lines:
        m = _THREADTIME.match(line)
        if m:
            counts[m.group(2)] = counts.get(m.group(2), 0) + 1
    return sorted(counts.items(), key=lambda kv: -kv[1])[:top]


# --------------------------------------------------------------------------- gcloud


def _gcloud() -> str:
    found = shutil.which("gcloud") or shutil.which("gcloud.cmd")
    if not found:
        raise Refused("gcloud not found. Install the Cloud SDK and run gcloud auth login.")
    return found


def _run(command: list[str]) -> subprocess.CompletedProcess:
    if os.name == "nt" and command[0].lower().endswith(".cmd"):
        return subprocess.run(
            subprocess.list2cmdline(command),
            shell=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
    return subprocess.run(
        command, capture_output=True, text=True, encoding="utf-8", errors="replace", check=False
    )


def execute(args: list[str], project: str, region: str, job: str) -> str:
    gcloud = _gcloud()
    result = _run(
        [
            gcloud,
            "run",
            "jobs",
            "execute",
            job,
            "--project",
            project,
            "--region",
            region,
            "--wait",
            f"--args={','.join(args)}",
            "--format=value(metadata.name)",
        ]
    )
    name = execution_name(result.stdout + "\n" + result.stderr)
    if result.returncode != 0 or not name:
        raise Refused(
            f"The {job} Job did not run cleanly:\n{(result.stderr or result.stdout).strip()[-2000:]}"
        )
    return name


def read_execution(name: str, project: str, job: str) -> list[str]:
    """The execution's stdout lines, waiting up to LOG_WAIT_S for Cloud Logging to catch up."""
    gcloud = _gcloud()
    query = (
        f'resource.type="cloud_run_job" AND resource.labels.job_name="{job}" '
        f'AND labels."run.googleapis.com/execution_name"="{name}"'
    )
    deadline = time.monotonic() + LOG_WAIT_S
    while True:
        result = _run(
            [
                gcloud,
                "logging",
                "read",
                query,
                "--project",
                project,
                "--freshness=1d",
                "--limit=200000",
                "--format=value(textPayload)",
            ]
        )
        if result.returncode != 0:
            raise Refused(f"gcloud logging read failed:\n{result.stderr.strip()[-2000:]}")
        lines = result.stdout.splitlines()
        if complete(lines) or time.monotonic() > deadline:
            return lines
        time.sleep(10)


def pull(
    since: str,
    until: str,
    reasons: list[str],
    device: str | None,
    project: str,
    region: str,
    job: str,
) -> str:
    args = engine_args(since, until, reasons, device)
    print(f"Running {job}: device_logs {' '.join(args[2:])}", file=sys.stderr)
    name = execute(args, project, region, job)
    print(f"Execution {name} finished; reading its output.", file=sys.stderr)
    return read_blobs(read_execution(name, project, job))


# --------------------------------------------------------------------------- report


def _module(name: str):
    path = TOOLS / f"{name}.py"
    if not path.exists():
        return None
    spec = importlib.util.spec_from_file_location(f"{name}_for_phone_audit", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def report(lines: list[str], *, summary: bool, verbose: bool, out_path: Path) -> list[str]:
    out = [f"== Phone, from the engine ({len(lines)} lines; full text in {out_path})", ""]
    voice = _module("voice_audit")
    out.append("-- Conversation")
    timeline = []
    if voice is not None:
        timeline = voice.summary(lines) if summary else voice.timeline(lines, verbose=verbose)
    out.extend(timeline or ["no LIVE_AUDIT lines in this window"])
    out += ["", "-- Slow tools (3 s or more)"]
    out.extend(slow_tools(lines) or ["none"])
    out += ["", "-- Crashes and exit records"]
    out.extend(crashes(lines)[:200] or ["none"])
    out += ["", "-- Busiest tags"]
    out.extend(f"{count:7d}  {tag}" for tag, count in tag_counts(lines))
    nav = TOOLS / "nav_audit_report.py"
    if nav.exists():
        result = subprocess.run(
            [sys.executable, str(nav), str(out_path)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        out += [
            "",
            "-- Navigation (nav_audit_report.py)",
            (result.stdout or result.stderr).rstrip(),
        ]
    return out


def main(argv: list[str] | None = None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(
        description="Audit the phone from its logs on the engine (no adb)."
    )
    ap.add_argument("--minutes", type=int, default=60, help="window ending now (default 60)")
    ap.add_argument("--since", help="ISO time, UTC unless it names a zone")
    ap.add_argument("--until", help="ISO time, UTC unless it names a zone (default now)")
    ap.add_argument("--device", help="device label, e.g. 'samsung SM-A256E' (default every phone)")
    ap.add_argument(
        "--summary", action="store_true", help="per-session counts instead of the timeline"
    )
    ap.add_argument("--verbose", action="store_true", help="timeline with mic and activity lines")
    ap.add_argument("--out", default="phone_audit_engine.txt", help="where the pulled log is saved")
    ap.add_argument("--file", help="re-read a saved pull instead of asking the engine")
    ap.add_argument("--project", default=os.environ.get("CLOUDSDK_CORE_PROJECT", DEFAULT_PROJECT))
    ap.add_argument("--region", default=DEFAULT_REGION)
    ap.add_argument("--job", default=DEFAULT_JOB)
    args = ap.parse_args(argv)
    try:
        if args.file:
            out_path = Path(args.file)
            text = out_path.read_text(encoding="utf-8", errors="replace")
        else:
            since, until = window(args.minutes, args.since, args.until)
            text = pull(
                since, until, ["logcat", "crash"], args.device, args.project, args.region, args.job
            )
            if "LIVE_AUDIT" not in text:
                print(
                    "No LIVE_AUDIT lines in the logcat batches (capture off?); adding the conversation "
                    "records for the timeline.",
                    file=sys.stderr,
                )
                text += pull(
                    since, until, ["conversation"], args.device, args.project, args.region, args.job
                )
            out_path = Path(args.out)
            out_path.write_text(text, encoding="utf-8")
    except Refused as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(
        "\n".join(
            report(text.splitlines(), summary=args.summary, verbose=args.verbose, out_path=out_path)
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
