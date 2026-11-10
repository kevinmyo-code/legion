"""The agency feeds behind `check_conditions`, one parser each, one network call for all.

Every endpoint below was fetched on 2026-11-10 and its answer's shape recorded in
`tests/fixtures/conditions/` (trimmed; no live call in tests). Keyless unless said.

- **alerts**, NWS (api.weather.gov): `/alerts/active?point=<lat>,<lon>`, GeoJSON. NWS asks for a
  User-Agent naming the app and a contact instead of a key: `USER_AGENT` is the phone's
  `AreaInfo.NWS_USER_AGENT`, byte for byte, with the public repo as the contact, never an email.
- **rivers**, USGS Water Services: `waterservices.usgs.gov/nwis/iv/?format=json&bBox=...
  &parameterCd=00065,00060&siteStatus=active`, gauge height (ft) and discharge (cfs), latest
  instantaneous values (provisional).
- **rivers (flood stage)**, NWS National Water Prediction Service:
  `api.water.noaa.gov/nwps/v1/gauges?bbox...` (observed stage and flood category), and
  `/gauges/<lid>` for the action, minor, moderate and major stages it publishes.
- **storms**, National Hurricane Center: `www.nhc.noaa.gov/CurrentStorms.json`, every active storm
  NHC and CPHC track.
- **roads**, DriveTexas (TxDOT): `api.drivetexas.org/api/conditions.geojson` answers 401 without a
  key (checked 2026-11-10). Needs `LEGION_DRIVETEXAS_KEY` from a DriveTexas API signup; until then
  "not set up". The feed's property names are NOT verified (no key to fetch with): `_road_text`
  reads the likely ones and falls back to every string property, and the fixture is a stand-in.
  Re-record it once the key exists.
- **fire**, NASA FIRMS: `firms.modaps.eosdis.nasa.gov/api/area/csv/<MAP_KEY>/VIIRS_SNPP_NRT/
  <w,s,e,n>/<days>`, satellite heat detections. Needs a free MAP_KEY (`LEGION_FIRMS_MAP_KEY`); until
  then "not set up". A detection is a hot pixel, not a confirmed wildfire, and every sentence says
  so: the phone's `area_info` fire category uses NIFC's named incidents for exactly that reason.

**Cache.** No `CACHES` is configured, so `django.core.cache` is Django's default per-process
local memory. Each Cloud Run instance keeps its own short copy (minutes, see `TTL`); nothing is
shared or persisted, and a cold instance simply asks again.

**Failure.** Every fetch or parse failure raises `SourceFailed` with a sentence: "could not be
reached" is never rendered as "nothing there".
"""

from __future__ import annotations

import csv
import io
import json
import math
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

from django.core.cache import cache

# The phone's `location/AreaInfo.kt` NWS_USER_AGENT, byte for byte.
USER_AGENT = "(LEGION voice assistant, https://github.com/kevinmyo-code/legion)"
TIMEOUT_SECONDS = 8
KM_PER_MILE = 1.609344

NWS_ALERTS = "https://api.weather.gov/alerts/active"
USGS_IV = "https://waterservices.usgs.gov/nwis/iv/"
NWPS_GAUGES = "https://api.water.noaa.gov/nwps/v1/gauges"
NHC_STORMS = "https://www.nhc.noaa.gov/CurrentStorms.json"
DRIVETEXAS = "https://api.drivetexas.org/api/conditions.geojson"
FIRMS_AREA = "https://firms.modaps.eosdis.nasa.gov/api/area/csv"

DRIVETEXAS_KEY_ENV = "LEGION_DRIVETEXAS_KEY"
FIRMS_KEY_ENV = "LEGION_FIRMS_MAP_KEY"

RIVER_RADIUS_KM = 25.0
ROAD_RADIUS_KM = 40.0
FIRE_RADIUS_KM = 50.0
FIRE_DAYS = 2

# Seconds each answer is kept per process.
TTL = {
    "alerts": 120,
    "usgs": 600,
    "nwps": 600,
    "nwps_gauge": 6 * 3600,
    "storms": 600,
    "roads": 300,
    "fire": 1800,
}


