"""Memory-review ticket 16: the laptop half of the daily memory review.

review_memories.py against a fake engine (real HTTP), the ask-list hook's memory-review rules,
and the worker running a `memory_review` task through its ordinary path with a fake `claude`.
Every memory here is invented. A sentinel planted in the fake context must never reach stdout,
the worker log, a heartbeat or a finish payload.

    python -m unittest discover -s tools/claude_session -p "test_*.py" -v
"""
from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import asklist_hook as H  # noqa: E402
import review_memories as R  # noqa: E402
import worker as W  # noqa: E402
from common import HOME_ENV, Paths, read_json, write_json_atomic  # noqa: E402
from fake_engine import TOKEN, FakeEngine  # noqa: E402
from test_worker import WorkerCase, fake  # noqa: E402

SENTINEL = "Quokkaberry"
CONTEXT = {
    "context_id": "6f0c3c1e-0000-4000-8000-000000000001",
    "today": "2026-11-09",
    "mode": "shadow",
    "memories": [
        {"handle": "m1", "text": f"Sam's cat is called {SENTINEL}."},
        {"handle": "m2", "text": f"{SENTINEL} is Sam's cat."},
    ],
    "conversations": [{"label": "L1", "date": "2026-11-08", "text": f"User: {SENTINEL} again."}],
    "exact_duplicates": [],
    "pending_handles": [],
}
GOOD = [{"action": "merge", "handles": ["m1", "m2"], "new_text": f"Sam's cat is called {SENTINEL}.",
         "reason": "Both name the same cat.", "confidence": "high"}]


class ScriptCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name) / "legion-claude"
        self.old_home = os.environ.get(HOME_ENV)
        os.environ[HOME_ENV] = str(self.home)
        self.paths = Paths(self.home).ensure()
        self.engine = FakeEngine()
        self.engine.review_context = CONTEXT
        write_json_atomic(self.paths.config, {"name": "Alienware", "scope": "full",
                                              "engine_url": self.engine.url, "token": TOKEN})
        self.work = self.paths.task_work(7)

    def tearDown(self):
        self.engine.close()
        if self.old_home is None:
            os.environ.pop(HOME_ENV, None)
        else:
            os.environ[HOME_ENV] = self.old_home
        self.tmp.cleanup()

    def run_script(self, *argv) -> tuple[int, str]:
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = R.main(list(argv))
        return code, out.getvalue() + err.getvalue()

    def test_fetch_then_submit_round_trip_deletes_both_files(self):
        code, said = self.run_script("fetch", "--task", "7")
        self.assertEqual(code, 0, said)
        self.assertIn("2 memories, 1 conversations", said)
        self.assertTrue((self.work / "context.json").exists())
        write_json_atomic(self.work / "changes.json", GOOD)
        write_json_atomic(self.work / "usage.json", {"total_tokens": 1234, "note": "dropped"})
        code, said2 = self.run_script("submit", "--task", "7")
        self.assertEqual(code, 0, said2)
        self.assertEqual(said2.strip(), "Memory review: 1 merged, 2 to check.")
        sent = self.engine.review_submits[0]
        self.assertEqual(sent["context_id"], CONTEXT["context_id"])
        self.assertEqual(sent["usage"], {"total_tokens": 1234})
        self.assertFalse((self.work / "context.json").exists())
        self.assertFalse((self.work / "changes.json").exists())
        self.assertEqual(read_json(self.work / "result.json")["words"],
                         "Memory review: 1 merged, 2 to check.")
        self.assertNotIn(SENTINEL, said + said2)

    def test_dry_run_keeps_the_files_and_prints_verdicts_without_text(self):
        self.run_script("fetch", "--task", "7")
        write_json_atomic(self.work / "changes.json", GOOD)
        self.engine.review_answer = {**self.engine.review_answer, "verdicts": [
            {"index": 0, "action": "merge", "handles": ["m1", "m2"], "ids": ["0a1b2c3d", "4e5f6a7b"],
             "status": "pending", "gate": "passed"}]}
        code, said = self.run_script("submit", "--task", "7", "--dry-run")
        self.assertEqual(code, 0, said)
        self.assertIn("0: merge m1,m2 [0a1b2c3d,4e5f6a7b] -> pending (passed)", said)
        self.assertTrue(self.engine.review_submits[0]["dry_run"])
        self.assertTrue((self.work / "context.json").exists())
        self.assertTrue((self.work / "changes.json").exists())
        self.assertNotIn(SENTINEL, said)

    def test_a_malformed_change_list_fails_locally_before_any_post(self):
        self.run_script("fetch", "--task", "7")
        write_json_atomic(self.work / "changes.json", [
            {"action": "merge", "handles": ["m1"], "new_text": SENTINEL, "reason": "x",
             "confidence": "high"},
            {"action": "expire", "handle": "m9", "event_date": "Friday", "reason": "x",
             "confidence": "sure"},
        ])
        code, said = self.run_script("submit", "--task", "7")
        self.assertEqual(code, 1)
        self.assertIn("change 0: a merge names two or more handles", said)
        self.assertIn("m9 not in context.json", said)
        self.assertIn("YYYY-MM-DD", said)
        self.assertEqual(self.engine.review_submits, [])
        self.assertNotIn(SENTINEL, said)

    def test_engine_down_fails_in_words_once_with_no_retry(self):
        self.engine.down = True
        code, said = self.run_script("fetch", "--task", "7")
        self.assertEqual(code, 1)
        self.assertIn("HTTP 503", said)
        self.assertEqual(self.engine.requests, 1)
        self.assertIn("HTTP 503", read_json(self.work / "result.json")["error"])

    def test_a_refusal_says_the_engines_words(self):
        self.engine.review_status = 403
        code, said = self.run_script("fetch", "--task", "7")
        self.assertEqual(code, 1)
        self.assertIn("The review is off.", said)

    def test_run_now_queues_one(self):
        code, said = self.run_script("run-now")
        self.assertEqual((code, self.engine.review_runs), (0, 1))
        self.assertIn("Queued", said)


class HookCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.paths = Paths(self.root / "home").ensure()
        self.work = self.paths.task_work(7)
        self.work.mkdir()
        write_json_atomic(self.paths.state, {"tasks": {
            "7": {"id": 7, "kind": "memory_review", "status": "running"},
            "8": {"id": 8, "kind": "ask", "status": "running"}}})
        write_json_atomic(self.paths.agents, {"agentR": 7, "agentA": 8})

    def tearDown(self):
        self.tmp.cleanup()

    def decide(self, tool, inp, agent):
        out, _rule = H.decide({"tool_name": tool, "tool_input": inp, "cwd": str(self.work),
                               "agent_id": agent}, self.paths, {"scope": "full"})
        return "deny" if out else "pass"

    def test_the_review_may_not_run_git_or_write_outside_its_folder(self):
        self.assertEqual(self.decide("Bash", {"command": "git status"}, "agentR"), "deny")
        self.assertEqual(self.decide("PowerShell", {"command": "cd x; git.exe log"}, "agentR"),
                         "deny")
        outside = self.root / "notes.md"
        self.assertEqual(self.decide("Write", {"file_path": str(outside)}, "agentR"), "deny")
        self.assertEqual(
            self.decide("Write", {"file_path": str(self.work / "changes.json")}, "agentR"), "pass")
        self.assertEqual(self.decide("Bash", {"command": "python review_memories.py fetch "
                                                         "--task 7"}, "agentR"), "pass")

    def test_an_ordinary_task_is_untouched(self):
        self.assertEqual(self.decide("Bash", {"command": "git status"}, "agentA"), "pass")
        self.assertEqual(self.decide("Write", {"file_path": str(self.root / "new.md")}, "agentA"),
                         "pass")


class TestReviewTask(WorkerCase):
    def test_a_memory_review_runs_the_ordinary_path_with_fixed_progress_and_summary(self):
        step = f"Merging {SENTINEL} rows"
        work = self.paths.task_work(80)
        write_json_atomic(work / "result.json", {"words": "Memory review: 2 merged, 1 to check."})
        write_json_atomic(work / "context.json", CONTEXT)
        blob = fake(sleep=1.0, progress=step, summary=SENTINEL)
        self.engine.queue(id=80, kind="memory_review",
                          request=f"Run the daily memory review. {blob}")
        self.start(_idle=lambda: 777)
        self.wait(lambda: self.engine.finished(80), what="task 80")
        done = self.engine.finished(80)[0]
        self.assertEqual((done["status"], done["summary"]),
                         ("done", "Memory review: 2 merged, 1 to check."))
        self.assertEqual(done["found"], [])
        beats = self.engine.heartbeats
        self.assertTrue(any(h.get("progress", {}).get("80") == W.REVIEW_PROGRESS for h in beats))
        self.assertEqual(beats[0]["input_idle_s"], 777)
        self.assertIn("on_mains", beats[0])
        self.assertNotIn(SENTINEL, json.dumps(beats))
        self.assertFalse((work / "context.json").exists())
        self.assertTrue((work / "instructions.md").exists())
        msg = next(m for m in self.msgs() if m.startswith("[task 80]"))
        self.assertIn("Memory review (kind memory_review)", msg)
        self.assertIn("instructions.md", msg)
        self.stop_last()
        text = "".join(p.read_text(encoding="utf-8") for p in self.paths.logs.glob("*.log"))
        self.assertNotIn(SENTINEL, text)

    def test_a_review_with_no_result_fails_in_fixed_words(self):
        self.engine.queue(id=81, kind="memory_review",
                          request=f"Run the daily memory review. {fake(sleep=0.2, summary=SENTINEL)}")
        self.start()
        self.wait(lambda: self.engine.finished(81), what="task 81")
        done = self.engine.finished(81)[0]
        self.assertEqual((done["status"], done["summary"]), ("failed", W.SAY_REVIEW_NO_RESULT))

    def test_the_instructions_name_the_scripts_and_the_task(self):
        w = W.Worker(self.paths, {**self.cfg}, power=None, drives=lambda: ["C:\\"],
                     base_env=self.env)
        work = self.paths.task_work(5)
        work.mkdir(parents=True, exist_ok=True)
        w.review_message({"id": 5, "request": "Run it.", "kind": "memory_review"}, work)
        text = (work / "instructions.md").read_text(encoding="utf-8")
        self.assertIn("review_memories.py", text)
        self.assertIn("fetch --task 5", text)
        self.assertIn("report.py", text)
        self.assertNotIn("<<", text)


if __name__ == "__main__":
    unittest.main()
