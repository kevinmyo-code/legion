"""Ticket 04: the worker against a fake engine (real HTTP) and a fake `claude` (fake_claude.py).

Never calls the real Claude CLI and never sends mail. Run from the repo root:

    python -m unittest discover -s tools/claude_session -p "test_*.py" -v
"""
from __future__ import annotations

import json
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import send_mail  # noqa: E402
import worker as W  # noqa: E402
from common import Paths, read_json, write_json_atomic  # noqa: E402
from fake_engine import TOKEN, FakeEngine  # noqa: E402
from engine_client import EngineClient  # noqa: E402

SECRET_REQUEST = "zebra-request-7781"
SECRET_SUMMARY = "okapi-summary-4410"


class NoPower:
    def update(self):
        pass

    def release(self):
        pass


class FakeSMTP:
    sent: list = []

    def __init__(self, host, port):
        self.host, self.port = host, port

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def login(self, user, pw):
        self.user = user

    def send_message(self, msg, to_addrs):
        FakeSMTP.sent.append((msg, list(to_addrs)))


def fake(**spec) -> str:
    return "FAKE" + json.dumps(spec)


class WorkerCase(unittest.TestCase):
    cfg_extra: dict = {}

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name) / "legion-claude"
        self.paths = Paths(self.home).ensure()
        self.fake_log = Path(self.tmp.name) / "fake_claude.jsonl"
        self.engine = FakeEngine()
        self.day = "2026-11-01"
        self.cfg = {
            "name": "Alienware", "scope": "full", "engine_url": self.engine.url, "token": TOKEN,
            "claude_cmd": [sys.executable, str(HERE / "fake_claude.py")],
            "claude_images": ["python.exe"],
            "heartbeat_s": 0.2, "tick_s": 0.05, "task_cap_s": 60.0, "report_grace_s": 0.6,
            "hang_s": 60.0, "restart_min_s": 0.2, "backoff_max_s": 0.4,
            "mail": {"address": "alfred@example.com", "app_password": "x", "kevin": "kevin@example.com"},
        }
        self.cfg.update(self.cfg_extra)
        self.env = {"PATH": __import__("os").environ.get("PATH", ""),
                    "SYSTEMROOT": __import__("os").environ.get("SYSTEMROOT", ""),
                    "CLAUDECODE": "1", "CLAUDE_CODE_ENTRYPOINT": "cli", "CLAUDE_CODE_SSE_PORT": "1",
                    "ANTHROPIC_API_KEY": "sk-should-not-pass", "FAKE_CLAUDE_LOG": str(self.fake_log)}
        self.workers: list = []

    def tearDown(self):
        for stop, t, _w in self.workers:
            stop.set()
            t.join(15)
        self.engine.close()
        import logging
        for h in list(W.log.handlers):
            W.log.removeHandler(h)
            h.close()
        self.tmp.cleanup()

    def start(self, **over) -> W.Worker:
        cfg = {**self.cfg, **over}
        write_json_atomic(self.paths.config, cfg)  # send_mail.py and the hook read it from disk
        if not W.log.handlers:
            W.setup_logging(self.paths)
        w = W.Worker(self.paths, cfg, power=NoPower(), today=lambda: self.day,
                     drives=lambda: ["C:\\", "D:\\"], base_env=self.env,
                     engine=EngineClient(cfg["engine_url"], cfg["token"], timeout=3))
        stop = threading.Event()
        t = threading.Thread(target=w.run, args=(stop,), daemon=True)
        t.start()
        self.workers.append((stop, t, w))
        return w

    def stop_last(self):
        stop, t, _w = self.workers.pop()
        stop.set()
        t.join(15)

    def wait(self, cond, timeout=15.0, what="condition"):
        end = time.time() + timeout
        while time.time() < end:
            if cond():
                return
            time.sleep(0.05)
        self.fail(f"timed out waiting for {what}")

    def fake_lines(self):
        if not self.fake_log.exists():
            return []
        return [json.loads(x) for x in self.fake_log.read_text(encoding="utf-8").splitlines() if x]

    def starts(self):
        return [x for x in self.fake_lines() if "argv" in x]

    def msgs(self):
        return [x["msg"] for x in self.fake_lines() if "msg" in x]


