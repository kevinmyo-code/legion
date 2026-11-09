"""B1 (2026-11-09): the warm worktree. Real git (a bare origin in a temp folder), no Gradle.

    python -m pytest tools/claude_session/test_warm_worktree.py -q
"""
from __future__ import annotations

import io
import subprocess
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import warm_worktree as WW  # noqa: E402
from common import Paths, read_json, write_json_atomic  # noqa: E402


def git(cwd, *args) -> str:
    p = subprocess.run(["git", "-C", str(cwd), *args], capture_output=True, text=True)
    if p.returncode != 0:
        raise AssertionError(f"git {' '.join(args)}: {p.stderr}")
    return p.stdout.strip()


def ident(repo):
    git(repo, "config", "user.email", "t@example.com")
    git(repo, "config", "user.name", "Test")
    git(repo, "config", "commit.gpgsign", "false")


def commit(cwd, rel, text, msg):
    f = Path(cwd) / rel
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(text, encoding="utf-8")
    git(cwd, "add", rel)
    git(cwd, "commit", "-q", "-m", msg)


class Base(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.tmp = Path(self.tmpdir.name)
        self.origin = self.tmp / "origin.git"
        subprocess.run(["git", "init", "-q", "--bare", str(self.origin)], check=True)
        seed = self.tmp / "seed"
        subprocess.run(["git", "init", "-q", "-b", "dev", str(seed)], check=True)
        ident(seed)
        commit(seed, ".gitignore", "build/\nlocal.properties\n", "ignore")
        commit(seed, "app/Main.kt", "fun main() {}\n", "seed")
        git(seed, "remote", "add", "origin", str(self.origin))
        git(seed, "push", "-q", "origin", "dev")
        self.seed = seed
        self.repo = self.tmp / "legion"
        subprocess.run(["git", "clone", "-q", "-b", "dev", str(self.origin), str(self.repo)], check=True)
        ident(self.repo)
        (self.repo / "local.properties").write_text("sdk.dir=C:/sdk\n")
        self.paths = Paths(self.tmp / "home").ensure()
        self.cfg = {"scope": "full", "ship": {"repo": str(self.repo)}}
        self.said: list[str] = []

    def tearDown(self):
        self.tmpdir.cleanup()

    def tasks(self, **rows):
        write_json_atomic(self.paths.state, {"tasks": {str(k): {"id": k, **v} for k, v in rows.items()}})

    def claim(self, task, **kw):
        return WW.claim(self.paths, self.cfg, task, say=self.said.append, **kw)


class TestClaim(Base):
    def test_first_claim_makes_it_then_reuse_keeps_build_outputs_and_resets_sources(self):
        self.tasks(**{"41": {"status": "running"}})
        wt = self.claim(41, branch="feat/snake")
        self.assertEqual(wt, WW.warm_path(self.paths))
        self.assertEqual(self.said[-1], str(wt), "the last line is the path")
        self.assertEqual(git(wt, "rev-parse", "--abbrev-ref", "HEAD"), "feat/snake")
        self.assertTrue((wt / "local.properties").is_file(), "gitignored files copied in")
        ident(wt)
        commit(wt, "app/Snake.kt", "class Snake\n", "snake")
        (wt / "build").mkdir()
        (wt / "build" / "classes.bin").write_text("compiled")
        (wt / "app" / "Main.kt").write_text("fun main() { left over }\n")
        (wt / "scratch.txt").write_text("left over")
        # dev moves on; task 41 is done; task 42 claims a new branch off the new dev
        commit(self.seed, "app/Dev.kt", "class Dev\n", "dev moved")
        git(self.seed, "push", "-q", "origin", "dev")
        self.tasks(**{"41": {"status": "done", "finished_at": time.time()},
                      "42": {"status": "running"}})
        wt2 = self.claim(42, branch="fix/timer")
        self.assertEqual(wt2, wt)
        self.assertEqual(git(wt, "rev-parse", "HEAD"), git(self.origin, "rev-parse", "dev"))
        self.assertEqual(git(wt, "rev-parse", "--abbrev-ref", "HEAD"), "fix/timer")
        self.assertTrue((wt / "app" / "Dev.kt").is_file())
        self.assertFalse((wt / "app" / "Snake.kt").exists(), "reset to the base")
        self.assertEqual((wt / "app" / "Main.kt").read_text(), "fun main() {}\n")
        self.assertFalse((wt / "scratch.txt").exists())
        self.assertEqual((wt / "build" / "classes.bin").read_text(), "compiled", "outputs kept")
        self.assertIn("warm worktree: left before task 42", git(wt, "stash", "list"))
        self.assertTrue(git(wt, "rev-parse", "feat/snake"), "the old branch and its commit stay")
        self.assertEqual(read_json(WW.lease_path(self.paths))["task"], 42)

    def test_resume_checks_out_the_existing_branch_without_reset(self):
        self.tasks(**{"41": {"status": "running"}})
        wt = self.claim(41, branch="feat/snake")
        ident(wt)
        commit(wt, "app/Snake.kt", "class Snake\n", "snake")
        self.tasks(**{"41": {"status": "needs_confirmation", "finished_at": time.time()},
                      "43": {"status": "running", "parent": 41}})
        self.claim(43, branch="feat/snake", resume=True)
        self.assertTrue((wt / "app" / "Snake.kt").is_file())

    def test_busy_while_another_task_runs_and_the_cli_says_use_a_fresh_one(self):
        self.tasks(**{"41": {"status": "running"}, "44": {"status": "running"}})
        self.claim(41, branch="feat/snake")
        with self.assertRaises(WW.Busy) as e:
            self.claim(44, branch="fix/x")
        self.assertEqual(e.exception.task, 41)
        out = io.StringIO()
        with redirect_stdout(out):
            code = WW.main(["claim", "--task", "44", "--branch", "fix/x"], self.paths)
        self.assertEqual(code, WW.EXIT_BUSY)
        self.assertIn("Busy: the warm worktree is in use by task 41", out.getvalue())
        self.assertIn("fresh worktree", out.getvalue())

    def test_a_question_holds_it_for_its_follow_up_for_a_while_only(self):
        now = time.time()
        self.tasks(**{"41": {"status": "running"}})
        self.claim(41, branch="feat/snake")
        self.tasks(**{"41": {"status": "needs_confirmation", "finished_at": now},
                      "45": {"status": "running"}, "46": {"status": "running", "parent": 41}})
        with self.assertRaises(WW.Busy):
            WW.take_lease(self.paths, 45, now=now)
        WW.take_lease(self.paths, 46, now=now)  # Kevin's answer to 41: it inherits the tree
        self.assertEqual(read_json(WW.lease_path(self.paths))["task"], 46)
        self.tasks(**{"41": {"status": "needs_confirmation", "finished_at": now - WW.HOLD_S - 1},
                      "45": {"status": "running"}})
        write_json_atomic(WW.lease_path(self.paths), {"task": 41})
        WW.take_lease(self.paths, 45, now=now)  # unanswered too long: free

    def test_a_finished_or_forgotten_holder_frees_it(self):
        self.tasks(**{"41": {"status": "running"}, "47": {"status": "running"}})
        self.claim(41)
        self.tasks(**{"41": {"status": "failed", "finished_at": time.time()},
                      "47": {"status": "running"}})
        self.assertIsNone(WW.holder(self.paths))
        self.tasks(**{"47": {"status": "running"}})  # 41 pruned from the worker state
        write_json_atomic(WW.lease_path(self.paths), {"task": 41})
        self.assertIsNone(WW.holder(self.paths))
        self.claim(47)

    def test_release_only_by_the_holder(self):
        self.tasks(**{"41": {"status": "running"}})
        self.claim(41)
        self.assertFalse(WW.release(self.paths, 40))
        self.assertTrue(WW.release(self.paths, 41))
        self.assertIsNone(WW.holder(self.paths))

    def test_a_git_failure_says_so_and_frees_the_lease(self):
        self.cfg["ship"]["repo"] = str(self.tmp / "no-repo")
        self.tasks(**{"41": {"status": "running"}})
        with self.assertRaises(WW.GitFailed):
            self.claim(41)
        self.assertIsNone(WW.holder(self.paths))

    def test_it_lives_in_the_work_folder_so_the_hook_lets_a_helper_edit_it(self):
        import asklist_hook as H
        wt = WW.warm_path(self.paths)
        self.assertTrue(H._scratch(wt / "app" / "Main.kt", self.paths))
        self.assertTrue(H._worker_internal(WW.lease_path(self.paths), self.paths),
                        "the lease is the worker's own file: a helper cannot forge one")


if __name__ == "__main__":
    unittest.main()
