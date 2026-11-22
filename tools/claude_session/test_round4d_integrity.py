"""Review round 4d (2026-11-22), ported from the reviewer's test: plants the old integrity check
missed, and the hook decisions around the manifest.

Each plant goes into its OWN temp copy of this folder (real scripts, not stubs), with a manifest
written for that copy first (LEGION_CLAUDE_HOME = a temp root). Then `integrity.py --check`, a run
of the copy's `report.py`, and a run of the copy's own `asklist_hook.py` on a hook event. A plant
"ran" when its marker line appears. A plant must never run in the hook, and every plant the check
can see (a package folder, `.pyw`, a sourceless `.pyc`, an extra `.py`) must fail it. A forged
`__pycache__` `.pyc` is never read at all (every script sets `sys.pycache_prefix`), so the check
skips `__pycache__` and the plant must simply never run.

Writes only inside temp folders. The user-site / site-packages rows are decisions only, and they
pass by ruling: changing the python install is an OS-level step for Kevin (review round 4d).

    python -m unittest discover -s tools/claude_session -p "test_round4d_integrity.py" -v
"""
from __future__ import annotations

import importlib._bootstrap_external as be
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import asklist_hook as H  # noqa: E402
import containment as C  # noqa: E402
import integrity  # noqa: E402
from common import Paths, write_json_atomic  # noqa: E402

PY = sys.executable


def shadow(mark: Path) -> str:
    """A `json` that writes a marker line, then hands over to the real one."""
    return f'''import sys, os
open(r"{mark}", "a").write("PLANT %s ran in %s\\n" % (__name__, os.path.basename(sys.argv[0])))
_here = os.path.dirname(os.path.abspath(__file__))
if os.path.basename(_here) in ("json", "__pycache__"):
    _here = os.path.dirname(_here)
sys.path[:] = [p for p in sys.path if os.path.normcase(os.path.abspath(p or ".")) != os.path.normcase(_here)]
del sys.modules["json"]
import json as _real
sys.modules["json"] = _real
globals().update(_real.__dict__)
'''


def plant_extra_py(folder: Path, mark: Path):
    (folder / "json.py").write_text(shadow(mark))


def plant_pkg(folder: Path, mark: Path):
    (folder / "json").mkdir()
    (folder / "json" / "__init__.py").write_text(shadow(mark))


def plant_pyw(folder: Path, mark: Path):
    (folder / "json.pyw").write_text(shadow(mark))


def plant_sourceless_pyc(folder: Path, mark: Path):
    code = compile(shadow(mark), str(folder / "json.py"), "exec")
    (folder / "json.pyc").write_bytes(be._code_to_timestamp_pyc(code, 0, 0))


def plant_forged_cache(folder: Path, mark: Path):
    """__pycache__/common.cpython-XY.pyc whose header matches common.py's mtime and size."""
    src = folder / "common.py"
    st = src.stat()
    head, sep, tail = src.read_text(encoding="utf-8").partition(
        "from __future__ import annotations\n")
    text = head + sep + f'open(r"{mark}", "a").write("PLANT common-pyc ran\\n")\n' + tail
    code = compile(text, str(src), "exec")
    # Beside the source, where Python would look without a pycache prefix (this test process has
    # one set, so cache_from_source would point elsewhere).
    cache = folder / "__pycache__" / f"common.{sys.implementation.cache_tag}.pyc"
    cache.parent.mkdir(exist_ok=True)
    cache.write_bytes(be._code_to_timestamp_pyc(code, int(st.st_mtime), st.st_size))


PLANTS = (("control-extra-py", plant_extra_py, True), ("pkg-dir-json", plant_pkg, True),
          ("json-pyw", plant_pyw, True), ("sourceless-json-pyc", plant_sourceless_pyc, True),
          ("forged-pycache-common", plant_forged_cache, False))


class Round4dPlants(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="r4d-")
        self.R = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def probe(self, name, plant):
        folder = self.R / name / "claude_session"
        shutil.copytree(HERE, folder, ignore=shutil.ignore_patterns(
            "__pycache__", ".*", "test_*.py", "test_fixtures"))
        root = self.R / f"root-{name}"
        root.mkdir()
        mark = self.R / f"marks-{name}.log"
        env = {**os.environ, "LEGION_CLAUDE_HOME": str(root)}
        w = subprocess.run([PY, str(folder / "integrity.py"), "--write"], env=env,
                           capture_output=True, text=True, timeout=60)
        self.assertEqual(w.returncode, 0, w.stdout + w.stderr)
        plant(folder, mark)
        chk = subprocess.run([PY, str(folder / "integrity.py"), "--check"], env=env,
                             capture_output=True, text=True, timeout=60)
        subprocess.run([PY, str(folder / "report.py"), "--task", "41", "--status", "done",
                        "--summary", "x"], env=env, capture_output=True, timeout=60)
        in_script = mark.read_text().splitlines() if mark.exists() else []
        ev = {"tool_name": "Bash", "tool_input": {"command": "ls"}, "cwd": str(root),
              "agent_id": "x"}
        hk = subprocess.run([PY, "-I", "-S", str(folder / "asklist_hook.py")], env=env, input=json.dumps(ev),
                            capture_output=True, text=True, timeout=60)
        in_hook = (mark.read_text().splitlines() if mark.exists() else [])[len(in_script):]
        return chk, in_script, in_hook, hk

    def test_plants_never_run_and_the_visible_ones_fail_the_check(self):
        for name, plant, visible in PLANTS:
            with self.subTest(case=name):
                chk, in_script, in_hook, hk = self.probe(name, plant)
                self.assertEqual(in_hook, [], f"{name} ran inside the hook")
                self.assertEqual(in_script, [], f"{name} ran inside report.py")
                if visible:
                    self.assertEqual(chk.returncode, 1, chk.stdout)
                    self.assertIn("WORKER CODE CHANGED", hk.stdout)  # every tool call refused
                else:
                    self.assertEqual(chk.returncode, 0, chk.stdout)

    def test_a_clean_copy_is_intact_and_the_hook_answers_normally(self):
        chk, in_script, in_hook, hk = self.probe("clean", lambda folder, mark: None)
        self.assertEqual(chk.returncode, 0, chk.stdout)
        self.assertNotIn("WORKER CODE CHANGED", hk.stdout)


