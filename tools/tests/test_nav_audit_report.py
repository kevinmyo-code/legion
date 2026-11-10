"""`tools/nav_audit_report.py` against a capture with every NAV_AUDIT event, noise lines and both spacings.

Also checks the phone's emitter and this parser agree on the event names, so a new event in
`NavAudit.kt` cannot be silently dropped by the report. Run from the repo root:

    python -m pytest tools/tests/test_nav_audit_report.py -p no:cacheprovider
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import nav_audit_report as nar  # noqa: E402

FIXTURE = TOOLS / "tests" / "fixtures" / "nav_audit_sample.txt"
EMITTER = TOOLS.parent / "app" / "src" / "main" / "java" / "com" / "kevin" / "legion" / "navigation" / "audit" / "NavAudit.kt"


@pytest.fixture(scope="module")
def r():
    with open(FIXTURE, encoding="utf-8") as fh:
        return nar.report(nar.parse(fh.readlines()))


def test_only_nav_audit_lines_are_read(r):
    # 33 lines in the file; the header, the LIVE_AUDIT line and the MapboxNavController warning are not events.
    assert r["events"] == 30


def test_search_success_rate_leaves_out_empty_phrases(r):
    s = r["search"]
    assert s["total"] == 6
    assert s["success_rate"] == pytest.approx(4 / 6, abs=0.001)
    assert s["by_origin"] == {"tap": 1, "typed": 2, "voice": 3}
    assert s["answered_by"] == {"saved_place": 2, "search": 2}


def test_zero_result_queries_are_counted_by_phrase(r):
    assert r["search"]["zero_result"] == [("zzyzx road", 2)]


def test_picked_rank_covers_auto_voice_dismissed_and_abandoned(r):
    assert r["search"]["picked_rank"] == {"1": 1, "3": 1, "abandoned": 1, "dismissed": 1}


def test_per_source_latency_is_nearest_rank(r):
    lat = r["latency"]
    assert lat["search"]["n"] == 4
    assert (lat["search"]["p50"], lat["search"]["p95"]) == (480, 1360)
    assert lat["search"]["hit_rate"] == 0.5
    assert lat["saved_place"]["n"] == 6
    assert lat["suggestions"]["n"] == 2


def test_route_latency_success_and_traffic(r):
    rt = r["routes"]
    assert rt["total"] == 4
    assert rt["success_rate"] == 0.75
    assert (rt["latency"]["p50"], rt["latency"]["p95"]) == (700, 12000)
    assert rt["failures"] == {"offline": 1}
    assert rt["traffic_used"] == {"false": 1, "true": 2}
    assert rt["alternatives"] == {"0": 1, "1": 1, "2": 1}


def test_trips_reroutes_and_eta_error(r):
    t = r["trips"]
    assert t["started"] == 3
    assert t["ends"] == {"arrived": 1, "cancelled": 1, "unknown": 1}
    assert t["reroutes"] == 2
    assert t["reroutes_per_trip"] == 1.0
    assert t["reroutes_per_hour"] == pytest.approx(2 / ((1259 + 159) / 3600), abs=0.01)
    assert t["off_route_reroutes"] == 1
    assert t["reroute_failed"] == 1
    assert t["refreshes"] == 1
    assert t["eta_error_s"]["mean"] == 159.0
    assert t["eta_error_pct"]["mean"] == 14.5


def test_cue_counts_add_up(r):
    assert r["trips"]["cues"] == {"spoken": 22, "dropped": {"cancelled": 1, "muted": 1, "superseded": 2}}


def test_text_and_json_output_run(capsys):
    assert nar.main([str(FIXTURE)]) == 0
    text = capsys.readouterr().out
    assert "zzyzx road" in text
    assert "SOURCE LATENCY" in text
    assert nar.main([str(FIXTURE), "--json"]) == 0
    assert json.loads(capsys.readouterr().out)["routes"]["total"] == 4


def test_an_empty_capture_reports_nothing_without_crashing(capsys):
    r = nar.report([])
    assert r["search"]["success_rate"] is None
    assert r["trips"]["eta_error_s"]["n"] == 0
    assert "NAV_AUDIT events: 0" in "\n".join(nar.render(r))


def test_quoted_free_text_with_spaces_and_escapes_parses():
    e = nar.parse_line('11-06 10:00:00.000 1 2 I NAV_AUDIT: search id=s1 verdict=not_found query="say \\"hi\\" there"')
    assert e["query"] == 'say "hi" there'
    assert e["at"] == "11-06 10:00:00.000"


def test_the_emitter_writes_exactly_the_events_the_report_reads():
    source = EMITTER.read_text(encoding="utf-8")
    emitted = set(re.findall(r'"([a-z_]+) (?:id|search|tool|trip)=', source))
    assert emitted == set(nar.EVENTS)
