"""alfred-sessions re-review round 2 (2026-11-22): each finding as a test. Made-up data only;
never the real `claude`, a real transcript or a real session."""
from __future__ import annotations

import json
import subprocess
import sys
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.append(str(HERE))

import asklist_hook as H  # noqa: E402
import containment as C  # noqa: E402
import sessions as S  # noqa: E402
import test_sessions as T  # noqa: E402
from common import write_json_atomic  # noqa: E402

PY = T.PY
HOME_PROJECTS = Path.home() / ".claude" / "projects"


class TestCourierGateIsolated(T.Base):
    """Finding 1: the gate runs only as `python -I -S`, and nothing in the env steers it."""

    def run_hook(self, *flags):
        ev = json.dumps({"tool_name": "ListAgents", "tool_input": {}})
        env = {"LEGION_CLAUDE_HOME": str(self.paths.root), "SYSTEMROOT": ""}
        return subprocess.run([sys.executable, *flags, str(HERE / "courier_hook.py")], input=ev,
                              text=True, capture_output=True, timeout=60, env=env)

    def test_not_isolated_refuses_everything(self):
        p = self.run_hook()
        self.assertEqual(p.returncode, 2)
        self.assertIn("not running isolated", p.stdout)
        self.assertEqual(self.run_hook("-I", "-S").returncode, 0)

    def test_settings_and_env(self):
        s = json.loads(S.courier_settings(self.paths, PY).read_text())
        cmd = s["hooks"]["PreToolUse"][0]["hooks"][0]["command"]
        self.assertIn(" -I -S ", cmd)
        env = S.courier_env({"PYTHONPATH": "x", "PYTHONSTARTUP": "y", "pythonhome": "z",
                             "PATH": "p"}, self.paths)
        self.assertEqual({k for k in env if k.upper().startswith("PYTHON")}, set())
        self.assertEqual(env["PATH"], "p")


class TestOnlyRunsOfOursAreJudged(T.Base):
    """Finding 2: a project's own sessions.py, or a test or a git add of one, is not ours."""

    def setUp(self):
        super().setUp()
        write_json_atomic(self.paths.state, {"tasks": {"41": {"id": 41, "kind": "ask",
                                                              "status": "running"}}})
        write_json_atomic(self.paths.agents, {"agentA": 41})

    def decide(self, tool, command):
        return H.decide({"tool_name": tool, "tool_input": {"command": command},
                         "agent_id": "agentA", "cwd": str(self.work)}, self.paths, self.cfg)

    def test_other_sessions_files_pass_untainted(self):
        for cmd in ("python -m unittest test_sessions.py", "git add app/sessions.py",
                    "python app/sessions.py read --all", "git diff -- web/sessions.py"):
            for tool in ("Bash", "PowerShell"):
                with self.subTest(cmd=cmd, tool=tool):
                    _out, rule = self.decide(tool, cmd)
                    self.assertNotEqual(rule, "sessions")
        self.assertFalse(C.tainted(self.paths, 41, "agentA", {}))

    def test_a_run_of_ours_in_another_spelling_is_still_denied(self):
        own = T.ours("sessions.py")
        gb = "/" + own[0].lower() + own[2:]  # Git Bash's /c/... spelling
        _out, rule = self.decide("Bash", f"{PY} {gb} read --task 41 --session garden | cat")
        self.assertEqual(rule, "sessions")


class TestNothingBlankGoes(T.Base):
    """Finding 3: an allowlist of what the card shows as itself."""

    def test_blank_looking_and_ignorable_characters_are_refused(self):
        for bad in ("Water the beds.️", "Water the beds.\U000e0100", "Water͏the beds",
                    "Waterㅤthe beds", "Water⠀the beds", "Water the beds",
                    "Water the beds", "Waterᅟthe beds", "Water the beds"):
            with self.subTest(bad=repr(bad)[-12:]):
                with self.assertRaises(S.Refused):
                    S.ask(self.paths, self.cfg, 41, "garden", self.msg(bad), self.rows, 1.0)

    def test_ordinary_text_still_goes(self):
        text = "Ship the résumé page ✓ at 6:30, then rest!\nThanks."
        S.ask(self.paths, self.cfg, 41, "garden", self.msg(text), self.rows, 1.0)
        self.assertEqual(S.pinned_plan(self.paths, 41)["text"], text)