class Round4dDecisions(unittest.TestCase):
    def test_decisions(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        paths = Paths(Path(tmp.name) / "home").ensure()
        integrity.write_manifest(paths)
        for t in (41, 42):
            paths.task_work(t).mkdir(parents=True)
        write_json_atomic(paths.agents, {"agentA": 41, "agentB": 42})
        write_json_atomic(paths.state, {"tasks": {"41": {}, "42": {}}})
        C.mark(paths, 41, "agentA", "WebFetch")
        src = (paths.task_work(42) / "e.txt").as_posix()
        man = integrity.manifest_path(paths).as_posix()
        hp, parent = HERE.as_posix(), HERE.parent.as_posix()
        py = PY.replace("\\", "/")
        usite = os.path.join(os.environ.get("APPDATA", ""), "Python",
                             f"Python{sys.version_info[0]}{sys.version_info[1]}", "site-packages")
        rows = [
            ("D1 Write manifest", "Write", {"file_path": man, "content": "{}"}, "agentB", None, "deny"),
            ("D2 integrity.py --write (Bash)", "Bash",
             {"command": f"{py} {hp}/integrity.py --write"}, "agentB", None, "deny"),
            ("D2b integrity.py --write (PS)", "PowerShell",
             {"command": f"& {py} {hp}/integrity.py --write"}, "agentB", None, "deny"),
            ("D2c integrity.py --write tainted", "Bash",
             {"command": f"{py} {hp}/integrity.py --write"}, "agentA", None, "deny"),
            ("D2d -I and python -c", "Bash",
             {"command": f"{py} -I -c \"import sys; sys.path.append('x'); import integrity; "
                         f"integrity.write_manifest()\""}, "agentB", None, "deny"),
            ("D3 cp onto manifest", "Bash", {"command": f"cp {src} {man}"}, "agentB", None, "deny"),
            # Refused outright since the port of round 4 R4-1 (outside_writes.py: the python's tree
            # and user site are what the hook runs on), even for a trusted helper:
            ("D4 Write usercustomize.py (user site)", "Write",
             {"file_path": os.path.join(usite, "usercustomize.py"), "content": "x"}, "agentB", None,
             "deny"),
            ("D5 Write evil.pth (site-packages)", "Write",
             {"file_path": os.path.join(sys.prefix, "Lib", "site-packages", "zz-evil.pth"),
              "content": "import os"}, "agentB", None, "deny"),
            # Built at run time; the integrity check now sees the folder it makes (Round4dPlants).
            ("D6 Join-Path plant pkg dir (V12b class)", "PowerShell",
             {"command": f"New-Item -ItemType Directory (Join-Path '{parent}' "
                         f"('claude_' + 'session/json'))"}, "agentB", None, "allow"),
            ("D7 Set-Location parent, new file", "PowerShell",
             {"command": f"Set-Location {parent}; Copy-Item {src} claude_session/new.py"}, "agentB",
             None, "deny"),
            ("D8 cwd parent, new file", "Bash", {"command": f"cp {src} claude_session/new.py"},
             "agentB", parent, "deny"),
        ]
        for cid, tool, inp, agent, cwd, expect in rows:
            out, rule = H.decide({"tool_name": tool, "tool_input": inp,
                                  "cwd": cwd or str(paths.task_work(42)), "agent_id": agent},
                                 paths, {"scope": "full"})
            with self.subTest(case=cid):
                self.assertEqual("deny" if out else "allow", expect, f"{cid}: {rule}")

    def test_every_file_any_depth_and_odd_folders_count(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        folder = Path(tmp.name) / "code"
        (folder / "test_fixtures" / "w").mkdir(parents=True)
        (folder / "a.py").write_text("x")
        (folder / "notes.md").write_text("x")
        (folder / "test_fixtures" / "w" / "c.json").write_text("{}")
        (folder / "__pycache__").mkdir()
        (folder / "__pycache__" / "a.cpython-313.pyc").write_bytes(b"x")
        files, odd = integrity.scan(folder)
        self.assertEqual(sorted(files), ["a.py", "notes.md", "test_fixtures/w/c.json"])
        self.assertEqual(odd, [])
        (folder / "pkg").mkdir()
        self.assertEqual(integrity.scan(folder)[1], ["pkg"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
