"""Ticket 04: the ask-list hook (ruling 6) and the work laptop's scope (ruling 5), report.py and
send_mail.py. No CLI, no network, no mail.

    python -m unittest discover -s tools/claude_session -p "test_*.py" -v
"""
from __future__ import annotations

import io
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stderr
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import asklist_hook as H  # noqa: E402
import report  # noqa: E402
import send_mail  # noqa: E402
from common import Paths, read_json, write_json_atomic  # noqa: E402


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.paths = Paths(self.root / "home").ensure()
        self.work = self.paths.task_work(41)
        self.work.mkdir()
        self.kevin_file = self.root / "Documents" / "budget.xlsx"
        self.kevin_file.parent.mkdir()
        self.kevin_file.write_text("x")
        self.cfg = {"scope": "full"}

    def tearDown(self):
        self.tmp.cleanup()

    def decide(self, tool, inp, cwd=None, agent="agentA"):
        out, rule = H.decide({"tool_name": tool, "tool_input": inp, "cwd": str(cwd or self.work),
                              "agent_id": agent}, self.paths, self.cfg)
        return ("deny" if out else "pass"), rule, out

    def assertDenied(self, tool, inp, **kw):
        d, rule, out = self.decide(tool, inp, **kw)
        self.assertEqual(d, "deny", f"{tool} {inp} should be denied")
        return out

    def assertPassed(self, tool, inp, **kw):
        d, rule, _ = self.decide(tool, inp, **kw)
        self.assertEqual(d, "pass", f"{tool} {inp} should pass (rule {rule})")


