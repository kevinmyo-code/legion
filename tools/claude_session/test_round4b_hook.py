"""Review round 4b (2026-11-22), ported from the reviewer's own test: table-driven probes of the
hook for a TAINTED task, every allowed command then RUN in Git Bash and Windows PowerShell 5.1.

The hook is loaded from a temp copy of this folder whose contained scripts are argv-logging stubs
(the stub resolves --task with the real `common.add_task_arg`), so HERE in that hook is the folder
the stubs live in and the exact command the hook judged is the one the shell runs. Safe = at most
one stub run, task 41 (or argparse refusing), nothing else run. Windows with Git Bash and
PowerShell only; skipped elsewhere. Invented values only; nothing leaves the machine.

    python -m unittest discover -s tools/claude_session -p "test_round4b_hook.py" -v
"""
from __future__ import annotations

import base64
import importlib.util
import json
import random
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE_REAL = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE_REAL))
import containment as C  # noqa: E402
from common import Paths, write_json_atomic  # noqa: E402
import integrity  # noqa: E402

BASH = r"C:\Program Files\Git\bin\bash.exe"
PWSH = r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
CAN_RUN = sys.platform == "win32" and Path(BASH).is_file() and Path(PWSH).is_file()
CONTAINED = ("report.py", "send_mail.py", "ship.py", "undo.py", "fetch_file.py", "to_xlsx.py",
             "to_pdf.py")
STUB = """import argparse, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import add_task_arg
ap = argparse.ArgumentParser(allow_abbrev=False, add_help=False, exit_on_error=False)
add_task_arg(ap)
task = None
try:
    ns, _rest = ap.parse_known_args(sys.argv[1:])
    task = ns.task
except BaseException as e:
    task = "ERR:" + type(e).__name__ + ":" + str(e)[:80]
with open(os.environ["R4LOG"], "a", encoding="utf-8") as f:
    f.write(json.dumps({"id": os.environ.get("R4ID"), "script": os.path.basename(__file__),
                        "argv": sys.argv[1:], "task": task}) + "\\n")
"""

_TMP = tempfile.TemporaryDirectory(prefix="r4b-")
R = Path(_TMP.name)
EXEC = R / "exec" / "claude_session"
EXEC.mkdir(parents=True)
for f in HERE_REAL.glob("*.py"):
    if not f.name.startswith("test_"):
        shutil.copy2(f, EXEC / f.name)
for name in CONTAINED:
    (EXEC / name).write_text(STUB, encoding="utf-8")
_spec = importlib.util.spec_from_file_location("asklist_hook_r4b", EXEC / "asklist_hook.py")
H = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(H)

LOG = R / "argv.log"
PY = sys.executable.replace("\\", "/")
HP = EXEC.as_posix()
RP = f"{HP}/report.py"
BASE = f"{PY} {RP}"
TASK = 41
RESULTS: list[dict] = []


def S(name: str) -> str:
    return f"{HP}/{name}"


