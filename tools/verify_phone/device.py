"""The real phone: screen reads and taps through phonectl (and its guard), assistant turns through
the app's test-turn door, and read-only adb for the build, the phone's clock and logcat.

phonectl lives outside this repo (Kevin's driver, `PHONECTL_HOME`, default ~/projects/phonectl); it is
imported only here and only when a real run starts, so CI and the dry run never need it. Its guard
applies to every tap: a refusal that needs Kevin's approval becomes a BLOCKED check, never a retry
with approval set. A locked or dark phone stops the run (`Abort`); nothing here wakes or unlocks it.
"""
from __future__ import annotations

import datetime as dt
import os
import re
import shlex
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from . import logs
from .runner import Abort, Blocked

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import phone_voice_test as door  # noqa: E402  (the door's bundle parser and turn-finished rule)
from phone.adb import PACKAGE, parse_version  # noqa: E402

_SNAP_LINE = re.compile(r'^(e\d+) (\S+) "(.*)" \[([^\]]*)\] @(-?\d+),(-?\d+),(-?\d+),(-?\d+)$')


@dataclass
class El:
    label: str
    bounds: tuple[int, int, int, int]
    clickable: bool = False

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


def parse_snapshot(lines: list[str]) -> list[El]:
    out = []
    for line in lines:
        m = _SNAP_LINE.match(line)
        if m:
            out.append(El(m.group(3), tuple(int(m.group(i)) for i in range(5, 9)), "click" in m.group(4)))
    return out


def _load_phonectl():
    home = os.environ.get("PHONECTL_HOME") or str(Path.home() / "projects" / "phonectl")
    if home not in sys.path:
        sys.path.insert(0, home)
    try:
        from phonectl.device import Phone  # type: ignore
    except ImportError as e:
        raise Abort(f"phonectl not importable from {home} (set PHONECTL_HOME): {e}") from e
    return Phone