class TestAskList(Base):
    def test_existing_kevin_file_overwrite_asks_new_and_work_files_pass(self):
        out = self.assertDenied("Write", {"file_path": str(self.kevin_file)})
        reason = out["hookSpecificOutput"]["permissionDecisionReason"]
        self.assertIn("needs_confirmation", reason)
        self.assertIn("--question", reason)
        self.assertDenied("Edit", {"file_path": str(self.kevin_file)})
        self.assertPassed("Write", {"file_path": str(self.root / "Documents" / "new.md")})
        own = self.work / "out.xlsx"
        own.write_text("made earlier by the helper")
        self.assertPassed("Write", {"file_path": str(own)})
        self.assertPassed("Edit", {"file_path": str(own)})

    def test_workflow_reports_are_kevins_files_new_passes_overwrite_asks(self):
        """Worker workflows ticket 05: a workflow's report lands in Kevin's own reports folder
        (`%USERPROFILE%/.legion/reports/<workflow>/`), outside the work folder. A new dated
        report passes; rewriting an older one is an overwrite of Kevin's file, so it asks, token or
        not, a scheduled run included (no token)."""
        folder = self.root / "profile" / ".legion" / "reports" / "rates-sheet"
        folder.mkdir(parents=True)
        self.assertPassed("Write", {"file_path": str(folder / "2026-11-10-task-41.md")})
        older = folder / "2026-11-03-task-30.md"
        older.write_text("last week's report")
        out = self.assertDenied("Write", {"file_path": str(older)})
        self.assertIn("overwrite", out["hookSpecificOutput"]["permissionDecisionReason"])
        self.assertDenied("Edit", {"file_path": str(older)})
        o = str(older).replace("\\", "/")
        self.assertDenied("Bash", {"command": f"rm {o}"})

    def test_deletes_outside_work_ask_inside_work_pass(self):
        k = str(self.kevin_file).replace("\\", "/")
        w = str(self.work / "tmp.txt").replace("\\", "/")
        self.assertDenied("PowerShell", {"command": f'Remove-Item "{k}"'})
        self.assertDenied("Bash", {"command": f"rm -f {k}"})
        self.assertDenied("Bash", {"command": f"python -c \"import os; os.remove('{k}')\""})
        self.assertDenied("Bash", {"command": "rm -rf ~/Downloads/old"})
        self.assertDenied("Bash", {"command": "rm ../budget.xlsx"})
        self.assertPassed("Bash", {"command": f"rm -f {w}"})
        self.assertPassed("PowerShell", {"command": "Remove-Item tmp.txt"})  # cwd = work folder
        self.assertDenied("PowerShell", {"command": "Remove-Item tmp.txt"}, cwd=self.root)

    def test_redirect_overwrite(self):
        k = str(self.kevin_file).replace("\\", "/")
        self.assertDenied("Bash", {"command": f"echo hi > {k}"})
        self.assertPassed("Bash", {"command": "python make.py > out.csv 2>&1"})

    def test_redirect_into_own_work_folder_passes_whatever_the_script_path(self):
        """2026-11-09, task 7: the redirect writes only compile.log; the interpreter and the
        build_lock.py it runs live outside the work folder and are read, not written."""
        w = self.work.as_posix()
        script = "C:/Users/kevin/AndroidStudioProjects/legion-claude-on/tools/claude_session/build_lock.py"
        cmd = (f'cd "{w}" && C:/Users/kevin/anaconda3/python.exe {script} --task 41 --cwd "{w}/wt" '
               "-- gradlew.bat compileDebugKotlin -Pnokey > compile.log 2>&1; echo \"exit $?\"; "
               "tail -40 compile.log")
        self.assertPassed("Bash", {"command": cmd}, cwd=self.paths.work)
        self.assertPassed("Bash", {"command": f"C:/x/python.exe {script} -- gradlew.bat a > compile.log"})
        self.assertPassed("Bash", {"command": f"C:/x/python.exe {script} >> \"{w}/build log.txt\" 2>&1"},
                          cwd=self.root)
        self.assertPassed("PowerShell", {"command": f"python {script} -- gradlew.bat a > {w}/wt/c.log"},
                          cwd=self.root)
        self.assertPassed("Bash", {"command": f"python {script} 2>&1 | tail -5"})  # no file at all

    def test_redirect_outside_work_folder_still_asks(self):
        k = self.kevin_file.as_posix()
        script = "C:/Users/kevin/AndroidStudioProjects/legion-claude-on/tools/claude_session/build_lock.py"
        for cmd, cwd in ((f"python {script} > {k}", self.work),
                         (f"python {script} >> {k} 2>&1", self.work),
                         (f'python {script} > "{k}"', self.work),
                         ("python make.py > budget.xlsx", self.kevin_file.parent),
                         (f"cd {self.kevin_file.parent.as_posix()} && python {script} > budget.xlsx", self.work),
                         ("python make.py > ../budget.xlsx", self.work),
                         ("python make.py > ~/Documents/x.log", self.work),
                         ("python make.py > $env:USERPROFILE/x.log", self.work),
                         (f"python make.py > {self.work.as_posix()}/ok.log; mv a.txt {k}", self.work)):
            with self.subTest(cmd=cmd):
                out = self.assertDenied("Bash", {"command": cmd}, cwd=cwd)
                self.assertIn("ASK LIST", out["hookSpecificOutput"]["permissionDecisionReason"])
        out = self.assertDenied("Bash", {"command": f"python x.py > {self.paths.config.as_posix()}"})
        self.assertIn("WORKER FILES", out["hookSpecificOutput"]["permissionDecisionReason"])
        out = self.assertDenied("Bash", {"command": f"python x.py > {(self.paths.skills / 'a.md').as_posix()}"},
                                agent="agentB")
        self.assertIn("ASK LIST", out["hookSpecificOutput"]["permissionDecisionReason"])

    def test_install_settings_send_always_ask(self):
        for cmd in ("pip install openpyxl", "python -m pip install requests", "winget install x",
                    "reg add HKCU\\Software\\X /v Y /d 1", "powercfg /change standby-timeout-ac 0",
                    "Set-ItemProperty -Path HKCU:\\X -Name Y -Value 1", "schtasks /create /tn x",
                    "git push origin main", "python -c \"import smtplib\"",
                    "curl -X POST https://example.com/book", "Stop-Process -Name chrome",
                    "Invoke-RestMethod -Uri https://x -Method Post -Body $b"):
            with self.subTest(cmd=cmd):
                self.assertDenied("PowerShell", {"command": cmd})

    def test_ordinary_work_passes(self):
        for cmd in ("Get-ChildItem C:/Users/kevin/Downloads -Filter *.pdf",
                    "python -c \"print(1)\"", "git log --oneline -5", "powercfg /q",
                    "Get-ItemProperty HKCU:\\Software\\X", "curl https://api.github.com/repos/o/r"):
            with self.subTest(cmd=cmd):
                self.assertPassed("PowerShell", {"command": cmd})
        self.assertPassed("Read", {"file_path": str(self.kevin_file)})

    def test_report_py_summary_text_never_trips_the_list(self):
        rp = str(HERE / "report.py").replace("\\", "/")
        self.assertPassed("Bash", {"command": f'python {rp} --task 41 --status done --summary '
                                              f'"Deleted nothing; I would rm the old copy > later"'})
        self.assertDenied("Bash", {"command": f'python {rp} --task 41 --status done --summary x; '
                                              f'rm -f {self.kevin_file.as_posix()}'})

    def test_send_mail_script_passes_hook_and_checks_itself(self):
        self.assertPassed("Bash", {"command": "python C:/x/send_mail.py --task 41 --token t --to kevin "
                                              "--subject s --body b"})

    def test_confirmed_task_passes_unconfirmed_and_main_session_ask(self):
        write_json_atomic(self.paths.agents, {"agentA": 42, "agentB": 7})
        write_json_atomic(self.paths.tokens / "42-yes.json", {
            "token": "t", "task": 42, "parent": 41, "kind": "yes", "expires_at": time.time() + 60})
        cmd = {"command": f"Remove-Item {self.kevin_file.as_posix()}"}
        self.assertPassed("PowerShell", cmd, agent="agentA")
        self.assertDenied("PowerShell", cmd, agent="agentB")
        self.assertDenied("PowerShell", cmd, agent=None)
        write_json_atomic(self.paths.tokens / "42-yes.json", {
            "token": "t", "task": 42, "kind": "yes", "expires_at": time.time() - 1})
        self.assertDenied("PowerShell", cmd, agent="agentA")

    def test_email_self_token_unlocks_nothing_in_the_hook(self):
        write_json_atomic(self.paths.agents, {"agentA": 42})
        write_json_atomic(self.paths.tokens / "42-email_self.json", {
            "token": "t", "task": 42, "kind": "email_self", "expires_at": time.time() + 60})
        self.assertDenied("PowerShell", {"command": f"Remove-Item {self.kevin_file.as_posix()}"})

    def test_hook_process_fails_closed_and_logs_no_text(self):
        env = {**os.environ, "LEGION_CLAUDE_HOME": str(self.paths.root)}
        write_json_atomic(self.paths.config, {"scope": "full"})
        p = subprocess.run([sys.executable, str(HERE / "asklist_hook.py")], input="not json",
                           capture_output=True, text=True, env=env)
        self.assertEqual(p.returncode, 0)
        self.assertEqual(json.loads(p.stdout)["hookSpecificOutput"]["permissionDecision"], "deny")
        ev = {"tool_name": "Bash", "tool_input": {"command": f"rm {self.kevin_file.as_posix()}"},
              "cwd": str(self.work), "agent_id": "a1"}
        p = subprocess.run([sys.executable, str(HERE / "asklist_hook.py")], input=json.dumps(ev),
                           capture_output=True, text=True, env=env)
        self.assertEqual(json.loads(p.stdout)["hookSpecificOutput"]["permissionDecision"], "deny")
        log = (self.paths.logs / "hook.log").read_text(encoding="utf-8")
        self.assertIn('"rule": "delete a file"', log)
        self.assertNotIn("budget", log)


