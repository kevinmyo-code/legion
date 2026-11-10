"""Laptop health snapshot for the heartbeat (laptop-health 2026-11-10).

Kevin leaves the Alienware on for days and watches it from the phone. About once a minute the
worker sends a compact snapshot with its heartbeat; the engine keeps the latest, a 10-minute
history and the alert rules (`server/claude_session/health.py`).

Where each reading comes from (no new WMI code):

- temps, fan RPMs, "is laptopctl-agent running": laptopctl's own local API,
  `GET http://127.0.0.1:47613/status` with the token in `%LOCALAPPDATA%\\laptopctl\\token`.
  Refused or timed out = agent not running. No token file = laptopctl not installed (null).
- CPU load, RAM used %: laptopctl's own `power.cpu_load()` / `power.memory()`, run in laptopctl's
  venv (they need pywin32/psutil, which the worker's Python may not have). Takes about 1 s.
- C: free, uptime, last boot, pending reboot: the standard library (`shutil`, `GetTickCount64`,
  `winreg`).

Every source is wrapped: a failing reading is null, never an error, and never stops a heartbeat.
Sampling runs on a daemon thread ([HealthSampler]) so the 1 s CPU sample never blocks the loop.
"""
from __future__ import annotations

import ctypes
import datetime
import json
import logging
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

log = logging.getLogger("legion.claude")

LAPTOPCTL_PORT = 47613
LAPTOPCTL_ROOT = Path(os.environ.get("USERPROFILE") or Path.home()) / "projects" / "laptopctl"
NO_WINDOW = 0x08000000
SAMPLE_EVERY_S = 60.0

# What the engine accepts (`server/claude_session/health.py` FIELDS); keep the two in step.
FIELDS = ("cpu_c", "gpu_c", "fans_rpm", "cpu_load_pct", "ram_used_pct", "disk_free_gb",
          "uptime_s", "boot_at", "laptopctl_agent", "on_mains", "reboot_pending")

LOAD_SNIPPET = ("import json; from laptopctl import power; "
                "print(json.dumps({'cpu': power.cpu_load(), 'memory': power.memory()}))")


def _num(v, digits=1):
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return round(f, digits) if f == f else None  # NaN -> None


# ---------------------------------------------------------------- sources

def laptopctl_token_file() -> Path:
    base = os.environ.get("LOCALAPPDATA") or str(Path.home())
    return Path(base) / "laptopctl" / "token"


def laptopctl_status(port: int = LAPTOPCTL_PORT, timeout: float = 10.0):
    """laptopctl agent's `/status` dict, `False` when the agent does not answer, `None` when
    laptopctl is not installed here (no token file)."""
    tf = laptopctl_token_file()
    try:
        token = tf.read_text(encoding="utf-8").strip()
    except OSError:
        return None
    req = urllib.request.Request(f"http://127.0.0.1:{port}/status", headers={"X-Token": token})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError:
        return True  # it answered (e.g. "no sensor reading yet"): running, readings unknown
    except (urllib.error.URLError, OSError, ValueError):
        return False


def laptopctl_load(root: Path = LAPTOPCTL_ROOT, timeout: float = 20.0) -> dict | None:
    """`{'cpu': {...}, 'memory': {...}}` from laptopctl's power module, or None."""
    py = root / ".venv" / "Scripts" / "python.exe"
    if not py.exists():
        return None
    try:
        out = subprocess.run([str(py), "-c", LOAD_SNIPPET], cwd=str(root), capture_output=True,
                             text=True, timeout=timeout, creationflags=NO_WINDOW if sys.platform == "win32" else 0)
        return json.loads(out.stdout.strip().splitlines()[-1]) if out.returncode == 0 else None
    except (OSError, subprocess.SubprocessError, ValueError, IndexError):
        return None


def disk_free_gb(root: str = "C:\\") -> float | None:
    try:
        return round(shutil.disk_usage(root).free / 2**30, 1)
    except OSError:
        return None


