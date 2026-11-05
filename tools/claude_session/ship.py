r"""The ship step: merge, push, deploy and publish after Kevin's one yes (map alfred-builds, ticket 03).

Two halves, because the yes sits between them (ruling 2: one yes per change ships it):

    python ship.py ask --task 41 --branch fix/double-announce --worktree <work>\wt \
        --kind engine --notes "Alfred no longer announces a timer twice" \
        [--targeted "server:tests/test_timers.py"]
    python ship.py ask --task 41 --kind content --file <work>\dracula.epub --title Dracula \
        --author "Bram Stoker" --licence-note "public domain (Project Gutenberg)" \
        --source-url https://www.gutenberg.org/ebooks/345

    python ship.py run --task 42 --token <token from the yes>

`ask` needs no token and sends nothing. For code it fetches, rebases the branch on origin/dev
(a conflict stops it), runs the targeted tests (red stops it: ruling 4, red tests block the ship
question), names any guarded area the diff touches, writes `ship-plan.json` into the task's work
folder and prints the question on its last line: "Ready: <notes>. Ship it?" plus "This also
changes <X>." The helper reports `needs_confirmation` with exactly that question.

`run` needs the confirmation token the worker issued for Kevin's yes (`tokens/<task>-yes.json`,
checked like `send_mail.py` checks it: `common.find_token`, one token, one ship). It loads the
plan the question was asked about (this task's, or the parent task's for a follow-up) and refuses
if the branch moved since. Then, in this order, exactly as ticket 03 writes it:

  1. Fetch; rebase the branch on origin/dev. On conflict, stop and say so; nothing ships.
  2. Run the targeted tests again, plus the server suite, the phone JVM suite, the head unit
     suite or the tools tests for what changed, under the build lock with the heat check
     (`build_lock.py`). Red stops it, with the failing tests named; nothing ships.
  3. Merge into dev, push dev, merge dev into main, push main (CLAUDE.md branching). No force.
  4. engine: `deploy/cloudrun/deploy.py` with GOOGLE_CLOUD_PROJECT set, then wait for the new
     revision, make sure it serves all traffic, and smoke-test `/health` (Cloud Run reserves
     `/healthz`, `server/legion/urls.py`; the engine has no `/api/health`).
  5. phone: `gradlew assembleFast` (build lock, heat check), then `tools/release_apk.py --app phone
     --notes "<one line>"` (alfred-ota ticket 04; this calls it, never reimplements it). The
     phone's updater asks Kevin to install.
  6. Report the revision name and/or release version, and append the ship to `work/ships.jsonl`
     (what `undo.py` reads).

`content` (a book, ticket 07) skips 1 to 5: a PDF goes through the engine's converter
(`/api/books/convert`), then the EPUB is uploaded to the household library (`/api/books/library`)
with its licence note and source.

Pushing, deploying, publishing and gcloud run ONLY through this file and `undo.py`: the ask-list
hook denies them anywhere else, token or not. Prints in words; the helper puts its last line in
the report. Stdlib only.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_lock  # noqa: E402
from common import (Paths, TokenRefused, find_token, load_config, read_json,  # noqa: E402
                    write_json_atomic)

KINDS = ("engine", "phone", "both", "content")
PROJECT = "legion-engine-260906"
SERVICE = "legion"
REGION = "us-south1"
REPO_DEFAULT = Path(r"C:\Users\kevin\AndroidStudioProjects\legion")
FAST_APK = Path("app/build/outputs/apk/fast/app-fast.apk")
JBR = Path(r"C:\Program Files\Android\Android Studio\jbr")
REVISION_WAIT_S = 10 * 60
PLAN_NAME = "ship-plan.json"
# Gitignored files a worktree needs and never commits (legion-chores copies the same ones).
LOCAL_FILES = ("local.properties", "deploy/.env", "deploy/cloudrun/.env")

# Ruling 4: a yes is informed. A diff touching one of these adds "This also changes <X>."
GUARDED = [
    ("a locked safety clause", (
        "app/src/main/java/com/kevin/legion/ai/AriaBrain.kt",
        "app/src/main/java/com/kevin/legion/ai/CrisisDetector.kt",
        "headunit/app/src/main/java/com/kevin/midnightai/ai/AriaBrain.kt",
        "headunit/app/src/main/java/com/kevin/midnightai/ai/CrisisDetector.kt",
        "server/assistant/shared_clauses.txt", "server/assistant/shared_clauses.py")),
    ("a CLAUDE.md ruling", ("CLAUDE.md", "headunit/CLAUDE.md", "docs/adr/")),
    ("auth or household isolation", (
        "server/household/", "server/legion/settings.py", "server/legacy/models/tenancy.py")),
    ("the money gate", ("server/ingest/",)),
    ("the recruiter demo", ("server/demo/", "server/household/recruiter_routes.py",
                            "server/household/demo.py")),
    ("the worker's ask list", ("tools/claude_session/asklist_hook.py",)),
]

# A changed path's area picks the suite that covers it (step 2).
AREAS = {"server": "server/", "phone": "app/", "headunit": "headunit/", "tools": "tools/"}
# What each kind delivers. A branch changing the engine needs engine or both, the phone app phone
# or both; anything else (tools, docs, tickets) rides any kind.
DELIVERS = {"engine": {"engine"}, "phone": {"phone"}, "both": {"engine", "phone"}}


class Refused(Exception):
    """Stop, in words. Nothing past the failing step ran."""


# ----------------------------------------------------------------------------- the outside world


class World:
    """Every process, HTTP call, sleep and clock ship.py and undo.py touch. Tests replace it."""

    def __init__(self, paths: Paths, cfg: dict):
        self.paths, self.cfg = paths, cfg

    def run(self, argv: list[str], cwd=None, env: dict | None = None,
            timeout: float | None = None) -> tuple[int, str]:
        argv = list(argv)
        if argv and not os.path.isabs(argv[0]):
            found = shutil.which(argv[0])  # gcloud is gcloud.cmd on Windows
            if found:
                argv[0] = found
        full_env = {**os.environ, **(env or {})}
        try:
            p = subprocess.run(argv, cwd=str(cwd) if cwd else None, env=full_env, timeout=timeout,
                               capture_output=True, text=True, encoding="utf-8", errors="replace")
        except (OSError, subprocess.SubprocessError) as e:
            return 127, f"{Path(argv[0]).name} could not run ({type(e).__name__})"
        return p.returncode, (p.stdout or "") + (p.stderr or "")

    def build(self, task, steps: list[list[str]], cwd, say) -> int:
        """Gradle and the full suites: under the build lock, heat-gated (ticket 08)."""
        try:
            return build_lock.run_steps(self.paths, task, steps, cwd=Path(cwd), say=say,
                                        runner=lambda argv, c: self.run(argv, c, env=gradle_env())[0])
        except build_lock.TimedOut as e:
            raise Refused(f"{e}; nothing shipped") from None

    def http(self, method: str, url: str, headers: dict | None = None, body: bytes | None = None,
             timeout: float = 60) -> tuple[int, bytes]:
        req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read() or b""
        except (urllib.error.URLError, OSError) as e:
            return 0, str(e).encode()

    def which(self, name: str) -> str | None:
        return shutil.which(name)

    def sleep(self, s: float) -> None:
        time.sleep(s)

    def now(self) -> float:
        return time.time()


def gradle_env() -> dict:
    return {"JAVA_HOME": str(JBR)} if JBR.exists() and not os.environ.get("JAVA_HOME") else {}


# ----------------------------------------------------------------------------- small helpers


class Shipper:
    def __init__(self, paths: Paths, cfg: dict, world: World | None = None, say=print):
        self.paths, self.cfg = paths, cfg
        self.world = world or World(paths, cfg)
        self.say = say
        ship_cfg = cfg.get("ship") or {}
        self.repo = Path(ship_cfg.get("repo") or REPO_DEFAULT)
        self.project = ship_cfg.get("project") or PROJECT
        self.service = ship_cfg.get("service") or SERVICE
        self.region = ship_cfg.get("region") or REGION
        self.python = ship_cfg.get("python") or sys.executable

    # -- git
    def git(self, wt, *args, check: bool = True) -> str:
        code, out = self.world.run(["git", "-C", str(wt), *args])
        if check and code != 0:
            raise Refused(f"git {args[0]} failed: {out.strip()[-300:]}")
        return out.strip()

    def sha(self, wt, ref: str) -> str:
        return self.git(wt, "rev-parse", ref)

    def changed(self, wt, branch: str) -> list[str]:
        out = self.git(wt, "diff", "--name-only", f"origin/dev...{branch}")
        return [line.strip() for line in out.splitlines() if line.strip()]

    def rebase(self, wt, branch: str) -> None:
        self.git(wt, "fetch", "origin")
        current = self.git(wt, "rev-parse", "--abbrev-ref", "HEAD")
        if current != branch:
            self.git(wt, "checkout", branch)
        code, out = self.world.run(["git", "-C", str(wt), "rebase", "origin/dev"])
        if code != 0:
            files = self.git(wt, "diff", "--name-only", "--diff-filter=U", check=False)
            self.world.run(["git", "-C", str(wt), "rebase", "--abort"])
            names = ", ".join(Path(f).name for f in files.split()[:5]) or "some files"
            raise Refused(f"the branch conflicts with dev in {names}; nothing shipped. "
                          "Fix the conflict on the branch and ask again")

    def copy_local_files(self, wt) -> None:
        for rel in LOCAL_FILES:
            src, dst = self.repo / rel, Path(wt) / rel
            if src.is_file() and not dst.exists():
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dst)

    # -- tests
    def areas(self, files: list[str]) -> list[str]:
        return [a for a, prefix in AREAS.items() if any(f.startswith(prefix) for f in files)]

    def guarded(self, files: list[str]) -> list[str]:
        hits = []
        for label, prefixes in GUARDED:
            if any(f == p or (p.endswith("/") and f.startswith(p)) for f in files for p in prefixes):
                hits.append(label)
        return hits

    def suite_steps(self, area: str, wt: Path, task, targeted: str | None = None):
        """(cwd, argv, junit paths) for an area's suite, or its targeted run."""
        work = self.paths.task_work(task)
        work.mkdir(parents=True, exist_ok=True)
        tag = "targeted" if targeted is not None else "suite"
        if area == "server":
            xml = work / f"ship-server-{tag}.xml"
            argv = ["uv", "run", "pytest", "-q", "-p", "no:cacheprovider", f"--junitxml={xml}"]
            if targeted:
                argv += targeted.split()
            return wt / "server", argv, [xml]
        if area == "tools":
            xml = work / f"ship-tools-{tag}.xml"
            argv = [self.python, "-m", "pytest", "-q", "-p", "no:cacheprovider", f"--junitxml={xml}"]
            argv += targeted.split() if targeted else ["tools/claude_session", "tools/tests"]
            return wt, argv, [xml]
        if area in ("phone", "headunit"):
            root = wt if area == "phone" else wt / "headunit"
            results = root / "app" / "build" / "test-results" / "testDebugUnitTest"
            shutil.rmtree(results, ignore_errors=True)
            argv = ["gradlew.bat" if os.name == "nt" else "./gradlew", "testDebugUnitTest"]
            if targeted:
                argv += ["--tests", targeted]
            return root, argv, [results]
        raise Refused(f"no tests are known for {area}")

    def run_tests(self, task, wt: Path, files: list[str], targeted: list[str], full: bool) -> int:
        """Targeted first (fast red), then the suites for what changed. Returns the count of
        test runs. Raises Refused naming the failing tests."""
        runs = 0
        plan = []
        for t in targeted:
            area, _, arg = t.partition(":")
            if area not in AREAS or not arg.strip():
                raise Refused(f"--targeted must look like server:tests/test_x.py, got {t!r}")
            plan.append((area, arg.strip()))
        if full:
            plan += [(a, None) for a in self.areas(files)]
        for area, arg in plan:
            cwd, argv, xmls = self.suite_steps(area, wt, task, arg)
            what = f"the {area} tests" + (f" ({arg})" if arg else "")
            self.say(f"Running {what}.")
            code = self.world.build(task, [argv], cwd, self.say)
            runs += 1
            failing = failing_tests(xmls)
            if code != 0 or failing:
                if failing:
                    names = ", ".join(failing[:5]) + (f" and {len(failing) - 5} more" if len(failing) > 5 else "")
                    raise Refused(f"{what} failed: {len(failing)} failing, {names}; nothing shipped")
                raise Refused(f"{what} did not finish green (exit {code}); nothing shipped")
        return runs

    def preflight(self, kind: str, wt: Path) -> None:
        """What a ship needs on this laptop, checked before anything runs, so Kevin is never asked a
        question that cannot ship and a ship never stops half way for a missing tool."""
        if kind in ("engine", "both"):
            if not (wt / "deploy" / "cloudrun" / "deploy.py").is_file():
                raise Refused("deploy/cloudrun/deploy.py is not in the worktree")
            if not (self.world.which("gcloud") or self.world.which("gcloud.cmd")):
                raise Refused("gcloud is not installed on this laptop, so the engine cannot deploy")
        if kind in ("phone", "both"):
            if not (wt / "tools" / "release_apk.py").is_file():
                raise Refused("tools/release_apk.py is not on dev yet (alfred-ota), so a phone "
                              "update cannot be published")
            if not ((self.cfg.get("ship") or {}).get("apk_bucket") or os.environ.get("LEGION_APK_BUCKET")):
                raise Refused("the APK bucket is not set in the worker config (ship.apk_bucket), so "
                              "a phone update cannot be published")

    # -- plan
    def plan_path(self, task) -> Path:
        return self.paths.task_work(task) / PLAN_NAME

    # ------------------------------------------------------------------------- ask
    def ask(self, a) -> str:
        if a.kind == "content":
            return self.ask_content(a)
        for name in ("branch", "worktree", "notes"):
            if not getattr(a, name):
                raise Refused(f"--{name} is required for a {a.kind} ship")
        wt = Path(a.worktree)
        if not (wt / ".git").exists():
            raise Refused("--worktree is not a git worktree")
        self.rebase(wt, a.branch)
        files = self.changed(wt, a.branch)
        if not files:
            raise Refused("the branch has no changes against dev; there is nothing to ship")
        areas = self.areas(files)
        if "headunit" in areas:
            raise Refused("the branch changes the head unit, which ship.py does not publish yet")
        need = {"server": "engine", "phone": "phone"}
        needed = {need[x] for x in areas if x in need}
        if needed - DELIVERS[a.kind]:
            suggest = "both" if len(needed) == 2 else next(iter(needed))
            raise Refused(f"the branch changes {' and '.join(sorted(needed))} code, which a {a.kind} "
                          f"ship would not deliver; ask again with --kind {suggest}")
        self.copy_local_files(wt)
        self.preflight(a.kind, wt)
        self.run_tests(a.task, wt, files, a.targeted, full=False)
        guarded = self.guarded(files)
        plan = {"task": a.task, "kind": a.kind, "branch": a.branch, "worktree": str(wt),
                "notes": a.notes.strip(), "head": self.sha(wt, "HEAD"), "targeted": a.targeted,
                "files": files, "guarded": guarded, "at": self.world.now()}
        write_json_atomic(self.plan_path(a.task), plan)
        return question(plan)

    def ask_content(self, a) -> str:
        for name in ("file", "title", "licence_note", "source_url"):
            if not (getattr(a, name) or "").strip():
                raise Refused(f"--{name.replace('_', '-')} is required to add a book")
        f = Path(a.file)
        if not f.is_file():
            raise Refused("the book file is not there")
        ext = f.suffix.lower()
        if ext not in (".epub", ".pdf"):
            raise Refused("a book must be an EPUB, or a PDF to convert")
        if not re.match(r"^https?://", a.source_url.strip()):
            raise Refused("--source-url must be the http(s) address the copy came from")
        plan = {"task": a.task, "kind": "content", "file": str(f.resolve()), "sha256": file_sha(f),
                "title": a.title.strip(), "author": (a.author or "").strip(),
                "licence_note": a.licence_note.strip(), "source_url": a.source_url.strip(),
                "notes": f"{a.title.strip()}" + (f" by {a.author.strip()}" if a.author else ""),
                "guarded": [], "at": self.world.now()}
        write_json_atomic(self.plan_path(a.task), plan)
        return question(plan)

    # ------------------------------------------------------------------------- run
    def load_plan(self, task: int, tok: dict) -> dict:
        for t in (task, tok.get("parent")):
            if t is None:
                continue
            plan = read_json(self.plan_path(t), None)
            if plan:
                return plan
        raise Refused("no ship question was asked for this task; run ship.py ask first and get "
                      "Kevin's yes")

    def run(self, a) -> str:
        try:
            tok_path, tok = find_token(self.paths, a.task, a.token, self.world.now(), "ship_used")
        except TokenRefused as e:
            raise Refused(f"{e}; nothing shipped") from None
        if tok.get("kind") != "yes":
            raise Refused("only Kevin's yes to the ship question can ship; nothing shipped")
        plan = self.load_plan(a.task, tok)
        for name in ("branch", "kind"):
            given = getattr(a, name, None)
            if given and given != plan.get(name):
                raise Refused(f"--{name} {given} is not what Kevin said yes to "
                              f"({plan.get(name)}); nothing shipped")
        record = {"id": f"{a.task}-{uuid.uuid4().hex[:8]}", "task": a.task,
                  "asked_in": plan["task"], "kind": plan["kind"], "notes": plan["notes"],
                  "guarded": plan.get("guarded", []), "at": self.world.now()}
        if plan["kind"] == "content":
            self.use(tok_path, tok)
            record["book"] = self.ship_book(plan)
            self.append(record)
            return f"Added {plan['notes']} to your Books."

        wt = Path(plan["worktree"])
        branch = plan["branch"]
        if self.sha(wt, branch) != plan["head"]:
            raise Refused("the branch changed after Kevin's yes; ask the ship question again. "
                          "Nothing shipped")
        # 1. fetch, rebase
        self.rebase(wt, branch)
        files = self.changed(wt, branch)
        self.copy_local_files(wt)
        self.preflight(plan["kind"], wt)
        # 2. targeted again, plus the suites for what changed
        self.run_tests(a.task, wt, files, plan.get("targeted") or [], full=True)
        # From here on things leave the laptop: the yes is spent.
        self.use(tok_path, tok)
        # 3. merge into dev, push dev, merge dev into main, push main
        record["branch"] = branch
        record["commits"] = self.merge_and_push(wt, branch, plan["notes"])
        said = []
        # 4. engine
        if plan["kind"] in ("engine", "both"):
            record["revision"] = self.deploy_engine(wt)
            said.append(f"engine revision {record['revision']['new']} is live")
        # 5. phone
        if plan["kind"] in ("phone", "both"):
            record["release"] = self.publish_phone(a.task, wt, plan["notes"])
            record["release"]["built_from"] = record["commits"]["dev_merge"]
            said.append(f"phone update {record['release']['version_code']} is published; "
                        "your phone will ask to install it")
        # 6. report
        self.append(record)
        tail = (", ".join(said) + ".") if said else "merged and pushed; nothing to deploy."
        return f"Shipped {plan['notes']}: {tail}"

    def use(self, tok_path: Path, tok: dict) -> None:
        tok["ship_used"] = True
        write_json_atomic(tok_path, tok)

    def append(self, record: dict) -> None:
        self.paths.work.mkdir(parents=True, exist_ok=True)
        with open(self.paths.ships, "a", encoding="utf-8") as f:
            f.write(json.dumps(record) + "\n")

    def merge_and_push(self, wt: Path, branch: str, notes: str) -> dict:
        commits = {"branch_head": self.sha(wt, branch), "dev_before": self.sha(wt, "origin/dev"),
                   "main_before": self.sha(wt, "origin/main")}
        self.git(wt, "checkout", "--detach", "origin/dev")
        self.git(wt, "merge", "--no-ff", branch, "-m", f"Merge {branch} for dev: {notes}")
        code, out = self.world.run(["git", "-C", str(wt), "push", "origin", "HEAD:refs/heads/dev"])
        if code != 0:
            raise Refused("pushing dev was refused (dev may have moved while shipping); nothing "
                          "was pushed or deployed. Ask again to ship on top of the new dev")
        commits["dev_merge"] = self.sha(wt, "HEAD")
        self.git(wt, "checkout", "--detach", "origin/main")
        code, _ = self.world.run(["git", "-C", str(wt), "merge", "--ff-only", commits["dev_merge"]])
        if code != 0:
            self.git(wt, "merge", "--no-ff", commits["dev_merge"], "-m", "Merge dev into main")
        code, _ = self.world.run(["git", "-C", str(wt), "push", "origin", "HEAD:refs/heads/main"])
        if code != 0:
            raise Refused("dev is pushed, but pushing main was refused; nothing was deployed. "
                          "Main can be caught up by hand")
        commits["main_after"] = self.sha(wt, "HEAD")
        self.git(wt, "checkout", "--detach", commits["dev_merge"])
        return commits

    # -- engine
    def gcloud_env(self) -> dict:
        return {"GOOGLE_CLOUD_PROJECT": self.project, "CLOUDSDK_CORE_PROJECT": self.project}

    def describe(self) -> dict:
        code, out = self.world.run(["gcloud", "run", "services", "describe", self.service,
                                    "--region", self.region, "--project", self.project,
                                    "--format", "json"], env=self.gcloud_env(), timeout=120)
        if code != 0:
            raise Refused(f"gcloud could not describe the {self.service} service: {out.strip()[-200:]}")
        try:
            # stdout and stderr arrive together; gcloud's own notices ("Updates are available")
            # can follow the JSON, so read the first JSON object and ignore what trails it.
            return json.JSONDecoder().raw_decode(out[out.index("{"):])[0]
        except ValueError:
            raise Refused("gcloud described the service in a shape ship.py cannot read") from None

    def serving(self, desc: dict) -> str | None:
        """The revision serving 100% of traffic, or None when it is split."""
        st = desc.get("status") or {}
        for t in st.get("traffic") or []:
            if int(t.get("percent") or 0) == 100:
                return t.get("revisionName") or (st.get("latestReadyRevisionName")
                                                 if t.get("latestRevision") else None)
        return None

    def health(self, url: str) -> None:
        last = None
        for _ in range(3):
            last, _body = self.world.http("GET", url.rstrip("/") + "/health", timeout=30)
            if last == 200:
                return
            self.world.sleep(10)
        raise Refused(f"the new revision is serving but /health answered {last or 'nothing'}. "
                      "Say \"undo the last update\" to go back")

    def deploy_engine(self, wt: Path) -> dict:
        before = self.describe()
        previous = (before.get("status") or {}).get("latestReadyRevisionName")
        self.say("Deploying the engine.")
        code, out = self.world.run([self.python, str(wt / "deploy" / "cloudrun" / "deploy.py")],
                                   cwd=wt, env=self.gcloud_env(), timeout=40 * 60)
        if code != 0:
            raise Refused(f"the engine deploy failed after dev and main were pushed: "
                          f"{out.strip()[-200:]}. The running engine is unchanged")
        start = self.world.now()
        while True:
            desc = self.describe()
            new = (desc.get("status") or {}).get("latestReadyRevisionName")
            if new and new != previous:
                break
            if self.world.now() - start > REVISION_WAIT_S:
                raise Refused("the deploy finished but no new revision became ready in ten minutes")
            self.world.sleep(10)
        if self.serving(desc) != new:
            code, out = self.world.run(["gcloud", "run", "services", "update-traffic", self.service,
                                        "--region", self.region, "--project", self.project,
                                        "--to-latest"], env=self.gcloud_env(), timeout=300)
            if code != 0:
                raise Refused(f"revision {new} is ready but could not be given the traffic")
        url = (desc.get("status") or {}).get("url") or ""
        self.health(url)
        return {"previous": previous, "new": new, "url": url}

    # -- phone
    def publish_phone(self, task, wt: Path, notes: str, label: str | None = None) -> dict:
        release = wt / "tools" / "release_apk.py"
        if not release.is_file():
            raise Refused("tools/release_apk.py is not on dev yet (alfred-ota), so the phone "
                          "update was not published")
        bucket = (self.cfg.get("ship") or {}).get("apk_bucket") or os.environ.get("LEGION_APK_BUCKET")
        if not bucket:
            raise Refused("the APK bucket is not set in the worker config (ship.apk_bucket), so "
                          "the phone update was not published")
        self.say("Building the phone app.")
        gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
        code = self.world.build(task, [[gradlew, "assembleFast"]], wt, self.say)
        if code != 0:
            raise Refused(f"the phone build failed (exit {code}); nothing was published")
        env = {"LEGION_ENGINE_URL": self.cfg.get("engine_url", ""),
               "LEGION_ENGINE_TOKEN": self.cfg.get("token", ""), "LEGION_APK_BUCKET": bucket}
        code, out = self.world.run([self.python, str(release), "--app", "phone",
                                    "--apk", str(wt / FAST_APK), "--notes", label or notes],
                                   cwd=wt, env=env, timeout=20 * 60)
        if code != 0:
            raise Refused(f"publishing the phone update was refused: {out.strip()[-300:]}")
        m = re.search(r"version\s+(\d+)\s+\(([^)]*)\)", out)
        r = re.search(r"as release (\S+)", out)
        return {"version_code": int(m.group(1)) if m else None,
                "version_name": m.group(2) if m else "", "release_id": r.group(1) if r else None}

    # -- content
    def engine_headers(self) -> dict:
        token = self.cfg.get("token")
        if not (self.cfg.get("engine_url") and token):
            raise Refused("the worker has no engine address or token")
        return {"Authorization": f"Token {token}", "Accept": "application/json"}

    def ship_book(self, plan: dict) -> dict:
        f = Path(plan["file"])
        if not f.is_file() or file_sha(f) != plan["sha256"]:
            raise Refused("the book file changed or is gone since Kevin's yes; nothing was added")
        base = self.cfg.get("engine_url", "").rstrip("/")
        headers = self.engine_headers()
        data = f.read_bytes()
        name = f.name
        if f.suffix.lower() == ".pdf":
            status, body = self.world.http("POST", f"{base}/api/books/convert",
                                           {**headers, "Content-Type": "application/pdf"}, data,
                                           timeout=90)
            if status != 200:
                raise Refused(f"the PDF could not be converted to an EPUB ({detail(body, status)}); "
                              "nothing was added")
            data, name = body, f.stem + ".epub"
        fields = {"title": plan["title"], "author": plan["author"],
                  "licence_note": plan["licence_note"], "source_url": plan["source_url"]}
        boundary = "----legion" + uuid.uuid4().hex
        status, body = self.world.http(
            "POST", f"{base}/api/books/library",
            {**headers, "Content-Type": f"multipart/form-data; boundary={boundary}"},
            multipart(boundary, fields, "file", name, "application/epub+zip", data), timeout=120)
        if status not in (200, 201):
            raise Refused(f"the library refused the book ({detail(body, status)})")
        try:
            book = json.loads(body)
        except ValueError:
            book = {}
        return {"id": book.get("id"), "title": plan["title"], "sha256": book.get("sha256")}


