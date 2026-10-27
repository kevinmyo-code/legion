"""Summarise LIVE_LATENCY lines from a logcat capture of the phone's voice loop.

The phone writes these lines from GeminiLiveSession / LiveToolbox (see LiveLatency.kt):

    cold_start_ms=<n>                      start() to setupComplete
    reply_ms=<n>                           last user transcript chunk to first reply audio
    tool_ms=<n> tool=<name> sent=<bool>    toolCall received to toolResponse sent
    ask_engine_ms=<n> model=<id> result=<type>

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
... '''.splitlines()
>>> rows = summarise(parse(sample))
>>> for r in rows: print(r)
('ask_engine_ms', 'gemini-3.8-flash', 1, 3000, 3000, 3000)
('cold_start_ms', '', 1, 900, 900, 900)
('reply_ms', '', 2, 1200, 2400, 2400)
('tool_ms', 'ask_fleet', 2, 800, 1600, 1600)
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
GROUP_KEY = {"tool_ms": "tool", "ask_engine_ms": "model"}


def parse(lines):
    """Yield (metric, group, ms) for every LIVE_LATENCY line; other lines are skipped."""
    for line in lines:
        if TAG not in line:
            continue
        body = line.split(TAG, 1)[1].lstrip(": ")
        pairs = dict(_PAIR.findall(body))
        for key, value in pairs.items():
            if not key.endswith("_ms"):
                continue
            try:
                ms = int(value)
            except ValueError:
                continue
            yield key, pairs.get(GROUP_KEY.get(key, ""), ""), ms


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


def main(argv=None):
    ap = argparse.ArgumentParser(description="Summarise LIVE_LATENCY lines from a logcat capture.")
    ap.add_argument("logfile", help="logcat capture; '-' reads stdin")
    ap.add_argument("--csv", action="store_true", help="print CSV instead of a table")
    args = ap.parse_args(argv)

    if args.logfile == "-":
        rows = summarise(parse(sys.stdin))
    else:
        with open(args.logfile, encoding="utf-8", errors="replace") as f:
            rows = summarise(parse(f))

    if args.csv:
        w = csv.writer(sys.stdout, lineterminator="\n")
        w.writerow(HEADER)
        w.writerows(rows)
        return 0
    if not rows:
        print("no LIVE_LATENCY lines found")
        return 1
    print("{:<15} {:<24} {:>6} {:>8} {:>8} {:>8}".format(*HEADER))
    for r in rows:
        print("{:<15} {:<24} {:>6} {:>8} {:>8} {:>8}".format(*r))
    return 0


if __name__ == "__main__":
    sys.exit(main())
