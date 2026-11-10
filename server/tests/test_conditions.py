"""`check_conditions` (round 5, 2026-11-10): rivers, alerts, storms, roads, fire.

Every source answer here is a recorded fixture (`tests/fixtures/conditions/README.md`); no
test reaches the network (`conftest.py` makes `_fetch` fail unless a test hands it answers).
"""

from __future__ import annotations

import urllib.error
from pathlib import Path

import pytest
from django.core.cache import cache

from conditions import report, sources
from tests.test_engine_mcp import call, client_with

pytestmark = pytest.mark.django_db

FX = Path(__file__).parent / "fixtures" / "conditions"
REAL_FETCH = sources._fetch  # conftest replaces `_fetch`; one test drives the real one
POINT = {"lat": 30.05, "lon": -98.85}


def fixture(name: str) -> bytes:
    return (FX / name).read_bytes()


@pytest.fixture(autouse=True)
def _fresh_cache():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def mcp(settings, household_user):
    settings.LEGION_MCP = True
    return client_with(household_user)


def route(monkeypatch, table: dict):
    """Answers `_fetch` by URL prefix; an Exception value is raised. Records each URL."""
    asked = []

    def fake(url, headers=None):
        asked.append((url, headers or {}))
        for prefix, answer in table.items():
            if url.startswith(prefix):
                if isinstance(answer, Exception):
                    raise answer
                return answer
        raise sources.SourceFailed("could not be reached (unrouted in test)")

    monkeypatch.setattr(sources, "_fetch", fake)
    return asked


ALL = {
    sources.NWS_ALERTS: fixture("nws_alerts.json"),
    sources.USGS_IV: fixture("usgs_iv.json"),
    sources.NWPS_GAUGES + "/BAPT2": fixture("nwps_gauge_BAPT2.json"),
    sources.NWPS_GAUGES + "/BRBT2": sources.SourceFailed("answered with an error (404)"),
    sources.NWPS_GAUGES: fixture("nwps_gauges.json"),
    sources.NHC_STORMS: fixture("nhc_storms.json"),
}


# --------------------------------------------------------------------------- parsers


def test_alert_parser_and_the_severe_filter():
    alerts = sources.parse_alerts(fixture("nws_alerts.json"))
    assert [a.event for a in alerts] == ["Storm Warning", "Flood Advisory", "Hurricane Watch"]
    assert [sources.is_severe(a) for a in alerts] == [True, False, True]
    assert alerts[0].sender.startswith("NWS")


def test_usgs_parser_pairs_height_and_flow_per_site():
    gauges = {g.site: g for g in sources.parse_usgs(fixture("usgs_iv.json"))}
    bear = gauges["08166140"]
    assert bear.stage_ft == 4.09 and bear.discharge_cfs == 357.0
    assert bear.stage_time == "2026-11-10T00:05:00.000-06:00"
    assert gauges["08166150"].discharge_cfs is None


def test_nwps_stage_parser_keeps_published_stages_only():
    assert sources.parse_nwps_stages(fixture("nwps_gauge_BAPT2.json")) == {
        "action": 4.0,
        "minor": 7.0,
        "moderate": 17.0,
        "major": 30.0,
    }
    assert (
        sources.parse_nwps_stages(b'{"flood": {"categories": {"minor": {"stage": -9999}}}}') == {}
    )


def test_storm_parser_names_the_class():
    (storm,) = sources.parse_storms(fixture("nhc_storms.json"))
    assert storm.classification == "Tropical Storm" and storm.name == "Pena"
    assert storm.intensity_kt == 35 and storm.movement.startswith("moving west")


def test_firms_parser_and_its_error_sentence():
    found = sources.parse_firms(fixture("firms_viirs.csv"))
    assert len(found) == 3 and found[0].confidence == "nominal"
    assert found[0].acquired == "2026-11-09 19:12 UTC"
    with pytest.raises(sources.SourceFailed, match="without detections"):
        sources.parse_firms(b"Invalid MAP_KEY.")


def test_drivetexas_standin_parses_any_geometry():
    roads = sources.parse_roads(fixture("drivetexas_standin.geojson"))
    assert roads[0].text == "Flooding, FM 0000, Low water crossing closed"
    assert (roads[0].lat, roads[0].lon) == (30.0, -98.7)


def test_bad_json_is_a_failure_not_empty():
    for parse in (sources.parse_alerts, sources.parse_usgs, sources.parse_storms):
        with pytest.raises(sources.SourceFailed):
            parse(b"<html>gateway timeout</html>")


