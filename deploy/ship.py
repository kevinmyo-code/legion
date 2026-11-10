# ruff: noqa: E501  (operator-facing messages read better unbroken)
"""Ship one commit of LEGION: worker, dev/main, engine image, migrations, service, job,
schedule, health and the phone's OTA, in that order, from one versioned tool.

Architecture review 2026-11-10, upgrade #9 ("ship as code"). It replaces the per-round bash in
`~/.legion/scripts/ship_*.sh` (438 lines a round, outside git) and keeps every guard those
scripts grew:

- refuses to start inside :30-:45 (the :40 `consolidate_memories` cron);
- pinned refs: `--sha` is the commit, `--branch` (optional) must point at it, `--expect-dev`
  (optional) is the only other value dev/main may hold, and dev/main must fast-forward to it;
- the Claude worker: no task running, stop, checkout, import check, start, fresh heartbeat;
- feature flags from the committed `deploy/cloudrun/flags.yaml`, each one that mounts a secret
  turned on only when that secret exists and the runtime account can read it;
- the live-env-drop check: nothing on the live service or job that the deploy would not pass;
- rollback facts recorded ONCE, before anything changes.

What it does differently, and why:

- the image is `server:<git sha>`, and the job and the service are both deployed by DIGEST. No
  mutable `latest` sits between the two deploys;
- `migrate` runs as a one-off execution of the `legion-worker` job, on the new image, BEFORE the
  service deploy. That is what lets the service carry `LEGION_SKIP_MIGRATE=1` (flags.yaml) so a
  scale-out no longer races `migrate`; with it unset, the boot-time migrate is a no-op;
- the scheduler sync describes each entry first and creates or updates only what differs.

Every step checks whether it is already done, so a failed run can be run again and carries on.

Usage (from a checkout that holds this file; the deploy tree is a clean checkout it moves to SHA):

    python deploy/ship.py --sha <40-hex> --deploy-tree ../legion-deploy \\
        --worker-tree ../legion-claude-on \\
        --apk <path> --apk-sha <sha256> --notes "what the phone gets" --dry-run

`--dry-run` runs every read-only check against the live project (git fetch, gcloud describes,
the secrets' IAM policies, the engine's newest release, `release_apk.py --dry-run`) and prints
the plan. It changes nothing: no push, no checkout, no build, no deploy, no job execution, no
scheduler change, no file written.

Never prints a token or a secret value. Secrets are passed to Cloud Run by NAME.
"""

from __future__ import annotations

import argparse
import atexit
import datetime as dt
import hashlib
import importlib.util
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
DEFAULT_REGION = "us-south1"
DEFAULT_SERVICE = "legion"
DEFAULT_JOB = "legion-worker"
ARTIFACT_REPO = "legion"
TASK_NAME = "LEGION Claude session"
SHA_LABEL = "legion-sha"
REFUSE_FROM, REFUSE_TO = 30, 45
# Before the job deploy + migrate: no crontab line may be due within this many seconds, so a
# scheduled task never runs the new image against the old schema (see quiet_window).
QUIET_SECONDS = 150
ROUTE_TREE = "server/frontend/src/routeTree.gen.ts"


class Stop(Exception):
    """A guard refused. Message says what did not happen."""


# ---------------------------------------------------------------- pure helpers (tested)


def in_refused_window(minute: int) -> bool:
    return REFUSE_FROM <= minute <= REFUSE_TO


def _cron_field(spec: str, low: int, high: int) -> set[int]:
    out: set[int] = set()
    for part in spec.split(","):
        step = 1
        if "/" in part:
            part, step_s = part.split("/", 1)
            step = int(step_s)
        if part in ("*", ""):
            lo, hi = low, high
        elif "-" in part:
            lo_s, hi_s = part.split("-", 1)
            lo, hi = int(lo_s), int(hi_s)
        else:
            lo = hi = int(part)
        out.update(range(lo, hi + 1, step))
    return out


def parse_crontab_text(text: str) -> list[tuple[set[int], set[int], str]]:
    """(minutes, hours, task) per line of deploy/crontab. Day/month/weekday fields are ignored,
    which only ever makes the quiet-window check MORE cautious."""
    rows = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 6:
            continue
        task = parts[-1]
        rows.append((_cron_field(parts[0], 0, 59), _cron_field(parts[1], 0, 23), task))
    return rows


