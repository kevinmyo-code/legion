"""Laptop health snapshot (2026-11-10): the builder with laptopctl mocked, the sampler, and the
heartbeat carrying a snapshot about once a minute. Never calls the real laptopctl.

    python -m unittest discover -s tools/claude_session -p "test_*.py" -v
"""
from __future__ import annotations

import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import health as H  # noqa: E402

STATUS = {
    "temps_c": {"cpu": 71, "gpu": 48.5},
    "fans": {"cpu": {"rpm": 4200, "max_rpm": 7000}, "gpu": {"rpm": 3900.4}},
    "profile": "balanced",
}
LOAD = {"cpu": {"load_pct": 12.34, "perf_pct_of_base": 120}, "memory": {"ram_used_pct": 61.2}}
NOW = 1_800_000_000.0


class BuilderTest(unittest.TestCase):
    def test_full_readings(self):
        s = H.build_snapshot(status=STATUS, load=LOAD, disk=120.44, uptime=3600, on_mains=True,
                             reboot=False, now=NOW)
        self.assertEqual(s["cpu_c"], 71.0)
        self.assertEqual(s["gpu_c"], 48.5)
        self.assertEqual(s["fans_rpm"], {"cpu": 4200, "gpu": 3900})
        self.assertEqual(s["cpu_load_pct"], 12.3)
        self.assertEqual(s["ram_used_pct"], 61.2)
        self.assertEqual(s["disk_free_gb"], 120.4)
        self.assertEqual(s["uptime_s"], 3600)
        self.assertEqual(s["boot_at"], "2027-01-15T07:00:00+00:00")
        self.assertIs(s["laptopctl_agent"], True)
        self.assertIs(s["on_mains"], True)
        self.assertIs(s["reboot_pending"], False)
        self.assertEqual(set(s), set(H.FIELDS))

    def test_agent_down_gives_nulls_not_errors(self):
        s = H.build_snapshot(status=False, load=None, disk=None, uptime=None, on_mains=None,
                             reboot=None, now=NOW)
        self.assertIs(s["laptopctl_agent"], False)
        for k in H.FIELDS:
            if k != "laptopctl_agent":
                self.assertIsNone(s[k], k)

    def test_laptopctl_not_installed_is_null_not_false(self):
        s = H.build_snapshot(status=None, load=None, disk=1.0, uptime=5, on_mains=False,
                             reboot=None, now=NOW)
        self.assertIsNone(s["laptopctl_agent"])

    def test_agent_answered_without_readings_counts_as_running(self):
        s = H.build_snapshot(status=True, load=None, disk=None, uptime=None, on_mains=None,
                             reboot=None, now=NOW)
        self.assertIs(s["laptopctl_agent"], True)
        self.assertIsNone(s["cpu_c"])

    def test_garbage_shapes_are_null(self):
        s = H.build_snapshot(status={"temps_c": "hot", "fans": {"cpu": "x"}},
                             load={"cpu": 5, "memory": {"ram_used_pct": "nan"}}, disk="?",
                             uptime=None, on_mains=None, reboot=None, now=NOW)
        self.assertIsNone(s["cpu_c"])
        self.assertIsNone(s["fans_rpm"])
        self.assertIsNone(s["cpu_load_pct"])
        self.assertIsNone(s["ram_used_pct"])
        self.assertIsNone(s["disk_free_gb"])

    def test_collect_survives_every_source_raising(self):
        def boom():
            raise RuntimeError("no")
        s = H.collect(on_mains=boom, clock=lambda: NOW,
                      sources={k: boom for k in ("status", "load", "disk", "uptime", "reboot")})
        self.assertTrue(all(s[k] is None for k in H.FIELDS))

    def test_collect_uses_sources(self):
        s = H.collect(on_mains=lambda: True, clock=lambda: NOW, sources={
            "status": lambda: STATUS, "load": lambda: LOAD, "disk": lambda: 50.0,
            "uptime": lambda: 60, "reboot": lambda: True})
        self.assertEqual(s["cpu_c"], 71.0)
        self.assertIs(s["reboot_pending"], True)
        self.assertIs(s["on_mains"], True)


