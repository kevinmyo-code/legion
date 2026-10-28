"""Summarise LIVE_LATENCY lines from a logcat capture of the phone's voice loop.

The phone writes these lines from GeminiLiveSession / LiveToolbox (see LiveLatency.kt):

    cold_start_ms=<n>                      start() to setupComplete
    reply_ms=<n>                           last user transcript chunk to first reply audio
    tool_ms=<n> tool=<name> sent=<bool> [async=true]
                                           toolCall received to toolResponse sent; any other
                                           trailing key=value field is ignored
    ask_engine_ms=<n> model=<id> result=<type>
    eot_ms=<n> eot_reason=<base|extended|cap|fallback> eot_longest_silence_ms=<n> st_checks=<n>
        st_scores=<score@asked>arrived,...>  silence before the phone's activityEnd (local end of turn);
                                           extended = a Smart Turn hold carried some silence of the
                                           turn past 700 ms; st_scores holds the last 4 checks only
    eot_reply_ms=<n>                       the phone's activityEnd to first reply audio
    st_score=<p> st_asked_at_ms=<n> st_arrive_ms=<n> st_feat_ms=<n> st_infer_ms=<n> ...
                                           one Smart Turn check; asked/arrived are ms of silence

Lookups (async-lookups ticket 03), one line per sub-agent call, printed in their own table and kept
out of the per-metric one:

    route_ms=<n> route_post_ms=<n> route_tools_ms=<n> route_tool_count=<n> route_tools=<a,b|none>
        tool=<ask_x> model=<id> result=<type>
                                           one ask_<domain> dispatcher: whole call, the router POST
                                           (a fallback retry included), the parallel tool fan-out
    investigate_ms=<n> rounds=<n> tool=<name> model=<id> result=<type>
                                           one investigate loop (ask_engine)
    subagent_fallback from=<model> to=<model> reason=<RateLimited|Overloaded> [tool=<name>]
                                           one POST retried on the fallback model

The Lookups table gives, per tool, count and p50/p95 of the total, the router POST and the fan-out
(fan-out over calls that ran at least one tool), and how many fallbacks fired. model= is the model
that answered: the fallback's when one fired. --csv appends the same figures after the per-metric
rows: lookup_total_ms / lookup_post_ms / lookup_tools_ms grouped by tool, and a subagent_fallbacks
count per tool.

Smart Turn may only extend a turn (Kevin, 2026-10-27): the first check starts at 192 ms of silence
and matters only if it lands before the 700 ms base. The table is followed by how often it did.

Capture on the host, then summarise:

    adb logcat -v threadtime -s LIVE_LATENCY > live.txt
    python tools/voice_latency.py live.txt
    python tools/voice_latency.py live.txt --csv > live.csv

Stdlib only. Self-test: python -m doctest tools/voice_latency.py

>>> sample = '''
... 10-27 16:20:01.100  1234  5678 I LIVE_LATENCY: cold_start_ms=900
... 10-27 16:20:05.200  1234  5678 I LIVE_LATENCY: reply_ms=1200
... 10-27 16:20:09.300  1234  5678 I LIVE_LATENCY: reply_ms=2400
... 10-27 16:20:09.000  1234  5679 I LIVE_LATENCY: tool_ms=800 tool=ask_fleet sent=true
... 10-27 16:20:19.000  1234  5679 I LIVE_LATENCY: tool_ms=1600 tool=ask_fleet sent=true
... 10-27 16:20:29.000  1234  5679 I LIVE_LATENCY: ask_engine_ms=3000 model=gemini-3.8-flash result=Success
... 10-27 16:20:30.000  1234  5679 I LIVE_FOLLOWUP: closed after silence
... 10-27 16:21:00.000  1234  5679 I LIVE_LATENCY: st_score=0.120 st_check=1 st_asked_at_ms=192 st_arrive_ms=480 st_queue_ms=0 st_feat_ms=40 st_infer_ms=250 frames=800
... 10-27 16:21:00.900  1234  5679 I LIVE_LATENCY: eot_ms=1100 eot_reason=extended eot_longest_silence_ms=1100 st_checks=2 st_scores=0.12@192>480,0.71@480>1100
... 10-27 16:21:05.000  1234  5679 I LIVE_LATENCY: st_score=0.900 st_check=3 st_asked_at_ms=192 st_arrive_ms=820 st_queue_ms=0 st_feat_ms=40 st_infer_ms=590 frames=800
... 10-27 16:21:05.100  1234  5679 I LIVE_LATENCY: eot_ms=704 eot_reason=base eot_longest_silence_ms=704 st_checks=1 st_scores=none
... 10-27 16:22:00.000  1234  5679 I LIVE_LATENCY: tool_ms=400 tool=ask_fleet sent=true async=true
... 10-27 16:22:10.000  1234  5679 I LIVE_LATENCY: route_ms=2000 route_post_ms=900 route_tools_ms=1000 route_tool_count=2 route_tools=get_codes,get_health tool=ask_fleet model=gemini-3.5-flash-lite result=Success
... 10-27 16:22:20.000  1234  5679 I LIVE_LATENCY: subagent_fallback from=gemini-3.5-flash-lite to=gemini-3.8-flash reason=RateLimited tool=ask_fleet
... 10-27 16:22:21.000  1234  5679 I LIVE_LATENCY: route_ms=3000 route_post_ms=2800 route_tools_ms=0 route_tool_count=0 route_tools=none tool=ask_fleet model=gemini-3.8-flash result=Success
... 10-27 16:22:30.000  1234  5679 I LIVE_LATENCY: investigate_ms=5200 rounds=3 tool=ask_engine model=gemini-3.5-flash-lite result=Success
... 10-27 16:22:40.000  1234  5679 I LIVE_LATENCY: subagent_fallback from=gemini-3.5-flash-lite to=gemini-3.8-flash reason=Overloaded
... '''.splitlines()
>>> rows = summarise(parse(sample))
>>> for r in rows: print(r)
('ask_engine_ms', 'gemini-3.8-flash', 1, 3000, 3000, 3000)
('cold_start_ms', '', 1, 900, 900, 900)
('eot_longest_silence_ms', '', 2, 704, 1100, 1100)
('eot_ms', 'base', 1, 704, 704, 704)
('eot_ms', 'extended', 1, 1100, 1100, 1100)
('reply_ms', '', 2, 1200, 2400, 2400)
('st_arrive_ms', '', 2, 480, 820, 820)
('st_asked_at_ms', '', 2, 192, 192, 192)
('st_feat_ms', '', 2, 40, 40, 40)
('st_infer_ms', '', 2, 250, 590, 590)
('st_queue_ms', '', 2, 0, 0, 0)
('tool_ms', 'ask_fleet', 3, 800, 1600, 1600)
>>> for r in lookups(sample): print(r)
('', 0, None, None, None, None, None, None, 1)
('ask_engine', 1, 5200, 5200, None, None, None, None, 0)
('ask_fleet', 2, 2000, 3000, 900, 2800, 1000, 1000, 1)
>>> fallback_count(sample)
2
>>> for r in lookup_csv_rows(sample): print(r)
('lookup_total_ms', 'ask_engine', 1, 5200, 5200, 5200)
('lookup_total_ms', 'ask_fleet', 2, 2000, 3000, 3000)
('lookup_post_ms', 'ask_fleet', 2, 900, 2800, 2800)
('lookup_tools_ms', 'ask_fleet', 1, 1000, 1000, 1000)
('subagent_fallbacks', '', 1, '', '', '')
('subagent_fallbacks', 'ask_fleet', 1, '', '', '')
>>> first_check_in_time(sample)
(1, 2)
>>> percentile([5, 1, 3, 2, 4], 50)
3
>>> percentile([], 95) is None
True
"""