class TestShipStep(Base):
    """alfred-builds ticket 03: push, deploy, publish and gcloud only through ship.py / undo.py."""

    def setUp(self):
        super().setUp()
        write_json_atomic(self.paths.agents, {"agentA": 42})
        write_json_atomic(self.paths.tokens / "42-yes.json", {
            "token": "t", "task": 42, "parent": 41, "kind": "yes", "expires_at": time.time() + 60})
        self.ship = (HERE / "ship.py").as_posix()
        self.undo = (HERE / "undo.py").as_posix()

    def test_push_deploy_publish_gcloud_are_denied_even_to_a_confirmed_task(self):
        for cmd in ("git push origin dev", "git -C C:/w/wt push origin HEAD:dev",
                    "git push --force origin main", 'git "push" origin dev',
                    'bash -c "git push origin dev"', '"C:/Program Files/Git/bin/git.exe" push',
                    "cd C:/w/wt && git --no-pager push origin dev",
                    "python deploy/cloudrun/deploy.py", "python C:/r/deploy/cloudrun/deploy_job.py",
                    "python tools/release_apk.py --app phone --apk a.apk --notes x",
                    "gcloud run services update-traffic legion --to-revisions a=100",
                    "gcloud run services describe legion", "gcloud.cmd builds submit",
                    f"python {self.ship} run --task 42 --token t; git push origin dev",
                    f"python {(HERE / 'build_lock.py').as_posix()} --task 42 -- git push origin dev"):
            with self.subTest(cmd=cmd):
                out = self.assertDenied("Bash", {"command": cmd}, agent="agentA")
                self.assertIn("SHIP STEP", out["hookSpecificOutput"]["permissionDecisionReason"])

    def test_our_ship_and_undo_run_alone_and_check_the_token_themselves(self):
        self.assertPassed("Bash", {"command": f'python {self.ship} ask --task 41 --kind engine '
                                              f'--branch fix/x --worktree C:/w/wt --notes "git push; deploy.py"'},
                          agent=None)
        self.assertPassed("PowerShell", {"command": f'& "C:/Program Files/Python/python.exe" '
                                                    f'{self.ship} run --task 42 --token t'})
        self.assertPassed("Bash", {"command": f"python {self.undo} run --task 42 --token t"})

    def test_a_ship_py_that_is_not_ours_gets_no_carve_out(self):
        fake = (self.work / "ship.py").as_posix()
        self.assertDenied("Bash", {"command": f'python {fake} ask --notes "git push origin dev"'},
                          agent="agentA")

    def test_a_commit_message_saying_push_is_not_a_push(self):
        self.assertPassed("Bash", {"command": 'git commit -m "push the timer fix to dev"'})
        self.assertPassed("Bash", {"command": "git log --oneline --grep push"})


