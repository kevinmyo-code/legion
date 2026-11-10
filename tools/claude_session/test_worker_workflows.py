"""Worker workflows ticket 05: the worker publishes its catalog, builds a workflow task's message,
makes the report folder, keeps a scheduled run tokenless, and writes and prunes the task log.

A fake engine (real HTTP) and a fake `claude`; invented workflows only; Kevin's real
`%USERPROFILE%\\.legion` is never touched (WorkerCase points it at a temp folder). Run from the repo
root:

    python -m unittest discover -s tools/claude_session -p "test_*.py" -v
"""
from __future__ import annotations

import datetime
import json
import shutil
import sys
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import worker as W  # noqa: E402
import workflows as WF  # noqa: E402
from common import write_json_atomic  # noqa: E402
from test_worker import WorkerCase, fake  # noqa: E402

FIXTURES = HERE / "test_fixtures" / "workflows" / "skills"

DIGEST = {"format": 1, "name": "weekly-digest", "says": "Writes a weekly digest for Kevin.",
          "aliases": ["weekly digest"], "params": [], "asks": [], "outputs": ["summary"],
          "deliver": "email_self", "scope": ["full"], "every": {"hours": 168}}


class WorkflowCase(WorkerCase):
    def setUp(self):
        super().setUp()
        self.skills = self.paths.work / ".claude" / "skills"
        self.skills.mkdir(parents=True, exist_ok=True)
        for name in ("rates-sheet", "tidy-downloads"):
            shutil.copytree(FIXTURES / name, self.skills / name)
        self.reports = Path(self.cfg["workflow_reports"])
        self.task_log = Path(self.cfg["task_log"])

    def add_workflow(self, sidecar: dict, skill: str = "Invented example.\n"):
        d = self.skills / sidecar["name"]
        d.mkdir(parents=True, exist_ok=True)
        (d / "workflow.json").write_text(json.dumps(sidecar), encoding="utf-8")
        (d / "SKILL.md").write_text(skill, encoding="utf-8")

    def msg(self, tid: int) -> str:
        return [m for m in self.msgs() if m.startswith(f"[task {tid}]")][0]

    def sent_catalogs(self):
        return [h for h in self.engine.heartbeats if "workflows" in h]


class TestCatalog(WorkflowCase):
    cfg_extra = {"catalog_s": 0.3}

    def test_the_catalog_goes_up_once_then_only_its_hash_until_it_changes(self):
        self.start()
        self.wait(lambda: len(self.engine.heartbeats) >= 4, what="heartbeats")
        self.assertEqual(len(self.engine.catalogs), 1)
        sent = self.engine.catalogs[0]
        self.assertEqual([e["name"] for e in sent], ["rates-sheet", "tidy-downloads"])
        entries, _ = WF.load_catalog(self.skills)
        self.assertEqual(sent, WF.catalog_for_engine(entries))
        self.assertTrue(all(h.get("workflows_hash") == WF.catalog_hash(entries)
                            for h in self.engine.heartbeats))
        self.assertEqual(len(self.sent_catalogs()), 1, "the list rides one heartbeat only")
        blob = json.dumps(self.engine.heartbeats)
        self.assertNotIn(str(self.skills), blob)  # no path
        self.assertNotIn("the usual three", blob)  # a default stays local
        self.assertNotIn("Look up today", blob)  # SKILL.md stays local

        self.add_workflow(DIGEST)
        self.wait(lambda: len(self.engine.catalogs) == 2, what="the changed catalog")
        self.assertIn("weekly-digest", [e["name"] for e in self.engine.catalogs[1]])

    def test_a_sidecar_that_fails_lint_is_left_out_and_logged_by_name_only(self):
        self.add_workflow({**DIGEST, "name": "broken-one", "says": "Mails someone@example.com"})
        self.start()
        self.wait(lambda: self.engine.catalogs, what="catalog")
        self.assertNotIn("broken-one", [e["name"] for e in self.engine.catalogs[0]])
        self.stop_last()
        logs = "".join(p.read_text(encoding="utf-8") for p in self.paths.logs.glob("*.log"))
        self.assertIn("broken-one fails the lint", logs)
        self.assertNotIn("someone@example.com", logs)


class TestOldEngine(WorkflowCase):
    cfg_extra = {"catalog_s": 60.0}

    def test_an_engine_that_does_not_echo_gets_the_list_rarely(self):
        self.engine.echo_hash = False
        self.start()
        self.wait(lambda: len(self.engine.heartbeats) >= 4, what="heartbeats")
        self.assertEqual(len(self.sent_catalogs()), 1)