import argparse
import csv
import math
import re
import sys
from collections import defaultdict

TAG = "LIVE_LATENCY"
_PAIR = re.compile(r"(\w+)=(\S+)")
# Which key groups each metric: tool_ms by tool name, ask_engine_ms by model.
GROUP_KEY = {"tool_ms": "tool", "ask_engine_ms": "model", "eot_ms": "eot_reason"}
# Lookup lines (ticket 03) go to the Lookups table only: route_tools_ms=0 on a call that ran no
# tool would drag a per-metric fan-out figure down.
LOOKUP_KEYS = {"route_ms", "route_post_ms", "route_tools_ms", "investigate_ms"}
FALLBACK = "subagent_fallback"
GATE_MS = 192
BASE_MS = 700


def parse(lines):
    """Yield (metric, group, ms) for every LIVE_LATENCY line; other lines are skipped."""
    for line in lines:
        if TAG not in line:
            continue
        body = line.split(TAG, 1)[1].lstrip(": ")
        pairs = dict(_PAIR.findall(body))
        for key, value in pairs.items():
            if not key.endswith("_ms") or key in LOOKUP_KEYS:
                continue
            try:
                ms = int(value)
            except ValueError:
                continue
            yield key, pairs.get(GROUP_KEY.get(key, ""), ""), ms


def first_check_in_time(lines):
    """(landed before the base, all first checks): first Smart Turn checks of a silence (asked at the
    192 ms gate) whose result arrived before the 700 ms base, i.e. could still extend the turn."""
    hit = total = 0
    for line in lines:
        if TAG not in line or "st_score=" not in line:
            continue
        pairs = dict(_PAIR.findall(line.split(TAG, 1)[1]))
        if pairs.get("st_asked_at_ms") != str(GATE_MS):
            continue
        total += 1
        hit += int(pairs.get("st_arrive_ms", "999999")) < BASE_MS
    return hit, total


def _lookup_pairs(lines):
    """(is_fallback, pairs) for every lookup or fallback LIVE_LATENCY line."""
    for line in lines:
        if TAG not in line:
            continue
        body = line.split(TAG, 1)[1].lstrip(": ")
        pairs = dict(_PAIR.findall(body))
        if body.startswith(FALLBACK):
            yield True, pairs
        elif "route_ms" in pairs or "investigate_ms" in pairs:
            yield False, pairs


