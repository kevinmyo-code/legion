"""`api/recurrence.py` against the shared vectors (web-revamp ticket 08, spec D4).

`tests/fixtures/recurrence_vectors.json` is read by this file and by the web's
`recurrence.test.ts`; each vector is transcribed from the phone's own
expansion tests and names the Kotlin test it came from. A vector this port
gets wrong fails here; a vector that is itself wrong fails the
broken-vector check below, which is what makes the comparison a gate rather
than an echo.
"""
from __future__ import annotations

import copy
import datetime as dt
import json
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from api.recurrence import parse_weekdays, rule_from_series, series_occurrences

FIXTURE = Path(__file__).parent / "fixtures" / "recurrence_vectors.json"
VECTORS = json.loads(FIXTURE.read_text(encoding="utf-8"))["vectors"]
REPO_ROOT = Path(__file__).resolve().parents[2]


def _instant(raw: str) -> dt.datetime:
    return dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))


def expand(vector: dict) -> list[str]:
    zone = ZoneInfo(vector["tz"])
    occurrences = series_occurrences(
        vector["series"],
        [dt.date.fromisoformat(day) for day in vector["skips"]],
        _instant(vector["window"]["start"]),
        _instant(vector["window"]["end"]),
        zone,
    )
    return [o.astimezone(zone).strftime("%Y-%m-%dT%H:%M") for o in occurrences]


@pytest.mark.parametrize("vector", VECTORS, ids=[v["name"] for v in VECTORS])
def test_every_vector(vector):
    assert expand(vector) == vector["expected"], vector["kotlin"]


def test_the_vectors_cover_every_rule_kind_end_kind_and_a_skip():
    kinds = {v["series"]["repeat_kind"] for v in VECTORS}
    ends = {v["series"]["repeat_end_kind"] for v in VECTORS}
    assert kinds == {"DAILY", "WEEKLY", "MONTHLY_ON_DATE", "YEARLY"}
    assert ends == {"NEVER", "ON_DATE", "AFTER_COUNT"}
    assert any(v["skips"] for v in VECTORS)
    assert {v["tz"] for v in VECTORS} >= {"UTC", "Asia/Tokyo", "America/Chicago"}


def test_every_vector_names_a_kotlin_test_that_exists():
    """The transcription is checkable: each vector's Kotlin file and test name
    are read from the phone's source tree. Skipped only where the tree is not
    checked out beside the server (a server-only image)."""
    if not (REPO_ROOT / "app").is_dir():
        pytest.skip("app/ is not checked out beside server/ here")
    for vector in VECTORS:
        path, _, name = vector["kotlin"].partition(" :: ")
        source = (REPO_ROOT / path).read_text(encoding="utf-8")
        assert f"fun `{name}`" in source, (vector["name"], path, name)


def test_a_broken_vector_fails():
    """Drop one expected occurrence, add a skip that is not in the vector, or
    move the zone: each must change the comparison's answer. A check that
    passes whatever the vector says is not a check."""
    vector = next(v for v in VECTORS if v["name"] == "weekly-monday-east-of-utc")
    dropped = copy.deepcopy(vector)
    dropped["expected"].pop()
    assert expand(dropped) != dropped["expected"]

    skipped = copy.deepcopy(vector)
    skipped["skips"].append("2026-08-17")
    assert expand(skipped) != skipped["expected"]

    moved = copy.deepcopy(vector)
    moved["tz"] = "UTC"
    assert expand(moved) != moved["expected"]


def test_weekday_spellings_are_the_phones():
    assert parse_weekdays("MON,wed, Friday") == (0, 2, 4)
    assert parse_weekdays("") == ()
    assert parse_weekdays("MON,FUNDAY") is None


def test_an_unreadable_repeat_reads_as_not_repeating():
    assert rule_from_series({"repeat_kind": "WEEKLY", "repeat_every": 1}) is None
    assert rule_from_series({"repeat_kind": "HOURLY", "repeat_every": 1}) is None
    assert rule_from_series({"repeat_kind": "YEARLY", "repeat_month": 2}) is None


def test_a_one_off_event_is_its_own_single_occurrence_unless_skipped():
    zone = ZoneInfo("America/Chicago")
    one_off = {"starts_at": "2026-10-05T20:00:00Z", "repeat_kind": None}
    window = (_instant("2026-10-01T00:00:00Z"), _instant("2026-10-10T00:00:00Z"))
    assert series_occurrences(one_off, [], *window, zone) == [_instant("2026-10-05T20:00:00Z")]
    assert series_occurrences(one_off, [dt.date(2026, 10, 5)], *window, zone) == []
    assert series_occurrences({"starts_at": None}, [], *window, zone) == []
