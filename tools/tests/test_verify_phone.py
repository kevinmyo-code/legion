"""tools/verify_phone: the runner against the fake phone, the log readers, and the stale map.

    python -m pytest tools/tests/test_verify_phone.py -p no:cacheprovider
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

from verify_phone import logs, runner, stale  # noqa: E402
from verify_phone.device import parse_snapshot  # noqa: E402
from verify_phone.fake import FakeDevice  # noqa: E402
from verify_phone.suites import SUITES  # noqa: E402
from verify_phone.suites.next2 import SUITE as NEXT2  # noqa: E402

REPO = TOOLS.parent


def run(**kw):
    return runner.run_suite(NEXT2, FakeDevice(**kw))


def status(r, cid):
    return next(x for x in r["results"] if x["id"] == cid)


# ------------------------------------------------------------------------------------ the runner

def test_happy_phone_passes_every_scripted_check_and_marks_voice_ones_manual():
    r = run()
    assert r["totals"]["fail"] == 0 and r["totals"]["blocked"] == 0
    assert {x["id"] for x in r["results"] if x["status"] == runner.MANUAL} == {"closing_by_voice", "wake_after_call_ring"}
    assert all(x["cleanup"] in ("", "ok") for x in r["results"])


@pytest.mark.parametrize("bug, check", [
    ("card_static", "card_live_update"),
    ("no_carry", "date_carry"),
    ("resurrect", "calendar_delete"),
    ("no_close", "closing_thanks"),
    ("mic_stuck", "mic_after_ring"),
    ("crash", "no_crash"),
])
def test_each_regression_fails_its_own_check_only(bug, check):
    r = run(bugs={bug})
    failed = {x["id"] for x in r["results"] if x["status"] == runner.FAIL}
    assert failed == {check}


def test_locked_phone_stops_the_run_before_any_check():
    r = run(locked=True)
    assert "locked" in r["stopped"]
    assert all(x["status"] in (runner.NOT_RUN, runner.MANUAL) for x in r["results"])
    assert r["totals"]["pass"] == 0


def test_wrong_build_skips_everything_but_the_version_and_crash_scan():
    r = run(version_code=263260518)
    assert status(r, "version")["status"] == runner.FAIL
    assert status(r, "no_crash")["status"] == runner.PASS
    assert status(r, "date_carry")["status"] == runner.SKIPPED


def test_door_switched_off_blocks_door_checks_with_the_reason():
    r = run(door="switch off (Kevin: Settings)")
    assert status(r, "budget_card")["status"] == runner.BLOCKED
    assert "switch off" in status(r, "budget_card")["reason"]
    assert status(r, "version")["status"] == runner.PASS


def test_guard_refusal_is_blocked_and_cleanup_goes_through_the_assistant():
    dev = FakeDevice(bugs={"guard_remove"})
    r = runner.run_suite(NEXT2, dev)
    cal = status(r, "calendar_delete")
    assert cal["status"] == runner.BLOCKED and "approval" in cal["reason"]
    assert cal["cleanup"] == "ok"
    assert not any("verify-test" in n for names in dev.events.values() for n in names)


def test_cleanup_that_fails_is_reported_left_behind():
    r = run(bugs={"cleanup_fails"})
    c = status(r, "card_live_update")
    assert c["cleanup"].startswith("LEFT BEHIND")
    assert "LEFT BEHIND" in runner.table(r)


def test_only_runs_the_named_checks_plus_the_always_ones():
    r = runner.run_suite(NEXT2, FakeDevice(), only={"budget_card"})
    ran = {x["id"] for x in r["results"] if x["status"] == runner.PASS}
    assert ran == {"version", "budget_card", "no_crash"}


def test_log_carries_no_screen_text_or_spoken_words(tmp_path):
    r = run()
    path = runner.write_log(r, tmp_path)
    text = path.read_text(encoding="utf-8")
    for private in ("eggs", "Private Brand Oats", "Private Dinner", "Food: On track", "what's on my"):
        assert private not in text
    assert json.loads(text)["totals"]["pass"] == 8


def test_test_items_are_all_gone_after_a_happy_run():
    dev = FakeDevice()
    runner.run_suite(NEXT2, dev)
    assert not [g for g in dev.groceries if g.startswith("verify-test")]
    assert not [n for names in dev.events.values() for n in names if n.startswith("verify-test")]
    assert not dev.ringing and dev.conversation() == "none"


# ------------------------------------------------------------------------------- log readers

LINES = """\
11-22 14:00:00.001  100  100 I LIVE_AUDIT: tool_call id=a1 name=manage_item async=false args={"action":"add","time":"21:00"}
11-22 14:00:00.200  100  100 I LIVE_AUDIT: tool_result id=a1 name=manage_item success=true ms=40 scheduling=none sent=true response={"success":true,"date":"2026-11-23","message":"x"}
11-22 14:00:01.000  100  100 I LIVE_AUDIT: session_end reason="stop_phrase"
11-22 14:00:02.000  100  100 D MicArbiter: request: WAKE_WORD granted
11-22 14:00:03.000  777  777 E AndroidRuntime: FATAL EXCEPTION: main
11-22 14:00:03.001  777  777 E AndroidRuntime: Process: com.kevin.legion, PID: 777
11-22 14:00:03.002  777  777 E AndroidRuntime: java.lang.NullPointerException: Attempt to read
11-22 14:00:04.000  888  888 E AndroidRuntime: FATAL EXCEPTION: main
11-22 14:00:04.001  888  888 E AndroidRuntime: Process: com.other.app, PID: 888
"""


def test_tool_calls_pair_results_and_read_the_landed_date():
    calls = logs.tool_calls(logs.parse(LINES))
    assert [(c.name, c.success, c.args["action"]) for c in calls] == [("manage_item", True, "add")]
    assert logs.DATE_IN_RESULT.search(calls[0].response).group(1) == "2026-11-23"


def test_crashes_count_legion_only():
    assert logs.crashes(logs.parse(LINES)) == ["crash java.lang.NullPointerException"]


def test_session_end_and_mic_lines():
    parsed = logs.parse(LINES)
    assert logs.sessions_ended(parsed) == ["stop_phrase"]
    assert any(logs.MIC_WAKE_GRANTED.search(x.msg) for x in parsed if x.tag == "MicArbiter")


def test_parse_snapshot_reads_phonectl_lines():
    els = parse_snapshot(['e3 Button "Remove" [click] @940,300,1040,360', 'e4 TextView "a "quoted" row" [] @0,0,10,10'])
    assert els[0].label == "Remove" and els[0].clickable and els[0].center == (990, 330)
    assert els[1].label == 'a "quoted" row' and not els[1].clickable


# ------------------------------------------------------------------------------- the stale map

def test_every_mapped_file_and_symbol_exists():
    """The map rots silently otherwise: a renamed file would never be flagged again."""
    checks = [c for s in SUITES.values() for c in s.checks]
    assert stale.missing(checks, REPO) == []


def test_every_scripted_check_maps_app_code():
    for c in NEXT2.checks:
        if c.id != "no_crash":
            assert c.deps, c.id


NAME_STATUS = "M\tapp/x/WakePhrases.kt\nR100\tapp/x/Old.kt\tapp/x/New.kt\nM\tapp/x/Other.kt\n"
PATCH = """\
diff --git a/app/x/WakePhrases.kt b/app/x/WakePhrases.kt
--- a/app/x/WakePhrases.kt
+++ b/app/x/WakePhrases.kt
@@ -10,1 +10,1 @@ object WakePhrases {
-    private val LEAD_INS = setOf("hey")
+    private val LEAD_INS = setOf("hey", "yo")
diff --git a/app/x/Other.kt b/app/x/Other.kt
--- a/app/x/Other.kt
+++ b/app/x/Other.kt
@@ -3,1 +3,1 @@ fun unrelated() {
-    val a = 1
+    val a = 2
"""


def test_stale_flags_touched_symbols_deleted_files_and_symbolless_files(tmp_path):
    (tmp_path / "app/x").mkdir(parents=True)
    (tmp_path / "app/x/WakePhrases.kt").write_text('private val LEAD_INS = setOf("hey", "yo")\nfun isStopPhrase()')
    (tmp_path / "app/x/Other.kt").write_text("fun unrelated() { val a = 2 }\nfun kept()")
    diff = stale.parse_diff(NAME_STATUS, PATCH)
    checks = [
        runner.Check("phrases", "", {"app/x/WakePhrases.kt": ["LEAD_INS", "isStopPhrase"]}),
        runner.Check("renamed", "", {"app/x/Old.kt": ["anything"]}),
        runner.Check("whole_file", "", {"app/x/Other.kt": []}),
        runner.Check("untouched_symbol", "", {"app/x/Other.kt": ["kept"]}),
        runner.Check("not_in_diff", "", {"app/x/Elsewhere.kt": ["x"]}),
    ]
    got = dict(stale.flags(checks, diff, tmp_path))
    assert got["phrases"].endswith("touched LEAD_INS")
    assert "deleted or renamed" in got["renamed"]
    assert got["whole_file"].endswith("changed")
    assert "untouched_symbol" not in got and "not_in_diff" not in got


def test_stale_flags_a_symbol_the_diff_removed(tmp_path):
    (tmp_path / "app/x").mkdir(parents=True)
    (tmp_path / "app/x/WakePhrases.kt").write_text("nothing here now")
    diff = stale.parse_diff(NAME_STATUS, PATCH)
    got = dict(stale.flags([runner.Check("c", "", {"app/x/WakePhrases.kt": ["LEAD_INS"]})], diff, tmp_path))
    assert "no longer has LEAD_INS" in got["c"]


# ------------------------------------------------------------- review fixes (2026-11-22)

def test_forbidden_tool_on_an_old_build_stops_the_run_and_sends_no_cleanup_turn():
    dev = FakeDevice(bugs={"forbidden_tool"}, preventive=False)
    r = runner.run_suite(NEXT2, dev)
    assert "FORBIDDEN" in r["stopped"] and r["door_preventive"] is False
    card = status(r, "card_live_update")
    assert card["status"] == runner.BLOCKED and card["cleanup"].startswith("LEFT BEHIND")
    # After the add that also mailed, not one more turn went to the assistant.
    assert dev.sent[-1][0].startswith("add verify-test milk")
    assert status(r, "budget_card")["status"] == runner.NOT_RUN


def test_forbidden_tool_refused_by_the_door_is_the_guard_working():
    dev = FakeDevice(bugs={"forbidden_tool"}, preventive=True)
    r = runner.run_suite(NEXT2, dev)
    assert r["stopped"] == "" and r["door_preventive"] is True
    assert r["totals"]["fail"] == 0
    assert any(k.startswith("forbidden_refused") for k in status(r, "card_live_update")["evidence"])


def test_every_write_turn_names_its_tools_and_reads_name_none():
    dev = FakeDevice()
    runner.run_suite(NEXT2, dev)
    for words, tools in dev.sent:
        writes = words.startswith(("add ", "remove ", "delete ", "set a timer", "cancel "))
        assert bool(tools) == writes, words
        assert not set(tools) & logs.FORBIDDEN_TOOLS


def test_a_delete_answered_with_a_read_is_not_counted_as_deleted():
    r = run(bugs={"delete_reads"})
    assert status(r, "date_carry")["cleanup"].startswith("LEFT BEHIND")


class NeighbourRemoveFake(FakeDevice):
    """The test row's own Remove is missing from the dump; a real event's Remove sits 20 px lower, short of the test row."""

    def screen(self):
        els = super().screen()
        if self.where != "calendar":
            return els
        out = []
        for e in els:
            if e.label == "Remove":
                row = next(x for x in els if x.bounds[1] == e.bounds[1] and x is not e)
                if row.label.startswith("verify-test"):
                    continue
                e.bounds = (940, e.bounds[1] + 20, 1040, e.bounds[3] + 20)  # near the test row, not on it
            out.append(e)
        return out


def test_calendar_remove_must_span_the_test_rows_centre():
    dev = NeighbourRemoveFake()
    r = runner.run_suite(NEXT2, dev, only={"calendar_delete"})
    cal = status(r, "calendar_delete")
    assert cal["status"] == runner.BLOCKED and "single Remove" in cal["reason"]
    assert "Remove" not in dev.taps
    assert dev.events[dev.clock.date()] == ["Private Dinner"]  # his event untouched, test item cleaned up


def test_exit_code_is_nonzero_when_a_pass_left_its_item(tmp_path, monkeypatch):
    from verify_phone.__main__ import main
    monkeypatch.setenv("LEGION_VERIFY_OUT", str(tmp_path))
    assert main(["run", "next2", "--dry-run"]) == 0
    assert main(["run", "next2", "--dry-run", "--only", "budget_card,date_carry", "--fake-bugs", "cleanup_fails"]) == 1


# ------------------------------------------------------- RealDevice against a stub phonectl

NEW_DOOR = "Result: Bundle[{ok=true, enabled=true, service=true, allow_tools=true, conversation=none}]"
OLD_DOOR = "Result: Bundle[{ok=true, enabled=true, service=true, conversation=none}]"


class StubAdb:
    serial = None

    def __init__(self, raw_rc=0, status=NEW_DOOR):
        self.raw_rc, self.status = raw_rc, status
        self.raw_calls, self.run_calls = [], []

    def run(self, args, timeout=20):
        self.run_calls.append(args)
        cmd = " ".join(args)
        if "%m-%d" in cmd:
            return b"11-22 14:00:00.000\n"
        if "--method status" in cmd:
            return self.status.encode()
        return b"Result: Bundle[{ok=true}]"

    def raw(self, args, timeout=20):
        self.raw_calls.append(args)
        out = b"Result: Bundle[{ok=true}]" if self.raw_rc == 0 else b""
        return self.raw_rc, out, b"error: device offline"


class StubPhone:
    def __init__(self, screen=None, act=None, adb=None):
        self._screen = screen or {"awake": True, "locked": False, "package": "com.kevin.legion"}
        self._act = act or {"ok": True}
        self.adb = adb or StubAdb()
        self.taps = []

    def screen(self):
        return dict(self._screen)

    def snapshot(self):
        if self._screen.get("locked") is not False or self._screen.get("awake") is not True:
            return {"ok": False, "refused": "phone is locked: ask Kevin"}
        return {"ok": True, "elements": []}

    def tap(self, target=None, x=None, y=None, **kw):
        assert "approved_by_user" not in kw  # never set by this tool
        self.taps.append((target, x, y))
        return dict(self._act)


def real(**kw):
    from verify_phone.device import RealDevice
    return RealDevice(phone=StubPhone(**kw), sleep=lambda s: None)


@pytest.mark.parametrize("screen", [
    {"awake": True, "locked": True, "package": "x"},
    {"awake": True, "locked": None, "package": "x"},   # unreadable lock state: fail closed
    {"awake": False, "locked": False, "package": "x"},
    {"awake": None, "locked": False, "package": "x"},
])
def test_real_device_fails_closed_on_a_locked_dark_or_unreadable_phone(screen):
    d = real(screen=screen)
    with pytest.raises(runner.Abort):
        d.mark()
    with pytest.raises(runner.Abort):
        d.screen()


def test_real_device_guard_approval_becomes_blocked_without_screen_text():
    d = real(act={"ok": False, "needs_approval": True,
                  "refused": "needs Kevin's approval: target says 'delete' (\"Delete Private Dinner\")"})
    with pytest.raises(runner.Blocked) as e:
        d.tap_xy(10, 10, what="the row's Remove")
    assert "approval" in str(e.value) and "Private" not in str(e.value)


def test_real_device_error_text_is_short_and_has_no_quoted_labels():
    d = real(act={"ok": False, "refused": 'no element matching "Private Dinner at a place" on screen (0 matches)'})
    with pytest.raises(runner.Blocked) as e:
        d.tap_text("Calendar")
    assert "Private" not in str(e.value)
    from verify_phone.device import short
    assert len(short("x" * 200)) == 60


def test_real_device_sends_a_turn_once_and_a_dropped_link_stops_the_run():
    d = real(adb=StubAdb(raw_rc=1))
    with pytest.raises(runner.Abort):
        d.turn("add verify-test milk to my grocery list", "verify-x", tools=("manage_checklist",))
    assert len(d.adb.raw_calls) == 1  # never retried
    sent = " ".join(d.adb.raw_calls[0])
    assert "--method turn" in sent and "allow_tools:s:manage_checklist" in sent and "writes:b:true" in sent


def test_real_device_read_turn_carries_no_write_extras():
    adb = StubAdb()
    d = real(adb=adb)
    d.turn("what's on my calendar tomorrow?", "verify-x", timeout=0.01)
    sent = " ".join(adb.raw_calls[0])
    assert "writes" not in sent and "allow_tools" not in sent


def test_real_device_reports_an_old_door_as_not_preventive():
    d = real(adb=StubAdb(status=OLD_DOOR))
    assert d.door_ready() is True and d.preventive is False
    d2 = real()
    assert d2.door_ready() is True and d2.preventive is True


# ------------------------------------------------------------- review round 2 (2026-11-22)

class MailOnCleanup(FakeDevice):
    """Old build: the model also mails during the grocery cleanup turn."""

    def turn(self, text, case_id, tools=(), timeout=60):
        if text.startswith("remove verify-test milk"):
            self._tool("email", {"to": "someone"}, True)
        return super().turn(text, case_id, tools, timeout)


def test_forbidden_tool_in_a_cleanup_turn_stops_the_run_before_the_next_check():
    dev = MailOnCleanup(preventive=False)
    r = runner.run_suite(NEXT2, dev)
    assert "FORBIDDEN" in r["stopped"] and "cleanup" in r["stopped"]
    assert status(r, "budget_card")["status"] == runner.NOT_RUN
    assert dev.sent[-1][0].startswith("remove verify-test milk")
    assert "STOPPED" in runner.table(r)


class RanButSaysTestMode(FakeDevice):
    """A forbidden tool that RAN (success true) though its response mentions test_mode."""

    def turn(self, text, case_id, tools=(), timeout=60):
        if text.startswith("add verify-test milk"):
            self._tool("email", {"to": "someone"}, True, {"test_mode": True})
        return super().turn(text, case_id, tools, timeout)


def test_a_forbidden_tool_counts_as_refused_only_with_success_false_and_test_mode():
    r = runner.run_suite(NEXT2, RanButSaysTestMode(preventive=False))
    assert "FORBIDDEN" in r["stopped"]


def test_a_check_whose_write_was_never_sent_reports_no_left_behind():
    import datetime as dt
    r = runner.run_suite(NEXT2, FakeDevice(now=dt.datetime(2026, 11, 22, 23, 30)))
    for cid in ("date_carry", "calendar_delete"):
        res = status(r, cid)
        assert res["status"] == runner.BLOCKED, cid
        assert not res["cleanup"].startswith("LEFT BEHIND"), (cid, res["cleanup"])


class RefusesTheAdd(FakeDevice):
    """The phone refuses the calendar add turn (e.g. a real conversation opened): nothing was written."""

    def turn(self, text, case_id, tools=(), timeout=60):
        if text.startswith(("add verify-test dentist", "set a timer")):
            raise runner.Blocked("the phone refused the turn: real_conversation")
        return super().turn(text, case_id, tools, timeout)


def test_a_refused_add_turn_leaves_nothing_to_clean_up():
    r = runner.run_suite(NEXT2, RefusesTheAdd())
    for cid in ("calendar_delete", "mic_after_ring"):
        res = status(r, cid)
        assert res["status"] == runner.BLOCKED, cid
        assert res["cleanup"] == "ok", (cid, res["cleanup"])


class DropsAfterTheAdd(FakeDevice):
    """The calendar add lands on the phone, then adb drops before the reply (review probe P3)."""

    def turn(self, text, case_id, tools=(), timeout=60):
        out = super().turn(text, case_id, tools, timeout)
        if text.startswith("add verify-test dentist"):
            raise runner.Abort("adb dropped while sending a turn (not resent): timed out")
        return out


def test_a_write_turn_that_may_have_landed_is_cleaned_up_or_reported():
    dev = DropsAfterTheAdd()
    r = runner.run_suite(NEXT2, dev, only={"calendar_delete"})
    cal = status(r, "calendar_delete")
    left = [n for names in dev.events.values() for n in names if n.startswith("verify-test")]
    # Either the cleanup removed it, or the run says it was left behind: never silent.
    assert (not left and cal["cleanup"] == "ok") or cal["cleanup"].startswith("LEFT BEHIND"), (left, cal["cleanup"])
    assert not left