class TestWorkflowTasks(WorkflowCase):
    def test_a_workflow_task_message_carries_skill_words_params_report_and_asks(self):
        self.engine.queue(id=60, kind="workflow", workflow="rates-sheet", origin="named",
                          params="for euro and yen",
                          request=f"make the rates sheet for euro and yen {fake(sleep=0.1)}")
        self.start()
        self.wait(lambda: self.engine.finished(60), what="task 60")
        msg = self.msg(60)
        self.assertTrue(msg.startswith("[task 60] Workflow rates-sheet. Use the rates-sheet skill."))
        self.assertIn('Kevin\'s words: "make the rates sheet for euro and yen', msg)
        self.assertIn('Params text: "for euro and yen"', msg)
        self.assertIn('Param currencies: which currencies, like \'euro and yen\' '
                      '(default "the usual three").', msg)
        day = datetime.date.today().isoformat()
        report = self.reports / "rates-sheet" / f"{day}-task-60.md"
        self.assertIn(f"Report file: {report}", msg)
        self.assertTrue(report.parent.is_dir(), "the worker makes the folder, not the file")
        self.assertFalse(report.exists())
        # Containment: the hook lets a tainted task create exactly this file, read from state.
        from common import read_json
        self.assertEqual(read_json(self.paths.state)["tasks"]["60"]["report_file"], str(report))
        self.assertNotIn("stops for Kevin's yes", msg)
        self.assertNotIn("Scheduled run", msg)
        self.assertNotIn("AGENT-RULES", msg, "no rules file here, so no line for it")
        self.assertFalse(list(self.paths.tokens.glob("60-*.json")), "a named run has no token")

    def test_declared_asks_and_the_agent_rules_line(self):
        rules = Path(self.cfg["agent_rules"])
        rules.parent.mkdir(parents=True, exist_ok=True)
        rules.write_text("rules", encoding="utf-8")
        self.engine.queue(id=61, kind="workflow", workflow="tidy-downloads", origin="named",
                          request=f"tidy my downloads {fake(sleep=0.1)}")
        self.start()
        self.wait(lambda: self.engine.finished(61), what="task 61")
        msg = self.msg(61)
        self.assertIn("This workflow stops for Kevin's yes at: Move the files into dated folders?",
                      msg)
        self.assertIn(f"Read {rules} first.", msg)

    def test_a_scheduled_run_gets_no_token_and_says_so(self):
        self.engine.queue(id=62, kind="workflow", workflow="tidy-downloads", origin="schedule",
                          request="Scheduled run of tidy downloads.")
        self.start()
        self.wait(lambda: self.engine.finished(62), what="task 62")
        msg = self.msg(62)
        self.assertIn("Scheduled run; nobody is waiting on this.", msg)
        self.assertIn("no confirmation token of any kind", msg)
        self.assertIn("Do not ask more than one question.", msg)
        self.assertNotIn("Kevin's words", msg)
        self.assertNotIn("token:", msg.lower())
        self.assertFalse(list(self.paths.tokens.glob("62-*.json")))

    def test_a_scheduled_email_yes_comes_only_from_the_local_sidecar(self):
        self.add_workflow(DIGEST, "Then send_mail.py --to kevin.\n")
        # The engine row claims email_self for a workflow whose sidecar does not: no token.
        self.engine.queue(id=63, kind="workflow", workflow="tidy-downloads", origin="schedule",
                          deliver="email_self", request="Scheduled run of tidy downloads.")
        self.engine.queue(id=64, kind="workflow", workflow="weekly-digest", origin="schedule",
                          deliver="email_self", request="Scheduled run of weekly digest.")
        self.start()
        self.wait(lambda: self.engine.finished(63) and self.engine.finished(64), what="63, 64")
        self.assertIn("has no yes to send mail", self.msg(63))
        self.assertFalse(list(self.paths.tokens.glob("63-*.json")))
        self.assertIn("--to kevin", self.msg(64))
        self.assertTrue((self.paths.tokens / "64-email_self.json").exists())
        self.assertFalse((self.paths.tokens / "64-yes.json").exists())

    def test_a_scheduled_run_never_takes_a_page_or_files_yes(self):
        self.engine.queue(id=68, kind="workflow", workflow="rates-sheet", origin="schedule",
                          deliver="files", request="Scheduled run of rates sheet.")
        self.engine.queue(id=69, kind="workflow", workflow="rates-sheet", origin="schedule",
                          deliver="phone", request="Scheduled run of rates sheet.")
        self.start()
        self.wait(lambda: self.engine.finished(68) and self.engine.finished(69), what="68, 69")
        for tid in (68, 69):
            msg = self.msg(tid)
            self.assertNotIn("his yes", msg)
            self.assertNotIn("LEGION sends", msg)
        w = self.workers[-1][2]
        self.assertEqual(w._task(68).get("deliver_refused"), "files")
        f = self.paths.task_work(68) / "x.txt"
        f.write_text("x", encoding="utf-8")
        w._task(68)["found"] = [{"path": str(f), "name": "x.txt", "size": 1}]
        w.upload_requested({"task": 68, "file_id": 7, "path": str(f)})
        self.assertEqual(self.engine.uploads, [])

    def test_a_workflow_this_laptop_does_not_have_fails_in_words_and_never_runs(self):
        self.engine.queue(id=65, kind="workflow", workflow="log-autopsy", origin="named",
                          request="run a log autopsy")
        self.start()
        self.wait(lambda: self.engine.finished(65), what="task 65")
        done = self.engine.finished(65)[0]
        self.assertEqual(done["status"], "failed")
        self.assertEqual(done["summary"], W.SAY_NO_WORKFLOW.format(name="log autopsy"))
        self.assertFalse([m for m in self.msgs() if m.startswith("[task 65]")])

    def test_a_follow_up_names_the_workflow_skill_again(self):
        self.engine.queue(id=66, kind="workflow", workflow="tidy-downloads", origin="named",
                          request=f"tidy my downloads {fake(status='needs_confirmation', question='Move 40 files?')}")
        self.start()
        self.wait(lambda: self.engine.finished(66), what="task 66")
        self.engine.queue(id=67, parent=66, confirms=True, kind="workflow",
                          workflow="tidy-downloads", origin="named",
                          request=f"yes {fake(sleep=0.1)}")
        self.wait(lambda: self.engine.finished(67), what="task 67")
        msg = self.msg(67)
        self.assertIn("Follow-up to task 66", msg)
        self.assertIn("Task 66 was workflow tidy-downloads: use the tidy-downloads skill again.",
                      msg)
        self.assertIn("Confirmation token:", msg)
        self.assertNotIn("Scheduled run", msg)


