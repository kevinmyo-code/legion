"""Ticket 06: session_hook.py against the fake engine and a temp settings file."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import session_hook as sh  # noqa: E402
from common import Paths, write_json_atomic  # noqa: E402
from fake_engine import TOKEN, FakeEngine  # noqa: E402
from session import WORKER_MARKER, child_env  # noqa: E402


def event(name, **kw):
    e = {"hook_event_name": name, "session_id": "abc-123", "cwd": r"C:\repos\legion"}
    e.update(kw)
    return json.dumps(e)


class HookTest(unittest.TestCase):
    def setUp(self):
        self.engine = FakeEngine()
        self.tmp = tempfile.TemporaryDirectory()
        self.paths = Paths(Path(self.tmp.name))
        self.config(scope="full")

    def tearDown(self):
        self.engine.close()
        self.tmp.cleanup()

    def config(self, **kw):
        cfg = {"name": "Alienware", "engine_url": self.engine.url, "token": TOKEN}
        cfg.update(kw)
        write_json_atomic(self.paths.config, cfg)

    def run_hook(self, text, env=None):
        return sh.report(text, env={} if env is None else env, paths=self.paths)

    def test_each_event_maps_to_state(self):
        want = {"SessionStart": "working", "UserPromptSubmit": "working",
                "Notification": "waiting", "Stop": "idle", "SessionEnd": "ended"}
        for name in want:
            self.run_hook(event(name, prompt="hi"))
        self.assertEqual([s["state"] for s in self.engine.sessions], list(want.values()))
        s = self.engine.sessions[0]
        self.assertEqual((s["machine"], s["session_id"], s["project"]),
                         ("Alienware", "abc-123", "legion"))
        self.assertTrue(s["at"])
        self.assertIsNone(self.run_hook(event("PreToolUse")))

    def test_marker_names_match(self):
        self.assertEqual(sh.WORKER_MARKER, WORKER_MARKER)

    def test_full_sends_120_char_summary(self):
        self.run_hook(event("UserPromptSubmit", prompt="x" * 300))
        self.assertEqual(self.engine.sessions[0]["last_prompt_summary"], "x" * 120)

    def test_listed_drops_summary(self):
        self.config(scope="listed", name="work laptop")
        self.run_hook(event("UserPromptSubmit", prompt="secret work thing"))
        self.assertIsNone(self.engine.sessions[0]["last_prompt_summary"])

    def test_timeout_never_blocks(self):
        self.engine.delay = 10
        t = time.monotonic()
        self.assertIsNotNone(self.run_hook(event("Stop")))
        self.assertLess(time.monotonic() - t, 4)

    def test_engine_down_is_quiet(self):
        self.engine.down = True
        self.assertIsNotNone(self.run_hook(event("Stop")))

    def test_no_config_is_noop(self):
        os.remove(self.paths.config)
        self.assertIsNone(self.run_hook(event("Stop")))
        self.assertEqual(self.engine.requests, 0)

    def test_worker_session_skipped(self):
        self.assertIsNone(self.run_hook(event("Stop"), env={WORKER_MARKER: "1"}))
        self.assertEqual(self.engine.requests, 0)
        self.assertEqual(child_env({}, Path(self.tmp.name)).get(WORKER_MARKER), "1")

    def test_process_exits_0_silently(self):
        env = dict(os.environ, LEGION_CLAUDE_HOME=self.tmp.name)
        env.pop(WORKER_MARKER, None)
        for text in (event("Stop"), "not json", ""):
            p = subprocess.run([sys.executable, str(HERE / "session_hook.py")], input=text,
                               capture_output=True, text=True, env=env, timeout=20)
            self.assertEqual((p.returncode, p.stdout, p.stderr), (0, "", ""))
        self.assertEqual(len(self.engine.sessions), 1)


class SettingsMergeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "settings.json"
        self.mine = {"matcher": "Bash", "hooks": [{"type": "command", "command": "kevin.cmd"}]}
        self.path.write_text(json.dumps({"model": "opus", "hooks": {
            "Stop": [{"hooks": [{"type": "command", "command": "notify.ps1"}]}],
            "PreToolUse": [self.mine]}}))
        self.original = json.loads(self.path.read_text())

    def tearDown(self):
        self.tmp.cleanup()

    def test_merge_keeps_existing_twice_safe_and_uninstall(self):
        cmd = sh.hook_command("python")
        self.assertTrue(sh.install_settings(self.path, cmd))
        once = json.loads(self.path.read_text())
        self.assertEqual(once["model"], "opus")
        self.assertEqual(once["hooks"]["PreToolUse"], [self.mine])
        self.assertEqual(once["hooks"]["Stop"][0]["hooks"][0]["command"], "notify.ps1")
        for ev in sh.STATES:
            ours = [h for g in once["hooks"][ev] for h in g["hooks"]
                    if "session_hook.py" in h["command"]]
            self.assertEqual(len(ours), 1, ev)
        self.assertTrue(list(Path(self.tmp.name).glob("settings.json.bak-*")))
        self.assertFalse(sh.install_settings(self.path, cmd))
        self.assertEqual(json.loads(self.path.read_text()), once)
        self.assertTrue(sh.uninstall_settings(self.path))
        self.assertEqual(json.loads(self.path.read_text()), self.original)
        self.assertFalse(sh.uninstall_settings(self.path))

    def test_missing_file_created(self):
        p = Path(self.tmp.name) / "new.json"
        self.assertTrue(sh.install_settings(p, "python session_hook.py"))
        self.assertEqual(set(json.loads(p.read_text())["hooks"]), set(sh.STATES))


if __name__ == "__main__":
    unittest.main()