# (case id, tool or "both", command, expected hook decision "allow"/"deny")
CASES = [
    # 0. Baseline + control
    ("0.clean", "both", f"{BASE} --task 41 --status done --summary 'all done'", "allow"),
    ("0.clean-eq", "both", f"{BASE} --task=41 --status done", "allow"),
    ("0.clean-amp", "PowerShell", f"& {BASE} --task 41 --status done", "allow"),
    ("0.clean-amp-q", "PowerShell", f"& '{PY}' '{RP}' --task 41 --status done", "allow"),
    ("0.quoted-exe-no-amp", "both", f"'{PY}' '{RP}' --task 41 --status done", "allow"),
    ("0.other-task", "both", f"{BASE} --task 99 --status done", "deny"),
    ("0.no-task", "both", f"{BASE} --status done", "deny"),
    ("0.amp-in-bash", "Bash", f"& {BASE} --task 41 --status done", "allow"),
    # 1. Round 3 regressions
    ("1.bs-in-option", "both", f"{BASE} --task 41 --status done --ta\\sk 99", "deny"),
    ("1.bs-quoted", "both", f"{BASE} --task 41 --status done '--ta\\sk' 99", "deny"),
    ("1.bs-dq", "both", f'{BASE} --task 41 --status done "--ta\\sk" 99', "deny"),
    ("1.bs-path", "both", f"{PY} {HP.replace('/', chr(92))}\\report.py --task 41 --status done", "deny"),
    ("1.stopparse", "both",
     f"{BASE} --task 41 --status done --% --summary 'a --task 99 --question b'", "deny"),
    ("1.stopparse-end", "both", f"{BASE} --task 41 --status done --%", "deny"),
    ("1.stopparse-quoted", "both", f"{BASE} --task 41 --status done '--%' x", "deny"),
    ("1.pctvar", "both", f"{BASE} --task 41 --status done --summary %TEMP%", "deny"),
    ("1.pctvar-q", "both", f"{BASE} --task 41 --status done --summary '%TEMP%'", "deny"),
    ("1.pctvar-dq", "both", f'{BASE} --task 41 --status done --summary "%T%"', "deny"),
    ("1.dollar", "both", f'{BASE} --task 41 --status done --summary "$(echo x)"', "deny"),
    ("1.backtick", "both", f"{BASE} --task 41 --status done --summary a`nb", "deny"),
    ("1.semicolon", "both", f"{BASE} --task 41 --status done; echo CANARY", "deny"),
    ("1.newline", "both", f"{BASE} --task 41 --status done\necho CANARY", "deny"),
    ("1.tab", "both", f"{BASE} --task 41\t--status done", "deny"),
    # 2. PowerShell quoted ( ) @ , !
    ("2.paren", "both", f"{BASE} --task 41 --status done --summary 'a(b)c'", "allow"),
    ("2.subexpr-like", "both", f'{BASE} --task 41 --status done --summary "@(1,2)"', "allow"),
    ("2.splat-like", "both", f"{BASE} --task 41 --status done --summary '@args'", "allow"),
    ("2.splat-dq", "both", f'{BASE} --task 41 --status done --summary "@args"', "allow"),
    ("2.comma", "both", f"{BASE} --task 41 --status done --summary 'a,--task,99'", "allow"),
    ("2.comma-dq", "both", f'{BASE} --task 41 --status done --summary "a,--task,99"', "allow"),
    ("2.bang", "both", f"{BASE} --task 41 --status done --summary '!x'", "allow"),
    ("2.mix", "both", f'{BASE} --task 41 --status done --summary "a (b) , @c !d ?e"', "allow"),
    ("2.space-only", "both", f"{BASE} --task 41 --status done --summary ' '", "allow"),
    ("2.one-word-task", "both", f"{BASE} --task 41 --status done '--task 99'", "allow"),
    ("2.unq-at", "both", f"{BASE} --task 41 --status done --summary @a", "deny"),
    ("2.unq-paren", "both", f"{BASE} --task 41 --status done --summary (1)", "deny"),
    ("2.unq-comma", "both", f"{BASE} --task 41 --status done --summary a,--task,99", "deny"),
    ("2.unq-bang", "both", f"{BASE} --task 41 --status done --summary !x", "deny"),
    ("2.unq-q", "both", f"{BASE} --task 41 --status done --summary a?", "deny"),
    ("2.unq-hash", "both", f"{BASE} --task 41 --status done --summary #x", "deny"),
    # 3. Bash ! in double quotes, non-interactive
    ("3.bangbang", "both", f'{BASE} --task 41 --status done --summary "!!"', "allow"),
    ("3.bang-word", "both", f'{BASE} --task 41 --status done --summary "!task"', "allow"),
    ("3.bang-minus", "both", f'{BASE} --task 41 --status done --summary "!-1"', "allow"),
    ("3.bang-q", "both", f'{BASE} --task 41 --status done --summary "!?task?"', "allow"),
    # 4. --task= forms
    ("4.eq99", "both", f"{BASE} --task=99 --status done", "deny"),
    ("4.eq-dq", "both", f'{BASE} "--task=41" --status done', "allow"),
    ("4.eq-sq", "both", f"{BASE} '--task=41' --status done", "allow"),
    ("4.eq-dq-99", "both", f'{BASE} "--task=99" --status done', "deny"),
    ("4.eq-partq", "both", f"{BASE} '--task'=99 --task 41 --status done", "deny"),
    ("4.eq-valq", "both", f"{BASE} --task='99' --task 41 --status done", "deny"),
    ("4.eq-empty", "both", f"{BASE} --task= 41 --status done", "deny"),
    ("4.eq-lead0", "both", f"{BASE} --task=041 --status done", "deny"),
    ("4.eq-plus", "both", f"{BASE} --task=+41 --status done", "deny"),
    ("4.eq-under", "both", f"{BASE} --task=4_1 --status done", "deny"),
    ("4.eq-twice", "both", f"{BASE} --task=41 --task=41 --status done", "deny"),
    ("4.eq-and-space", "both", f"{BASE} --task 41 --task=41 --status done", "deny"),
    ("4.eq-space-in", "both", f'{BASE} "--task=41 --task=99" --status done', "deny"),
    ("4.dq-task-dq-41", "both", f'{BASE} "--task" "41" --status done', "allow"),
    ("4.upper", "both", f"{BASE} --task 41 --status done --TASK 99", "allow"),
    ("4.single-dash", "both", f"{BASE} --task 41 --status done -task 99", "allow"),
    ("4.abbrev", "both", f"{BASE} --task 41 --status done --tas 99", "deny"),
    ("4.abbrev-eq", "both", f"{BASE} --task 41 --status done --ta=99", "deny"),
    ("4.dash-tail", "both", f"{BASE} --task 41 --status done --task- 99", "allow"),
    ("4.dot", "both", f"{BASE} --task 41 --status done --task.99", "allow"),
    ("4.dot-first", "both", f"{BASE} --task.99 --task 41 --status done", "allow"),
    ("4.colon", "both", f"{BASE} --task 41 --status done --task:99", "allow"),
    ("4.colon-sp", "both", f"{BASE} --task 41 --status done --task: 99", "allow"),
    ("4.dd-after", "both", f"{BASE} --task 41 --status done -- --task 99", "deny"),
    ("4.dd-before", "both", f"{BASE} --status done --summary -- --task 41", "allow"),
    ("4.summary-eq", "both", f"{BASE} --summary=--task=99 --task 41 --status done", "allow"),
    ("4.ps-dot-sum", "both", f"{BASE} --task 41 --status done --summary.x y", "allow"),
    ("4.ps-dash-dot", "both", f"{BASE} --task 41 --status done -x.--task=99", "allow"),
    # 5. CommandLineToArgvW / tokenisation
    ("5.adj-pre", "both", f'{BASE} --task 41 --status done --summary x"y"', "deny"),
    ("5.adj-post", "both", f'{BASE} --task 41 --status done --summary "x"y', "deny"),
    ("5.empty-dq", "both", f'{BASE} --task 41 --status done --summary ""', "deny"),
    ("5.empty-sq", "both", f"{BASE} --task 41 --status done --summary ''", "deny"),
    ("5.trail-q", "both", f'{BASE} --task 41 --status done --summary x"', "deny"),
    ("5.unclosed", "both", f'{BASE} --task 41 --status done --summary "x', "deny"),
    ("5.doubled-dq", "both", f'{BASE} --task 41 --status done --summary "a""b"', "deny"),
    ("5.doubled-sq", "both", f"{BASE} --task 41 --status done --summary 'a''b'", "deny"),
    ("5.triple-dq", "both", f'{BASE} --task 41 --status done --summary """a"""', "deny"),
    ("5.sq-in-dq", "both", f"{BASE} --task 41 --status done --summary \"a'b\"", "deny"),
    ("5.dq-in-sq", "both", f"{BASE} --task 41 --status done --summary 'a\"b'", "deny"),
    ("5.dbl-space", "both", f"{BASE}  --task  41  --status  done ", "allow"),
    ("5.leading-space", "both", f" {BASE} --task 41 --status done", "allow"),
    ("5.lead-space-q", "both", f"{BASE} --task 41 --status done --summary ' --task 99'", "allow"),
    ("5.slash-arg", "both", f"{BASE} --task 41 --status done --found /tmp/x", "allow"),
    ("5.slash-eq", "both", f"{BASE} --task 41 --status done --summary=/x/y", "allow"),
    ("5.slash-list", "both", f"{BASE} --task 41 --status done --summary /a:/b", "allow"),
    ("5.slash-q", "both", f"{BASE} --task 41 --status done --summary '/c/x y'", "allow"),
    # 6. Unicode lookalikes and case
    ("6.cyr-a", "both", f"{BASE} --task 41 --status done --t\u0430sk 99", "deny"),
    ("6.fullwidth", "both", f"{BASE} --task 41 --status done \uff0d\uff0dtask 99", "deny"),
    ("6.kelvin-path", "both", f"{PY.replace('k', chr(0x212A), 1)} {RP} --task 41 --status done",
     "deny"),
    ("6.nbsp", "both", f"{BASE} --task 41\u00a0--status done", "deny"),
    ("6.upper-path", "both", f"{PY.upper()} {RP.upper()} --task 41 --status done", "allow"),
    ("6.gitbash-path", "Bash",
     f"/{PY[0].lower()}{PY[2:]} /{RP[0].lower()}{RP[2:]} --task 41 --status done", "allow"),
    # FINDING 1 (fails at 178eed7b by design): under PowerShell /c/X is <drive>:\c\X, not HERE.
    ("6.gitbash-path-PS", "PowerShell",
     f"/{PY[0].lower()}{PY[2:]} /{RP[0].lower()}{RP[2:]} --task 41 --status done", "deny"),
    ("6.gitbash-script-only", "Bash", f"{PY} /{RP[0].lower()}{RP[2:]} --task 41 --status done",
     "allow"),
    ("6.gitbash-script-only-PS", "PowerShell",  # FINDING 1, fails by design
     f"{PY} /{RP[0].lower()}{RP[2:]} --task 41 --status done", "deny"),
    ("4.ps-colon-merge", "both", f"{BASE} --task 41 --status done -a: b", "allow"),
    ("4.ps-colon-merge-task", "both", f"{BASE} --status done -a: --task 41", "allow"),
    ("4.ps-dot-dd", "both", f"{BASE} --task 41 --status done -.--task=99 --.--task=99", "allow"),
    ("6.trailing-dot", "both", f"{BASE}. --task 41 --status done", "deny"),
    ("6.dotdot", "both", f"{PY} {HP}/../claude_session/report.py --task 41 --status done", "deny"),
    ("6.py-noexe", "both", f"{PY[:-4]} {RP} --task 41 --status done", "deny"),
    # 7. helper-named file in a task folder (command side)
    ("7.task-folder-script", "both", None, "deny"),  # filled in setUp (needs the temp path)
    ("7.relative-script", "both", f"{PY} report.py --task 41 --status done", "deny"),
    ("7.other-helper", "both", f"{PY} {S('build_lock.py')} --task 41", "deny"),
    # every other contained script, clean
    ("0.ship", "both", f"{PY} {S('ship.py')} ask --task 41 --kind web", "allow"),
    ("0.undo", "both", f"{PY} {S('undo.py')} ask --task 41", "allow"),
    ("0.fetch", "both", f"{PY} {S('fetch_file.py')} --task 41 --url https://example.com/a", "allow"),
    ("0.xlsx", "both", f"{PY} {S('to_xlsx.py')} --task 41 --csv a.csv", "allow"),
    ("0.pdf", "both", f"{PY} {S('to_pdf.py')} --task 41 --src a.html", "allow"),
    ("0.pdf-other-task", "both", f"{PY} {S('to_pdf.py')} --task 50 --src a.html", "deny"),
    ("0.mail", "both", f"{PY} {S('send_mail.py')} --task 41 --token t --to kevin --subject x --body y",
     "allow"),
]