def _int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _lookup_samples(lines):
    """(total, post, fan, count, fallbacks), each keyed by tool. Fan-out samples only from calls
    that ran at least one tool."""
    total, post, fan = defaultdict(list), defaultdict(list), defaultdict(list)
    count, fallbacks = defaultdict(int), defaultdict(int)
    for is_fallback, pairs in _lookup_pairs(lines):
        tool = pairs.get("tool", "")
        if is_fallback:
            fallbacks[tool] += 1
            continue
        count[tool] += 1
        ms = _int(pairs.get("route_ms", pairs.get("investigate_ms")))
        if ms is not None:
            total[tool].append(ms)
        post_ms = _int(pairs.get("route_post_ms"))
        if post_ms is not None:
            post[tool].append(post_ms)
        fan_ms = _int(pairs.get("route_tools_ms"))
        if fan_ms is not None and (_int(pairs.get("route_tool_count")) or 0) > 0:
            fan[tool].append(fan_ms)
    return total, post, fan, count, fallbacks


def lookups(lines):
    """Rows of (tool, count, total p50, total p95, post p50, post p95, fan-out p50, fan-out p95,
    fallbacks), sorted by tool. A fallback line with no tool= counts under tool ''. None where a
    figure has no samples: investigate has no router POST or fan-out of its own."""
    total, post, fan, count, fallbacks = _lookup_samples(lines)
    return [
        (tool, count[tool],
         percentile(total[tool], 50), percentile(total[tool], 95),
         percentile(post[tool], 50), percentile(post[tool], 95),
         percentile(fan[tool], 50), percentile(fan[tool], 95),
         fallbacks[tool])
        for tool in sorted(set(count) | set(fallbacks))
    ]


def lookup_csv_rows(lines):
    """The Lookups table in the per-metric CSV shape (metric, group, count, p50, p95, max), group
    being the tool: lookup_total_ms, lookup_post_ms and lookup_tools_ms per tool that has samples,
    then one subagent_fallbacks row per tool that fell back (count only)."""
    total, post, fan, _, fallbacks = _lookup_samples(lines)
    rows = []
    for metric, buckets in (("lookup_total_ms", total), ("lookup_post_ms", post), ("lookup_tools_ms", fan)):
        for tool in sorted(buckets):
            v = buckets[tool]
            rows.append((metric, tool, len(v), percentile(v, 50), percentile(v, 95), max(v)))
    for tool in sorted(fallbacks):
        rows.append(("subagent_fallbacks", tool, fallbacks[tool], "", "", ""))
    return rows


def fallback_count(lines):
    """How many sub-agent POSTs were retried on the fallback model."""
    return sum(1 for is_fallback, _ in _lookup_pairs(lines) if is_fallback)


def percentile(values, p):
    """Nearest-rank percentile, or None for no values."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(p / 100 * len(ordered)))
    return ordered[rank - 1]


def summarise(samples):
    """Rows of (metric, group, count, p50, p95, max), sorted by metric then group."""
    buckets = defaultdict(list)
    for metric, group, ms in samples:
        buckets[(metric, group)].append(ms)
    return [
        (metric, group, len(v), percentile(v, 50), percentile(v, 95), max(v))
        for (metric, group), v in sorted(buckets.items())
    ]


HEADER = ("metric", "group", "count", "p50_ms", "p95_ms", "max_ms")
LOOKUP_HEADER = ("tool", "count", "total_p50", "total_p95", "post_p50", "post_p95", "fan_p50", "fan_p95",
                 "fallbacks")


def main(argv=None):
    ap = argparse.ArgumentParser(description="Summarise LIVE_LATENCY lines from a logcat capture.")
    ap.add_argument("logfile", help="logcat capture; '-' reads stdin")
    ap.add_argument("--csv", action="store_true", help="print CSV instead of a table")
    args = ap.parse_args(argv)

    if args.logfile == "-":
        lines = sys.stdin.readlines()
    else:
        with open(args.logfile, encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    rows = summarise(parse(lines))
    hit, total = first_check_in_time(lines)
    lookup_rows = lookups(lines)

    if args.csv:
        w = csv.writer(sys.stdout, lineterminator="\n")
        w.writerow(HEADER)
        w.writerows(rows)
        w.writerows(lookup_csv_rows(lines))
        return 0
    if not rows and not lookup_rows:
        print("no LIVE_LATENCY lines found")
        return 1
    print("{:<15} {:<24} {:>6} {:>8} {:>8} {:>8}".format(*HEADER))
    for r in rows:
        print("{:<15} {:<24} {:>6} {:>8} {:>8} {:>8}".format(*r))
    if total:
        print(f"first Smart Turn check landed before the {BASE_MS} ms base: {hit}/{total}")
    if lookup_rows:
        print()
        print("Lookups")
        fmt = "{:<16} {:>6} {:>9} {:>9} {:>8} {:>8} {:>8} {:>8} {:>9}"
        print(fmt.format(*LOOKUP_HEADER))
        for r in lookup_rows:
            print(fmt.format(r[0] or "(none)", *("-" if v is None else v for v in r[1:])))
        print(f"fallbacks to the fallback model: {fallback_count(lines)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
