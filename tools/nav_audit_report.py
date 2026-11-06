r"""Summarise LEGION's navigation from its NAV_AUDIT lines: how good search and routing are, in numbers.

Read-only. Takes an `adb logcat` capture or a conversation batch downloaded from the engine
(`manage.py device_logs --reason conversation`, raw, not --render) and prints:

    search success rate, zero-result queries, p50/p95 latency per source, picked-rank distribution,
    route request latency, reroute rate, ETA error at arrival, and turn-cue counts.

Capture on the host (the conversation log's logcat switch must be on, or read the engine batch):

    adb logcat -v threadtime -s NAV_AUDIT > nav.txt
    python tools/nav_audit_report.py nav.txt
    python tools/nav_audit_report.py nav.txt --json

The phone writes these lines from `navigation/audit/NavAudit.kt`; change both together. One line per
event, `<event> key=value ...`, the one free-text field last and JSON-quoted:

    search id= origin=<voice|typed|tap|unknown> along= verdict=<resolved|ambiguous|not_found|no_address|empty>
        source=<kind|none> results= ms= tried=<kind:ms:results:outcome,...|none> top=<json> query="..."
    pick search=<id> rank=<n, 0 = dismissed> how=<voice|tap|dismissed> source=
    resolve tool= slot= verdict=<go|ask|not_found|choice_ok|choice_out_of_range|cached> search= candidates=
        choice= source= reason="..."
    route_request id= purpose=<preview|change> trip= result=<ok|empty|failed|canceled> fail= ms= routes=
        alternatives= distance_m= eta_s= typical_s= profile= traffic=<true|false|unknown> router= stops=
        avoid= from=<lat,lng 3dp> to=<lat,lng 3dp> to_src= crow_m= message="..."
    trip_start trip= route= route_index= eta_s= distance_m= typical_s= stops= avoid= profile= traffic=
    trip_start trip=none result=failed message="..."
    route_update trip= reason=<reroute|refresh|alternative|new|...> routes= eta_s= distance_m= elapsed_s=
    reroute trip= state=fetching reason=<off_route|other>
    reroute trip= state=<fetched|failed|interrupted> ms= message="..."
    off_route trip= state=<true|false> elapsed_s=
    arrival trip= elapsed_s= first_eta_s= error_s= error_pct=
    trip_end trip= reason=<arrived|cancelled|token> elapsed_s= distance_left_m= reroutes= off_routes=
        cues_spoken= cues_dropped= cue_drops=<reason:n,...|none>

What the numbers mean:

- A search SUCCEEDS when it resolved or offered places to choose from; `empty` (nothing said) is left
  out. A calendar or contact match whose address was geocoded counts that Mapbox call in its own
  source's latency, not in `search`'s.
- Picked rank: an unambiguous search is rank 1 (auto). An ambiguous one is the rank of its `pick`
  line, `dismissed` when the user backed out, `abandoned` with no pick at all (a voice read-back
  that never got its yes).
- A trip with a `trip_start` and no `trip_end` is `unknown`: the process died, or the capture ended.
- ETA error is `elapsed - first ETA` at arrival, so positive means later than first promised.
- Mapbox search result names never appear (Mapbox ToS 2.7.2); `top=` carries only first-party labels.

Stdlib only. Self-test: python -m doctest tools/nav_audit_report.py

>>> sample = r'''
... 11-06 08:00:00.000  1  2 I NAV_AUDIT: search id=sa0 origin=voice along=false verdict=ambiguous source=search results=2 ms=900 tried=saved_place:3:0:no_match,search:880:2:hits top=[{"r":1,"src":"search","kind":"poi","m":1200},{"r":2,"src":"search","kind":"poi","m":5000}] query="the Starbucks near work"
... 11-06 08:00:05.000  1  2 I NAV_AUDIT: pick search=sa0 rank=2 how=voice source=search
... 11-06 08:00:06.000  1  2 I NAV_AUDIT: route_request id=rb0 purpose=preview trip=none result=ok fail=none ms=650 routes=3 alternatives=2 distance_m=9000 eta_s=900 typical_s=850 profile=mapbox/driving-traffic traffic=true router=online stops=0 avoid=none from=29.700,-95.400 to=29.750,-95.450 to_src=search crow_m=7000 message=""
... 11-06 08:00:07.000  1  2 I NAV_AUDIT: trip_start trip=tc0 route=r#0 route_index=1 eta_s=900 distance_m=9000 typical_s=850 stops=0 avoid=none profile=mapbox/driving-traffic traffic=true
... 11-06 08:05:00.000  1  2 I NAV_AUDIT: reroute trip=tc0 state=fetching reason=off_route
... 11-06 08:05:01.000  1  2 I NAV_AUDIT: reroute trip=tc0 state=fetched ms=700 message="ONLINE"
... 11-06 08:16:00.000  1  2 I NAV_AUDIT: arrival trip=tc0 elapsed_s=1000 first_eta_s=900 error_s=100 error_pct=11.1
... 11-06 08:16:00.000  1  2 I NAV_AUDIT: trip_end trip=tc0 reason=arrived elapsed_s=1000 distance_left_m=none reroutes=1 off_routes=1 cues_spoken=12 cues_dropped=1 cue_drops=superseded:1
... 11-06 08:20:00.000  1  2 I NAV_AUDIT: search id=sd0 origin=typed along=false verdict=not_found source=none results=0 ms=410 tried=saved_place:2:0:no_match,search:400:0:no_match top=[] query="zzyzx rd"
... '''.strip().splitlines()
>>> r = report(parse(sample))
>>> r["search"]["success_rate"], r["search"]["zero_result"]
(0.5, [('zzyzx rd', 1)])
>>> r["search"]["picked_rank"]
{'2': 1}
>>> r["latency"]["search"]["p50"], r["routes"]["latency"]["p50"], r["trips"]["eta_error_s"]["mean"]
(400, 650, 100.0)
>>> r["trips"]["reroutes_per_trip"], r["trips"]["ends"]
(1.0, {'arrived': 1})
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from collections import Counter, defaultdict

TAG = "NAV_AUDIT"
_MARK = re.compile(r"\bNAV_AUDIT\s*:\s?")
_FIELD = re.compile(r'(\w+)=("(?:[^"\\]|\\.)*"|\S+)')
_STAMP = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})")
EVENTS = frozenset({
    "search", "pick", "resolve", "route_request", "trip_start", "route_update", "reroute", "off_route",
    "arrival", "trip_end",
})


def parse_line(line):
    r"""One NAV_AUDIT line to {"event", "at", ...fields}, or None for any other line.

    A logcat or spooled line must carry the NAV_AUDIT tag; a line with no logcat prefix is read as a
    bare message body. Either way the first word must be a known event.

    >>> parse_line('11-06 08:00:00.000 1 2 I NAV_AUDIT: pick search=s1 rank=2 how=tap source=search')["rank"]
    '2'
    >>> parse_line('resolve tool=navigate verdict=ask reason="a \\"b\\" c"')["reason"]
    'a "b" c'
    >>> parse_line('11-06 08:00:00.000 1 2 I LIVE_AUDIT: turn_complete') is None
    True
    >>> parse_line('11-06 08:00:00.000 1 2 W MapboxNavController: search failed=x') is None
    True
    """
    m = _MARK.search(line)
    if m:
        body = line[m.end():]
    elif not _STAMP.match(line):
        body = line
    else:
        return None
    body = body.strip()
    event = body.split(" ", 1)[0] if body else ""
    if event not in EVENTS:
        return None
    out = {"event": event, "at": None}
    stamp = _STAMP.match(line)
    if stamp:
        out["at"] = stamp.group(1)
    for key, value in _FIELD.findall(body):
        if value.startswith('"'):
            try:
                value = json.loads(value)
            except ValueError:
                value = value[1:-1]
        out[key] = value
    return out


def parse(lines):
    """Every NAV_AUDIT event in [lines], in order."""
    return [e for e in (parse_line(l.rstrip("\n")) for l in lines) if e]


def _num(v):
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(f):
        return None
    return int(f) if f.is_integer() else f


def percentile(values, p):
    """Nearest-rank percentile; None for no values.

    >>> percentile([10, 20, 30, 40], 50), percentile([10, 20, 30, 40], 95), percentile([], 50)
    (20, 40, None)
    """
    vals = sorted(values)
    if not vals:
        return None
    k = max(1, math.ceil(p / 100 * len(vals)))
    return vals[k - 1]


def _stats(values):
    vals = [v for v in values if v is not None]
    if not vals:
        return {"n": 0, "p50": None, "p95": None, "mean": None}
    return {
        "n": len(vals),
        "p50": percentile(vals, 50),
        "p95": percentile(vals, 95),
        "mean": round(sum(vals) / len(vals), 1),
    }


def _searches(events):
    searches = [e for e in events if e["event"] == "search"]
    picks = defaultdict(list)
    for e in events:
        if e["event"] == "pick":
            picks[e.get("search")].append(e)
    counted = [s for s in searches if s.get("verdict") != "empty"]
    ok = [s for s in counted if s.get("verdict") in ("resolved", "ambiguous")]
    zero = Counter(s.get("query", "") for s in counted if s.get("verdict") in ("not_found", "no_address"))
    ranks = Counter()
    for s in counted:
        verdict = s.get("verdict")
        if verdict == "resolved":
            ranks["1"] += 1
        elif verdict == "ambiguous":
            got = picks.get(s.get("id"))
            if not got:
                ranks["abandoned"] += 1
            else:
                last = got[-1]
                ranks["dismissed" if last.get("rank") == "0" else str(last.get("rank"))] += 1
    by_origin = Counter(s.get("origin", "unknown") for s in counted)
    winners = Counter(s.get("source", "none") for s in ok)
    return {
        "total": len(counted),
        "success_rate": round(len(ok) / len(counted), 3) if counted else None,
        "ambiguous_rate": round(sum(1 for s in counted if s.get("verdict") == "ambiguous") / len(counted), 3)
        if counted else None,
        "zero_result": sorted(zero.items(), key=lambda kv: (-kv[1], kv[0])),
        "picked_rank": dict(sorted(ranks.items())),
        "by_origin": dict(sorted(by_origin.items())),
        "answered_by": dict(sorted(winners.items())),
        "total_ms": _stats(_num(s.get("ms")) for s in counted),
    }


def _source_latency(events):
    per = defaultdict(list)
    hits = Counter()
    asked = Counter()
    for s in events:
        if s["event"] != "search" or s.get("tried", "none") == "none":
            continue
        for part in s["tried"].split(","):
            bits = part.split(":")
            if len(bits) < 4:
                continue
            kind, ms, _results, outcome = bits[0], _num(bits[1]), bits[2], bits[3]
            per[kind].append(int(ms) if ms is not None else None)
            asked[kind] += 1
            if outcome == "hits":
                hits[kind] += 1
    out = {}
    for kind in sorted(per):
        st = _stats(per[kind])
        st["hit_rate"] = round(hits[kind] / asked[kind], 3) if asked[kind] else None
        out[kind] = st
    return out


def _routes(events):
    reqs = [e for e in events if e["event"] == "route_request"]
    ok = [r for r in reqs if r.get("result") == "ok"]
    fails = Counter(r.get("fail", "none") for r in reqs if r.get("result") == "failed")
    by_purpose = {}
    for purpose in sorted({r.get("purpose", "?") for r in reqs}):
        by_purpose[purpose] = _stats(_num(r.get("ms")) for r in reqs if r.get("purpose") == purpose)
    return {
        "total": len(reqs),
        "success_rate": round(len(ok) / len(reqs), 3) if reqs else None,
        "latency": _stats(_num(r.get("ms")) for r in reqs if r.get("result") != "canceled"),
        "latency_by_purpose": by_purpose,
        "failures": dict(sorted(fails.items())),
        "traffic_used": dict(sorted(Counter(r.get("traffic", "unknown") for r in ok).items())),
        "alternatives": dict(sorted(Counter(r.get("alternatives", "0") for r in ok).items())),
        "offline_router": sum(1 for r in ok if r.get("router") == "offline"),
    }


def _trips(events):
    started = [e for e in events if e["event"] == "trip_start" and e.get("trip", "none") != "none"]
    failed_starts = sum(1 for e in events if e["event"] == "trip_start" and e.get("trip") == "none")
    ends = {e.get("trip"): e for e in events if e["event"] == "trip_end"}
    arrivals = [e for e in events if e["event"] == "arrival"]
    reasons = Counter(ends[t["trip"]].get("reason", "?") if t["trip"] in ends else "unknown" for t in started)
    ended = [ends[t["trip"]] for t in started if t["trip"] in ends]
    reroutes = sum(int(_num(e.get("reroutes")) or 0) for e in ended)
    hours = sum((_num(e.get("elapsed_s")) or 0) for e in ended) / 3600
    fetches = [e for e in events if e["event"] == "reroute" and e.get("state") != "fetching"]
    spoken = sum(int(_num(e.get("cues_spoken")) or 0) for e in ended)
    dropped = Counter()
    for e in ended:
        for part in (e.get("cue_drops") or "none").split(","):
            if ":" in part:
                k, n = part.split(":", 1)
                dropped[k] += int(_num(n) or 0)
    errs = [_num(a.get("error_s")) for a in arrivals]
    errs = [e for e in errs if e is not None]
    pcts = [_num(a.get("error_pct")) for a in arrivals]
    return {
        "started": len(started),
        "failed_starts": failed_starts,
        "ends": dict(sorted(reasons.items())),
        "reroutes": reroutes,
        "reroutes_per_trip": round(reroutes / len(ended), 2) if ended else None,
        "reroutes_per_hour": round(reroutes / hours, 2) if hours > 0 else None,
        "off_route_reroutes": sum(1 for e in events if e["event"] == "reroute" and e.get("reason") == "off_route"),
        "reroute_fetch": _stats(_num(e.get("ms")) for e in fetches),
        "reroute_failed": sum(1 for e in fetches if e.get("state") == "failed"),
        "refreshes": sum(1 for e in events if e["event"] == "route_update" and e.get("reason") == "refresh"),
        "eta_error_s": {**_stats(errs), "abs_p95": percentile([abs(e) for e in errs], 95)},
        "eta_error_pct": _stats(pcts),
        "cues": {"spoken": spoken, "dropped": dict(sorted(dropped.items()))},
    }


def report(events):
    """Every metric, as a dict (what --json prints)."""
    return {
        "events": len(events),
        "search": _searches(events),
        "latency": _source_latency(events),
        "routes": _routes(events),
        "trips": _trips(events),
    }


def _fmt(v, unit=""):
    return "-" if v is None else f"{v}{unit}"


def _pct(v):
    return "-" if v is None else f"{v * 100:.1f}%"


def render(r):
    """The report as text lines."""
    s, rt, t = r["search"], r["routes"], r["trips"]
    out = [f"NAV_AUDIT events: {r['events']}", ""]
    out.append(f"SEARCH  {s['total']} searches  success {_pct(s['success_rate'])}  "
               f"ambiguous {_pct(s['ambiguous_rate'])}  total p50 {_fmt(s['total_ms']['p50'], ' ms')} "
               f"p95 {_fmt(s['total_ms']['p95'], ' ms')}")
    out.append(f"  by origin: {s['by_origin'] or '-'}   answered by: {s['answered_by'] or '-'}")
    out.append(f"  picked rank: {s['picked_rank'] or '-'}")
    out.append(f"  zero-result queries ({sum(n for _, n in s['zero_result'])}):")
    out += [f"    {n}x  {q!r}" for q, n in s["zero_result"]] or ["    none"]
    out.append("")
    out.append("SOURCE LATENCY        n     p50     p95    hit rate")
    for kind, st in r["latency"].items():
        out.append(f"  {kind:<16}{st['n']:>5}{_fmt(st['p50']):>8}{_fmt(st['p95']):>8}    {_pct(st['hit_rate'])}")
    if not r["latency"]:
        out.append("  none")
    out.append("")
    lat = rt["latency"]
    out.append(f"ROUTES  {rt['total']} requests  success {_pct(rt['success_rate'])}  "
               f"p50 {_fmt(lat['p50'], ' ms')}  p95 {_fmt(lat['p95'], ' ms')}")
    for purpose, st in rt["latency_by_purpose"].items():
        out.append(f"  {purpose}: n={st['n']} p50 {_fmt(st['p50'], ' ms')} p95 {_fmt(st['p95'], ' ms')}")
    out.append(f"  failures: {rt['failures'] or '-'}  traffic used: {rt['traffic_used'] or '-'}  "
               f"alternatives: {rt['alternatives'] or '-'}  offline router: {rt['offline_router']}")
    out.append("")
    e = t["eta_error_s"]
    out.append(f"TRIPS   {t['started']} started ({t['failed_starts']} failed to start)  ends: {t['ends'] or '-'}")
    out.append(f"  reroutes {t['reroutes']}  per trip {_fmt(t['reroutes_per_trip'])}  per hour "
               f"{_fmt(t['reroutes_per_hour'])}  after off-route {t['off_route_reroutes']}  "
               f"failed {t['reroute_failed']}  fetch p50 {_fmt(t['reroute_fetch']['p50'], ' ms')}")
    out.append(f"  live re-ETA refreshes: {t['refreshes']}")
    out.append(f"  ETA error at arrival (s, + is late): n={e['n']} mean {_fmt(e['mean'])} p50 {_fmt(e['p50'])} "
               f"|p95| {_fmt(e['abs_p95'])}   pct mean {_fmt(t['eta_error_pct']['mean'], '%')}")
    out.append(f"  turn cues: spoken {t['cues']['spoken']}  dropped {t['cues']['dropped'] or '-'}")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("files", nargs="*", help="logcat captures or downloaded batches; stdin when none")
    ap.add_argument("--json", action="store_true", help="print the metrics as JSON")
    args = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    lines = []
    if args.files:
        for path in args.files:
            with open(path, encoding="utf-8", errors="replace") as fh:
                lines += fh.readlines()
    else:
        lines = sys.stdin.readlines()
    r = report(parse(lines))
    if args.json:
        print(json.dumps(r, indent=2, ensure_ascii=False))
    else:
        print("\n".join(render(r)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