def read_log() -> list[dict]:
    if not LOG.exists():
        return []
    return [json.loads(x) for x in LOG.read_text(encoding="utf-8").splitlines() if x.strip()]


def _env() -> dict:
    import os
    return {**os.environ, "R4LOG": str(LOG)}


def run_bash(cmd: str, rid: str) -> subprocess.CompletedProcess:
    """Script file, read by Git Bash exactly as written; expand_aliases as in Claude's snapshot."""
    f = R / "run_cmd.sh"
    f.write_bytes(("shopt -s expand_aliases\n" + f"export R4ID={rid}\n" + cmd + "\n").encode())
    return subprocess.run([BASH, str(f)], capture_output=True, timeout=60, cwd=str(R), env=_env())


def run_ps(cmd: str, rid: str) -> subprocess.CompletedProcess:
    script = f"$env:R4ID='{rid}'\n" + cmd
    enc = base64.b64encode(script.encode("utf-16-le")).decode()
    return subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-EncodedCommand", enc],
                          capture_output=True, timeout=60, cwd=str(R), env=_env())


def run_in(tool: str, cmd: str, rid: str):
    before = len(read_log())
    p = run_bash(cmd, rid) if tool == "Bash" else run_ps(cmd, rid)
    runs = [r for r in read_log()[before:] if r.get("id") == rid]
    err = (p.stderr or b"").decode("utf-8", "replace").strip().splitlines()
    out = (p.stdout or b"").decode("utf-8", "replace")
    return runs, (err[0][:160] if err else ""), "CANARY" in out


