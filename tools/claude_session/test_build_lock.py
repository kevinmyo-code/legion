"""alfred-builds ticket 08: one build at a time, the heat pause, the private Gradle registry.
No Gradle, no laptopctl agent: fake readings and a fake runner.

    python -m pytest tools/claude_session/test_build_lock.py -q
"""
from __future__ import annotations

import io
import subprocess
import sys
import tempfile
import textwrap
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import build_lock as B  # noqa: E402
from common import Paths  # noqa: E402


class Clock:
    def __init__(self):
        self.t = 0.0
        self.slept = []

    def __call__(self):
        return self.t

    def sleep(self, s):
        self.slept.append(s)
        self.t += s


def readings(*values):
    it = iter(values)
    last = [None]

    def read():
        try:
            last[0] = next(it)
        except StopIteration:
            pass
        return last[0]
    return read


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.paths = Paths(Path(self.tmp.name) / "home").ensure()
        self.said = []
        self.clock = Clock()

    def tearDown(self):
        self.tmp.cleanup()


class TestHeat(Base):
    def gate(self, read, **kw):
        return B.heat_gate("testDebugUnitTest", read=read, sleep=self.clock.sleep, clock=self.clock,
                           say=self.said.append, **kw)

    def test_cool_runs_without_waiting(self):
        self.assertEqual(self.gate(readings(70.0)), 70.0)
        self.assertEqual(self.clock.slept, [])
        self.assertEqual(self.said, [])

    def test_hot_pauses_until_under_80_not_just_under_92(self):
        t = self.gate(readings(95.0, 90.0, 85.0, 79.0))
        self.assertEqual(t, 79.0)
        self.assertEqual(len(self.clock.slept), 3)
        self.assertIn("95 C", self.said[0])
        self.assertIn("pausing before testDebugUnitTest", self.said[0])
        self.assertIn("Cooled to 79 C", self.said[-1])

    def test_exactly_92_is_not_over(self):
        self.assertEqual(self.gate(readings(92.0)), 92.0)
        self.assertEqual(self.clock.slept, [])

    def test_stays_hot_times_out_in_words(self):
        with self.assertRaises(B.TimedOut) as e:
            self.gate(readings(96.0), max_wait_s=100, poll_s=20)
        self.assertIn("stayed over 80 C", str(e.exception))

    def test_agent_down_is_said_and_the_build_goes_on(self):
        self.assertIsNone(self.gate(readings(None)))
        self.assertIn("Heat unknown", self.said[0])
        self.assertEqual(self.clock.slept, [])

    def test_hottest_reads_cpu_and_gpu_of_the_agent_status(self):
        status = {"temps_c": {"cpu": 71, "gpu": 88, "front": 99}, "fans": {}}
        self.assertEqual(B.hottest(status), 88.0)
        self.assertIsNone(B.hottest({}))


class TestLock(Base):
    def test_second_task_waits_and_says_so_then_times_out(self):
        first = B.BuildLock(self.paths, 41)
        first.acquire()
        try:
            second = B.BuildLock(self.paths, 42, sleep=self.clock.sleep, clock=self.clock,
                                 say=self.said.append)
            with self.assertRaises(B.TimedOut):
                second.acquire(wait_s=30, poll_s=10)
            self.assertEqual(self.said, ["Waiting for another build (task 41)."])
        finally:
            first.release()
        second = B.BuildLock(self.paths, 42)
        second.acquire(wait_s=0)
        self.assertEqual(second.holder(), 42)
        second.release()
        self.assertIsNone(second.holder())

    def test_waiter_gets_the_lock_when_the_holder_releases(self):
        first = B.BuildLock(self.paths, 41)
        first.acquire()
        second = B.BuildLock(self.paths, 42, clock=self.clock, say=self.said.append,
                             sleep=lambda s: (self.clock.sleep(s), first.release()))
        second.acquire(wait_s=60, poll_s=10)
        self.assertEqual(second.holder(), 42)
        second.release()

    def test_a_dead_holder_releases_the_lock_with_its_process(self):
        script = textwrap.dedent(f"""
            import sys; sys.path.insert(0, {str(HERE)!r})
            from build_lock import BuildLock
            from common import Paths
            BuildLock(Paths({str(self.paths.root)!r}), 7).acquire()
            sys.exit(0)  # never released
        """)
        subprocess.run([sys.executable, "-c", script], check=True, timeout=60)
        lock = B.BuildLock(self.paths, 8)
        lock.acquire(wait_s=0)
        lock.release()


class TestSteps(Base):
    def run_steps(self, steps, codes=None, read=readings(70.0), **kw):
        ran = []
        codes = list(codes or [])

        def runner(argv, cwd):
            ran.append(argv)
            if "--stop" in argv:
                return 0
            return codes.pop(0) if codes else 0
        code = B.run_steps(self.paths, 41, steps, runner=runner, read=read, sleep=self.clock.sleep,
                           clock=self.clock, say=self.said.append, **kw)
        return code, ran

    def test_gradle_gets_the_private_registry_and_stop_after(self):
        code, ran = self.run_steps([["gradlew.bat", "compileDebugKotlin"],
                                    ["gradlew.bat", "testDebugUnitTest"]])
        self.assertEqual(code, 0)
        flag = f"-Dorg.gradle.daemon.registry.base={self.paths.root / 'gradle-daemons'}"
        self.assertEqual(ran, [["gradlew.bat", "compileDebugKotlin", flag],
                               ["gradlew.bat", "testDebugUnitTest", flag],
                               ["gradlew.bat", "--stop", flag]])

    def test_heat_is_checked_before_each_step(self):
        code, ran = self.run_steps([["gradlew", "a"], ["gradlew", "b"]],
                                   read=readings(70.0, 95.0, 78.0))
        self.assertEqual(code, 0)
        self.assertEqual(len(self.clock.slept), 1)
        self.assertTrue(any("pausing before gradlew b" in s for s in self.said))

    def test_a_failing_step_stops_the_rest_still_stops_gradle_and_releases(self):
        code, ran = self.run_steps([["gradlew", "a"], ["gradlew", "b"]], codes=[3])
        self.assertEqual(code, 3)
        self.assertEqual([r[1] for r in ran], ["a", "--stop"])
        lock = B.BuildLock(self.paths, 9)
        lock.acquire(wait_s=0)
        lock.release()

    def test_non_gradle_steps_get_no_flag(self):
        code, ran = self.run_steps([["uv", "run", "pytest"]])
        self.assertEqual(ran, [["uv", "run", "pytest"]])

    def test_cli_runs_a_real_command_under_the_lock(self):
        import os
        old = os.environ.get("LEGION_CLAUDE_HOME")
        os.environ["LEGION_CLAUDE_HOME"] = str(self.paths.root)
        try:
            out = io.StringIO()
            with redirect_stdout(out):
                code = B.main(["--task", "41", "--no-heat", "--", sys.executable, "-c", "print(1)"])
            self.assertEqual(code, 0)
            self.assertIn("All steps passed.", out.getvalue())
        finally:
            if old is None:
                os.environ.pop("LEGION_CLAUDE_HOME")
            else:
                os.environ["LEGION_CLAUDE_HOME"] = old


if __name__ == "__main__":
    unittest.main()