class TestTasks(WorkerCase):
    def test_two_tasks_at_once_both_finish_from_report_py(self):
        self.engine.queue(id=41, request=f"slow one {fake(sleep=2.0, summary='slow done')}")
        self.engine.queue(id=42, request=f"fast one {fake(sleep=0.2, summary='fast done')}")
        self.start()
        self.wait(lambda: self.engine.finished(42), what="task 42")
        self.assertFalse(self.engine.finished(41), "42 finished first while 41 still ran")
        self.wait(lambda: any(h["running"] == [41, 42] and h["state"] == "busy"
                              for h in self.engine.heartbeats) or self.engine.finished(41),
                  what="busy heartbeat")
        self.wait(lambda: self.engine.finished(41), what="task 41")
        self.assertEqual(self.engine.finished(41)[0]["summary"], "slow done")
        self.assertEqual(self.engine.finished(42)[0]["status"], "done")
        self.assertGreaterEqual(self.engine.finished(42)[0]["turns"], 1)
        self.assertEqual(len(self.starts()), 1, "one main session for both tasks")
        hb = self.engine.heartbeats[0]
        self.assertEqual((hb["name"], hb["scope"], hb["claude_version"]),
                         ("Alienware", "full", "2.1.316 (Claude Code)"))
        self.wait(lambda: self.engine.heartbeats[-1]["state"] == "idle", what="idle")

    def test_child_env_and_command(self):
        self.engine.queue(id=1, request=fake(sleep=0.1))
        self.start()
        self.wait(lambda: self.engine.finished(1), what="task 1")
        s = self.starts()[0]
        self.assertFalse(s["claudecode"], "CLAUDECODE must be cleared")
        self.assertEqual(s["claude_code_keys"], [])
        self.assertFalse(s["api_key"], "ANTHROPIC_API_KEY must not reach the CLI")
        self.assertEqual(Path(s["home"]), self.home)
        argv = s["argv"]
        settings = argv[argv.index("--settings") + 1]
        self.assertTrue(Path(settings).is_absolute())
        hooks = json.loads(Path(settings).read_text())["hooks"]["PreToolUse"][0]
        self.assertTrue(hooks["matcher"].startswith("Bash|PowerShell|Write|Edit"))
        self.assertNotIn("Read", hooks["matcher"], "full scope: no hook process per read")
        self.assertIn("asklist_hook.py", hooks["hooks"][0]["command"])
        self.assertEqual(argv[argv.index("--setting-sources") + 1], "project,local")
        self.assertEqual(argv[argv.index("--permission-mode") + 1], "dontAsk")
        self.assertNotIn("bypassPermissions", argv)
        self.assertIn("--session-id", argv)
        self.assertEqual([argv[i + 1] for i, a in enumerate(argv) if a == "--add-dir"], ["C:\\", "D:\\"])
        for tool in ("Bash", "PowerShell", "Agent", "TaskStop", "WebSearch"):
            self.assertIn(tool, argv)
        prompt = argv[argv.index("--append-system-prompt") + 1]
        self.assertIn("Alienware", prompt)
        self.assertIn("report.py", prompt)
        self.assertNotIn("<<", prompt)
        self.assertTrue(Path(s["cwd"]).resolve() == self.paths.work.resolve())

    def test_follow_up_carries_context_and_confirmation_token(self):
        self.engine.queue(id=50, request=f"tidy Downloads {fake(status='needs_confirmation', question='OK to delete the old copy?')}")
        self.start()
        self.wait(lambda: self.engine.finished(50), what="task 50")
        f = self.engine.finished(50)[0]
        self.assertEqual((f["status"], f["question"]), ("needs_confirmation", "OK to delete the old copy?"))
        self.engine.queue(id=52, parent=50, request=f"what was that again {fake(sleep=0.1)}")
        self.wait(lambda: self.engine.finished(52), what="plain follow-up 52")
        plain = [m for m in self.msgs() if m.startswith("[task 52]")][0]
        self.assertIn("Follow-up to task 50", plain)
        self.assertNotIn("token", plain.lower(), "no confirms: plain follow-up, no token")
        self.assertFalse((self.paths.tokens / "52-yes.json").exists())
        self.engine.queue(id=51, parent=50, confirms=True, request=f"yes {fake(sleep=0.1)}")
        self.wait(lambda: self.engine.finished(51), what="task 51")
        msg = [m for m in self.msgs() if m.startswith("[task 51]")][0]
        self.assertIn("Follow-up to task 50", msg)
        self.assertIn("OK to delete the old copy?", msg)
        self.assertIn("Confirmation token:", msg)
        tok = read_json(self.paths.tokens / "51-yes.json")
        self.assertEqual((tok["kind"], tok["task"], tok["parent"]), ("yes", 51, 50))
        self.assertIn(tok["token"], msg)
        self.assertEqual(len(self.starts()), 1, "follow-up reached the same main session")

        # The token lets send_mail.py send once, Kevin cc'd. Fake SMTP; nothing leaves the machine.
        FakeSMTP.sent = []
        orig = send_mail.SMTP_FACTORY
        send_mail.SMTP_FACTORY = FakeSMTP
        try:
            args = ["--task", "51", "--token", tok["token"], "--to", "dad@example.com",
                    "--subject", "s", "--body", "b"]
            self.assertEqual(send_mail.main(args, self.paths), 0)
            self.assertEqual(send_mail.main(args, self.paths), 1, "one yes, one send")
        finally:
            send_mail.SMTP_FACTORY = orig
        msg_obj, rcpts = FakeSMTP.sent[0]
        self.assertEqual(rcpts, ["dad@example.com", "kevin@example.com"])
        self.assertEqual(msg_obj["Cc"], "kevin@example.com")

    def test_email_self_delivery_issues_kevin_only_token(self):
        self.engine.queue(id=55, request=f"rates sheet {fake(sleep=0.1)}", deliver="email_self")
        self.start()
        self.wait(lambda: self.engine.finished(55), what="task 55")
        msg = [m for m in self.msgs() if m.startswith("[task 55]")][0]
        self.assertIn("Kevin asked for the result by email", msg)
        self.assertIn("--to kevin", msg)
        tok = read_json(self.paths.tokens / "55-email_self.json")
        self.assertEqual(tok["kind"], "email_self")
        FakeSMTP.sent = []
        orig = send_mail.SMTP_FACTORY
        send_mail.SMTP_FACTORY = FakeSMTP
        try:
            base = ["--task", "55", "--token", tok["token"], "--subject", "s", "--body", "b"]
            self.assertEqual(send_mail.main(base + ["--to", "dad@example.com"], self.paths), 1)
            self.assertEqual(send_mail.main(base + ["--to", "kevin"], self.paths), 0)
        finally:
            send_mail.SMTP_FACTORY = orig
        self.assertEqual(len(FakeSMTP.sent), 1)
        self.assertEqual(FakeSMTP.sent[0][1], ["kevin@example.com"])

    def test_phone_delivery_asks_for_one_page_and_uploads_it_at_finish(self):
        page = Path(self.tmp.name) / "atlanta.html"
        page.write_bytes(b"<html><title>Atlanta</title></html>")
        other = Path(self.tmp.name) / "notes.txt"
        other.write_text("n")
        self.engine.queue(id=56, deliver="phone", request=(
            f"itinerary {fake(found=[str(page), str(other)], summary='Your page is ready.')}"))
        self.start()
        self.wait(lambda: self.engine.uploads, what="page upload")
        msg = [m for m in self.msgs() if m.startswith("[task 56]")][0]
        self.assertIn("page on his phone", msg)
        self.assertIn("ONE self-contained HTML file", msg)
        time.sleep(0.5)
        # Only the page went up, without a heartbeat asking; notes.txt waits for a yes.
        self.assertEqual(self.engine.uploads,
                         [(56, str(1000 + 560), b"<html><title>Atlanta</title></html>")])

    def test_a_task_without_phone_delivery_uploads_no_page(self):
        page = Path(self.tmp.name) / "plain.html"
        page.write_bytes(b"<html></html>")
        self.engine.queue(id=57, request=f"page {fake(found=[str(page)], summary='made it')}")
        self.start()
        self.wait(lambda: self.engine.finished(57), what="task 57")
        time.sleep(0.6)
        self.assertEqual(self.engine.uploads, [])
        msg = [m for m in self.msgs() if m.startswith("[task 57]")][0]
        self.assertNotIn("page on his phone", msg)

    def test_cancel_stops_only_that_helper(self):
        self.engine.queue(id=60, request=f"long {fake(sleep=8)}")
        self.engine.queue(id=61, request=f"short {fake(sleep=1.0, summary='short done')}")
        w = self.start()
        self.wait(lambda: (w._task(60) or {}).get("agent"), what="helper 60 started")
        with self.engine.lock:
            self.engine.cancel_requested.append(60)
        self.wait(lambda: self.engine.finished(60), what="task 60 finished")
        self.assertEqual(self.engine.finished(60)[0]["summary"], W.SAY_CANCELLED)
        self.assertEqual(self.engine.finished(60)[0]["status"], "cancelled")
        self.wait(lambda: any(m.startswith("[cancel 60]") for m in self.msgs()), what="cancel msg")
        self.wait(lambda: self.engine.finished(61), what="task 61")
        self.assertEqual(self.engine.finished(61)[0]["status"], "done")
        time.sleep(0.5)
        self.assertEqual(len(self.engine.finished(60)), 1, "cancelled task reported once")
        self.assertEqual(len(self.starts()), 1, "cancel did not kill the main session")

    def test_requested_file_uploads_only_listed_files(self):
        f = Path(self.tmp.name) / "aw4 wiring.pdf"
        f.write_bytes(b"%PDF-fake-bytes")
        other = Path(self.tmp.name) / "secret.txt"
        other.write_text("no")
        self.engine.queue(id=70, request=f"find it {fake(found=[str(f)], summary='found it')}")
        self.start()
        self.wait(lambda: self.engine.finished(70), what="task 70")
        found = self.engine.finished(70)[0]["found"]
        self.assertEqual(found[0]["name"], "aw4 wiring.pdf")
        self.assertEqual(found[0]["size"], len(b"%PDF-fake-bytes"))
        self.assertEqual(self.engine.uploads, [], "nothing uploads until Kevin asks")
        with self.engine.lock:
            self.engine.files_requested = [
                {"task": 70, "file_id": 9, "path": found[0]["path"]},
                {"task": 70, "file_id": 10, "path": str(other)}]
        self.wait(lambda: self.engine.uploads, what="upload")
        time.sleep(0.6)
        self.assertEqual([(t, fid, b) for t, fid, b in self.engine.uploads],
                         [(70, "9", b"%PDF-fake-bytes")])

    def test_heartbeat_carries_live_progress_capped_and_unlogged(self):
        step = "Searching Downloads for " + SECRET_SUMMARY + " x" * 100
        self.engine.queue(id=72, request=fake(sleep=2.0, progress=step))
        self.start()
        self.wait(lambda: any(h.get("progress", {}).get("72") for h in self.engine.heartbeats),
                  what="progress in heartbeat")
        got = next(h["progress"]["72"] for h in self.engine.heartbeats if h.get("progress", {}).get("72"))
        self.assertEqual(got, step[:120])
        self.wait(lambda: self.engine.finished(72), what="task 72")
        self.wait(lambda: self.engine.heartbeats[-1].get("progress") == {}, what="progress cleared")
        self.stop_last()
        text = "".join(p.read_text(encoding="utf-8") for p in self.paths.logs.glob("*.log"))
        self.assertNotIn(SECRET_SUMMARY, text)

    def test_helper_ending_without_report_fails_in_words(self):
        self.engine.queue(id=75, request=fake(report=False))
        self.start()
        self.wait(lambda: self.engine.finished(75), what="task 75")
        self.assertEqual(self.engine.finished(75)[0]["summary"], W.SAY_NO_REPORT)


