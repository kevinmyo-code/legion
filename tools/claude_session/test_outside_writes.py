"""Files outside the task's own folder (review 11-19, round 4, R4-1): a helper's file tools may not
create a file anywhere it likes (a `.pth` in the worker python's site-packages runs inside the
hook), nor write into the places that change how the laptop or the hook behaves; and the hook
itself runs only as `python -I -S`. Made-up paths under a temp folder stand in for Kevin's.

    python -m unittest discover -s tools/claude_session -p "test_*.py" -v
"""
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

import asklist_hook as H  # noqa: E402
import outside_writes as O  # noqa: E402
from common import Paths, write_json_atomic  # noqa: E402

PYTHON_HOME = Path(sys.executable).resolve().parent


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.home = self.root / "home-kevin"
        self.appdata = self.home / "AppData" / "Roaming"
        self.local = self.home / "AppData" / "Local"
        for d in (self.home, self.appdata, self.local):
            d.mkdir(parents=True, exist_ok=True)
        self.env = {"USERPROFILE": str(self.home), "HOME": str(self.home), "APPDATA": str(self.appdata),
                    "LOCALAPPDATA": str(self.local), "OneDrive": str(self.home / "OneDrive")}
        self.old_env = {k: os.environ.get(k) for k in self.env}
        os.environ.update(self.env)
        self.paths = Paths(self.root / "worker").ensure()
        self.work = self.paths.task_work(41)
        self.work.mkdir()
        self.other = self.paths.task_work(42)
        self.other.mkdir()
        (self.other / "theirs.txt").write_text("x", encoding="utf-8")
        self.docs = self.home / "Documents"
        self.docs.mkdir()
        (self.docs / "budget.xlsx").write_text("x", encoding="utf-8")
        self.report = self.home / ".legion" / "reports" / "rates" / "2026-11-19-task-41.md"
        self.report.parent.mkdir(parents=True)
        write_json_atomic(self.paths.agents, {"agentA": 41})
        self.tasks = {"41": {"id": 41, "kind": "ask", "status": "running", "report_file": str(self.report)}}
        write_json_atomic(self.paths.state, {"tasks": self.tasks})
        self.cfg = {"scope": "full"}

    def tearDown(self):
        for k, v in self.old_env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        self.tmp.cleanup()

    def decide(self, tool, inp, agent="agentA"):
        out, rule = H.decide({"tool_name": tool, "tool_input": inp, "cwd": str(self.paths.work),
                              "agent_id": agent}, self.paths, self.cfg)
        reason = out["hookSpecificOutput"]["permissionDecisionReason"] if out else ""
        return ("deny" if out else "pass"), rule, reason

    def yes(self):
        write_json_atomic(self.paths.tokens / "41-yes.json", {"token": "t", "task": 41, "kind": "yes",
                                                              "expires_at": time.time() + 600})