class TestListedTaskLog(WorkflowCase):
    cfg_extra = {"scope": "listed", "repos_root": "C:/repos"}

    def test_the_work_laptop_keeps_no_task_log(self):
        self.engine.queue(id=72, request=f"read the readme {fake(sleep=0.1)}")
        self.start()
        self.wait(lambda: self.engine.finished(72), what="task 72")
        self.assertFalse(self.task_log.exists())


class TestTaskLog(WorkflowCase):
    def test_every_finish_writes_one_local_line_with_the_request(self):
        self.engine.queue(id=70, request=f"zebra-request-7781 {fake(sleep=0.1)}")
        self.engine.queue(id=71, kind="workflow", workflow="rates-sheet", origin="schedule",
                          request="Scheduled run of rates sheet.")
        self.start()
        self.wait(lambda: self.engine.finished(70) and self.engine.finished(71), what="70, 71")
        self.wait(lambda: self.task_log.is_file()
                  and len(self.task_log.read_text(encoding="utf-8").splitlines()) == 2,
                  what="two log lines")
        lines = [json.loads(x) for x in self.task_log.read_text(encoding="utf-8").splitlines()]
        by_task = {x["task"]: x for x in lines}
        self.assertTrue(by_task[70]["request"].startswith("zebra-request-7781"))
        self.assertEqual((by_task[70]["kind"], by_task[70]["status"]), ("ask", "done"))
        self.assertEqual((by_task[71]["kind"], by_task[71]["workflow"], by_task[71]["origin"]),
                         ("workflow", "rates-sheet", "schedule"))
        self.stop_last()
        logs = "".join(p.read_text(encoding="utf-8") for p in self.paths.logs.glob("*.log"))
        self.assertNotIn("zebra-request-7781", logs, "the worker log never carries a request")

    def test_prune_drops_old_log_lines_and_old_reports_only(self):
        old = (datetime.datetime.now() - datetime.timedelta(days=200)).isoformat(timespec="seconds")
        new = datetime.datetime.now().isoformat(timespec="seconds")
        self.task_log.parent.mkdir(parents=True, exist_ok=True)
        self.task_log.write_text(
            json.dumps({"date": old, "task": 1, "request": "old"}) + "\n"
            + json.dumps({"date": new, "task": 2, "request": "new"}) + "\n", encoding="utf-8")
        folder = self.reports / "rates-sheet"
        folder.mkdir(parents=True)
        (folder / "2020-01-01-task-1.md").write_text("old", encoding="utf-8")
        today = folder / f"{datetime.date.today().isoformat()}-task-2.md"
        today.write_text("new", encoding="utf-8")
        (folder / "notes.md").write_text("Kevin's own", encoding="utf-8")
        stranger = self.reports / "not-a-workflow"
        stranger.mkdir()
        (stranger / "2020-01-01-task-3.md").write_text("not the worker's", encoding="utf-8")
        write_json_atomic(self.paths.config, self.cfg)
        w = W.Worker(self.paths, self.cfg, power=None, base_env=self.env)
        w.refresh_catalog(time.time(), force=True)
        w.prune(time.time())
        kept = [json.loads(x)["task"] for x in self.task_log.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(kept, [2])
        self.assertFalse((folder / "2020-01-01-task-1.md").exists())
        self.assertTrue(today.exists())
        self.assertTrue((folder / "notes.md").exists())
        self.assertTrue((stranger / "2020-01-01-task-3.md").exists())


if __name__ == "__main__":
    unittest.main()