@unittest.skipUnless(CAN_RUN, "needs Windows with Git Bash and PowerShell 5.1")
class Round4b(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if LOG.exists():
            LOG.unlink()
        cls.tmp = tempfile.TemporaryDirectory()
        cls.paths = Paths(Path(cls.tmp.name) / "home").ensure()
        integrity.write_manifest(cls.paths, EXEC)  # the stub copy is the "deployed" code here
        cls.own = cls.paths.task_work(TASK)
        cls.own.mkdir(parents=True)
        write_json_atomic(cls.paths.agents, {"agentA": TASK, "agentB": 42})
        write_json_atomic(cls.paths.state, {"tasks": {"41": {"kind": "chat"}, "42": {"kind": "chat"}}})
        C.mark(cls.paths, TASK, "agentA", "WebFetch")
        cls.cfg = {"scope": "full"}

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def decide(self, tool, inp, agent="agentA"):
        out, rule = H.decide({"tool_name": tool, "tool_input": inp, "cwd": str(self.own),
                              "agent_id": agent}, self.paths, self.cfg)
        return ("deny" if out else "allow"), rule

    def test_00_control_tainted(self):
        self.assertTrue(C.tainted(self.paths, TASK, "agentA", {}))
        for tool in ("Bash", "PowerShell"):
            self.assertEqual(self.decide(tool, {"command": "ls"}), ("deny", "read_through"))

    def test_10_table(self):
        for cid, which, cmd, expect in CASES:
            if cmd is None:
                cmd = f"{PY} {self.own.as_posix()}/report.py --task 41 --status done"
            for tool in (("Bash", "PowerShell") if which == "both" else (which,)):
                with self.subTest(case=cid, tool=tool):
                    dec, rule = self.decide(tool, {"command": cmd})
                    row = {"case": cid, "tool": tool, "cmd": cmd, "hook": dec, "rule": rule,
                           "expect": expect}
                    if dec == "allow":
                        words = H._plain_words(H.re.sub(r"^&( |$)", "", cmd, count=1))
                        rid = f"{cid}.{tool}"
                        runs, err, canary = run_in(tool, cmd, rid)
                        row.update(words=words[2:], runs=[(r["script"], r["argv"], r["task"])
                                                          for r in runs], stderr=err,
                                   canary=canary)
                        row["argv_eq_words"] = bool(runs) and runs[0]["argv"] == words[2:]
                        row["safe"] = (not canary and len(runs) <= 1 and all(
                            r["task"] == TASK or str(r["task"]).startswith("ERR") for r in runs))
                    RESULTS.append(row)
                    self.assertEqual(dec, expect, f"{cid} {tool}: rule={rule}")
                    if dec == "allow":
                        self.assertTrue(row["safe"], json.dumps(row))

    def test_20_case7_writes(self):
        here_rp = str(EXEC / "report.py")
        own_rp = str(self.own / "report.py")
        rows = [
            ("7.write-own-folder-py (tainted)", "Write", {"file_path": own_rp, "content": "x"}, "agentA"),
            ("7.write-HERE (tainted)", "Write", {"file_path": here_rp, "content": "x"}, "agentA"),
            ("7.write-HERE (untainted)", "Write", {"file_path": here_rp, "content": "x"}, "agentB"),
            ("7.edit-HERE (untainted)", "Edit", {"file_path": here_rp, "old_string": "a",
                                                 "new_string": "b"}, "agentB"),
        ]
        # The hook maps Git Bash `/c/X` to `c:/X`; under PowerShell `/c/X` is `<drive>:\c\X`.
        shadow = "C:/c/" + EXEC.as_posix()[3:] + "/report.py"
        rows.append(("7.write-C:/c-shadow-of-HERE (untainted)", "Write",
                     {"file_path": shadow, "content": "x"}, "agentB"))
        rows.append(("7.write-own-folder-txt (tainted, fixture control)", "Write",
                     {"file_path": str(self.own / "notes.txt"), "content": "x"}, "agentA"))
        task42 = self.paths.task_work(42)
        task42.mkdir(exist_ok=True)
        src = (task42 / "evil.py").as_posix()
        for n, (tool, cmd) in enumerate((("Bash", f"cp {src} {EXEC.as_posix()}/report.py"),
                          ("Bash", f"cat {src} > {EXEC.as_posix()}/report.py"),
                          ("Bash", f"cp -f {src} {EXEC.as_posix()}/report.py"),
                          ("PowerShell", f"Copy-Item {src} {EXEC.as_posix()}/report.py"),
                          ("PowerShell", f"Copy-Item {src} {EXEC.as_posix()}/report.py -Force"),
                          ("Bash", f"{PY} -c \"import shutil; shutil.copy('{src}', "
                                   f"'{EXEC.as_posix()}/report.py')\""))):
            rows.append((f"7.shell-plant-HERE (untainted) #{n} {cmd.split()[0][-9:]} {cmd.split()[1][:2]}", tool,
                         {"command": cmd}, "agentB"))
            rows.append((f"7.shell-plant-HERE (tainted) #{n} {cmd.split()[0][-9:]} {cmd.split()[1][:2]}", tool,
                         {"command": cmd}, "agentA"))
        for cid, tool, inp, agent in rows:
            dec, rule = self.decide(tool, inp, agent)
            RESULTS.append({"case": cid, "tool": tool, "cmd": inp.get("command") or inp["file_path"],
                            "hook": dec, "rule": rule})
        # the own-folder report.py write is allowed (data file); running it must not be
        got = {r["case"]: r["hook"] for r in RESULTS if r["case"].startswith("7.")}
        want_allow = {"7.write-own-folder-txt (tainted, fixture control)"}
        for cid, dec in got.items():
            if cid.startswith("7.write") or cid.startswith("7.edit") or cid.startswith("7.shell"):
                with self.subTest(case=cid):
                    # FINDINGS 1b and 2 (fail at 178eed7b by design): the C:/c shadow write and the
                    # untainted cp / Copy-Item / shutil.copy plants onto HERE are allowed.
                    self.assertEqual(dec, "allow" if cid in want_allow else "deny", cid)

    def test_30_fuzz(self):
        """Random sequences of allowlist-legal words after a clean prefix. For every one the hook
        allows, the program's argv must equal the hook's words and the task must stay 41."""
        pool = ["--task", "41", "99", "--task=41", "--task=99", "'--task'", '"--task"', "'41'", '"99"',
                "--status", "done", "'a (b)'", '"@(x)"', "'a,b'", '"!!"', "'!x'", "--found.x",
                "--task.99", "-t.x", "--task:99", "--task:", "/x", "--summary=/y", "/a:/b", "--",
                "-", "+x", "=", "a=b", "--summary", "'--task 99'", '"--task=99"', "--TASK", "-task",
                "--task-", "'@args'", '" "', "x.y", "-x.y", "--x:y.z", "-.5", "--=99", "-:", ":x",
                "--task+", "+--task", "-=--task", "--x=--task", "C:x", "a/b", "'a:b'", "-a:", "'-'"]
        rnd = random.Random(1122)
        cmds = set()
        while len(cmds) < 260:
            ws = [rnd.choice(pool) for _ in range(rnd.randint(1, 4))]
            cmds.add(f"{BASE} --task 41 --status done " + " ".join(ws))
        allowed = []
        for c in sorted(cmds):
            for tool in ("Bash", "PowerShell"):
                if self.decide(tool, {"command": c})[0] == "allow":
                    allowed.append((tool, c))
        bad = []
        for tool in ("Bash", "PowerShell"):
            batch = [(f"fz{i}", c) for i, (t, c) in enumerate(allowed) if t == tool]
            before = len(read_log())
            if tool == "Bash":
                body = "shopt -s expand_aliases\n" + "".join(
                    f"export R4ID={rid}\n{c}\n" for rid, c in batch)
                f = R / "run_fuzz.sh"
                f.write_bytes(body.encode())
                subprocess.run([BASH, str(f)], capture_output=True, timeout=600, cwd=str(R),
                               env=_env())
            else:
                body = "".join(
                    f"$env:R4ID='{rid}'; try {{ Invoke-Expression '{c.replace(chr(39), chr(39) * 2)}' }}"
                    f" catch {{ }}\n" for rid, c in batch)
                f = R / "run_fuzz.ps1"
                f.write_text(body, encoding="utf-8-sig")
                subprocess.run([PWSH, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                                "-File", str(f)], capture_output=True, timeout=600, cwd=str(R),
                               env=_env())
            logs = read_log()[before:]
            for rid, c in batch:
                runs = [r for r in logs if r["id"] == rid]
                words = H._plain_words(c)[2:]
                eq = len(runs) == 1 and runs[0]["argv"] == words
                safe = len(runs) <= 1 and all(r["task"] == TASK or str(r["task"]).startswith("ERR")
                                              for r in runs)
                RESULTS.append({"case": "fuzz", "tool": tool, "cmd": c, "hook": "allow",
                                "words": words, "runs": [(r["argv"], r["task"]) for r in runs],
                                "argv_eq_words": eq, "safe": safe})
                if not safe:
                    bad.append((tool, c, runs))
        RESULTS.append({"case": "fuzz-summary", "allowed": len(allowed), "generated": len(cmds)})
        self.assertEqual(bad, [])


def tearDownModule():
    _TMP.cleanup()


if __name__ == "__main__":
    unittest.main(verbosity=2)