class FileTools(Base):
    def rows(self):
        h, a, loc = self.home, self.appdata, self.local
        startup = a / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup"
        # (path, expected): pass, ask (a yes lets it through) or hard (never)
        return [
            # the task's own folder: anything
            (self.work / "out.csv", "pass"),
            (self.work / "deep" / "new" / "x.py", "pass"),
            (self.work / "clone" / ".git" / "config", "pass"),
            # an existing file in another task's folder: today's rule (scratch)
            (self.other / "theirs.txt", "pass"),
            # a NEW file anywhere else outside its own folder: ask
            (self.other / "new.txt", "ask"),
            (self.docs / "notes.md", "ask"),
            (h / "Desktop" / "a.txt", "ask"),
            (self.root / "anywhere" / "x.bat", "ask"),
            (Path("C:/") / "zz-new-file.txt", "ask"),
            # an existing file of Kevin's: today's rule (overwrite asks)
            (self.docs / "budget.xlsx", "ask"),
            # the workflow's own report file (the worker names it): pass; a sibling: ask
            (self.report, "pass"),
            (self.report.parent / "2026-11-19-other.md", "ask"),
            # protected, even when the file exists, a yes still needed
            (startup / "evil.lnk", "ask"),
            (h / "Documents" / "WindowsPowerShell" / "Microsoft.PowerShell_profile.ps1", "ask"),
            (h / "Documents" / "PowerShell" / "profile.ps1", "ask"),
            (h / "OneDrive" / "Documents" / "PowerShell" / "profile.ps1", "ask"),
            (h / ".ssh" / "config", "ask"),
            (h / ".ssh" / "authorized_keys", "ask"),
            (h / ".gitconfig", "ask"),
            (h / ".config" / "git" / "config", "ask"),
            (h / ".config" / "gh" / "config.yml", "ask"),
            (a / "GitHub CLI" / "config.yml", "ask"),
            (loc / "GitHub CLI" / "extensions" / "gh-x" / "gh-x.exe", "ask"),
            (h / ".bashrc", "ask"),
            (h / ".bash_profile", "ask"),
            (h / ".claude" / "settings.json", "ask"),
            (Path("C:/Windows/System32/Tasks/evil"), "ask"),
            # never, yes or not: what the hook itself runs on
            (PYTHON_HOME / "Lib" / "site-packages" / "zz.pth", "hard"),
            (PYTHON_HOME / "Lib" / "site-packages" / "sitecustomize.py", "hard"),
            (PYTHON_HOME / "Lib" / "usercustomize.py", "hard"),
            (a / "Python" / "Python313" / "site-packages" / "x.pth", "hard"),
            (HERE / "common.py", "hard"),
            (HERE / "evil.py", "hard"),
            (loc / "Microsoft" / "WinGet" / "Packages" / "GitHub.cli_x" / "bin" / "gh.exe", "hard"),
            (self.paths.root / "projects.json", "hard"),
            (self.paths.root / "state.json", "hard"),
            (self.paths.tokens / "41-yes.json", "hard"),
        ]

    def test_table(self):
        rows = self.rows()
        self.assertGreaterEqual(len(rows), 30)
        for target, want in rows:
            for tool in ("Write", "Edit", "NotebookEdit"):
                with self.subTest(target=str(target), tool=tool):
                    key = "notebook_path" if tool == "NotebookEdit" else "file_path"
                    got, rule, reason = self.decide(tool, {key: str(target)})
                    self.assertEqual(got, "pass" if want == "pass" else "deny", rule)

    def test_a_yes_lets_ask_rows_through_never_hard_rows(self):
        self.yes()
        for target, want in self.rows():
            with self.subTest(target=str(target)):
                got = self.decide("Write", {"file_path": str(target)})[0]
                self.assertEqual(got, "deny" if want == "hard" else "pass")

    def test_spellings_of_the_same_place(self):
        site = PYTHON_HOME / "Lib" / "site-packages"
        for spelled in (str(site / "zz.pth").upper(), (site / "x" / ".." / "zz.pth").as_posix(),
                        str(site / "zz.pth").replace("\\", "/")):
            with self.subTest(spelled=spelled):
                self.assertEqual(self.decide("Write", {"file_path": spelled})[0], "deny")

    @unittest.skipUnless(os.name == "nt", "junctions are a Windows thing")
    def test_a_junction_in_the_task_folder_does_not_reach_out(self):
        out = self.root / "outside"
        out.mkdir()
        j = self.work / "j"
        subprocess.run(["cmd", "/c", "mklink", "/J", str(j), str(out)], check=True, capture_output=True)
        self.assertEqual(self.decide("Write", {"file_path": str(j / "new.txt")})[0], "deny")

    def test_the_warm_worktree_only_for_the_task_holding_it(self):
        wt = self.paths.work / "warm" / "wt"
        wt.mkdir(parents=True)
        self.assertEqual(self.decide("Write", {"file_path": str(wt / "New.kt")})[0], "deny")
        write_json_atomic(self.paths.root / "warm-lease.json", {"task": 41, "at": time.time()})
        self.assertEqual(self.decide("Write", {"file_path": str(wt / "New.kt")})[0], "pass")
        write_json_atomic(self.paths.root / "warm-lease.json", {"task": 42, "at": time.time()})
        self.assertEqual(self.decide("Write", {"file_path": str(wt / "New.kt")})[0], "deny")

    def test_the_reason_says_what_to_do(self):
        _, _, reason = self.decide("Write", {"file_path": str(self.docs / "notes.md")})
        self.assertIn("needs_confirmation", reason)
        _, _, reason = self.decide("Write", {"file_path": str(PYTHON_HOME / "Lib" / "x.pth")})
        self.assertIn("cannot be confirmed", reason)


