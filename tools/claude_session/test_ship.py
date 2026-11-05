"""alfred-builds ticket 03: ship.py with a real git remote (a bare repo in a temp folder) and a
fake deploy, a fake gcloud, a fake publisher and fake test runs. Never the real Cloud Run, the
real engine or Gradle.

    python -m pytest tools/claude_session/test_ship.py -q

Checks the ticket's verification list: the order of steps, a conflict stops, red stops, the token
is required. Plus: the guarded-area line, the branch moving after the yes, one token one ship,
the book (content) path.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ship  # noqa: E402
from common import Paths, read_json, write_json_atomic  # noqa: E402


def git(cwd, *args) -> str:
    p = subprocess.run(["git", "-C", str(cwd), *args], capture_output=True, text=True)
    if p.returncode != 0:
        raise AssertionError(f"git {' '.join(args)}: {p.stderr}")
    return p.stdout.strip()


def commit(cwd, rel, text, msg):
    f = Path(cwd) / rel
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(text, encoding="utf-8")
    git(cwd, "add", rel)
    git(cwd, "commit", "-q", "-m", msg)


class FakeWorld(ship.World):
    """Real git; everything else scripted and logged in order."""

    def __init__(self, paths, cfg, tmp: Path):
        super().__init__(paths, cfg)
        self.events: list[str] = []
        self.revisions = ["legion-00041-abc"]
        self.split_traffic = False
        self.test_codes: list[int] = []
        self.red_xml: str | None = None
        self.deploy_code = 0
        self.health_status = 200
        self.release_out = "version      2611051230 (2026-11-05-abc1234)\npublished    phone 2611051230 as release 9\n"
        self.http_calls: list[tuple[str, str]] = []
        self.clock = 1_000_000.0
        self.tmp = tmp

    def run(self, argv, cwd=None, env=None, timeout=None):
        argv = [str(a) for a in argv]
        if argv[0] == "git":
            if "push" in argv:
                self.events.append("push " + argv[-1].split("/")[-1])
            return super().run(argv, cwd, env, timeout)
        name = Path(argv[0]).name
        if name == "gcloud" and "describe" in argv:
            self.events.append("describe")
            latest = self.revisions[-1]
            traffic = ([{"revisionName": self.revisions[0], "percent": 100}] if self.split_traffic
                       else [{"latestRevision": True, "percent": 100}])
            return 0, json.dumps({"status": {"latestReadyRevisionName": latest,
                                             "url": "https://legion.example.run.app",
                                             "traffic": traffic}})
        if name == "gcloud" and "update-traffic" in argv:
            self.events.append("update-traffic " + argv[-1])
            self.split_traffic = False
            return 0, ""
        if any(a.endswith("deploy.py") for a in argv):
            self.events.append("deploy")
            assert env and env.get("GOOGLE_CLOUD_PROJECT") == "legion-engine-260906"
            if self.deploy_code == 0:
                self.revisions.append("legion-00042-new")
            return self.deploy_code, "deployed"
        if any(a.endswith("release_apk.py") for a in argv):
            self.events.append("release " + argv[argv.index("--notes") + 1])
            assert env["LEGION_APK_BUCKET"] == "legion-apk-test"
            return 0, self.release_out
        self.events.append("run " + name)
        return 0, ""

    def build(self, task, steps, cwd, say):
        argv = steps[0]
        label = "assembleFast" if "assembleFast" in argv else "test " + Path(cwd).name
        self.events.append(label)
        code = self.test_codes.pop(0) if self.test_codes else 0
        if self.red_xml and label.startswith("test"):
            xml = next((a.split("=", 1)[1] for a in argv if a.startswith("--junitxml=")), None)
            if xml:
                Path(xml).write_text(self.red_xml, encoding="utf-8")
        return code

    def which(self, name):
        return "C:/fake/gcloud.cmd" if name.startswith("gcloud") else None

    def http(self, method, url, headers=None, body=None, timeout=60):
        self.http_calls.append((method, url))
        if url.endswith("/health"):
            self.events.append("health")
            return self.health_status, b"ok"
        if url.endswith("/api/books/convert"):
            assert headers["Content-Type"] == "application/pdf"
            return 200, b"PK-epub-bytes"
        if url.endswith("/api/books/library"):
            assert b'name="licence_note"' in body and b"public domain" in body
            return 201, json.dumps({"id": 5, "sha256": "ab" * 32}).encode()
        if "/api/books/library/" in url and method == "DELETE":
            return 204, b""
        return 404, b"{}"

    def sleep(self, s):
        self.clock += s

    def now(self):
        return self.clock


RED = """<testsuite><testcase classname="tests.test_timers" name="test_one_announce">
<failure message="x"/></testcase><testcase classname="tests.test_timers" name="test_ok"/></testsuite>"""


class ShipBase(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.tmp = Path(self.tmpdir.name)
        self.origin = self.tmp / "origin.git"
        subprocess.run(["git", "init", "-q", "--bare", str(self.origin)], check=True)
        seed = self.tmp / "seed"
        subprocess.run(["git", "init", "-q", "-b", "dev", str(seed)], check=True)
        self.ident(seed)
        commit(seed, "server/app.py", "x = 1\n", "seed")
        commit(seed, "deploy/cloudrun/deploy.py", "print('deploy')\n", "deploy script")
        commit(seed, "tools/release_apk.py", "print('release')\n", "release script")
        git(seed, "remote", "add", "origin", str(self.origin))
        git(seed, "push", "-q", "origin", "dev")
        git(seed, "push", "-q", "origin", "dev:main")
        self.seed = seed
        self.wt = self.tmp / "work-wt"
        subprocess.run(["git", "clone", "-q", "-b", "dev", str(self.origin), str(self.wt)], check=True)
        self.ident(self.wt)
        git(self.wt, "checkout", "-q", "-b", "fix/timer")
        commit(self.wt, "server/app.py", "x = 2\n", "fix the timer")

        self.paths = Paths(self.tmp / "home").ensure()
        self.cfg = {"scope": "full", "engine_url": "https://legion.example", "token": "devtok",
                    "ship": {"repo": str(self.tmp / "no-repo"), "apk_bucket": "legion-apk-test"}}
        write_json_atomic(self.paths.config, self.cfg)
        self.world = FakeWorld(self.paths, self.cfg, self.tmp)
        self.said: list[str] = []

    def tearDown(self):
        self.tmpdir.cleanup()

    @staticmethod
    def ident(repo):
        git(repo, "config", "user.email", "t@example.com")
        git(repo, "config", "user.name", "Test")
        git(repo, "config", "commit.gpgsign", "false")

    def main(self, *argv):
        self.said = []
        return ship.main(list(argv), self.paths, self.world, say=self.said.append)

    def ask(self, kind="engine", *extra):
        return self.main("ask", "--task", "41", "--kind", kind, "--branch", "fix/timer",
                         "--worktree", str(self.wt), "--notes", "Alfred announces a timer once",
                         *extra)

    def token(self, kind="yes", task=42, parent=41, expires=600):
        write_json_atomic(self.paths.tokens / f"{task}-{kind}.json", {
            "token": "tok", "task": task, "parent": parent, "kind": kind,
            "expires_at": self.world.now() + expires})

    def run_ship(self, token="tok", *extra):
        return self.main("run", "--task", "42", "--token", token, *extra)

    def origin_sha(self, ref):
        return git(self.origin, "rev-parse", ref)


class TestAsk(ShipBase):
    def test_ask_prints_the_question_and_writes_the_plan_sending_nothing(self):
        dev = self.origin_sha("dev")
        self.assertEqual(self.ask("engine", "--targeted", "server:tests/test_timers.py"), 0, self.said)
        self.assertEqual(self.said[-1], "Ready: Alfred announces a timer once. Ship it?")
        plan = read_json(self.paths.task_work(41) / "ship-plan.json")
        self.assertEqual((plan["branch"], plan["kind"]), ("fix/timer", "engine"))
        self.assertEqual(plan["files"], ["server/app.py"])
        self.assertEqual(self.world.events, ["test server"])
        self.assertEqual(self.origin_sha("dev"), dev)

    def test_guarded_areas_are_named_in_the_question(self):
        commit(self.wt, "server/household/tenancy.py", "#\n", "tenancy")
        commit(self.wt, "CLAUDE.md", "#\n", "rule")
        self.assertEqual(self.ask(), 0, self.said)
        self.assertEqual(self.said[-1], "Ready: Alfred announces a timer once. Ship it? This also "
                                        "changes a CLAUDE.md ruling and auth or household isolation.")

    def test_a_kind_that_would_not_deliver_the_change_is_refused(self):
        self.assertEqual(self.ask("phone"), 1)
        self.assertIn("--kind engine", self.said[-1])

    def test_red_targeted_tests_block_the_question(self):
        self.world.test_codes = [1]
        self.world.red_xml = RED
        self.assertEqual(self.ask("engine", "--targeted", "server:tests/test_timers.py"), 1)
        self.assertIn("test_timers.test_one_announce", self.said[-1])
        self.assertFalse((self.paths.task_work(41) / "ship-plan.json").exists())

    def test_a_conflict_stops_the_question(self):
        commit(self.seed, "server/app.py", "x = 3\n", "someone else")
        git(self.seed, "push", "-q", "origin", "dev")
        head = git(self.wt, "rev-parse", "fix/timer")
        self.assertEqual(self.ask(), 1)
        self.assertIn("conflicts with dev in app.py", self.said[-1])
        self.assertEqual(git(self.wt, "rev-parse", "fix/timer"), head)
        self.assertEqual(git(self.wt, "status", "--porcelain"), "")

    def test_phone_ship_needs_release_apk_and_the_bucket(self):
        commit(self.wt, "app/src/Main.kt", "//\n", "phone")
        self.cfg["ship"].pop("apk_bucket")
        write_json_atomic(self.paths.config, self.cfg)
        self.assertEqual(self.ask("both"), 1)
        self.assertIn("APK bucket", self.said[-1])

    def test_the_work_laptop_never_ships(self):
        self.cfg["scope"] = "listed"
        write_json_atomic(self.paths.config, self.cfg)
        self.assertEqual(self.ask(), 1)
        self.assertIn("only the Alienware ships", self.said[-1])


class TestRun(ShipBase):
    def asked(self, kind="engine", *extra):
        self.assertEqual(self.ask(kind, *extra), 0, self.said)
        self.world.events = []

    def test_the_token_is_required(self):
        self.asked()
        dev = self.origin_sha("dev")
        self.assertEqual(self.run_ship(), 1)
        self.assertIn("no confirmation from Kevin", self.said[-1])
        self.token()
        self.assertEqual(self.run_ship("guess"), 1)
        self.token(kind="email_self")
        (self.paths.tokens / "42-yes.json").unlink()
        self.assertEqual(self.run_ship(), 1)
        self.assertIn("only Kevin's yes", self.said[-1])
        (self.paths.tokens / "42-email_self.json").unlink()
        self.token(expires=-1)
        self.assertEqual(self.run_ship(), 1)
        self.assertIn("expired", self.said[-1])
        self.assertEqual(self.origin_sha("dev"), dev)
        self.assertNotIn("deploy", self.world.events)

    def test_no_question_no_ship(self):
        self.token()
        self.assertEqual(self.run_ship(), 1)
        self.assertIn("no ship question was asked", self.said[-1])

    def test_engine_ship_runs_the_steps_in_order(self):
        self.asked("engine", "--targeted", "server:tests/test_timers.py")
        self.token()
        dev_before = self.origin_sha("dev")
        self.assertEqual(self.run_ship(), 0, self.said)
        self.assertEqual(self.world.events, [
            "test server", "test server",          # 2: targeted, then the server suite
            "push dev", "push main",               # 3
            "describe", "deploy", "describe",      # 4: before, deploy, the new revision
            "health",
        ])
        self.assertEqual(self.said[-1], "Shipped Alfred announces a timer once: engine revision "
                                        "legion-00042-new is live.")
        # dev got a merge commit of the branch; main mirrors dev
        self.assertEqual(self.origin_sha("dev"), self.origin_sha("main"))
        self.assertEqual(git(self.origin, "rev-list", "--parents", "-n", "1", "dev").count(" "), 2)
        self.assertEqual(git(self.origin, "rev-parse", "dev^1"), dev_before)
        self.assertIn("Merge fix/timer for dev", git(self.origin, "log", "-1", "--format=%s", "dev"))
        rec = [json.loads(line) for line in self.paths.ships.read_text().splitlines()][-1]
        self.assertEqual(rec["revision"], {"previous": "legion-00041-abc", "new": "legion-00042-new",
                                           "url": "https://legion.example.run.app"})
        self.assertEqual(rec["commits"]["dev_before"], dev_before)
        self.assertEqual(rec["commits"]["dev_merge"], self.origin_sha("dev"))
        self.assertTrue(read_json(self.paths.tokens / "42-yes.json")["ship_used"])

    def test_one_token_one_ship(self):
        self.asked()
        self.token()
        self.assertEqual(self.run_ship(), 0, self.said)
        self.assertEqual(self.run_ship(), 1)
        self.assertIn("already used", self.said[-1])

    def test_red_stops_before_anything_leaves_and_keeps_the_token(self):
        self.asked()
        self.token()
        dev = self.origin_sha("dev")
        self.world.test_codes = [1]
        self.world.red_xml = RED
        self.assertEqual(self.run_ship(), 1)
        self.assertIn("server tests failed: 1 failing, test_timers.test_one_announce", self.said[-1])
        self.assertIn("nothing shipped", self.said[-1])
        self.assertEqual(self.world.events, ["test server"])
        self.assertEqual(self.origin_sha("dev"), dev)
        self.assertFalse(read_json(self.paths.tokens / "42-yes.json").get("ship_used"))

    def test_red_without_a_report_is_still_red(self):
        self.asked()
        self.token()
        self.world.test_codes = [2]
        self.assertEqual(self.run_ship(), 1)
        self.assertIn("did not finish green (exit 2)", self.said[-1])

    def test_a_conflict_after_the_yes_stops_and_nothing_ships(self):
        self.asked()
        self.token()
        commit(self.seed, "server/app.py", "x = 9\n", "someone else")
        git(self.seed, "push", "-q", "origin", "dev")
        dev = self.origin_sha("dev")
        self.assertEqual(self.run_ship(), 1)
        self.assertIn("conflicts with dev", self.said[-1])
        self.assertEqual(self.origin_sha("dev"), dev)
        self.assertEqual(self.world.events, [])

    def test_the_branch_moving_after_the_yes_is_refused(self):
        self.asked()
        self.token()
        commit(self.wt, "server/app.py", "x = 4\n", "sneaky")
        self.assertEqual(self.run_ship(), 1)
        self.assertIn("changed after Kevin's yes", self.said[-1])

    def test_a_branch_other_than_the_question_is_refused(self):
        self.asked()
        self.token()
        self.assertEqual(self.run_ship("tok", "--branch", "fix/other"), 1)
        self.assertIn("not what Kevin said yes to", self.said[-1])

    def test_split_traffic_is_moved_to_the_new_revision(self):
        self.asked()
        self.token()
        self.world.split_traffic = True
        self.assertEqual(self.run_ship(), 0, self.said)
        self.assertIn("update-traffic --to-latest", self.world.events)

    def test_failed_health_is_said_with_the_way_back(self):
        self.asked()
        self.token()
        self.world.health_status = 503
        self.assertEqual(self.run_ship(), 1)
        self.assertIn("/health answered 503", self.said[-1])
        self.assertIn("undo the last update", self.said[-1])

    def test_both_deploys_then_publishes(self):
        commit(self.wt, "app/src/Main.kt", "//\n", "phone")
        self.asked("both")
        self.token()
        self.assertEqual(self.run_ship(), 0, self.said)
        ev = self.world.events
        self.assertEqual(ev[:3], ["test server", "test work-wt", "push dev"])
        self.assertLess(ev.index("health"), ev.index("assembleFast"))
        self.assertEqual(ev[-1], "release Alfred announces a timer once")
        rec = json.loads(self.paths.ships.read_text().splitlines()[-1])
        self.assertEqual(rec["release"]["version_code"], 2611051230)
        self.assertEqual(rec["release"]["built_from"], self.origin_sha("dev"))
        self.assertIn("phone update 2611051230 is published", self.said[-1])


class TestContent(ShipBase):
    def test_a_pdf_book_is_converted_then_added_with_its_licence(self):
        pdf = self.paths.task_work(41) / "dracula.pdf"
        pdf.parent.mkdir(parents=True, exist_ok=True)
        pdf.write_bytes(b"%PDF-1.4 fake")
        code = self.main("ask", "--task", "41", "--kind", "content", "--file", str(pdf),
                         "--title", "Dracula", "--author", "Bram Stoker",
                         "--licence-note", "public domain (Project Gutenberg)",
                         "--source-url", "https://www.gutenberg.org/ebooks/345")
        self.assertEqual(code, 0, self.said)
        self.assertEqual(self.said[-1], "Ready: Dracula by Bram Stoker, public domain (Project "
                                        "Gutenberg). Add it to your Books?")
        self.assertEqual(self.world.http_calls, [])
        self.token()
        self.assertEqual(self.run_ship(), 0, self.said)
        self.assertEqual([u.rsplit("/", 2)[-1] for _, u in self.world.http_calls],
                         ["convert", "library"])
        self.assertEqual(self.said[-1], "Added Dracula by Bram Stoker to your Books.")
        rec = json.loads(self.paths.ships.read_text().splitlines()[-1])
        self.assertEqual((rec["kind"], rec["book"]["id"]), ("content", 5))

    def test_a_book_needs_a_licence_note_and_a_source(self):
        epub = self.paths.task_work(41) / "b.epub"
        epub.parent.mkdir(parents=True, exist_ok=True)
        epub.write_bytes(b"PK")
        self.assertEqual(self.main("ask", "--task", "41", "--kind", "content", "--file", str(epub),
                                   "--title", "B", "--source-url", "https://x"), 1)
        self.assertIn("--licence-note is required", self.said[-1])


if __name__ == "__main__":
    unittest.main()
