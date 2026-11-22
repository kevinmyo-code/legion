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
