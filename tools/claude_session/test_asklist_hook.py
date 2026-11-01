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
                    "Get-ItemProperty HKCU:\\Software\\X", "curl https://example.com"):
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