def test_fetch_sends_the_nws_user_agent_with_a_timeout(monkeypatch):
    seen = {}

    def fake_urlopen(req, timeout):
        seen.update(ua=req.get_header("User-agent"), timeout=timeout, method=req.get_method())
        raise urllib.error.URLError("offline")

    monkeypatch.setattr(sources.urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(sources.SourceFailed, match="could not be reached"):
        REAL_FETCH("https://api.weather.gov/alerts/active?point=1,1")
    assert seen == {"ua": sources.USER_AGENT, "timeout": sources.TIMEOUT_SECONDS, "method": "GET"}
    assert "@" not in sources.USER_AGENT  # a public repo URL as the contact, never an email


# --------------------------------------------------------------------------- the tool


def test_everything_at_a_point_names_source_and_time(mcp, monkeypatch):
    asked = route(monkeypatch, ALL)
    is_error, text, structured = call(mcp, "check_conditions", POINT)
    assert not is_error, text
    assert text.startswith("Conditions at the given point, read just now.")
    assert "NWS alerts, active now for this point: Storm Warning (Severe" in text
    assert "Hurricane Watch (Moderate" in text and "Flood Advisory (Minor" in text
    assert (
        "gauge height 4.09 ft, flow 357 cubic feet per second (USGS, Nov 10 12:05 AM, UTC-6" in text
    )
    assert "Blanco River at Crabapple Road" in text and "below flood stage at 0.7 ft" in text
    assert "flood stages: action 4 ft, minor 7 ft, moderate 17 ft, major 30 ft" in text
    assert "Blanco River at Blanco" in text and "NWS publishes no flood stage" in text
    assert "Tropical Storm Pena, 40 mph winds" in text and "None is within 900 miles" in text
    assert "DriveTexas is not set up" in text and sources.DRIVETEXAS_KEY_ENV in text
    assert "NASA FIRMS is not set up" in text and sources.FIRMS_KEY_ENV in text
    statuses = {s["kind"]: s["status"] for s in structured["sections"]}
    assert statuses == {
        "alerts": "found",
        "rivers": "found",
        "storms": "found",
        "roads": "not_configured",
        "fire": "not_configured",
    }
    nws = [h for u, h in asked if u.startswith(sources.NWS_ALERTS)]
    assert nws == [{"Accept": "application/geo+json"}]


def test_severe_only_keeps_severe_and_hurricane(mcp, monkeypatch):
    route(monkeypatch, ALL)
    _, text, structured = call(
        mcp, "check_conditions", {**POINT, "kinds": ["alerts"], "severe_only": True}
    )
    assert "Storm Warning" in text and "Hurricane Watch" in text
    assert "Flood Advisory" not in text
    rows = structured["sections"][0]["rows"]
    assert all(r["severe"] for r in rows) and len(rows) == 2


def test_no_alerts_is_read_and_empty(mcp, monkeypatch):
    route(monkeypatch, {sources.NWS_ALERTS: fixture("nws_alerts_empty.json")})
    is_error, text, _ = call(mcp, "check_conditions", {**POINT, "kinds": ["alerts"]})
    assert not is_error
    assert "lists no active alerts for this point right now" in text


def test_an_unreachable_source_is_not_known_never_clear(mcp, monkeypatch):
    route(
        monkeypatch, {sources.NWS_ALERTS: sources.SourceFailed("could not be reached (URLError)")}
    )
    is_error, text, _ = call(mcp, "check_conditions", {**POINT, "kinds": ["alerts"]})
    assert is_error and text.startswith("Nothing was read.")
    assert "is NOT known" in text and "no active alerts" not in text


def test_offline_everything_fails_in_words(mcp):
    is_error, text, _ = call(mcp, "check_conditions", POINT)
    assert is_error and text.startswith("Nothing was read.")
    for words in ("NWS alerts", "river levels here are NOT known", "active storms are NOT known"):
        assert words in text


def test_keys_configured_reach_drivetexas_and_firms(mcp, monkeypatch):
    monkeypatch.setenv(sources.DRIVETEXAS_KEY_ENV, "dt-test-key")
    monkeypatch.setenv(sources.FIRMS_KEY_ENV, "firms-test-key")
    asked = route(
        monkeypatch,
        {
            sources.DRIVETEXAS: fixture("drivetexas_standin.geojson"),
            sources.FIRMS_AREA: fixture("firms_viirs.csv"),
        },
    )
    is_error, text, _ = call(mcp, "check_conditions", {**POINT, "kinds": ["roads", "fire"]})
    assert not is_error, text
    assert "Road conditions from DriveTexas near here: Flooding, FM 0000" in text
    assert "Construction" not in text  # 200+ km away
    assert "2 within 31 mi" in text and "not a confirmed wildfire" in text
    urls = [u for u, _ in asked]
    assert any("key=dt-test-key" in u for u in urls)
    assert any("/firms-test-key/VIIRS_SNPP_NRT/" in u for u in urls)


def test_answers_are_cached_per_process(mcp, monkeypatch):
    asked = route(monkeypatch, ALL)
    call(mcp, "check_conditions", {**POINT, "kinds": ["alerts"]})
    call(mcp, "check_conditions", {**POINT, "kinds": ["alerts"]})
    assert len([u for u, _ in asked if u.startswith(sources.NWS_ALERTS)]) == 1


def test_home_place_is_the_default_point(mcp, monkeypatch, auth_client):
    response = auth_client.put(
        "/api/places/home/", {"latitude": 30.05, "longitude": -98.85}, format="json"
    )
    assert response.status_code in (200, 201), response.content
    route(monkeypatch, {sources.NWS_ALERTS: fixture("nws_alerts_empty.json")})
    is_error, text, structured = call(mcp, "check_conditions", {"kinds": ["alerts"]})
    assert not is_error, text
    assert "Conditions at the saved place 'home'" in text
    assert structured["lat"] == 30.05


def test_no_point_and_no_home_is_refused(mcp):
    is_error, text, _ = call(mcp, "check_conditions", {"kinds": ["alerts"]})
    assert is_error and "no saved place is labelled home" in text
    is_error, text, _ = call(mcp, "check_conditions", {"place": "cabin"})
    assert is_error and "No saved place is labelled 'cabin'" in text
    is_error, text, _ = call(mcp, "check_conditions", {"lat": 30.0})
    assert is_error and "both lat and lon" in text


def test_clock_keeps_the_sources_offset():
    assert report._clock("2026-11-10T00:05:00.000-06:00") == "Nov 10 12:05 AM, UTC-6"
    assert report._clock("2026-11-10T05:45:00Z") == "Nov 10 5:45 AM, UTC"
    assert report._clock("2026-11-10T09:30:00+05:30") == "Nov 10 9:30 AM, UTC+5:30"
    assert report._clock("nonsense") == ""