def uptime_s() -> int | None:
    if sys.platform != "win32":
        return None
    try:
        f = ctypes.windll.kernel32.GetTickCount64
        f.restype = ctypes.c_ulonglong
        return int(f() // 1000)
    except (AttributeError, OSError):
        return None


REBOOT_KEYS = (
    r"SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Auto Update\RebootRequired",
    r"SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending",
)


def reboot_pending() -> bool | None:
    """Windows Update or servicing says a restart is owed (two registry keys; cheap)."""
    if sys.platform != "win32":
        return None
    try:
        import winreg
    except ImportError:
        return None
    try:
        for path in REBOOT_KEYS:
            try:
                winreg.CloseKey(winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path))
                return True
            except FileNotFoundError:
                continue
        return False
    except OSError:
        return None


# ---------------------------------------------------------------- the builder

def build_snapshot(*, status, load, disk, uptime, on_mains, reboot, now: float) -> dict:
    """Pure: the readings to the heartbeat's `health` dict. Any missing reading is None.

    [status] is laptopctl's `/status` (dict), True (answered without readings), False (not
    running) or None (not installed)."""
    st = status if isinstance(status, dict) else {}
    temps = st.get("temps_c") if isinstance(st.get("temps_c"), dict) else {}
    fans = st.get("fans") if isinstance(st.get("fans"), dict) else {}
    rpm = {}
    for name, f in fans.items():
        v = _num(f.get("rpm") if isinstance(f, dict) else None, 0)
        if v is not None:
            rpm[str(name)[:16]] = int(v)
    ld = load if isinstance(load, dict) else {}
    cpu = ld.get("cpu") if isinstance(ld.get("cpu"), dict) else {}
    mem = ld.get("memory") if isinstance(ld.get("memory"), dict) else {}
    boot = None
    if uptime is not None:
        boot = datetime.datetime.fromtimestamp(now - uptime, datetime.timezone.utc).isoformat(timespec="seconds")
    return {
        "cpu_c": _num(temps.get("cpu")),
        "gpu_c": _num(temps.get("gpu")),
        "fans_rpm": rpm or None,
        "cpu_load_pct": _num(cpu.get("load_pct")),
        "ram_used_pct": _num(mem.get("ram_used_pct")),
        "disk_free_gb": _num(disk),
        "uptime_s": int(uptime) if uptime is not None else None,
        "boot_at": boot,
        "laptopctl_agent": None if status is None else status is not False,
        "on_mains": on_mains,
        "reboot_pending": reboot,
    }


def _safe(fn, *a):
    try:
        return fn(*a)
    except Exception:  # noqa: BLE001 - a reading must never stop a heartbeat
        return None


def collect(on_mains=lambda: None, clock=time.time, sources=None) -> dict:
    """One snapshot from the live sources (or [sources], a dict of callables, in tests)."""
    src = {"status": laptopctl_status, "load": laptopctl_load, "disk": disk_free_gb,
           "uptime": uptime_s, "reboot": reboot_pending, **(sources or {})}
    return build_snapshot(
        status=_safe(src["status"]), load=_safe(src["load"]), disk=_safe(src["disk"]),
        uptime=_safe(src["uptime"]), on_mains=_safe(on_mains), reboot=_safe(src["reboot"]),
        now=clock())


class HealthSampler:
    """Samples on a daemon thread every [every] seconds. `take()` hands the newest snapshot once
    (so the heartbeat carries one about once a minute) and None in between."""

    def __init__(self, collect_fn, every: float = SAMPLE_EVERY_S):
        self.collect_fn = collect_fn
        self.every = every
        self.lock = threading.Lock()
        self.fresh: dict | None = None
        self.stop = threading.Event()
        self.thread: threading.Thread | None = None

    def sample_once(self) -> None:
        try:
            snap = self.collect_fn()
        except Exception:  # noqa: BLE001
            log.exception("health sample failed")
            return
        with self.lock:
            self.fresh = snap

    def start(self) -> None:
        if self.thread is not None:
            return

        def loop():
            while not self.stop.is_set():
                self.sample_once()
                self.stop.wait(self.every)

        self.thread = threading.Thread(target=loop, name="health", daemon=True)
        self.thread.start()

    def take(self) -> dict | None:
        with self.lock:
            snap, self.fresh = self.fresh, None
            return snap

    def put_back(self, snap: dict | None) -> None:
        """A heartbeat that failed keeps its snapshot for the next try, unless a newer one came."""
        if snap is None:
            return
        with self.lock:
            if self.fresh is None:
                self.fresh = snap