class TestCap(WorkerCase):
    cfg_extra = {"task_cap_s": 1.0}

    def test_thirty_minute_stop(self):
        self.engine.queue(id=80, request=f"forever {fake(sleep=10)}")
        self.start()
        self.wait(lambda: self.engine.finished(80), what="task 80 stopped")
        self.assertEqual(self.engine.finished(80)[0]["status"], "failed")
        self.assertEqual(self.engine.finished(80)[0]["summary"], W.SAY_CAP)
        self.wait(lambda: any(m.startswith("[stop 80]") for m in self.msgs()), what="stop msg")
        self.assertEqual(len(self.starts()), 1, "stopped by message, not by killing the session")


class TestHang(WorkerCase):
    cfg_extra = {"hang_s": 1.0}

    def test_hung_main_session_is_killed_and_resumed(self):
        self.engine.queue(id=85, request=f"x {fake(sleep=10, hang=True)}")
        self.start()
        self.wait(lambda: self.engine.finished(85), what="task 85")
        self.assertEqual(self.engine.finished(85)[0]["summary"], W.SAY_HUNG)
        self.wait(lambda: len(self.starts()) >= 2, what="second start")


class TestRestart(WorkerCase):
    def test_restart_resumes_same_session_and_fails_orphans(self):
        self.engine.queue(id=90, request=fake(sleep=0.1))
        w = self.start()
        self.wait(lambda: self.engine.finished(90), what="task 90")
        sid = w.state["session_id"]
        self.engine.queue(id=91, request=fake(sleep=20))
        self.wait(lambda: (w._task(91) or {}).get("agent"), what="helper 91")
        self.stop_last()
        self.start()
        self.wait(lambda: self.engine.finished(91), what="task 91 failed")
        self.assertEqual(self.engine.finished(91)[0]["summary"], W.SAY_RESTARTED)
        self.wait(lambda: len(self.starts()) == 2, what="second start")
        argv = self.starts()[1]["argv"]
        self.assertEqual(argv[argv.index("--resume") + 1], sid)

    @unittest.skipUnless(sys.platform == "win32", "Windows process checks")
    def test_stale_pid_killed_only_when_it_is_ours(self):
        import subprocess
        sid = "11111111-2222-4333-8444-555555555555"
        ours = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)", sid])
        other = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        try:
            write_json_atomic(self.paths.pid, {"pid": other.pid, "session": sid})
            W.Worker(self.paths, self.cfg, power=NoPower(), base_env=self.env,
                     engine=EngineClient(self.engine.url, TOKEN)).startup()
            self.assertIsNone(other.poll(), "a recycled pid that is not ours is left alone")
            write_json_atomic(self.paths.pid, {"pid": ours.pid, "session": sid})
            W.Worker(self.paths, self.cfg, power=NoPower(), base_env=self.env,
                     engine=EngineClient(self.engine.url, TOKEN)).startup()
            self.assertIsNotNone(ours.wait(10))
        finally:
            for p in (ours, other):
                if p.poll() is None:
                    p.kill()
                    p.wait()

    def test_daily_rotation_starts_a_new_session_when_idle(self):
        self.engine.queue(id=95, request=fake(sleep=0.1))
        w = self.start()
        self.wait(lambda: self.engine.finished(95), what="task 95")
        old = w.state["session_id"]
        self.day = "2026-11-02"
        self.wait(lambda: len(self.starts()) == 2, what="rotated start")
        argv = self.starts()[1]["argv"]
        self.assertIn("--session-id", argv)
        self.assertNotEqual(argv[argv.index("--session-id") + 1], old)

    def test_crashed_session_restarts(self):
        self.engine.queue(id=97, request=fake(crash=True))
        self.start()
        self.wait(lambda: self.engine.finished(97), what="task 97")
        self.assertEqual(self.engine.finished(97)[0]["summary"], W.SAY_RESTARTED)
        self.wait(lambda: len(self.starts()) >= 2, what="restart")