def next_fires(rows, now: dt.datetime, within_s: int) -> list[tuple[dt.datetime, str]]:
    """Crontab firings in (now, now + within_s], by minute."""
    out = []
    start = now.replace(second=0, microsecond=0)
    for i in range(0, within_s // 60 + 2):
        t = start + dt.timedelta(minutes=i)
        if t <= now or (t - now).total_seconds() > within_s:
            continue
        for minutes, hours, task in rows:
            if t.minute in minutes and t.hour in hours:
                out.append((t, task))
    return out


def load_flags(text: str) -> dict:
    """flags.yaml -> {NAME: None | {"value": str, "secret": str|None, "readme": str|None}}."""
    import yaml

    doc = yaml.safe_load(text) or {}
    raw = doc.get("flags") or {}
    if not isinstance(raw, dict):
        raise Stop("flags.yaml: `flags` must be a mapping of NAME to value")
    flags: dict = {}
    for name, spec in raw.items():
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", str(name)):
            raise Stop(f"flags.yaml: {name!r} is not an environment variable name")
        if spec is None:
            flags[name] = None
        elif isinstance(spec, dict):
            if "value" not in spec:
                raise Stop(f"flags.yaml: {name} has no value")
            flags[name] = {
                "value": str(spec["value"]),
                "secret": spec.get("secret"),
                "readme": spec.get("readme"),
            }
        elif isinstance(spec, bool):
            raise Stop(f'flags.yaml: {name} is a YAML boolean; quote it ("on"/"off")')
        else:
            flags[name] = {"value": str(spec), "secret": None, "readme": None}
    return flags


def read_env_text(text: str) -> dict[str, str]:
    """The same parse as deploy/cloudrun/_common.read_cloud_env."""
    values: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, _, value = line.partition("=")
        values[name.strip()] = value.strip().strip("'\"")
    return values


@dataclass
class FlagPlan:
    values: dict[str, str]  # final .env-equivalent values handed to _common
    on: dict[str, str] = field(default_factory=dict)  # flag -> value set this ship
    off: list[str] = field(default_factory=list)  # flags that must be unset
    owed: list[str] = field(default_factory=list)  # human sentences


def decide_flags(env: dict[str, str], flags: dict, secret_ok) -> FlagPlan:
    """Merge flags.yaml over .env. `secret_ok(name) -> bool` answers "exists and readable"."""
    values = dict(env)
    plan = FlagPlan(values=values)
    for name, spec in flags.items():
        cur = env.get(name, "")
        if spec is None:
            if cur:
                raise Stop(
                    f".env sets {name}={cur} but flags.yaml says it must stay unset. "
                    f"Remove the line from .env (or change flags.yaml in a commit)."
                )
            values.pop(name, None)
            plan.off.append(name)
            continue
        want = spec["value"]
        if cur and cur != want:
            raise Stop(
                f".env sets {name}={cur}, flags.yaml says {want}. One source per name: "
                f"remove it from .env or change flags.yaml in a commit."
            )
        secret = spec.get("secret")
        if secret and not secret_ok(secret):
            values.pop(name, None)
            plan.owed.append(
                f"{name} stays off: secret {secret} is missing or the runtime account cannot "
                f'read it (deploy/cloudrun/README.md, "{spec.get("readme") or name}"). Create it, '
                f"grant roles/secretmanager.secretAccessor, and ship again; flags.yaml already "
                f"says {name}={want}."
            )
            continue
        values[name] = want
        plan.on[name] = want
    return plan


def env_drop(live_names: set[str], will_names: set[str]) -> set[str]:
    return set(live_names) - set(will_names)


def migrations_in_tree(server_dir: Path) -> list[str]:
    """`app.0001_name` for every migration file the tree carries (Django apps only)."""
    out = []
    for path in sorted(server_dir.glob("*/migrations/[0-9][0-9][0-9][0-9]_*.py")):
        out.append(f"{path.parent.parent.name}.{path.stem}")
    return out


def unapplied(expected: list[str], log_text: str) -> list[str]:
    """Names from `expected` not shown as `[X]  app.name` in a `showmigrations --plan` log."""
    applied = set(re.findall(r"\[X\]\s+(\S+)", log_text))
    return [m for m in expected if m not in applied]


# ---------------------------------------------------------------- process helpers


def find_gcloud() -> str:
    found = shutil.which("gcloud")
    if found:
        return found
    base = (
        Path(os.environ.get("LOCALAPPDATA", ""))
        / "Google"
        / "Cloud SDK"
        / "google-cloud-sdk"
        / "bin"
    )
    for name in ("gcloud.cmd", "gcloud"):
        if (base / name).exists():
            return str(base / name)
    alt = (
        Path(os.environ.get("LOCALAPPDATA", ""))
        / "Google"
        / "google-cloud-sdk"
        / "bin"
        / "gcloud.CMD"
    )
    if alt.exists():
        return str(alt)
    raise Stop("gcloud not found on PATH")


def _as_command(cmd: list[str]):
    if os.name == "nt" and str(cmd[0]).lower().endswith(".cmd"):
        return 'cmd /c "' + subprocess.list2cmdline(cmd) + '"'
    return cmd


def render(cmd: list[str]) -> str:
    return " ".join(f'"{p}"' if (" " in p or "," in p) else p for p in map(str, cmd))


class Ship:
    def __init__(self, args):
        self.a = args
        self.dry = args.dry_run
        self.gcloud = find_gcloud()
        self.plan: list[str] = []
        self.owed: list[str] = []
        self.token = ""
        self.machine = ""

    # -- output
    def say(self, text: str = "") -> None:
        print(text, flush=True)

    def step(self, title: str) -> None:
        self.say(f"\n== {title}")

    def would(self, text: str) -> None:
        self.plan.append(text)
        self.say(f"[would] {text}")

    # -- running
    def read(self, cmd: list[str], cwd: Path | None = None, check: bool = True) -> str:
        """A read-only command: runs in --dry-run too."""
        res = subprocess.run(
            _as_command([str(c) for c in cmd]),
            cwd=cwd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if check and res.returncode != 0:
            raise Stop(f"read failed ({render(cmd)}): {res.stderr.strip()[-400:]}")
        return res.stdout.replace("\r", "")

    def act(self, cmd: list[str], cwd: Path | None = None, what: str = "") -> int:
        """A changing command: printed and skipped in --dry-run."""
        if self.dry:
            self.would(what or render(cmd))
            return 0
        self.say(f"$ {render(cmd)}")
        return subprocess.run(_as_command([str(c) for c in cmd]), cwd=cwd).returncode

    def git(self, *args, tree: Path | None = None, check: bool = True) -> str:
        return self.read(["git", "-C", str(tree or self.a.deploy_tree), *args], check=check).strip()

    def gc(self, *args, check: bool = True) -> str:
        return self.read([self.gcloud, *args, "--project", self.project], check=check)

    def engine_get(self, path: str):
        req = urllib.request.Request(self.engine_url + path)
        if self.token:
            req.add_header("Authorization", f"Token {self.token}")
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))

    # ------------------------------------------------------------ main
    def run(self) -> int:
        a = self.a
        self.project = a.project or os.environ.get("GOOGLE_CLOUD_PROJECT", "")
        env_file = Path(a.deploy_tree) / "deploy" / "cloudrun" / ".env"
        self.env_text = env_file.read_text(encoding="utf-8") if env_file.exists() else ""
        if not self.project:
            self.project = read_env_text(self.env_text).get("GOOGLE_CLOUD_PROJECT", "")
        if not self.project:
            raise Stop("no project: set GOOGLE_CLOUD_PROJECT, pass --project, or put it in .env")
        self.region = a.region
        self.say(
            f"ship {a.sha[:8]} to {self.project}/{self.region} "
            f"({'DRY RUN: read-only, nothing changes' if self.dry else 'REAL RUN'})"
        )

        self.check_window()
        self.check_git()
        self.load_target()
        self.read_live()
        self.check_worker()
        self.check_apk()
        self.check_flags_and_env()
        self.record_rollback()
        if self.nothing_to_ship():
            return 0
        self.dry_run_apk()
        self.ship_worker()
        self.push_refs()
        self.checkout_deploy_tree()
        self.build_image()
        self.job_and_migrate()
        self.deploy_service()
        self.confirm_env()
        self.confirm_migrations()
        self.sync_schedule()
        self.health()
        self.publish_apk()
        self.finish()
        return 0

    # ------------------------------------------------------------ 0) window
    def check_window(self) -> None:
        minute = dt.datetime.now().minute
        if in_refused_window(minute):
            msg = (
                f"It is :{minute:02d}. Ship outside :{REFUSE_FROM}-:{REFUSE_TO} (the :40 "
                f"consolidate cron); try again after :{REFUSE_TO}."
            )
            if self.dry:
                self.say(f"[note] a real run would refuse now: {msg}")
            else:
                raise Stop(msg)

    # ------------------------------------------------------------ 1) git
    def check_git(self) -> None:
        a = self.a
        self.step("1) refs")
        tree = Path(a.deploy_tree)
        self.git("fetch", "-q", "origin")
        sha = self.git("rev-parse", "--verify", f"{a.sha}^{{commit}}")
        if not a.sha or not sha.startswith(a.sha.lower()):
            raise Stop(f"{a.sha} does not resolve to one commit")
        a.sha = sha
        if a.branch:
            got = self.git("rev-parse", f"origin/{a.branch}")
            if got != sha:
                raise Stop(f"origin/{a.branch} is {got[:8]}, not {sha[:8]}; stop")
        self.dev = self.git("rev-parse", "origin/dev")
        self.main = self.git("rev-parse", "origin/main")
        for name, ref in (("dev", self.dev), ("main", self.main)):
            if ref == sha:
                continue
            if a.expect_dev and ref != a.expect_dev and not ref.startswith(a.expect_dev):
                raise Stop(
                    f"origin/{name} moved off the pinned {a.expect_dev[:8]} ({ref[:8]}); stop"
                )
            if (
                subprocess.run(
                    ["git", "-C", str(tree), "merge-base", "--is-ancestor", ref, sha]
                ).returncode
                != 0
            ):
                raise Stop(
                    f"origin/{name} ({ref[:8]}) is not an ancestor of {sha[:8]}: not a "
                    f"fast-forward. stop"
                )
        self.say(
            f"target {sha[:8]}; origin/dev {self.dev[:8]}, origin/main {self.main[:8]} "
            f"({'both at target' if self.dev == sha == self.main else 'fast-forward to target'})"
        )
        # deploy tree clean (only the EOL-only routeTree difference and the .env count as clean)
        status = self.read(["git", "-C", str(tree), "status", "--porcelain"])
        for line in status.splitlines():
            path = line[3:].strip()
            if path == "deploy/cloudrun/.env":
                continue
            if (
                path == ROUTE_TREE
                and subprocess.run(
                    ["git", "-C", str(tree), "diff", "--ignore-cr-at-eol", "--quiet", "--", path]
                ).returncode
                == 0
            ):
                continue
            raise Stop(f"{tree} has a real local change in {path}; stop")
        self.say(f"deploy tree {tree} clean (HEAD {self.git('rev-parse', 'HEAD')[:8]})")

    # ------------------------------------------------------------ target's deploy code
    def load_target(self) -> None:
        """deploy/cloudrun/*.py, flags.yaml and crontab AS OF the target commit, so the env
        lists, deploy flags and schedule shipped are the ones reviewed with that code."""
        a = self.a
        tmp = Path(tempfile.mkdtemp(prefix="ship-target-"))
        atexit.register(shutil.rmtree, tmp, True)
        data = subprocess.run(
            [
                "git",
                "-C",
                str(a.deploy_tree),
                "archive",
                a.sha,
                "deploy/cloudrun",
                "deploy/crontab",
            ],
            capture_output=True,
        ).stdout
        with tarfile.open(fileobj=io.BytesIO(data)) as tar:
            tar.extractall(tmp, filter="data") if sys.version_info >= (3, 12) else tar.extractall(
                tmp
            )
        cloud = tmp / "deploy" / "cloudrun"
        sys.path.insert(0, str(cloud))
        for mod in ("_common", "deploy", "deploy_job", "install_schedule"):
            sys.modules.pop(mod, None)
        import _common  # noqa: E402  (the target's)
        import deploy_job as job_mod  # noqa: E402

        import deploy as deploy_mod  # noqa: E402

        self.common, self.deploy_mod, self.job_mod = _common, deploy_mod, job_mod
        self.target_cloud = cloud
        self.legacy_target = not hasattr(deploy_mod, "service_deploy_command")
        flags_path = cloud / "flags.yaml"
        if not flags_path.exists():
            if not self.dry:
                raise Stop(
                    f"{a.sha[:8]} predates ship.py (no deploy/cloudrun/flags.yaml). Ship it "
                    f"with that round's bash, or ship a commit that carries ship.py."
                )
            flags_path = HERE / "cloudrun" / "flags.yaml"
            self.say(
                f"[note] {a.sha[:8]} predates ship.py: the plan uses this tool's flags.yaml "
                f"and deploy commands (a real run refuses such a commit)"
            )
            sys.path.insert(0, str(HERE / "cloudrun"))
            for mod in ("deploy", "deploy_job"):
                sys.modules.pop(mod, None)
            if self.legacy_target:
                spec = importlib.util.spec_from_file_location(
                    "ship_local_deploy", HERE / "cloudrun" / "deploy.py"
                )
                m = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(m)
                self.deploy_mod = m
                spec = importlib.util.spec_from_file_location(
                    "ship_local_job", HERE / "cloudrun" / "deploy_job.py"
                )
                m = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(m)
                self.job_mod = m
        self.flags = load_flags(flags_path.read_text(encoding="utf-8"))
        self.crontab_text = (tmp / "deploy" / "crontab").read_text(encoding="utf-8")
        self.image_base = f"{self.region}-docker.pkg.dev/{self.project}/{ARTIFACT_REPO}/server"
        self.image_tag = f"{self.image_base}:{a.sha}"

    # ------------------------------------------------------------ live state
    def read_live(self) -> None:
        a = self.a
        self.step("2) live engine (read only)")
        self.svc = json.loads(
            self.gc(
                "run",
                "services",
                "describe",
                a.service,
                "--region",
                self.region,
                "--format",
                "json",
            )
        )
        self.jobd = json.loads(
            self.gc("run", "jobs", "describe", a.job, "--region", self.region, "--format", "json")
        )
        self.sa = self.svc["spec"]["template"]["spec"].get("serviceAccountName", "")
        if not self.sa:
            raise Stop("could not read the service's runtime account; stop")
        self.engine_url = (
            a.engine_url or os.environ.get("LEGION_ENGINE_URL") or self.svc["status"]["url"]
        ).rstrip("/")
        self.live_rev = self.svc["status"].get("latestReadyRevisionName", "")
        rev = json.loads(
            self.gc(
                "run",
                "revisions",
                "describe",
                self.live_rev,
                "--region",
                self.region,
                "--format",
                "json",
            )
        )
        self.live_digest = rev["status"].get("imageDigest", "")
        self.live_rev_created = rev["metadata"].get("creationTimestamp", "")
        cont = self.jobd["spec"]["template"]["spec"]["template"]["spec"]["containers"][0]
        self.live_job_image = cont["image"]
        self.live_svc_env = {
            e["name"] for e in self.svc["spec"]["template"]["spec"]["containers"][0].get("env", [])
        }
        self.live_job_env = {e["name"] for e in cont.get("env", [])}
        # which commit runs? (1) our label, (2) a server:<sha> tag on the live digest,
        # (3) legacy: revision built from server:latest after the target was committed.
        labels = self.svc["metadata"].get("labels", {}) or {}
        self.live_sha = labels.get(SHA_LABEL, "")
        self.live_sha_how = "label" if self.live_sha else ""
        if not self.live_sha and self.live_digest:
            tags = self.read(
                [
                    self.gcloud,
                    "artifacts",
                    "docker",
                    "tags",
                    "list",
                    self.image_base,
                    "--project",
                    self.project,
                    "--format",
                    "value(tag,version)",
                ],
                check=False,
            )
            dig = self.live_digest.split("@")[-1]
            for line in tags.splitlines():
                parts = line.split()
                if (
                    len(parts) == 2
                    and parts[1].endswith(dig)
                    and re.fullmatch(r"[0-9a-f]{40}", parts[0])
                ):
                    self.live_sha, self.live_sha_how = parts[0], "image tag"
            if not self.live_sha:
                latest = [p.split() for p in tags.splitlines() if p.split()[:1] == ["latest"]]
                commit_t = self.git("show", "-s", "--format=%cI", a.sha)
                if (
                    latest
                    and latest[0][1].endswith(dig)
                    and self.live_rev_created
                    and dt.datetime.fromisoformat(self.live_rev_created.replace("Z", "+00:00"))
                    > dt.datetime.fromisoformat(commit_t)
                    and self.dev == a.sha
                ):
                    self.live_sha, self.live_sha_how = (
                        a.sha,
                        (
                            "legacy: server:latest, revision created after the commit and after dev "
                            "reached it (deployed by the pre-ship.py bash; not provable by digest)"
                        ),
                    )
        self.say(
            f"service {a.service}: revision {self.live_rev}, digest {self.live_digest.split('@')[-1][:19]}, "
            f"runs {self.live_sha[:8] or 'unknown'}{f' ({self.live_sha_how})' if self.live_sha else ''}"
        )
        self.say(f"job {a.job}: image {self.live_job_image}")
        self.say(f"runtime account {self.sa}")

    # ------------------------------------------------------------ worker
    def check_worker(self) -> None:
        a = self.a
        self.worker_prev = ""
        if a.no_worker:
            self.say("worker: skipped (--no-worker)")
            return
        self.step("3) Claude worker checks (read only)")
        wt = Path(a.worker_tree)
        if self.read(["git", "-C", str(wt), "status", "--porcelain"]).strip():
            raise Stop(f"{wt} has local changes; stop")
        head = self.git("rev-parse", "HEAD", tree=wt)
        if (
            subprocess.run(
                ["git", "-C", str(wt), "merge-base", "--is-ancestor", head, a.sha]
            ).returncode
            != 0
        ):
            raise Stop(f"worker checkout {head[:8]} is not an ancestor of {a.sha[:8]}; stop")
        self.worker_prev = head
        cfg = json.loads(Path(a.worker_config).read_text(encoding="utf-8-sig"))
        self.token = os.environ.get("LEGION_ENGINE_TOKEN") or cfg.get("token", "")
        self.machine = cfg.get("name", "")
        running = self.worker_running_tasks()
        self.say(f"worker checkout {head[:8]}, clean; {running} task(s) running")
        if running and head != a.sha:
            msg = (
                f"the Claude worker is running {running} task(s); a restart would fail them. "
                f"Wait (powershell -File {wt}/tools/claude_session/status.ps1), then run again."
            )
            if self.dry:
                self.say(f"[note] a real run would stop here: {msg}")
            else:
                raise Stop(msg)

    def worker_running_tasks(self) -> int:
        path = Path(self.a.worker_state)
        if not path.exists():
            return 0
        s = json.loads(path.read_text(encoding="utf-8-sig"))
        return sum(1 for t in s.get("tasks", {}).values() if t.get("status") == "running")

    def worker_procs(self) -> int:
        out = self.read(
            [
                "powershell.exe",
                "-NoProfile",
                "-Command",
                "@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match '"
                + re.escape(Path(self.a.worker_tree).name)
                + r".+worker\.py' }).Count",
            ],
            check=False,
        )
        try:
            return int(out.strip() or 0)
        except ValueError:
            return 0

    def last_seen(self) -> str:
        try:
            d = self.engine_get("/api/claude/status/")
        except Exception:  # noqa: BLE001 - liveness probe, empty means unknown
            return ""
        return next(
            (
                m.get("last_seen_at") or ""
                for m in d.get("machines", [])
                if m.get("name") == self.machine
            ),
            "",
        )

    # ------------------------------------------------------------ apk
    def check_apk(self) -> None:
        a = self.a
        self.apk_needed = False
        if not a.apk:
            self.say("phone: no --apk; the OTA step is skipped")
            return
        self.step("4) phone APK (read only)")
        apk = Path(a.apk)
        if not apk.exists():
            raise Stop(f"no APK at {apk}")
        h = hashlib.sha256(apk.read_bytes()).hexdigest()
        if h != a.apk_sha.lower():
            raise Stop("the pinned APK file changed (sha256 differs); stop")
        sys.path.insert(0, str(REPO / "tools"))
        import release_apk  # noqa: E402

        vc, _ = release_apk.parse_badging(release_apk.aapt2_output(apk))
        if not self.token:
            self.token = os.environ.get("LEGION_ENGINE_TOKEN", "")
        latest = self.engine_get(f"/api/releases/latest?app={a.app}")
        self.latest_vc = int(latest.get("version_code") or 0)
        latest_sha = latest.get("sha256") or ""
        if latest_sha == h:
            self.say(f"newest {a.app} release is already this APK ({self.latest_vc})")
        elif vc > self.latest_vc:
            self.apk_needed = True
            self.say(f"APK versionCode {vc} > newest published {self.latest_vc}")
        else:
            raise Stop(
                f"APK versionCode {vc} is not higher than the newest published "
                f"{self.latest_vc}: no phone would take it. stop"
            )

    # ------------------------------------------------------------ flags + env drop
    def secret_ok(self, name: str) -> bool:
        if not self.read(
            [
                self.gcloud,
                "secrets",
                "describe",
                name,
                "--project",
                self.project,
                "--format",
                "value(name)",
            ],
            check=False,
        ).strip():
            return False
        pol = self.read(
            [
                self.gcloud,
                "secrets",
                "get-iam-policy",
                name,
                "--project",
                self.project,
                "--format",
                "json",
            ],
            check=False,
        )
        try:
            p = json.loads(pol or "{}")
        except ValueError:
            return False
        member = f"serviceAccount:{self.sa}"
        return any(
            b.get("role") == "roles/secretmanager.secretAccessor" and member in b.get("members", [])
            for b in p.get("bindings", [])
        )

    def check_flags_and_env(self) -> None:
        self.step("5) flags and env (read only)")
        env = read_env_text(self.env_text)
        fp = decide_flags(env, self.flags, self.secret_ok)
        self.fp = fp
        self.owed += fp.owed
        c = self.common
        passed = (
            set(c.OPTIONAL_PLAIN_ENV_VARS) | set(c.PLAIN_ENV_VARS) | set(c.DEFAULTED_PLAIN_ENV_VARS)
        )
        for name, spec in self.flags.items():
            # A null flag must stay unset, which needs no passing; a flag with a value must reach
            # Cloud Run, or it is a line in a file that silently never ships.
            if spec is not None and name not in passed:
                raise Stop(
                    f"flags.yaml names {name}, which _common.py at {self.a.sha[:8]} never passes "
                    f"to Cloud Run (a line that would silently not ship). stop"
                )
        self.plain_env = c.plain_env_from(fp.values)
        self.secret_names = c.secret_names_for(fp.values)
        will = set(self.plain_env) | set(self.secret_names)
        for name, val in fp.on.items():
            self.say(f"flag {name}={val}")
            for sec, switch in c.OPTIONAL_SECRET_ENV_VARS.items():
                if switch == name and sec not in will:
                    raise Stop(f"{name} is on but _common.py would not mount {sec}; stop")
        for name in fp.off:
            self.say(f"flag {name}: unset (must stay unset)")
        for line in fp.owed:
            self.say(f"owed: {line}")
        for side, live in (("service", self.live_svc_env), ("job", self.live_job_env)):
            lost = env_drop(live, will)
            if lost:
                raise Stop(
                    f"the deploy would drop live {side} env: {' '.join(sorted(lost))}. Add them "
                    f"to .env or flags.yaml first. stop"
                )
        adds = sorted(will - self.live_svc_env)
        self.say(
            f"env: keeps every live name on the service and the job; adds: {' '.join(adds) or 'nothing'}"
        )
        unused = sorted(
            set(env)
            - passed
            - {"GOOGLE_CLOUD_PROJECT", "CLOUD_RUN_REGION", "CLOUD_RUN_MAX_INSTANCES"}
        )
        if unused:
            self.say(f"[note] .env names that never reach Cloud Run: {' '.join(unused)}")

    # ------------------------------------------------------------ rollback
    def record_rollback(self) -> None:
        a = self.a
        self.rb_path = Path(a.records) / f"{a.sha[:12]}.rollback.json"
        facts = {
            "sha": a.sha,
            "recorded_at": dt.datetime.now(dt.UTC).isoformat(),
            "prev_revision": self.live_rev,
            "prev_digest": self.live_digest,
            "prev_job_image": self.live_job_image,
            "prev_sha": self.live_sha,
            "prev_dev": self.dev,
            "prev_main": self.main,
            "worker_prev": self.worker_prev,
            "prev_phone_vc": getattr(self, "latest_vc", None),
        }
        if self.rb_path.exists():
            facts = json.loads(self.rb_path.read_text(encoding="utf-8"))
            self.say(f"rollback facts from the first run: {self.rb_path}")
        elif self.dry:
            self.would(f"write rollback facts to {self.rb_path}")
        else:
            self.rb_path.parent.mkdir(parents=True, exist_ok=True)
            self.rb_path.write_text(json.dumps(facts, indent=2), encoding="utf-8")
            self.say(f"rollback facts written to {self.rb_path}")
        self.rb = facts
        self.say(self.rollback_text())

    def rollback_text(self) -> str:
        f, a = self.rb, self.a
        lines = [
            "ROLLBACK (recorded before this run changed anything):",
            f"  service: gcloud run services update-traffic {a.service} --project {self.project} "
            f"--region {self.region} --to-revisions={f['prev_revision']}=100",
            f"  job:     gcloud run jobs update {a.job} --project {self.project} --region {self.region} "
            f"--image {f['prev_digest'] or f['prev_job_image']}",
            "  migrations stay (additive); old code ignores new tables and nullable columns",
        ]
        if f.get("worker_prev"):
            lines.append(
                f"  worker:  Stop-ScheduledTask '{TASK_NAME}'; git -C {a.worker_tree} checkout "
                f"--detach {f['worker_prev']}; Start-ScheduledTask '{TASK_NAME}'"
            )
        lines.append("  dev/main: not rewound (force-push needs Kevin's OK); revert commit instead")
        if a.apk:
            lines.append(
                f"  phone:   no downgrade; go forward with a higher versionCode built from the old "
                f"commit (newest before this run: {f.get('prev_phone_vc')})"
            )
        return "\n".join(lines)

    # ------------------------------------------------------------ nothing to ship?
    def nothing_to_ship(self) -> bool:
        a = self.a
        git_done = self.dev == a.sha == self.main
        engine_done = self.live_sha == a.sha
        worker_done = a.no_worker or self.worker_prev == a.sha
        apk_done = not self.apk_needed
        self.step("6) state")
        self.say(
            f"dev/main at target: {git_done}; engine runs target: {engine_done}; "
            f"worker on target: {worker_done}; phone release current: {apk_done}"
        )
        if git_done and engine_done and worker_done and apk_done and not a.force:
            why = f" ({self.live_sha_how})" if self.live_sha_how != "label" else ""
            self.say(f"nothing to ship: dev already at {a.sha[:8]}, and the engine runs it{why}.")
            if self.owed:
                self.say("still owed:\n  - " + "\n  - ".join(self.owed))
            return True
        return False

    # ------------------------------------------------------------ steps that change things
    def dry_run_apk(self) -> None:
        if not (self.a.apk and self.apk_needed):
            return
        self.step("7) release_apk --dry-run (signer pin, package; nothing uploaded)")
        env = dict(os.environ, LEGION_ENGINE_URL=self.engine_url)
        res = subprocess.run(
            [
                sys.executable,
                str(REPO / "tools" / "release_apk.py"),
                "--app",
                self.a.app,
                "--apk",
                self.a.apk,
                "--notes",
                "dry run",
                "--dry-run",
            ],
            env=env,
        )
        if res.returncode != 0:
            raise Stop("release_apk.py --dry-run refused the APK; stop")

    def ship_worker(self) -> None:
        a = self.a
        if a.no_worker:
            return
        self.step(f"8) worker to {a.sha[:8]}")
        wt = Path(a.worker_tree)
        if self.worker_prev == a.sha and (self.dry or self.worker_procs()):
            self.say("worker already on target and running; not restarting it")
            return
        ps = ["powershell.exe", "-NoProfile", "-Command"]
        if self.dry:
            self.would(
                f"Stop-ScheduledTask '{TASK_NAME}'; git -C {wt} checkout --detach {a.sha[:8]}; "
                f"import-check {a.worker_imports}; Start-ScheduledTask; wait for a fresh heartbeat (3 min)"
            )
            return
        if self.worker_running_tasks():
            raise Stop("a worker task started since the checks; nothing changed. Run again later.")
        self.act(ps + [f"Stop-ScheduledTask -TaskName '{TASK_NAME}'"])
        time.sleep(3)
        l0 = self.last_seen()
        self.act(["git", "-C", str(wt), "checkout", "-q", "--detach", a.sha])
        if self.git("rev-parse", "HEAD", tree=wt) != a.sha:
            raise Stop(
                f"worker checkout is not at {a.sha[:8]}; the worker is STOPPED. Start it: "
                f"Start-ScheduledTask -TaskName '{TASK_NAME}'"
            )
        mods = ",".join(a.worker_imports.split(","))
        code = (
            f"import sys;sys.path.insert(0,r'{wt / 'tools' / 'claude_session'}');"
            f"import {mods};print('ok')"
        )
        res = subprocess.run(
            [a.worker_python or sys.executable, "-c", code], capture_output=True, text=True
        )
        if res.stdout.strip().splitlines()[-1:] != ["ok"]:
            self.act(["git", "-C", str(wt), "checkout", "-q", "--detach", self.worker_prev])
            self.act(ps + [f"Start-ScheduledTask -TaskName '{TASK_NAME}'"])
            raise Stop(
                f"worker modules do not import ({res.stderr.strip()[-300:]}). Rolled the worker "
                f"back to {self.worker_prev[:8]} and restarted it; nothing pushed or deployed."
            )
        self.act(ps + [f"Start-ScheduledTask -TaskName '{TASK_NAME}'"])
        for _ in range(18):
            time.sleep(10)
            l1 = self.last_seen()
            if l1 and l1 > l0:
                self.say(f"worker back: last seen {l1}, {self.worker_procs()} process(es)")
                return
        raise Stop(
            f"the worker did not send a fresh heartbeat in 3 minutes. Nothing pushed or deployed; "
            f"the checkout is at {a.sha[:8]} (safe with the live engine). Check status.ps1, start the "
            f"task by hand, then run ship.py again."
        )

    def push_refs(self) -> None:
        a = self.a
        self.step(f"9) dev and main to {a.sha[:8]} (fast-forward only)")
        if a.no_push:
            if self.dev != a.sha:
                raise Stop("--no-push, but origin/dev is not at the target; stop")
            self.say("--no-push: dev/main left as they are")
            return
        for name, ref in (("dev", self.dev), ("main", self.main)):
            if ref == a.sha:
                self.say(f"origin/{name} already at target")
                continue
            if (
                self.act(
                    [
                        "git",
                        "-C",
                        str(a.deploy_tree),
                        "push",
                        "origin",
                        f"{a.sha}:refs/heads/{name}",
                    ]
                )
                != 0
            ):
                raise Stop(f"push to {name} failed (not a fast-forward?); stop")
        if not self.dry:
            self.git("fetch", "-q", "origin")
            if not (
                self.git("rev-parse", "origin/dev") == a.sha == self.git("rev-parse", "origin/main")
            ):
                raise Stop("dev/main are not at the target after the push; stop")

    def checkout_deploy_tree(self) -> None:
        a = self.a
        if self.git("rev-parse", "HEAD") == a.sha:
            return
        self.act(["git", "-C", str(a.deploy_tree), "checkout", "-q", "--detach", a.sha])
        if not self.dry and self.git("rev-parse", "HEAD") != a.sha:
            raise Stop(f"deploy tree is not at {a.sha[:8]} after checkout; stop")

    def build_image(self) -> None:
        a = self.a
        self.step(f"10) image server:{a.sha[:12]}")
        got = self.read(
            [
                self.gcloud,
                "artifacts",
                "docker",
                "images",
                "describe",
                self.image_tag,
                "--project",
                self.project,
                "--format",
                "value(image_summary.digest)",
            ],
            check=False,
        ).strip()
        if got:
            self.say(f"already built: {got[:19]}")
        else:
            code = self.act(
                [
                    self.gcloud,
                    "builds",
                    "submit",
                    "--project",
                    self.project,
                    "--tag",
                    self.image_tag,
                    str(Path(a.deploy_tree) / "server"),
                ]
            )
            if code != 0:
                raise Stop("image build failed; nothing deployed")
            if not self.dry:
                got = self.read(
                    [
                        self.gcloud,
                        "artifacts",
                        "docker",
                        "images",
                        "describe",
                        self.image_tag,
                        "--project",
                        self.project,
                        "--format",
                        "value(image_summary.digest)",
                    ]
                ).strip()
        self.image = (
            f"{self.image_base}@{got}" if got else f"{self.image_base}@<digest of :{a.sha[:12]}>"
        )
        self.say(f"deploying by digest: {self.image}")

    def _env_file(self, prefix: str) -> str:
        if self.dry:
            return "<temp .yaml>"
        return str(self.common.write_env_vars_file(self.plain_env, prefix))

    def quiet_window(self) -> None:
        rows = parse_crontab_text(self.crontab_text)
        for _ in range(12):
            now = dt.datetime.now()
            due = next_fires(rows, now, QUIET_SECONDS)
            if not due:
                return
            if self.dry:
                self.say(
                    f"[note] a real run would now wait: {due[0][1]} fires at {due[0][0]:%H:%M}"
                )
                return
            self.say(
                f"waiting: {due[0][1]} fires at {due[0][0]:%H:%M}; the job image and migrate go "
                f"in a {QUIET_SECONDS}s gap with no scheduled task"
            )
            time.sleep(30)
        raise Stop("no quiet gap in the crontab within 6 minutes; nothing deployed")

    def job_and_migrate(self) -> None:
        a = self.a
        self.step("11) job image, then migrate as a job execution (before the service)")
        sec = self.common.secrets_flag_value(self.secret_names)
        cur = self.jobd["spec"]["template"]["spec"]["template"]["spec"]["containers"][0]["image"]
        if cur == self.image and not self.dry:
            self.say("job already on this digest")
        else:
            self.quiet_window()
            env_file = self._env_file(a.job)
            cmd = self.job_mod.job_deploy_command(
                self.gcloud,
                a.job,
                self.image,
                self.region,
                sec,
                env_file,
                labels={SHA_LABEL: a.sha},
            ) + ["--project", self.project]
            try:
                if self.act(cmd) != 0:
                    raise Stop("job deploy failed; the service is untouched")
            finally:
                if not self.dry:
                    Path(env_file).unlink(missing_ok=True)
        self.exec_job(["manage.py", "migrate", "--noinput"], "migrate")

    def exec_job(self, args: list[str], what: str) -> str:
        a = self.a
        cmd = [
            self.gcloud,
            "run",
            "jobs",
            "execute",
            a.job,
            "--project",
            self.project,
            "--region",
            self.region,
            "--wait",
            "--args=" + ",".join(args),
            "--format",
            "value(metadata.name)",
        ]
        if self.dry:
            self.would(render(cmd))
            return ""
        self.say(f"$ {render(cmd)}")
        res = subprocess.run(_as_command(cmd), capture_output=True, text=True)
        name = res.stdout.strip().splitlines()[-1:] or [""]
        if res.returncode != 0:
            raise Stop(
                f"{what} execution failed ({res.stderr.strip()[-300:]}); the service was NOT deployed"
            )
        self.say(f"{what}: execution {name[0]} succeeded")
        return name[0]

    def deploy_service(self) -> None:
        a = self.a
        self.step("12) service")
        if self.live_digest == self.image and self.live_sha == a.sha:
            self.say("service already on this digest")
            return
        sec = self.common.secrets_flag_value(self.secret_names)
        env_file = self._env_file(a.service)
        max_inst = int(read_env_text(self.env_text).get("CLOUD_RUN_MAX_INSTANCES", "4"))
        cmd = self.deploy_mod.service_deploy_command(
            self.gcloud,
            a.service,
            self.image,
            self.region,
            max_inst,
            sec,
            env_file,
            labels={SHA_LABEL: a.sha},
        )
        cmd += ["--project", self.project]
        try:
            if self.act(cmd) != 0:
                raise Stop(
                    "service deploy failed. Job is on the new image; migrations applied (additive)."
                    "\n" + self.rollback_text()
                )
        finally:
            if not self.dry:
                Path(env_file).unlink(missing_ok=True)

    def confirm_env(self) -> None:
        a = self.a
        self.step("13) confirm service and job")
        if self.dry:
            self.would(
                "confirm image digest, every live env name and each flag on the service AND the job"
            )
            return
        svc = json.loads(
            self.gc(
                "run",
                "services",
                "describe",
                a.service,
                "--region",
                self.region,
                "--format",
                "json",
            )
        )
        job = json.loads(
            self.gc("run", "jobs", "describe", a.job, "--region", self.region, "--format", "json")
        )
        sides = {
            "service": svc["spec"]["template"]["spec"]["containers"][0],
            "job": job["spec"]["template"]["spec"]["template"]["spec"]["containers"][0],
        }
        rev = svc["status"].get("latestReadyRevisionName", "")
        dig = self.gc(
            "run",
            "revisions",
            "describe",
            rev,
            "--region",
            self.region,
            "--format",
            "value(status.imageDigest)",
        ).strip()
        if dig != self.image:
            raise Stop(f"service revision {rev} runs {dig}, not {self.image}; OTA not published")
        if sides["job"]["image"] != self.image:
            raise Stop(f"job image is {sides['job']['image']}; OTA not published")
        for side, cont in sides.items():
            env = {e["name"]: e.get("value", "<secret>") for e in cont.get("env", [])}
            live = self.live_svc_env if side == "service" else self.live_job_env
            gone = live - set(env)
            if gone:
                raise Stop(f"{side} lost env {' '.join(sorted(gone))}; OTA not published")
            for name, val in self.fp.on.items():
                if env.get(name) != val:
                    raise Stop(f"{name} is {env.get(name, '<unset>')} on the {side}, not {val}")
            for name in self.fp.off:
                if name in env:
                    raise Stop(f"{name} is set on the {side}; it must stay unset")
            self.say(
                f"{side}: {self.image.split('@')[-1][:19]}, {len(env)} env names, flags as planned"
            )

    def confirm_migrations(self) -> None:
        self.step("14) showmigrations (read only, through the job)")
        expected = migrations_in_tree(Path(self.a.deploy_tree) / "server")
        exe = self.exec_job(["manage.py", "showmigrations", "--plan"], "showmigrations")
        if self.dry:
            self.say(f"[plan] expect all {len(expected)} migrations in the tree as [X]")
            return
        missing = expected
        for _ in range(10):
            log = self.gc(
                "logging",
                "read",
                f'resource.type="cloud_run_job" AND labels."run.googleapis.com/execution_name"="{exe}"',
                "--format",
                "value(textPayload)",
                "--freshness",
                "1h",
                check=False,
            )
            missing = unapplied(expected, log)
            if not missing:
                break
            time.sleep(30)
        if missing:
            raise Stop(f"not confirmed applied: {' '.join(missing[:10])}; OTA not published")
        self.say(f"all {len(expected)} migrations [X]")

    def sync_schedule(self) -> None:
        self.step("15) scheduler (create missing, update changed, leave the rest)")
        cmd = [
            sys.executable,
            str(self.target_cloud / "install_schedule.py"),
            "--project",
            self.project,
            "--job",
            self.a.job,
            "--region",
            self.region,
        ]
        if self.dry:
            self.would(render(cmd))
            return
        env = dict(os.environ, GOOGLE_CLOUD_PROJECT=self.project)
        if subprocess.run(cmd, cwd=self.target_cloud, env=env).returncode != 0:
            raise Stop("schedule sync failed; OTA not published")

    def health(self) -> None:
        self.step("16) health")
        if self.dry:
            self.would(f"GET {self.engine_url}/health -> db ok; /api/claude/status/")
            return
        h = self.engine_get("/health")
        if h.get("db") != "ok":
            raise Stop(f"/health says {h}; OTA not published")
        self.say(f"/health {h}")

    def publish_apk(self) -> None:
        a = self.a
        if not (a.apk and self.apk_needed):
            return
        self.step("17) phone OTA")
        cmd = [
            sys.executable,
            str(REPO / "tools" / "release_apk.py"),
            "--app",
            a.app,
            "--apk",
            a.apk,
            "--notes",
            a.notes,
        ]
        if self.dry:
            self.would(render(cmd))
            return
        env = dict(os.environ, LEGION_ENGINE_URL=self.engine_url, LEGION_ENGINE_TOKEN=self.token)
        if a.apk_bucket:
            env["LEGION_APK_BUCKET"] = a.apk_bucket
        if subprocess.run(cmd, env=env).returncode != 0:
            raise Stop("OTA publish failed; the engine is shipped")

    def finish(self) -> None:
        a = self.a
        self.step("done" if not self.dry else "plan complete (dry run: nothing changed)")
        if not self.dry:
            done = Path(a.records) / f"{a.sha[:12]}.shipped.json"
            done.write_text(
                json.dumps(
                    {
                        "sha": a.sha,
                        "image": self.image,
                        "at": dt.datetime.now(dt.UTC).isoformat(),
                        "owed": self.owed,
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )
        if self.owed:
            self.say("owed:\n  - " + "\n  - ".join(self.owed))
        self.say(self.rollback_text())


def parse_args(argv=None):
    appdata = Path(os.environ.get("APPDATA", str(Path.home())))
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("--sha", required=True, help="the commit to ship")
    p.add_argument("--branch", default="", help="origin/<branch> must point at --sha")
    p.add_argument("--expect-dev", default="", help="the only other value dev/main may hold")
    p.add_argument("--deploy-tree", default=str(REPO), help="clean checkout to build from")
    p.add_argument("--project", default="")
    p.add_argument("--region", default=DEFAULT_REGION)
    p.add_argument("--service", default=DEFAULT_SERVICE)
    p.add_argument("--job", default=DEFAULT_JOB)
    p.add_argument("--engine-url", default="")
    p.add_argument("--worker-tree", default="")
    p.add_argument("--worker-config", default=str(appdata / "legion-claude" / "config.json"))
    p.add_argument("--worker-state", default=str(appdata / "legion-claude" / "state.json"))
    p.add_argument("--worker-imports", default="worker,workflows,review_memories")
    p.add_argument("--worker-python", default="")
    p.add_argument("--no-worker", action="store_true", help="CI: no laptop worker to update")
    p.add_argument("--no-push", action="store_true", help="dev must already be at --sha")
    p.add_argument("--apk", default="")
    p.add_argument("--apk-sha", default="")
    p.add_argument("--app", default="phone", choices=("phone", "headunit"))
    p.add_argument("--apk-bucket", default=os.environ.get("LEGION_APK_BUCKET", ""))
    p.add_argument("--notes", default="")
    p.add_argument(
        "--records",
        default=str(Path.home() / ".legion" / "ships"),
        help="where rollback and shipped records go (outside the repo)",
    )
    p.add_argument("--force", action="store_true", help="redeploy even when nothing changed")
    p.add_argument("--dry-run", action="store_true")
    a = p.parse_args(argv)
    for name in ("deploy_tree", "worker_tree", "apk", "records"):
        if getattr(a, name):
            setattr(a, name, str(Path(getattr(a, name)).resolve()))
    if not a.no_worker and not a.worker_tree:
        p.error("--worker-tree is required (or --no-worker)")
    if a.apk and not (a.apk_sha and a.notes):
        p.error("--apk needs --apk-sha and --notes")
    return a


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        return Ship(args).run()
    except Stop as exc:
        print(f"\nSTOP: {exc}", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
