"""`check_conditions`' answer: one section per kind, each naming its source and its time.

**Every section is one of four shapes, never blurred** (CLAUDE.md section 1, "unreadable and
empty are different sentences"): what the source reports; that the source was read and reports
nothing here; that it could not be read, so nothing is known; or that it is not set up on this
engine (a key is missing). A section is never left out silently.

Times keep the offset the source sent ("until 6:00 PM, UTC-6"); a time sent in UTC is said in
UTC. No zone name ever reaches the model (CLAUDE.md section 1).
"""

from __future__ import annotations

from dataclasses import asdict
from datetime import datetime

from conditions import sources

KINDS = ("alerts", "rivers", "storms", "roads", "fire")
MAX_ALERTS = 5
MAX_GAUGES = 3
MAX_ROADS = 5
STORM_NEAR_KM = 1500.0


def _clock(iso: str) -> str:
    """'Nov 10 6:00 PM, UTC-6' from an ISO time with its own offset; '' when unreadable."""
    if not iso:
        return ""
    try:
        moment = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    except ValueError:
        return ""
    offset = moment.utcoffset()
    if offset is None:
        zone = "UTC"
    else:
        minutes = int(offset.total_seconds() // 60)
        if minutes == 0:
            zone = "UTC"
        else:
            sign = "+" if minutes > 0 else "-"
            hours, rest = divmod(abs(minutes), 60)
            zone = f"UTC{sign}{hours}" + (f":{rest:02d}" if rest else "")
    hour = moment.strftime("%I").lstrip("0") or "12"
    return f"{moment.strftime('%b')} {moment.day} {hour}:{moment.strftime('%M %p')}, {zone}"


def _miles(km: float) -> str:
    miles = km / sources.KM_PER_MILE
    return f"{miles:.1f} mi" if miles < 10 else f"{miles:.0f} mi"


def _section(kind: str, status: str, text: str, rows: list | None = None) -> dict:
    return {"kind": kind, "status": status, "text": text, "rows": rows or []}


def _alerts(lat: float, lon: float, severe_only: bool) -> dict:
    try:
        found = sources.alerts(lat, lon)
    except sources.SourceFailed as exc:
        return _section(
            "alerts",
            "failed",
            f"NWS alerts: the National Weather Service {exc}, so whether any alert is active here "
            f"is NOT known.",
        )
    if severe_only:
        found = [a for a in found if sources.is_severe(a)]
    if not found:
        words = (
            "no severe or extreme alert and no hurricane watch or warning"
            if severe_only
            else "no active alerts"
        )
        return _section(
            "alerts",
            "empty",
            f"NWS alerts: the National Weather Service lists {words} for this point right now.",
        )
    parts = []
    for a in found[:MAX_ALERTS]:
        until = _clock(a.ends or a.expires)
        parts.append(
            f"{a.event} ({a.severity}"
            + (f", until {until}" if until else "")
            + f") from {a.sender}"
            + (f": {a.headline}" if a.headline else "")
        )
    more = f" And {len(found) - MAX_ALERTS} more." if len(found) > MAX_ALERTS else ""
    return _section(
        "alerts",
        "found",
        "NWS alerts, active now for this point: " + "; ".join(parts) + "." + more,
        [asdict(a) | {"severe": sources.is_severe(a)} for a in found],
    )


def _rivers(lat: float, lon: float) -> dict:
    words, rows, failures = [], {"gauges": [], "flood": []}, []
    try:
        gauges = sorted(
            sources.usgs_gauges(lat, lon),
            key=lambda g: sources.distance_km(lat, lon, g.lat, g.lon),
        )[:MAX_GAUGES]
    except sources.SourceFailed as exc:
        gauges = None
        failures.append(f"USGS {exc}")
    try:
        flood = sources.nwps_flood(lat, lon, MAX_GAUGES)
    except sources.SourceFailed as exc:
        flood = None
        failures.append(f"the NWS water service {exc}")
    if gauges:
        for g in gauges:
            km = sources.distance_km(lat, lon, g.lat, g.lon)
            bits = []
            if g.stage_ft is not None:
                bits.append(f"gauge height {g.stage_ft:.2f} ft")
            if g.discharge_cfs is not None:
                bits.append(f"flow {g.discharge_cfs:,.0f} cubic feet per second")
            when = _clock(g.stage_time or g.discharge_time)
            words.append(
                f"{g.name}, {_miles(km)} {sources.compass_word(lat, lon, g.lat, g.lon)}: "
                + ", ".join(bits)
                + (f" (USGS, {when}, provisional)" if when else " (USGS)")
            )
            rows["gauges"].append(asdict(g) | {"distance_km": round(km, 2)})
    if flood:
        for f in flood:
            km = sources.distance_km(lat, lon, f.lat, f.lon)
            state = sources.FLOOD_WORDS.get(f.category, f"flood status {f.category}")
            stage = f" at {f.observed_ft:.1f} ft" if f.observed_ft is not None else ""
            limits = (
                " (flood stages: " + ", ".join(f"{k} {v:g} ft" for k, v in f.stages.items()) + ")"
                if f.stages
                else " (NWS publishes no flood stage for it)"
            )
            when = _clock(f.valid_time)
            words.append(
                f"{f.name}, {_miles(km)}: {state}{stage}{limits}"
                + (f" (NWS, {when})" if when else " (NWS)")
            )
            rows["flood"].append(asdict(f) | {"distance_km": round(km, 2)})
    if not words and not failures:
        return _section(
            "rivers",
            "empty",
            f"Rivers: USGS and NWS list no active stream gauge within "
            f"{_miles(sources.RIVER_RADIUS_KM)} of this point.",
        )
    if not words:
        return _section(
            "rivers",
            "failed",
            "Rivers: " + "; ".join(failures) + ", so river levels here are NOT known.",
        )
    note = (" Not read: " + "; ".join(failures) + ".") if failures else ""
    return _section("rivers", "found", "Rivers near here: " + "; ".join(words) + "." + note, [rows])


def _storms(lat: float, lon: float) -> dict:
    try:
        found = sources.storms()
    except sources.SourceFailed as exc:
        return _section(
            "storms",
            "failed",
            f"Tropical storms: the National Hurricane Center {exc}, so active storms are NOT "
            f"known.",
        )
    if not found:
        return _section(
            "storms",
            "empty",
            "Tropical storms: the National Hurricane Center tracks no active storm right now.",
        )
    parts, rows = [], []
    for s in found:
        far = ""
        km = None
        if s.lat is not None and s.lon is not None:
            km = sources.distance_km(lat, lon, s.lat, s.lon)
            far = f", {_miles(km)} {sources.compass_word(lat, lon, s.lat, s.lon)} of here"
        wind = f", {round(s.intensity_kt * 1.15078)} mph winds" if s.intensity_kt else ""
        move = f", {s.movement}" if s.movement else ""
        when = _clock(s.last_update)
        parts.append(
            f"{s.classification} {s.name}{wind}{far}{move}" + (f" ({when})" if when else "")
        )
        rows.append(asdict(s) | {"distance_km": round(km, 1) if km is not None else None})
    near = any(r["distance_km"] is not None and r["distance_km"] <= STORM_NEAR_KM for r in rows)
    tail = "" if near else " None is within 900 miles of this point."
    return _section(
        "storms",
        "found",
        "Tropical storms the National Hurricane Center tracks now: "
        + "; ".join(parts)
        + "."
        + tail,
        rows,
    )


def _roads(lat: float, lon: float) -> dict:
    try:
        found = sources.roads(lat, lon)
    except sources.NotConfigured as exc:
        return _section(
            "roads",
            "not_configured",
            f"Road closures: DriveTexas is not set up on this engine (it needs {exc.env}), so "
            f"road closures and flooded roads are NOT known from here.",
        )
    except sources.SourceFailed as exc:
        return _section(
            "roads",
            "failed",
            f"Road closures: DriveTexas {exc}, so road closures here are NOT known.",
        )
    if not found:
        return _section(
            "roads",
            "empty",
            f"Road closures: DriveTexas lists nothing within {_miles(sources.ROAD_RADIUS_KM)} of "
            f"this point (Texas roads only).",
        )
    ranked = sorted(found, key=lambda r: sources.distance_km(lat, lon, r.lat, r.lon))
    parts = [
        f"{r.text} ({_miles(sources.distance_km(lat, lon, r.lat, r.lon))})"
        for r in ranked[:MAX_ROADS]
    ]
    more = f" And {len(found) - MAX_ROADS} more." if len(found) > MAX_ROADS else ""
    return _section(
        "roads",
        "found",
        "Road conditions from DriveTexas near here: " + "; ".join(parts) + "." + more,
        [asdict(r) for r in ranked],
    )


def _fire(lat: float, lon: float) -> dict:
    try:
        found = sources.fires(lat, lon)
    except sources.NotConfigured as exc:
        return _section(
            "fire",
            "not_configured",
            f"Satellite fire detections: NASA FIRMS is not set up on this engine (it needs "
            f"{exc.env}), so they are NOT known from here.",
        )
    except sources.SourceFailed as exc:
        return _section(
            "fire",
            "failed",
            f"Satellite fire detections: NASA FIRMS {exc}, so they are NOT known.",
        )
    if not found:
        return _section(
            "fire",
            "empty",
            f"Satellite fire detections: NASA FIRMS shows no heat detection within "
            f"{_miles(sources.FIRE_RADIUS_KM)} in the last {sources.FIRE_DAYS} days.",
        )
    nearest = min(found, key=lambda d: sources.distance_km(lat, lon, d.lat, d.lon))
    km = sources.distance_km(lat, lon, nearest.lat, nearest.lon)
    return _section(
        "fire",
        "found",
        f"Satellite fire detections from NASA FIRMS (VIIRS), last {sources.FIRE_DAYS} days: "
        f"{len(found)} within {_miles(sources.FIRE_RADIUS_KM)}; the nearest is {_miles(km)} "
        f"{sources.compass_word(lat, lon, nearest.lat, nearest.lon)}, seen {nearest.acquired}, "
        f"{nearest.confidence} confidence. A detection is a hot spot seen from orbit, not a "
        f"confirmed wildfire: say so.",
        [asdict(d) for d in found],
    )


SECTIONS = {
    "alerts": _alerts,
    "rivers": _rivers,
    "storms": _storms,
    "roads": _roads,
    "fire": _fire,
}


def check(lat: float, lon: float, kinds: list[str], where: str, severe_only: bool = False):
    """(text, structured) for `kinds` at the point. `where` names the point in words."""
    sections = []
    for kind in kinds:
        if kind == "alerts":
            sections.append(_alerts(lat, lon, severe_only))
        else:
            sections.append(SECTIONS[kind](lat, lon))
    text = f"Conditions at {where}, read just now. " + " ".join(s["text"] for s in sections)
    return text, {"where": where, "lat": lat, "lon": lon, "sections": sections}