class _Agent(BaseHTTPRequestHandler):
    body = STATUS
    seen_token = None

    def log_message(self, *a):
        pass

    def do_GET(self):
        type(self).seen_token = self.headers.get("X-Token")
        data = json.dumps(self.body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(data)


class LaptopctlApiTest(unittest.TestCase):
    """laptopctl's local API, mocked by a tiny HTTP server on a free port."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        tok = Path(self.tmp.name) / "laptopctl" / "token"
        tok.parent.mkdir()
        tok.write_text("fake-token\n")
        self.env = mock.patch.dict("os.environ", {"LOCALAPPDATA": self.tmp.name})
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()

    def test_reads_status_with_token(self):
        srv = ThreadingHTTPServer(("127.0.0.1", 0), _Agent)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            got = H.laptopctl_status(port=srv.server_address[1], timeout=5)
        finally:
            srv.shutdown()
        self.assertEqual(got["temps_c"]["cpu"], 71)
        self.assertEqual(_Agent.seen_token, "fake-token")

    def test_refused_port_is_false(self):
        srv = ThreadingHTTPServer(("127.0.0.1", 0), _Agent)
        port = srv.server_address[1]
        srv.server_close()
        self.assertIs(H.laptopctl_status(port=port, timeout=2), False)

    def test_no_token_is_none(self):
        (Path(self.tmp.name) / "laptopctl" / "token").unlink()
        self.assertIsNone(H.laptopctl_status(port=1, timeout=1))

    def test_load_without_laptopctl_venv_is_none(self):
        self.assertIsNone(H.laptopctl_load(root=Path(self.tmp.name)))


class SamplerTest(unittest.TestCase):
    def test_take_hands_each_snapshot_once(self):
        n = iter(range(100))
        s = H.HealthSampler(lambda: {"i": next(n)}, every=999)
        self.assertIsNone(s.take())
        s.sample_once()
        self.assertEqual(s.take(), {"i": 0})
        self.assertIsNone(s.take())

    def test_put_back_keeps_the_newer_one(self):
        s = H.HealthSampler(lambda: {"i": 1}, every=999)
        s.put_back({"i": 0})
        self.assertEqual(s.take(), {"i": 0})
        s.sample_once()
        s.put_back({"i": 0})
        self.assertEqual(s.take(), {"i": 1})

    def test_a_failing_collect_keeps_running(self):
        def boom():
            raise RuntimeError("x")
        s = H.HealthSampler(boom, every=999)
        s.sample_once()
        self.assertIsNone(s.take())


class WorkerHeartbeatTest(unittest.TestCase):
    """The worker sends `health` only when a fresh snapshot exists, and keeps it on a failed beat."""

    def _worker(self, engine, sampler):
        import worker as W
        from common import Paths
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        paths = Paths(Path(tmp.name))
        cfg = {"name": "Testbox", "scope": "full", "engine_url": "http://x", "token": "t",
               "keep_awake": False}

        class NoPower:
            def update(self):
                pass

            def release(self):
                pass

        return W.Worker(paths, cfg, engine=engine, power=NoPower(), clock=lambda: NOW,
                        idle=lambda: 0, health=sampler)

    def test_snapshot_rides_one_heartbeat(self):
        sent = []

        class Eng:
            def heartbeat(self, body):
                sent.append(body)
                return {}

            def claim(self, name):
                return []

        sampler = H.HealthSampler(lambda: {"cpu_c": 50.0}, every=999)
        w = self._worker(Eng(), sampler)
        w.flush_outbox = lambda: None
        sampler.sample_once()
        w.contact_engine(NOW)
        w.next_contact = 0
        w.contact_engine(NOW)
        self.assertEqual(sent[0]["health"], {"cpu_c": 50.0})
        self.assertNotIn("health", sent[1])

    def test_failed_heartbeat_keeps_snapshot(self):
        from engine_client import EngineDown
        calls = []

        class Eng:
            def heartbeat(self, body):
                calls.append(body)
                if len(calls) == 1:
                    raise EngineDown("down")
                return {}

            def claim(self, name):
                return []

        sampler = H.HealthSampler(lambda: {"cpu_c": 51.0}, every=999)
        w = self._worker(Eng(), sampler)
        w.flush_outbox = lambda: None
        sampler.sample_once()
        w.contact_engine(NOW)
        w.next_contact = 0
        w.contact_engine(NOW)
        self.assertEqual(calls[1]["health"], {"cpu_c": 51.0})

    def test_listed_machine_has_no_sampler_by_default(self):
        import worker as W
        from common import Paths
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        w = W.Worker(Paths(Path(tmp.name)), {"name": "Work", "scope": "listed", "engine_url": "http://x",
                                            "token": "t", "repos_root": tmp.name, "keep_awake": False},
                     engine=object(), power=object(), idle=lambda: 0)
        self.assertIsNone(w.health)


if __name__ == "__main__":
    unittest.main()