class RealDevice:
    def __init__(self, phone=None, sleep=time.sleep):
        self.phone = phone or _load_phonectl()()
        self.adb = self.phone.adb
        self.sleep = sleep

    # ---- facts ---------------------------------------------------------------------------------
    def describe(self) -> dict:
        return {"fake": False, "driver": "phonectl", "serial_set": bool(self.adb.serial)}

    def _sh(self, cmd: str, timeout: float = 20) -> str:
        """Read-only shell reads only (dumpsys, date, content call to the test door, logcat)."""
        try:
            return self.adb.run(["shell", cmd], timeout=timeout).decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001 - phonectl's AdbError: the phone went away
            raise Abort(f"adb: {str(e)[:160]}") from e

    def mark(self) -> str:
        """The phone's clock (the laptop's drifts after a power-off), as logcat -T takes it."""
        self._usable()
        return self._sh("date +'%m-%d %H:%M:%S.000'").strip().strip("'")

    def now(self) -> dt.datetime:
        """The phone's local time: "tomorrow" and the time-of-day guards follow the phone, not the laptop."""
        return dt.datetime.strptime(self._sh("date +%Y-%m-%dT%H:%M:%S").strip(), "%Y-%m-%dT%H:%M:%S")

    def version(self) -> tuple[int | None, str | None]:
        v = parse_version(self._sh(f"dumpsys package {PACKAGE}", timeout=30))
        return (v.version_code, v.version_name) if v else (None, None)

    def logcat(self, since: str) -> list[logs.Line]:
        try:
            raw = self.adb.run(["logcat", "-d", "-v", "threadtime", "-T", since], timeout=60)
        except Exception as e:  # noqa: BLE001
            raise Abort(f"logcat: {str(e)[:160]}") from e
        return logs.parse(raw.decode("utf-8", "replace"))

    def foreground(self) -> str:
        return str(self.phone.screen().get("package") or "")

    def _usable(self) -> None:
        s = self.phone.screen()
        if s.get("locked") is not False or s.get("awake") is not True:
            raise Abort("phone is locked or its screen is off: Kevin unlocks it (never done here)")

    # ---- the screen, through phonectl ----------------------------------------------------------
    def screen(self) -> list[El]:
        r = self.phone.snapshot()
        if not r.get("ok"):
            why = r.get("refused") or r.get("error") or "snapshot failed"
            if "locked" in why or "screen is off" in why:
                raise Abort(why)
            raise Blocked(f"snapshot: {why[:160]}")
        return parse_snapshot(r["elements"])

    def wait_text(self, text: str, timeout: float, gone: bool = False) -> bool:
        r = self.phone.wait(stable_ms=700, timeout_s=timeout, text=text, gone=gone)
        if not r.get("ok") and r.get("refused"):
            raise Abort(r["refused"])
        return bool(r.get("ok"))

    def _acted(self, r: dict, what: str) -> None:
        if r.get("ok"):
            return
        why = r.get("refused") or r.get("error") or "failed"
        if r.get("needs_approval"):
            raise Blocked(f"phonectl guard asks Kevin's approval for {what}; not set by this tool")
        if "locked" in why or "screen is off" in why:
            raise Abort(why)
        raise Blocked(f"{what}: {why[:160]}")

    def tap_text(self, text: str) -> None:
        self._acted(self.phone.tap(text), f"tap '{text}'")

    def tap_xy(self, x: int, y: int, what: str) -> None:
        self._acted(self.phone.tap(x=x, y=y), f"tap {what}")

    def key(self, name: str) -> None:
        self._acted(self.phone.key(name), f"key {name}")

    def launch(self) -> None:
        self._acted(self.phone.launch(), "launch LEGION")

    # ---- the assistant, through the test-turn door ---------------------------------------------
    def _door(self, method: str, arg: str | None = None, **extras) -> dict:
        cmd = f"content call --uri {door.AUTHORITY} --method {method}"
        if arg is not None:
            cmd += f" --arg {shlex.quote(arg)}"
        for k, v in extras.items():
            kind, val = ("b", str(v).lower()) if isinstance(v, bool) else ("s", str(v))
            cmd += f" --extra {k}:{kind}:{shlex.quote(val)}"
        return door.parse_bundle(self._sh(cmd))

    def door_ready(self) -> bool | str:
        st = self._door("status")
        if not st.get("ok"):
            return str(st.get("error"))
        if not st.get("enabled"):
            return "switch off (Kevin: Settings, Assistant, Testing: Allow test turns from a computer)"
        if not st.get("service"):
            return "assistant service not running (open LEGION once)"
        if st.get("conversation") == "real":
            return "a real conversation is open"
        return True

    def conversation(self) -> str:
        return str(self._door("status").get("conversation", "none"))

    def turn(self, text: str, case_id: str, writes: bool = False, timeout: float = 60) -> str:
        """His words as a test-conversation turn; waits until it is answered. Returns why it ended
        (`answered`, `quiet`, `closed`, `timeout`, ...). Raises Blocked if the phone refuses it."""
        before = self._door("status")
        if before.get("conversation") == "real":
            raise Blocked("a real conversation is open; not testing over it")
        r = self._door("turn", text, id=case_id, writes=writes)
        if not r.get("ok"):
            raise Blocked(f"the phone refused the turn: {r.get('error')}")
        sent, heard_at, saw_busy = time.monotonic(), None, False
        while time.monotonic() - sent < timeout:
            self.sleep(0.6)
            now = self._door("status")
            if heard_at is None and door.heard(before, now):
                heard_at = time.monotonic()
            if heard_at is not None:
                saw_busy |= bool(now.get("busy") or now.get("speaking"))
            why = door.turn_finished(before, now, heard_at, time.monotonic(), saw_busy)
            if why:
                return why
        return "timeout"

    def end_conversation(self) -> None:
        self._door("end")
        t0 = time.monotonic()
        while time.monotonic() - t0 < 15 and self.conversation() not in ("none", "None"):
            self.sleep(0.5)