class TestEngineDown(WorkerCase):
    def test_backs_off_and_delivers_results_later(self):
        self.engine.queue(id=100, request=fake(sleep=0.1))
        w = self.start()
        self.wait(lambda: w._task(100) is not None, what="claimed")
        self.engine.down = True
        self.wait(lambda: w.backoff > 0, what="backoff")
        self.wait(lambda: w._task(100)["status"] == "done", what="report drained while down")
        self.assertFalse(self.engine.finished(100))
        self.engine.down = False
        self.wait(lambda: self.engine.finished(100), what="delivered after recovery")


class TestListed(WorkerCase):
    def setUp(self):
        super().setUp()
        self.repos = Path(self.tmp.name) / "repos"
        self.repos.mkdir()
        self.cfg.update(scope="listed", repos_root=str(self.repos), name="work laptop")

    def test_work_laptop_returns_summaries_only(self):
        f = self.repos / "notes.txt"
        f.write_text("x")
        self.engine.queue(id=110, request=f"look {fake(found=[str(f)])}", deliver="email_self")
        self.start()
        self.wait(lambda: self.engine.finished(110), what="task 110")
        self.assertEqual(self.engine.finished(110)[0]["found"], [])
        argv = self.starts()[0]["argv"]
        self.assertEqual([argv[i + 1] for i, a in enumerate(argv) if a == "--add-dir"], [str(self.repos)])
        self.assertNotIn("WebFetch", argv)
        # No Gmail on the work laptop (Kevin's inbox stays on the Alienware).
        self.assertFalse([a for a in argv if a.startswith("mcp__claude_ai_Gmail")])
        msg = [m for m in self.msgs() if m.startswith("[task 110]")][0]
        self.assertIn("no attachments from this laptop", msg)
        tok = read_json(self.paths.tokens / "110-email_self.json")
        att = self.repos / "notes.txt"
        FakeSMTP.sent = []
        orig = send_mail.SMTP_FACTORY
        send_mail.SMTP_FACTORY = FakeSMTP
        try:
            base = ["--task", "110", "--token", tok["token"], "--subject", "s", "--body", "summary"]
            self.assertEqual(send_mail.main(base + ["--to", "kevin", "--attach", str(att)], self.paths), 1)
            self.assertEqual(send_mail.main(base + ["--to", "dad@example.com"], self.paths), 1)
            self.assertEqual(send_mail.main(base + ["--to", "kevin"], self.paths), 0)
        finally:
            send_mail.SMTP_FACTORY = orig
        self.assertEqual(len(FakeSMTP.sent), 1)
        self.assertEqual(FakeSMTP.sent[0][1], ["kevin@example.com"])
        self.assertFalse(list(FakeSMTP.sent[0][0].iter_attachments()))
        with self.engine.lock:
            self.engine.files_requested = [{"task": 110, "file_id": 1, "path": str(f)}]
        time.sleep(0.8)
        self.assertEqual(self.engine.uploads, [])