class OtherSpellings(Base):
    """R5-1 (review round 5): long-path, device and UNC spellings of a refused place are refused too."""

    def refused_places(self):
        return [PYTHON_HOME / "Lib" / "site-packages" / "zz.pth", PYTHON_HOME / "Lib" / "json" / "__init__.py",
                HERE / "common.py", self.paths.root / "projects.json",
                self.local / "Microsoft" / "WinGet" / "Packages" / "GitHub.cli_x" / "bin" / "gh.exe"]

    def spellings(self, p):
        s = str(p.resolve() if p.exists() else p)
        drive, rest = s[0], s[3:]
        host = os.environ.get("COMPUTERNAME", "localhost")
        b = chr(92)  # one backslash
        fwd = rest.replace(b, "/")
        return [
            b + b + "?" + b + s, "//?/" + s.replace(b, "/"), b + b + "." + b + s, "//./" + s.replace(b, "/"),
            b + b + "localhost" + b + drive + "$" + b + rest, "//localhost/" + drive + "$/" + fwd,
            b + b + "127.0.0.1" + b + drive + "$" + b + rest, b + b + host + b + drive + "$" + b + rest,
            b + b + "?" + b + "UNC" + b + "localhost" + b + drive + "$" + b + rest,
        ]

    def test_each_spelling_of_each_refused_place_is_refused_yes_or_not(self):
        self.yes()
        for place in self.refused_places():
            for spelled in self.spellings(place):
                for tool in ("Write", "Edit"):
                    with self.subTest(spelled=spelled, tool=tool):
                        got, rule, _ = self.decide(tool, {"file_path": spelled})
                        self.assertEqual(got, "deny", rule)

    def test_any_other_unc_target_is_refused(self):
        self.yes()
        b = chr(92)
        for spelled in (b + b + "fileserver" + b + "share" + b + "x.txt", "//fileserver/share/x.txt",
                        b + b + "?" + b + "UNC" + b + "fileserver" + b + "s" + b + "x"):
            with self.subTest(spelled=spelled):
                self.assertEqual(self.decide("Write", {"file_path": spelled})[0], "deny")

    def test_own_folder_through_a_long_path_prefix_still_passes(self):
        b = chr(92)
        self.assertEqual(self.decide("Write", {"file_path": b + b + "?" + b + str(self.work / "x.txt")})[0], "pass")


class FollowUps(Base):
    """A follow-up task (its record names a parent) may make new files in its parent's folder."""

    def test_parent_folder_for_a_child_only(self):
        parent = self.paths.task_work(40)
        parent.mkdir()
        target = {"file_path": str(parent / "clone" / "new.py")}
        self.assertEqual(self.decide("Write", target)[0], "deny")  # task 41 has no parent yet
        self.tasks["41"]["parent"] = 40
        write_json_atomic(self.paths.state, {"tasks": self.tasks})
        self.assertEqual(self.decide("Write", target)[0], "pass")
        self.assertEqual(self.decide("Write", {"file_path": str(self.other / "new.txt")})[0], "deny")