# ----------------------------------------------------------------------------- module helpers


def question(plan: dict) -> str:
    if plan["kind"] == "content":
        q = f"Ready: {plan['notes']}, {plan['licence_note']}. Add it to your Books?"
    else:
        q = f"Ready: {plan['notes'].rstrip('.')}. Ship it?"
    if plan.get("guarded"):
        g = plan["guarded"]
        q += " This also changes " + (g[0] if len(g) == 1 else ", ".join(g[:-1]) + " and " + g[-1]) + "."
    return q


def failing_tests(paths: list[Path]) -> list[str]:
    files = []
    for p in paths:
        p = Path(p)
        if p.is_dir():
            files += sorted(p.glob("*.xml"))
        elif p.is_file():
            files.append(p)
    out = []
    for f in files:
        try:
            root = ET.parse(f).getroot()
        except (ET.ParseError, OSError):
            continue
        for case in root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                cls = (case.get("classname") or "").split(".")[-1]
                out.append(f"{cls}.{case.get('name')}" if cls else case.get("name") or "?")
    return out


def file_sha(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def detail(body: bytes, status: int) -> str:
    try:
        return str(json.loads(body).get("detail") or status)
    except (ValueError, AttributeError):
        return f"HTTP {status}"


def multipart(boundary: str, fields: dict, file_field: str, filename: str, ctype: str,
              data: bytes) -> bytes:
    parts = []
    for k, v in fields.items():
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n"
                     .encode("utf-8"))
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; "
                 f"filename=\"{filename}\"\r\nContent-Type: {ctype}\r\n\r\n".encode("utf-8"))
    parts.append(data)
    parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts)


