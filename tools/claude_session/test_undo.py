"""alfred-builds ticket 05: undo.py with the same fakes as test_ship.py (a bare git remote, fake
gcloud, fake publisher). Never the real Cloud Run or engine.

    python -m pytest tools/claude_session/test_undo.py -q
"""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import undo  # noqa: E402
from common import write_json_atomic  # noqa: E402
from test_ship import ShipBase, commit, git  # noqa: E402


class UndoBase(ShipBase):
    def setUp(self):
        super().setUp()
        self.cfg["ship"]["repo"] = str(self.wt)
        write_json_atomic(self.paths.config, self.cfg)

    def tok(self, task, parent):
        write_json_atomic(self.paths.tokens / f"{task}-yes.json", {
            "token": f"tok{task}", "task": task, "parent": parent, "kind": "yes",
            "expires_at": self.world.now() + 600})

    def ship_one(self, task, kind, branch, notes):
        code = self.main("ask", "--task", str(task), "--kind", kind, "--branch", branch,
                         "--worktree", str(self.wt), "--notes", notes)
        self.assertEqual(code, 0, self.said)
        self.tok(task + 1, task)
        self.assertEqual(self.main("run", "--task", str(task + 1), "--token", f"tok{task + 1}"), 0,
                         self.said)

    def undo(self, *argv):
        self.said = []
        return undo.main(list(argv), self.paths, self.world, say=self.said.append)

    def ships(self):
        return [json.loads(line) for line in self.paths.ships.read_text().splitlines()]


class TestUndoEngine(UndoBase):
    def test_undo_moves_traffic_back_and_reverts_dev_and_main(self):
        self.ship_one(41, "engine", "fix/timer", "Alfred announces a timer once")
        self.assertEqual(self.undo("ask", "--task", "50"), 0, self.said)
        self.assertEqual(self.said[-1], "Undo Alfred announces a timer once? The engine goes back "
                                        "to revision legion-00041-abc and the change is reverted on dev.")
        self.world.events = []
        self.tok(51, 50)
        self.assertEqual(self.undo("run", "--task", "51", "--token", "tok51"), 0, self.said)
        self.assertEqual(self.world.events, ["update-traffic legion-00041-abc=100", "health",
                                             "push dev", "push main"])
        self.assertEqual(git(self.origin, "show", "dev:server/app.py"), "x = 1")
        self.assertEqual(git(self.origin, "rev-parse", "dev"), git(self.origin, "rev-parse", "main"))
        self.assertIn("Revert", git(self.origin, "log", "-1", "--format=%s", "dev"))
        rec = self.ships()[-1]
        self.assertEqual((rec["kind"], rec["undoes"]), ("undo", self.ships()[0]["id"]))
        self.assertEqual(rec["revision"], {"from": "legion-00042-new", "to": "legion-00041-abc"})
        self.assertIn("the engine is back on legion-00041-abc", self.said[-1])
        # nothing left to undo
        self.assertEqual(self.undo("ask", "--task", "52"), 1)
        self.assertIn("no ship to undo", self.said[-1])

    def test_the_token_is_required(self):
        self.ship_one(41, "engine", "fix/timer", "Alfred announces a timer once")
        self.undo("ask", "--task", "50")
        self.world.events = []
        self.assertEqual(self.undo("run", "--task", "51", "--token", "guess"), 1)
        self.assertIn("no confirmation from Kevin", self.said[-1])
        self.assertEqual(self.world.events, [])

    def test_a_newer_ship_after_the_question_is_refused(self):
        self.ship_one(41, "engine", "fix/timer", "Alfred announces a timer once")
        self.undo("ask", "--task", "50")
        git(self.wt, "checkout", "-q", "-b", "fix/second")
        commit(self.wt, "server/b.py", "y = 1\n", "second")
        self.ship_one(60, "engine", "fix/second", "Second fix")
        self.tok(51, 50)
        self.assertEqual(self.undo("run", "--task", "51", "--token", "tok51"), 1)
        self.assertIn("landed since the question", self.said[-1])


class TestUndoPhone(UndoBase):
    def phone_branch(self, name, text):
        git(self.wt, "fetch", "-q", "origin")
        git(self.wt, "checkout", "-q", "-b", name, "origin/dev")
        commit(self.wt, "app/src/Main.kt", text, name)

    def test_phone_undo_rebuilds_the_previous_ship_with_a_new_release(self):
        self.phone_branch("fix/phone-one", "// one\n")
        self.ship_one(41, "phone", "fix/phone-one", "First phone fix")
        first = self.ships()[0]["release"]["built_from"]
        self.phone_branch("fix/phone-two", "// two\n")
        self.ship_one(60, "phone", "fix/phone-two", "Second phone fix")
        self.assertEqual(self.undo("ask", "--task", "70"), 0, self.said)
        self.assertIn("Your phone gets the previous build again", self.said[-1])
        self.world.events = []
        self.tok(71, 70)
        self.assertEqual(self.undo("run", "--task", "71", "--token", "tok71"), 0, self.said)
        self.assertEqual(self.world.events[-2:], ["assembleFast", "release undo of Second phone fix"])
        rec = self.ships()[-1]
        self.assertEqual(rec["release"]["built_from"], first)
        self.assertIn("puts back the previous build", self.said[-1])

    def test_no_earlier_phone_build_is_said(self):
        self.phone_branch("fix/phone-one", "// one\n")
        self.ship_one(41, "phone", "fix/phone-one", "First phone fix")
        self.undo("ask", "--task", "50")
        self.assertIn("no earlier phone build", self.said[-1])
        self.tok(51, 50)
        self.assertEqual(self.undo("run", "--task", "51", "--token", "tok51"), 0, self.said)
        self.assertIn("But there is no earlier phone build to go back to", self.said[-1])


class TestUndoContent(UndoBase):
    def test_a_book_leaves_the_library(self):
        epub = self.paths.task_work(41) / "d.epub"
        epub.parent.mkdir(parents=True, exist_ok=True)
        epub.write_bytes(b"PK")
        self.main("ask", "--task", "41", "--kind", "content", "--file", str(epub), "--title",
                  "Dracula", "--licence-note", "public domain (Project Gutenberg)",
                  "--source-url", "https://www.gutenberg.org/ebooks/345")
        self.tok(42, 41)
        self.assertEqual(self.main("run", "--task", "42", "--token", "tok42"), 0, self.said)
        self.undo("ask", "--task", "50")
        self.assertIn("Dracula leaves your Books", self.said[-1])
        self.tok(51, 50)
        self.assertEqual(self.undo("run", "--task", "51", "--token", "tok51"), 0, self.said)
        self.assertEqual(self.world.http_calls[-1], ("DELETE", "https://legion.example/api/books/library/5"))


if __name__ == "__main__":
    unittest.main()