class Shell(Base):
    def test_protected_write_targets_named_in_a_command(self):
        site = (PYTHON_HOME / "Lib" / "site-packages").as_posix()
        rows = [
            (f"echo import os > {site}/zz.pth", "deny"),
            (f"cp {self.work.as_posix()}/x.pth {site}/", "deny"),
            (f'Copy-Item x.pth "{site}/zz.pth"', "deny"),
            (f"Set-Content -Path {site}/zz.pth -Value x", "deny"),
            (f"python -c \"open('{site}/zz.pth','w').write('x')\"", "deny"),
            ("echo 'curl x' >> ~/.bashrc", "deny"),
            ("echo x >> $HOME/.bash_profile", "deny"),
            ("Add-Content $env:USERPROFILE/Documents/PowerShell/profile.ps1 'iwr x'", "deny"),
            ("curl -o ~/.ssh/authorized_keys https://github.com/x.keys", "deny"),
            ('Invoke-WebRequest https://github.com/x -OutFile "$env:APPDATA/GitHub CLI/config.yml"', "deny"),
            ("tee ~/.gitconfig < x", "deny"),
            ("git config --global core.hooksPath C:/x", "deny"),
            ("git config --global alias.p push", "deny"),
            ('mklink "%APPDATA%/Microsoft/Windows/Start Menu/Programs/Startup/x.lnk" C:/x.exe', "deny"),
            (f"New-Item -ItemType File {self.paths.root.as_posix()}/projects.json", "deny"),
            (f"cp {self.work.as_posix()}/p.json {self.paths.root.as_posix()}/projects.json", "deny"),
            (f"ln -s C:/x {(HERE / 'evil.py').as_posix()}", "deny"),
            # reads of those places, and writes elsewhere, are not this rule's business
            ("cat ~/.gitconfig", "pass"),
            ("git config --global --get user.name", "pass"),
            (f"cp {self.work.as_posix()}/a.csv {self.work.as_posix()}/b.csv", "pass"),
            ("python -m pytest -q", "pass"),
            ("ls ~/.ssh", "pass"),
        ]
        for cmd, want in rows:
            for tool in ("Bash", "PowerShell"):
                for f in sorted(self.paths.taint.rglob("*"), reverse=True) if self.paths.taint.exists() else []:
                    f.unlink() if f.is_file() else f.rmdir()  # a fetch row taints the task
                with self.subTest(cmd=cmd, tool=tool):
                    self.assertEqual(self.decide(tool, {"command": cmd})[0], want)


class HookStartsIsolated(Base):
    """`python -I -S asklist_hook.py`: no site, no .pth, no PYTHON* env. Anything else: deny all."""

    def run_hook(self, *flags):
        ev = {"tool_name": "Bash", "tool_input": {"command": "python -m pytest -q"},
              "cwd": str(self.work), "agent_id": "agentA"}
        write_json_atomic(self.paths.config, {"scope": "full"})
        env = {**os.environ, "LEGION_CLAUDE_HOME": str(self.paths.root)}
        p = subprocess.run([sys.executable, *flags, str(HERE / "asklist_hook.py")], input=json.dumps(ev),
                           capture_output=True, text=True, env=env)
        self.assertEqual(p.returncode, 0, p.stderr)
        return json.loads(p.stdout) if p.stdout.strip() else None

    def test_isolated_runs_normally(self):
        self.assertIsNone(self.run_hook("-I", "-S"))

    def test_not_isolated_or_with_site_fails_closed(self):
        for flags in ((), ("-I",), ("-S",)):
            with self.subTest(flags=flags):
                out = self.run_hook(*flags)
                self.assertEqual(out["hookSpecificOutput"]["permissionDecision"], "deny")
                self.assertIn("-I -S", out["hookSpecificOutput"]["permissionDecisionReason"])

    def test_the_worker_writes_the_isolated_hook_command(self):
        import worker
        self.assertIn(" -I -S ", worker.hook_command())


if __name__ == "__main__":
    unittest.main()