class SourceFailed(Exception):
    """The source was not reached or answered in a shape this cannot read."""


class NotConfigured(Exception):
    """The source needs a key this engine does not have. `env` names it."""

    def __init__(self, env: str):
        super().__init__(env)
        self.env = env


def _fetch(url: str, headers: dict[str, str] | None = None) -> bytes:
    """The one network call in this package; tests replace it."""
    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, **(headers or {})}, method="GET"
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310
            return response.read()
    except urllib.error.HTTPError as exc:
        raise SourceFailed(f"answered with an error ({exc.code})") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise SourceFailed(f"could not be reached ({type(exc).__name__})") from exc


def _json(raw: bytes):
    try:
        return json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise SourceFailed("answered with something that was not JSON") from exc


def _cached(key: str, ttl: int, load):
    hit = cache.get(key)
    if hit is not None:
        return hit
    value = load()
    cache.set(key, value, ttl)
    return value


def _cell(lat: float, lon: float) -> str:
    return f"{lat:.2f},{lon:.2f}"


def distance_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def compass_word(lat1: float, lon1: float, lat2: float, lon2: float) -> str:
    """Which way the second point lies from the first, as one of eight words."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    deg = (math.degrees(math.atan2(y, x)) + 360) % 360
    words = ("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
    return words[int((deg + 22.5) // 45) % 8]


def bbox(lat: float, lon: float, radius_km: float) -> tuple[float, float, float, float]:
    """(west, south, east, north) around the point, `radius_km` each way."""
    dlat = radius_km / 111.32
    dlon = radius_km / (111.32 * max(0.01, math.cos(math.radians(lat))))
    return (round(lon - dlon, 4), round(lat - dlat, 4), round(lon + dlon, 4), round(lat + dlat, 4))


# --------------------------------------------------------------------------- NWS alerts


@dataclass(frozen=True)
class Alert:
    id: str
    event: str
    severity: str
    urgency: str
    certainty: str
    headline: str
    area: str
    effective: str
    expires: str
    ends: str
    sender: str


SEVERE_LEVELS = frozenset({"Severe", "Extreme"})
HURRICANE_EVENTS = frozenset({"Hurricane Watch", "Hurricane Warning"})


def is_severe(alert: Alert) -> bool:
    """The proactive raise's filter (Kevin, round 5): NWS severity Severe or Extreme, or a
    hurricane watch or warning whatever severity NWS gave it. Nothing else raises."""
    return alert.severity in SEVERE_LEVELS or alert.event in HURRICANE_EVENTS


def parse_alerts(raw: bytes) -> list[Alert]:
    body = _json(raw)
    features = body.get("features") if isinstance(body, dict) else None
    if not isinstance(features, list):
        raise SourceFailed("answered without a list of alerts")
    alerts = []
    for feature in features:
        p = (feature or {}).get("properties") or {}
        if p.get("status", "Actual") != "Actual":
            continue
        alerts.append(
            Alert(
                id=str(p.get("id") or feature.get("id") or ""),
                event=str(p.get("event") or "Alert"),
                severity=str(p.get("severity") or "Unknown"),
                urgency=str(p.get("urgency") or "Unknown"),
                certainty=str(p.get("certainty") or "Unknown"),
                headline=str(p.get("headline") or "").strip(),
                area=str(p.get("areaDesc") or "").strip(),
                effective=str(p.get("effective") or ""),
                expires=str(p.get("expires") or ""),
                ends=str(p.get("ends") or ""),
                sender=str(p.get("senderName") or "NWS"),
            )
        )
    return alerts


def alerts(lat: float, lon: float) -> list[Alert]:
    def load():
        query = urllib.parse.urlencode({"point": f"{lat:.4f},{lon:.4f}"})
        return parse_alerts(_fetch(f"{NWS_ALERTS}?{query}", {"Accept": "application/geo+json"}))

    return _cached(f"cond:alerts:{_cell(lat, lon)}", TTL["alerts"], load)


# --------------------------------------------------------------------------- USGS gauges


@dataclass(frozen=True)
class Gauge:
    site: str
    name: str
    lat: float
    lon: float
    stage_ft: float | None
    stage_time: str
    discharge_cfs: float | None
    discharge_time: str


def parse_usgs(raw: bytes) -> list[Gauge]:
    body = _json(raw)
    try:
        series = body["value"]["timeSeries"]
    except (KeyError, TypeError) as exc:
        raise SourceFailed("answered without a list of gauges") from exc
    sites: dict[str, dict] = {}
    for ts in series:
        info = ts.get("sourceInfo") or {}
        site = ((info.get("siteCode") or [{}])[0]).get("value")
        geo = (info.get("geoLocation") or {}).get("geogLocation") or {}
        if not site or geo.get("latitude") is None:
            continue
        row = sites.setdefault(
            site,
            {
                "name": info.get("siteName") or site,
                "lat": float(geo["latitude"]),
                "lon": float(geo["longitude"]),
            },
        )
        code = (((ts.get("variable") or {}).get("variableCode") or [{}])[0]).get("value")
        no_data = (ts.get("variable") or {}).get("noDataValue")
        points = ((ts.get("values") or [{}])[0]).get("value") or []
        if not points:
            continue
        latest = points[-1]
        try:
            value = float(latest.get("value"))
        except (TypeError, ValueError):
            continue
        if no_data is not None and value == float(no_data):
            continue
        key = {"00065": "stage", "00060": "discharge"}.get(code)
        if key:
            row[key] = value
            row[f"{key}_time"] = latest.get("dateTime") or ""
    return [
        Gauge(
            site=s,
            name=r["name"],
            lat=r["lat"],
            lon=r["lon"],
            stage_ft=r.get("stage"),
            stage_time=r.get("stage_time", ""),
            discharge_cfs=r.get("discharge"),
            discharge_time=r.get("discharge_time", ""),
        )
        for s, r in sites.items()
        if "stage" in r or "discharge" in r
    ]


def usgs_gauges(lat: float, lon: float) -> list[Gauge]:
    west, south, east, north = bbox(lat, lon, RIVER_RADIUS_KM)

    def load():
        query = urllib.parse.urlencode(
            {
                "format": "json",
                "bBox": f"{west:.4f},{south:.4f},{east:.4f},{north:.4f}",
                "parameterCd": "00065,00060",
                "siteStatus": "active",
            }
        )
        return parse_usgs(_fetch(f"{USGS_IV}?{query}", {"Accept": "application/json"}))

    return _cached(f"cond:usgs:{_cell(lat, lon)}", TTL["usgs"], load)


# --------------------------------------------------------------------------- NWPS flood stage


@dataclass(frozen=True)
class FloodGauge:
    lid: str
    name: str
    lat: float
    lon: float
    observed_ft: float | None
    category: str
    valid_time: str
    stages: dict


FLOOD_WORDS = {
    "no_flooding": "below flood stage",
    "action": "at action stage (near flooding)",
    "minor": "in MINOR flood",
    "moderate": "in MODERATE flood",
    "major": "in MAJOR flood",
}


def parse_nwps_list(raw: bytes) -> list[dict]:
    body = _json(raw)
    gauges = body.get("gauges") if isinstance(body, dict) else None
    if not isinstance(gauges, list):
        raise SourceFailed("answered without a list of gauges")
    return gauges


def parse_nwps_stages(raw: bytes) -> dict:
    """{'action': 4.0, 'minor': 7.0, ...} in feet, only the stages NWS publishes (> 0)."""
    body = _json(raw)
    cats = ((body.get("flood") or {}).get("categories") or {}) if isinstance(body, dict) else {}
    stages = {}
    for name in ("action", "minor", "moderate", "major"):
        stage = (cats.get(name) or {}).get("stage")
        if isinstance(stage, int | float) and stage > 0:
            stages[name] = float(stage)
    return stages


def nwps_flood(lat: float, lon: float, limit: int = 3) -> list[FloodGauge]:
    west, south, east, north = bbox(lat, lon, RIVER_RADIUS_KM)

    def load_list():
        query = urllib.parse.urlencode(
            {
                "bbox.xmin": west,
                "bbox.ymin": south,
                "bbox.xmax": east,
                "bbox.ymax": north,
                "srid": "EPSG_4326",
            }
        )
        return parse_nwps_list(_fetch(f"{NWPS_GAUGES}?{query}", {"Accept": "application/json"}))

    rows = _cached(f"cond:nwps:{_cell(lat, lon)}", TTL["nwps"], load_list)
    rows = sorted(
        (g for g in rows if isinstance(g, dict) and g.get("latitude") is not None),
        key=lambda g: distance_km(lat, lon, g["latitude"], g["longitude"]),
    )[:limit]
    out = []
    for g in rows:
        lid = str(g.get("lid") or "")
        observed = ((g.get("status") or {}).get("observed")) or {}
        try:
            stages = _cached(
                f"cond:nwps_gauge:{lid}",
                TTL["nwps_gauge"],
                lambda lid=lid: parse_nwps_stages(_fetch(f"{NWPS_GAUGES}/{lid}")),
            )
        except SourceFailed:
            stages = {}
        primary = observed.get("primary")
        out.append(
            FloodGauge(
                lid=lid,
                name=str(g.get("name") or lid),
                lat=float(g["latitude"]),
                lon=float(g["longitude"]),
                observed_ft=float(primary)
                if isinstance(primary, int | float) and primary > -900
                else None,
                category=str(observed.get("floodCategory") or "unknown"),
                valid_time=str(observed.get("validTime") or ""),
                stages=stages,
            )
        )
    return out


# --------------------------------------------------------------------------- NHC storms


@dataclass(frozen=True)
class Storm:
    id: str
    name: str
    classification: str
    intensity_kt: int | None
    lat: float | None
    lon: float | None
    movement: str
    last_update: str


CLASSIFICATIONS = {
    "TD": "Tropical Depression",
    "TS": "Tropical Storm",
    "HU": "Hurricane",
    "MH": "Major Hurricane",
    "STD": "Subtropical Depression",
    "STS": "Subtropical Storm",
    "PTC": "Potential Tropical Cyclone",
    "PC": "Post-tropical Cyclone",
    "TY": "Typhoon",
}


def parse_storms(raw: bytes) -> list[Storm]:
    body = _json(raw)
    rows = body.get("activeStorms") if isinstance(body, dict) else None
    if not isinstance(rows, list):
        raise SourceFailed("answered without a list of storms")
    storms = []
    for s in rows:
        try:
            intensity = int(s.get("intensity")) if s.get("intensity") not in (None, "") else None
        except (TypeError, ValueError):
            intensity = None
        direction, speed = s.get("movementDir"), s.get("movementSpeed")
        movement = ""
        if isinstance(direction, int | float) and isinstance(speed, int | float):
            movement = f"moving {_deg_word(direction)} at {int(speed)} mph"
        storms.append(
            Storm(
                id=str(s.get("id") or ""),
                name=str(s.get("name") or "unnamed"),
                classification=CLASSIFICATIONS.get(str(s.get("classification")), "Storm"),
                intensity_kt=intensity,
                lat=s.get("latitudeNumeric"),
                lon=s.get("longitudeNumeric"),
                movement=movement,
                last_update=str(s.get("lastUpdate") or ""),
            )
        )
    return storms


def _deg_word(deg: float) -> str:
    words = ("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
    return words[int(((deg % 360) + 22.5) // 45) % 8]


def storms() -> list[Storm]:
    return _cached(
        "cond:storms",
        TTL["storms"],
        lambda: parse_storms(_fetch(NHC_STORMS, {"Accept": "application/json"})),
    )


# --------------------------------------------------------------------------- DriveTexas


@dataclass(frozen=True)
class Road:
    text: str
    lat: float
    lon: float


_ROAD_KEYS = (
    "CONDITION",
    "condition",
    "CNSTRNT_TYPE_CD",
    "TYPE",
    "type",
    "ROUTE_NAME",
    "route_name",
    "RDWAY_NM",
    "DESCRIPTION",
    "description",
    "COND_DSCR",
)


def _road_text(props: dict) -> str:
    picked = [str(props[k]).strip() for k in _ROAD_KEYS if props.get(k)]
    if not picked:
        picked = [str(v).strip() for v in props.values() if isinstance(v, str) and v.strip()]
    seen, words = set(), []
    for word in picked:
        if word not in seen:
            seen.add(word)
            words.append(word)
    return ", ".join(words)[:200] or "a reported condition"


def _first_point(geometry: dict) -> tuple[float, float] | None:
    coords = (geometry or {}).get("coordinates")
    while isinstance(coords, list) and coords and isinstance(coords[0], list):
        coords = coords[0]
    if isinstance(coords, list) and len(coords) >= 2:
        return float(coords[1]), float(coords[0])
    return None


def parse_roads(raw: bytes) -> list[Road]:
    body = _json(raw)
    features = body.get("features") if isinstance(body, dict) else None
    if not isinstance(features, list):
        raise SourceFailed("answered without a list of road conditions")
    roads = []
    for f in features:
        point = _first_point((f or {}).get("geometry") or {})
        if point is None:
            continue
        roads.append(Road(text=_road_text(f.get("properties") or {}), lat=point[0], lon=point[1]))
    return roads


def roads(lat: float, lon: float) -> list[Road]:
    key = os.environ.get(DRIVETEXAS_KEY_ENV, "").strip()
    if not key:
        raise NotConfigured(DRIVETEXAS_KEY_ENV)

    def load():
        query = urllib.parse.urlencode({"key": key})
        return parse_roads(_fetch(f"{DRIVETEXAS}?{query}", {"Accept": "application/geo+json"}))

    every = _cached("cond:roads", TTL["roads"], load)
    return [r for r in every if distance_km(lat, lon, r.lat, r.lon) <= ROAD_RADIUS_KM]


# --------------------------------------------------------------------------- FIRMS


@dataclass(frozen=True)
class Detection:
    lat: float
    lon: float
    acquired: str
    confidence: str
    frp_mw: float | None


def parse_firms(raw: bytes) -> list[Detection]:
    try:
        text = raw.decode("utf-8")
    except (AttributeError, UnicodeDecodeError) as exc:
        raise SourceFailed("answered with something that was not text") from exc
    if not text.startswith("latitude"):
        # FIRMS answers a bad key or a malformed request with a plain sentence, not CSV.
        raise SourceFailed(f"answered without detections ({text.strip()[:80]!r})")
    out = []
    for row in csv.DictReader(io.StringIO(text)):
        try:
            lat, lon = float(row["latitude"]), float(row["longitude"])
        except (KeyError, TypeError, ValueError):
            continue
        acq_time = (row.get("acq_time") or "").zfill(4)
        try:
            frp = float(row.get("frp") or "")
        except ValueError:
            frp = None
        out.append(
            Detection(
                lat=lat,
                lon=lon,
                acquired=f"{row.get('acq_date', '')} {acq_time[:2]}:{acq_time[2:]} UTC",
                confidence={"l": "low", "n": "nominal", "h": "high"}.get(
                    str(row.get("confidence") or "").lower(), str(row.get("confidence") or "")
                ),
                frp_mw=frp,
            )
        )
    return out


def fires(lat: float, lon: float) -> list[Detection]:
    key = os.environ.get(FIRMS_KEY_ENV, "").strip()
    if not key:
        raise NotConfigured(FIRMS_KEY_ENV)
    west, south, east, north = bbox(lat, lon, FIRE_RADIUS_KM)

    def load():
        area = f"{west},{south},{east},{north}"
        url = f"{FIRMS_AREA}/{urllib.parse.quote(key)}/VIIRS_SNPP_NRT/{area}/{FIRE_DAYS}"
        return parse_firms(_fetch(url))

    found = _cached(f"cond:fire:{_cell(lat, lon)}", TTL["fire"], load)
    return [d for d in found if distance_km(lat, lon, d.lat, d.lon) <= FIRE_RADIUS_KM]