class TestKevinsSkillsAndWorkerFiles(Base):
    """`work/.claude` is Kevin's, not scratch; the worker's own files are never a helper's."""

    def setUp(self):
        super().setUp()
        self.skill = self.paths.skills / "skills" / "legion-chores" / "SKILL.md"
        self.skill.parent.mkdir(parents=True)
        self.skill.write_text("x")
        write_json_atomic(self.paths.agents, {"agentA": 42})
        write_json_atomic(self.paths.tokens / "42-yes.json", {
            "token": "t", "task": 42, "parent": 41, "kind": "yes", "expires_at": time.time() + 60})

    def test_writing_a_skill_new_or_existing_asks(self):
        new = self.paths.skills / "skills" / "evil" / "SKILL.md"
        for tool, target in (("Write", self.skill), ("Edit", self.skill), ("Write", new)):
            with self.subTest(tool=tool, target=target.name):
                out = self.assertDenied(tool, {"file_path": str(target)}, agent="agentB")
                self.assertIn("needs_confirmation", out["hookSpecificOutput"]["permissionDecisionReason"])
        self.assertPassed("Write", {"file_path": str(new)}, agent="agentA")  # Kevin said yes

    def test_shell_deletes_and_overwrites_in_skills_ask(self):
        d = (self.paths.skills / "skills" / "legion-chores").as_posix()
        self.assertDenied("Bash", {"command": f"rm -rf {d}"}, agent="agentB")
        self.assertDenied("Bash", {"command": f"echo hi > {d}/SKILL.md"}, agent="agentB")
        self.assertDenied("PowerShell", {"command": "Remove-Item SKILL.md"}, cwd=self.skill.parent,
                          agent="agentB")
        src = (self.work / "SKILL.md").as_posix()
        for cmd in (f"cp {src} {d}/SKILL.md", f"Copy-Item {src} {d}/SKILL.md",
                    f"mkdir {self.paths.skills.as_posix()}/skills/new",
                    f"python -c \"open('{d}/SKILL.md','a')\""):
            with self.subTest(cmd=cmd):
                self.assertDenied("PowerShell", {"command": cmd}, agent="agentB")
        self.assertPassed("PowerShell", {"command": f"cp {src} {d}/SKILL.md"}, agent="agentA")
        self.assertPassed("Bash", {"command": f"cat {d}/SKILL.md"}, agent="agentB")

    def test_scratch_in_the_task_folder_still_runs(self):
        self.assertPassed("Write", {"file_path": str(self.work / "notes.md")})
        self.assertPassed("Bash", {"command": f"rm -f {(self.work / 'tmp.txt').as_posix()}"})

    def test_worker_files_are_never_a_helpers_to_write_even_confirmed(self):
        for target in (self.paths.tokens / "99-yes.json", self.paths.config, self.paths.agents,
                       self.paths.ships, self.paths.root / "worker-settings.json"):
            with self.subTest(target=target.name):
                out = self.assertDenied("Write", {"file_path": str(target)}, agent="agentA")
                self.assertIn("WORKER FILES", out["hookSpecificOutput"]["permissionDecisionReason"])
        tok = (self.paths.tokens / "99-yes.json").as_posix()
        out = self.assertDenied("Bash", {"command": f"echo x > {tok}"}, agent="agentA")
        self.assertIn("WORKER FILES", out["hookSpecificOutput"]["permissionDecisionReason"])
        self.assertDenied("Bash", {"command": f"rm {self.paths.ships.as_posix()}"}, agent="agentA")