class TestLogs(WorkerCase):
    def test_logs_never_hold_request_summary_or_path(self):
        f = Path(self.tmp.name) / "found-file-name.bin"
        f.write_bytes(b"1")
        self.engine.queue(id=120, request=f"{SECRET_REQUEST} {fake(summary=SECRET_SUMMARY, found=[str(f)])}")
        self.engine.queue(id=121, request=f"{SECRET_REQUEST} {fake(status='needs_confirmation', question=SECRET_SUMMARY)}")
        self.start()
        self.wait(lambda: self.engine.finished(120) and self.engine.finished(121), what="both")
        with self.engine.lock:
            self.engine.files_requested = [{"task": 120, "file_id": 3, "path": str(f)}]
        self.wait(lambda: self.engine.uploads, what="upload")
        self.stop_last()
        text = "".join(p.read_text(encoding="utf-8") for p in self.paths.logs.glob("*.log"))
        self.assertIn("task 120 finished done", text)
        for bad in (SECRET_REQUEST, SECRET_SUMMARY, "found-file-name", self.tmp.name):
            self.assertNotIn(bad, text)


if __name__ == "__main__":
    unittest.main()


class TestGmailAndAmazon(unittest.TestCase):
    """Kevin, 2026-11-04: Gmail first, Amazon's page as the live fallback, Alienware only."""

    def test_gmail_is_read_only_and_full_scope_only(self):
        import session as S

        self.assertTrue(set(S.GMAIL_READ) <= set(S.ALLOWED_FULL))
        self.assertFalse([t for t in S.ALLOWED_LISTED if "Gmail" in t])
        for tool in S.GMAIL_READ:
            self.assertRegex(tool, r"__(search_threads|get_thread|get_message)$")
        for verb in ("send", "draft", "label", "trash", "spam", "forward", "reply", "delete"):
            self.assertFalse([t for t in S.ALLOWED_FULL if verb in t.lower()], verb)

    def test_the_prompt_carries_rule_10_and_the_script_path(self):
        text = (Path(W.__file__).parent / "main_session_prompt.md").read_text(encoding="utf-8")
        self.assertIn("10. Amazon orders", text)
        self.assertIn("<<AMAZON>>", text)
        self.assertIn("ALWAYS run", text)
        self.assertIn("Never prices, order numbers", text)
