"""Open loops, laptop half (ADR 0085): review_memories.py's loops.json against a fake engine (real
HTTP), and the worker deleting loops.json with the other review files. Every task here is
invented. A sentinel planted in the fake context must never reach stdout, the worker log, a
heartbeat or a finish payload.

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

import review_memories as R  # noqa: E402
from common import HOME_ENV, Paths, read_json, write_json_atomic  # noqa: E402
from fake_engine import TOKEN, FakeEngine  # noqa: E402
from test_worker import WorkerCase, fake  # noqa: E402

SENTINEL = "Wombatford"
ON = {
    "context_id": "6f0c3c1e-0000-4000-8000-000000000002",
    "today": "2026-11-09",
    "mode": "shadow",
    "memories": [],
    "conversations": [],
    "exact_duplicates": [],
    "pending_handles": [],
    "open_loops": {
        "on": True, "cap": 5, "already_proposed": [],
        "own_words": [{"label": "W1", "text": f"I need to call the dentist in {SENTINEL}."}],
    },
}
OFF = {**ON, "open_loops": {"on": False, "why": "Proposed to-dos are off on this engine."}}
LOOP = [{"text": "call the dentist", "evidence": ["W1"], "reason": "Sam named a call to make."}]


class LoopsScriptCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = Path(self.tmp.name) / "legion-claude"
        self.old_home = os.environ.get(HOME_ENV)
        os.environ[HOME_ENV] = str(self.home)
        self.paths = Paths(self.home).ensure()
        self.engine = FakeEngine()
        self.engine.review_context = ON
        self.engine.review_answer = {
            "words": "nothing to change. Open loops: 1 to-do proposed.", "mode": "shadow",
            "counts": {}, "open_loops": {"proposed": 1, "verdicts": [
                {"index": 0, "status": "proposed", "gate": "passed"}]},
        }
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

    def test_fetch_says_open_loops_are_on_and_where_to_write(self):
        code, said = self.run_script("fetch", "--task", "7")
        self.assertEqual(code, 0, said)
        self.assertIn("Open loops on: 1 of his own conversations, at most 5", said)
        self.assertIn("loops.json", said)
        self.assertNotIn(SENTINEL, said)

    def test_submit_sends_loops_and_deletes_every_text_file(self):
        self.run_script("fetch", "--task", "7")
        write_json_atomic(self.work / "changes.json", [])
        write_json_atomic(self.work / "loops.json", LOOP)
        code, said = self.run_script("submit", "--task", "7")
        self.assertEqual(code, 0, said)
        self.assertEqual(said.strip(), "Memory review: nothing to change. Open loops: 1 to-do proposed.")
        self.assertEqual(self.engine.review_submits[0]["open_loops"], LOOP)
        for name in ("context.json", "changes.json", "loops.json"):
            self.assertFalse((self.work / name).exists(), name)
        self.assertNotIn(SENTINEL, said)

    def test_no_loops_file_sends_an_empty_list_when_on(self):
        self.run_script("fetch", "--task", "7")
        write_json_atomic(self.work / "changes.json", [])
        code, said = self.run_script("submit", "--task", "7")
        self.assertEqual(code, 0, said)
        self.assertEqual(self.engine.review_submits[0]["open_loops"], [])

    def test_a_bad_loop_fails_locally_before_any_post(self):
        self.run_script("fetch", "--task", "7")
        write_json_atomic(self.work / "changes.json", [])
        write_json_atomic(self.work / "loops.json", [
            {"text": SENTINEL, "evidence": ["W9"]}, {"text": "", "evidence": ["W1"]}])
        code, said = self.run_script("submit", "--task", "7")
        self.assertEqual(code, 1)
        self.assertIn("loop 0: evidence must list W labels", said)
        self.assertIn("loop 1: needs text", said)
        self.assertEqual(self.engine.review_submits, [])
        self.assertNotIn(SENTINEL, said)

    def test_loops_when_off_fail_locally_and_none_are_sent(self):
        self.engine.review_context = OFF
        self.run_script("fetch", "--task", "7")
        write_json_atomic(self.work / "changes.json", [])
        write_json_atomic(self.work / "loops.json", LOOP)
        code, said = self.run_script("submit", "--task", "7")
        self.assertEqual(code, 1)
        self.assertIn("Open loops are off tonight", said)
        self.assertEqual(self.engine.review_submits, [])

    def test_off_without_a_loops_file_sends_no_loops_key(self):
        self.engine.review_context = OFF
        self.run_script("fetch", "--task", "7")
        write_json_atomic(self.work / "changes.json", [])
        code, _said = self.run_script("submit", "--task", "7")
        self.assertEqual(code, 0)
        self.assertNotIn("open_loops", self.engine.review_submits[0])

    def test_a_dry_run_prints_loop_verdicts_without_text_and_keeps_the_files(self):
        self.run_script("fetch", "--task", "7")
        write_json_atomic(self.work / "changes.json", [])
        write_json_atomic(self.work / "loops.json", LOOP)
        code, said = self.run_script("submit", "--task", "7", "--dry-run")
        self.assertEqual(code, 0, said)
        self.assertIn("loop 0: -> proposed (passed)", said)
        self.assertTrue((self.work / "loops.json").exists())
        self.assertNotIn("dentist", said)


class TestReviewTaskDeletesLoops(WorkerCase):
    def test_the_worker_deletes_loops_json_when_a_review_finishes(self):
        work = self.paths.task_work(82)
        write_json_atomic(work / "result.json", {"words": "Memory review: nothing to change."})
        write_json_atomic(work / "context.json", ON)
        write_json_atomic(work / "loops.json", LOOP)
        self.engine.queue(id=82, kind="memory_review",
                          request=f"Run the daily memory review. {fake(sleep=0.5, summary=SENTINEL)}")
        self.start(_idle=lambda: 777)
        self.wait(lambda: self.engine.finished(82), what="task 82")
        done = self.engine.finished(82)[0]
        self.assertEqual(done["status"], "done")
        self.assertFalse((work / "loops.json").exists())
        self.assertFalse((work / "context.json").exists())
        self.assertNotIn(SENTINEL, json.dumps(self.engine.heartbeats) + json.dumps(done))
        self.stop_last()
        text = "".join(p.read_text(encoding="utf-8") for p in self.paths.logs.glob("*.log"))
        self.assertNotIn(SENTINEL, text)
        self.assertIsNone(read_json(work / "loops.json", None))


if __name__ == "__main__":
    unittest.main()