class TestListedScope(Base):
    def setUp(self):
        super().setUp()
        self.repos = self.root / "repos"
        (self.repos / "app").mkdir(parents=True)
        self.cfg = {"scope": "listed", "repos_root": str(self.repos)}

    def test_reads_only_inside_repos(self):
        self.assertPassed("Read", {"file_path": str(self.repos / "app" / "a.py")})
        self.assertDenied("Read", {"file_path": str(self.kevin_file)})
        self.assertPassed("Grep", {"pattern": "x", "path": str(self.repos)})
        self.assertDenied("Glob", {"pattern": "*", "path": str(self.root)})

    def test_writes_only_in_work_folder(self):
        self.assertDenied("Write", {"file_path": str(self.repos / "app" / "new.py")})
        self.assertPassed("Write", {"file_path": str(self.work / "notes.md")})

    def test_shell_read_only_git_and_report(self):
        r = self.repos.as_posix()
        self.assertPassed("Bash", {"command": f"git -C {r}/app log --oneline -5"})
        self.assertPassed("Bash", {"command": f"cd {r}/app && git status"})
        self.assertDenied("Bash", {"command": f"cd {r}/app && git commit -m x"})
        self.assertDenied("Bash", {"command": f"cat {self.kevin_file.as_posix()}"})
        self.assertDenied("Bash", {"command": "python -c \"print(open('C:/secret').read())\""})
        self.assertPassed("Bash", {"command": f'python {HERE.as_posix()}/report.py --task 1 '
                                              f'--status done --summary "two commits; fine"'})

    def test_send_mail_runs_send_mail_checks_kevin_only(self):
        self.assertPassed("Bash", {"command": f'python {HERE.as_posix()}/send_mail.py --task 1 '
                                              f'--token t --to kevin --subject s --body "a; b"'})
        self.assertDenied("Bash", {"command": f'python {HERE.as_posix()}/send_mail.py --task 1 '
                                              f'--token t --to kevin --subject s --body b; cat C:/x'})

    def test_no_web(self):
        self.assertDenied("WebFetch", {"url": "https://example.com"})


class TestReport(Base):
    def run_report(self, *args):
        old = os.environ.get("LEGION_CLAUDE_HOME")
        os.environ["LEGION_CLAUDE_HOME"] = str(self.paths.root)
        try:
            with redirect_stderr(io.StringIO()) as err:
                try:
                    code = report.main(list(args))
                except SystemExit as e:
                    code = e.code
            return code, err.getvalue()
        finally:
            if old is None:
                os.environ.pop("LEGION_CLAUDE_HOME")
            else:
                os.environ["LEGION_CLAUDE_HOME"] = old

    def test_done_with_found_files(self):
        code, _ = self.run_report("--task", "41", "--status", "done", "--summary", "Found it.",
                                  "--found", str(self.kevin_file))
        self.assertEqual(code, 0)
        files = list(self.paths.reports.glob("*.json"))
        r = read_json(files[0])
        self.assertEqual((r["task"], r["status"], r["summary"]), (41, "done", "Found it."))
        self.assertEqual(r["found"][0]["name"], "budget.xlsx")
        self.assertEqual(r["found"][0]["size"], 1)

    def test_question_must_be_in_question_field(self):
        code, err = self.run_report("--task", "41", "--status", "needs_confirmation",
                                    "--summary", "OK to delete?")
        self.assertEqual(code, 2)
        self.assertIn("--question", err)
        self.assertFalse(list(self.paths.reports.glob("*.json")))

    def test_missing_found_file_and_missing_summary_refused(self):
        self.assertEqual(self.run_report("--task", "1", "--status", "done", "--summary", "x",
                                         "--found", str(self.root / "nope.pdf"))[0], 2)
        self.assertEqual(self.run_report("--task", "1", "--status", "done")[0], 2)
        self.assertEqual(self.run_report("--task", "1", "--status", "finished", "--summary", "x")[0], 2)


class FakeSMTP:
    sent: list = []
    fail = False

    def __init__(self, host, port):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def login(self, u, p):
        if FakeSMTP.fail:
            import smtplib
            raise smtplib.SMTPAuthenticationError(535, b"no")

    def send_message(self, msg, to_addrs):
        FakeSMTP.sent.append((msg, list(to_addrs)))