def read_ships(paths: Paths) -> list[dict]:
    out = []
    try:
        with open(paths.ships, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        out.append(json.loads(line))
                    except ValueError:
                        continue
    except OSError:
        pass
    return out


def parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="ship.py", description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="mode", required=True)
    ask = sub.add_parser("ask", help="check, test, and print the ship question (no token)")
    ask.add_argument("--task", type=int, required=True)
    ask.add_argument("--kind", required=True, choices=KINDS)
    ask.add_argument("--branch")
    ask.add_argument("--worktree")
    ask.add_argument("--notes", help="what the change does, one line, spoken")
    ask.add_argument("--targeted", action="append", default=[],
                     help="area:args, e.g. server:tests/test_x.py or phone:'*Timer*'")
    ask.add_argument("--file")
    ask.add_argument("--title")
    ask.add_argument("--author", default="")
    ask.add_argument("--licence-note", dest="licence_note")
    ask.add_argument("--source-url", dest="source_url")
    run = sub.add_parser("run", help="ship what Kevin said yes to (needs the token)")
    run.add_argument("--task", type=int, required=True)
    run.add_argument("--token", required=True)
    run.add_argument("--branch", help="optional; must match the question")
    run.add_argument("--kind", choices=KINDS, help="optional; must match the question")
    return ap


def main(argv: list[str] | None = None, paths: Paths | None = None, world: World | None = None,
         say=print) -> int:
    a = parser().parse_args(sys.argv[1:] if argv is None else argv)
    paths = paths or Paths()
    cfg = load_config(paths)
    if cfg.get("scope") != "full":
        say("Not shipped: only the Alienware ships; the work laptop never does.")
        return 1
    shipper = Shipper(paths, cfg, world or World(paths, cfg), say)
    try:
        if a.mode == "ask":
            q = shipper.ask(a)
            say("Question for Kevin (report it with --status needs_confirmation --question):")
            say(q)
        else:
            say(shipper.run(a))
    except Refused as e:
        say(f"Not shipped: {e}.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