class TestMessageFileCap(T.Base):
    """Finding 8: the message file is read through common.task_file, 64 KB at most."""

    def test_a_huge_file_is_refused_unread(self):
        f = self.paths.task_work(41) / "msg.txt"
        f.write_bytes(b"x" * (S.TEXT_FILE_CAP + 1))
        with self.assertRaises(S.Refused) as e:
            S.ask(self.paths, self.cfg, 41, "garden", str(f), self.rows, 1.0)
        self.assertIn("KB", str(e.exception))


class TestTranscriptsByAnyRoute(T.Base):
    """Findings 4b and 6."""

    def setUp(self):
        super().setUp()
        write_json_atomic(self.paths.state, {"tasks": {"41": {"id": 41, "kind": "ask",
                                                              "status": "running"}}})
        write_json_atomic(self.paths.agents, {"agentA": 41})

    def decide(self, tool, inp):
        return H.decide({"tool_name": tool, "tool_input": inp, "agent_id": "agentA",
                         "cwd": str(self.work)}, self.paths, self.cfg)

    def sources(self):
        from common import read_json
        return (read_json(self.paths.taint / "task-41.json", {}) or {}).get("sources") or []

    def test_cd_then_projects_taints(self):
        self.assertIsNone(self.decide("PowerShell", {"command":
                                                     "cd $HOME\\.claude; Get-ChildItem projects"})[0])
        self.assertIn("session_transcript", self.sources())

    def test_recursive_searches_naming_claude_are_denied(self):
        for cmd in ("rg token ~/.claude", "Get-ChildItem -Recurse $HOME\\.claude",
                    "grep -r token ~/.claude/projects", "findstr /s token %USERPROFILE%\\.claude\\*"):
            with self.subTest(cmd=cmd):
                _out, rule = self.decide("PowerShell", {"command": cmd})
                self.assertEqual(rule, "worker transcripts")

    def test_a_read_tool_read_of_a_transcript_is_a_session_read(self):
        t = HOME_PROJECTS / "C--repos-garden" / "s.jsonl"
        self.assertIsNone(self.decide("Read", {"file_path": str(t)})[0])
        self.assertIn("session_transcript", self.sources())


class TestCapsAfterAnyRead(T.TestWorkerCard):
    """Finding 6: summary and question are capped once a transcript was read."""

    def test_question_is_capped_too(self):
        t = {"id": 41, "parent": None, "request": "x", "status": "running", "kind": "ask",
             "started_at": time.time(), "turns": 0}
        self.w.state["tasks"]["41"] = t
        C.mark(self.paths, 41, "agentA", "session_transcript")
        self.w.finish(t, "needs_confirmation", "", "q" * 5000)
        self.assertEqual(len(self.w.state["outbox"][-1]["payload"]["question"]), S.SUMMARY_MAX)


class TestMoreSecretShapes(T.Base):
    """Finding 9."""

    def test_said_named_and_bearer_secrets_are_masked(self):
        self.transcript(T.SID_A, [{"type": "assistant", "message": {"content": [
            {"type": "text", "text": "my password is hunter2pass and OPENAI_KEY=sk-short123 and "
                                     "Authorization: Bearer abcdefghijklmnopqrst"}]}}])
        out = S.read(self.paths, self.cfg, "garden", 5, self.rows, self.projects)
        for gone in ("hunter2pass", "sk-short123", "abcdefghijklmnopqrst"):
            self.assertNotIn(gone, out)


if __name__ == "__main__":
    unittest.main()
