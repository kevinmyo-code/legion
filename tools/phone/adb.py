"""A thin adb runner, and the read-only facts the rails and the preflight need.

Every phone fact is read fresh from `adb`, never from a document: the phone model, the installed
build, what is in front. `.claude/agents/device.md`: a stale device name in a prompt once had three
agents reporting work on a phone that was not attached.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

PACKAGE = "com.kevin.legion"


class AdbError(RuntimeError):
    pass


def find_adb() -> str:
    """`$ADB`, then the SDK under %LOCALAPPDATA% (adb is not on the Bash PATH on Kevin's laptop,
    memory/MEMORY.md), then `adb` on PATH."""
    env = os.environ.get("ADB")
    if env:
        return env
    local = os.environ.get("LOCALAPPDATA")
    if local:
        p = Path(local) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        if p.exists():
            return str(p)
    found = shutil.which("adb")
    if found:
        return found
    raise AdbError("adb not found: set $ADB, or install platform-tools under %LOCALAPPDATA%\\Android\\Sdk")


class Adb:
    """Runs adb. Tests replace [run] with a fake; nothing else here touches a process."""

    def __init__(self, serial: str | None = None, exe: str | None = None):
        self.serial = serial or os.environ.get("ANDROID_SERIAL")
        self._exe = exe

    @property
    def exe(self) -> str:
        if self._exe is None:
            self._exe = find_adb()
        return self._exe

    def run(self, args: list[str], timeout: float = 30, binary: bool = False, device: bool = True):
        """(returncode, stdout) with stderr folded in for text. Git Bash path mangling is not an
        issue here because no shell sits between this and adb."""
        cmd = [self.exe]
        if device and self.serial:
            cmd += ["-s", self.serial]
        cmd += args
        try:
            p = subprocess.run(cmd, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as e:
            raise AdbError(f"adb timed out after {timeout:.0f}s: {' '.join(args)[:120]}") from e
        if binary:
            return p.returncode, p.stdout
        out = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
        return p.returncode, out

    def shell(self, cmd: str, timeout: float = 30) -> str:
        rc, out = self.run(["shell", cmd], timeout=timeout)
        if rc != 0 and ("no devices" in out or "not found" in out or "offline" in out):
            raise AdbError(out.strip())
        return out


# ---------------------------------------------------------------- parsers (pure, fixture-tested)

@dataclass
class Device:
    serial: str
    state: str
    model: str


def parse_devices(out: str) -> list[Device]:
    devs = []
    for line in out.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        model = ""
        for p in parts[2:]:
            if p.startswith("model:"):
                model = p.split(":", 1)[1]
        devs.append(Device(parts[0], parts[1], model))
    return devs


@dataclass
class Version:
    version_code: int | None
    version_name: str | None
    debuggable: bool
    last_update: str | None

    @property
    def build(self) -> str:
        """`debug` or `fast` (CLAUDE.md section 6): `fast` is release-signed with the debug key and
        not debuggable, so no DEBUGGABLE flag. A release build would read `fast` here too."""
        return "debug" if self.debuggable else "fast"


def parse_version(dumpsys_package: str) -> Version | None:
    if "versionCode=" not in dumpsys_package:
        return None
    code = re.search(r"versionCode=(\d+)", dumpsys_package)
    name = re.search(r"versionName=(\S+)", dumpsys_package)
    flags = re.search(r"^\s*flags=\[([^\]]*)\]", dumpsys_package, re.M)
    upd = re.search(r"lastUpdateTime=([\d\- :]+)", dumpsys_package)
    return Version(
        int(code.group(1)) if code else None,
        name.group(1) if name else None,
        bool(flags and "DEBUGGABLE" in flags.group(1)),
        upd.group(1).strip() if upd else None,
    )


def parse_focus(dumpsys_activities: str) -> str:
    """`package/.Activity` of the resumed activity, from `dumpsys activity activities`."""
    for pat in (r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)",
                r"ResumedActivity: ActivityRecord\{\S+ \S+ (\S+)",
                r"mResumedActivity: ActivityRecord\{\S+ \S+ (\S+)"):
        m = re.search(pat, dumpsys_activities)
        if m:
            return m.group(1)
    return ""


def parse_battery(dumpsys_battery: str) -> dict:
    def grab(key):
        m = re.search(rf"^\s*{re.escape(key)}:\s*(\S+)", dumpsys_battery, re.M)
        return m.group(1) if m else None
    level = grab("level")
    powered = any(grab(k) == "true" for k in ("AC powered", "USB powered", "Wireless powered"))
    return {"level": int(level) if level and level.isdigit() else None, "charging": powered}


def parse_screen(dumpsys_window: str, dumpsys_power: str) -> dict:
    awake = re.search(r"mWakefulness=(\w+)", dumpsys_power)
    kg = re.search(r"isKeyguardShowing=(\w+)", dumpsys_window) or re.search(
        r"mShowingLockscreen=(\w+)", dumpsys_window)
    return {
        "awake": bool(awake and awake.group(1) == "Awake"),
        "locked": bool(kg and kg.group(1) == "true"),
    }


def parse_screencap_raw(data: bytes) -> tuple[int, int, bytes]:
    """(width, height, RGBA bytes) from `screencap` without `-p`. The header is 12 bytes on older
    Android and 16 (a colour-space word added) on newer; the pixel count tells which."""
    if len(data) < 16:
        raise AdbError("screencap returned no image")
    w = int.from_bytes(data[0:4], "little")
    h = int.from_bytes(data[4:8], "little")
    px = w * h * 4
    header = len(data) - px
    if header not in (12, 16) or w <= 0 or h <= 0:
        raise AdbError(f"screencap raw header not understood (w={w} h={h} extra={header})")
    return w, h, data[header:]


def blank_fraction(w: int, h: int, rgba: bytes, step: int = 7) -> float:
    """Share of sampled pixels that are near-black. FLAG_SECURE makes screencap draw the secure
    window black; the status and nav bars may still draw, so 'blank' is a share, not all-or-none."""
    total = dark = 0
    n = w * h
    for i in range(0, n, step):
        o = i * 4
        r, g, b = rgba[o], rgba[o + 1], rgba[o + 2]
        total += 1
        if r < 12 and g < 12 and b < 12:
            dark += 1
    return dark / total if total else 0.0


THREADTIME_RE = re.compile(
    r"^(?P<date>\d\d-\d\d) (?P<time>\d\d:\d\d:\d\d\.\d+)\s+(?P<pid>\d+)\s+(?P<tid>\d+)\s+"
    r"(?P<level>[VDIWEFA])\s+(?P<tag>.*?)\s*:\s(?P<msg>.*)$"
)
LEGION_TAGS = ("LIVE_AUDIT", "LIVE_LATENCY", "LIVE_FOLLOWUP", "LIVE_TOOL_CANCEL")


def filter_logcat(text: str, pids: set[str], tags: tuple[str, ...] = LEGION_TAGS,
                  grep: str | None = None) -> list[str]:
    """Lines from LEGION's process, LEGION's audit tags from anywhere, and LEGION's crashes.

    [pids] is LEGION's pids now. A process that crashed and restarted logged under an OLD pid, so a
    pid also joins the set the moment an AndroidRuntime line says `Process: com.kevin.legion`, and
    the rest of that crash (logged under the same pid) is kept."""
    out = []
    pids = set(pids)
    rx = re.compile(grep, re.I) if grep else None
    parsed = [(line, THREADTIME_RE.match(line)) for line in text.splitlines()]
    for _, m in parsed:  # first pass: a crash's pid, so its FATAL header line (before Process:) is kept
        if m and m.group("tag") == "AndroidRuntime" and f"Process: {PACKAGE}" in m.group("msg"):
            pids.add(m.group("pid"))
    for line, m in parsed:
        if not m:
            continue
        keep = m.group("pid") in pids or m.group("tag") in tags
        if keep and (rx is None or rx.search(line)):
            out.append(line)
    return out


def input_text_arg(s: str) -> str:
    """Escape for `adb shell input text`: spaces become %s, shell metacharacters are backslashed.
    One shell parses this, on the phone."""
    out = []
    for ch in s:
        if ch == " ":
            out.append("%s")
        elif ch in "\\'\"`$&|;<>()[]{}*?!~#%":
            out.append("\\" + ch)
        else:
            out.append(ch)
    return "".join(out)
