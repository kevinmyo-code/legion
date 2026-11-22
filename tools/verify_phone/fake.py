"""A fake phone for `run --dry-run` and the tests: the same calls as `device.RealDevice`, answered by
a small model of the app (a grocery list, a calendar, cards, a timer ring, the mic arbiter) that
writes real threadtime logcat lines, so the checks' parsing runs as it would on the phone.

Each `bugs` entry turns one behaviour off, the way a regression would: `card_static`,
`no_carry`, `resurrect`, `no_close`, `mic_stuck`, `crash`, `guard_remove`, `cleanup_fails`, and
`forbidden_tool` (the model also calls `email` in every write turn), `delete_reads` (a delete
turn is answered with a read). `preventive=False` is a build
whose door ignores `allow_tools`, so that email runs.
Every name and item here is made up."""
from __future__ import annotations

import datetime as dt
import json
import re

from . import logs
from .device import El
from .runner import Abort, Blocked

PKG = "com.kevin.legion"


class FakeDevice:
    def __init__(self, version_code: int = 263261832, locked: bool = False, door: bool | str = True,
                 bugs: set[str] | None = None, now: dt.datetime | None = None, preventive: bool = True):
        self.version_code = version_code
        self.locked = locked
        self.door = door
        self.bugs = set(bugs or ())
        self.clock = now or dt.datetime(2026, 11, 22, 12, 0, 0)
        self.lines: list[str] = []
        self.groceries = ["eggs", "Private Brand Oats"]  # stand-ins for his own data: never logged
        self.events: dict[dt.date, list[str]] = {self.clock.date(): ["Private Dinner"]}
        self.cards: list[dict] = []
        self.where = "home"
        self.conv = "none"
        self.ringing = False
        self.timer_due: int | None = None
        self.about_day: dt.date | None = None
        self.pending_sync = False
        self.deleted_here: set[str] = set()
        self.taps: list[str] = []
        self.preventive = preventive  # this build's door honours allow_tools
        self.sent: list[tuple[str, tuple[str, ...]]] = []  # (words, tools) of every turn

    # ---- plumbing --------------------------------------------------------------------------------
    def describe(self) -> dict:
        return {"fake": True, "bugs": sorted(self.bugs)}

    def sleep(self, s: float) -> None:
        self.clock += dt.timedelta(seconds=s)
        self._tick()

    def _log(self, tag: str, msg: str, level: str = "I", pid: int = 4242) -> None:
        self.clock += dt.timedelta(milliseconds=10)
        t = self.clock.strftime("%m-%d %H:%M:%S.") + f"{self.clock.microsecond // 1000:03d}"
        self.lines.append(f"{t}  {pid}  {pid} {level} {tag}: {msg}")

    def _audit(self, msg: str) -> None:
        self._log("LIVE_AUDIT", msg)

    def _tool(self, name: str, args: dict, success: bool = True, extra: dict | None = None) -> None:
        tid = f"t{len(self.lines)}"
        self._audit(f"tool_call id={tid} name={name} async=false args={json.dumps(args)}")
        self._audit(f"tool_result id={tid} name={name} success={str(success).lower()} ms=5 scheduling=none "
                    f"sent=true response={json.dumps({'success': success, **(extra or {})})}")

    def _tick(self, sync: bool = False) -> None:
        if self.timer_due is not None and self.clock.timestamp() >= self.timer_due:
            self.timer_due, self.ringing = None, True
            self._log("AlfredRing", "ring_start kind=timer")
        if sync and self.pending_sync:
            self.pending_sync = False
            for _ in range(2):
                self._log("MidnightEvents", "engine_poll[events] inserted=0 updated=0 tombstoned=0", "D")
            if "resurrect" in self.bugs:
                for day, names in list(self.deleted_here_days()):
                    self.events.setdefault(day, []).extend(names)

    def deleted_here_days(self):
        return [(self.clock.date(), sorted(self.deleted_here))] if self.deleted_here else []

    def _usable(self) -> None:
        if self.locked:
            raise Abort("phone is locked or its screen is off: Kevin unlocks it (never done here)")

    def mark(self) -> str:
        self._usable()
        self.clock += dt.timedelta(milliseconds=1)
        return self.clock.strftime("%m-%d %H:%M:%S.") + f"{self.clock.microsecond // 1000:03d}"

    def now(self) -> dt.datetime:
        return self.clock

    def version(self):
        return self.version_code, "2026-11-22-fake"

    def logcat(self, since: str) -> list[logs.Line]:
        self._tick(sync=True)
        return logs.parse("\n".join(ln for ln in self.lines if ln[:18] >= since))

    def foreground(self) -> str:
        return PKG

    # ---- screen ----------------------------------------------------------------------------------
    def screen(self) -> list[El]:
        self._usable()
        self._tick()
        els, y = [], 100

        def add(label, click=False):
            nonlocal y
            els.append(El(label, (40, y, 1040, y + 60), click))
            y += 70

        if self.where == "home":
            add("More", True)
            add("Calendar", True)
            for c in self.cards[-1:]:
                add(c["title"])
                add("Close card", True)
                for r in c["rows"]:
                    add(r)
        else:
            add("Calendar")
            for name in self.events.get(self.clock.date(), []):
                els.append(El(name, (40, y, 900, y + 60)))
                els.append(El("Remove", (940, y, 1040, y + 60), True))
                y += 70
        if self.ringing:
            add("Stop alarm", True)
        return els

    def wait_text(self, text: str, timeout: float, gone: bool = False) -> bool:
        present = any(text.lower() in e.label.lower() for e in self.screen())
        return present != gone

    def tap_text(self, text: str) -> None:
        self._usable()
        self.taps.append(text)
        if text == "Calendar":
            self.where = "calendar"

    def tap_xy(self, x: int, y: int, what: str) -> None:
        self._usable()
        els = self.screen()
        hit = next((e for e in els if e.bounds[0] <= x <= e.bounds[2] and e.bounds[1] <= y <= e.bounds[3]), None)
        if hit is None:
            raise Blocked(f"tap {what}: nothing there")
        self.taps.append(hit.label)
        if hit.label == "Remove":
            if "guard_remove" in self.bugs:
                raise Blocked(f"phonectl guard asks Kevin's approval for tap {what}; not set by this tool")
            row = next(e for e in els if e.bounds[1] == hit.bounds[1] and e is not hit)
            self.events[self.clock.date()].remove(row.label)
            self.deleted_here.add(row.label)
            self.pending_sync = True
        elif hit.label == "Stop alarm":
            self.ringing = False
            self._log("AlfredRing", "ring_stop kind=timer reason=button")
            if "mic_stuck" in self.bugs:  # the wake word's watchdog keeps asking and keeps being refused
                self._log("MicArbiter", "request: WAKE_WORD refused - held by RING_LISTENING", "D")

    def key(self, name: str) -> None:
        self._usable()
        if name == "back":
            self.where = "home"

    def launch(self) -> None:
        self._usable()
        if "crash" in self.bugs:
            self._log("AndroidRuntime", "FATAL EXCEPTION: main", "E", pid=999)
            self._log("AndroidRuntime", f"Process: {PKG}, PID: 999", "E", pid=999)
            self._log("AndroidRuntime", "java.lang.IllegalStateException: boom", "E", pid=999)
            self.bugs.discard("crash")

    # ---- the door --------------------------------------------------------------------------------
    def door_ready(self):
        return self.door

    def conversation(self) -> str:
        return self.conv

    def end_conversation(self) -> None:
        if self.conv != "none":
            self.conv = "none"
            self._audit('session_end reason="test_end"')
            self._log("MicArbiter", "request: WAKE_WORD refused - held by RING_LISTENING" if "mic_stuck" in self.bugs
                      else "request: WAKE_WORD granted", "D")

    def turn(self, text: str, case_id: str, tools: tuple[str, ...] = (), timeout: float = 60) -> str:
        self._usable()
        self.sent.append((text, tools))
        if self.conv == "none":
            self.conv = "test"
            self._audit("session_start model=fake async_set=none")
        self._audit(f'user final=true text="{text}"')
        t = text.lower()
        tomorrow = self.clock.date() + dt.timedelta(days=1)
        fail = "cleanup_fails" in self.bugs and any(w in t for w in ("remove", "delete"))

        def allowed(name):
            return name in tools if self.preventive else bool(tools)

        def write(name, args, extra=None):
            if not allowed(name):
                self._tool(name, args, False, {"test_mode": True})
                return False
            ok = not fail
            self._tool(name, args, ok, extra)
            return ok

        if "forbidden_tool" in self.bugs and tools:  # the model reaches for mail in a write turn
            if allowed("email"):
                self._tool("email", {"to": "someone"}, True)
            else:
                self._tool("email", {"to": "someone"}, False, {"test_mode": True})

        if t == "what's on my grocery list?":
            self._tool("manage_checklist", {"action": "read", "list": "groceries"})
            self.cards.append({"title": "Groceries", "rows": list(self.groceries)})
        elif m := re.match(r"add (.+) to my grocery list", t):
            if write("manage_checklist", {"action": "add", "item": m.group(1)}):
                self.groceries.append(m.group(1))
                self._refresh_cards()
        elif m := re.match(r"remove (.+) from my grocery list", t):
            if write("manage_checklist", {"action": "remove", "item": m.group(1)}) and m.group(1) in self.groceries:
                self.groceries.remove(m.group(1))
                self._refresh_cards()
        elif "budget" in t:
            self._tool("get_budget", {})
            self.cards.append({"title": "Budget pace", "rows": ["Food: On track"]})
        elif t == "what's on my calendar tomorrow?":
            self._tool("read_calendar", {"date": tomorrow.isoformat()})
            self.about_day = tomorrow
        elif m := re.match(r"add (.+) at 9 pm", t):
            day = self.about_day if self.about_day and "no_carry" not in self.bugs else self.clock.date()
            if write("manage_item", {"action": "add", "item": m.group(1), "time": "21:00"}, {"date": day.isoformat()}):
                self.events.setdefault(day, []).append(m.group(1))
        elif m := re.match(r"add (.+) today at 11:30 pm", t):
            if write("manage_item", {"action": "add", "item": m.group(1), "time": "23:30"},
                     {"date": self.clock.date().isoformat()}):
                self.events.setdefault(self.clock.date(), []).append(m.group(1))
        elif "delete_reads" in self.bugs and re.match(r"delete the (.+) event on .+", t):
            self._tool("manage_item", {"action": "read_list"})  # answered with a read, nothing deleted
        elif m := re.match(r"delete the (.+) event on .+", t):
            name = m.group(1)
            if write("manage_item", {"action": "remove", "item": name}):
                for names in self.events.values():
                    if name in names:
                        names.remove(name)
        elif t == "thanks, that's all":
            if "no_close" not in self.bugs:
                self.conv = "none"
                self._audit('session_end reason="stop_phrase"')
                return "closed"
        elif m := re.match(r"set a timer for (\d+) seconds", t):
            if write("clock", {"action": "timer_start", "seconds": int(m.group(1))}):
                self.timer_due = self.clock.timestamp() + int(m.group(1))
        elif t == "cancel the timer":
            write("clock", {"action": "timer_cancel"})
            self.timer_due, self.ringing = None, False
        self._audit('assistant text="ok"')
        return "answered"

    def _refresh_cards(self) -> None:
        if "card_static" in self.bugs:
            return
        for c in self.cards:
            if c["title"] == "Groceries":
                c["rows"] = list(self.groceries)