class TestSendMail(Base):
    def setUp(self):
        super().setUp()
        self.mail = {"address": "alfred@example.com", "app_password": "pw", "kevin": "kevin@example.com"}
        write_json_atomic(self.paths.config, {"scope": "full", "mail": self.mail})
        FakeSMTP.sent, FakeSMTP.fail = [], False
        self.orig = send_mail.SMTP_FACTORY
        send_mail.SMTP_FACTORY = FakeSMTP

    def tearDown(self):
        send_mail.SMTP_FACTORY = self.orig
        super().tearDown()

    def token(self, kind="yes", task=42, expires=60):
        write_json_atomic(self.paths.tokens / f"{task}-{kind}.json", {
            "token": "tok", "task": task, "parent": 41, "kind": kind,
            "expires_at": time.time() + expires})

    def send(self, *extra, task="42", token="tok"):
        out = io.StringIO()
        from contextlib import redirect_stdout
        with redirect_stdout(out):
            code = send_mail.main(["--task", task, "--token", token, "--subject", "Rates",
                                   "--body", "Sheet attached.", *extra], self.paths)
        return code, out.getvalue()

    def test_refuses_without_token(self):
        code, out = self.send("--to", "dad@example.com")
        self.assertEqual(code, 1)
        self.assertIn("no confirmation from Kevin", out)
        self.assertEqual(FakeSMTP.sent, [])

    def test_wrong_or_expired_token(self):
        self.token()
        self.assertEqual(self.send("--to", "kevin", token="guess")[0], 1)
        self.token(expires=-1)
        self.assertIn("expired", self.send("--to", "kevin")[1])
        self.assertEqual(FakeSMTP.sent, [])

    def test_yes_sends_with_kevin_cc_and_attachment_once(self):
        self.token()
        att = self.work / "rates.xlsx"
        att.write_bytes(b"PK")
        code, out = self.send("--to", "dad@example.com", "--attach", str(att))
        self.assertEqual(code, 0, out)
        self.assertIn("Kevin copied", out)
        msg, rcpts = FakeSMTP.sent[0]
        self.assertEqual(rcpts, ["dad@example.com", "kevin@example.com"])
        self.assertEqual((msg["From"], msg["Cc"]), ("alfred@example.com", "kevin@example.com"))
        self.assertEqual([p.get_filename() for p in msg.iter_attachments()], ["rates.xlsx"])
        self.assertNotIn("pw", out)
        self.assertEqual(self.send("--to", "dad@example.com")[0], 1)

    def test_parent_task_id_also_accepted(self):
        self.token()
        self.assertEqual(self.send("--to", "kevin", task="41")[0], 0)

    def test_email_self_only_to_kevin(self):
        self.token(kind="email_self")
        code, out = self.send("--to", "dad@example.com")
        self.assertEqual(code, 1)
        self.assertIn("anyone else needs his yes", out)
        code, out = self.send("--to", "kevin")
        self.assertEqual(code, 0)
        self.assertEqual(FakeSMTP.sent[0][1], ["kevin@example.com"])
        self.assertIsNone(FakeSMTP.sent[0][0]["Cc"])

    def test_work_laptop_text_to_kevin_only_and_missing_account_never_sends(self):
        self.token()
        write_json_atomic(self.paths.config, {"scope": "listed", "mail": self.mail})
        att = self.work / "a.txt"
        att.write_text("x")
        self.assertIn("only mails Kevin", self.send("--to", "dad@example.com")[1])
        self.assertIn("no attachments", self.send("--to", "kevin", "--attach", str(att))[1])
        self.assertEqual(FakeSMTP.sent, [])
        self.assertEqual(self.send("--to", "kevin")[0], 0)
        self.assertEqual(FakeSMTP.sent[0][1], ["kevin@example.com"])
        FakeSMTP.sent = []
        self.token()
        write_json_atomic(self.paths.config, {"scope": "full"})
        self.assertIn("not set up", self.send("--to", "kevin")[1])
        self.assertEqual(FakeSMTP.sent, [])

    def test_smtp_failure_said_in_words_and_token_kept(self):
        self.token()
        FakeSMTP.fail = True
        code, out = self.send("--to", "kevin")
        self.assertEqual(code, 1)
        self.assertIn("Not sent", out)
        self.assertFalse(read_json(self.paths.tokens / "42-yes.json").get("mail_used"))


if __name__ == "__main__":
    unittest.main()
